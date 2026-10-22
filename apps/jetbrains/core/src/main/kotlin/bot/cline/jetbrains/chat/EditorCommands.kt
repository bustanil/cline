package bot.cline.jetbrains.chat

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/** The editor actions that hand code to the agent. Each is one `cline.CommandsService` method. */
enum class EditorCommand(val method: String) {
    ADD_TO_CHAT("addToCline"),
    FIX("fixWithCline"),
    EXPLAIN("explainWithCline"),
    IMPROVE("improveWithCline"),
}

/** A problem in the selection. Lines and columns are 0-based, as in the core's `Diagnostic`. */
data class EditorDiagnostic(
    val message: String,
    val startLine: Int,
    val startCharacter: Int,
    val endLine: Int,
    val endCharacter: Int,
    val severity: Severity,
    val source: String? = null,
) {
    /** Names the core's `DiagnosticSeverity` proto enum uses. */
    enum class Severity(val protoName: String) {
        ERROR("DIAGNOSTIC_ERROR"),
        WARNING("DIAGNOSTIC_WARNING"),
        INFORMATION("DIAGNOSTIC_INFORMATION"),
        HINT("DIAGNOSTIC_HINT"),
    }
}

/** What the core needs to know about the selected code. [startLine] and [endLine] are 1-based and inclusive. */
data class EditorContext(
    val filePath: String,
    val selectedText: String,
    val language: String,
    val startLine: Int?,
    val endLine: Int?,
    val diagnostics: List<EditorDiagnostic>,
)

object EditorCommands {
    /**
     * The 1-based inclusive line range of a selection given by 0-based start and
     * end positions. A selection that ends at column 0 of a line stops at the line
     * before, so selecting whole lines by dragging down does not count the next one.
     */
    fun selectedLines(startLine: Int, startColumn: Int, endLine: Int, endColumn: Int): Pair<Int, Int> {
        val lastLine = if (endColumn == 0 && endLine > startLine) endLine - 1 else endLine
        return (startLine + 1) to (lastLine + 1)
    }

    /** Whether a diagnostic touches the selected lines (both 0-based here). */
    fun touches(diagnostic: EditorDiagnostic, firstLine: Int, lastLine: Int): Boolean =
        diagnostic.startLine <= lastLine && diagnostic.endLine >= firstLine

    /** The `CommandContext` message, as proto3 JSON. */
    fun contextJson(context: EditorContext): String = JsonObject().apply {
        addProperty("filePath", context.filePath)
        addProperty("selectedText", context.selectedText)
        addProperty("language", context.language)
        context.startLine?.let { addProperty("startLine", it) }
        context.endLine?.let { addProperty("endLine", it) }
        add("diagnostics", JsonArray().also { array ->
            context.diagnostics.forEach { d ->
                array.add(JsonObject().apply {
                    addProperty("message", d.message)
                    add("range", JsonObject().apply {
                        add("start", position(d.startLine, d.startCharacter))
                        add("end", position(d.endLine, d.endCharacter))
                    })
                    addProperty("severity", d.severity.protoName)
                    d.source?.let { addProperty("source", it) }
                })
            }
        })
    }.toString()

    private fun position(line: Int, character: Int) = JsonObject().apply {
        addProperty("line", line)
        addProperty("character", character)
    }
}
