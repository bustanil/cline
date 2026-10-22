package bot.cline.jetbrains.chat

import com.google.gson.JsonParser
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileLinksTest {
    @Test
    fun `potential file paths match the webview rule`() {
        for (ok in listOf("src/app.ts", "src/app.ts:12", "src/app.ts:12-20", "src/app.ts:12:5", "a/b.kt#L4", "a/b.kt#L4-L9", "~/notes.md", "/abs/path/x.kt", "README", "a-b_c/d.e.f"))
            assertTrue(FileLinks.isPotentialFilePath(ok), ok)
        for (bad in listOf("", "two words", "a/b/", "x\ny", "fun main()", "a/b.kt:", "https://example.com", "<T>", "a/b.kt:x"))
            assertFalse(FileLinks.isPotentialFilePath(bad), bad)
    }

    @Test
    fun `resolve returns the file and caches a hit`() {
        val core = FakeCore()
        val links = FileLinks(object : bot.cline.jetbrains.bridge.CoreApi by core {
            override fun call(service: String, method: String, messageJson: String): CompletableFuture<String> {
                core.call(service, method, messageJson)
                return CompletableFuture.completedFuture("""{"path":"/ws/src/app.ts","startLine":12,"endLine":20}""")
            }
        })
        assertEquals(FileLink("/ws/src/app.ts", 12, 20), links.resolve("src/app.ts:12-20").get())
        links.resolve("src/app.ts:12-20").get()
        assertEquals(1, core.calls.size)
        assertEquals("""{"value":"src/app.ts:12-20"}""", core.calls[0].third)
        assertEquals("cline.FileService" to "resolveFileReference", core.calls[0].first to core.calls[0].second)
    }

    @Test
    fun `an empty path means no such file`() {
        val links = FileLinks(object : bot.cline.jetbrains.bridge.CoreApi by FakeCore() {
            override fun call(service: String, method: String, messageJson: String) = CompletableFuture.completedFuture("""{"path":""}""")
        })
        assertNull(links.resolve("nope.kt").get())
    }

    @Test
    fun `open sends the path and the lines`() {
        val core = FakeCore()
        FileLinks(core).open(FileLink("/ws/a.kt", 3, null)).get()
        val body = JsonParser.parseString(core.calls.single().third).asJsonObject
        assertEquals("cline.FileService" to "openFileLocation", core.calls.single().first to core.calls.single().second)
        assertEquals("/ws/a.kt", body.get("path").asString)
        assertEquals(3, body.get("startLine").asInt)
        assertFalse(body.has("endLine"))
    }
}

class MarkdownHtmlTest {
    @Test
    fun `finds inline code that may be a file`() {
        val md = "See `src/a.kt:3` and `fun main()` and `src/a.kt:3` plus ```\nnot/inline.kt\n```"
        assertEquals(listOf("src/a.kt:3"), MarkdownHtml.fileCandidates(md))
    }

    @Test
    fun `resolved inline code becomes a link and the rest does not`() {
        val html = MarkdownHtml.render("Open `src/a.kt:3` or `other.kt`", mapOf("src/a.kt:3" to FileLink("/ws/src/a.kt", 3, null)))
        assertTrue(html.contains("""href="cline-file:src%2Fa.kt%3A3"><code>src/a.kt:3</code></a>"""), html)
        assertTrue(html.contains("<code>other.kt</code>") && !html.contains("""href="cline-file:other"""), html)
    }

    @Test
    fun `a link reads back to the code text`() {
        assertEquals("src/a.kt:3", MarkdownHtml.referenceOf(MarkdownHtml.hrefFor("src/a.kt:3")))
        assertNull(MarkdownHtml.referenceOf("https://example.com"))
    }

    @Test
    fun `raw html in a reply is shown as text`() {
        val html = MarkdownHtml.render("hello <img src=x onerror=alert(1)> **bold**\n\n<script>alert(1)</script>")
        assertFalse(html.contains("<img") || html.contains("<script>"), html)
        assertTrue(html.contains("<strong>bold</strong>"), html)
    }

    @Test
    fun `unsafe link schemes are dropped`() {
        val html = MarkdownHtml.render("[x](javascript:alert(1)) [y](https://example.com)")
        assertFalse(html.contains("javascript:"), html)
        assertTrue(html.contains("https://example.com"), html)
    }

    @Test
    fun `tables and strikethrough render`() {
        val html = MarkdownHtml.render("| a | b |\n|---|---|\n| 1 | 2 |\n\n~~gone~~")
        assertTrue(html.contains("<table>") && html.contains("<del>gone</del>"), html)
    }

    @Test
    fun `a br is a line break, even in a table cell`() {
        val md = "| Pillar | Meaning |\n|---|---|\n| Shahādah <br> الشهادة | Testimony |"
        val html = MarkdownHtml.blocks(md).first { it.kind == MarkdownHtml.BlockKind.TABLE }.html
        assertTrue(html.contains("<br>"), html)
        assertFalse(html.contains("&lt;br&gt;"), html)
        val three = MarkdownHtml.render("a<br/>b<BR />c")
        assertEquals(2, Regex("<br>").findAll(three).count(), three)
    }

    @Test
    fun `a picture written as a file path is drawn from a file url`() {
        val picture = java.io.File.createTempFile("cline-chart", ".png").apply { deleteOnExit() }
        val html = MarkdownHtml.render("![a chart](${picture.path})")
        assertTrue(html.contains("src=\"file:${picture.toURI().path}"), html)

        val missing = MarkdownHtml.render("![gone](/no/such/chart.png)")
        assertTrue(missing.contains("/no/such/chart.png"), missing)
    }

    @Test
    fun `a br in code stays code`() {
        val html = MarkdownHtml.render("`<br>`\n\n```\n<br>\n```")
        assertFalse(html.contains("<br>"), html)
        assertTrue(html.contains("&lt;br&gt;"), html)
    }
}

class CodeBlockTest {
    private val style = CodeBlockStyle("#f6f5f3", "#e3e2e2") { language, code -> "[$language]" + code.replace("int", "<b>int</b>") }

    @Test
    fun `a fenced block is a bordered light box with the highlighted code`() {
        val html = MarkdownHtml.render("Intro\n\n```java\nint x = 1;\n```\n\nOutro", codeStyle = style)
        assertTrue(html.contains("""bgcolor="#f6f5f3""""), html)
        assertTrue(html.contains("""style="border: 1px solid #e3e2e2""""), html)
        assertTrue(html.contains("<pre>[java]<b>int</b> x = 1;</pre>"), html)
        assertTrue(html.contains("<p>Intro</p>") && html.contains("<p>Outro</p>"), html)
    }

    @Test
    fun `without a highlighter the code is escaped plain text and a failing highlighter is ignored`() {
        val plain = MarkdownHtml.render("```\na < b && c\n```", codeStyle = CodeBlockStyle("#fff", "#000"))
        assertTrue(plain.contains("<pre>a &lt; b &amp;&amp; c</pre>"), plain)
        val broken = MarkdownHtml.render("```kt\nx<y\n```", codeStyle = CodeBlockStyle("#fff", "#000") { _, _ -> error("boom") })
        assertTrue(broken.contains("<pre>x&lt;y</pre>"), broken)
    }

    @Test
    fun `the language is the first word of the fence and indented code has none`() {
        val seen = mutableListOf<String?>()
        val spy = CodeBlockStyle("#fff", "#000") { language, _ -> seen += language; null }
        MarkdownHtml.render("```kotlin title=\"A.kt\"\nval a = 1\n```\n\n    indented\n", codeStyle = spy)
        assertEquals(listOf("kotlin", null), seen)
    }

    @Test
    fun `no style keeps the default code rendering`() {
        val html = MarkdownHtml.render("```\ncode\n```")
        assertTrue(html.contains("<pre><code>code") && !html.contains("<table"), html)
    }

    @Test
    fun `rows pass the colors and the highlighter to markdown`() {
        val row = RowRenderer.render(TranscriptItem.Single(say(1, "text", "```java\nint a;\n```")), RowColors(codeBackground = "#111111", codeBorder = "#222222"), style.highlighter).html(emptyMap())
        assertTrue(row.contains("""bgcolor="#111111"""") && row.contains("<b>int</b>"), row)
    }
}

class TableStyleTest {
    private val style = CodeBlockStyle("#f6f5f3", "#e3e2e2")
    private val md = "| Your need | Where it lives |\n|---|---|\n| Send snippets | `showTextDocument` |\n| Jump | `openFileDescriptor` |\n\nAfter the table."

    @Test
    fun `a table is a bordered card with lines between rows only`() {
        val html = MarkdownHtml.render(md, codeStyle = style)
        assertTrue(html.contains("""style="border: 1px solid #e3e2e2"><tr><td><table class="cline-md-table""""), html)
        assertTrue(html.contains("</table></td></tr></table>"), html)
        // The header and the first body row have a line under them; the last row does not.
        assertEquals(4, Regex("border-bottom: 1px solid #e3e2e2").findAll(html).count(), html)
        assertTrue(html.contains("font-weight: bold"), html)
        assertTrue(html.contains("<code>openFileDescriptor</code>"), html)
        assertTrue(html.contains("""align="left""""), html)
        assertTrue(html.contains("<p>After the table.</p>"), html)
    }

    @Test
    fun `code in a cell keeps its spaces together and code outside tables is left alone`() {
        val html = MarkdownHtml.render("| a |\n|---|\n| `POST /authorize now` |\n\nPlain `two words` here", codeStyle = style)
        assertTrue(html.contains("<code>POST&nbsp;/authorize&nbsp;now</code>"), html)
        assertTrue(html.contains("<code>two words</code>"), html)
    }

    @Test
    fun `a table without a style keeps the default rendering`() {
        val html = MarkdownHtml.render(md)
        assertFalse(html.contains("cline-md-table") || html.contains("border-bottom"), html)
        assertTrue(html.contains("<table>"), html)
    }

    @Test
    fun `a table at the very end of the reply is closed`() {
        val html = MarkdownHtml.render("| a |\n|---|\n| b |", codeStyle = style)
        assertEquals(Regex("<table").findAll(html).count(), Regex("</table>").findAll(html).count(), html)
    }

    @Test
    fun `inline code and links inside cells still work`() {
        val html = MarkdownHtml.render(md, mapOf("showTextDocument" to FileLink("/a", null, null)), style)
        assertTrue(html.contains("cline-file:showTextDocument"), html)
    }
}

class MarkdownBlocksTest {
    private val style = CodeBlockStyle("#f6f5f3", "#e3e2e2") { language, code -> "[$language]" + code }
    private val md = "Intro `x`\n\n```java\nint a;\n```\n\n| Name | Description of it |\n|---|---|\n| `a b` | A much longer description |\n| c | d |\n\nOutro\n\n    indented"

    @Test
    fun `a reply splits into text code and table blocks in order`() {
        val blocks = MarkdownHtml.blocks(md, style = style)
        assertEquals(
            listOf(MarkdownHtml.BlockKind.TEXT, MarkdownHtml.BlockKind.CODE, MarkdownHtml.BlockKind.TABLE, MarkdownHtml.BlockKind.TEXT, MarkdownHtml.BlockKind.CODE),
            blocks.map { it.kind },
        )
        assertTrue(blocks[0].html.contains("<code>x</code>"))
        assertEquals("<pre>[java]int a;</pre>", blocks[1].html)
        assertTrue(blocks[3].html.contains("Outro"))
        assertTrue(blocks[4].html.contains("indented"))
    }

    @Test
    fun `a table block has a bold header lines between rows and widths from its content`() {
        val table = MarkdownHtml.blocks(md, style = style).first { it.kind == MarkdownHtml.BlockKind.TABLE }.html
        assertTrue(table.contains("font-weight: bold") && table.contains("""align="left""""), table)
        assertEquals(4, Regex("border-bottom: 1px solid #e3e2e2").findAll(table).count(), table)
        val widths = Regex("""<th[^>]*width="(\d+)%"""").findAll(table).map { it.groupValues[1].toInt() }.toList()
        assertEquals(2, widths.size)
        assertTrue(widths[1] > widths[0], "the longer column is wider: $widths")
        assertTrue(table.contains("<code>a&nbsp;b</code>"), table)
    }

    @Test
    fun `only the header cells get the header background`() {
        val table = MarkdownHtml.blocks(md, style = CodeBlockStyle("#f6f5f3", "#e3e2e2", "#eeece8")).first { it.kind == MarkdownHtml.BlockKind.TABLE }.html
        assertEquals(2, Regex("""<th[^>]*bgcolor="#eeece8"""").findAll(table).count(), table)
        assertEquals(0, Regex("""<td[^>]*bgcolor""").findAll(table).count(), table)
        assertFalse(MarkdownHtml.blocks(md).first { it.kind == MarkdownHtml.BlockKind.TABLE }.html.contains("bgcolor"))
    }

    @Test
    fun `links work in text and table blocks`() {
        val html = MarkdownHtml.blocks("`src/a.kt`\n\n| f |\n|---|\n| `src/a.kt` |", mapOf("src/a.kt" to FileLink("/a", null, null)), style).joinToString("") { it.html }
        assertEquals(2, Regex("cline-file:").findAll(html).count(), html)
    }

    @Test
    fun `raw html in blocks is still shown as text and plain replies are one text block`() {
        assertEquals(1, MarkdownHtml.blocks("just text <b>x</b>").size)
        assertTrue(MarkdownHtml.blocks("a <script>x</script>")[0].html.contains("&lt;script&gt;"))
        assertEquals(emptyList(), MarkdownHtml.blocks(""))
    }

    @Test
    fun `markdown rows offer blocks that match their html`() {
        val view = RowRenderer.render(TranscriptItem.Single(say(1, "text", md)), highlighter = style.highlighter)
        assertEquals(5, view.blocks!!(emptyMap()).size)
        assertTrue(view.blocks!!(emptyMap()).any { it.html == "<pre>[java]int a;</pre>" })
    }
}
