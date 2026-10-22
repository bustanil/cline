package bot.cline.jetbrains.ide

import bot.cline.jetbrains.chat.MentionMatch
import bot.cline.jetbrains.chat.MentionResults
import bot.cline.jetbrains.chat.Mentions
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.Point
import java.awt.event.KeyEvent
import java.util.concurrent.CompletableFuture
import javax.swing.JList
import javax.swing.ListSelectionModel
import javax.swing.Timer
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.text.JTextComponent

/** Shows the list of suggestions. Replaced in tests. */
interface MentionPresenter {
    fun show(list: JBList<MentionMatch>, input: JTextComponent)

    fun hide()
}

/** The default presenter: a popup under the caret that does not take focus, so typing continues. */
class PopupMentionPresenter : MentionPresenter {
    private var popup: JBPopup? = null

    override fun show(list: JBList<MentionMatch>, input: JTextComponent) {
        hide()
        val point = runCatching { input.modelToView2D(input.caretPosition) }.getOrNull()?.let { Point(it.x.toInt(), (it.y + it.height).toInt()) } ?: Point(0, input.height)
        popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(JBScrollPane(list).apply { preferredSize = JBUI.size(380, 160) }, null)
            .setRequestFocus(false)
            .setFocusable(false)
            .setMovable(false)
            .setResizable(false)
            .createPopup()
            .also { it.show(RelativePoint(input, point)) }
    }

    override fun hide() {
        popup?.cancel()
        popup = null
    }
}

/**
 * `@` completion in the input box. Typing `@` and some letters asks the core
 * for matching files and folders and offers them; Up and Down move, Enter or
 * Tab inserts, Escape closes. The chat calls [handleKey] first so Enter does
 * not send the message while a suggestion is chosen.
 */
class MentionController(
    private val input: JTextComponent,
    private val search: (String) -> CompletableFuture<MentionResults>,
    private val presenter: MentionPresenter = PopupMentionPresenter(),
    delayMs: Int = 150,
) {
    private val model = javax.swing.DefaultListModel<MentionMatch>()
    private val list = JBList(model).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = object : ColoredListCellRenderer<MentionMatch>() {
            override fun customizeCellRenderer(list: JList<out MentionMatch>, value: MentionMatch?, index: Int, selected: Boolean, hasFocus: Boolean) {
                value ?: return
                append(if (value.isFolder) "📁 " else "")
                append(value.label ?: value.path.substringAfterLast('/'))
                if (value.label != null && value.label != value.path) append("   " + value.path, SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, JBColor.GRAY))
            }
        }
        addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (e.clickCount >= 1) selectedIndex.takeIf { it >= 0 }?.let(::accept)
            }
        })
    }
    private val timer = Timer(delayMs) { lookup() }.apply { isRepeats = false }
    private var ignoreChanges = false
    private var generation = 0

    @Volatile
    var visible = false
        private set

    val suggestions: List<MentionMatch> get() = model.elements().toList()

    fun attach() {
        input.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = changed()
            override fun removeUpdate(e: DocumentEvent) = changed()
            override fun changedUpdate(e: DocumentEvent) = Unit
        })
        input.addCaretListener { changed() }
    }

    private fun changed() {
        if (ignoreChanges) return
        if (Mentions.tokenAt(input.text, input.caretPosition) == null) hide() else timer.restart()
    }

    /** Asks for suggestions for the mention at the caret now, without waiting for the delay. */
    fun lookup() {
        val token = Mentions.tokenAt(input.text, input.caretPosition) ?: return hide()
        val mine = ++generation
        search(token.query).whenComplete { results, error ->
            ApplicationManager.getApplication().invokeLater({
                // A later keystroke made this answer stale.
                if (mine != generation) return@invokeLater
                model.clear()
                if (error != null || results.matches.isEmpty()) return@invokeLater hide()
                results.matches.forEach(model::addElement)
                list.selectedIndex = 0
                visible = true
                presenter.show(list, input)
            }, ModalityState.any())
        }
    }

    /** Returns true when the key was for the suggestions and the input should not see it. */
    fun handleKey(e: KeyEvent): Boolean {
        if (!visible) return false
        when (e.keyCode) {
            KeyEvent.VK_DOWN -> move(1)
            KeyEvent.VK_UP -> move(-1)
            KeyEvent.VK_ENTER, KeyEvent.VK_TAB -> accept(list.selectedIndex.coerceAtLeast(0))
            KeyEvent.VK_ESCAPE -> hide()
            else -> return false
        }
        e.consume()
        return true
    }

    private fun move(by: Int) {
        if (model.isEmpty) return
        list.selectedIndex = (list.selectedIndex + by).coerceIn(0, model.size() - 1)
        list.ensureIndexIsVisible(list.selectedIndex)
    }

    /** Inserts suggestion [index] in place of the mention being typed. */
    fun accept(index: Int) {
        val match = model.getElementAt(index) ?: return
        val token = Mentions.tokenAt(input.text, input.caretPosition) ?: return hide()
        val (text, caret) = Mentions.accept(input.text, input.caretPosition, token, match)
        ignoreChanges = true
        try {
            input.text = text
            input.caretPosition = caret.coerceIn(0, text.length)
        } finally {
            ignoreChanges = false
        }
        hide()
        // Choosing a folder goes on into it, so list what is inside right away.
        if (match.isFolder) timer.restart()
    }

    fun hide() {
        timer.stop()
        generation++
        visible = false
        model.clear()
        presenter.hide()
    }
}
