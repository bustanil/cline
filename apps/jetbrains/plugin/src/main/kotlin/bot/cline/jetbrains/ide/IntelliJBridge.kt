package bot.cline.jetbrains.ide

import bot.cline.jetbrains.bridge.FileDiff
import bot.cline.jetbrains.bridge.IdeBridge
import bot.cline.jetbrains.bridge.UserMessage
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.contents.DiffContent
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.util.concurrent.atomic.AtomicReference

/**
 * The IntelliJ Platform side of [IdeBridge]. The core calls it from gRPC
 * threads, so every UI or document touch hops to the event dispatch thread
 * and waits for the result: the core expects the call to have happened when
 * it gets its reply.
 */
class IntelliJBridge(private val project: Project) : IdeBridge {
    override fun workspacePaths(): List<String> = runReadAction {
        ProjectRootManager.getInstance(project).contentRoots.map { it.path }.ifEmpty { listOfNotNull(project.basePath) }
    }

    override fun hostVersion(): Pair<String, String> =
        ApplicationInfo.getInstance().let { it.versionName to it.fullVersion }

    override fun showMessage(message: UserMessage): String? {
        if (message.items.isEmpty()) {
            val type = when (message.type) {
                "ERROR" -> NotificationType.ERROR
                "WARNING" -> NotificationType.WARNING
                else -> NotificationType.INFORMATION
            }
            NotificationGroupManager.getInstance().getNotificationGroup("Cline")
                .createNotification("Cline", listOfNotNull(message.text, message.detail).joinToString("\n"), type)
                .notify(project)
            return null
        }
        // A message with options waits for the user's choice.
        val chosen = AtomicReference<String?>()
        onEdtAndWait {
            val index = Messages.showDialog(
                project,
                listOfNotNull(message.text, message.detail).joinToString("\n\n"),
                "Cline",
                message.items.toTypedArray(),
                0,
                when (message.type) {
                    "ERROR" -> Messages.getErrorIcon()
                    "WARNING" -> Messages.getWarningIcon()
                    else -> Messages.getInformationIcon()
                },
            )
            chosen.set(message.items.getOrNull(index))
        }
        return chosen.get()
    }

    override fun showTextDocument(path: String, startLine: Int?, endLine: Int?) {
        val file = findFile(path) ?: throw IllegalArgumentException("File not found: $path")
        onEdtAndWait { openAt(file, startLine, endLine) }
    }

    /** Lines are 1-based on the wire; the editor is 0-based. */
    private fun openAt(file: VirtualFile, startLine: Int?, endLine: Int?) {
        val manager = FileEditorManager.getInstance(project)
        if (startLine == null) {
            manager.openFile(file, true)
            return
        }
        val first = (startLine - 1).coerceAtLeast(0)
        val editor = manager.openTextEditor(OpenFileDescriptor(project, file, first, 0), true) ?: return
        val document = editor.document
        if (document.lineCount == 0) return
        val from = first.coerceAtMost(document.lineCount - 1)
        val to = ((endLine ?: startLine) - 1).coerceIn(from, document.lineCount - 1)
        editor.selectionModel.setSelection(document.getLineStartOffset(from), document.getLineEndOffset(to))
        editor.caretModel.moveToLogicalPosition(com.intellij.openapi.editor.LogicalPosition(from, 0))
        editor.scrollingModel.scrollToCaret(com.intellij.openapi.editor.ScrollType.CENTER)
    }

    override fun openTabs(): List<String> = runReadAction { FileEditorManager.getInstance(project).openFiles.map { it.path } }

    override fun visibleTabs(): List<String> = runReadAction { FileEditorManager.getInstance(project).selectedFiles.map { it.path } }

    override fun activeEditor(): String? = runReadAction { FileEditorManager.getInstance(project).selectedFiles.firstOrNull()?.path }

    override fun saveDocumentIfDirty(path: String): Boolean {
        val file = findFile(path) ?: return false
        val manager = FileDocumentManager.getInstance()
        val document = manager.getCachedDocument(file) ?: return false
        if (!manager.isDocumentUnsaved(document)) return false
        onEdtAndWait { manager.saveDocument(document) }
        return true
    }

    override fun openMultiFileDiff(title: String?, diffs: List<FileDiff>) {
        if (diffs.isEmpty()) return
        onEdtAndWait {
            val chain = buildDiffChain(diffs)
            title?.let { chain.putUserData(DIFF_TITLE, it) }
            DiffManager.getInstance().showDiff(project, chain, com.intellij.diff.DiffDialogHints.DEFAULT)
        }
    }

    /**
     * One request per file, so the diff window lists them and steps through
     * them. When the file on disk still matches the right side, the right side
     * is the file itself: the user can edit it, and revert single changes,
     * inside the diff.
     */
    internal fun buildDiffChain(diffs: List<FileDiff>): SimpleDiffRequestChain {
        val factory = DiffContentFactory.getInstance()
        val requests = diffs.map { diff ->
            val file = findFile(diff.filePath)
            val type = FileTypeManager.getInstance().getFileTypeByFileName(diff.filePath.substringAfterLast('/'))
            val left: DiffContent = factory.create(project, diff.left, type)
            val right: DiffContent =
                if (file != null && diskText(file) == diff.right) factory.create(project, file) else factory.create(project, diff.right, type)
            SimpleDiffRequest(diff.filePath.substringAfterLast('/'), left, right, "Before", "After")
        }
        return SimpleDiffRequestChain(requests)
    }

    override fun clipboardRead(): String =
        CopyPasteManager.getInstance().getContents<String>(DataFlavor.stringFlavor) ?: ""

    override fun clipboardWrite(text: String) {
        CopyPasteManager.getInstance().setContents(StringSelection(text))
    }

    override fun openExternal(url: String) {
        BrowserUtil.browse(url)
    }

    private fun findFile(path: String): VirtualFile? =
        LocalFileSystem.getInstance().let { it.findFileByPath(path) ?: it.refreshAndFindFileByPath(path) }

    private fun diskText(file: VirtualFile): String? = runCatching { String(file.contentsToByteArray(), file.charset) }.getOrNull()

    private fun onEdtAndWait(action: () -> Unit) {
        val app = ApplicationManager.getApplication()
        if (app.isDispatchThread) action() else app.invokeAndWait(action, ModalityState.defaultModalityState())
    }

    companion object {
        private val DIFF_TITLE = com.intellij.openapi.util.Key.create<String>("cline.diff.title")
    }
}
