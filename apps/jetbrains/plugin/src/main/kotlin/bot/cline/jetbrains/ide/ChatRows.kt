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
    val terminal: JBColor = JBColor(0xFFFFFF, 0x1F1E1D)
    val codeBackground: JBColor = JBColor(0xF8F7F5, 0x34332F)
    val codeText: JBColor = JBColor(0x5E4D47, 0xCDBDB4)

    /** CSS for `#` headings: a little larger than body text, not a page title. */
    val headingRules: List<String> = listOf(
        "h1 { font-size: 125%; font-weight: bold; margin-top: 10px; margin-bottom: 4px; }",
        "h2 { font-size: 115%; font-weight: bold; margin-top: 10px; margin-bottom: 4px; }",
        "h3 { font-size: 105%; font-weight: bold; margin-top: 8px; margin-bottom: 3px; }",
        "h4 { font-size: 100%; font-weight: bold; margin-top: 8px; margin-bottom: 3px; }",
        "h5 { font-size: 100%; font-weight: bold; margin-top: 6px; margin-bottom: 2px; }",
        "h6 { font-size: 100%; font-weight: bold; margin-top: 6px; margin-bottom: 2px; }",
    )

    /** The CSS rule for inline code (`like this`). */
    fun inlineCodeRule(): String = "code { background-color: ${ColorUtil.toHtmlColor(codeBackground)}; color: ${ColorUtil.toHtmlColor(codeText)}; font-size: 95%; }"
    /** What the user wrote: a bubble a little darker than the page. */
    val bubble: JBColor = JBColor(0xF0EFEE, 0x3A3936)

    /** A table's header row: a little different from the white card. */
    val tableHeader: JBColor = JBColor(0xF7F6F4, 0x3A3936)
}

/** What rows need from the chat: file links, sending an answer, and theme colors. */
class RowContext(
    val fileLinks: FileLinks,
    /** Sends [text] as the user's answer (an option button). */
    val reply: (String) -> Unit = {},
    val colors: () -> RowColors = ::themeColors,
    /** Colors the tokens in code blocks; null draws them plain. */
    val highlighter: bot.cline.jetbrains.chat.CodeHighlighter? = null,
    /** Background of a code card. */
    val codeBackground: java.awt.Color = ChatColors.terminal,
    /** CSS rule for inline code. */
    val inlineCodeRule: String = ChatColors.inlineCodeRule(),
) {
    companion object {
        fun themeColors() = RowColors(
            added = ColorUtil.toHtmlColor(JBColor(0x2E9E4F, 0x6CC17E)),
            removed = ColorUtil.toHtmlColor(JBColor(0xD03B3B, 0xE06C6C)),
            muted = ColorUtil.toHtmlColor(UIUtil.getContextHelpForeground()),
            error = ColorUtil.toHtmlColor(JBColor(0xD03B3B, 0xE06C6C)),
            codeBackground = ColorUtil.toHtmlColor(ChatColors.terminal),
            codeBorder = ColorUtil.toHtmlColor(ChatColors.border),
            tableHeader = ColorUtil.toHtmlColor(ChatColors.tableHeader),
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
internal fun newHtmlPane(inlineCodeRule: String = ChatColors.inlineCodeRule(), onLink: (String?) -> Unit): JBHtmlPane =
    JBHtmlPane(JBHtmlPaneStyleConfiguration.builder().build(), JBHtmlPaneConfiguration.builder().build()).apply {
        isEditable = false
        isOpaque = false
        border = JBUI.Borders.empty(2, 10)
        // Tight lists: the default gives each bullet the gap of a paragraph.
        (editorKit as? javax.swing.text.html.HTMLEditorKit)?.styleSheet?.addRule(inlineCodeRule)
        // Headings stay close to body size, as in the Claude Code app, and are bold with tight spacing.
        for (rule in ChatColors.headingRules) (editorKit as? javax.swing.text.html.HTMLEditorKit)?.styleSheet?.addRule(rule)
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
    private val pane = newHtmlPane(context.inlineCodeRule) { href -> openLink(href) }
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
        val pane: JBHtmlPane = newHtmlPane(context.inlineCodeRule) { href -> openLink(href) }
        val component: JComponent = when (kind) {
            MarkdownHtml.BlockKind.TEXT -> pane
            else -> JPanel(BorderLayout()).apply {
                isOpaque = true
                background = if (kind == MarkdownHtml.BlockKind.CODE) context.codeBackground else ChatColors.card
                border = BorderFactory.createCompoundBorder(JBUI.Borders.empty(4, 10), RoundedLineBorder(ChatColors.border, if (kind == MarkdownHtml.BlockKind.CODE) 12 else 6))
                // A table's header row runs to the card's edge; code keeps some padding.
                add(pane.apply { border = if (kind == MarkdownHtml.BlockKind.CODE) JBUI.Borders.empty(8, 10) else JBUI.Borders.empty(1) }, BorderLayout.CENTER)
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

    private var bubble: JPanel? = null

    /** What the user wrote, in a rounded bubble on the right that is as wide as the text, up to most of the row. */
    private fun bubbleAround(content: JComponent): JComponent = bubble ?: JPanel(BubbleLayout()).apply {
        isOpaque = false
        border = JBUI.Borders.empty(6, 10)
        add(RoundedPanel(ChatColors.bubble, 14).apply {
            layout = BorderLayout()
            border = JBUI.Borders.empty(4, 6)
            add(content, BorderLayout.CENTER)
            if (view.attachments.isNotEmpty()) add(attachmentsOf(view.attachments), BorderLayout.SOUTH)
        })
    }.also { bubble = it }

    /** Thumbnails of the images and names of the files that were sent with the prompt. */
    private fun attachmentsOf(items: List<bot.cline.jetbrains.chat.Attachment>): JComponent = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 4)).apply {
        isOpaque = false
        border = JBUI.Borders.empty(0, 6, 4, 6)
        for (a in items) {
            add(
                if (a.kind == bot.cline.jetbrains.chat.Attachment.Kind.IMAGE) {
                    JBLabel(attachmentThumbnail(a, 72)).apply { toolTipText = a.name; border = BorderFactory.createLineBorder(ChatColors.border) }
                } else {
                    JBLabel(a.name, com.intellij.icons.AllIcons.FileTypes.Any_type, JBLabel.LEADING).apply { toolTipText = a.value }
                },
            )
        }
    }

    internal val attachmentLabels: List<String> get() = bubble?.let { b -> collectLabels(b) } ?: emptyList()

    private fun collectLabels(c: java.awt.Component): List<String> = when (c) {
        is JBLabel -> listOf((if (c.icon is javax.swing.ImageIcon && c.text.isNullOrEmpty()) "[image]" else c.text.orEmpty()))
        is java.awt.Container -> c.components.flatMap(::collectLabels)
        else -> emptyList()
    }

    private fun layoutParts() {
        panel.removeAll()
        val title = view.collapsedTitle
        if (title != null) {
            toggle.text = (if (expanded) "▾ " else "▸ ") + title
            panel.add(toggle, BorderLayout.NORTH)
        }
        if (title == null || expanded) {
            val content: JComponent = if (view.blocks != null) blockPanel else pane
            panel.add(if (view.userBubble) bubbleAround(content) else content, BorderLayout.CENTER)
        }
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


/** A panel that paints a rounded background. */
class RoundedPanel(private val color: java.awt.Color, private val arc: Int) : JPanel() {
    init {
        isOpaque = false
    }

    override fun paintComponent(g: java.awt.Graphics) {
        val g2 = g.create() as java.awt.Graphics2D
        try {
            g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = color
            g2.fillRoundRect(0, 0, width, height, JBUI.scale(arc), JBUI.scale(arc))
        } finally {
            g2.dispose()
        }
        super.paintComponent(g)
    }
}

/**
 * Puts its one child at the right edge, as wide as the child wants but no more than [maxFraction] of the
 * width. Text that wraps then gets a bubble that shrinks to a short message and caps a long one.
 */
class BubbleLayout(private val maxFraction: Double = 0.85) : java.awt.LayoutManager {
    override fun addLayoutComponent(name: String?, comp: java.awt.Component?) {}

    override fun removeLayoutComponent(comp: java.awt.Component?) {}

    private fun naturalWidth(child: java.awt.Component): Int {
        child.setSize(Short.MAX_VALUE.toInt(), 10)
        return child.preferredSize.width
    }

    private fun settle(c: java.awt.Component) {
        if (c is java.awt.Container) {
            c.doLayout()
            c.components.forEach(::settle)
        }
    }

    private fun heightAt(child: java.awt.Component, width: Int): Int {
        child.setSize(width, 10)
        settle(child)
        return child.preferredSize.height
    }

    private fun widthFor(child: java.awt.Component, available: Int): Int =
        minOf(naturalWidth(child), (available * maxFraction).toInt().coerceAtLeast(1))

    override fun layoutContainer(parent: java.awt.Container) {
        val child = parent.components.firstOrNull() ?: return
        val insets = parent.insets
        val available = (parent.width - insets.left - insets.right).coerceAtLeast(1)
        val width = widthFor(child, available)
        child.setBounds(insets.left + available - width, insets.top, width, heightAt(child, width))
        settle(child)
    }

    override fun preferredLayoutSize(parent: java.awt.Container): java.awt.Dimension {
        val child = parent.components.firstOrNull() ?: return java.awt.Dimension()
        val insets = parent.insets
        val available = (if (parent.width > 0) parent.width else naturalWidth(child)) - insets.left - insets.right
        val width = widthFor(child, available.coerceAtLeast(1))
        return java.awt.Dimension(parent.width.coerceAtLeast(width + insets.left + insets.right), heightAt(child, width) + insets.top + insets.bottom)
    }

    override fun minimumLayoutSize(parent: java.awt.Container): java.awt.Dimension = java.awt.Dimension(0, preferredLayoutSize(parent).height)
}
