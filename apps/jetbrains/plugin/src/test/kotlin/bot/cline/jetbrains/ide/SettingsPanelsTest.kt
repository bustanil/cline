package bot.cline.jetbrains.ide

import bot.cline.jetbrains.bridge.CoreApi
import bot.cline.jetbrains.bridge.Subscription
import bot.cline.jetbrains.chat.ChatStore
import bot.cline.jetbrains.chat.HistoryItem
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import java.util.concurrent.CompletableFuture

class SettingsPanelsTest : BasePlatformTestCase() {
    private class Canned(var history: String = """{"tasks":[],"hasMore":false}""") : CoreApi {
        val calls = mutableListOf<Triple<String, String, String>>()
        val state = Gson().toJson(mapOf("stateJson" to """{"mode":"plan","epoch":1,"stateVersion":1,"clineMessages":[],"apiConfiguration":{"planModeApiProvider":"anthropic","planModeApiModelId":"claude-sonnet-5"}}"""))

        override fun call(service: String, method: String, messageJson: String): CompletableFuture<String> {
            calls += Triple(service, method, messageJson)
            return CompletableFuture.completedFuture(
                when (method) {
                    "getLatestState" -> state
                    "getTaskHistory" -> history
                    "listProviders" -> """{"providers":[{"id":"openrouter","name":"OpenRouter","defaultModelId":"or/default"},{"id":"anthropic","name":"Anthropic","defaultModelId":"claude-sonnet-5"}]}"""
                    "resolveProviderModels" ->
                        if (messageJson.contains("anthropic")) """{"ok":true,"defaultModelId":"claude-sonnet-5","models":{"claude-sonnet-5":{"name":"Claude Sonnet 5"},"claude-haiku-5-5":{"name":"Claude Haiku 5.5"}}}"""
                        else """{"ok":false,"error":{"message":"No API key"}}"""
                    "readProviderConfig" -> if (messageJson.contains("anthropic")) """{"apiKeyLength":11}""" else """{"apiKeyLength":0}"""
                    else -> "{}"
                },
            )
        }

        override fun subscribe(service: String, method: String, messageJson: String, onMessage: (String) -> Unit, onClose: (Throwable?) -> Unit) = Subscription {}
        fun body(method: String) = JsonParser.parseString(calls.last { it.second == method }.third).asJsonObject
    }

    private fun settle() = repeat(20) { UIUtil.dispatchAllInvocationEvents(); Thread.sleep(20) }

    private fun store(core: Canned) = ChatStore(core).also { it.start().get() }

    private val tasks = """{"tasks":[
        {"id":"t1","task":"Fix the bug\nmore detail","ts":"1700000000000","isFavorited":true,"totalCost":0.25,"modelId":"m1"},
        {"id":"t2","task":"Write docs","ts":"1700000100000"}],"hasMore":false}"""

    fun `test the history lists tasks and a search reloads with the query`() {
        val core = Canned(tasks)
        val panel = HistoryPanel(store(core))
        settle()
        assertEquals(listOf("t1", "t2"), panel.items.map { it.id })
        panel.setSearch("docs"); settle()
        assertEquals("docs", core.body("getTaskHistory").get("searchQuery").asString)
        panel.setFavoritesOnly(true); settle()
        assertEquals(true, core.body("getTaskHistory").get("favoritesOnly").asBoolean)
    }

    fun `test opening a task loads it and tells the caller`() {
        val core = Canned(tasks)
        var opened = 0
        val panel = HistoryPanel(store(core), onOpened = { opened++ })
        settle()
        panel.select("t2"); panel.openSelected(); settle()
        assertEquals("t2", core.body("showTaskWithId").get("value").asString)
        assertEquals(1, opened)
    }

    fun `test favorite toggles the other way and delete asks first`() {
        val core = Canned(tasks)
        val asked = mutableListOf<HistoryItem>()
        var yes = false
        val panel = HistoryPanel(store(core), confirmDelete = { asked += it; yes })
        settle()
        panel.select("t1"); panel.toggleFavorite(); settle()
        assertEquals(false, core.body("toggleTaskFavorite").get("isFavorited").asBoolean)
        panel.select("t2"); panel.toggleFavorite(); settle()
        assertEquals(true, core.body("toggleTaskFavorite").get("isFavorited").asBoolean)
        panel.select("t1"); panel.deleteSelected(); settle()
        assertEquals(listOf("t1"), asked.map { it.id })
        assertTrue(core.calls.none { it.second == "deleteTasksWithIds" })
        yes = true
        panel.select("t1"); panel.deleteSelected(); settle()
        assertEquals(listOf("t1"), core.body("deleteTasksWithIds").getAsJsonArray("value").map { it.asString })
    }

    fun `test the model panel starts on the current choice and says a key is saved`() {
        val core = Canned()
        val panel = ModelPanel(store(core))
        settle()
        assertEquals("anthropic", panel.chosenProvider?.id)
        assertEquals("claude-sonnet-5", panel.chosenModelId)
        assertTrue(panel.noteText, panel.noteText.contains("API key is saved"))
    }

    fun `test applying saves the key and then commits the model for the current mode`() {
        val core = Canned()
        val panel = ModelPanel(store(core))
        settle()
        panel.chooseModel("claude-haiku-5-5")
        panel.setApiKey("sk-new-key")
        panel.apply().get(); settle()
        assertEquals("sk-new-key", core.body("writeProviderConfig").getAsJsonObject("patch").get("apiKey").asString)
        val commit = core.body("commitModelSelection")
        assertEquals(listOf("anthropic", "plan", "claude-haiku-5-5"), listOf("providerId", "mode", "modelId").map { commit.get(it).asString })
        val order = core.calls.map { it.second }
        assertTrue(order.indexOf("writeProviderConfig") < order.indexOf("commitModelSelection"))
    }

    fun `test a provider whose models cannot be listed asks for a typed model id and an empty key is not saved`() {
        val core = Canned()
        val panel = ModelPanel(store(core))
        settle()
        panel.chooseProvider("openrouter"); settle()
        assertTrue(panel.noteText, panel.noteText.contains("No API key"))
        panel.chooseModel("anthropic/claude-sonnet-5.5")
        panel.apply().get(); settle()
        assertTrue(core.calls.none { it.second == "writeProviderConfig" })
        assertEquals("anthropic/claude-sonnet-5.5", core.body("commitModelSelection").get("modelId").asString)
        assertEquals("openrouter", core.body("commitModelSelection").get("providerId").asString)
    }
}
