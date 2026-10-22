package bot.cline.jetbrains.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ActivityRendererTest {
    private fun cmd(ts: Long, text: String, output: String? = null) =
        Transcript.combineCommandSequences(listOfNotNull(say(ts, "command", text), output?.let { say(ts + 1000, "command_output", it) })).single()

    private fun view(vararg messages: ChatMessage, running: Boolean = false) =
        ActivityRenderer.render(TranscriptItem.Activity(messages.toList(), running))

    @Test
    fun `finished commands say Ran and the header counts them`() {
        val v = view(cmd(1, "python3 script.py --flag"), cmd(2, "sed -i s/a/b/ file"), cmd(3, "cd /x && ./gradlew test"))
        assertEquals("Ran 3 commands", v.title)
        assertEquals(listOf("Ran", "Ran", "Ran"), v.steps.map { it.verb })
        assertEquals("python3 script.py --flag", v.steps[0].target)
        assertEquals("Ran a command", v.steps[0].openTitle)
        assertTrue(v.steps.all { it.isCommand })
    }

    @Test
    fun `the step in progress says Running and earlier ones say Ran`() {
        val v = view(cmd(1, "ls"), cmd(2, "make build"), running = true)
        assertEquals("Running 2 commands", v.title)
        assertEquals(listOf("Ran", "Running"), v.steps.map { it.verb })
        assertEquals(listOf(false, true), v.steps.map { it.running })
        assertEquals("Running a command", v.steps[1].openTitle)
    }

    @Test
    fun `a long or multi line command is cut for the one line view`() {
        val long = view(cmd(1, "find . -iname '*AuthCapture*' -not -path '*/node_modules/*' -not -path '*/.git/*'")).steps[0]
        assertTrue(long.target.endsWith("…") && long.target.length <= 49, long.target)
        val multi = view(cmd(2, "echo a\necho b")).steps[0]
        assertEquals("echo a…", multi.target)
    }

    @Test
    fun `a command's detail is a terminal block with the output`() {
        val html = view(cmd(1, "ls -la", "a.txt\nb<c>.txt")).steps[0].detailHtml(emptyMap())
        assertTrue(html.contains("$ </font>ls -la"), html)
        assertTrue(html.contains("a.txt") && html.contains("b&lt;c&gt;.txt"), html)
    }

    @Test
    fun `tools read like Read a dot kt and the header names reads`() {
        val v = view(tool(1, "readFile", "src/main/A.kt"), tool(2, "readFile", "src/B.kt"))
        assertEquals("Read 2 files", v.title)
        assertEquals(listOf("A.kt", "B.kt"), v.steps.map { it.target })
        assertEquals("Read A.kt", v.steps[0].openTitle)
        assertEquals(listOf("src/main/A.kt"), v.steps[0].references)
        assertFalse(v.steps[0].isCommand)
        assertEquals("Reading 2 files", view(tool(1, "readFile", "a"), tool(2, "readFile", "b"), running = true).title)
    }

    @Test
    fun `mixed steps are counted as steps and an edit shows its diff`() {
        val edit = tool(2, "editedExistingFile", "src/A.kt", ""","diff":"-old\\n+new"""")
        val v = view(cmd(1, "ls"), edit)
        assertEquals("Ran 2 steps", v.title)
        assertEquals("Edited", v.steps[1].verb)
        assertTrue(v.steps[1].detailHtml(emptyMap()).contains("+new"))
        assertEquals("Ran a step", view(edit).title)
    }

    @Test
    fun `a search with no folder is named by what it looked for`() {
        val search = ChatMessage(say(1, "tool").raw.deepCopy().also { it.addProperty("text", """{"tool":"searchFiles","regex":"AuthCapture"}""") })
        val v = view(search)
        assertEquals("Searched", v.steps[0].verb)
        assertEquals("AuthCapture", v.steps[0].target)
    }

    @Test
    fun `thinking is a step that the header does not count`() {
        val v = view(say(1, "reasoning", "Looking at **A**"), tool(2, "readFile", "a.kt"), say(3, "reasoning", "more"), tool(4, "readFile", "b.kt"))
        assertEquals("Read 2 files", v.title)
        assertEquals(listOf("Thought", "Read", "Thought", "Read"), v.steps.map { it.verb })
        assertTrue(v.steps[0].isThinking && !v.steps[1].isThinking)
        assertTrue(v.steps[0].detailHtml(emptyMap()).contains("<strong>A</strong>"))
        assertEquals("Thinking", view(say(1, "reasoning", "x"), tool(2, "readFile", "a"), running = true).steps[0].verb.let { "Thinking" })
        val running = view(tool(1, "readFile", "a.kt"), say(2, "reasoning", "x"), running = true)
        assertEquals(listOf("Read", "Thinking"), running.steps.map { it.verb })
        assertEquals("Reading a file", running.title)
    }
}
