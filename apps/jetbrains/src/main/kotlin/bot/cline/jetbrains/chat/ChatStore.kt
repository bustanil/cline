package bot.cline.jetbrains.chat

import bot.cline.jetbrains.bridge.CoreApi
import bot.cline.jetbrains.bridge.Subscription
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList

/** What the chat UI draws from. Replaced as a whole on every change. */
data class ChatSnapshot(
    val messages: List<ChatMessage>,
    val turnState: TurnState?,
    /** The latest full `ExtensionState`, for fields the UI reads (mode, history, ...). */
    val extensionState: JsonObject,
)

/**
 * Holds the conversation and the actions on it. It mirrors what the webview's
 * `ExtensionStateContext` does: take the latest state, follow state pushes and
 * partial messages, and merge them through [ReplicaState].
 *
 * Listeners run on the thread that delivered the update, not the UI thread.
 */
class ChatStore(private val core: CoreApi) {
    private val listeners = CopyOnWriteArrayList<(ChatSnapshot) -> Unit>()
    private val subscriptions = mutableListOf<Subscription>()
    private var replica = ReplicaState.empty()
    private var extensionState = JsonObject()

    @Volatile
    var snapshot: ChatSnapshot = ChatSnapshot(emptyList(), null, JsonObject())
        private set

    fun addListener(listener: (ChatSnapshot) -> Unit) {
        listeners += listener
    }

    /** Loads the current state, then follows state pushes and partial messages. */
    fun start(): CompletableFuture<Unit> {
        synchronized(this) {
            subscriptions += core.subscribe("cline.StateService", "subscribeToState", onMessage = ::onStatePayload)
            subscriptions += core.subscribe("cline.UiService", "subscribeToPartialMessage", onMessage = ::onPartialPayload)
        }
        return core.call("cline.StateService", "getLatestState").thenApply { onStatePayload(it) }
    }

    fun stop() {
        synchronized(this) {
            subscriptions.forEach { it.cancel() }
            subscriptions.clear()
        }
    }

    fun newTask(text: String, images: List<String> = emptyList(), files: List<String> = emptyList()): CompletableFuture<String> =
        core.call("cline.TaskService", "newTask", JsonObject().apply {
            addProperty("text", text)
            add("images", images.toJsonArray())
            add("files", files.toJsonArray())
        }.toString())

    /** [responseType] is `yesButtonClicked`, `noButtonClicked` or `messageResponse`. */
    fun askResponse(responseType: String, text: String = ""): CompletableFuture<String> =
        core.call("cline.TaskService", "askResponse", JsonObject().apply {
            addProperty("responseType", responseType)
            addProperty("text", text)
        }.toString())

    fun cancelTask(): CompletableFuture<String> = core.call("cline.TaskService", "cancelTask")

    fun clearTask(): CompletableFuture<String> = core.call("cline.TaskService", "clearTask")

    private fun onStatePayload(payload: String) {
        val state = runCatching {
            JsonParser.parseString(JsonParser.parseString(payload).asJsonObject.get("stateJson").asString).asJsonObject
        }.getOrNull() ?: return
        val messages = (state.get("clineMessages") as? JsonArray)?.map { ChatMessage(it.asJsonObject) } ?: emptyList()
        val turn = state.get("turnState")?.takeIf { it.isJsonObject }?.asJsonObject?.let {
            TurnState(
                phase = it.get("phase")?.asString ?: "idle",
                anchorTs = it.get("anchorTs")?.takeIf { v -> !v.isJsonNull }?.asLong,
                seq = it.get("seq")?.asInt ?: 0,
            )
        }
        publish {
            extensionState = state
            replica = replica.applyStateSnapshot(
                messages,
                state.get("epoch")?.asInt ?: 0,
                state.get("stateVersion")?.asInt ?: 0,
                turn,
            )
        }
    }

    private fun onPartialPayload(payload: String) {
        val message = runCatching { ChatMessage(ProtoMessageConverter.convert(JsonParser.parseString(payload).asJsonObject)) }.getOrNull()
            ?: return
        if (message.ts <= 0L) return
        publish { replica = replica.applyMessage(message) }
    }

    private fun publish(update: () -> Unit) {
        val before: ReplicaState
        val next: ChatSnapshot
        synchronized(this) {
            before = replica
            update()
            if (replica === before && extensionState === snapshot.extensionState) return
            next = ChatSnapshot(replica.messages, replica.turnState, extensionState)
            snapshot = next
        }
        listeners.forEach { it(next) }
    }

    private fun List<String>.toJsonArray() = JsonArray().also { array -> forEach { array.add(it) } }
}
