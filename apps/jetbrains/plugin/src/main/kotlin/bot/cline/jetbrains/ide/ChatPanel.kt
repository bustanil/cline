package bot.cline.jetbrains.ide

import bot.cline.jetbrains.chat.ButtonAction
import bot.cline.jetbrains.chat.ChatSnapshot
import bot.cline.jetbrains.chat.ApiMetrics
import bot.cline.jetbrains.chat.ChatStore
import bot.cline.jetbrains.chat.Transcript
import bot.cline.jetbrains.chat.TranscriptItem
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.ui.DialogWrapper
import bot.cline.jetbrains.chat.ModelSettings
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.util.concurrent.CompletableFuture
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants

/**
 * The chat: the transcript, the two footer buttons and the input box. It only
 * draws; what each control does lives in [ChatStore].
 */
class ChatPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {
    private val service = ClineProjectService.getInstance(project)
    private val rows = LinkedHashMap<Long, ChatRow>()
    private val transcript = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
    }
    private val scroll = JBScrollPane(transcript).apply {
        border = JBUI.Borders.empty()
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
    }
    private val metrics = JBLabel().apply { border = JBUI.Borders.empty(2, 10); foreground = com.intellij.util.ui.UIUtil.getContextHelpForeground() }
    private val notice = JBLabel().apply { border = JBUI.Borders.empty(12) }
    private val settingsButton = JButton("Open settings").apply {
        addActionListener { ShowSettingsUtil.getInstance().showSettingsDialog(project, ClineSettingsConfigurable::class.java) }
    }
    private val primary = JButton().apply { addActionListener { run(Slot.PRIMARY) } }
    private val secondary = JButton().apply { addActionListener { run(Slot.SECONDARY) } }
    private val input = JBTextArea(3, 40).apply {
        lineWrap = true
        wrapStyleWord = true
        border = JBUI.Borders.empty(4)
        emptyText.text = "Type a task, or a reply. Enter sends, Shift+Enter adds a line."
        addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (mentions.handleKey(e)) return
                if (e.keyCode == KeyEvent.VK_ENTER && !e.isShiftDown) {
                    e.consume()
                    send()
                }
            }
        })
    }
    private val newChat = JButton("New chat").apply { addActionListener { service.store?.let { report(it.clearTask()) } } }
    private val historyButton = JButton("History").apply { addActionListener { showHistory() } }
    private val planButton = javax.swing.JToggleButton("Plan").apply { addActionListener { service.store?.let { report(it.setMode("plan")) } } }
    private val actButton = javax.swing.JToggleButton("Act").apply { addActionListener { service.store?.let { report(it.setMode("act")) } } }
    private val modelButton = JButton("Model…").apply { addActionListener { showModel() } }
    private val changed = ChangedFilesPanel(project, store = { service.store })
    private var lastPhase: String? = null
    private var shownStore: ChatStore? = null
    private val mentions: MentionController = MentionController(input, search = { query -> service.store?.searchMentions(query) ?: CompletableFuture.completedFuture(bot.cline.jetbrains.chat.MentionResults(emptyList(), null, null)) })
    private val sendButton = JButton("Send").apply { addActionListener { send() } }
    private val listener: () -> Unit = { ApplicationManager.getApplication().invokeLater(::refresh, ModalityState.any()) }
    private val inputListener: (String) -> Unit = { text ->
        ApplicationManager.getApplication().invokeLater({
            input.text = if (input.text.isBlank()) text else input.text.trimEnd() + "\n\n" + text
            input.caretPosition = input.text.length
            input.requestFocusInWindow()
        }, ModalityState.any())
    }

    // The two footer slots; the store decides what each does.
    private object Slot { const val PRIMARY = 1; const val SECONDARY = 2 }

    init {
        val top = JPanel(BorderLayout()).apply {
            add(
                JPanel(BorderLayout()).apply {
                    add(JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
                        val modes = javax.swing.ButtonGroup().apply { add(planButton); add(actButton) }
                        add(newChat); add(historyButton); add(planButton); add(actButton); add(modelButton)
                    }, BorderLayout.NORTH)
                    add(JPanel(FlowLayout(FlowLayout.LEFT)).apply { add(notice); add(settingsButton) }, BorderLayout.CENTER)
                },
                BorderLayout.CENTER,
            )
            add(metrics, BorderLayout.SOUTH)
        }
        val buttons = JPanel(FlowLayout(FlowLayout.LEFT)).apply { add(primary); add(secondary) }
        val inputRow = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
            add(JBScrollPane(input), BorderLayout.CENTER)
            add(sendButton, BorderLayout.EAST)
        }
        val footer = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(6)
            add(JPanel(BorderLayout()).apply { add(changed, BorderLayout.NORTH); add(buttons, BorderLayout.CENTER) }, BorderLayout.NORTH)
            add(inputRow, BorderLayout.CENTER)
        }
        add(top, BorderLayout.NORTH)
        add(scroll, BorderLayout.CENTER)
        add(footer, BorderLayout.SOUTH)
        mentions.attach()
        service.addListener(listener)
        service.addInputListener(inputListener)
        service.start()
        refresh()
    }

    private fun refresh() {
        val status = service.status
        val store = service.store
        val connected = status == ClineProjectService.Status.CONNECTED && store != null
        notice.text = when (status) {
            ClineProjectService.Status.NOT_STARTED -> "Cline has not started."
            ClineProjectService.Status.STARTING -> "Starting Cline…"
            ClineProjectService.Status.MISSING_NODE -> "Node was not found. Set its path in Settings > Tools > Cline."
            ClineProjectService.Status.MISSING_CORE -> "cline-core.js was not found. Set its path in Settings > Tools > Cline."
            ClineProjectService.Status.WRONG_NODE -> (service.nodeProblem ?: "Wrong Node version.") + " Set a Node 22 path in Settings > Tools > Cline."
            ClineProjectService.Status.CONNECTED -> ""
        }
        (notice.parent as? JPanel)?.isVisible = !connected
        settingsButton.isVisible = status in setOf(ClineProjectService.Status.MISSING_NODE, ClineProjectService.Status.MISSING_CORE, ClineProjectService.Status.WRONG_NODE)
        input.isEnabled = connected
        sendButton.isEnabled = connected
        for (b in listOf(newChat, historyButton, planButton, actButton, modelButton)) b.isEnabled = connected
        if (store != null) {
            val mode = store.mode
            planButton.isSelected = mode == "plan"
            actButton.isSelected = mode != "plan"
            val providers = emptyList<bot.cline.jetbrains.chat.ProviderOption>()
            val current = ModelSettings.current(store.snapshot.extensionState, mode, providers)
            modelButton.text = listOfNotNull(current.providerId, current.modelId?.substringAfterLast('/')).joinToString(" · ").ifEmpty { "Model…" }
            modelButton.toolTipText = "Provider and model for ${mode.replaceFirstChar { it.uppercase() }} mode"
        }
        if (store == null) {
            clearRows()
            updateButtons(null)
            shownStore = null
            lastPhase = null
            changed.refresh()
            return
        }
        val snapshot = store.snapshot
        syncRows(store, snapshot)
        updateButtons(store)
        refreshChangedFiles(store, snapshot)
    }

    /** The core does not push the changed files, so ask when a run ends and when a new connection first shows. */
    private fun refreshChangedFiles(store: ChatStore, snapshot: ChatSnapshot) {
        val phase = snapshot.turnState?.phase
        val firstSight = shownStore !== store
        val turnEnded = lastPhase == "streaming" && phase != "streaming"
        shownStore = store
        lastPhase = phase
        if (firstSight || turnEnded) changed.refresh()
    }

    private fun syncRows(store: ChatStore, snapshot: ChatSnapshot) {
        val atBottom = scroll.verticalScrollBar.let { it.value + it.visibleAmount >= it.maximum - 8 }
        val items = Transcript.build(snapshot.messages)
        val keys = items.map { it.key }
        // Rows only ever grow at the end while a task runs. Anything else (a new task, history) starts over.
        if (rows.keys.toList() != keys.take(rows.size)) clearRows()
        val context = RowContext(store.fileLinks, reply = { text -> report(store.send(text)) })
        for ((index, item) in items.withIndex()) {
            val existing = rows[item.key]
            if (existing != null && existing.update(item)) continue
            val row = ChatRows.create(item, context)
            if (existing != null) {
                transcript.remove(index)
                transcript.add(row.component, index)
            } else {
                transcript.add(row.component)
            }
            rows[item.key] = row
        }
        metrics.text = ApiMetrics.describe(ApiMetrics.of(snapshot.messages))
        transcript.revalidate()
        transcript.repaint()
        if (atBottom) ApplicationManager.getApplication().invokeLater {
            scroll.verticalScrollBar.value = scroll.verticalScrollBar.maximum
        }
    }

    /** The HTML of each row now drawn; for tests. */
    internal fun renderedHtml(): List<String> = rows.values.filterIsInstance<HtmlRow>().map { it.html }

    internal fun metricsText(): String = metrics.text.orEmpty()

    private fun clearRows() {
        rows.clear()
        transcript.removeAll()
        transcript.revalidate()
        transcript.repaint()
    }

    private fun showHistory() {
        val store = service.store ?: return
        lateinit var dialog: DialogWrapper
        val content = HistoryPanel(store, onOpened = { dialog.close(DialogWrapper.OK_EXIT_CODE) })
        dialog = object : DialogWrapper(project, true) {
            init {
                title = "Task History"
                setOKButtonText("Close")
                init()
            }

            override fun createCenterPanel() = content

            override fun createActions() = arrayOf(okAction)
        }
        dialog.show()
    }

    private fun showModel() {
        val store = service.store ?: return
        val content = ModelPanel(store)
        val dialog = object : DialogWrapper(project, true) {
            init {
                title = "Model and API Key"
                init()
            }

            override fun createCenterPanel() = content

            override fun doOKAction() {
                super.doOKAction()
                report(content.apply())
            }
        }
        dialog.show()
    }

    private fun updateButtons(store: ChatStore?) {
        val config = store?.buttons()
        primary.isVisible = config?.primaryText != null
        secondary.isVisible = config?.secondaryText != null
        primary.text = config?.primaryText
        secondary.text = config?.secondaryText
        primary.isEnabled = config?.enableButtons == true
        secondary.isEnabled = config?.enableButtons == true
    }

    private fun run(slot: Int) {
        val store = service.store ?: return
        val config = store.buttons()
        val action = (if (slot == Slot.PRIMARY) config.primaryAction else config.secondaryAction) ?: return
        val draft = input.text
        report(store.perform(action, draft))
        if (action != ButtonAction.CANCEL && action != ButtonAction.NEW_TASK) input.text = ""
    }

    private fun send() {
        val store = service.store ?: return
        val text = input.text.trim()
        if (text.isEmpty()) return
        input.text = ""
        report(store.send(text))
    }

    private fun report(future: CompletableFuture<*>) {
        future.whenComplete { _, error ->
            if (error != null) {
                ApplicationManager.getApplication().invokeLater {
                    notice.text = "Cline could not do that: " + (error.cause?.message ?: error.message)
                    (notice.parent as? JPanel)?.isVisible = true
                }
            }
        }
    }

    override fun dispose() {
        service.removeListener(listener)
        service.removeInputListener(inputListener)
    }
}
