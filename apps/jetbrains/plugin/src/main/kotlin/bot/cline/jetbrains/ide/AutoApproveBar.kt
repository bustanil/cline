package bot.cline.jetbrains.ide

import bot.cline.jetbrains.chat.ApprovalAction
import bot.cline.jetbrains.chat.AutoApproval
import bot.cline.jetbrains.chat.ChatStore
import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.Point
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

/** The checkboxes for what the agent may do without asking. A change goes to the core at once. */
class AutoApprovePanel(private val store: ChatStore, private val onError: (String) -> Unit = {}) : JPanel(BorderLayout()) {
    private val boxes = LinkedHashMap<String, JBCheckBox>()

    init {
        isOpaque = false
        border = JBUI.Borders.empty(8, 10)
        val column = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(JBLabel("Let Cline do these without asking").apply { foreground = UIUtil.getContextHelpForeground(); border = JBUI.Borders.emptyBottom(6) })
            for (action in AutoApproval.ACTIONS) {
                val box = JBCheckBox(action.label).apply {
                    isOpaque = false
                    border = JBUI.Borders.empty(2, if (action.parent == null) 0 else 22, 2, 0)
                    addActionListener { change(action, isSelected) }
                }
                boxes[action.id] = box
                add(box)
            }
        }
        add(column, BorderLayout.CENTER)
        refresh()
    }

    /** Matches the boxes to what the core has now. An extra only applies when the action it widens is on. */
    fun refresh() {
        val settings = store.autoApproval
        for (action in AutoApproval.ACTIONS) {
            val box = boxes.getValue(action.id)
            box.isSelected = settings?.isOn(action.id) == true
            box.isEnabled = action.parent.let { parent -> parent == null || settings?.isOn(parent) == true }
        }
    }

    private fun change(action: ApprovalAction, on: Boolean) {
        store.setAutoApproval(action.id, on).whenComplete { _, error ->
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater({
                if (error != null) onError("Could not change it: " + (error.cause?.message ?: error.message))
                refresh()
            }, com.intellij.openapi.application.ModalityState.any())
        }
        // Switching an action off also switches off what widens it; the box shows that at once.
        if (!on) AutoApproval.ACTIONS.filter { it.parent == action.id }.forEach { boxes.getValue(it.id).isEnabled = false }
        else AutoApproval.ACTIONS.filter { it.parent == action.id }.forEach { boxes.getValue(it.id).isEnabled = true }
    }

    internal fun checked(): Map<String, Boolean> = boxes.mapValues { it.value.isSelected }

    internal fun enabled(): Map<String, Boolean> = boxes.mapValues { it.value.isEnabled }

    internal fun click(id: String) = boxes.getValue(id).doClick()
}

/**
 * The line above the composer: "Auto-approve: Read, Edit, Web Fetch, MCP". Click it to change what is allowed.
 */
class AutoApproveBar(private val store: () -> ChatStore?) : JPanel(BorderLayout()) {
    private val title = JBLabel("Auto-approve:").apply { foreground = UIUtil.getContextHelpForeground() }
    private val summary = JBLabel("None").apply { minimumSize = java.awt.Dimension(0, minimumSize.height) }
    private val chevron = JBLabel(AllIcons.General.ArrowRight)
    private var popup: JBPopup? = null

    init {
        isOpaque = false
        border = JBUI.Borders.empty(0, 10)
        val card = CardPanel(ChatColors.card, ChatColors.border, 10).apply {
            border = JBUI.Borders.empty(6, 12)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            // A border layout, so a long summary is cut with an ellipsis instead of dropping out of sight.
            add(JPanel(BorderLayout(JBUI.scale(6), 0)).apply { isOpaque = false; add(title, BorderLayout.WEST); add(summary, BorderLayout.CENTER) }, BorderLayout.CENTER)
            add(chevron, BorderLayout.EAST)
        }
        add(card, BorderLayout.CENTER)
        val open = object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = toggle()
        }
        listOf<JComponent>(card, title, summary, chevron).forEach { it.addMouseListener(open) }
        isVisible = false
    }

    /** Shows what the core allows now; hidden while there is no connection. */
    fun refresh() {
        val current = store()
        isVisible = current != null
        summary.text = AutoApproval.summary(current?.autoApproval)
    }

    internal val summaryText: String get() = summary.text.orEmpty()

    private fun toggle() {
        popup?.let { if (it.isVisible) return it.cancel() }
        val current = store() ?: return
        val panel = AutoApprovePanel(current, onError = { summary.text = it })
        val created = JBPopupFactory.getInstance().createComponentPopupBuilder(panel, panel)
            .setRequestFocus(true).setFocusable(true).setCancelOnClickOutside(true).setResizable(false).setMovable(false)
            .setCancelCallback { refresh(); true }
            .createPopup()
        popup = created
        val size = panel.preferredSize
        created.show(RelativePoint(this, Point(JBUI.scale(10), -size.height - JBUI.scale(2))))
    }
}
