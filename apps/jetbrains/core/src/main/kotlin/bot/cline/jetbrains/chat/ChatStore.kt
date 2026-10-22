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
    /** Turns file paths in chat text into links that open at their line. */
    val fileLinks = FileLinks(core)

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
    fun askResponse(
        responseType: String,
        text: String = "",
        images: List<String> = emptyList(),
        files: List<String> = emptyList(),
    ): CompletableFuture<String> =
        core.call("cline.TaskService", "askResponse", JsonObject().apply {
            addProperty("responseType", responseType)
            addProperty("text", text)
            if (images.isNotEmpty()) add("images", images.toJsonArray())
            if (files.isNotEmpty()) add("files", files.toJsonArray())
        }.toString())

    /**
     * What the Send button does, as `handleSendMessage` does in the webview:
     * start a task when there is none, reject with feedback while an approval
     * is pending, resume on a resume ask, and otherwise answer the pending ask.
     */
    fun send(text: String, images: List<String> = emptyList(), files: List<String> = emptyList()): CompletableFuture<String> {
        val message = text.trim()
        val current = snapshot
        if (current.messages.isEmpty()) return newTask(message, images, files)
        if (current.turnState?.phase == "awaiting_approval") return askResponse("noButtonClicked", message, images, files)
        val last = current.messages.lastOrNull { it.raw.str("type") == "ask" }
        return when (last?.raw?.str("ask")) {
            "resume_task", "resume_completed_task" -> askResponse("yesButtonClicked", message, images, files)
            else -> askResponse("messageResponse", message, images, files)
        }
    }

    fun cancelTask(): CompletableFuture<String> = core.call("cline.TaskService", "cancelTask")

    fun clearTask(): CompletableFuture<String> = core.call("cline.TaskService", "clearTask")

    /** The footer buttons for the current transcript. */
    fun buttons(): ButtonConfig {
        val state = snapshot.extensionState
        val foreground = state.get("foregroundCommandRunning")?.let { it.isJsonPrimitive && it.asBoolean } == true
        return ButtonConfigs.forState(snapshot.messages, snapshot.turnState, foreground)
    }

    /**
     * Runs what a footer button does, as `executeButtonAction` does in the
     * webview. [draftText] is what the user typed in the input box, sent along
     * with an approval, rejection or proceed.
     */
    fun perform(action: ButtonAction, draftText: String = ""): CompletableFuture<*> {
        val text = draftText.trim()
        return when (action) {
            ButtonAction.RETRY -> askResponse("yesButtonClicked")
            ButtonAction.APPROVE, ButtonAction.PROCEED, ButtonAction.REJECT -> {
                val type = if (action == ButtonAction.REJECT) "noButtonClicked" else "yesButtonClicked"
                if (text.isEmpty()) askResponse(type) else askResponse(type, text)
            }
            ButtonAction.PROCEED_WHILE_RUNNING -> core.call("cline.TaskService", "proceedWhileRunningCommand")
            ButtonAction.NEW_TASK -> {
                val last = snapshot.messages.lastOrNull()
                if (last?.raw?.str("ask") == "new_task") newTask(last.raw.str("text") ?: "") else clearTask()
            }
            ButtonAction.CANCEL -> {
                val background = snapshot.extensionState.get("backgroundCommandRunning")?.let { it.isJsonPrimitive && it.asBoolean } == true
                if (background) {
                    core.call("cline.TaskService", "cancelBackgroundCommand").thenCompose { cancelTask() }
                } else {
                    cancelTask()
                }
            }
            ButtonAction.UTILITY -> {
                val last = snapshot.messages.lastOrNull()
                val value = JsonObject().apply { addProperty("value", last?.raw?.str("text") ?: "") }.toString()
                when (last?.raw?.str("ask")) {
                    "condense" -> core.call("cline.SlashService", "condense", value)
                    "report_bug" -> core.call("cline.SlashService", "reportBug", value)
                    else -> CompletableFuture.completedFuture("{}")
                }
            }
        }
    }

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
