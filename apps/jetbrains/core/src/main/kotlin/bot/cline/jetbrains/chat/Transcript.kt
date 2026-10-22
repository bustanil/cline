package bot.cline.jetbrains.chat

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** One drawn row of the chat: a single message, or a run of read-only tool calls shown together. */
sealed interface TranscriptItem {
    /** Stable while the row grows, so the UI can update it in place. */
    val key: Long

    data class Single(val message: ChatMessage) : TranscriptItem {
        override val key: Long get() = message.ts
    }

    data class ToolGroup(val messages: List<ChatMessage>) : TranscriptItem {
        override val key: Long get() = messages.first().ts
    }
}

/**
 * Turns the raw message list into what the chat draws. A port of the webview's
 * `combineApiRequests`, `combineCommandSequences` and the parts of
 * `messageUtils` the first version needs: hiding bookkeeping rows and grouping
 * read-only tool calls. Browser sessions and subagents are not grouped; they
 * draw as plain text.
 */
object Transcript {
    const val COMMAND_OUTPUT = "Output:"

    private val lowStakesTools = setOf("readFile", "listFilesTopLevel", "listFilesRecursive", "listCodeDefinitionNames", "searchFiles")

    fun build(messages: List<ChatMessage>): List<TranscriptItem> =
        group(visible(combineCommandSequences(combineApiRequests(messages))))

    /** Merges each `api_req_started` with the `api_req_finished` after it, and drops the finished rows. */
    fun combineApiRequests(messages: List<ChatMessage>): List<ChatMessage> {
        val combined = HashMap<Long, ChatMessage>()
        var i = 0
        while (i < messages.size) {
            val m = messages[i]
            val started = if (m.isSay("api_req_started")) parseObject(m.text()) else null
            if (started != null) {
                var j = i + 1
                while (j < messages.size && !messages[j].isSay("api_req_finished")) j++
                if (j < messages.size) {
                    val finished = parseObject(messages[j].text()) ?: JsonObject()
                    val merged = started.deepCopy().also { s -> finished.entrySet().forEach { (k, v) -> s.add(k, v) } }
                    combined[m.ts] = m.withText(merged.toString())
                    i = j
                }
            }
            i++
        }
        return messages.filterNot { it.isSay("api_req_finished") }.map { if (it.isSay("api_req_started")) combined[it.ts] ?: it else it }
    }

    /** Folds the `command_output` messages after a command into the command's text, after an `Output:` line. */
    fun combineCommandSequences(messages: List<ChatMessage>): List<ChatMessage> {
        val combined = HashMap<Long, ChatMessage>()
        var i = 0
        while (i < messages.size) {
            if (messages[i].isCommand()) {
                val text = StringBuilder(messages[i].text())
                var sawOutput = false
                var j = i + 1
                while (j < messages.size && !messages[j].isCommand()) {
                    if (messages[j].isCommandOutput()) {
                        if (!sawOutput) {
                            text.append("\n").append(COMMAND_OUTPUT)
                            sawOutput = true
                        }
                        // The core sends an empty output when it hands the exit button back; skip it.
                        messages[j].text().takeIf { it.isNotEmpty() }?.let { text.append("\n").append(it) }
                    }
                    j++
                }
                combined[messages[i].ts] = messages[i].withText(text.toString())
                i = j - 1
            }
            i++
        }
        return messages.filterNot { it.isCommandOutput() }.map { if (it.isCommand()) combined[it.ts] ?: it else it }
    }

    /** Leaves out the rows the user should not see. */
    fun visible(messages: List<ChatMessage>): List<ChatMessage> = messages.filterIndexed { index, m ->
        if (isEchoOfAskOption(m, messages.getOrNull(index - 1))) return@filterIndexed false
        when (m.ask()) {
            "completion_result" -> if (m.text().isEmpty()) return@filterIndexed false
            "resume_task", "resume_completed_task" -> return@filterIndexed false
            "use_subagents" -> if (messages.drop(index + 1).any { it.isSay("subagent") }) return@filterIndexed false
        }
        when (m.say()) {
            "api_req_finished", "deleted_api_reqs", "subagent_usage", "checkpoint_created", "mcp_server_request_started" -> false
            // A request row has nothing to show unless it failed or was cancelled.
            "api_req_started" -> parseObject(m.text()).let { info -> info == null || info.has("cancelReason") || info.has("streamingFailedMessage") }
            "text" -> m.text().isNotEmpty() || m.raw.getAsJsonArray("images")?.size()?.let { it > 0 } == true
            "use_subagents" -> messages.drop(index + 1).none { it.isSay("subagent") }
            else -> true
        }
    }

    /** Runs of consecutive read-only tool calls become one group; everything else stays single. */
    fun group(messages: List<ChatMessage>): List<TranscriptItem> {
        val items = mutableListOf<TranscriptItem>()
        val run = mutableListOf<ChatMessage>()
        fun flush() {
            when (run.size) {
                0 -> {}
                1 -> items += TranscriptItem.Single(run[0])
                else -> items += TranscriptItem.ToolGroup(run.toList())
            }
            run.clear()
        }
        for (m in messages) {
            if (isLowStakesTool(m)) run += m else { flush(); items += TranscriptItem.Single(m) }
        }
        flush()
        return items
    }

    fun isLowStakesTool(m: ChatMessage): Boolean =
        (m.isSay("tool") || m.isAsk("tool")) && ToolInfo.parse(m)?.tool in lowStakesTools

    // The user's answer to a followup that is just one of its offered options is shown by the question itself.
    private fun isEchoOfAskOption(m: ChatMessage, previous: ChatMessage?): Boolean {
        if (!m.isSay("user_feedback") || previous == null) return false
        if (m.raw.getAsJsonArray("images")?.size() ?: 0 > 0 || m.raw.getAsJsonArray("files")?.size() ?: 0 > 0) return false
        if (!previous.isAsk("followup") && !previous.isAsk("plan_mode_respond")) return false
        val answer = m.text().takeIf { it.isNotEmpty() } ?: return false
        val question = AskQuestion.parse(previous) ?: return false
        return answer in question.options && (question.selected == null || question.selected == answer)
    }

    private fun parseObject(text: String): JsonObject? = runCatching { JsonParser.parseString(text.ifEmpty { "{}" }).asJsonObject }.getOrNull()
}

internal fun ChatMessage.text(): String = raw.get("text")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()

internal fun ChatMessage.ask(): String? = raw.get("ask")?.takeIf { it.isJsonPrimitive }?.asString

internal fun ChatMessage.say(): String? = raw.get("say")?.takeIf { it.isJsonPrimitive }?.asString

internal fun ChatMessage.isSay(kind: String) = raw.get("type")?.asString == "say" && say() == kind

internal fun ChatMessage.isAsk(kind: String) = raw.get("type")?.asString == "ask" && ask() == kind

internal fun ChatMessage.isCommand() = ask() == "command" || say() == "command"

internal fun ChatMessage.isCommandOutput() = ask() == "command_output" || say() == "command_output"

internal fun ChatMessage.withText(text: String) = ChatMessage(raw.deepCopy().also { it.addProperty("text", text) })
