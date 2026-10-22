package bot.cline.jetbrains.ide

import bot.cline.jetbrains.chat.ChatStore
import bot.cline.jetbrains.chat.HistoryItem
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.ui.Messages
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.text.DateFormat
import java.util.Date
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent

/** Past tasks: search, open, favorite and delete. [onOpened] runs after a task was loaded into the chat. */
class HistoryPanel(
    private val store: ChatStore,
    private val onOpened: () -> Unit = {},
    /** Asks before deleting; true goes ahead. Replaced in tests. */
    private val confirmDelete: (HistoryItem) -> Boolean = ::askBeforeDelete,
) : JPanel(BorderLayout()) {
    private val model = DefaultListModel<HistoryItem>()
    private val list = JBList(model).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = Renderer()
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) openSelected()
            }
        })
        addListSelectionListener { updateButtons() }
    }
    private val search = SearchTextField(false).apply {
        textEditor.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = reload()
            override fun removeUpdate(e: DocumentEvent) = reload()
            override fun changedUpdate(e: DocumentEvent) = reload()
        })
    }
    private val favorites = JBCheckBox("Favorites only").apply { addActionListener { reload() } }
    private val workspaceOnly = JBCheckBox("This project only").apply { addActionListener { reload() } }
    private val status = JBLabel().apply { border = JBUI.Borders.empty(4) }
    private val open = JButton("Open").apply { addActionListener { openSelected() } }
    private val favorite = JButton("Favorite").apply { addActionListener { toggleFavorite() } }
    private val delete = JButton("Delete").apply { addActionListener { deleteSelected() } }
    private var generation = 0

    init {
        preferredSize = JBUI.size(560, 380)
        val filters = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply { add(favorites); add(workspaceOnly) }
        add(JPanel(BorderLayout()).apply { add(search, BorderLayout.NORTH); add(filters, BorderLayout.SOUTH) }, BorderLayout.NORTH)
        add(JBScrollPane(list), BorderLayout.CENTER)
        add(JPanel(BorderLayout()).apply {
            add(status, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, 4, 2)).apply { add(favorite); add(delete); add(open) }, BorderLayout.EAST)
        }, BorderLayout.SOUTH)
        updateButtons()
        reload()
    }

    val items: List<HistoryItem> get() = model.elements().toList()

    internal fun setSearch(text: String) {
        search.text = text
    }

    internal fun setFavoritesOnly(value: Boolean) {
        favorites.isSelected = value
        reload()
    }

    /** Asks the core for the page that matches the filters. A slower earlier answer never overwrites a newer one. */
    fun reload() {
        val mine = ++generation
        status.text = "Loading…"
        store.history.list(search.text.trim(), favorites.isSelected, workspaceOnly.isSelected).whenComplete { page, error ->
            ApplicationManager.getApplication().invokeLater({
                if (mine != generation) return@invokeLater
                model.clear()
                if (error != null) {
                    status.text = "Could not load the history: " + (error.cause?.message ?: error.message)
                } else {
                    page.items.forEach(model::addElement)
                    status.text = if (page.items.isEmpty()) "No tasks." else "${page.items.size} ${if (page.items.size == 1) "task" else "tasks"}" + if (page.hasMore) " (more not shown)" else ""
                }
                updateButtons()
            }, ModalityState.any())
        }
    }

    private fun updateButtons() {
        val has = list.selectedValue != null
        open.isEnabled = has
        favorite.isEnabled = has
        delete.isEnabled = has
        favorite.text = if (list.selectedValue?.favorited == true) "Unfavorite" else "Favorite"
    }

    internal fun select(id: String) {
        list.setSelectedValue(items.first { it.id == id }, false)
    }

    internal fun openSelected() {
        val item = list.selectedValue ?: return
        store.history.open(item.id).whenComplete { _, error ->
            ApplicationManager.getApplication().invokeLater({
                if (error != null) status.text = "Could not open it: " + (error.cause?.message ?: error.message) else onOpened()
            }, ModalityState.any())
        }
    }

    internal fun toggleFavorite() {
        val item = list.selectedValue ?: return
        store.history.setFavorite(item.id, !item.favorited).whenComplete { _, _ -> reload() }
    }

    internal fun deleteSelected() {
        val item = list.selectedValue ?: return
        if (!confirmDelete(item)) return
        store.history.delete(listOf(item.id)).whenComplete { _, _ -> reload() }
    }

    private class Renderer : ColoredListCellRenderer<HistoryItem>() {
        override fun customizeCellRenderer(list: JList<out HistoryItem>, value: HistoryItem?, index: Int, selected: Boolean, hasFocus: Boolean) {
            value ?: return
            if (value.favorited) append("★ ", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
            append(value.task.lineSequence().firstOrNull().orEmpty().take(90).ifEmpty { "(no title)" })
            val details = listOfNotNull(
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(value.timestamp)).takeIf { value.timestamp > 0 },
                value.cost.takeIf { it > 0 }?.let { String.format("$%.4f", it) },
                value.modelId,
            )
            if (details.isNotEmpty()) append("   " + details.joinToString(" · "), SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
    }

    companion object {
        private fun askBeforeDelete(item: HistoryItem): Boolean =
            Messages.showYesNoDialog("Delete \"${item.task.lineSequence().firstOrNull().orEmpty().take(60)}\" from the history? This cannot be undone.", "Delete Task", "Delete", "Cancel", Messages.getWarningIcon()) == Messages.YES
    }
}
