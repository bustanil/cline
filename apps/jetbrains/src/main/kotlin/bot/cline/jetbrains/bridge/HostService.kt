package bot.cline.jetbrains.bridge

import com.google.protobuf.Message
import io.grpc.MethodDescriptor
import io.grpc.ServerServiceDefinition
import io.grpc.ServiceDescriptor
import io.grpc.Status
import io.grpc.protobuf.ProtoUtils
import io.grpc.stub.ServerCalls
import io.grpc.stub.StreamObserver

/**
 * Builds a `host.*` gRPC service definition without the grpc-java code
 * generator (see [CoreConnectionService] for why). Each method is declared
 * with its request and response default instances.
 */
class HostService(private val name: String) {
    private val descriptors = mutableListOf<MethodDescriptor<*, *>>()
    private val bindings = mutableListOf<(ServerServiceDefinition.Builder) -> Unit>()

    fun <Req : Message, Resp : Message> unary(
        method: String,
        request: Req,
        response: Resp,
        handler: (Req) -> Resp,
    ): HostService {
        val descriptor = descriptor<Req, Resp>(method, MethodDescriptor.MethodType.UNARY, request, response)
        descriptors += descriptor
        bindings += { builder ->
            builder.addMethod(
                descriptor,
                ServerCalls.asyncUnaryCall { req: Req, out: StreamObserver<Resp> ->
                    try {
                        out.onNext(handler(req))
                        out.onCompleted()
                    } catch (e: UnsupportedOperationException) {
                        out.onError(Status.UNIMPLEMENTED.withDescription(e.message).asRuntimeException())
                    } catch (e: Exception) {
                        out.onError(Status.INTERNAL.withDescription(e.message).withCause(e).asRuntimeException())
                    }
                },
            )
        }
        return this
    }

    /** A server stream. The handler gets the observer and returns a cancel callback. */
    fun <Req : Message, Resp : Message> serverStreaming(
        method: String,
        request: Req,
        response: Resp,
        handler: (Req, StreamObserver<Resp>) -> Unit,
    ): HostService {
        val descriptor = descriptor<Req, Resp>(method, MethodDescriptor.MethodType.SERVER_STREAMING, request, response)
        descriptors += descriptor
        bindings += { builder ->
            builder.addMethod(descriptor, ServerCalls.asyncServerStreamingCall<Req, Resp> { req, out -> handler(req, out) })
        }
        return this
    }

    fun build(): ServerServiceDefinition {
        val serviceDescriptor = ServiceDescriptor.newBuilder(name).apply { descriptors.forEach { addMethod(it) } }.build()
        val builder = ServerServiceDefinition.builder(serviceDescriptor)
        bindings.forEach { it(builder) }
        return builder.build()
    }

    @Suppress("UNCHECKED_CAST")
    private fun <Req : Message, Resp : Message> descriptor(
        method: String,
        type: MethodDescriptor.MethodType,
        request: Req,
        response: Resp,
    ): MethodDescriptor<Req, Resp> =
        MethodDescriptor.newBuilder<Req, Resp>()
            .setType(type)
            .setFullMethodName(MethodDescriptor.generateFullMethodName(name, method))
            .setRequestMarshaller(ProtoUtils.marshaller(request as Req))
            .setResponseMarshaller(ProtoUtils.marshaller(response as Resp))
            .build()
}
