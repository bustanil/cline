package bot.cline.jetbrains.ide

import bot.cline.jetbrains.chat.ButtonAction
import bot.cline.jetbrains.chat.ChatSnapshot
import bot.cline.jetbrains.chat.ApiMetrics
import bot.cline.jetbrains.chat.ChatStore
import bot.cline.jetbrains.chat.Coalescer
import bot.cline.jetbrains.chat.Transcript
import bot.cline.jetbrains.chat.TranscriptItem
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.ShowSettingsUtil
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
    private val transcript = TranscriptPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = true
        background = ChatColors.page
    }
    private val scroll = JBScrollPane(transcript).apply {
        border = JBUI.Borders.empty()
        viewport.background = ChatColors.page
        background = ChatColors.page
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
    }
    private val status = UsageStatusLine()
    private val notice = JBLabel().apply { border = JBUI.Borders.empty(12) }
    private val settingsButton = JButton("Open settings").apply {
        addActionListener { ShowSettingsUtil.getInstance().showSettingsDialog(project, ClineSettingsConfigurable::class.java) }
    }
    private val primary = JButton().apply { addActionListener { run(Slot.PRIMARY) } }
    private val secondary = JButton().apply { addActionListener { run(Slot.SECONDARY) } }
    private val approve = AutoApproveBar { service.store }
    private val changed = ChangedFilesPanel(project, store = { service.store })
    // Closing the button bar hides it until the buttons or the conversation change.
    private var dismissedFor: String? = null
    private var buttonKey: String? = null
    private val dismiss = JBLabel(com.intellij.icons.AllIcons.Actions.Close).apply {
        toolTipText = "Dismiss"
        cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        border = JBUI.Borders.empty(4)
        addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                dismissedFor = buttonKey
                service.store?.let { updateButtons(it) }
            }
        })
    }
    private val buttonBar = JPanel(BorderLayout())
    private var lastPhase: String? = null
    private var shownStore: ChatStore? = null
    private val composer: Composer = Composer(project, store = { service.store }, onSubmit = { text, images, files -> sendMessage(text, images, files) })
    private val input get() = composer.input
    private val mentions: MentionController = MentionController(composer.input, search = { query -> service.store?.searchMentions(query) ?: CompletableFuture.completedFuture(bot.cline.jetbrains.chat.MentionResults(emptyList(), null, null)) })
    // Updates arrive for nearly every word while a reply streams. Drawing each one makes the chat flicker, so they
    // are batched: at most one draw per 50 ms, always ending on the latest state.
    private val redraw = Coalescer(
        intervalMs = RENDER_INTERVAL_MS,
        schedule = { delay, task ->
            ApplicationManager.getApplication().invokeLater({
                if (delay <= 0) task() else javax.swing.Timer(delay.toInt()) { task() }.apply { isRepeats = false; start() }
            }, ModalityState.any())
        },
        run = { refresh() },
    )
    private val listener: () -> Unit = { redraw.request() }
    private val newChatListener: () -> Unit = { composer.reset() }
    private val inputListener: (String) -> Unit = { text ->
        ApplicationManager.getApplication().invokeLater({
            input.text = if (input.text.isBlank()) text else input.text.trimEnd() + "\n\n" + text
            input.caretPosition = input.text.length
            input.requestFocusInWindow()
        }, ModalityState.any())
    }

    companion object {
        /** The longest wait between draws while updates keep coming: about 20 per second. */
        const val RENDER_INTERVAL_MS = 50L
    }

    // The two footer slots; the store decides what each does.
    private object Slot { const val PRIMARY = 1; const val SECONDARY = 2 }

    init {
        val top = JPanel(BorderLayout()).apply {
            add(JPanel(FlowLayout(FlowLayout.LEFT)).apply { add(notice); add(settingsButton) }, BorderLayout.CENTER)
        }
        val buttons = buttonBar.apply {
            border = JBUI.Borders.emptyTop(6)
            add(JPanel(FlowLayout(FlowLayout.LEFT)).apply { add(primary); add(secondary) }, BorderLayout.CENTER)
            add(dismiss, BorderLayout.EAST)
        }
        val footer = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(0, 0, 4, 0)
            background = ChatColors.page
            isOpaque = true
            add(JPanel(BorderLayout()).apply { isOpaque = false; border = JBUI.Borders.empty(0, 10); add(changed, BorderLayout.NORTH); add(buttons, BorderLayout.CENTER) }, BorderLayout.NORTH)
            add(JPanel(BorderLayout()).apply { isOpaque = false; add(approve, BorderLayout.NORTH); add(composer, BorderLayout.CENTER); add(status, BorderLayout.SOUTH) }, BorderLayout.CENTER)
        }
        add(top, BorderLayout.NORTH)
        add(scroll, BorderLayout.CENTER)
        add(footer, BorderLayout.SOUTH)
        mentions.attach()
        composer.keyHandler = { e -> mentions.handleKey(e) }
        composer.onModelDetails = { service.store?.let { updateStatus(it.snapshot) } }
        service.addListener(listener)
        service.addInputListener(inputListener)
        service.addNewChatListener(newChatListener)
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
            ClineProjectService.Status.MISSING_NODE -> "Node was not found. Set its path in Settings > Tools > Cline Native."
            ClineProjectService.Status.MISSING_CORE -> "cline-core.js was not found. Set its path in Settings > Tools > Cline Native."
            ClineProjectService.Status.WRONG_NODE -> (service.nodeProblem ?: "Wrong Node version.") + " Set a Node 22 path in Settings > Tools > Cline Native."
            ClineProjectService.Status.CONNECTED -> ""
        }
        (notice.parent as? JPanel)?.isVisible = !connected
        settingsButton.isVisible = status in setOf(ClineProjectService.Status.MISSING_NODE, ClineProjectService.Status.MISSING_CORE, ClineProjectService.Status.WRONG_NODE)
        composer.update(connected)
        approve.refresh()
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
        val items = Transcript.build(snapshot.messages, snapshot.turnState?.phase)
        val keys = items.map { it.key }
        // Rows only ever grow at the end while a task runs. Anything else (a new task, history) starts over.
        if (rows.keys.toList() != keys.take(rows.size)) clearRows()
        val context = RowContext(
            store.fileLinks,
            reply = { text -> report(store.send(text)) },
            highlighter = IdeCodeHighlighter(project),
            quotes = object : QuoteActions {
                override fun addQuote(quote: String, reply: String) = composer.addQuote(quote, reply)

                override fun sendReply(quote: String, text: String) = report(store.send(bot.cline.jetbrains.chat.Quote.format(quote, text)))
            },
        )
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
        updateStatus(snapshot)
        transcript.revalidate()
        transcript.repaint()
        if (atBottom) ApplicationManager.getApplication().invokeLater {
            scroll.verticalScrollBar.value = scroll.verticalScrollBar.maximum
        }
    }

    /** The HTML of each row now drawn; for tests. */
    internal fun renderedHtml(): List<String> = rows.values.filterIsInstance<HtmlRow>().map { it.html }

    /** The cost text and the context bar under the composer. The bar needs the model's context window, which the composer looks up. */
    private fun updateStatus(snapshot: ChatSnapshot) {
        status.update(ApiMetrics.describe(ApiMetrics.of(snapshot.messages)), bot.cline.jetbrains.chat.ContextUsage.of(snapshot.messages, composer.contextWindow))
    }

    internal fun metricsText(): String = status.metricsText

    internal fun statusLine(): UsageStatusLine = status

    private fun clearRows() {
        rows.clear()
        transcript.removeAll()
        transcript.revalidate()
        transcript.repaint()
    }

    private fun updateButtons(store: ChatStore?) {
        val config = store?.buttons()
        val key = config?.let { "${it.primaryText}|${it.secondaryText}|${store.snapshot.messages.lastOrNull()?.ts}" }
        if (key != buttonKey) {
            buttonKey = key
            dismissedFor = null
        }
        val shown = config != null && dismissedFor != key
        primary.isVisible = shown && config?.primaryText != null
        secondary.isVisible = shown && config?.secondaryText != null
        dismiss.isVisible = shown && (config?.primaryText != null || config?.secondaryText != null)
        buttonBar.isVisible = dismiss.isVisible
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

    private fun sendMessage(text: String, images: List<String>, files: List<String>) {
        val store = service.store ?: return
        report(store.send(text, images, files))
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

    internal fun drawsSoFar(): Int = redraw.runs

    internal fun composer(): Composer = composer

    override fun dispose() {
        service.removeListener(listener)
        service.removeInputListener(inputListener)
        service.removeNewChatListener(newChatListener)
    }
}

/**
 * The column of rows. It is as wide as the viewport and no wider, so text wraps to what is visible
 * instead of running off the right edge when one row (a wide table, a long path) wants more room.
 */
class TranscriptPanel : JPanel(), javax.swing.Scrollable {
    override fun getPreferredScrollableViewportSize(): java.awt.Dimension = preferredSize

    override fun getScrollableUnitIncrement(visibleRect: java.awt.Rectangle, orientation: Int, direction: Int): Int = JBUI.scale(24)

    override fun getScrollableBlockIncrement(visibleRect: java.awt.Rectangle, orientation: Int, direction: Int): Int =
        (if (orientation == javax.swing.SwingConstants.VERTICAL) visibleRect.height else visibleRect.width) - JBUI.scale(24)

    override fun getScrollableTracksViewportWidth(): Boolean = true

    override fun getScrollableTracksViewportHeight(): Boolean = false
}
