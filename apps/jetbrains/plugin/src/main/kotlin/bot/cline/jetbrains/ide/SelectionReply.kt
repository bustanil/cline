package bot.cline.jetbrains.ide

import bot.cline.jetbrains.chat.Quote
import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Point
import java.awt.event.KeyAdapter
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.KeyStroke
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.text.JTextComponent

/** What replying to part of a message can do. */
interface QuoteActions {
    /** Puts the quote above the composer, with any [reply] already typed for it, to be sent with the next message. */
    fun addQuote(quote: String, reply: String = "")

    /** Sends [text] now, with [quote] as its context. */
    fun sendReply(quote: String, text: String)
}

/** A thin bar and the quoted words, cut to a few lines. Used in the popup and above the composer. */
internal fun quoteBlock(quote: String, maxChars: Int, widthPx: Int = 220): JComponent = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
    isOpaque = false
    add(JPanel().apply { background = ChatColors.border; preferredSize = Dimension(JBUI.scale(3), 10) }, BorderLayout.WEST)
    add(
        JBLabel("<html><div style='width:${JBUI.scale(widthPx)}px'>" + Quote.preview(quote, maxChars).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") + "</div></html>").apply {
            foreground = UIUtil.getContextHelpForeground()
        },
        BorderLayout.CENTER,
    )
}

/**
 * What the popup over a selection holds: the quoted words and a box to reply in. Enter sends the reply with the
 * quote as context; the icon adds the quote to the composer instead, for a longer reply.
 */
class ReplyPopupPanel(
    private val quote: String,
    private val onSend: (text: String) -> Unit,
    /** Gets what was typed in the box (it may be empty), so the quote card can show it. */
    private val onAddToComposer: (reply: String) -> Unit,
) : JPanel(BorderLayout(0, JBUI.scale(6))) {
    val field = JBTextField().apply {
        emptyText.text = "Reply to Cline"
        border = JBUI.Borders.empty(2, 0)
        isOpaque = false
        // Enter is bound as an action of the field itself, so it is handled once even if the popup or the IDE also
        // sees the key; the other Enter events are swallowed so none reaches whatever gets focus when the popup closes.
        val enter = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0)
        inputMap.put(enter, "cline.sendReply")
        actionMap.put("cline.sendReply", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) = send()
        })
        addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) {
                    e.consume()
                    if (!e.isShiftDown) send()
                }
            }

            override fun keyTyped(e: KeyEvent) {
                if (e.keyChar == '\n' || e.keyChar == '\r') e.consume()
            }

            override fun keyReleased(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) e.consume()
            }
        })
    }
    private val add = JBLabel(AllIcons.General.Add).apply {
        toolTipText = "Add the quote to your message"
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = addToComposer()
        })
    }

    init {
        isOpaque = false
        border = JBUI.Borders.empty(8, 10)
        add(quoteBlock(quote, 160), BorderLayout.NORTH)
        add(JPanel(BorderLayout(JBUI.scale(6), 0)).apply { isOpaque = false; add(field, BorderLayout.CENTER); add(add, BorderLayout.EAST) }, BorderLayout.CENTER)
        preferredSize = Dimension(JBUI.scale(300), super.getPreferredSize().height)
    }

    /** An empty reply is not sent: the quote alone says nothing. */
    fun send() {
        val text = field.text.trim()
        if (text.isNotEmpty()) onSend(text)
    }

    /** Adds the quote to the composer along with what was typed so far. */
    fun addToComposer() = onAddToComposer(field.text.trim())
}

/** Shows the popup. Replaced in tests. */
interface ReplyPresenter {
    fun show(content: JComponent, owner: JTextComponent, at: Point)

    fun hide()
}

class PopupReplyPresenter : ReplyPresenter {
    private var popup: JBPopup? = null

    override fun show(content: JComponent, owner: JTextComponent, at: Point) {
        hide()
        popup = JBPopupFactory.getInstance().createComponentPopupBuilder(content, (content as? ReplyPopupPanel)?.field ?: content)
            .setRequestFocus(true).setFocusable(true).setCancelOnClickOutside(true).setMovable(false).setResizable(false)
            .createPopup()
            .also { it.show(RelativePoint(owner, at)) }
    }

    override fun hide() {
        popup?.cancel()
        popup = null
    }
}

/**
 * Watches one text pane. When the user selects some words, a small popup offers to reply to them: the same
 * "Reply" the Claude app has on a selection. The words become the quote of the reply.
 */
class SelectionReplyController(
    private val pane: JTextComponent,
    private val quotes: QuoteActions,
    private val presenter: ReplyPresenter = PopupReplyPresenter(),
) {
    fun attach() {
        // Wait for the mouse to be released, so the popup does not appear (and take focus) mid-drag.
        pane.addMouseListener(object : MouseAdapter() {
            override fun mouseReleased(e: MouseEvent) = maybeShow()
        })
        pane.addCaretListener { if (pane.selectionStart == pane.selectionEnd) presenter.hide() }
    }

    /** Shows the popup for the current selection, or hides it when there is nothing worth quoting. */
    fun maybeShow() {
        val quote = Quote.clean(pane.selectedText)
        if (quote.length < 2) return presenter.hide()
        val end = runCatching { pane.modelToView2D(pane.selectionEnd) }.getOrNull()
        val at = if (end != null) Point(end.x.toInt().coerceAtLeast(0), (end.y + end.height).toInt() + JBUI.scale(4)) else Point(0, pane.height)
        val content = ReplyPopupPanel(
            quote,
            onSend = { text -> presenter.hide(); quotes.sendReply(quote, text) },
            onAddToComposer = { reply -> presenter.hide(); quotes.addQuote(quote, reply) },
        )
        presenter.show(content, pane, at)
    }
}
