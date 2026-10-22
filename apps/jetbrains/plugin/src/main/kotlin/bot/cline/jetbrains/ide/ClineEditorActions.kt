package bot.cline.jetbrains.ide

import bot.cline.jetbrains.chat.EditorCommand
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.wm.ToolWindowManager

/** Sends the selected code, or the caret's line, to the agent. One subclass per [EditorCommand]. */
abstract class ClineEditorAction(private val command: EditorCommand) : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        // Add, explain and improve need code selected; fix may use the caret's line.
        e.presentation.isEnabledAndVisible = e.project != null && editor != null &&
            (editor.selectionModel.hasSelection() || command == EditorCommand.FIX)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        send(project, editor, command)
    }

    companion object {
        /** Builds the context, shows the Cline window, waits for the core, and sends the command. */
        fun send(project: Project, editor: Editor, command: EditorCommand) {
            val context = EditorContextBuilder.build(project, editor) ?: return notify(project, "Select some code first.")
            ToolWindowManager.getInstance(project).getToolWindow(OpenClineAction.TOOL_WINDOW_ID)?.show()
            ClineProjectService.getInstance(project).awaitStore().whenComplete { store, error ->
                if (error != null || store == null) {
                    notify(project, "Cline is not running. Check Settings > Tools > Cline Native.")
                } else {
                    store.runEditorCommand(command, context).whenComplete { _, failure ->
                        if (failure != null) notify(project, "Cline could not take that: " + (failure.cause?.message ?: failure.message))
                    }
                }
            }
        }

        private fun notify(project: Project, text: String) {
            ApplicationManager.getApplication().invokeLater {
                NotificationGroupManager.getInstance().getNotificationGroup("Cline Native")
                    .createNotification("Cline", text, NotificationType.WARNING).notify(project)
            }
        }
    }
}

class AddToClineAction : ClineEditorAction(EditorCommand.ADD_TO_CHAT)

class FixWithClineAction : ClineEditorAction(EditorCommand.FIX)

class ExplainWithClineAction : ClineEditorAction(EditorCommand.EXPLAIN)

class ImproveWithClineAction : ClineEditorAction(EditorCommand.IMPROVE)
