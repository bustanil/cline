package bot.cline.jetbrains.ide

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.wm.ToolWindowManager

/** The toolbar button and Tools menu entry that opens the Cline tool window. */
class OpenClineAction : AnAction(), DumbAware {
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val window = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return
        // Open it, or close it if it is already the one in front, as a toolbar toggle does.
        if (window.isVisible && window.isActive) window.hide() else window.activate(null)
    }

    companion object {
        const val TOOL_WINDOW_ID = "Cline"
    }
}
