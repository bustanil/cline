package bot.cline.jetbrains.chat

import org.commonmark.Extension
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableBody
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.node.AbstractVisitor
import org.commonmark.renderer.html.AttributeProvider
import org.commonmark.node.Code
import org.commonmark.node.Node
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.DefaultUrlSanitizer
import org.commonmark.renderer.html.HtmlRenderer
import java.net.URLEncoder
import java.net.URLDecoder

/** Draws the inside of a code block as HTML: token colors, ready to put in a `<pre>`. Null means "no highlighting". */
fun interface CodeHighlighter {
    fun highlight(language: String?, code: String): String?
}

/** How fenced code blocks look: a box with these colors (`#rrggbb`), and the highlighter for their text. */
data class CodeBlockStyle(
    val background: String,
    val border: String,
    /** Background of a table's header row, a little different from the card around it. */
    val tableHeader: String = "#f7f6f4",
    val highlighter: CodeHighlighter? = null,
)

/**
 * Markdown to the small HTML subset Swing's HTML pane draws. Inline code that
 * resolved to a file becomes a link; the href carries the code text, so the
 * click handler can look the link up again.
 */
object MarkdownHtml {
    const val FILE_SCHEME = "cline-file:"

    private val extensions: List<Extension> = listOf(TablesExtension.create(), StrikethroughExtension.create())
    private val parser: Parser = Parser.builder().extensions(extensions).build()

    /** Inline code in [markdown] that looks like a file path and is worth asking the core about. */
    fun fileCandidates(markdown: String): List<String> {
        val found = LinkedHashSet<String>()
        parser.parse(markdown).accept(object : AbstractVisitor() {
            override fun visit(code: Code) {
                if (FileLinks.isPotentialFilePath(code.literal)) found += code.literal
            }
        })
        return found.toList()
    }

    /** Renders [markdown]; [links] maps inline code text to the file it resolved to. */
    fun render(markdown: String, links: Map<String, FileLink> = emptyMap(), codeStyle: CodeBlockStyle? = null): String {
        val document = parser.parse(markdown)
        if (links.isNotEmpty()) wrapLinkedCode(document, links)
        // Raw HTML in a model's reply is shown as text, not drawn.
        val builder = HtmlRenderer.builder().extensions(extensions).escapeHtml(true)
        if (codeStyle != null) {
            builder.nodeRendererFactory { context -> CodeBlockRenderer(context, codeStyle) }
            builder.attributeProviderFactory { AttributeProvider { node, tag, attributes -> styleTable(node, tag, attributes, codeStyle) } }
        }
        val html = builder
            .sanitizeUrls(true)
            .urlSanitizer(DefaultUrlSanitizer(listOf("http", "https", "mailto", FILE_SCHEME.removeSuffix(":"))))
            .build().render(document)
        return if (codeStyle != null) wrapTables(html, codeStyle) else html
    }

    // Tables, as in the Claude Code app: a bordered card, a bold left-aligned header, and thin lines between rows only.
    private fun styleTable(node: Node, tag: String, attributes: MutableMap<String, String>, style: CodeBlockStyle) {
        when {
            node is TableBlock -> {
                attributes["class"] = "cline-md-table"
                attributes["width"] = "100%"
                attributes["cellspacing"] = "0"
                attributes["cellpadding"] = "8"
                attributes["border"] = "0"
            }
            node is TableCell -> {
                attributes["align"] = "left"
                attributes["valign"] = "top"
                val row = node.parent
                val isLastBodyRow = row is TableRow && row.next == null && row.parent is TableBody
                val line = if (isLastBodyRow) "" else "border-bottom: 1px solid ${style.border}; "
                attributes["style"] = line + if (tag == "th") "font-weight: bold" else ""
            }
        }
    }

    /** Puts each table in a one-cell card so it gets an outer border; Swing's HTML has no rounded or per-side table border. */
    private fun wrapTables(html: String, style: CodeBlockStyle): String =
        html.replace(Regex("""<table class="cline-md-table"[^>]*>"""), { """<table width="100%" cellspacing="0" cellpadding="0" border="0" style="border: 1px solid ${style.border}"><tr><td>${it.value}""" })
            .replace("</table>\n", "</table></td></tr></table>\n")
            .let { out -> if (out.endsWith("</table>") && out.contains("cline-md-table")) out + "</td></tr></table>" else out }
            .let(::keepCellCodeOnOneLine)

    // A cell's inline code should not break at its spaces ("POST /authorize"); Swing has no nowrap, so use no-break spaces.
    private fun keepCellCodeOnOneLine(html: String): String =
        Regex("""(<table.*?</table>)""", RegexOption.DOT_MATCHES_ALL).replace(html) { table ->
            Regex("""<code>([^<]{0,60})</code>""").replace(table.value) { code -> "<code>" + code.groupValues[1].replace(" ", "&nbsp;") + "</code>" }
        }

    /** Draws fenced and indented code as a bordered box around a `<pre>`, highlighted when a highlighter can. */
    private class CodeBlockRenderer(private val context: org.commonmark.renderer.html.HtmlNodeRendererContext, private val style: CodeBlockStyle) :
        org.commonmark.renderer.NodeRenderer {
        override fun getNodeTypes(): Set<Class<out Node>> = setOf(org.commonmark.node.FencedCodeBlock::class.java, org.commonmark.node.IndentedCodeBlock::class.java)

        override fun render(node: Node) {
            val (language, code) = when (node) {
                is org.commonmark.node.FencedCodeBlock -> node.info.trim().substringBefore(' ').takeIf { it.isNotEmpty() } to node.literal
                is org.commonmark.node.IndentedCodeBlock -> null to node.literal
                else -> return
            }
            val body = runCatching { style.highlighter?.highlight(language, code.trimEnd('\n')) }.getOrNull()
                ?: code.trimEnd('\n').replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            // A one-cell table is the way Swing's HTML draws a padded box with a border and a background.
            context.writer.raw(
                """<table width="100%" cellspacing="0" cellpadding="8" border="0" bgcolor="${style.background}" style="border: 1px solid ${style.border}"><tr><td><pre>$body</pre></td></tr></table>""",
            )
            context.writer.line()
        }
    }

    /** What kind of block a part of a reply is. Code and tables are drawn as cards of their own. */
    enum class BlockKind { TEXT, CODE, TABLE }

    data class Block(val kind: BlockKind, val html: String)

    /**
     * Renders [markdown] as a list of blocks: runs of text, code blocks and tables. The IDE draws each code
     * block and table as a rounded card, which Swing's HTML cannot do. Table columns get widths from their content.
     */
    fun blocks(markdown: String, links: Map<String, FileLink> = emptyMap(), style: CodeBlockStyle? = null): List<Block> {
        val document = parser.parse(markdown)
        if (links.isNotEmpty()) wrapLinkedCode(document, links)
        val widths = columnWidths(document)
        val text = baseBuilder().build()
        val tables = baseBuilder().attributeProviderFactory {
            AttributeProvider { node, tag, attributes -> styleTableCell(node, tag, attributes, style?.border ?: "#e3e2e2", style?.tableHeader, widths) }
        }.build()
        val out = mutableListOf<Block>()
        var pending = org.commonmark.node.Document()
        fun flush() {
            if (pending.firstChild != null) out += Block(BlockKind.TEXT, text.render(pending))
            pending = org.commonmark.node.Document()
        }
        var node = document.firstChild
        while (node != null) {
            val next = node.next
            when (node) {
                is org.commonmark.node.FencedCodeBlock -> { flush(); out += codeBlock(node.info.trim().substringBefore(' ').takeIf { it.isNotEmpty() }, node.literal, style) }
                is org.commonmark.node.IndentedCodeBlock -> { flush(); out += codeBlock(null, node.literal, style) }
                is TableBlock -> {
                    flush()
                    val holder = org.commonmark.node.Document().also { it.appendChild(node) }
                    out += Block(BlockKind.TABLE, keepCellCodeOnOneLine(tables.render(holder)))
                }
                else -> pending.appendChild(node)
            }
            node = next
        }
        flush()
        return out
    }

    private fun baseBuilder(): HtmlRenderer.Builder = HtmlRenderer.builder().extensions(extensions).escapeHtml(true)
        .sanitizeUrls(true)
        .urlSanitizer(DefaultUrlSanitizer(listOf("http", "https", "mailto", FILE_SCHEME.removeSuffix(":"))))

    private fun codeBlock(language: String?, literal: String, style: CodeBlockStyle?): Block {
        val code = literal.trimEnd('\n')
        val body = runCatching { style?.highlighter?.highlight(language, code) }.getOrNull()
            ?: code.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        return Block(BlockKind.CODE, "<pre>$body</pre>")
    }

    /** Per-header-cell width, in percent, from the longest text in each column. */
    private fun columnWidths(document: Node): java.util.IdentityHashMap<TableCell, Int> {
        val result = java.util.IdentityHashMap<TableCell, Int>()
        document.accept(object : AbstractVisitor() {
            override fun visit(customBlock: org.commonmark.node.CustomBlock) {
                if (customBlock !is TableBlock) return super.visit(customBlock)
                val rows = mutableListOf<List<TableCell>>()
                var section = customBlock.firstChild
                while (section != null) {
                    var row = section.firstChild
                    while (row != null) {
                        rows += generateSequence(row.firstChild) { it.next }.filterIsInstance<TableCell>().toList()
                        row = row.next
                    }
                    section = section.next
                }
                val columns = rows.maxOfOrNull { it.size } ?: return
                // Text can wrap, so a column wants about its longest text (capped). Inline code cannot, and a
                // monospace character is wider, so a column of code wants at least its longest code at 1.5x.
                val weight = IntArray(columns) { column ->
                    rows.maxOf { row ->
                        val cell = row.getOrNull(column) ?: return@maxOf 6
                        maxOf(textLength(cell).coerceIn(6, 40), (longestCode(cell) * 3 / 2))
                    }
                }
                val total = weight.sum().coerceAtLeast(1)
                rows.firstOrNull()?.forEachIndexed { column, cell -> result[cell] = (weight[column] * 100 / total).coerceAtLeast(5) }
            }
        })
        return result
    }

    private fun longestCode(node: Node): Int {
        var longest = 0
        node.accept(object : AbstractVisitor() {
            override fun visit(code: Code) { longest = maxOf(longest, code.literal.length) }
        })
        return longest
    }

    private fun textLength(node: Node): Int {
        var n = 0
        node.accept(object : AbstractVisitor() {
            override fun visit(text: org.commonmark.node.Text) { n += text.literal.length }
            override fun visit(code: Code) { n += code.literal.length }
        })
        return n
    }

    // A table inside a card: a bold left-aligned header and thin lines between rows only.
    private fun styleTableCell(node: Node, tag: String, attributes: MutableMap<String, String>, border: String, headerBackground: String?, widths: java.util.IdentityHashMap<TableCell, Int>) {
        when (node) {
            is TableBlock -> {
                attributes["width"] = "100%"
                attributes["cellspacing"] = "0"
                attributes["cellpadding"] = "8"
                attributes["border"] = "0"
            }
            is TableCell -> {
                attributes["align"] = "left"
                attributes["valign"] = "top"
                widths[node]?.let { attributes["width"] = "$it%" }
                val row = node.parent
                val isLastBodyRow = row is TableRow && row.next == null && row.parent is TableBody
                if (tag == "th" && headerBackground != null) attributes["bgcolor"] = headerBackground
                attributes["style"] = (if (isLastBodyRow) "" else "border-bottom: 1px solid $border; ") + if (tag == "th") "font-weight: bold" else ""
            }
        }
    }

    fun hrefFor(reference: String): String = FILE_SCHEME + URLEncoder.encode(reference, Charsets.UTF_8)

    /** The code text a link made by [render] stands for, or null if [href] is another kind of link. */
    fun referenceOf(href: String): String? =
        href.takeIf { it.startsWith(FILE_SCHEME) }?.let { URLDecoder.decode(it.removePrefix(FILE_SCHEME), Charsets.UTF_8) }

    private fun wrapLinkedCode(root: Node, links: Map<String, FileLink>) {
        val codes = mutableListOf<Code>()
        root.accept(object : AbstractVisitor() {
            override fun visit(code: Code) {
                if (code.literal in links) codes += code
            }
        })
        for (code in codes) {
            val link = org.commonmark.node.Link(hrefFor(code.literal), null)
            code.insertBefore(link)
            code.unlink()
            link.appendChild(code)
        }
    }
}
