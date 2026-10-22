package bot.cline.jetbrains.bridge

import bot.cline.host.proto.CoreConnectionHello
import bot.cline.host.proto.CoreConnectionMessage
import io.grpc.CallOptions
import io.grpc.ClientInterceptors
import io.grpc.stub.ClientCalls
import io.grpc.ManagedChannelBuilder
import io.grpc.Metadata
import io.grpc.StatusRuntimeException
import io.grpc.health.v1.HealthCheckRequest
import io.grpc.health.v1.HealthCheckResponse
import io.grpc.health.v1.HealthGrpc
import io.grpc.stub.MetadataUtils
import io.grpc.stub.StreamObserver
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CoreConnectionServerTest {
    private val token = "secret"
    private var connected: CoreConnection? = null
    private val server = CoreConnectionServer(token) { connected = it }.start()
    private val channel = ManagedChannelBuilder.forAddress("127.0.0.1", server.port).usePlaintext().build()

    @AfterTest
    fun tearDown() {
        channel.shutdownNow()
        server.stop()
    }

    private fun connectStub(withToken: Boolean) = object {
        fun connect(responses: StreamObserver<CoreConnectionMessage>): StreamObserver<CoreConnectionMessage> {
            val target = if (!withToken) channel else {
                val headers = Metadata().apply { put(CoreConnectionServer.TOKEN_HEADER, token) }
                ClientInterceptors.intercept(channel, MetadataUtils.newAttachHeadersInterceptor(headers))
            }
            return ClientCalls.asyncBidiStreamingCall(
                target.newCall(CoreConnectionService.connectMethod, CallOptions.DEFAULT),
                responses,
            )
        }
    }

    private fun hello(tokenValue: String) = CoreConnectionMessage.newBuilder()
        .setHello(CoreConnectionHello.newBuilder().setToken(tokenValue).setInstanceId("test-1"))
        .build()

    @Test
    fun `health service reports SERVING without a token`() {
        val status = HealthGrpc.newBlockingStub(channel).check(HealthCheckRequest.getDefaultInstance()).status
        assertEquals(HealthCheckResponse.ServingStatus.SERVING, status)
    }

    @Test
    fun `valid hello gets ready`() {
        val replies = LinkedBlockingQueue<CoreConnectionMessage>()
        val out = connectStub(true).connect(queueObserver(replies))
        out.onNext(hello(token))
        val reply = assertNotNull(replies.poll(5, TimeUnit.SECONDS))
        assertTrue(reply.hasReady())
        assertEquals("test-1", connected?.instanceId)
    }

    @Test
    fun `call without the header token is rejected`() {
        val errors = LinkedBlockingQueue<Throwable>()
        val out = connectStub(false).connect(object : StreamObserver<CoreConnectionMessage> {
            override fun onNext(value: CoreConnectionMessage) {}
            override fun onError(t: Throwable) { errors.add(t) }
            override fun onCompleted() {}
        })
        out.onNext(hello(token))
        val error = assertNotNull(errors.poll(5, TimeUnit.SECONDS))
        assertEquals(io.grpc.Status.Code.UNAUTHENTICATED, (error as StatusRuntimeException).status.code)
    }

    @Test
    fun `hello with the wrong token is rejected`() {
        val errors = LinkedBlockingQueue<Throwable>()
        val out = connectStub(true).connect(object : StreamObserver<CoreConnectionMessage> {
            override fun onNext(value: CoreConnectionMessage) {}
            override fun onError(t: Throwable) { errors.add(t) }
            override fun onCompleted() {}
        })
        out.onNext(hello("wrong"))
        assertNotNull(errors.poll(5, TimeUnit.SECONDS))
    }

    private fun queueObserver(queue: LinkedBlockingQueue<CoreConnectionMessage>) = object : StreamObserver<CoreConnectionMessage> {
        override fun onNext(value: CoreConnectionMessage) { queue.add(value) }
        override fun onError(t: Throwable) {}
        override fun onCompleted() {}
    }
}
