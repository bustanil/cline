package bot.cline.jetbrains.ide

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent

/**
 * A two-way switch drawn as a rounded pill: the chosen option is filled with its own color, as the Plan and
 * Act switch is in the VS Code client. It is not a `JToggleButton`, for the same reason [PillButton] is not a `JButton`.
 */
class SegmentedToggle(
    private val options: List<String>,
    private val accent: (String) -> Color,
    initial: String = options.first(),
    private val onSelect: (String) -> Unit = {},
) : JComponent() {
    var selected: String = initial
        private set

    init {
        isOpaque = false
        font = JBUI.Fonts.label().deriveFont(java.awt.Font.BOLD, JBUI.Fonts.label().size2D - 0.5f)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (!isEnabled) return
                val option = optionAt(e.x) ?: return
                if (option != selected) choose(option)
            }
        })
    }

    private fun segmentWidth(): Int {
        val metrics = getFontMetrics(font)
        return options.maxOf { metrics.stringWidth(it) } + JBUI.scale(22)
    }

    private fun optionAt(x: Int): String? = options.getOrNull((x / segmentWidth()).coerceIn(0, options.size - 1))

    /** Shows [option] as chosen without telling [onSelect]; the core is the source of truth. */
    fun show(option: String) {
        if (option in options && option != selected) {
            selected = option
            repaint()
        }
    }

    /** The user chose [option]. */
    fun choose(option: String) {
        if (option !in options) return
        selected = option
        repaint()
        onSelect(option)
    }

    override fun getPreferredSize(): Dimension = Dimension(segmentWidth() * options.size, getFontMetrics(font).height + JBUI.scale(10))

    override fun getMinimumSize(): Dimension = preferredSize

    override fun getMaximumSize(): Dimension = preferredSize

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            val arc = height
            g2.color = ChatColors.card
            g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
            val w = segmentWidth()
            g2.font = font
            val metrics = g2.fontMetrics
            options.forEachIndexed { i, option ->
                val chosen = option == selected
                if (chosen) {
                    g2.color = accent(option)
                    // The chosen segment is a rounded pill inside the outline.
                    g2.fillRoundRect(i * w, 0, w, height - 1, arc, arc)
                }
                g2.color = if (chosen) Color.WHITE else if (isEnabled) UIUtil.getLabelForeground() else UIUtil.getContextHelpForeground()
                g2.drawString(option, i * w + (w - metrics.stringWidth(option)) / 2, (height - metrics.height) / 2 + metrics.ascent)
            }
            g2.color = ChatColors.border
            g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
        } finally {
            g2.dispose()
        }
    }
}

/** A paper plane, for sending. Drawn, so no font or icon set has to carry the glyph. */
class PaperPlaneButton(private val onClick: () -> Unit) : JComponent() {
    private var hover = false

    init {
        isOpaque = false
        isFocusable = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        toolTipText = "Send"
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) { if (isEnabled) onClick() }
            override fun mouseEntered(e: MouseEvent) { hover = true; repaint() }
            override fun mouseExited(e: MouseEvent) { hover = false; repaint() }
        })
    }

    fun click() {
        if (isEnabled) onClick()
    }

    override fun getPreferredSize(): Dimension = Dimension(JBUI.scale(30), JBUI.scale(30))

    override fun getMinimumSize(): Dimension = preferredSize

    override fun getMaximumSize(): Dimension = preferredSize

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            if (hover && isEnabled) {
                g2.color = Color(0, 0, 0, 20)
                g2.fillRoundRect(0, 0, width, height, JBUI.scale(8), JBUI.scale(8))
            }
            g2.color = if (isEnabled) UIUtil.getLabelForeground() else JBColor(0xC4C4C4, 0x5A5A5A)
            g2.stroke = java.awt.BasicStroke(JBUI.scale(1.4f), java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND)
            val x = width * 0.2f
            val y = height * 0.2f
            val w = width * 0.6f
            val h = height * 0.6f
            // The plane: a triangle pointing right with the fold line to its middle.
            val path = java.awt.geom.Path2D.Float()
            path.moveTo(x, y)
            path.lineTo(x + w, y + h / 2)
            path.lineTo(x, y + h)
            path.lineTo(x + w * 0.2f, y + h / 2)
            path.closePath()
            g2.draw(path)
            g2.draw(java.awt.geom.Line2D.Float(x + w * 0.2f, y + h / 2, x + w * 0.62f, y + h / 2))
        } finally {
            g2.dispose()
        }
    }
}
