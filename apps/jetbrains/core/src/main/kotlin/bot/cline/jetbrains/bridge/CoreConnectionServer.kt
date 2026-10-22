package bot.cline.jetbrains.bridge

import bot.cline.host.proto.CoreConnectionMessage
import bot.cline.host.proto.CoreConnectionReady
import io.grpc.Metadata
import io.grpc.Server
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.ServerInterceptors
import io.grpc.Status
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import io.grpc.protobuf.services.HealthStatusManager
import io.grpc.stub.StreamObserver
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit

/**
 * The gRPC server that `cline-core` connects back to. It serves the health
 * service the core polls before it starts, and `CoreConnectionService`, whose
 * stream carries the plugin's calls to the core.
 */
class CoreConnectionServer(
    private val token: String,
    private val ide: IdeBridge = object : IdeBridge {},
    private val onConnected: (CoreConnection) -> Unit = {},
) {
    private val health = HealthStatusManager()
    private var server: Server? = null

    val port: Int
        get() = checkNotNull(server) { "server not started" }.port

    fun start(port: Int = 0): CoreConnectionServer {
        val service = ServerInterceptors.intercept(CoreConnectionService.bind(::connect), TokenInterceptor(token))
        server = NettyServerBuilder.forAddress(InetSocketAddress("127.0.0.1", port))
            .addService(health.healthService)
            .addService(service)
            .apply {
                listOf(EnvService(ide).definition(), WorkspaceService(ide).definition(), WindowService(ide).definition(), DiffService(ide).definition())
                    .forEach { addService(ServerInterceptors.intercept(it, TokenInterceptor(token))) }
            }
            .build()
            .start()
        return this
    }

    fun stop() {
        server?.shutdown()?.awaitTermination(5, TimeUnit.SECONDS)
    }

    private fun connect(responses: StreamObserver<CoreConnectionMessage>): StreamObserver<CoreConnectionMessage> {
        val connection = CoreConnection(responses)
        return object : StreamObserver<CoreConnectionMessage> {
            override fun onNext(message: CoreConnectionMessage) {
                if (!connection.authenticated) {
                    if (message.hasHello() && message.hello.token == token) {
                        connection.authenticated = true
                        connection.instanceId = message.hello.instanceId
                        responses.onNext(
                            CoreConnectionMessage.newBuilder().setReady(CoreConnectionReady.getDefaultInstance()).build(),
                        )
                        onConnected(connection)
                    } else {
                        responses.onError(Status.UNAUTHENTICATED.withDescription("expected hello with a valid token").asRuntimeException())
                    }
                    return
                }
                connection.dispatch(message)
            }

            override fun onError(t: Throwable) = connection.close(t)

            override fun onCompleted() {
                connection.close(null)
                responses.onCompleted()
            }
        }
    }

    /** Rejects calls that lack the `cline-hostbridge-token` header. */
    private class TokenInterceptor(private val token: String) : ServerInterceptor {
        override fun <ReqT, RespT> interceptCall(
            call: ServerCall<ReqT, RespT>,
            headers: Metadata,
            next: ServerCallHandler<ReqT, RespT>,
        ): ServerCall.Listener<ReqT> {
            if (call.methodDescriptor.serviceName == "grpc.health.v1.Health") {
                return next.startCall(call, headers)
            }
            if (headers.get(TOKEN_HEADER) != token) {
                call.close(Status.UNAUTHENTICATED.withDescription("missing or invalid host bridge token"), Metadata())
                return object : ServerCall.Listener<ReqT>() {}
            }
            return next.startCall(call, headers)
        }
    }

    companion object {
        val TOKEN_HEADER: Metadata.Key<String> = Metadata.Key.of("cline-hostbridge-token", Metadata.ASCII_STRING_MARSHALLER)
    }
}
