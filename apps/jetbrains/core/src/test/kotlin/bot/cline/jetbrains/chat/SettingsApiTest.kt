package bot.cline.jetbrains.chat

import bot.cline.jetbrains.bridge.CoreApi
import com.google.gson.Gson
import com.google.gson.JsonParser
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A core that answers each method with canned JSON and records the calls. */
private class Canned(private val answers: Map<String, String>) : CoreApi by FakeCore() {
    val calls = mutableListOf<Triple<String, String, String>>()

    override fun call(service: String, method: String, messageJson: String): CompletableFuture<String> {
        calls += Triple(service, method, messageJson)
        return answers[method]?.let { CompletableFuture.completedFuture(it) } ?: CompletableFuture.completedFuture("{}")
    }

    fun body(method: String) = JsonParser.parseString(calls.last { it.second == method }.third).asJsonObject
}

class ModelSettingsTest {
    private val providers = """{"providers":[{"id":"anthropic","name":"Anthropic","defaultModelId":"claude-sonnet-5"},{"id":"openrouter","name":"OpenRouter","defaultModelId":"anthropic/claude-sonnet-5.5"},{"name":"no id"}]}"""

    @Test
    fun `lists providers and skips entries without an id`() {
        val list = ModelSettings(Canned(mapOf("listProviders" to providers))).providers().get()
        assertEquals(listOf("anthropic", "openrouter"), list.map { it.id })
        assertEquals("Anthropic", list[0].name)
        assertEquals("claude-sonnet-5", list[0].defaultModelId)
    }

    @Test
    fun `lists models sorted by name with the provider default`() {
        val core = Canned(mapOf("resolveProviderModels" to """{"ok":true,"defaultModelId":"b","models":{"b":{"name":"Beta"},"a":{"name":"Alpha"},"c":{}}}"""))
        val list = ModelSettings(core).models("anthropic", forceRefresh = true).get()
        assertTrue(list.ok)
        assertEquals(listOf("a", "b", "c"), list.models.map { it.id })
        assertEquals("Alpha (a)", list.models[0].toString())
        assertEquals("c", list.models[2].toString())
        assertEquals("b", list.defaultModelId)
        assertEquals(true, core.body("resolveProviderModels").get("forceRefresh").asBoolean)
    }

    @Test
    fun `a failed lookup is reported not thrown`() {
        val core = Canned(mapOf("resolveProviderModels" to """{"ok":false,"error":{"kind":"auth","message":"No API key"}}"""))
        val list = ModelSettings(core).models("openai").get()
        assertFalse(list.ok)
        assertEquals("No API key", list.error)
        assertEquals(emptyList(), list.models)
    }

    @Test
    fun `selecting commits the provider mode and model`() {
        val core = Canned(emptyMap())
        ModelSettings(core).select("anthropic", "act", "claude-sonnet-5").get()
        val body = core.body("commitModelSelection")
        assertEquals(listOf("anthropic", "act", "claude-sonnet-5"), listOf("providerId", "mode", "modelId").map { body.get(it).asString })
    }

    @Test
    fun `saving a key writes it in a patch and reading shows only its length`() {
        val core = Canned(mapOf("readProviderConfig" to """{"providerId":"anthropic","apiKeyLength":11}"""))
        val settings = ModelSettings(core)
        settings.saveApiKey("anthropic", "sk-test-123").get()
        assertEquals("sk-test-123", core.body("writeProviderConfig").getAsJsonObject("patch").get("apiKey").asString)
        assertEquals(11, settings.apiKeyLength("anthropic").get())
        assertEquals("anthropic", core.body("readProviderConfig").get("value").asString)
    }

    @Test
    fun `the current model is the explicit one or else the provider default`() {
        val state = JsonParser.parseString("""{"apiConfiguration":{"actModeApiProvider":"anthropic","planModeApiProvider":"openrouter","planModeApiModelId":"x/y"}}""").asJsonObject
        val providers = listOf(ProviderOption("anthropic", "Anthropic", "claude-sonnet-5"))
        assertEquals(ModelSelection("anthropic", "claude-sonnet-5"), ModelSettings.current(state, "act", providers))
        assertEquals(ModelSelection("openrouter", "x/y"), ModelSettings.current(state, "plan", providers))
        assertEquals(ModelSelection(null, null), ModelSettings.current(JsonParser.parseString("{}").asJsonObject, "act"))
    }
}

class TaskHistoryTest {
    @Test
    fun `lists tasks and builds the query`() {
        val core = Canned(mapOf("getTaskHistory" to """{"tasks":[{"id":"1","task":"Fix bug","ts":"1700000000000","isFavorited":true,"totalCost":0.5,"tokensIn":10,"tokensOut":4,"modelId":"m"},{"id":"2","task":"Other"}],"hasMore":true}"""))
        val page = TaskHistory(core).list(searchQuery = "bug", favoritesOnly = true, currentWorkspaceOnly = true, limit = 20, offset = 40).get()
        assertEquals(listOf("1", "2"), page.items.map { it.id })
        assertEquals(HistoryItem("1", "Fix bug", 1700000000000L, true, 0.5, 10, 4, "m"), page.items[0])
        assertEquals(HistoryItem("2", "Other", 0L, false, 0.0, 0, 0, null), page.items[1])
        assertTrue(page.hasMore)
        val body = core.body("getTaskHistory")
        assertEquals("bug", body.get("searchQuery").asString)
        assertEquals(true, body.get("favoritesOnly").asBoolean)
        assertEquals(true, body.get("currentWorkspaceOnly").asBoolean)
        assertEquals(20, body.get("limit").asInt)
        assertEquals(40, body.get("offset").asInt)
    }

    @Test
    fun `the default query asks for a page only`() {
        val core = Canned(mapOf("getTaskHistory" to """{"tasks":[],"hasMore":false}"""))
        assertEquals(HistoryPage(emptyList(), false), TaskHistory(core).list().get())
        assertEquals(setOf("limit"), core.body("getTaskHistory").keySet())
    }

    @Test
    fun `open delete and favorite send the right requests`() {
        val core = Canned(emptyMap())
        val history = TaskHistory(core)
        history.open("abc").get()
        history.delete(listOf("a", "b")).get()
        history.setFavorite("abc", true).get()
        assertEquals("abc", core.body("showTaskWithId").get("value").asString)
        assertEquals(listOf("a", "b"), core.body("deleteTasksWithIds").getAsJsonArray("value").map { it.asString })
        assertEquals("abc", core.body("toggleTaskFavorite").get("taskId").asString)
        assertEquals(true, core.body("toggleTaskFavorite").get("isFavorited").asBoolean)
    }
}

class PlanActTest {
    @Test
    fun `mode comes from the state and switching sends the enum name`() {
        val core = FakeCore().apply { latestState = Gson().toJson(mapOf("stateJson" to """{"mode":"plan","epoch":1,"stateVersion":1,"clineMessages":[]}""")) }
        val store = ChatStore(core)
        assertEquals("act", store.mode)
        store.start().get()
        assertEquals("plan", store.mode)
        store.setMode("act").get()
        val call = core.calls.last()
        assertEquals("cline.StateService" to "togglePlanActModeProto", call.first to call.second)
        assertEquals("ACT", JsonParser.parseString(call.third).asJsonObject.get("mode").asString)
    }
}
