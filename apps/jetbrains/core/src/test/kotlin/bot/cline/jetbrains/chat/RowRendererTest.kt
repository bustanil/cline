package bot.cline.jetbrains.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RowRendererTest {
    private fun render(m: ChatMessage, links: Map<String, FileLink> = emptyMap()) =
        RowRenderer.render(TranscriptItem.Single(m)).let { it to it.html(links) }

    @Test
    fun `text is markdown and file references are collected`() {
        val (view, html) = render(say(1, "text", "Edit `src/a.kt:3` now"))
        assertEquals(listOf("src/a.kt:3"), view.references)
        assertTrue(html.contains("<code>src/a.kt:3</code>") && !html.contains("cline-file"))
        val linked = view.html(mapOf("src/a.kt:3" to FileLink("/ws/src/a.kt", 3, null)))
        assertTrue(linked.contains("cline-file:"), linked)
    }

    @Test
    fun `task and user feedback get a heading`() {
        assertTrue(render(say(1, "task", "Do it")).second.contains("<b>Task</b>"))
        assertTrue(render(say(2, "user_feedback", "ok")).second.contains("<b>You</b>"))
    }

    @Test
    fun `reasoning starts collapsed and completion has a heading`() {
        assertEquals("Thinking", render(say(1, "reasoning", "hmm")).first.collapsedTitle)
        assertEquals(null, render(say(1, "text", "hi")).first.collapsedTitle)
        assertTrue(render(ask(2, "completion_result", "All done")).second.contains("Task completed"))
    }

    @Test
    fun `an approval for an edit says wants to edit and shows a colored diff`() {
        val diff = "--- a\\n+++ b\\n@@\\n-old line\\n+new line\\n context"
        val m = tool(1, "editedExistingFile", "src/a.kt", ""","diff":"$diff"""", type = "ask")
        val (view, html) = render(m, mapOf("src/a.kt" to FileLink("/ws/src/a.kt", null, null)))
        assertTrue(html.contains("Cline wants to edit") && html.contains("""<a href="cline-file:src%2Fa.kt">"""), html)
        assertTrue(html.contains("""<font color="#2e9e4f">+new line</font>"""), html)
        assertTrue(html.contains("""<font color="#d03b3b">-old line</font>"""), html)
        assertFalse(html.contains("""color="#2e9e4f">+++"""), html)
        assertEquals(listOf("src/a.kt"), view.references)
    }

    @Test
    fun `a finished read links the path with its lines`() {
        val m = tool(1, "readFile", "src/a.kt", ""","readLineStart":12,"readLineEnd":20""")
        val (view, html) = render(m, mapOf("src/a.kt:12-20" to FileLink("/ws/src/a.kt", 12, 20)))
        assertTrue(html.contains("Cline read") && html.contains("cline-file:src%2Fa.kt%3A12-20"), html)
        assertEquals(listOf("src/a.kt:12-20"), view.references)
    }

    @Test
    fun `a new file shows a preview that is cut off`() {
        val content = (1..50).joinToString("\\n") { "line $it" }
        val html = render(tool(1, "newFileCreated", "n.kt", ""","content":"$content"""")).second
        assertTrue(html.contains("line 40") && !html.contains("line 41"), html)
        assertTrue(html.contains("10 more lines"), html)
    }

    @Test
    fun `model supplied text is escaped`() {
        val html = render(tool(1, "readFile", "<img src=x>.kt")).second
        assertFalse(html.contains("<img"), html)
        assertTrue(html.contains("&lt;img"), html)
    }

    @Test
    fun `a command shows the command and the tail of long output`() {
        val output = (1..50).joinToString("\n") { "out $it" }
        val combined = Transcript.combineCommandSequences(listOf(ask(1, "command", "ls -la"), ask(2, "command_output", output))).single()
        val html = render(combined).second
        assertTrue(html.contains("Cline wants to run") && html.contains("ls -la"), html)
        assertTrue(html.contains("out 50") && !html.contains("out 10<") && html.contains("10 earlier lines"), html)
    }

    @Test
    fun `a followup offers its options as answers`() {
        val (view, html) = render(ask(1, "followup", """{"question":"Which one?","options":["A","B"]}"""))
        assertEquals(listOf("A", "B"), view.options)
        assertTrue(html.contains("Which one?"))
    }

    @Test
    fun `failures show in the error color`() {
        assertTrue(render(say(1, "error", "bad <thing>")).second.contains("bad &lt;thing&gt;"))
        assertTrue(render(ask(2, "api_req_failed", """{"message":"Unauthorized"}""")).second.contains("Unauthorized"))
        assertTrue(render(say(3, "api_req_started", """{"streamingFailedMessage":"boom"}""")).second.contains("Request failed: boom"))
        assertTrue(render(say(4, "api_req_started", """{"cancelReason":"user_cancelled"}""")).second.contains("cancelled (user_cancelled)"))
    }

    @Test
    fun `an unknown kind falls back to a label and the pretty text`() {
        val html = render(say(1, "brand_new_kind", """{"a":1}""")).second
        assertTrue(html.contains("say · brand_new_kind"), html)
        assertTrue(html.contains("&quot;a&quot;: 1"), html)
        val partial = ChatMessage(com.google.gson.JsonObject().apply { addProperty("ts", 2); addProperty("type", "ask"); addProperty("ask", "other_ask"); addProperty("partial", true); addProperty("text", "t") })
        assertTrue(render(partial).second.contains("ask · other_ask · …"))
    }

    @Test
    fun `a tool group lists one line per call`() {
        val group = TranscriptItem.ToolGroup(listOf(tool(1, "readFile", "a.kt"), tool(2, "searchFiles", "src", ""","regex":"TODO"""")))
        val html = RowRenderer.render(group).html(emptyMap())
        assertTrue(html.contains("Cline read") && html.contains("Cline searched") && html.contains("TODO"), html)
        assertEquals(listOf("a.kt", "src"), RowRenderer.render(group).references)
    }
}
