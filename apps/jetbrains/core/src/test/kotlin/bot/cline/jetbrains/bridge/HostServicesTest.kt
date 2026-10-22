package bot.cline.jetbrains.bridge

import bot.cline.host.proto.ContentDiff
import bot.cline.host.proto.GetWorkspacePathsRequest
import bot.cline.host.proto.GetWorkspacePathsResponse
import bot.cline.host.proto.LineRange
import bot.cline.host.proto.OpenMultiFileDiffRequest
import bot.cline.host.proto.OpenMultiFileDiffResponse
import bot.cline.host.proto.ShowTextDocumentOptions
import bot.cline.host.proto.ShowTextDocumentRequest
import bot.cline.host.proto.TextEditorInfo
import com.google.protobuf.Message
import io.grpc.CallOptions
import io.grpc.ClientInterceptors
import io.grpc.ManagedChannelBuilder
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.protobuf.ProtoUtils
import io.grpc.stub.ClientCalls
import io.grpc.stub.MetadataUtils
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Calls the host services the way the core does and checks what reaches the [IdeBridge]. */
class HostServicesTest {
    private class RecordingIde : IdeBridge {
        val opened = mutableListOf<Triple<String, Int?, Int?>>()
        val diffs = mutableListOf<Pair<String?, List<FileDiff>>>()
        override fun workspacePaths() = listOf("/ws/a", "/ws/b")
        override fun showTextDocument(path: String, startLine: Int?, endLine: Int?) { opened += Triple(path, startLine, endLine) }
        override fun openMultiFileDiff(title: String?, diffs: List<FileDiff>) { this.diffs += title to diffs }
    }

    private val ide = RecordingIde()
    private val server = CoreConnectionServer("tok", ide).start()
    private val channel = ClientInterceptors.intercept(
        ManagedChannelBuilder.forAddress("127.0.0.1", server.port).usePlaintext().build(),
        MetadataUtils.newAttachHeadersInterceptor(Metadata().apply { put(CoreConnectionServer.TOKEN_HEADER, "tok") }),
    )

    @AfterTest
    fun tearDown() = server.stop()

    private fun <Req : Message, Resp : Message> call(service: String, method: String, request: Req, response: Resp): Resp {
        val descriptor = MethodDescriptor.newBuilder<Req, Resp>()
            .setType(MethodDescriptor.MethodType.UNARY)
            .setFullMethodName(MethodDescriptor.generateFullMethodName(service, method))
            .setRequestMarshaller(ProtoUtils.marshaller(request))
            .setResponseMarshaller(ProtoUtils.marshaller(response))
            .build()
        @Suppress("UNCHECKED_CAST")
        return ClientCalls.blockingUnaryCall(channel, descriptor, CallOptions.DEFAULT, request) as Resp
    }

    @Test
    fun `workspace paths come from the bridge`() {
        val response = call("host.WorkspaceService", "getWorkspacePaths", GetWorkspacePathsRequest.getDefaultInstance(), GetWorkspacePathsResponse.getDefaultInstance())
        assertEquals(listOf("/ws/a", "/ws/b"), response.pathsList)
    }

    @Test
    fun `showTextDocument passes the selection lines`() {
        val request = ShowTextDocumentRequest.newBuilder().setPath("/ws/a/X.kt").setOptions(
            ShowTextDocumentOptions.newBuilder().setSelection(LineRange.newBuilder().setStartLine(12).setEndLine(20)),
        ).build()
        val info = call("host.WindowService", "showTextDocument", request, TextEditorInfo.getDefaultInstance())
        assertEquals("/ws/a/X.kt", info.documentPath)
        assertEquals(Triple("/ws/a/X.kt", 12, 20), ide.opened.single())
    }

    @Test
    fun `showTextDocument without a selection opens the file only`() {
        call("host.WindowService", "showTextDocument", ShowTextDocumentRequest.newBuilder().setPath("/p").build(), TextEditorInfo.getDefaultInstance())
        assertEquals(Triple("/p", null, null), ide.opened.single())
    }

    @Test
    fun `a start line without an end line selects from the start`() {
        val request = ShowTextDocumentRequest.newBuilder().setPath("/p").setOptions(
            ShowTextDocumentOptions.newBuilder().setSelection(LineRange.newBuilder().setStartLine(7)),
        ).build()
        call("host.WindowService", "showTextDocument", request, TextEditorInfo.getDefaultInstance())
        assertEquals(Triple("/p", 7, null), ide.opened.single())
    }

    @Test
    fun `openMultiFileDiff delivers every file`() {
        val request = OpenMultiFileDiffRequest.newBuilder().setTitle("Changes")
            .addDiffs(ContentDiff.newBuilder().setFilePath("/a").setLeftContent("1").setRightContent("2"))
            .addDiffs(ContentDiff.newBuilder().setFilePath("/b").setLeftContent("").setRightContent("x"))
            .build()
        call("host.DiffService", "openMultiFileDiff", request, OpenMultiFileDiffResponse.getDefaultInstance())
        val (title, diffs) = ide.diffs.single()
        assertEquals("Changes", title)
        assertEquals(listOf(FileDiff("/a", "1", "2"), FileDiff("/b", "", "x")), diffs)
    }

    @Test
    fun `an unsupported bridge call answers UNIMPLEMENTED`() {
        val bare = CoreConnectionServer("tok").start()
        val ch = ClientInterceptors.intercept(
            ManagedChannelBuilder.forAddress("127.0.0.1", bare.port).usePlaintext().build(),
            MetadataUtils.newAttachHeadersInterceptor(Metadata().apply { put(CoreConnectionServer.TOKEN_HEADER, "tok") }),
        )
        val descriptor = MethodDescriptor.newBuilder<ShowTextDocumentRequest, TextEditorInfo>()
            .setType(MethodDescriptor.MethodType.UNARY)
            .setFullMethodName(MethodDescriptor.generateFullMethodName("host.WindowService", "showTextDocument"))
            .setRequestMarshaller(ProtoUtils.marshaller(ShowTextDocumentRequest.getDefaultInstance()))
            .setResponseMarshaller(ProtoUtils.marshaller(TextEditorInfo.getDefaultInstance()))
            .build()
        val failure = assertFailsWith<StatusRuntimeException> {
            ClientCalls.blockingUnaryCall(ch, descriptor, CallOptions.DEFAULT, ShowTextDocumentRequest.newBuilder().setPath("/p").build())
        }
        assertEquals(Status.Code.UNIMPLEMENTED, failure.status.code)
        bare.stop()
    }
}
