package bot.cline.jetbrains.ide

import bot.cline.jetbrains.chat.FileLink
import bot.cline.jetbrains.chat.FileLinks
import bot.cline.jetbrains.chat.MarkdownHtml
import bot.cline.jetbrains.chat.RowColors
import bot.cline.jetbrains.chat.RowRenderer
import bot.cline.jetbrains.chat.RowView
import bot.cline.jetbrains.chat.TranscriptItem
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBHtmlPane
import com.intellij.ui.components.JBHtmlPaneConfiguration
import com.intellij.ui.components.JBHtmlPaneStyleConfiguration
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.HyperlinkEvent

/** What rows need from the chat: file links, sending an answer, and theme colors. */
class RowContext(
    val fileLinks: FileLinks,
    /** Sends [text] as the user's answer (an option button). */
    val reply: (String) -> Unit = {},
    val colors: () -> RowColors = ::themeColors,
) {
    companion object {
        fun themeColors() = RowColors(
            added = ColorUtil.toHtmlColor(JBColor(0x2E9E4F, 0x6CC17E)),
            removed = ColorUtil.toHtmlColor(JBColor(0xD03B3B, 0xE06C6C)),
            muted = ColorUtil.toHtmlColor(UIUtil.getContextHelpForeground()),
            error = ColorUtil.toHtmlColor(JBColor(0xD03B3B, 0xE06C6C)),
        )
    }
}

/** One row of the transcript. */
interface ChatRow {
    val component: JComponent

    /** Shows [item]. Returns false if this row cannot stand for it, so the chat makes a new one. */
    fun update(item: TranscriptItem): Boolean
}

object ChatRows {
    fun create(item: TranscriptItem, context: RowContext): ChatRow = HtmlRow(item, context)
}

/**
 * Every row is HTML drawn by [RowRenderer], which also names the file paths to
 * link and the answers to offer. The core decides which paths exist, so links
 * appear a moment after the text.
 */
class HtmlRow(item: TranscriptItem, private val context: RowContext) : ChatRow {
    private val pane = JBHtmlPane(JBHtmlPaneStyleConfiguration.builder().build(), JBHtmlPaneConfiguration.builder().build()).apply {
        isEditable = false
        isOpaque = false
        border = JBUI.Borders.empty(2, 10)
        addHyperlinkListener { event -> if (event.eventType == HyperlinkEvent.EventType.ACTIVATED) openLink(event.description ?: event.url?.toString()) }
    }
    private val toggle = JBLabel().apply {
        border = JBUI.Borders.empty(4, 10)
        foreground = UIUtil.getContextHelpForeground()
        cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                expanded = !expanded
                layoutParts()
            }
        })
    }
    private val answers = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply { isOpaque = false }
    private val panel = JPanel(BorderLayout()).apply { isOpaque = false }
    override val component: JComponent get() = panel

    private val resolved = HashMap<String, FileLink>()
    private val asked = HashSet<String>()
    private var view: RowView = RowRenderer.render(item, context.colors())
    private var key = item.key
    private var expanded = false
    private var lastHtml = ""

    init {
        draw()
    }

    override fun update(item: TranscriptItem): Boolean {
        if (item.key != key) return false
        view = RowRenderer.render(item, context.colors())
        draw()
        return true
    }

    private fun draw() {
        val html = view.html(resolved)
        if (html != lastHtml) {
            lastHtml = html
            pane.text = html
        }
        answers.removeAll()
        view.options.forEach { option -> answers.add(JButton(option).apply { addActionListener { context.reply(option) } }) }
        layoutParts()
        // Ask the core only about paths not asked about yet; streaming text repeats them.
        for (reference in view.references) {
            if (!asked.add(reference)) continue
            context.fileLinks.resolve(reference).whenComplete { link, _ ->
                if (link != null) {
                    ApplicationManager.getApplication().invokeLater({
                        resolved[reference] = link
                        draw()
                    }, ModalityState.any())
                }
            }
        }
    }

    private fun layoutParts() {
        panel.removeAll()
        val title = view.collapsedTitle
        if (title != null) {
            toggle.text = (if (expanded) "▾ " else "▸ ") + title
            panel.add(toggle, BorderLayout.NORTH)
        }
        if (title == null || expanded) panel.add(pane, BorderLayout.CENTER)
        if (view.options.isNotEmpty()) panel.add(answers, BorderLayout.SOUTH)
        panel.revalidate()
        panel.repaint()
    }

    internal fun openLink(href: String?) {
        if (href == null) return
        val reference = MarkdownHtml.referenceOf(href)
        if (reference != null) {
            resolved[reference]?.let { context.fileLinks.open(it) }
        } else if (href.startsWith("http://") || href.startsWith("https://")) {
            BrowserUtil.browse(href)
        }
    }

    /** The HTML now shown, whether or not the row is expanded; for tests. */
    internal val html: String get() = lastHtml

    internal val isExpanded: Boolean get() = expanded

    internal fun toggle() {
        expanded = !expanded
        layoutParts()
    }

    internal val answerButtons: List<JButton> get() = answers.components.filterIsInstance<JButton>()
}
