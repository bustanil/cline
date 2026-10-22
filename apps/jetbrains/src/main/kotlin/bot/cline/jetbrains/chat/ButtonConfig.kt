package bot.cline.jetbrains.chat

import com.google.gson.JsonParser

/** What a footer button does. Same names as the webview's `ButtonActionType`. */
enum class ButtonAction { APPROVE, REJECT, PROCEED, PROCEED_WHILE_RUNNING, NEW_TASK, CANCEL, UTILITY, RETRY }

/** The two footer buttons and whether the input box is usable. */
data class ButtonConfig(
    val sendingDisabled: Boolean,
    val enableButtons: Boolean,
    val primaryText: String? = null,
    val secondaryText: String? = null,
    val primaryAction: ButtonAction? = null,
    val secondaryAction: ButtonAction? = null,
)

/**
 * A port of `webview-ui/.../chat-view/shared/buttonConfig.ts`: the single
 * source of truth for what the two buttons say and do for each `ask` kind.
 * Keep it in step with that file.
 */
object ButtonConfigs {
    private const val CLOUD_CREATION_UNCONFIRMED = "cloud_creation_unconfirmed"

    private fun approveReject(primary: String) =
        ButtonConfig(false, true, primary, "Reject", ButtonAction.APPROVE, ButtonAction.REJECT)

    private fun single(text: String, action: ButtonAction) = ButtonConfig(false, true, text, null, action, null)

    val default = ButtonConfig(sendingDisabled = false, enableButtons = false)
    val apiReqFailed = ButtonConfig(true, true, "Retry", "Start New Task", ButtonAction.RETRY, ButtonAction.NEW_TASK)
    val cloudCreationUnconfirmed = ButtonConfig(true, true, "Start New Task", null, ButtonAction.NEW_TASK, null)
    val mistakeLimitReached = ButtonConfig(false, true, "Proceed Anyways", "Start New Task", ButtonAction.PROCEED, ButtonAction.NEW_TASK)
    val toolApprove = approveReject("Approve")
    val toolSave = approveReject("Save")
    val command = approveReject("Run Command")
    val commandOutput = single("Proceed While Running", ButtonAction.PROCEED)
    val completionResult = single("Start New Task", ButtonAction.NEW_TASK)
    val resumeTask = single("Resume Task", ButtonAction.PROCEED)
    val resumeCompletedTask = single("Start New Task", ButtonAction.NEW_TASK)
    val newTask = single("Start New Task with Context", ButtonAction.NEW_TASK)
    val condense = single("Condense Conversation", ButtonAction.UTILITY)
    val reportBug = single("Report GitHub issue", ButtonAction.UTILITY)
    val partial = ButtonConfig(true, true, null, "Cancel", null, ButtonAction.CANCEL)
    val foregroundCommandRunning =
        ButtonConfig(true, true, "Proceed While Running", "Cancel", ButtonAction.PROCEED_WHILE_RUNNING, ButtonAction.CANCEL)
    val apiReqActive = ButtonConfig(true, true, null, "Cancel", null, ButtonAction.CANCEL)

    private val errorAsks = setOf("api_req_failed", "mistake_limit_reached")
    private val fileTools = setOf("editedExistingFile", "newFileCreated", "fileDeleted")
    private val inertSays = setOf("api_req_finished", "deleted_api_reqs", "mcp_server_request_started", "subagent_usage")

    /** Buttons for one message, as `getButtonConfig` does. */
    fun forMessage(message: ChatMessage?): ButtonConfig {
        if (message == null) return default
        val type = message.raw.str("type")
        val ask = message.raw.str("ask")
        val say = message.raw.str("say")
        val isStreaming = message.raw.get("partial")?.let { it.isJsonPrimitive && it.asBoolean } == true
        val isError = ask != null && ask in errorAsks

        if (type == "ask" && ask == "command_output") return commandOutput
        if (isStreaming && !isError) return partial

        if (type == "ask") {
            return when (ask) {
                "api_req_failed" ->
                    if (message.raw.str("text")?.contains("\"$CLOUD_CREATION_UNCONFIRMED\"") == true) cloudCreationUnconfirmed else apiReqFailed
                "mistake_limit_reached" -> mistakeLimitReached
                "tool" -> if (toolName(message) in fileTools) toolSave else toolApprove
                "command" -> command
                "followup", "plan_mode_respond" -> default
                "browser_action_launch", "use_mcp_server", "use_subagents" -> toolApprove
                "completion_result" -> completionResult
                "resume_task" -> resumeTask
                "resume_completed_task" -> resumeCompletedTask
                "new_task" -> newTask
                "condense" -> condense
                "report_bug" -> reportBug
                else -> toolApprove
            }
        }
        if (type == "say" && say == "api_req_started") return apiReqActive
        if (type == "say" && say == "command_output") return commandOutput
        return partial
    }

    /** The last message that is not a bookkeeping row decides the buttons (legacy path). */
    fun forMessages(messages: List<ChatMessage>): ButtonConfig {
        for (message in messages.asReversed()) {
            if (!isInert(message)) return forMessage(message)
        }
        return default
    }

    /** Buttons for a [TurnState] phase; the anchored message decides labels for approvals. */
    fun forPhase(turnState: TurnState, anchored: ChatMessage?, foregroundCommandRunning: Boolean = false): ButtonConfig =
        when (turnState.phase) {
            "idle" -> default
            "streaming" -> if (foregroundCommandRunning) this.foregroundCommandRunning else partial
            "completed" -> completionResult
            "resumable" -> resumeTask
            "error" ->
                if (anchored?.raw?.str("type") == "ask" && anchored.raw.str("ask") in errorAsks) forMessage(anchored) else apiReqFailed
            "awaiting_followup" -> if (anchored != null) forMessage(anchored) else default
            "awaiting_approval" -> if (anchored != null) forMessage(anchored) else toolApprove
            else -> default
        }

    /** The entry point: the authoritative turn state when present, else the legacy walk. */
    fun forState(messages: List<ChatMessage>, turnState: TurnState?, foregroundCommandRunning: Boolean = false): ButtonConfig {
        if (turnState != null) {
            val anchored = turnState.anchorTs?.let { ts -> messages.firstOrNull { it.ts == ts } }
            return forPhase(turnState, anchored, foregroundCommandRunning)
        }
        return forMessages(messages)
    }

    private fun isInert(message: ChatMessage): Boolean {
        if (message.raw.str("type") != "say") return false
        val say = message.raw.str("say")
        if (say == "api_req_started") {
            return runCatching {
                val info = JsonParser.parseString(message.raw.str("text") ?: "{}").asJsonObject
                listOf("cost", "cancelReason", "streamingFailedMessage").any { info.get(it)?.isJsonNull == false }
            }.getOrDefault(false)
        }
        return say in inertSays
    }

    private fun toolName(message: ChatMessage): String? =
        runCatching { JsonParser.parseString(message.raw.str("text") ?: "{}").asJsonObject.get("tool")?.asString }.getOrNull()
}

internal fun com.google.gson.JsonObject.str(name: String): String? =
    get(name)?.takeIf { it.isJsonPrimitive }?.asString
