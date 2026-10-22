package bot.cline.jetbrains.chat

import org.commonmark.Extension
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.Code
import org.commonmark.node.Node
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.DefaultUrlSanitizer
import org.commonmark.renderer.html.HtmlRenderer
import java.net.URLEncoder
import java.net.URLDecoder

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
    fun render(markdown: String, links: Map<String, FileLink> = emptyMap()): String {
        val document = parser.parse(markdown)
        if (links.isNotEmpty()) wrapLinkedCode(document, links)
        // Raw HTML in a model's reply is shown as text, not drawn.
        return HtmlRenderer.builder().extensions(extensions).escapeHtml(true)
            .sanitizeUrls(true)
            .urlSanitizer(DefaultUrlSanitizer(listOf("http", "https", "mailto", FILE_SCHEME.removeSuffix(":"))))
            .build().render(document)
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
