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
}
