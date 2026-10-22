package bot.cline.jetbrains.ide

import bot.cline.jetbrains.bridge.CoreApi
import bot.cline.jetbrains.bridge.Subscription
import bot.cline.jetbrains.chat.ChatMessage
import bot.cline.jetbrains.chat.FileLinks
import bot.cline.jetbrains.chat.MarkdownHtml
import com.google.gson.JsonObject
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import java.util.concurrent.CompletableFuture

class MarkdownRowTest : BasePlatformTestCase() {
    private class FakeCore : CoreApi {
        val calls = mutableListOf<Triple<String, String, String>>()

        override fun call(service: String, method: String, messageJson: String): CompletableFuture<String> {
            calls += Triple(service, method, messageJson)
            val reference = messageJson.substringAfter("\"value\":\"", "").substringBefore('"')
            return CompletableFuture.completedFuture(
                if (method == "resolveFileReference" && reference.startsWith("src/")) """{"path":"/ws/$reference","startLine":4}""" else """{"path":""}""",
            )
        }

        override fun subscribe(service: String, method: String, messageJson: String, onMessage: (String) -> Unit, onClose: (Throwable?) -> Unit) =
            Subscription {}
    }

    private fun message(text: String) = ChatMessage(JsonObject().apply {
        addProperty("ts", 1); addProperty("type", "say"); addProperty("say", "text"); addProperty("text", text)
    })

    fun `test a path that exists becomes a link and one that does not stays code`() {
        val core = FakeCore()
        val row = MarkdownRow(message("See `src/A.kt` and `Missing.kt`."), RowContext(FileLinks(core)))
        UIUtil.dispatchAllInvocationEvents()
        assertTrue(row.html, row.html.contains("cline-file:"))
        assertEquals(1, Regex("cline-file:").findAll(row.html).count())
    }

    fun `test clicking a link opens the file at its line through the core`() {
        val core = FakeCore()
        val row = MarkdownRow(message("`src/A.kt:4`"), RowContext(FileLinks(core)))
        UIUtil.dispatchAllInvocationEvents()
        row.openLink(MarkdownHtml.hrefFor("src/A.kt:4"))
        val open = core.calls.last()
        assertEquals("openFileLocation", open.second)
        assertTrue(open.third, open.third.contains("\"path\":\"/ws/src/A.kt:4\"") && open.third.contains("\"startLine\":4"))
    }

    fun `test the same path is asked about once while text streams`() {
        val core = FakeCore()
        val row = MarkdownRow(message("`src/A.kt`"), RowContext(FileLinks(core)))
        row.update(message("`src/A.kt` and more"))
        row.update(message("`src/A.kt` and more text"))
        assertEquals(1, core.calls.count { it.second == "resolveFileReference" })
    }

    fun `test the row factory picks markdown for text and plain text for unknown kinds`() {
        val ctx = RowContext(FileLinks(FakeCore()))
        assertTrue(ChatRows.create(message("hi"), ctx) is MarkdownRow)
        val unknown = ChatMessage(JsonObject().apply { addProperty("ts", 2); addProperty("type", "say"); addProperty("say", "brand_new"); addProperty("text", "{\"a\":1}") })
        val row = ChatRows.create(unknown, ctx)
        assertTrue(row is PlainTextRow)
        assertEquals("say · brand_new", PlainTextRow.kindLabel(unknown))
        assertEquals("{\n  \"a\": 1\n}", PlainTextRow.prettyText("{\"a\":1}"))
    }
}
