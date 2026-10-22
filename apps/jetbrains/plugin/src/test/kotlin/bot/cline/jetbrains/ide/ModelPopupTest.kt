package bot.cline.jetbrains.ide

import bot.cline.jetbrains.bridge.CoreApi
import bot.cline.jetbrains.bridge.Subscription
import bot.cline.jetbrains.chat.ChatStore
import bot.cline.jetbrains.chat.ModelOption
import bot.cline.jetbrains.chat.ReasoningEffort
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import java.util.concurrent.CompletableFuture

class ModelPopupTest : BasePlatformTestCase() {
    private class Core : CoreApi {
        val calls = mutableListOf<Triple<String, String, String>>()
        val state = Gson().toJson(mapOf("stateJson" to """{"mode":"act","epoch":1,"stateVersion":1,"clineMessages":[],"apiConfiguration":{"actModeApiProvider":"anthropic","actModeApiModelId":"claude-sonnet-5"}}"""))

        override fun call(service: String, method: String, messageJson: String): CompletableFuture<String> {
            calls += Triple(service, method, messageJson)
            return CompletableFuture.completedFuture(
                when (method) {
                    "getLatestState" -> state
                    "listProviders" -> """{"providers":[{"id":"openrouter","name":"OpenRouter","defaultModelId":"x"},{"id":"anthropic","name":"Anthropic","defaultModelId":"claude-sonnet-5"}]}"""
                    "resolveProviderModels" ->
                        if (messageJson.contains("anthropic")) """{"ok":true,"models":{"claude-sonnet-5":{"name":"Claude Sonnet 5","contextWindow":1000000,"supportsReasoning":true},"claude-haiku-5-5":{"name":"Claude Haiku 5.5","contextWindow":200000}}}"""
                        else """{"ok":false,"error":{"message":"No API key"}}"""
                    "readProviderConfig" -> """{"actSelection":{"modelId":"claude-sonnet-5","modelInfo":{"name":"Claude Sonnet 5","contextWindow":1000000,"supportsImages":true,"supportsReasoning":true}}}"""
                    else -> "{}"
                },
            )
        }

        override fun subscribe(service: String, method: String, messageJson: String, onMessage: (String) -> Unit, onClose: (Throwable?) -> Unit) = Subscription {}
        fun body(method: String) = JsonParser.parseString(calls.last { it.second == method }.third).asJsonObject
    }

    private val core = Core()
    private val chosen = mutableListOf<String>()
    private val remembered = mutableMapOf<String, String>()

    private fun panel(): ModelPopupPanel {
        val store = ChatStore(core).also { it.start().get() }
        return ModelPopupPanel(store, onChosen = { chosen += "done" }, remembered = { remembered[it] }, remember = { p, e -> remembered[p] = e })
            .also { settle() }
    }

    private fun settle() = repeat(20) { UIUtil.dispatchAllInvocationEvents(); Thread.sleep(20) }

    fun `test the rows show the current model with its context size and the effort`() {
        remembered["anthropic"] = "high"
        val p = panel()
        assertEquals("Claude Sonnet 5  1M", p.modelText)
        assertEquals("High", p.effortText)
        assertEquals(listOf("anthropic", "openrouter"), p.providerIds())
    }

    fun `test effort says Default until one was set`() {
        assertEquals("Default", panel().effortText)
    }

    fun `test the models of the chosen provider are listed and searchable`() {
        val p = panel()
        assertEquals(listOf("claude-haiku-5-5", "claude-sonnet-5"), p.modelIds().sorted())
        p.setSearch("haiku"); settle()
        assertEquals(listOf("claude-haiku-5-5"), p.modelIds())
        p.setSearch(""); settle()
        p.chooseProvider("openrouter"); settle()
        assertTrue(p.noteText, p.noteText.contains("No API key"))
        assertTrue(p.modelIds().isEmpty())
    }

    fun `test choosing a model commits it for the current mode`() {
        val p = panel()
        p.chooseModel(ModelOption("claude-haiku-5-5", "Claude Haiku 5.5")); settle()
        val body = core.body("commitModelSelection")
        assertEquals(listOf("anthropic", "act", "claude-haiku-5-5"), listOf("providerId", "mode", "modelId").map { body.get(it).asString })
        assertEquals(listOf("done"), chosen)
    }

    fun `test choosing an effort writes it remembers it and closes`() {
        val p = panel()
        p.chooseEffort(ReasoningEffort.XHIGH); settle()
        val reasoning = core.body("writeProviderConfig").getAsJsonObject("patch").getAsJsonObject("reasoning")
        assertEquals("xhigh", reasoning.get("effort").asString)
        assertEquals("xhigh", remembered["anthropic"])
        assertEquals("Extra high", p.effortText)
        assertEquals(listOf("done"), chosen)
    }

    fun `test snapshot of the popup`() {
        remembered["anthropic"] = "high"
        val p = panel()
        p.openSections()
        p.background = ChatColors.card
        p.isOpaque = true
        Snapshots.save(p, "model-popup", 420)
    }

    fun `test the composer chip shows the model its context and the remembered effort`() {
        ClineSettings.getInstance().rememberEffort("anthropic", "medium")
        try {
            val store = ChatStore(core).also { it.start().get() }
            val composer = Composer(project, store = { store }, onSubmit = { _, _, _ -> })
            composer.update(true); settle()
            composer.update(true)
            assertEquals("Claude Sonnet 5  1M  Medium  ⌃", composer.chipText)
            assertTrue(composer.attachments.imagesAllowed)
        } finally {
            ClineSettings.getInstance().state.efforts.clear()
        }
    }
}
