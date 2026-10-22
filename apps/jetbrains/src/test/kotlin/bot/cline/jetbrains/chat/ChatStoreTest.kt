package bot.cline.jetbrains.chat

import bot.cline.jetbrains.bridge.CoreApi
import bot.cline.jetbrains.bridge.Subscription
import com.google.gson.Gson
import com.google.gson.JsonParser
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class FakeCore : CoreApi {
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
    fun `stop cancels both subscriptions`() {
        val core = FakeCore()
        val store = ChatStore(core)
        store.start().get()
        store.stop()
        assertEquals(2, core.cancelled.size)
    }
}
