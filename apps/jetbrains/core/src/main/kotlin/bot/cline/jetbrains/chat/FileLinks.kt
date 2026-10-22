package bot.cline.jetbrains.chat

import bot.cline.jetbrains.bridge.CoreApi
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/** An existing file the core resolved from chat text, with a 1-based inclusive line range. */
data class FileLink(val path: String, val startLine: Int?, val endLine: Int?)

/**
 * Turns file paths written in chat into links that open at the right line.
 * The core does the resolving, so the plugin and the other clients agree on
 * which file `src/app.ts:12-20` or `~/notes.md#L4` means.
 */
class FileLinks(private val core: CoreApi) {
    private val found = ConcurrentHashMap<String, FileLink>()

    /** Resolves [reference] to an existing file, or null if nothing matches. */
    fun resolve(reference: String): CompletableFuture<FileLink?> {
        found[reference]?.let { return CompletableFuture.completedFuture(it) }
        val body = JsonObject().apply { addProperty("value", reference) }.toString()
        return core.call("cline.FileService", "resolveFileReference", body).thenApply { json ->
            val response = JsonParser.parseString(json).asJsonObject
            val path = response.get("path")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
            if (path.isEmpty()) {
                null
            } else {
                FileLink(path, response.get("startLine")?.asInt, response.get("endLine")?.asInt).also { found[reference] = it }
            }
        }
    }

    /** Opens [link] in the editor and selects its lines. */
    fun open(link: FileLink): CompletableFuture<String> {
        val body = JsonObject().apply {
            addProperty("path", link.path)
            link.startLine?.let { addProperty("startLine", it) }
            link.endLine?.let { addProperty("endLine", it) }
        }.toString()
        return core.call("cline.FileService", "openFileLocation", body)
    }

    companion object {
        // Same shape as the webview's isPotentialFilePath: relative, absolute or ~/, with an
        // optional :line, :line:col, :start-end or #Lstart-Lend suffix. The core then checks the file exists.
        private val FILE_PATH = Regex("""^(?:~/)?[\w\-./]+(?<!/)(?::\d+(?:-\d+|:\d+)?|#L\d+(?:-L?\d+)?)?$""")

        fun isPotentialFilePath(text: String): Boolean = !text.contains('\n') && FILE_PATH.matches(text)
    }
}
