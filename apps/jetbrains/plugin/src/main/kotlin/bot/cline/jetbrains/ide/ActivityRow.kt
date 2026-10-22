package bot.cline.jetbrains.ide

import bot.cline.jetbrains.chat.ActivityRenderer
import bot.cline.jetbrains.chat.ActivityStep
import bot.cline.jetbrains.chat.ActivityView
import bot.cline.jetbrains.chat.FileLink
import bot.cline.jetbrains.chat.TranscriptItem
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.JBColor
import com.intellij.ui.RoundedLineBorder
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel

private fun clickable(component: JComponent, onClick: () -> Unit) {
    component.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    component.addMouseListener(object : MouseAdapter() {
        override fun mouseClicked(e: MouseEvent) = onClick()
    })
}

/**
 * Commands and tool calls that follow each other, drawn as a header such as
 * "Ran 3 commands" over a bordered list of one-line steps ("Ran python3 …").
 * A step opens to show its command and output, or its diff. The step in
 * progress says "Running" and shows a spinner. A lone step is drawn without
 * the header.
 */
class ActivityRow(item: TranscriptItem.Activity, private val context: RowContext) : ChatRow {
    private val key = item.key
    private val panel = JPanel(BorderLayout()).apply {
        isOpaque = false
        border = JBUI.Borders.empty(4, 10)
    }
    override val component: JComponent get() = panel

    private val steps = LinkedHashMap<Long, StepPanel>()
    private var view: ActivityView = ActivityRenderer.render(item, context.colors())
    private var groupOpen: Boolean? = null // null until the user decides; then it stays as they left it

    init {
        render()
    }

    private var lastItem: TranscriptItem.Activity = item

    override fun update(item: TranscriptItem): Boolean {
        if (item !is TranscriptItem.Activity || item.key != key) return false
        if (item.sameContentAs(lastItem)) return true
        lastItem = item
        view = ActivityRenderer.render(item, context.colors())
        render()
        return true
    }

    /** While the activity is the live end of the chat it is open; once finished it folds away. */
    private val isOpen: Boolean get() = view.steps.size == 1 || (groupOpen ?: view.running)

    private var shownShape: List<Any?>? = null

    /** How many times the row's parts were rearranged; for tests. */
    internal var layouts = 0
        private set

    private fun render() {
        val live = view.steps.map { it.key }.toSet()
        steps.keys.retainAll(live)
        for ((index, step) in view.steps.withIndex()) {
            val isLast = index == view.steps.lastIndex
            steps.getOrPut(step.key) { StepPanel(context, openByDefault = { view.running && isLast }) }.update(step)
        }
        // The steps update themselves in place. The row is rearranged only when its shape changed: a step
        // was added or finished, or it was opened or closed. Not on every streamed line of output.
        val shape = listOf(view.title, isOpen, view.steps.map { it.key to it.running })
        if (shape == shownShape) return
        shownShape = shape
        layouts++
        panel.removeAll()
        val box = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = 0f
        }
        if (view.steps.size > 1) box.add(header())
        if (isOpen) {
            val list = JPanel().apply {
                alignmentX = 0f
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = true
                background = ChatColors.card
                border = if (view.steps.size > 1) BorderFactory.createCompoundBorder(RoundedLineBorder(ChatColors.border, 10), JBUI.Borders.empty(2, 0)) else RoundedLineBorder(ChatColors.border, 10)
            }
            view.steps.forEachIndexed { index, step ->
                if (index > 0) list.add(JPanel().apply { background = ChatColors.border; preferredSize = JBUI.size(1, 1); maximumSize = JBUI.size(Int.MAX_VALUE, 1) })
                list.add(steps.getValue(step.key).component)
            }
            box.add(list)
        }
        panel.add(box, BorderLayout.CENTER)
        panel.revalidate()
        panel.repaint()
    }

    private fun header(): JComponent {
        val label = JBLabel(view.title, if (isOpen) AllIcons.General.ArrowDown else AllIcons.General.ArrowRight, JBLabel.LEADING).apply {
            foreground = UIUtil.getContextHelpForeground()
            // The chevron follows the text, as in the Claude Code app.
            horizontalTextPosition = javax.swing.SwingConstants.LEADING
            iconTextGap = JBUI.scale(4)
            border = JBUI.Borders.empty(2, 2, 4, 2)
        }
        val row = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { isOpaque = false; add(label) }
        if (view.running) row.add(JBLabel(AnimatedIcon.Default()).apply { border = JBUI.Borders.emptyLeft(6) })
        clickable(row) { groupOpen = !isOpen; render() }
        clickable(label) { groupOpen = !isOpen; render() }
        return row.apply { alignmentX = 0f }
    }

    internal val title: String get() = view.title

    internal val isGroupOpen: Boolean get() = isOpen

    internal fun toggleGroup() {
        groupOpen = !isOpen
        render()
    }

    internal fun stepPanels(): List<StepPanel> = view.steps.map { steps.getValue(it.key) }
}

/** One step of an activity: a line that opens to its detail. */
class StepPanel(private val context: RowContext, private val openByDefault: () -> Boolean) {
    private val panel = JPanel(BorderLayout()).apply {
        isOpaque = false
        border = JBUI.Borders.empty(4, 8)
    }
    val component: JComponent get() = panel

    private var step: ActivityStep? = null
    private var userOpen: Boolean? = null
    private val resolved = HashMap<String, FileLink>()
    private val asked = HashSet<String>()
    private val pane = newHtmlPane(context.inlineCodeRule, context.quotes) { href -> openChatLink(href, resolved, context) }
    private val terminal = JPanel(BorderLayout()).apply {
        background = ChatColors.terminal
        border = BorderFactory.createCompoundBorder(RoundedLineBorder(ChatColors.border, 8), JBUI.Borders.empty(4, 4))
    }

    val isOpen: Boolean get() = userOpen ?: openByDefault()

    fun update(step: ActivityStep) {
        this.step = step
        draw()
        // Tool steps ask the core about their file only once opened, so a long list costs nothing until needed.
        if (isOpen) resolveReferences(step)
    }

    fun toggle() {
        userOpen = !isOpen
        draw()
        step?.let { if (isOpen) resolveReferences(it) }
    }

    private fun resolveReferences(step: ActivityStep) {
        for (reference in step.references) {
            if (!asked.add(reference)) continue
            context.fileLinks.resolve(reference).whenComplete { link, _ ->
                if (link != null) {
                    ApplicationManager.getApplication().invokeLater({
                        resolved[reference] = link
                        draw()
                    }, ModalityState.any())
                }
            }
        }
    }

    private var shownStructure: List<Any?>? = null
    private var shownHtml: String? = null

    /** How many times the step was rebuilt, and how many times only its text was replaced; for tests. */
    internal var draws = 0
        private set
    internal var textUpdates = 0
        private set

    private fun draw() {
        val step = step ?: return
        val open = isOpen
        val html = if (open) step.detailHtml(resolved) else null
        val structure = listOf(open, step.verb, step.target, step.running, step.openTitle, step.isCommand)
        if (structure == shownStructure) {
            // Only the detail grew, such as a command's output: replace the text and leave the rest alone.
            if (open && html != null && html != shownHtml) {
                shownHtml = html
                pane.text = html
                textUpdates++
            }
            return
        }
        shownStructure = structure
        shownHtml = html
        draws++
        panel.removeAll()
        val line = JPanel(BorderLayout()).apply { isOpaque = false }
        val text = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            add(JBLabel(if (open) step.openTitle else step.verb).apply {
                foreground = if (open) UIUtil.getLabelForeground() else UIUtil.getContextHelpForeground()
                font = if (open) font.deriveFont(java.awt.Font.BOLD) else font
            })
            if (!open && step.target.isNotEmpty()) add(JBLabel("  " + step.target).apply { font = JBUI.Fonts.label().deriveFont(java.awt.Font.BOLD) })
            if (step.running) add(JBLabel(AnimatedIcon.Default()).apply { border = JBUI.Borders.emptyLeft(6) })
            add(JBLabel(if (open) AllIcons.General.ArrowDown else AllIcons.General.ArrowRight).apply { border = JBUI.Borders.emptyLeft(2) })
        }
        line.add(text, BorderLayout.CENTER)
        clickable(line) { toggle() }
        clickable(text) { toggle() }
        panel.add(line, BorderLayout.NORTH)
        if (open && html != null) {
            if (pane.text != html) pane.text = html
            if (step.isCommand) {
                terminal.removeAll()
                terminal.add(pane, BorderLayout.CENTER)
                panel.add(JPanel(BorderLayout()).apply { isOpaque = false; border = JBUI.Borders.emptyTop(4); add(terminal, BorderLayout.CENTER) }, BorderLayout.CENTER)
            } else {
                panel.add(pane, BorderLayout.CENTER)
            }
        }
        panel.revalidate()
        panel.repaint()
    }

    internal val html: String get() = pane.text.orEmpty()

    internal val summary: String get() = step?.let { if (isOpen) it.openTitle else (it.verb + " " + it.target).trim() } ?: ""
}
