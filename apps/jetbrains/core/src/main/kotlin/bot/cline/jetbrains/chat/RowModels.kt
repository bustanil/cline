package bot.cline.jetbrains.chat

import com.google.gson.JsonObject
import com.google.gson.JsonParser

private fun parse(text: String): JsonObject? = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()

private fun JsonObject.int(name: String) = get(name)?.takeIf { it.isJsonPrimitive }?.asInt

/** The JSON a `tool` message carries (`ClineSayTool`). */
data class ToolInfo(
    val tool: String,
    val path: String?,
    val diff: String?,
    val content: String?,
    val regex: String?,
    val filePattern: String?,
    val readLineStart: Int?,
    val readLineEnd: Int?,
) {
    /** The path with its read range as a chat reference, such as `src/a.kt:12-20`, for linking. */
    fun reference(): String? = path?.let { p ->
        when {
            readLineStart == null -> p
            readLineEnd == null || readLineEnd <= readLineStart -> "$p:$readLineStart"
            else -> "$p:$readLineStart-$readLineEnd"
        }
    }

    /** One line saying what the tool does, with [subject] standing for the path. */
    fun summary(past: Boolean, subject: String): String {
        fun verb(now: String, done: String) = if (past) done else now
        return when (tool) {
            "editedExistingFile" -> "${verb("Cline wants to edit", "Cline edited")} $subject"
            "newFileCreated" -> "${verb("Cline wants to create", "Cline created")} $subject"
            "fileDeleted" -> "${verb("Cline wants to delete", "Cline deleted")} $subject"
            "readFile" -> "${verb("Cline wants to read", "Cline read")} $subject"
            "listFilesTopLevel" -> "${verb("Cline wants to list the files in", "Cline listed the files in")} $subject"
            "listFilesRecursive" -> "${verb("Cline wants to list all files in", "Cline listed all files in")} $subject"
            "listCodeDefinitionNames" -> "${verb("Cline wants to list the code definitions in", "Cline listed the code definitions in")} $subject"
            "searchFiles" -> "${verb("Cline wants to search", "Cline searched")} $subject for ${regex ?: "…"}"
            "webFetch" -> "${verb("Cline wants to fetch", "Cline fetched")} $subject"
            "webSearch" -> "${verb("Cline wants to search the web for", "Cline searched the web for")} ${path ?: subject}"
            "summarizeTask" -> if (past) "Cline summarized the task" else "Cline wants to summarize the task"
            "useSkill" -> "${verb("Cline wants to use the skill", "Cline used the skill")} $subject"
            else -> "$tool $subject".trim()
        }
    }

    companion object {
        fun parse(message: ChatMessage): ToolInfo? {
            val o = parse(message.text()) ?: return null
            return ToolInfo(
                tool = o.str("tool") ?: return null,
                path = o.str("path"),
                diff = o.str("diff"),
                content = o.str("content"),
                regex = o.str("regex"),
                filePattern = o.str("filePattern"),
                readLineStart = o.int("readLineStart"),
                readLineEnd = o.int("readLineEnd"),
            )
        }
    }
}

/** A question to the user (`followup`) or a plan (`plan_mode_respond`), with optional canned answers. */
data class AskQuestion(val text: String, val options: List<String>, val selected: String?) {
    companion object {
        fun parse(message: ChatMessage): AskQuestion? {
            val o = parse(message.text()) ?: return AskQuestion(message.text(), emptyList(), null).takeIf { message.text().isNotEmpty() }
            val text = o.str("question") ?: o.str("response") ?: return null
            val options = o.getAsJsonArray("options")?.map { it.asString } ?: emptyList()
            return AskQuestion(text, options, o.str("selected"))
        }
    }
}

/** A command, and its output when it has run (`Transcript.combineCommandSequences` joins them). */
data class CommandInfo(val command: String, val output: String?) {
    companion object {
        fun parse(message: ChatMessage): CommandInfo {
            val text = message.text()
            val marker = "\n${Transcript.COMMAND_OUTPUT}"
            val at = text.indexOf(marker)
            if (at < 0) return CommandInfo(text, null)
            return CommandInfo(text.substring(0, at), text.substring(at + marker.length).trimStart('\n'))
        }
    }
}

/** An MCP tool call or resource read (`ClineAskUseMcpServer`), with the server's answer when it has come. */
data class McpInfo(val server: String, val tool: String?, val uri: String?, val arguments: String?, val response: String?) {
    /** What the call is about: the tool name, or the resource it reads. */
    val subject: String get() = tool ?: uri ?: "a resource"

    companion object {
        /** The property [Transcript.combineMcpResponses] adds to a call to hold the answer. */
        const val RESPONSE = "mcpResponse"

        fun parse(message: ChatMessage): McpInfo {
            val o = parse(message.text()) ?: JsonObject()
            return McpInfo(
                server = o.str("serverName").orEmpty(),
                tool = o.str("toolName"),
                uri = o.str("uri"),
                arguments = o.get("arguments")?.takeIf { it.isJsonPrimitive }?.asString,
                response = message.raw.str(RESPONSE),
            )
        }
    }
}

/** The JSON of an `api_req_started` message (`ClineApiReqInfo`). */
data class ApiReqInfo(
    val tokensIn: Int,
    val tokensOut: Int,
    val cost: Double?,
    val cancelReason: String?,
    val failure: String?,
) {
    companion object {
        fun parse(message: ChatMessage): ApiReqInfo {
            val o = parse(message.text()) ?: JsonObject()
            return ApiReqInfo(
                tokensIn = o.int("tokensIn") ?: 0,
                tokensOut = o.int("tokensOut") ?: 0,
                cost = o.get("cost")?.takeIf { it.isJsonPrimitive }?.asDouble,
                cancelReason = o.str("cancelReason"),
                failure = o.str("streamingFailedMessage"),
            )
        }
    }
}

/** Totals over a task, for the status line. */
data class ApiMetrics(val tokensIn: Long, val tokensOut: Long, val cost: Double) {
    companion object {
        fun of(messages: List<ChatMessage>): ApiMetrics {
            var inTokens = 0L
            var outTokens = 0L
            var cost = 0.0
            for (m in Transcript.combineApiRequests(messages)) {
                if (!m.isSay("api_req_started")) continue
                val info = ApiReqInfo.parse(m)
                inTokens += info.tokensIn
                outTokens += info.tokensOut
                cost += info.cost ?: 0.0
            }
            return ApiMetrics(inTokens, outTokens, cost)
        }

        /** `$0.0123 · 12.3k in · 1.2k out`, or empty when nothing was spent. */
        fun describe(metrics: ApiMetrics): String {
            if (metrics.tokensIn == 0L && metrics.tokensOut == 0L && metrics.cost == 0.0) return ""
            fun tokens(n: Long) = if (n >= 1000) String.format("%.1fk", n / 1000.0) else n.toString()
            return String.format("$%.4f · %s in · %s out", metrics.cost, tokens(metrics.tokensIn), tokens(metrics.tokensOut))
        }
    }
}

/** What a failed request says. The text of an `api_req_failed` ask is a JSON error with a `message`, or plain text. */
object ErrorText {
    fun of(message: ChatMessage): String {
        val text = message.text()
        val o = parse(text) ?: return text
        return o.str("message") ?: o.getAsJsonObject("error")?.str("message") ?: o.getAsJsonObject("_error")?.str("message") ?: text
    }
}
