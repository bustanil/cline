package bot.cline.jetbrains.chat

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** A file the agent changed since the latest checkpoint. */
data class ChangedFile(
    /** Absolute path. */
    val path: String,
    val relativePath: String,
    val status: Status,
    val addedLines: Int,
    val removedLines: Int,
) {
    enum class Status(val letter: Char) { MODIFIED('M'), ADDED('A'), DELETED('D') }

    companion object {
        /** Parses `ChangedFiles` in proto3 JSON. Default values (MODIFIED, 0) are absent on the wire. */
        fun parseList(json: String): List<ChangedFile> {
            val files = JsonParser.parseString(json).asJsonObject.getAsJsonArray("files") ?: return emptyList()
            return files.map { parse(it.asJsonObject) }
        }

        private fun parse(file: JsonObject): ChangedFile {
            val path = file.get("path")?.asString.orEmpty()
            return ChangedFile(
                path = path,
                relativePath = file.get("relativePath")?.asString?.takeIf { it.isNotEmpty() } ?: path,
                status = when (file.get("status")?.asString) {
                    "CHANGED_FILE_ADDED" -> Status.ADDED
                    "CHANGED_FILE_DELETED" -> Status.DELETED
                    else -> Status.MODIFIED
                },
                addedLines = file.get("addedLines")?.asInt ?: 0,
                removedLines = file.get("removedLines")?.asInt ?: 0,
            )
        }
    }
}
