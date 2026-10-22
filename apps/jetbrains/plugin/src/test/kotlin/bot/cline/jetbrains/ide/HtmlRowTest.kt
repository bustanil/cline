package bot.cline.jetbrains.ide

import bot.cline.jetbrains.bridge.CoreApi
import bot.cline.jetbrains.bridge.Subscription
import bot.cline.jetbrains.chat.ChatMessage
import bot.cline.jetbrains.chat.FileLinks
import bot.cline.jetbrains.chat.MarkdownHtml
import bot.cline.jetbrains.chat.TranscriptItem
import com.google.gson.JsonObject
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import java.util.concurrent.CompletableFuture

class HtmlRowTest : BasePlatformTestCase() {
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

    private fun message(ts: Long, type: String, kind: String, text: String) =
        ChatMessage(JsonObject().apply { addProperty("ts", ts); addProperty("type", type); addProperty(type, kind); addProperty("text", text) })

    private fun item(text: String, ts: Long = 1) = TranscriptItem.Single(message(ts, "say", "text", text))

    private fun row(core: FakeCore, item: TranscriptItem, reply: (String) -> Unit = {}) =
        HtmlRow(item, RowContext(FileLinks(core), reply))

    fun `test a path that exists becomes a link and one that does not stays code`() {
        val row = row(FakeCore(), item("See `src/A.kt` and `Missing.kt`."))
        UIUtil.dispatchAllInvocationEvents()
        assertEquals(1, Regex("cline-file:").findAll(row.html).count())
    }

    fun `test clicking a link opens the file at its line through the core`() {
        val core = FakeCore()
        val row = row(core, item("`src/A.kt:4`"))
        UIUtil.dispatchAllInvocationEvents()
        row.openLink(MarkdownHtml.hrefFor("src/A.kt:4"))
        val open = core.calls.last()
        assertEquals("openFileLocation", open.second)
        assertTrue(open.third, open.third.contains("\"startLine\":4"))
    }

    fun `test the same path is asked about once while text streams`() {
        val core = FakeCore()
        val row = row(core, item("`src/A.kt`"))
        assertTrue(row.update(item("`src/A.kt` and more")))
        assertTrue(row.update(item("`src/A.kt` and more text")))
        assertEquals(1, core.calls.count { it.second == "resolveFileReference" })
    }

    fun `test a row refuses an item with another key`() {
        assertFalse(row(FakeCore(), item("a", ts = 1)).update(item("b", ts = 2)))
    }

    fun `test a tool row links the file it read`() {
        val core = FakeCore()
        val read = TranscriptItem.Single(message(5, "say", "tool", """{"tool":"readFile","path":"src/A.kt","readLineStart":4}"""))
        val row = row(core, read)
        UIUtil.dispatchAllInvocationEvents()
        assertTrue(row.html, row.html.contains("Cline read") && row.html.contains("cline-file:"))
    }

    fun `test options become buttons that send the answer`() {
        val sent = mutableListOf<String>()
        val ask = TranscriptItem.Single(message(7, "ask", "followup", """{"question":"Which?","options":["Alpha","Beta"]}"""))
        val row = row(FakeCore(), ask) { sent += it }
        assertEquals(listOf("Alpha", "Beta"), row.answerButtons.map { it.text })
        row.answerButtons[1].doClick()
        assertEquals(listOf("Beta"), sent)
    }

    fun `test reasoning is collapsed until expanded`() {
        val row = row(FakeCore(), TranscriptItem.Single(message(3, "say", "reasoning", "step by step")))
        assertFalse(row.isExpanded)
        fun hasPane(c: java.awt.Component): Boolean = c is com.intellij.ui.components.JBHtmlPane || (c is java.awt.Container && c.components.any(::hasPane))
        assertFalse(hasPane(row.component))
        row.toggle()
        assertTrue(row.isExpanded)
        assertTrue(hasPane(row.component))
    }

    fun `test a prompt shows its images and files in the bubble`() {
        val pic = java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(java.awt.image.BufferedImage(8, 6, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()
        val url = "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(pic)
        val raw = message(4, "say", "task", "look at these").raw.also {
            it.add("images", com.google.gson.JsonArray().apply { add(url) })
            it.add("files", com.google.gson.JsonArray().apply { add("/work/spec.pdf") })
        }
        val row = row(FakeCore(), TranscriptItem.Single(ChatMessage(raw)))
        assertEquals(listOf("[image]", "spec.pdf"), row.attachmentLabels)
        val plain = row(FakeCore(), TranscriptItem.Single(message(5, "say", "task", "no attachments")))
        assertEquals(emptyList<String>(), plain.attachmentLabels)
    }

    fun `test streaming text changes the words but does not rearrange the row`() {
        val row = row(FakeCore(), item("Hello"))
        val first = row.layouts
        var text = "Hello"
        repeat(30) {
            text += " word$it"
            assertTrue(row.update(item(text)))
        }
        assertEquals("a streamed update must not tear the row down", first, row.layouts)
        assertTrue(row.html.contains("word29"))
    }

    fun `test an update that changes nothing does no work`() {
        val message = message(1, "say", "text", "same words")
        val row = row(FakeCore(), TranscriptItem.Single(message))
        val html = row.html
        val layouts = row.layouts
        repeat(20) { row.update(TranscriptItem.Single(message)) }
        // A copy with equal content counts as unchanged too.
        row.update(TranscriptItem.Single(ChatMessage(message.raw.deepCopy())))
        assertEquals(html, row.html)
        assertEquals(layouts, row.layouts)
    }

    fun `test the row is rearranged when its shape changes`() {
        val row = row(FakeCore(), TranscriptItem.Single(message(1, "ask", "followup", """{"question":"Which?"}""")))
        val before = row.layouts
        row.update(TranscriptItem.Single(message(1, "ask", "followup", """{"question":"Which?","options":["A","B"]}""")))
        assertEquals(before + 1, row.layouts)
        assertEquals(listOf("A", "B"), row.answerButtons.map { it.text })
    }

    fun `test an unknown kind still draws`() {
        val unknown = TranscriptItem.Single(message(9, "say", "brand_new", """{"a":1}"""))
        assertTrue(row(FakeCore(), unknown).html.contains("say · brand_new"))
    }
}
