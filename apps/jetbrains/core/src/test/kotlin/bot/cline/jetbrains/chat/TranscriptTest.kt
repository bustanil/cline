package bot.cline.jetbrains.chat

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CombineApiRequestsTest {
    @Test
    fun `merges a started request with its finished payload`() {
        val out = Transcript.combineApiRequests(
            listOf(say(1, "api_req_started", """{"request":"GET /api/data"}"""), say(2, "api_req_finished", """{"cost":0.005,"tokensIn":10,"tokensOut":5}""")),
        )
        assertEquals(1, out.size)
        assertEquals(JsonParser.parseString("""{"request":"GET /api/data","cost":0.005,"tokensIn":10,"tokensOut":5}"""), out[0].json())
        assertEquals(1L, out[0].ts)
    }

    @Test
    fun `keeps a started request that never finished`() {
        val out = Transcript.combineApiRequests(listOf(say(1, "api_req_started", """{"request":"A"}""")))
        assertEquals("""{"request":"A"}""", out.single().text())
    }

    @Test
    fun `a malformed started payload is kept and does not throw`() {
        val out = Transcript.combineApiRequests(listOf(say(1, "api_req_started", "{not-json"), say(2, "text", "hello")))
        assertEquals(listOf("{not-json", "hello"), out.map { it.text() })
    }

    @Test
    fun `a malformed finished payload still consumes the pair`() {
        val out = Transcript.combineApiRequests(listOf(say(1, "api_req_started", """{"request":"A","cost":0.1}"""), say(2, "api_req_finished", "{not-json")))
        assertEquals(1, out.size)
        assertEquals(0.1, out[0].json().get("cost").asDouble)
    }

    @Test
    fun `pairs each started request with the finished one after it`() {
        val out = Transcript.combineApiRequests(
            listOf(
                say(1, "api_req_started", """{"n":1}"""), say(2, "api_req_finished", """{"cost":1}"""),
                say(3, "api_req_started", """{"n":2}"""), say(4, "api_req_finished", """{"cost":2}"""),
            ),
        )
        assertEquals(listOf(1.0, 2.0), out.map { it.json().get("cost").asDouble })
    }
}

class CombineCommandSequencesTest {
    @Test
    fun `folds outputs into the command after an Output line`() {
        val out = Transcript.combineCommandSequences(listOf(ask(1, "command", "ls"), ask(2, "command_output", "file1.txt"), ask(3, "command_output", "file2.txt")))
        assertEquals(1, out.size)
        assertEquals("ls\nOutput:\nfile1.txt\nfile2.txt", out[0].text())
        assertEquals(CommandInfo("ls", "file1.txt\nfile2.txt"), CommandInfo.parse(out[0]))
    }

    @Test
    fun `a command without output stays as it is and empty outputs are skipped`() {
        assertEquals("pwd", Transcript.combineCommandSequences(listOf(ask(1, "command", "pwd"))).single().text())
        assertEquals("ls\nOutput:", Transcript.combineCommandSequences(listOf(ask(1, "command", "ls"), ask(2, "command_output", ""))).single().text())
        assertEquals(CommandInfo("pwd", null), CommandInfo.parse(ask(1, "command", "pwd")))
    }

    @Test
    fun `outputs go to the command before them and say messages combine too`() {
        val out = Transcript.combineCommandSequences(
            listOf(say(1, "command", "a"), say(2, "command_output", "1"), say(3, "command", "b"), say(4, "command_output", "2"), say(5, "text", "done")),
        )
        assertEquals(listOf("a\nOutput:\n1", "b\nOutput:\n2", "done"), out.map { it.text() })
    }
}

class TranscriptVisibilityTest {
    private fun kinds(vararg m: ChatMessage) = Transcript.visible(m.toList()).map { it.ts }

    @Test
    fun `bookkeeping rows are hidden`() {
        assertEquals(listOf(9L), kinds(say(1, "api_req_finished"), say(2, "deleted_api_reqs"), say(3, "subagent_usage"), say(4, "checkpoint_created"), say(5, "mcp_server_request_started"), ask(6, "resume_task"), ask(7, "resume_completed_task"), say(8, "text", ""), say(9, "text", "hi")))
    }

    @Test
    fun `a request row shows only when it failed or was cancelled or cannot be read`() {
        assertEquals(listOf(2L, 3L, 4L), kinds(say(1, "api_req_started", """{"cost":0.1}"""), say(2, "api_req_started", """{"cancelReason":"user_cancelled"}"""), say(3, "api_req_started", """{"streamingFailedMessage":"boom"}"""), say(4, "api_req_started", "{bad")))
    }

    @Test
    fun `a completion ask without text is hidden`() {
        assertEquals(listOf(2L), kinds(ask(1, "completion_result", ""), ask(2, "completion_result", "Done")))
    }

    @Test
    fun `an answer that echoes an offered option is hidden`() {
        val question = ask(1, "followup", """{"question":"Which?","options":["A","B"]}""")
        assertEquals(listOf(1L, 3L), kinds(question, say(2, "user_feedback", "A"), say(3, "user_feedback", "something else")))
        val selected = ask(1, "followup", """{"question":"Which?","options":["A","B"],"selected":"B"}""")
        assertEquals(listOf(1L, 2L), kinds(selected, say(2, "user_feedback", "A")))
    }

    @Test
    fun `a failed request ask stays visible so the failure is shown`() {
        assertEquals(listOf(1L), kinds(ask(1, "api_req_failed", """{"message":"Unauthorized"}""")))
    }
}

class TranscriptGroupTest {
    private fun shape(items: List<TranscriptItem>) =
        items.map { if (it is TranscriptItem.Activity) "activity:${it.key}:${it.messages.size}${if (it.running) ":running" else ""}" else "single:${it.key}" }

    @Test
    fun `commands and tools that follow each other form one activity and text splits them`() {
        val items = Transcript.group(
            listOf(
                tool(1, "readFile"), say(2, "command", "ls"), tool(3, "editedExistingFile"),
                say(4, "text", "thinking"),
                tool(5, "readFile"),
                say(6, "text", "more"),
                say(7, "command", "a"), say(8, "tool", """{"tool":"readFile","path":"x"}"""),
            ),
        )
        assertEquals(listOf("activity:1:3", "single:4", "activity:5:1", "single:6", "activity:7:2"), shape(items))
    }

    @Test
    fun `thinking between steps folds into the activity and thinking alone stays its own row`() {
        val items = Transcript.group(
            listOf(
                say(1, "reasoning", "plan"), tool(2, "readFile"), say(3, "reasoning", "hmm"), tool(4, "readFile"), say(5, "reasoning", "ok"),
                say(6, "text", "Here is the answer"),
                say(7, "reasoning", "alone"),
                say(8, "text", "bye"),
            ),
        )
        assertEquals(listOf("activity:1:5", "single:6", "single:7", "single:8"), shape(items))
    }

    @Test
    fun `the last activity runs while the turn streams and finished ones do not`() {
        val messages = listOf(say(1, "command", "a"), say(2, "text", "x"), say(3, "command", "b"))
        assertEquals(listOf("activity:1:1", "single:2", "activity:3:1:running"), shape(Transcript.group(messages, "streaming")))
        assertEquals(listOf("activity:1:1", "single:2", "activity:3:1"), shape(Transcript.group(messages, "idle")))
        assertEquals(listOf("activity:1:1", "single:2", "activity:3:1"), shape(Transcript.group(messages, null)))
    }

    @Test
    fun `an approval that is waiting stays its own row so it is not hidden in a group`() {
        val messages = listOf(say(1, "command", "ls"), tool(2, "editedExistingFile", type = "ask"))
        assertEquals(listOf("activity:1:1", "single:2"), shape(Transcript.group(messages, "awaiting_approval")))
        // Once the turn moves on, the answered ask joins the activity.
        assertEquals(listOf("activity:1:2:running"), shape(Transcript.group(messages, "streaming")))
    }

    @Test
    fun `build runs the whole pipeline`() {
        val items = Transcript.build(
            listOf(
                say(1, "task", "do it"),
                say(2, "api_req_started", """{"request":"x"}"""), say(3, "api_req_finished", """{"cost":0.1}"""),
                ask(4, "command", "ls"), ask(5, "command_output", "a"),
                say(6, "checkpoint_created"),
            ),
            "streaming",
        )
        assertEquals(listOf(1L, 4L), items.map { it.key })
        val activity = items[1] as TranscriptItem.Activity
        assertEquals("ls\nOutput:\na", activity.messages.single().text())
        assertTrue(activity.running)
    }
}

class RowModelsTest {
    @Test
    fun `tool info parses and builds the reference and summary`() {
        val read = ToolInfo.parse(tool(1, "readFile", "src/a.kt", ""","readLineStart":12,"readLineEnd":20"""))!!
        assertEquals("src/a.kt:12-20", read.reference())
        assertEquals("Cline read src/a.kt", read.summary(true, "src/a.kt"))
        assertEquals("Cline wants to read src/a.kt", read.summary(false, "src/a.kt"))
        assertEquals("src/a.kt:7", ToolInfo.parse(tool(1, "readFile", "src/a.kt", ""","readLineStart":7"""))!!.reference())
        assertEquals("src/a.kt", ToolInfo.parse(tool(1, "editedExistingFile", "src/a.kt"))!!.reference())
        val search = ToolInfo.parse(tool(1, "searchFiles", "src", ""","regex":"TODO""""))!!
        assertEquals("Cline searched src for TODO", search.summary(true, "src"))
        assertTrue(ToolInfo.parse(say(1, "tool", "not json")) == null)
    }

    @Test
    fun `ask question parses options and falls back to plain text`() {
        val q = AskQuestion.parse(ask(1, "followup", """{"question":"Which?","options":["A","B"],"selected":"A"}"""))!!
        assertEquals(AskQuestion("Which?", listOf("A", "B"), "A"), q)
        assertEquals("The plan", AskQuestion.parse(ask(1, "plan_mode_respond", """{"response":"The plan"}"""))!!.text)
        assertEquals(AskQuestion("just text", emptyList(), null), AskQuestion.parse(ask(1, "followup", "just text")))
    }

    @Test
    fun `api metrics add up requests and describe them`() {
        val metrics = ApiMetrics.of(
            listOf(
                say(1, "api_req_started", """{"tokensIn":1000,"tokensOut":200}"""), say(2, "api_req_finished", """{"cost":0.01}"""),
                say(3, "api_req_started", """{"tokensIn":500,"tokensOut":50,"cost":0.0123}"""),
            ),
        )
        assertEquals(ApiMetrics(1500, 250, 0.0223), metrics.copy(cost = Math.round(metrics.cost * 10000) / 10000.0))
        assertEquals("", ApiMetrics.describe(ApiMetrics(0, 0, 0.0)))
        assertTrue(ApiMetrics.describe(metrics).contains("1.5k in"))
    }

    @Test
    fun `error text reads the message of a JSON error`() {
        assertEquals("Unauthorized", ErrorText.of(ask(1, "api_req_failed", """{"message":"Unauthorized","code":"x"}""")))
        assertEquals("plain failure", ErrorText.of(ask(1, "api_req_failed", "plain failure")))
        assertEquals("deep", ErrorText.of(ask(1, "api_req_failed", """{"error":{"message":"deep"}}""")))
    }
}

class HoldUnfinishedTest {
    private fun say(ts: Long, kind: String, partial: Boolean = false) = ChatMessage(
        JsonObject().apply {
            addProperty("ts", ts)
            addProperty("type", "say")
            addProperty("say", kind)
            addProperty("text", "x")
            if (partial) addProperty("partial", true)
        },
    )

    private fun rows(vararg m: ChatMessage): List<TranscriptItem> = m.map { TranscriptItem.Single(it) }

    @Test
    fun `an answer still being written is left out while the turn streams`() {
        val out = Transcript.holdUnfinished(rows(say(1, "task"), say(2, "text", partial = true)), "streaming")
        assertEquals(listOf(1L), out.map { it.key })
    }

    @Test
    fun `a finished answer, a later row or an ended turn shows it`() {
        assertEquals(2, Transcript.holdUnfinished(rows(say(1, "task"), say(2, "text")), "streaming").size)
        assertEquals(3, Transcript.holdUnfinished(rows(say(1, "task"), say(2, "text", partial = true), say(3, "tool")), "streaming").size)
        assertEquals(2, Transcript.holdUnfinished(rows(say(1, "task"), say(2, "text", partial = true)), "idle").size)
    }

    @Test
    fun `a tool row that is still partial is not held`() {
        assertEquals(2, Transcript.holdUnfinished(rows(say(1, "task"), say(2, "tool", partial = true)), "streaming").size)
    }
}

class McpRowsTest {
    private fun msg(ts: Long, type: String, kind: String, text: String) = ChatMessage(
        JsonObject().apply {
            addProperty("ts", ts)
            addProperty("type", type)
            addProperty(type, kind)
            addProperty("text", text)
        },
    )

    private val call = """{"type":"use_mcp_tool","serverName":"jetbrains-idea","toolName":"list_directory_tree","arguments":"{\"directoryPath\":\".\"}"}"""

    @Test
    fun `an MCP call and its answer become one activity step`() {
        val items = Transcript.build(
            listOf(msg(1, "say", "use_mcp_server", call), msg(2, "say", "mcp_server_response", "{\"tree\":\"a\"}")),
            "idle",
        )
        val activity = items.single() as TranscriptItem.Activity
        assertEquals(1, activity.messages.size)
        val view = ActivityRenderer.render(activity)
        val step = view.steps.single()
        assertEquals("Used", step.verb)
        assertTrue(step.target.startsWith("list_directory_tree"), step.target)
        val html = step.detailHtml(emptyMap())
        assertTrue(html.contains("jetbrains-idea") && html.contains("directoryPath") && html.contains("tree"), html)
    }

    @Test
    fun `an MCP call waiting for approval is its own row with the server and tool`() {
        val items = Transcript.build(listOf(msg(1, "ask", "use_mcp_server", call)), "awaiting_approval")
        val row = RowRenderer.render(items.single()).html(emptyMap())
        assertTrue(row.contains("wants to use") && row.contains("list_directory_tree"), row)
    }
}

class QuoteSplitTest {
    @Test
    fun `a sent quote and its reply are split apart`() {
        val (quotes, rest) = Quote.split(Quote.format("project", "test"))
        assertEquals(listOf("project"), quotes)
        assertEquals("test", rest)
    }

    @Test
    fun `several quotes and a plain message`() {
        val text = Quote.context("one") + "\n\n" + Quote.format("two", "why?") + "\n\nmore"
        val (quotes, rest) = Quote.split(text)
        assertEquals(listOf("one", "two"), quotes)
        assertEquals("why?\n\nmore", rest)
        assertEquals(emptyList<String>() to "no quote", Quote.split("no quote"))
    }

    @Test
    fun `a user bubble carries the quote as a card and not as markers`() {
        val m = ChatMessage(
            JsonObject().apply {
                addProperty("ts", 1)
                addProperty("type", "say")
                addProperty("say", "user_feedback")
                addProperty("text", Quote.format("project", "test"))
            },
        )
        val view = RowRenderer.render(TranscriptItem.Single(m))
        assertEquals(listOf("project"), view.quotes)
        assertTrue(!view.html(emptyMap()).contains("[context]"))
    }
}
