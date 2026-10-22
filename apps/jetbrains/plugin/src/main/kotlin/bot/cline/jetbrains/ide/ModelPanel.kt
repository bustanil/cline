package bot.cline.jetbrains.ide

import bot.cline.jetbrains.chat.ChatStore
import bot.cline.jetbrains.chat.ModelOption
import bot.cline.jetbrains.chat.ModelSettings
import bot.cline.jetbrains.chat.ProviderOption
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.util.concurrent.CompletableFuture
import javax.swing.DefaultComboBoxModel
import javax.swing.JPanel

/** Chooses the provider and model for the current mode, and stores an API key for the provider. */
class ModelPanel(private val store: ChatStore) : JPanel(BorderLayout()) {
    private val mode = store.mode
    private val provider = ComboBox<ProviderOption>()
    private val model = ComboBox<Any>().apply { isEditable = true }
    private val key = JBPasswordField()
    private val note = JBLabel().apply { border = JBUI.Borders.emptyTop(6) }
    private var providers: List<ProviderOption> = emptyList()
    private var generation = 0

    init {
        preferredSize = JBUI.size(460, 200)
        add(
            FormBuilder.createFormBuilder()
                .addComponent(JBLabel("These settings apply to ${mode.replaceFirstChar { it.uppercase() }} mode."))
                .addLabeledComponent("Provider:", provider)
                .addLabeledComponent("Model:", model)
                .addLabeledComponent("API key:", key)
                .addComponent(note)
                .panel,
            BorderLayout.CENTER,
        )
        provider.addActionListener { (provider.selectedItem as? ProviderOption)?.let { loadModels(it) } }
        store.models.providers().whenComplete { found, error ->
            ApplicationManager.getApplication().invokeLater({
                if (error != null) {
                    note.text = "Could not load the providers: " + (error.cause?.message ?: error.message)
                    return@invokeLater
                }
                providers = found.sortedBy { it.name.lowercase() }
                val current = ModelSettings.current(store.snapshot.extensionState, mode, providers)
                provider.model = DefaultComboBoxModel(providers.toTypedArray())
                providers.firstOrNull { it.id == current.providerId }?.let { provider.selectedItem = it }
                if (current.modelId != null) model.selectedItem = current.modelId
                (provider.selectedItem as? ProviderOption)?.let { loadModels(it, keep = current.modelId) }
            }, ModalityState.any())
        }
    }

    private fun loadModels(selected: ProviderOption, keep: String? = null) {
        val mine = ++generation
        note.text = "Loading models…"
        store.models.apiKeyLength(selected.id).thenCombine(store.models.models(selected.id)) { length, list -> length to list }
            .whenComplete { result, error ->
                ApplicationManager.getApplication().invokeLater({
                    if (mine != generation) return@invokeLater
                    if (error != null) {
                        note.text = "Could not load the models: " + (error.cause?.message ?: error.message)
                        return@invokeLater
                    }
                    val (keyLength, list) = result
                    val previous = keep ?: (model.selectedItem as? ModelOption)?.id ?: model.selectedItem?.toString()
                    model.model = DefaultComboBoxModel<Any>(list.models.toTypedArray<Any>())
                    val wanted = previous?.takeIf { id -> list.models.any { it.id == id } } ?: list.defaultModelId ?: selected.defaultModelId
                    list.models.firstOrNull { it.id == wanted }?.let { model.selectedItem = it } ?: wanted?.let { model.selectedItem = it }
                    note.text = when {
                        !list.ok -> "Models could not be listed: ${list.error ?: "unknown error"}. Type a model id."
                        keyLength > 0 -> "An API key is saved ($keyLength characters). Leave the field empty to keep it."
                        else -> "No API key is saved for this provider."
                    }
                }, ModalityState.any())
            }
    }

    val chosenProvider: ProviderOption? get() = provider.selectedItem as? ProviderOption

    val chosenModelId: String?
        get() = ((model.selectedItem as? ModelOption)?.id ?: model.selectedItem?.toString())?.trim()?.takeIf { it.isNotEmpty() }

    internal fun setApiKey(text: String) {
        key.text = text
    }

    internal fun chooseProvider(id: String) {
        provider.selectedItem = providers.first { it.id == id }
    }

    internal fun chooseModel(id: String) {
        model.selectedItem = id
    }

    internal val noteText: String get() = note.text.orEmpty()

    /** Saves the key (when one was typed) and the choice. Completes when the core has taken both. */
    fun apply(): CompletableFuture<*> {
        val chosen = chosenProvider ?: return CompletableFuture.completedFuture(null)
        val modelId = chosenModelId ?: chosen.defaultModelId ?: return CompletableFuture.completedFuture(null)
        val typedKey = String(key.password).trim()
        val saveKey = if (typedKey.isNotEmpty()) store.models.saveApiKey(chosen.id, typedKey) else CompletableFuture.completedFuture("{}")
        return saveKey.thenCompose { store.models.select(chosen.id, mode, modelId) }
    }
}
