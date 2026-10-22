package bot.cline.jetbrains.chat

import bot.cline.jetbrains.bridge.CoreApi
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.concurrent.CompletableFuture

data class ProviderOption(val id: String, val name: String, val defaultModelId: String?)

data class ModelOption(val id: String, val name: String?) {
    override fun toString() = name?.takeIf { it.isNotBlank() && it != id }?.let { "$it ($id)" } ?: id
}

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
            val models = o.getAsJsonObject("models")?.entrySet()?.map { (id, info) -> ModelOption(id, info.asJsonObject.str("name")) }.orEmpty()
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
