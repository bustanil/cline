package bot.cline.jetbrains.bridge

import bot.cline.host.proto.CoreConnectionMessage
import io.grpc.MethodDescriptor
import io.grpc.ServerServiceDefinition
import io.grpc.ServiceDescriptor
import io.grpc.protobuf.ProtoUtils
import io.grpc.stub.ServerCalls
import io.grpc.stub.StreamObserver

/**
 * `host.CoreConnectionService`, defined by hand. The gRPC code generator's
 * macOS arm64 binary is x86_64 and needs Rosetta, so the build generates only
 * the message classes and this file supplies the one bidirectional method.
 */
object CoreConnectionService {
    const val SERVICE_NAME = "host.CoreConnectionService"

    val connectMethod: MethodDescriptor<CoreConnectionMessage, CoreConnectionMessage> =
        MethodDescriptor.newBuilder<CoreConnectionMessage, CoreConnectionMessage>()
            .setType(MethodDescriptor.MethodType.BIDI_STREAMING)
            .setFullMethodName(MethodDescriptor.generateFullMethodName(SERVICE_NAME, "connect"))
            .setRequestMarshaller(ProtoUtils.marshaller(CoreConnectionMessage.getDefaultInstance()))
            .setResponseMarshaller(ProtoUtils.marshaller(CoreConnectionMessage.getDefaultInstance()))
            .build()

    fun bind(connect: (StreamObserver<CoreConnectionMessage>) -> StreamObserver<CoreConnectionMessage>): ServerServiceDefinition =
        ServerServiceDefinition.builder(ServiceDescriptor.newBuilder(SERVICE_NAME).addMethod(connectMethod).build())
            .addMethod(connectMethod, ServerCalls.asyncBidiStreamingCall(connect))
            .build()
}
