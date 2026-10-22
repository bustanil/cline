package bot.cline.jetbrains.ide

import bot.cline.jetbrains.chat.ChatStore
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper

/** Opens the task history in a dialog. Used by the title bar button. */
object HistoryDialog {
    fun show(project: Project, store: ChatStore) {
        lateinit var dialog: DialogWrapper
        val content = HistoryPanel(store, onOpened = { dialog.close(DialogWrapper.OK_EXIT_CODE) })
        dialog = object : DialogWrapper(project, true) {
            init {
                title = "Task History"
                setOKButtonText("Close")
                init()
            }

            override fun createCenterPanel() = content

            override fun createActions() = arrayOf(okAction)
        }
        dialog.show()
    }
}

abstract class TitleAction(text: String, description: String, icon: javax.swing.Icon) : AnAction(text, description, icon), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val project = e.project
        // Both need a running core; before it connects the button is greyed out.
        e.presentation.isEnabled = project != null && ClineProjectService.getInstance(project).store != null
    }

    protected fun storeOf(e: AnActionEvent): ChatStore? = e.project?.let { ClineProjectService.getInstance(it).store }
}

/** The + in the title bar: start a new chat. */
class NewChatTitleAction : TitleAction("New Chat", "Start a new task", AllIcons.General.Add) {
    override fun actionPerformed(e: AnActionEvent) {
        storeOf(e)?.clearTask()
    }
}

/** The clock in the title bar: past tasks. */
class HistoryTitleAction : TitleAction("Task History", "Open a past task", AllIcons.Vcs.History) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        storeOf(e)?.let { HistoryDialog.show(project, it) }
    }
}
