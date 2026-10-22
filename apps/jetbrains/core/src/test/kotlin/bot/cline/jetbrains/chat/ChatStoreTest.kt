package bot.cline.jetbrains.chat

import bot.cline.jetbrains.bridge.CoreApi
import bot.cline.jetbrains.bridge.Subscription
import com.google.gson.Gson
import com.google.gson.JsonParser
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class FakeCore : CoreApi {
    val calls = mutableListOf<Triple<String, String, String>>()
    val streams = mutableMapOf<String, (String) -> Unit>()
    val cancelled = mutableListOf<String>()
    var latestState = "{}"

    override fun call(service: String, method: String, messageJson: String): CompletableFuture<String> {
        calls += Triple(service, method, messageJson)
        return CompletableFuture.completedFuture(if (method == "getLatestState") latestState else "{}")
    }

    override fun subscribe(service: String, method: String, messageJson: String, onMessage: (String) -> Unit, onClose: (Throwable?) -> Unit): Subscription {
        streams["$service/$method"] = onMessage
        return Subscription { cancelled += "$service/$method" }
    }
}

class ChatStoreTest {
    private fun stateJson(vararg messages: String, epoch: Int = 1, version: Int = 1, phase: String = "idle", seq: Int = 1): String {
        val state = """{"mode":"act","epoch":$epoch,"stateVersion":$version,"turnState":{"phase":"$phase","seq":$seq},"clineMessages":[${messages.joinToString(",")}]}"""
        return Gson().toJson(mapOf("stateJson" to state))
    }

    private fun say(ts: Long, text: String, epoch: Int = 1, seq: Int = 1) = """{"ts":$ts,"type":"say","say":"text","text":"$text","epoch":$epoch,"seq":$seq}"""

    @Test
    fun `start loads the latest state and exposes it`() {
        val core = FakeCore().apply { latestState = stateJson(say(1, "hello"), phase = "streaming", seq = 3) }
        val store = ChatStore(core)
        store.start().get()
        assertEquals(listOf("hello"), store.snapshot.messages.map { it.raw.get("text").asString })
        assertEquals("streaming", store.snapshot.turnState?.phase)
        assertEquals("act", store.snapshot.extensionState.get("mode").asString)
        assertTrue("cline.StateService/subscribeToState" in core.streams)
        assertTrue("cline.UiService/subscribeToPartialMessage" in core.streams)
    }

    @Test
    fun `a partial message streams into the transcript and notifies listeners`() {
        val core = FakeCore().apply { latestState = stateJson(say(1, "a")) }
        val store = ChatStore(core)
        val seen = mutableListOf<Int>()
        store.addListener { seen += it.messages.size }
        store.start().get()
        core.streams.getValue("cline.UiService/subscribeToPartialMessage")(
            """{"ts":"2","type":"SAY","say":"TEXT","text":"b","partial":true,"seq":"1","epoch":"1"}""",
        )
        assertEquals(listOf("a", "b"), store.snapshot.messages.map { it.raw.get("text").asString })
        assertEquals(listOf(1, 2), seen)
    }

    @Test
    fun `a state push merges without dropping a streamed message`() {
        val core = FakeCore().apply { latestState = stateJson(say(1, "a")) }
        val store = ChatStore(core)
        store.start().get()
        core.streams.getValue("cline.UiService/subscribeToPartialMessage")("""{"ts":"2","type":"SAY","say":"TEXT","text":"b","seq":"1","epoch":"1"}""")
        core.streams.getValue("cline.StateService/subscribeToState")(stateJson(say(1, "a"), version = 2))
        assertEquals(2, store.snapshot.messages.size)
    }

    @Test
    fun `newTask askResponse and cancel send the expected calls`() {
        val core = FakeCore()
        val store = ChatStore(core)
        store.newTask("do it", files = listOf("/a.kt")).get()
        store.askResponse("yesButtonClicked").get()
        store.cancelTask().get()
        val (service, method, json) = core.calls[0]
        assertEquals("cline.TaskService" to "newTask", service to method)
        val body = JsonParser.parseString(json).asJsonObject
        assertEquals("do it", body.get("text").asString)
        assertEquals("/a.kt", body.getAsJsonArray("files")[0].asString)
        assertEquals("yesButtonClicked", JsonParser.parseString(core.calls[1].third).asJsonObject.get("responseType").asString)
        assertEquals("cancelTask", core.calls[2].second)
    }

    @Test
    fun `stop cancels every subscription`() {
        val core = FakeCore()
        val store = ChatStore(core)
        store.start().get()
        store.stop()
        assertEquals(3, core.cancelled.size)
    }
}

class ChatStoreActionsTest {
    private fun store(vararg msgs: String, turn: String = "idle", anchor: Long? = null, extra: String = ""): Pair<ChatStore, FakeCore> {
        val core = FakeCore()
        val anchorJson = if (anchor != null) ""","anchorTs":$anchor""" else ""
        val state = """{"epoch":1,"stateVersion":1,"turnState":{"phase":"$turn","seq":1$anchorJson}$extra,"clineMessages":[${msgs.joinToString(",")}]}"""
        core.latestState = Gson().toJson(mapOf("stateJson" to state))
        return ChatStore(core).also { it.start().get() } to core
    }

    @Test
    fun `approve and reject send yes and no with optional text`() {
        val (s, c) = store("""{"ts":1,"type":"ask","ask":"tool","epoch":1,"seq":1}""", turn = "awaiting_approval", anchor = 1)
        assertEquals("Approve", s.buttons().primaryText)
        s.perform(ButtonAction.APPROVE).get()
        s.perform(ButtonAction.REJECT, " not that one ").get()
        val sent = c.calls.filter { it.second == "askResponse" }.map { JsonParser.parseString(it.third).asJsonObject }
        assertEquals("yesButtonClicked", sent[0].get("responseType").asString)
        assertEquals("", sent[0].get("text").asString)
        assertEquals("noButtonClicked", sent[1].get("responseType").asString)
        assertEquals("not that one", sent[1].get("text").asString)
    }

    @Test
    fun `retry sends yes and cancel sends cancelTask`() {
        val (s, c) = store("""{"ts":1,"type":"ask","ask":"api_req_failed","epoch":1,"seq":1}""", turn = "error", anchor = 1)
        s.perform(ButtonAction.RETRY).get()
        s.perform(ButtonAction.CANCEL).get()
        assertEquals(listOf("askResponse", "cancelTask"), c.calls.drop(1).map { it.second })
    }

    @Test
    fun `new task after a new_task ask starts a task with its text and otherwise clears`() {
        val (s, c) = store("""{"ts":1,"type":"ask","ask":"new_task","text":"carry on","epoch":1,"seq":1}""")
        s.perform(ButtonAction.NEW_TASK).get()
        assertEquals("newTask", c.calls.last().second)
        assertEquals("carry on", JsonParser.parseString(c.calls.last().third).asJsonObject.get("text").asString)
        val (s2, c2) = store("""{"ts":1,"type":"ask","ask":"completion_result","epoch":1,"seq":1}""")
        s2.perform(ButtonAction.NEW_TASK).get()
        assertEquals("clearTask", c2.calls.last().second)
    }

    @Test
    fun `cancel with a background command cancels it first`() {
        val (s, c) = store("""{"ts":1,"type":"say","say":"text","epoch":1,"seq":1}""", turn = "streaming", extra = ""","backgroundCommandRunning":true""")
        s.perform(ButtonAction.CANCEL).get()
        assertEquals(listOf("cancelBackgroundCommand", "cancelTask"), c.calls.drop(1).map { it.second })
    }
}


class ChatStoreSendTest {
    private fun store(vararg msgs: String, turn: String = "idle"): Pair<ChatStore, FakeCore> {
        val core = FakeCore()
        val state = """{"epoch":1,"stateVersion":1,"turnState":{"phase":"$turn","seq":1},"clineMessages":[${msgs.joinToString(",")}]}"""
        core.latestState = Gson().toJson(mapOf("stateJson" to state))
        return ChatStore(core).also { it.start().get() } to core
    }

    private fun lastCall(c: FakeCore) = c.calls.last().second to JsonParser.parseString(c.calls.last().third).asJsonObject

    @Test
    fun `send starts a task when the transcript is empty`() {
        val (s, c) = store()
        s.send("  build it  ", files = listOf("/f")).get()
        val (method, body) = lastCall(c)
        assertEquals("newTask", method)
        assertEquals("build it", body.get("text").asString)
    }

    @Test
    fun `send during an approval rejects with feedback`() {
        val (s, c) = store("""{"ts":1,"type":"ask","ask":"tool","epoch":1,"seq":1}""", turn = "awaiting_approval")
        s.send("use another file").get()
        val (method, body) = lastCall(c)
        assertEquals("askResponse", method)
        assertEquals("noButtonClicked", body.get("responseType").asString)
        assertEquals("use another file", body.get("text").asString)
    }

    @Test
    fun `send on a resume ask resumes and otherwise answers the ask`() {
        val (s, c) = store("""{"ts":1,"type":"ask","ask":"resume_task","epoch":1,"seq":1}""")
        s.send("go on").get()
        assertEquals("yesButtonClicked", lastCall(c).second.let { JsonParser.parseString(c.calls.last().third).asJsonObject.get("responseType").asString })
        val (s2, c2) = store("""{"ts":1,"type":"ask","ask":"followup","epoch":1,"seq":1}""")
        s2.send("here is the answer").get()
        assertEquals("messageResponse", JsonParser.parseString(c2.calls.last().third).asJsonObject.get("responseType").asString)
    }
}
