package bot.cline.jetbrains.chat

import bot.cline.jetbrains.bridge.CoreApi
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.concurrent.CompletableFuture

data class HistoryItem(
    val id: String,
    val task: String,
    /** Milliseconds since the epoch. */
    val timestamp: Long,
    val favorited: Boolean,
    val cost: Double,
    val tokensIn: Int,
    val tokensOut: Int,
    val modelId: String?,
)

data class HistoryPage(val items: List<HistoryItem>, val hasMore: Boolean)

/** Past tasks: list, open, favorite and delete. */
class TaskHistory(private val core: CoreApi) {
    fun list(
        searchQuery: String = "",
        favoritesOnly: Boolean = false,
        currentWorkspaceOnly: Boolean = false,
        limit: Int = 50,
        offset: Int = 0,
    ): CompletableFuture<HistoryPage> {
        val body = JsonObject().apply {
            if (searchQuery.isNotBlank()) addProperty("searchQuery", searchQuery)
            if (favoritesOnly) addProperty("favoritesOnly", true)
            if (currentWorkspaceOnly) addProperty("currentWorkspaceOnly", true)
            addProperty("limit", limit)
            if (offset > 0) addProperty("offset", offset)
        }.toString()
        return core.call("cline.TaskService", "getTaskHistory", body).thenApply { json ->
            val o = JsonParser.parseString(json).asJsonObject
            HistoryPage(
                items = o.getAsJsonArray("tasks")?.map { parse(it.asJsonObject) }.orEmpty(),
                hasMore = o.get("hasMore")?.asBoolean == true,
            )
        }
    }

    /** Loads a task into the chat. This shows its messages; it does not start inference. */
    fun open(id: String): CompletableFuture<String> =
        core.call("cline.TaskService", "showTaskWithId", JsonObject().apply { addProperty("value", id) }.toString())

    fun delete(ids: List<String>): CompletableFuture<String> =
        core.call(
            "cline.TaskService", "deleteTasksWithIds",
            JsonObject().apply { add("value", JsonArray().also { a -> ids.forEach(a::add) }) }.toString(),
        )

    fun setFavorite(id: String, favorited: Boolean): CompletableFuture<String> =
        core.call(
            "cline.TaskService", "toggleTaskFavorite",
            JsonObject().apply { addProperty("taskId", id); addProperty("isFavorited", favorited) }.toString(),
        )

    private fun parse(o: JsonObject) = HistoryItem(
        id = o.str("id").orEmpty(),
        task = o.str("task").orEmpty(),
        timestamp = o.get("ts")?.asLong ?: 0L,
        favorited = o.get("isFavorited")?.asBoolean == true,
        cost = o.get("totalCost")?.asDouble ?: 0.0,
        tokensIn = o.get("tokensIn")?.asInt ?: 0,
        tokensOut = o.get("tokensOut")?.asInt ?: 0,
        modelId = o.str("modelId")?.takeIf { it.isNotEmpty() },
    )
}
