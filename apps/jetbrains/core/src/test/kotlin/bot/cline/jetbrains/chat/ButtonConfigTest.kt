package bot.cline.jetbrains.chat

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals

class ButtonConfigTest {
    private fun m(json: String) = ChatMessage(JsonParser.parseString(json).asJsonObject)

    @Test
    fun `file tools get Save and other tools get Approve`() {
        val edit = m("""{"ts":1,"type":"ask","ask":"tool","text":"{\"tool\":\"editedExistingFile\"}"}""")
        val read = m("""{"ts":2,"type":"ask","ask":"tool","text":"{\"tool\":\"readFile\"}"}""")
        assertEquals("Save", ButtonConfigs.forMessage(edit).primaryText)
        assertEquals("Approve", ButtonConfigs.forMessage(read).primaryText)
        assertEquals("Reject", ButtonConfigs.forMessage(read).secondaryText)
    }

    @Test
    fun `a streaming message offers only Cancel but a streaming error ask keeps its buttons`() {
        val streaming = m("""{"ts":1,"type":"ask","ask":"tool","partial":true}""")
        assertEquals(ButtonConfigs.partial, ButtonConfigs.forMessage(streaming))
        val failedStreaming = m("""{"ts":2,"type":"ask","ask":"api_req_failed","partial":true}""")
        assertEquals("Retry", ButtonConfigs.forMessage(failedStreaming).primaryText)
    }

    @Test
    fun `ask kinds map to their labels and actions`() {
        fun cfg(ask: String) = ButtonConfigs.forMessage(m("""{"ts":1,"type":"ask","ask":"$ask"}"""))
        assertEquals("Run Command" to ButtonAction.APPROVE, cfg("command").let { it.primaryText to it.primaryAction })
        assertEquals("Resume Task" to ButtonAction.PROCEED, cfg("resume_task").let { it.primaryText to it.primaryAction })
        // No Start New Task button: the + in the title bar starts a new chat.
        assertEquals(false, cfg("completion_result").enableButtons)
        assertEquals(null, cfg("completion_result").primaryText)
        assertEquals(false, cfg("resume_completed_task").enableButtons)
        assertEquals("Start New Task with Context" to ButtonAction.NEW_TASK, cfg("new_task").let { it.primaryText to it.primaryAction })
        assertEquals(false, cfg("followup").enableButtons)
        assertEquals("Proceed Anyways", cfg("mistake_limit_reached").primaryText)
        assertEquals(ButtonConfigs.toolApprove, cfg("some_future_ask"))
    }

    @Test
    fun `an unconfirmed cloud creation error offers no button because the plus starts a new chat`() {
        val msg = m("""{"ts":1,"type":"ask","ask":"api_req_failed","text":"{\"code\":\"cloud_creation_unconfirmed\"}"}""")
        assertEquals(ButtonConfigs.cloudCreationUnconfirmed, ButtonConfigs.forMessage(msg))
        assertEquals(false, ButtonConfigs.cloudCreationUnconfirmed.enableButtons)
    }

    @Test
    fun `only the new task with context ask offers a new task button`() {
        val all = listOf(
            ButtonConfigs.apiReqFailed, ButtonConfigs.cloudCreationUnconfirmed, ButtonConfigs.mistakeLimitReached, ButtonConfigs.toolApprove,
            ButtonConfigs.toolSave, ButtonConfigs.command, ButtonConfigs.commandOutput, ButtonConfigs.completionResult, ButtonConfigs.resumeTask,
            ButtonConfigs.resumeCompletedTask, ButtonConfigs.condense, ButtonConfigs.reportBug, ButtonConfigs.partial, ButtonConfigs.default,
        )
        assertEquals(emptyList(), all.filter { ButtonAction.NEW_TASK in listOf(it.primaryAction, it.secondaryAction) })
        assertEquals(ButtonAction.NEW_TASK, ButtonConfigs.newTask.primaryAction)
        assertEquals("Retry" to null, ButtonConfigs.apiReqFailed.primaryText to ButtonConfigs.apiReqFailed.secondaryText)
        assertEquals("Proceed Anyways" to null, ButtonConfigs.mistakeLimitReached.primaryText to ButtonConfigs.mistakeLimitReached.secondaryText)
    }

    @Test
    fun `turn state phase decides the buttons and the anchored ask decides the labels`() {
        val ask = m("""{"ts":7,"type":"ask","ask":"command"}""")
        val tail = m("""{"ts":8,"type":"say","say":"api_req_started","text":"{\"cost\":0.1}"}""")
        val awaiting = TurnState("awaiting_approval", 7, 2)
        assertEquals("Run Command", ButtonConfigs.forState(listOf(ask, tail), awaiting).primaryText)
        assertEquals(ButtonConfigs.default, ButtonConfigs.forState(listOf(ask), TurnState("idle", null, 3)))
        assertEquals(ButtonConfigs.partial, ButtonConfigs.forState(listOf(ask), TurnState("streaming", null, 3)))
        assertEquals(ButtonConfigs.foregroundCommandRunning, ButtonConfigs.forState(listOf(ask), TurnState("streaming", null, 3), true))
        assertEquals(ButtonConfigs.resumeTask, ButtonConfigs.forState(emptyList(), TurnState("resumable", null, 3)))
        assertEquals(ButtonConfigs.apiReqFailed, ButtonConfigs.forState(emptyList(), TurnState("error", null, 3)))
    }

    @Test
    fun `without a turn state the last non inert message decides`() {
        val ask = m("""{"ts":7,"type":"ask","ask":"tool","text":"{}"}""")
        val usage = m("""{"ts":8,"type":"say","say":"api_req_started","text":"{\"cost\":0.1}"}""")
        assertEquals("Approve", ButtonConfigs.forState(listOf(ask, usage), null).primaryText)
        val active = m("""{"ts":9,"type":"say","say":"api_req_started","text":"{}"}""")
        assertEquals(ButtonConfigs.apiReqActive, ButtonConfigs.forState(listOf(ask, active), null))
    }
}
