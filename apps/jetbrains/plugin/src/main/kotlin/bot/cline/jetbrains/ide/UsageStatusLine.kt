package bot.cline.jetbrains.ide

import bot.cline.jetbrains.chat.ContextUsage
import bot.cline.jetbrains.chat.UsageLevel
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JComponent
import javax.swing.JPanel

/** A slim bar that shows how full the context window is. Quiet colors: it should be noticed only when it matters. */
class UsageBar : JComponent() {
    var usage: ContextUsage? = null
        set(value) {
            field = value
            toolTipText = value?.let { "Context: " + it.describe() }
            repaint()
        }

    init {
        isOpaque = false
    }

    override fun getPreferredSize(): Dimension = Dimension(JBUI.scale(44), JBUI.scale(8))

    override fun getMinimumSize(): Dimension = preferredSize

    override fun getMaximumSize(): Dimension = preferredSize

    override fun paintComponent(g: Graphics) {
        val u = usage ?: return
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val h = JBUI.scale(4)
            val y = (height - h) / 2
            g2.color = TRACK
            g2.fillRoundRect(0, y, width, h, h, h)
            // At least a sliver, so a small amount still shows; never past the end.
            val filled = (width * u.fraction.coerceIn(0.0, 1.0)).toInt().coerceAtLeast(if (u.usedTokens > 0) JBUI.scale(2) else 0)
            g2.color = colorFor(u.level)
            g2.fillRoundRect(0, y, filled.coerceAtMost(width), h, h, h)
        } finally {
            g2.dispose()
        }
    }

    companion object {
        private val TRACK = JBColor(Color(0xE8, 0xE6, 0xE3), Color(0x44, 0x43, 0x3F))
        private val GREEN = JBColor(Color(0x6F, 0xB5, 0x8A), Color(0x5F, 0xA3, 0x7A))
        private val YELLOW = JBColor(Color(0xDD, 0xB8, 0x5A), Color(0xC9, 0xA4, 0x4E))
        private val RED = JBColor(Color(0xD9, 0x7B, 0x74), Color(0xC9, 0x6A, 0x63))

        /** Soft versions of green, yellow and red. */
        fun colorFor(level: UsageLevel): Color = when (level) {
            UsageLevel.LOW -> GREEN
            UsageLevel.MEDIUM -> YELLOW
            UsageLevel.HIGH -> RED
        }
    }
}

/**
 * The line under the composer, right aligned: the context bar, then what the task has cost, such as
 * `$0.0409 · 223.6k in · 2.8k out`. Small and muted; the details are in the tooltip.
 */
class UsageStatusLine : JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), 0)) {
    private val bar = UsageBar()
    private val label = JBLabel().apply {
        font = JBUI.Fonts.smallFont()
        foreground = UIUtil.getContextHelpForeground()
    }

    init {
        isOpaque = false
        border = JBUI.Borders.empty(0, 10, 6, 10)
        add(bar)
        add(label)
        isVisible = false
    }

    /** Shows the cost text and the context bar. The line is hidden while there is nothing to show. */
    fun update(metrics: String, usage: ContextUsage?) {
        bar.usage = usage
        bar.isVisible = usage != null
        label.text = metrics
        label.isVisible = metrics.isNotEmpty()
        isVisible = metrics.isNotEmpty() || usage != null
        revalidate()
        repaint()
    }

    internal val metricsText: String get() = label.text.orEmpty()

    internal val shownUsage: ContextUsage? get() = bar.usage.takeIf { bar.isVisible }

    internal fun barColor(): Color? = bar.usage?.let { UsageBar.colorFor(it.level) }
}
