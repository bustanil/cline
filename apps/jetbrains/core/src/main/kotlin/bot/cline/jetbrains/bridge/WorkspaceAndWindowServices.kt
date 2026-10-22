package bot.cline.jetbrains.bridge

import bot.cline.host.proto.CloseAllDiffsRequest
import bot.cline.host.proto.CloseAllDiffsResponse
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
import bot.cline.host.proto.OpenFileRequest
import bot.cline.host.proto.OpenFileResponse
import bot.cline.host.proto.OpenMultiFileDiffRequest
import bot.cline.host.proto.OpenMultiFileDiffResponse
import bot.cline.host.proto.SaveOpenDocumentIfDirtyRequest
import bot.cline.host.proto.SaveOpenDocumentIfDirtyResponse
import bot.cline.host.proto.SelectedResponse
import bot.cline.host.proto.ShowMessageRequest
import bot.cline.host.proto.ShowTextDocumentRequest
import bot.cline.host.proto.TextEditorInfo
import io.grpc.ServerServiceDefinition

/**
 * `host.WorkspaceService`. Only the calls the core makes on startup and for a
 * normal task are served; the rest answer UNIMPLEMENTED until they are wired.
 */
class WorkspaceService(private val ide: IdeBridge) {
    fun definition(): ServerServiceDefinition =
        HostService("host.WorkspaceService")
            .unary("getWorkspacePaths", GetWorkspacePathsRequest.getDefaultInstance(), GetWorkspacePathsResponse.getDefaultInstance()) {
                GetWorkspacePathsResponse.newBuilder().addAllPaths(ide.workspacePaths()).build()
            }
            .unary("getDiagnostics", GetDiagnosticsRequest.getDefaultInstance(), GetDiagnosticsResponse.getDefaultInstance()) {
                GetDiagnosticsResponse.getDefaultInstance()
            }
            .unary(
                "saveOpenDocumentIfDirty",
                SaveOpenDocumentIfDirtyRequest.getDefaultInstance(),
                SaveOpenDocumentIfDirtyResponse.getDefaultInstance(),
            ) { request ->
                SaveOpenDocumentIfDirtyResponse.newBuilder()
                    .setWasSaved(request.hasFilePath() && ide.saveDocumentIfDirty(request.filePath))
                    .build()
            }
            .build()
}

/** `host.WindowService`: messages, opening files at a line, and editor tabs. */
class WindowService(private val ide: IdeBridge) {
    fun definition(): ServerServiceDefinition =
        HostService("host.WindowService")
            .unary("showMessage", ShowMessageRequest.getDefaultInstance(), SelectedResponse.getDefaultInstance()) { request ->
                val options = if (request.hasOptions()) request.options else null
                val chosen = ide.showMessage(
                    UserMessage(
                        type = request.type.name,
                        text = request.message,
                        items = options?.itemsList ?: emptyList(),
                        detail = options?.takeIf { it.hasDetail() }?.detail,
                    ),
                )
                SelectedResponse.newBuilder().apply { if (chosen != null) selectedOption = chosen }.build()
            }
            .unary("showTextDocument", ShowTextDocumentRequest.getDefaultInstance(), TextEditorInfo.getDefaultInstance()) { request ->
                val selection = if (request.hasOptions() && request.options.hasSelection()) request.options.selection else null
                ide.showTextDocument(
                    request.path,
                    selection?.startLine,
                    selection?.takeIf { it.hasEndLine() }?.endLine,
                )
                TextEditorInfo.newBuilder().setDocumentPath(request.path).setIsActive(true).build()
            }
            .unary("openFile", OpenFileRequest.getDefaultInstance(), OpenFileResponse.getDefaultInstance()) { request ->
                ide.showTextDocument(request.filePath)
                OpenFileResponse.getDefaultInstance()
            }
            .unary("getOpenTabs", GetOpenTabsRequest.getDefaultInstance(), GetOpenTabsResponse.getDefaultInstance()) {
                GetOpenTabsResponse.newBuilder().addAllPaths(ide.openTabs()).build()
            }
            .unary("getVisibleTabs", GetVisibleTabsRequest.getDefaultInstance(), GetVisibleTabsResponse.getDefaultInstance()) {
                GetVisibleTabsResponse.newBuilder().addAllPaths(ide.visibleTabs()).build()
            }
            .unary("getActiveEditor", GetActiveEditorRequest.getDefaultInstance(), GetActiveEditorResponse.getDefaultInstance()) {
                GetActiveEditorResponse.newBuilder().apply { ide.activeEditor()?.let { filePath = it } }.build()
            }
            .build()
}

/**
 * `host.DiffService`. This build of the core uses only the multi-file diff and
 * closing diffs; the single-file calls answer UNIMPLEMENTED.
 */
class DiffService(private val ide: IdeBridge) {
    fun definition(): ServerServiceDefinition =
        HostService("host.DiffService")
            .unary("openMultiFileDiff", OpenMultiFileDiffRequest.getDefaultInstance(), OpenMultiFileDiffResponse.getDefaultInstance()) { request ->
                ide.openMultiFileDiff(
                    request.takeIf { it.hasTitle() }?.title,
                    request.diffsList.map { FileDiff(it.filePath, it.leftContent, it.rightContent) },
                )
                OpenMultiFileDiffResponse.getDefaultInstance()
            }
            .unary("closeAllDiffs", CloseAllDiffsRequest.getDefaultInstance(), CloseAllDiffsResponse.getDefaultInstance()) {
                ide.closeAllDiffs()
                CloseAllDiffsResponse.getDefaultInstance()
            }
            .build()
}
