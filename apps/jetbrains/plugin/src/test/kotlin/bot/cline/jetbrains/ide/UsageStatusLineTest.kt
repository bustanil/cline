package bot.cline.jetbrains.ide

import bot.cline.jetbrains.chat.ContextUsage
import bot.cline.jetbrains.chat.UsageLevel
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class UsageStatusLineTest : BasePlatformTestCase() {
    fun `test the line is hidden until there is something to show`() {
        val line = UsageStatusLine()
        line.update("", null)
        assertFalse(line.isVisible)
        line.update("$0.0409 · 223.6k in · 2.8k out", null)
        assertTrue(line.isVisible)
        assertEquals("$0.0409 · 223.6k in · 2.8k out", line.metricsText)
        assertNull(line.shownUsage)
    }

    fun `test the bar shows the usage and its tooltip has the numbers`() {
        val line = UsageStatusLine()
        line.update("", ContextUsage(223_600, 1_000_000))
        assertEquals(22, line.shownUsage?.percent)
        val bar = line.components.filterIsInstance<UsageBar>().single()
        assertEquals("Context: 223.6k of 1M tokens (22%)", bar.toolTipText)
    }

    fun `test the bar color is green under half yellow to eighty and red above`() {
        val line = UsageStatusLine()
        fun colorAt(used: Long): java.awt.Color { line.update("x", ContextUsage(used, 100)); return line.barColor()!! }
        val green = colorAt(49)
        val yellow = colorAt(50)
        val stillYellow = colorAt(80)
        val red = colorAt(81)
        assertEquals(green, UsageBar.colorFor(UsageLevel.LOW))
        assertEquals(yellow, UsageBar.colorFor(UsageLevel.MEDIUM))
        assertEquals(stillYellow, yellow)
        assertEquals(red, UsageBar.colorFor(UsageLevel.HIGH))
        assertEquals(3, setOf(green, yellow, red).size)
        // Green is the greener of the three, red the redder, so the colors mean what they say.
        assertTrue(green.green > green.red && red.red > red.green)
    }

    fun `test the bar is small`() {
        val size = UsageBar().preferredSize
        assertTrue("$size", size.width <= com.intellij.util.ui.JBUI.scale(48) && size.height <= com.intellij.util.ui.JBUI.scale(10))
    }

    fun `test snapshots of the three levels`() {
        val panel = javax.swing.JPanel().apply { layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS); isOpaque = true; background = ChatColors.page }
        for ((used, text) in listOf(22_000L to "$0.0409 · 223.6k in · 2.8k out", 640_000L to "$0.2210 · 1.4M in · 21k out", 910_000L to "$0.5120 · 3.2M in · 40k out")) {
            val line = UsageStatusLine()
            line.update(text, ContextUsage(used, 1_000_000))
            panel.add(line)
        }
        Snapshots.save(panel, "usage-status", 480)
    }
}
