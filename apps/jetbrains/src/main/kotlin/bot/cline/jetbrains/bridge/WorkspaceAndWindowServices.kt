package bot.cline.jetbrains.bridge

import bot.cline.host.proto.GetActiveEditorRequest
import bot.cline.host.proto.GetActiveEditorResponse
import bot.cline.host.proto.GetDiagnosticsRequest
import bot.cline.host.proto.GetDiagnosticsResponse
import bot.cline.host.proto.GetOpenTabsRequest
import bot.cline.host.proto.GetOpenTabsResponse
import bot.cline.host.proto.GetVisibleTabsRequest
import bot.cline.host.proto.GetVisibleTabsResponse
import bot.cline.host.proto.GetWorkspacePathsRequest
import bot.cline.host.proto.GetWorkspacePathsResponse
import bot.cline.host.proto.SaveOpenDocumentIfDirtyRequest
import bot.cline.host.proto.SaveOpenDocumentIfDirtyResponse
import bot.cline.host.proto.SelectedResponse
import bot.cline.host.proto.ShowMessageRequest
import io.grpc.ServerServiceDefinition

/** What the core asked the IDE to tell the user. */
data class UserMessage(val type: String, val text: String, val items: List<String>, val detail: String?)

/**
 * `host.WorkspaceService`. Only the calls the core makes on startup and for a
 * normal task are served; the rest answer UNIMPLEMENTED until they are wired.
 */
class WorkspaceService(private val workspacePaths: () -> List<String>) {
    fun definition(): ServerServiceDefinition =
        HostService("host.WorkspaceService")
            .unary("getWorkspacePaths", GetWorkspacePathsRequest.getDefaultInstance(), GetWorkspacePathsResponse.getDefaultInstance()) {
                GetWorkspacePathsResponse.newBuilder().addAllPaths(workspacePaths()).build()
            }
            .unary("getDiagnostics", GetDiagnosticsRequest.getDefaultInstance(), GetDiagnosticsResponse.getDefaultInstance()) {
                GetDiagnosticsResponse.getDefaultInstance()
            }
            .unary(
                "saveOpenDocumentIfDirty",
                SaveOpenDocumentIfDirtyRequest.getDefaultInstance(),
                SaveOpenDocumentIfDirtyResponse.getDefaultInstance(),
            ) { SaveOpenDocumentIfDirtyResponse.newBuilder().setWasSaved(false).build() }
            .build()
}

/**
 * `host.WindowService`. [onMessage] shows the message and returns the chosen
 * option, or null if the user dismissed it.
 */
class WindowService(private val onMessage: (UserMessage) -> String? = { null }) {
    fun definition(): ServerServiceDefinition =
        HostService("host.WindowService")
            .unary("showMessage", ShowMessageRequest.getDefaultInstance(), SelectedResponse.getDefaultInstance()) { request ->
                val options = if (request.hasOptions()) request.options else null
                val chosen = onMessage(
                    UserMessage(
                        type = request.type.name,
                        text = request.message,
                        items = options?.itemsList ?: emptyList(),
                        detail = options?.takeIf { it.hasDetail() }?.detail,
                    ),
                )
                SelectedResponse.newBuilder().apply { if (chosen != null) selectedOption = chosen }.build()
            }
            .unary("getOpenTabs", GetOpenTabsRequest.getDefaultInstance(), GetOpenTabsResponse.getDefaultInstance()) {
                GetOpenTabsResponse.getDefaultInstance()
            }
            .unary("getVisibleTabs", GetVisibleTabsRequest.getDefaultInstance(), GetVisibleTabsResponse.getDefaultInstance()) {
                GetVisibleTabsResponse.getDefaultInstance()
            }
            .unary("getActiveEditor", GetActiveEditorRequest.getDefaultInstance(), GetActiveEditorResponse.getDefaultInstance()) {
                GetActiveEditorResponse.getDefaultInstance()
            }
            .build()
}
