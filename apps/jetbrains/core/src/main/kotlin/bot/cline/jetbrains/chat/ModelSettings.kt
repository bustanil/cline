package bot.cline.jetbrains.chat

import bot.cline.jetbrains.bridge.CoreApi
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.concurrent.CompletableFuture

data class ProviderOption(val id: String, val name: String, val defaultModelId: String?)

data class ModelOption(
    val id: String,
    val name: String?,
    /** Tokens the model can read at once. */
    val contextWindow: Long? = null,
    val supportsImages: Boolean = false,
    val supportsReasoning: Boolean = false,
) {
    override fun toString() = name?.takeIf { it.isNotBlank() && it != id }?.let { "$it ($id)" } ?: id

    /** The name to show: the model's own name, else its id. */
    val label: String get() = name?.takeIf { it.isNotBlank() } ?: id

    /** The context size as `200K` or `1M`, or empty when unknown. */
    val contextLabel: String
        get() = contextWindow?.takeIf { it > 0 }?.let { w -> if (w >= 1_000_000) trimZero(w / 1_000_000.0) + "M" else if (w >= 1_000) trimZero(w / 1_000.0) + "K" else w.toString() }.orEmpty()

    private fun trimZero(v: Double) = if (v == Math.floor(v)) v.toLong().toString() else String.format("%.1f", v)
}

/** How hard the model should think. The core takes these names in a provider's `reasoning` setting. */
enum class ReasoningEffort(val wire: String, val label: String) {
    NONE("none", "Off"),
    LOW("low", "Low"),
    MEDIUM("medium", "Medium"),
    HIGH("high", "High"),
    XHIGH("xhigh", "Extra high"),
    ;

    companion object {
        fun fromWire(value: String?): ReasoningEffort? = entries.firstOrNull { it.wire == value }
    }
}

/** What the core knows about the model a mode uses now. */
data class ModelDetails(val modelId: String?, val name: String?, val contextWindow: Long?, val supportsImages: Boolean, val supportsReasoning: Boolean)

data class ModelList(val ok: Boolean, val models: List<ModelOption>, val defaultModelId: String?, val error: String?)

/** The provider and model a mode uses now. */
data class ModelSelection(val providerId: String?, val modelId: String?)

/**
 * Which provider and model Plan and Act use, and the API key for a provider.
 * Everything goes through the core, which keeps the settings in the shared
 * `~/.cline` data folder, so the CLI and other clients see the same choice.
 */
class ModelSettings(private val core: CoreApi) {
    fun providers(): CompletableFuture<List<ProviderOption>> = core.call("cline.ModelsService", "listProviders").thenApply { json ->
        JsonParser.parseString(json).asJsonObject.getAsJsonArray("providers")?.map {
            val o = it.asJsonObject
            ProviderOption(o.str("id").orEmpty(), o.str("name") ?: o.str("id").orEmpty(), o.str("defaultModelId"))
        }.orEmpty().filter { it.id.isNotEmpty() }
    }

    /** The models a provider offers. A failed lookup answers `ok = false` with the reason, not an exception. */
    fun models(providerId: String, forceRefresh: Boolean = false): CompletableFuture<ModelList> {
        val body = JsonObject().apply {
            addProperty("providerId", providerId)
            if (forceRefresh) addProperty("forceRefresh", true)
        }.toString()
        return core.call("cline.ModelsService", "resolveProviderModels", body).thenApply { json ->
            val o = JsonParser.parseString(json).asJsonObject
            val ok = o.get("ok")?.asBoolean == true
            val models = o.getAsJsonObject("models")?.entrySet()?.map { (id, info) ->
                val m = info.asJsonObject
                ModelOption(
                    id, m.str("name"), m.get("contextWindow")?.takeIf { it.isJsonPrimitive }?.asLong,
                    m.get("supportsImages")?.asBoolean == true, m.get("supportsReasoning")?.asBoolean == true,
                )
            }.orEmpty()
            ModelList(ok, models.sortedBy { it.name ?: it.id }, o.str("defaultModelId"), o.getAsJsonObject("error")?.str("message"))
        }
    }

    /** Uses [modelId] of [providerId] for [mode] (`plan` or `act`). */
    fun select(providerId: String, mode: String, modelId: String): CompletableFuture<String> =
        core.call(
            "cline.ModelsService", "commitModelSelection",
            JsonObject().apply { addProperty("providerId", providerId); addProperty("mode", mode); addProperty("modelId", modelId) }.toString(),
        )

    fun saveApiKey(providerId: String, apiKey: String): CompletableFuture<String> =
        core.call(
            "cline.ModelsService", "writeProviderConfig",
            JsonObject().apply {
                addProperty("providerId", providerId)
                add("patch", JsonObject().apply { addProperty("apiKey", apiKey) })
            }.toString(),
        )

    /**
     * Sets how hard the provider's models think. The core accepts this but does not report it back, so the
     * caller has to remember what it set.
     */
    fun setEffort(providerId: String, effort: ReasoningEffort): CompletableFuture<String> =
        core.call(
            "cline.ModelsService", "writeProviderConfig",
            JsonObject().apply {
                addProperty("providerId", providerId)
                add("patch", JsonObject().apply {
                    add("reasoning", JsonObject().apply {
                        addProperty("enabled", effort != ReasoningEffort.NONE)
                        if (effort != ReasoningEffort.NONE) addProperty("effort", effort.wire)
                    })
                })
            }.toString(),
        )

    /** The model [mode] (`plan` or `act`) uses with [providerId], with its context size and what it supports. */
    fun details(providerId: String, mode: String): CompletableFuture<ModelDetails?> =
        core.call("cline.ModelsService", "readProviderConfig", JsonObject().apply { addProperty("value", providerId) }.toString()).thenApply { json ->
            val selection = JsonParser.parseString(json).asJsonObject.getAsJsonObject("${mode}Selection") ?: return@thenApply null
            val info = selection.getAsJsonObject("modelInfo")
            ModelDetails(
                modelId = selection.str("modelId"),
                name = info?.str("name"),
                contextWindow = info?.get("contextWindow")?.takeIf { it.isJsonPrimitive }?.asLong,
                supportsImages = info?.get("supportsImages")?.asBoolean == true,
                supportsReasoning = info?.get("supportsReasoning")?.asBoolean == true,
            )
        }

    /** How long the stored key is; the core never returns the key itself. 0 means none. */
    fun apiKeyLength(providerId: String): CompletableFuture<Int> =
        core.call("cline.ModelsService", "readProviderConfig", JsonObject().apply { addProperty("value", providerId) }.toString())
            .thenApply { JsonParser.parseString(it).asJsonObject.get("apiKeyLength")?.asInt ?: 0 }

    companion object {
        /** The selection for [mode] in the extension state. A model not set explicitly is the provider's default. */
        fun current(state: JsonObject, mode: String, providers: List<ProviderOption> = emptyList()): ModelSelection {
            val api = state.getAsJsonObject("apiConfiguration") ?: return ModelSelection(null, null)
            val provider = api.str("${mode}ModeApiProvider")
            val model = api.str("${mode}ModeApiModelId") ?: providers.firstOrNull { it.id == provider }?.defaultModelId
            return ModelSelection(provider, model)
        }
    }
}
