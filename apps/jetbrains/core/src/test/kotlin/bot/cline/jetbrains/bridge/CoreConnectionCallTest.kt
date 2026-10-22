package bot.cline.jetbrains.bridge

import bot.cline.host.proto.CoreConnectionHello
import bot.cline.host.proto.CoreConnectionMessage
import bot.cline.host.proto.CoreConnectionResponse
import com.google.protobuf.ByteString
import io.grpc.CallOptions
import io.grpc.ClientInterceptors
import io.grpc.ManagedChannelBuilder
import io.grpc.Metadata
import io.grpc.stub.ClientCalls
import io.grpc.stub.MetadataUtils
import io.grpc.stub.StreamObserver
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/** Drives [CoreConnection] against a fake core that answers requests by hand. */
class CoreConnectionCallTest {
    private val token = "t"
    private val connection = CompletableFuture<CoreConnection>()
    private val server = CoreConnectionServer(token) { connection.complete(it) }.start()
    private val channel = ManagedChannelBuilder.forAddress("127.0.0.1", server.port).usePlaintext().build()
    private val requests = LinkedBlockingQueue<CoreConnectionMessage>()
    private val toPlugin: StreamObserver<CoreConnectionMessage>

    init {
        val headers = Metadata().apply { put(CoreConnectionServer.TOKEN_HEADER, token) }
        val authed = ClientInterceptors.intercept(channel, MetadataUtils.newAttachHeadersInterceptor(headers))
        toPlugin = ClientCalls.asyncBidiStreamingCall(
            authed.newCall(CoreConnectionService.connectMethod, CallOptions.DEFAULT),
            object : StreamObserver<CoreConnectionMessage> {
                override fun onNext(value: CoreConnectionMessage) { requests.add(value) }
                override fun onError(t: Throwable) {}
                override fun onCompleted() {}
            },
        )
        toPlugin.onNext(
            CoreConnectionMessage.newBuilder()
                .setHello(CoreConnectionHello.newBuilder().setToken(token).setInstanceId("fake"))
                .build(),
        )
        assertNotNull(requests.poll(5, TimeUnit.SECONDS)) // ready
    }

    @AfterTest
    fun tearDown() {
        channel.shutdownNow()
        server.stop()
    }

    private fun nextRequest() = assertNotNull(requests.poll(5, TimeUnit.SECONDS)).request

    private fun reply(build: CoreConnectionResponse.Builder.() -> Unit) {
        toPlugin.onNext(CoreConnectionMessage.newBuilder().setResponse(CoreConnectionResponse.newBuilder().apply(build)).build())
    }

    @Test
    fun `unary call joins chunks into one payload`() {
        val core = connection.get(5, TimeUnit.SECONDS)
        val future = core.call("cline.StateService", "getLatestState", "{}")
        val request = nextRequest()
        assertEquals("cline.StateService", request.service)
        assertEquals("getLatestState", request.method)
        assertEquals(false, request.isStreaming)
        reply { requestId = request.requestId; messageJsonChunk = ByteString.copyFromUtf8("{\"stateJ") }
        reply { requestId = request.requestId; messageJsonChunk = ByteString.copyFromUtf8("son\":\"x\"}"); messageJsonComplete = true; completed = true }
        assertEquals("{\"stateJson\":\"x\"}", future.get(5, TimeUnit.SECONDS))
    }

    @Test
    fun `unary call accepts a whole message_json`() {
        val core = connection.get(5, TimeUnit.SECONDS)
        val future = core.call("cline.TaskService", "cancelTask")
        val request = nextRequest()
        reply { requestId = request.requestId; messageJson = "{\"a\":1}"; completed = true }
        assertEquals("{\"a\":1}", future.get(5, TimeUnit.SECONDS))
    }

    @Test
    fun `error response fails the call`() {
        val core = connection.get(5, TimeUnit.SECONDS)
        val future = core.call("cline.TaskService", "newTask", "{}")
        val request = nextRequest()
        reply { requestId = request.requestId; error = "boom"; completed = true }
        val failure = assertFailsWith<java.util.concurrent.ExecutionException> { future.get(5, TimeUnit.SECONDS) }
        assertEquals("boom", failure.cause?.message)
    }

    @Test
    fun `streaming call delivers payloads in order and cancel is sent`() {
        val core = connection.get(5, TimeUnit.SECONDS)
        val seen = LinkedBlockingQueue<String>()
        val subscription = core.subscribe("cline.StateService", "subscribeToState", "{}", onMessage = { seen.add(it) })
        val request = nextRequest()
        assertEquals(true, request.isStreaming)
        reply { requestId = request.requestId; messageJson = "{\"n\":1}" }
        reply { requestId = request.requestId; messageJsonChunk = ByteString.copyFromUtf8("{\"n\":2}"); messageJsonComplete = true }
        assertEquals("{\"n\":1}", seen.poll(5, TimeUnit.SECONDS))
        assertEquals("{\"n\":2}", seen.poll(5, TimeUnit.SECONDS))
        subscription.cancel()
        val cancel = assertNotNull(requests.poll(5, TimeUnit.SECONDS))
        assertEquals(request.requestId, cancel.cancel.requestId)
    }
}
