package bot.cline.jetbrains.ide

import bot.cline.jetbrains.chat.ChangedFile
import bot.cline.jetbrains.chat.ChatStore
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.project.Project
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel

/**
 * The files the agent changed in its last run, with open, diff and revert for
 * each. It is hidden while the list is empty. The core does not push this
 * list, so [refresh] asks for it; the chat calls that when a turn ends.
 */
class ChangedFilesPanel(
    private val project: Project,
    private val store: () -> ChatStore?,
    /** Asks before a revert; the answer is true to go ahead. Replaced in tests. */
    private val confirmRevert: (ChangedFile) -> Boolean = ::askBeforeRevert,
) : JPanel(BorderLayout()) {
    private val model = DefaultListModel<ChangedFile>()
    private val list = JBList(model).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        visibleRowCount = 5
        cellRenderer = Renderer()
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) viewSelected()
            }
        })
        addListSelectionListener { updateButtons() }
    }
    private val summary = JBLabel()
    private val viewAll = JButton("View all changes").apply { addActionListener { store()?.viewAllChanges() } }
    private val open = JButton("Open").apply { addActionListener { openSelected() } }
    private val diff = JButton("Diff").apply { addActionListener { viewSelected() } }
    private val revert = JButton("Revert").apply { addActionListener { revertSelected() } }

    init {
        border = JBUI.Borders.empty(4, 6)
        val header = JPanel(BorderLayout()).apply {
            add(summary, BorderLayout.CENTER)
            add(viewAll, BorderLayout.EAST)
        }
        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply { add(open); add(diff); add(revert) }
        add(header, BorderLayout.NORTH)
        add(JBScrollPane(list), BorderLayout.CENTER)
        add(buttons, BorderLayout.SOUTH)
        isVisible = false
    }

    val files: List<ChangedFile> get() = model.elements().toList()

    /** Asks the core for the current list and redraws. */
    fun refresh() {
        val current = store() ?: return setFiles(emptyList())
        current.changedFiles().whenComplete { found, error ->
            ApplicationManager.getApplication().invokeLater({ setFiles(if (error != null) emptyList() else found) }, ModalityState.any())
        }
    }

    private fun setFiles(files: List<ChangedFile>) {
        val selected = list.selectedValue?.path
        model.clear()
        files.forEach(model::addElement)
        files.indexOfFirst { it.path == selected }.takeIf { it >= 0 }?.let { list.selectedIndex = it }
        val added = files.sumOf { it.addedLines }
        val removed = files.sumOf { it.removedLines }
        summary.text = "${files.size} ${if (files.size == 1) "file" else "files"} changed  +$added −$removed"
        isVisible = files.isNotEmpty()
        updateButtons()
        revalidate()
        repaint()
    }

    private fun updateButtons() {
        val file = list.selectedValue
        open.isEnabled = file != null && file.status != ChangedFile.Status.DELETED
        diff.isEnabled = file != null
        revert.isEnabled = file != null
    }

    internal fun select(path: String) {
        list.setSelectedValue(files.first { it.path == path }, false)
    }

    internal fun openSelected() {
        val file = list.selectedValue ?: return
        runCatching { ClineProjectService.getInstance(project).bridge.showTextDocument(file.path) }
    }

    internal fun viewSelected() {
        val file = list.selectedValue ?: return
        store()?.viewFileChanges(file.path)
    }

    internal fun revertSelected() {
        val file = list.selectedValue ?: return
        if (!confirmRevert(file)) return
        store()?.revertFile(file.path)?.whenComplete { _, _ -> refresh() }
    }

    private class Renderer : ColoredListCellRenderer<ChangedFile>() {
        override fun customizeCellRenderer(list: JList<out ChangedFile>, value: ChangedFile?, index: Int, selected: Boolean, hasFocus: Boolean) {
            value ?: return
            val color = when (value.status) {
                ChangedFile.Status.ADDED -> JBColor.GREEN.darker()
                ChangedFile.Status.DELETED -> JBColor.RED
                ChangedFile.Status.MODIFIED -> JBColor.BLUE
            }
            append("${value.status.letter}  ", SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, color))
            append(value.relativePath)
            if (value.addedLines > 0) append("  +${value.addedLines}", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, JBColor.GREEN.darker()))
            if (value.removedLines > 0) append("  −${value.removedLines}", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, JBColor.RED))
        }
    }

    companion object {
        /** What a revert does is easy to misread, so the dialog says it plainly. */
        fun revertWarning(file: ChangedFile): String {
            val what = if (file.status == ChangedFile.Status.ADDED) "delete ${file.relativePath}, which Cline created" else "restore ${file.relativePath} to how it was before Cline's last run"
            return "This will $what. All of Cline's changes to this file since then are lost. Cline is not told, so it may still expect the changes."
        }

        private fun askBeforeRevert(file: ChangedFile): Boolean =
            Messages.showYesNoDialog(revertWarning(file), "Revert ${file.relativePath}?", "Revert", "Cancel", Messages.getWarningIcon()) == Messages.YES
    }
}
