package bot.cline.jetbrains.chat

import bot.cline.jetbrains.bridge.CoreApi
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.concurrent.CompletableFuture

/** A file or folder the user can mention with `@`. */
data class MentionMatch(
    /** Relative to a workspace root. */
    val path: String,
    val isFolder: Boolean,
    val label: String?,
) {
    /** What goes into the input: `@/src/a.kt`, or `@/src/` for a folder. */
    fun text(): String = "@/" + path.trimStart('/') + if (isFolder && !path.endsWith("/")) "/" else ""
}

data class MentionResults(val matches: List<MentionMatch>, val errorReason: String?, val errorMessage: String?)

/** An `@` being typed: it starts at [start] and the text after it is [query]. */
data class MentionToken(val start: Int, val query: String)

object Mentions {
    // An @ at the start or after whitespace, then non-space characters up to the caret.
    private val token = Regex("""(?:^|\s)@([^\s@]*)$""")

    /** The `@` mention the caret is inside, if any. */
    fun tokenAt(text: String, caret: Int): MentionToken? {
        val before = text.substring(0, caret.coerceIn(0, text.length))
        val match = token.find(before) ?: return null
        val group = match.groups[1] ?: return null
        return MentionToken(group.range.first - 1, group.value)
    }

    /** Replaces the mention being typed with [match]. Returns the new text and where the caret goes. */
    fun accept(text: String, caret: Int, token: MentionToken, match: MentionMatch): Pair<String, Int> {
        val end = caret.coerceIn(token.start, text.length)
        // A file ends the mention, so a space follows unless one is already there. A folder keeps going.
        val space = !match.isFolder && (end >= text.length || !text[end].isWhitespace())
        val inserted = match.text() + if (space) " " else ""
        return (text.substring(0, token.start) + inserted + text.substring(end)) to (token.start + inserted.length)
    }

    fun search(core: CoreApi, query: String, limit: Int = 20): CompletableFuture<MentionResults> {
        val body = JsonObject().apply {
            addProperty("query", query)
            addProperty("limit", limit)
        }.toString()
        return core.call("cline.FileService", "searchFiles", body).thenApply { json ->
            val o = JsonParser.parseString(json).asJsonObject
            MentionResults(
                matches = o.getAsJsonArray("results")?.map {
                    val r = it.asJsonObject
                    MentionMatch(r.str("path").orEmpty(), r.str("type") == "folder", r.str("label"))
                }.orEmpty().filter { it.path.isNotEmpty() },
                errorReason = o.str("errorReason"),
                errorMessage = o.str("errorMessage"),
            )
        }
    }
}
