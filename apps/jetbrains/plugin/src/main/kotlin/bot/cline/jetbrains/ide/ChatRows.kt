package bot.cline.jetbrains.ide

import bot.cline.jetbrains.chat.FileLink
import bot.cline.jetbrains.chat.FileLinks
import bot.cline.jetbrains.chat.MarkdownHtml
import com.intellij.ui.RoundedLineBorder
import javax.swing.BorderFactory
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

/**
 * The chat's palette. The light values are sampled from the Claude Code app: a warm off-white page
 * (`#FCFBFA`), white cards and `#E3E2E2` borders. The dark values are chosen to match, not sampled.
 */
object ChatColors {
    val page: JBColor = JBColor(0xFCFBFA, 0x262624)
    val card: JBColor = JBColor(0xFFFFFF, 0x30302E)
    val border: JBColor = JBColor(0xE3E2E2, 0x45443F)
    val terminal: JBColor = JBColor(0xF6F5F3, 0x1F1E1D)
    val codeBackground: JBColor = JBColor(0xF1EFEC, 0x3A3936)
    val codeText: JBColor = JBColor(0x8B3A2B, 0xE0A58F)
}

/** What rows need from the chat: file links, sending an answer, and theme colors. */
class RowContext(
    val fileLinks: FileLinks,
    /** Sends [text] as the user's answer (an option button). */
    val reply: (String) -> Unit = {},
    val colors: () -> RowColors = ::themeColors,
    /** Colors the tokens in code blocks; null draws them plain. */
    val highlighter: bot.cline.jetbrains.chat.CodeHighlighter? = null,
) {
    companion object {
        fun themeColors() = RowColors(
            added = ColorUtil.toHtmlColor(JBColor(0x2E9E4F, 0x6CC17E)),
            removed = ColorUtil.toHtmlColor(JBColor(0xD03B3B, 0xE06C6C)),
            muted = ColorUtil.toHtmlColor(UIUtil.getContextHelpForeground()),
            error = ColorUtil.toHtmlColor(JBColor(0xD03B3B, 0xE06C6C)),
            codeBackground = ColorUtil.toHtmlColor(ChatColors.terminal),
            codeBorder = ColorUtil.toHtmlColor(ChatColors.border),
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
    fun create(item: TranscriptItem, context: RowContext): ChatRow = when (item) {
        is TranscriptItem.Activity -> ActivityRow(item, context)
        is TranscriptItem.Single -> HtmlRow(item, context)
    }
}

/** An HTML pane that draws row content and passes link clicks to [onLink]. */
internal fun newHtmlPane(onLink: (String?) -> Unit): JBHtmlPane =
    JBHtmlPane(JBHtmlPaneStyleConfiguration.builder().build(), JBHtmlPaneConfiguration.builder().build()).apply {
        isEditable = false
        isOpaque = false
        border = JBUI.Borders.empty(2, 10)
        // Tight lists: the default gives each bullet the gap of a paragraph.
        (editorKit as? javax.swing.text.html.HTMLEditorKit)?.styleSheet?.addRule("code { background-color: ${ColorUtil.toHtmlColor(ChatColors.codeBackground)}; color: ${ColorUtil.toHtmlColor(ChatColors.codeText)}; }")
        (editorKit as? javax.swing.text.html.HTMLEditorKit)?.styleSheet?.addRule("li { margin-top: 0px; margin-bottom: 0px; } ul { margin-top: 2px; margin-bottom: 2px; } ol { margin-top: 2px; margin-bottom: 2px; }")
        addHyperlinkListener { event -> if (event.eventType == HyperlinkEvent.EventType.ACTIVATED) onLink(event.description ?: event.url?.toString()) }
    }

/** Opens a link made by [MarkdownHtml]: a file through the core, or a web page in the browser. */
internal fun openChatLink(href: String?, resolved: Map<String, FileLink>, context: RowContext) {
    if (href == null) return
    val reference = MarkdownHtml.referenceOf(href)
    if (reference != null) {
        resolved[reference]?.let { context.fileLinks.open(it) }
    } else if (href.startsWith("http://") || href.startsWith("https://")) {
        BrowserUtil.browse(href)
    }
}

/**
 * Every row is HTML drawn by [RowRenderer], which also names the file paths to
 * link and the answers to offer. The core decides which paths exist, so links
 * appear a moment after the text.
 */
class HtmlRow(item: TranscriptItem, private val context: RowContext) : ChatRow {
    private val pane = newHtmlPane { href -> openLink(href) }
    private val blockPanel = JPanel().apply {
        layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
        isOpaque = false
    }
    private val blockViews = ArrayList<BlockView>()
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
    private var view: RowView = RowRenderer.render(item, context.colors(), context.highlighter)
    private var key = item.key
    private var expanded = false
    private var lastHtml = ""

    init {
        draw()
    }

    override fun update(item: TranscriptItem): Boolean {
        if (item.key != key) return false
        view = RowRenderer.render(item, context.colors(), context.highlighter)
        draw()
        return true
    }

    /** One block of a Markdown reply: plain text, or a code block or table in a card of its own. */
    private inner class BlockView(var kind: MarkdownHtml.BlockKind) {
        val pane: JBHtmlPane = newHtmlPane { href -> openLink(href) }
        val component: JComponent = when (kind) {
            MarkdownHtml.BlockKind.TEXT -> pane
            else -> JPanel(BorderLayout()).apply {
                isOpaque = true
                background = if (kind == MarkdownHtml.BlockKind.CODE) ChatColors.terminal else ChatColors.card
                border = BorderFactory.createCompoundBorder(JBUI.Borders.empty(4, 10), RoundedLineBorder(ChatColors.border, 12))
                add(pane.apply { border = JBUI.Borders.empty(if (kind == MarkdownHtml.BlockKind.CODE) 8 else 4, 10) }, BorderLayout.CENTER)
            }
        }
        var html = ""
    }

    private fun drawBlocks(blocks: List<MarkdownHtml.Block>) {
        // Rebuild only when the shape of the reply changed; while text streams, update the panes in place.
        if (blockViews.map { it.kind } != blocks.map { it.kind }) {
            blockViews.clear()
            blockPanel.removeAll()
            for (block in blocks) {
                val v = BlockView(block.kind)
                blockViews += v
                blockPanel.add(v.component)
            }
            blockPanel.revalidate()
        }
        blocks.forEachIndexed { i, block ->
            val v = blockViews[i]
            if (v.html != block.html) {
                v.html = block.html
                v.pane.text = block.html
            }
        }
        lastHtml = blocks.joinToString("") { it.html }
    }

    private fun draw() {
        val blocks = view.blocks
        if (blocks != null) {
            drawBlocks(blocks(resolved))
        } else {
            val html = view.html(resolved)
            if (html != lastHtml) {
                lastHtml = html
                pane.text = html
            }
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
        if (title == null || expanded) panel.add(if (view.blocks != null) blockPanel else pane, BorderLayout.CENTER)
        if (view.options.isNotEmpty()) panel.add(answers, BorderLayout.SOUTH)
        panel.revalidate()
        panel.repaint()
    }

    internal fun openLink(href: String?) = openChatLink(href, resolved, context)

    /** The HTML now shown, whether or not the row is expanded; for tests. */
    internal val html: String get() = lastHtml

    internal val isExpanded: Boolean get() = expanded

    internal fun toggle() {
        expanded = !expanded
        layoutParts()
    }

    internal val answerButtons: List<JButton> get() = answers.components.filterIsInstance<JButton>()
}
