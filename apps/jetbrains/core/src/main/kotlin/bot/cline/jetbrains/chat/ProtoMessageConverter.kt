package bot.cline.jetbrains.chat

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * Converts a `ClineMessage` in proto3 JSON, as the partial-message stream sends
 * it, into the shape state snapshots use. A port of `convertProtoToClineMessage`
 * in `src/shared/proto-conversions/cline-message.ts`: kinds are strings, empty
 * and default fields are left out.
 *
 * A kind this table does not know is passed through as its lowercase proto
 * name instead of being dropped, so it reaches the plain-text fallback.
 */
object ProtoMessageConverter {
    // The proto default for each enum is the first value; proto3 JSON leaves it out.
    private const val DEFAULT_ASK = "FOLLOWUP"
    private const val DEFAULT_SAY = "TASK"

    private val askNames = mapOf(
        "FOLLOWUP" to "followup",
        "PLAN_MODE_RESPOND" to "plan_mode_respond",
        "ACT_MODE_RESPOND" to "act_mode_respond",
        "COMMAND" to "command",
        "COMMAND_OUTPUT" to "command_output",
        "COMPLETION_RESULT" to "completion_result",
        "TOOL" to "tool",
        "API_REQ_FAILED" to "api_req_failed",
        "RESUME_TASK" to "resume_task",
        "RESUME_COMPLETED_TASK" to "resume_completed_task",
        "MISTAKE_LIMIT_REACHED" to "mistake_limit_reached",
        "BROWSER_ACTION_LAUNCH" to "browser_action_launch",
        "USE_MCP_SERVER" to "use_mcp_server",
        "NEW_TASK" to "new_task",
        "CONDENSE" to "condense",
        "SUMMARIZE_TASK" to "summarize_task",
        "REPORT_BUG" to "report_bug",
        "USE_SUBAGENTS" to "use_subagents",
    )

    private val sayNames = mapOf(
        "TASK" to "task",
        "ERROR" to "error",
        "API_REQ_STARTED" to "api_req_started",
        "API_REQ_FINISHED" to "api_req_finished",
        "TEXT" to "text",
        "REASONING" to "reasoning",
        "COMPLETION_RESULT_SAY" to "completion_result",
        "PLAN_COMPLETION_RESULT" to "plan_completion_result",
        "USER_FEEDBACK" to "user_feedback",
        "USER_FEEDBACK_DIFF" to "user_feedback_diff",
        "COMMAND_SAY" to "command",
        "COMMAND_OUTPUT_SAY" to "command_output",
        "TOOL_SAY" to "tool",
        "SHELL_INTEGRATION_WARNING" to "shell_integration_warning",
        "BROWSER_ACTION_LAUNCH_SAY" to "browser_action_launch",
        "BROWSER_ACTION" to "browser_action",
        "BROWSER_ACTION_RESULT" to "browser_action_result",
        "MCP_SERVER_REQUEST_STARTED" to "mcp_server_request_started",
        "MCP_SERVER_RESPONSE" to "mcp_server_response",
        "MCP_NOTIFICATION" to "mcp_notification",
        "USE_MCP_SERVER_SAY" to "use_mcp_server",
        "DIFF_ERROR" to "diff_error",
        "DELETED_API_REQS" to "deleted_api_reqs",
        "CLINEIGNORE_ERROR" to "clineignore_error",
        "COMMAND_PERMISSION_DENIED" to "command_permission_denied",
        "CHECKPOINT_CREATED" to "checkpoint_created",
        "LOAD_MCP_DOCUMENTATION" to "load_mcp_documentation",
        "INFO" to "info",
        "HOOK_STATUS" to "hook_status",
        "HOOK_OUTPUT_STREAM" to "hook_output_stream",
        "CONDITIONAL_RULES_APPLIED" to "conditional_rules_applied",
        "SUBAGENT_STATUS" to "subagent",
        "USE_SUBAGENTS_SAY" to "use_subagents",
        "SUBAGENT_USAGE" to "subagent_usage",
        "COMPACTION" to "compaction",
    )

    fun convert(proto: JsonObject): JsonObject {
        val out = JsonObject()
        out.add("ts", proto.get("ts") ?: return out)
        val isAsk = (proto.string("type") ?: "ASK") == "ASK" // ASK is enum value 0, the proto3 default
        out.addProperty("type", if (isAsk) "ask" else "say")
        if (isAsk) {
            val raw = proto.string("ask") ?: DEFAULT_ASK
            out.addProperty("ask", askNames[raw] ?: raw.lowercase())
        } else {
            val raw = proto.string("say") ?: DEFAULT_SAY
            out.addProperty("say", sayNames[raw] ?: raw.lowercase())
        }
        for (field in listOf("text", "reasoning", "lastCheckpointHash")) {
            proto.string(field)?.takeIf { it.isNotEmpty() }?.let { out.addProperty(field, it) }
        }
        for (field in listOf("images", "files")) {
            (proto.get(field) as? JsonArray)?.takeIf { it.size() > 0 }?.let { out.add(field, it) }
        }
        for (field in listOf("partial", "isCheckpointCheckedOut", "isOperationOutsideWorkspace")) {
            if (proto.get(field)?.asBooleanOrFalse() == true) out.addProperty(field, true)
        }
        proto.get("conversationHistoryIndex")?.takeIf { it.isNonZeroNumber() }?.let { out.add("conversationHistoryIndex", it) }
        (proto.get("conversationHistoryDeletedRange") as? JsonObject)?.let { range ->
            out.add(
                "conversationHistoryDeletedRange",
                JsonArray().apply {
                    add(range.get("startIndex") ?: com.google.gson.JsonPrimitive(0))
                    add(range.get("endIndex") ?: com.google.gson.JsonPrimitive(0))
                },
            )
        }
        proto.get("modelInfo")?.takeIf { it.isJsonObject }?.let { out.add("modelInfo", it) }
        // 0 means unstamped; leave it out so the replica treats the message as epoch 0.
        proto.get("seq")?.takeIf { it.isNonZeroNumber() }?.let { out.add("seq", it) }
        proto.get("epoch")?.takeIf { it.isNonZeroNumber() }?.let { out.add("epoch", it) }
        return out
    }

    private fun JsonObject.string(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive }?.asString

    private fun JsonElement.isNonZeroNumber(): Boolean = isJsonPrimitive && (asString.toLongOrNull() ?: 0L) != 0L

    // proto3 JSON writes int64 as a string, but booleans stay booleans.
    private fun JsonElement.asBooleanOrFalse(): Boolean = isJsonPrimitive && asString == "true"
}
