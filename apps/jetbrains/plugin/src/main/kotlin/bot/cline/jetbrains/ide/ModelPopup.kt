package bot.cline.jetbrains.ide

import bot.cline.jetbrains.chat.ChatStore
import bot.cline.jetbrains.chat.ModelDetails
import bot.cline.jetbrains.chat.ModelOption
import bot.cline.jetbrains.chat.ModelSettings
import bot.cline.jetbrains.chat.ProviderOption
import bot.cline.jetbrains.chat.ReasoningEffort
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Point
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * What the model chip opens: a Model row that expands to a provider and a searchable list of its models, and
 * an Effort row that expands to how hard the model should think. Choosing something applies it at once.
 */
class ModelPopupPanel(
    private val store: ChatStore,
    private val onChosen: () -> Unit = {},
    private val onKeyRequested: () -> Unit = {},
    private val remembered: (String) -> String? = { ClineSettings.getInstance().effortFor(it) },
    private val remember: (String, String) -> Unit = { provider, effort -> ClineSettings.getInstance().rememberEffort(provider, effort) },
    /** The width in pixels, so the popup fits inside the tool window. */
    private val popupWidth: Int = JBUI.scale(340),
) : JPanel(BorderLayout()) {
    private val mode = store.mode
    private val providerBox = ComboBox<ProviderOption>().apply {
        renderer = object : ColoredListCellRenderer<ProviderOption>() {
            override fun customizeCellRenderer(list: JList<out ProviderOption>, value: ProviderOption?, index: Int, selected: Boolean, hasFocus: Boolean) {
                append(value?.name ?: "")
            }
        }
    }
    private val search = SearchTextField(false)
    private val modelModel = DefaultListModel<ModelOption>()
    private val modelList = JBList(modelModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = object : ColoredListCellRenderer<ModelOption>() {
            init { ipad = JBUI.insets(3, 10) }

            override fun customizeCellRenderer(list: JList<out ModelOption>, value: ModelOption?, index: Int, selected: Boolean, hasFocus: Boolean) {
                value ?: return
                append(value.label)
                if (value.contextLabel.isNotEmpty()) append("   " + value.contextLabel, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                if (value.id == currentModelId) append("   ✓", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (selectedIndex >= 0) chooseModel(selectedValue)
            }
        })
    }
    private val effortModel = DefaultListModel<ReasoningEffort>().apply { ReasoningEffort.entries.forEach(::addElement) }
    private val effortList = JBList(effortModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = object : ColoredListCellRenderer<ReasoningEffort>() {
            init { ipad = JBUI.insets(3, 14) }

            override fun customizeCellRenderer(list: JList<out ReasoningEffort>, value: ReasoningEffort?, index: Int, selected: Boolean, hasFocus: Boolean) {
                value ?: return
                append(value.label)
                if (value == currentEffort) append("   ✓", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (selectedIndex >= 0) chooseEffort(selectedValue)
            }
        })
    }
    private val modelSection = JPanel(BorderLayout(0, JBUI.scale(4))).apply { isVisible = false; isOpaque = false }
    private val effortSection = JPanel(BorderLayout()).apply { isVisible = false; isOpaque = false }
    private val modelValue = JBLabel()
    private val effortValue = JBLabel()
    private val note = JBLabel().apply { foreground = UIUtil.getContextHelpForeground(); border = JBUI.Borders.empty(2, 12) }

    /** A fixed width, and the height the open sections need. */
    override fun getPreferredSize(): Dimension = super.getPreferredSize().also { it.width = popupWidth }

    private var providers: List<ProviderOption> = emptyList()
    private var allModels: List<ModelOption> = emptyList()
    private var details: ModelDetails? = null
    private var generation = 0

    private val current get() = ModelSettings.current(store.snapshot.extensionState, mode, providers)
    private val currentModelId: String? get() = current.modelId
    private val currentEffort: ReasoningEffort? get() = current.providerId?.let { remembered(it) }?.let(ReasoningEffort::fromWire)

    init {
        isOpaque = false
        border = JBUI.Borders.empty(6)
        modelSection.add(JPanel(BorderLayout(JBUI.scale(6), 0)).apply { isOpaque = false; add(JBLabel("Provider"), BorderLayout.WEST); add(providerBox, BorderLayout.CENTER) }, BorderLayout.NORTH)
        modelSection.add(search, BorderLayout.CENTER)
        modelSection.add(JBScrollPane(modelList).apply { preferredSize = JBUI.size(340, 170) }, BorderLayout.SOUTH)
        effortSection.add(effortList, BorderLayout.CENTER)
        val keyLink = JBLabel("API key…").apply {
            foreground = com.intellij.ui.JBColor.namedColor("Link.activeForeground", com.intellij.ui.JBColor.BLUE)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            border = JBUI.Borders.empty(4, 12)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) = onKeyRequested()
            })
        }
        val column = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(row("Model", modelValue, modelSection))
            add(modelSection.also { it.alignmentX = 0f })
            add(row("Effort", effortValue, effortSection))
            add(effortSection.also { it.alignmentX = 0f })
            add(keyLink.also { it.alignmentX = 0f })
            add(note.also { it.alignmentX = 0f })
        }
        add(column, BorderLayout.CENTER)
        providerBox.addActionListener { (providerBox.selectedItem as? ProviderOption)?.let(::loadModels) }
        search.textEditor.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = filter()
            override fun removeUpdate(e: DocumentEvent) = filter()
            override fun changedUpdate(e: DocumentEvent) = Unit
        })
        store.models.providers().whenComplete { found, error ->
            ApplicationManager.getApplication().invokeLater({
                if (error != null) {
                    note.text = "Could not load the providers: " + (error.cause?.message ?: error.message)
                    return@invokeLater
                }
                providers = found.sortedBy { it.name.lowercase() }
                providerBox.model = DefaultComboBoxModel(providers.toTypedArray())
                providers.firstOrNull { it.id == current.providerId }?.let { providerBox.selectedItem = it }
                updateValues()
                (providerBox.selectedItem as? ProviderOption)?.let(::loadModels)
            }, ModalityState.any())
        }
        current.providerId?.let { provider ->
            store.models.details(provider, mode).whenComplete { found, _ ->
                ApplicationManager.getApplication().invokeLater({ details = found; updateValues() }, ModalityState.any())
            }
        }
        updateValues()
    }

    /** A row like "Model  Claude Sonnet 5  1M  >" that opens or closes [section]. */
    private fun row(title: String, value: JBLabel, section: JComponent): JComponent = JPanel(BorderLayout()).apply {
        isOpaque = false
        border = JBUI.Borders.empty(8, 10)
        alignmentX = 0f
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        val left = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply { isOpaque = false; add(JBLabel(title).apply { font = font.deriveFont(java.awt.Font.BOLD) }); add(value.apply { foreground = UIUtil.getContextHelpForeground() }) }
        val chevron = JBLabel(AllIcons.General.ArrowRight)
        add(left, BorderLayout.CENTER)
        add(chevron, BorderLayout.EAST)
        val toggle = object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                section.isVisible = !section.isVisible
                chevron.icon = if (section.isVisible) AllIcons.General.ArrowDown else AllIcons.General.ArrowRight
                revalidate()
                SwingUtilitiesAncestor.repack(this@ModelPopupPanel)
            }
        }
        addMouseListener(toggle)
        left.addMouseListener(toggle)
    }

    private fun updateValues() {
        val d = details
        val name = d?.name?.takeIf { it.isNotBlank() } ?: currentModelId?.substringAfterLast('/') ?: "Not set"
        val context = d?.contextWindow?.let { ModelOption("", null, it).contextLabel }.orEmpty()
        modelValue.text = listOf(name, context).filter { it.isNotEmpty() }.joinToString("  ")
        effortValue.text = currentEffort?.label ?: "Default"
        if (d != null && !d.supportsReasoning) note.text = "This model does not report reasoning support, so effort may have no effect."
    }

    private fun loadModels(provider: ProviderOption) {
        val mine = ++generation
        note.text = "Loading models…"
        store.models.models(provider.id).whenComplete { list, error ->
            ApplicationManager.getApplication().invokeLater({
                if (mine != generation) return@invokeLater
                if (error != null) {
                    note.text = "Could not load the models: " + (error.cause?.message ?: error.message)
                    return@invokeLater
                }
                allModels = list.models
                note.text = if (list.ok) "" else "Models could not be listed: ${list.error ?: "unknown error"}."
                filter()
            }, ModalityState.any())
        }
    }

    private fun filter() {
        val q = search.text.trim().lowercase()
        modelModel.clear()
        allModels.filter { q.isEmpty() || it.id.lowercase().contains(q) || (it.name?.lowercase()?.contains(q) == true) }.forEach(modelModel::addElement)
        modelList.setSelectedValue(allModels.firstOrNull { it.id == currentModelId }, true)
    }

    internal fun chooseModel(model: ModelOption) {
        val provider = (providerBox.selectedItem as? ProviderOption) ?: return
        store.models.select(provider.id, mode, model.id).whenComplete { _, error ->
            ApplicationManager.getApplication().invokeLater({
                if (error != null) note.text = "Could not select it: " + (error.cause?.message ?: error.message) else onChosen()
            }, ModalityState.any())
        }
    }

    internal fun chooseEffort(effort: ReasoningEffort) {
        val provider = current.providerId ?: (providerBox.selectedItem as? ProviderOption)?.id ?: return
        store.models.setEffort(provider, effort).whenComplete { _, error ->
            ApplicationManager.getApplication().invokeLater({
                if (error != null) {
                    note.text = "Could not set the effort: " + (error.cause?.message ?: error.message)
                } else {
                    remember(provider, effort.wire)
                    updateValues()
                    onChosen()
                }
            }, ModalityState.any())
        }
    }

    // For tests.
    internal fun providerIds(): List<String> = providers.map { it.id }
    internal fun modelIds(): List<String> = modelModel.elements().toList().map { it.id }
    internal fun chooseProvider(id: String) { providerBox.selectedItem = providers.first { it.id == id } }
    internal fun setSearch(text: String) { search.text = text }
    internal fun openSections() { modelSection.isVisible = true; effortSection.isVisible = true }
    internal val modelText: String get() = modelValue.text.orEmpty()
    internal val effortText: String get() = effortValue.text.orEmpty()
    internal val noteText: String get() = note.text.orEmpty()
}

/** Makes a popup fit its content again after a row opened or closed. */
internal object SwingUtilitiesAncestor {
    fun repack(component: JComponent) {
        component.revalidate()
        val window = javax.swing.SwingUtilities.getWindowAncestor(component)
        window?.pack()
    }
}

object ModelPopup {
    /** As wide as the composer it opens from, less a small margin, but not narrower than 240 or wider than 420 (logical pixels). */
    fun widthFor(composerWidth: Int): Int = (composerWidth - JBUI.scale(16)).coerceIn(JBUI.scale(240), JBUI.scale(420))

    /** Opens the popup above [anchor]. [onChanged] runs after a model or an effort was chosen. */
    fun show(project: Project, store: ChatStore, anchor: JComponent, onChanged: () -> Unit): JBPopup {
        lateinit var popup: JBPopup
        val composer = javax.swing.SwingUtilities.getAncestorOfClass(Composer::class.java, anchor) as? JComponent
        val panel = ModelPopupPanel(
            store,
            popupWidth = widthFor(composer?.width ?: anchor.width * 3),
            onChosen = { popup.cancel(); onChanged() },
            onKeyRequested = {
                popup.cancel()
                val content = ModelPanel(store)
                object : DialogWrapper(project, true) {
                    init { title = "Model and API Key"; init() }
                    override fun createCenterPanel() = content
                    override fun doOKAction() { super.doOKAction(); content.apply().whenComplete { _, _ -> ApplicationManager.getApplication().invokeLater(onChanged) } }
                }.show()
            },
        )
        popup = JBPopupFactory.getInstance().createComponentPopupBuilder(panel, panel)
            .setRequestFocus(true).setFocusable(true).setCancelOnClickOutside(true).setResizable(false).setMovable(false)
            .createPopup()
        val size = panel.preferredSize
        // Line up with the composer's left edge so the popup stays over the tool window, not the editor.
        val left = composer?.let { javax.swing.SwingUtilities.convertPoint(it, JBUI.scale(8), 0, anchor).x } ?: (anchor.width - size.width)
        popup.show(RelativePoint(anchor, Point(left, -size.height - JBUI.scale(6))))
        return popup
    }
}
