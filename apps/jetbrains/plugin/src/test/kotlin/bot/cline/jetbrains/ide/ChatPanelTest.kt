package bot.cline.jetbrains.ide

import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import java.awt.Component
import java.awt.Container
import javax.swing.JButton
import javax.swing.JLabel

class ChatPanelTest : BasePlatformTestCase() {
    private fun <T : Component> Container.all(type: Class<T>): List<T> =
        components.flatMap { c ->
            (if (type.isInstance(c)) listOf(type.cast(c)) else emptyList()) + ((c as? Container)?.all(type) ?: emptyList())
        }

    private fun pump(timeoutMs: Long = 90_000, until: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            UIUtil.dispatchAllInvocationEvents()
            if (until()) return true
            Thread.sleep(100)
        }
        return false
    }

    override fun tearDown() {
        try {
            ClineProjectService.getInstance(project).dispose()
            val s = ClineSettings.getInstance().state
            s.nodePath = ""; s.corePath = ""; s.dataDir = ""
        } finally {
            super.tearDown()
        }
    }

    fun `test the panel explains a missing core`() {
        val s = ClineSettings.getInstance().state
        s.nodePath = ""
        s.corePath = "/no/such/cline-core.js"
        val panel = ChatPanel(project)
        pump(5_000) { ClineProjectService.getInstance(project).status == ClineProjectService.Status.MISSING_CORE }
        UIUtil.dispatchAllInvocationEvents()
        val labels = panel.all(JLabel::class.java).map { it.text.orEmpty() }
        assertTrue(labels.toString(), labels.any { it.contains("cline-core.js was not found") })
        assertFalse(panel.all(JButton::class.java).first { it.text == "Send" }.isEnabled)
        panel.dispose()
    }

    fun `test a task on the real core shows rows and the retry button`() {
        val coreJs = System.getenv("CLINE_CORE_JS")
        if (coreJs == null) {
            println("SKIPPED: CLINE_CORE_JS is not set")
            return
        }
        val s = ClineSettings.getInstance().state
        s.corePath = coreJs
        s.nodePath = CoreLocator.node(s) ?: error("no node")
        s.dataDir = FileUtil.createTempDirectory("cline-data", null).path
        val service = ClineProjectService.getInstance(project)
        val panel = ChatPanel(project)
        assertTrue("core never connected: ${service.status}", pump { service.status == ClineProjectService.Status.CONNECTED && service.store != null })
        service.store!!.send("say hello").get()
        val shown = pump { panel.all(JButton::class.java).any { it.text == "Retry" && it.isVisible } }
        assertTrue("no Retry button; buttons=${panel.all(JButton::class.java).map { it.text + ":" + it.isVisible }}", shown)
        val rows = panel.renderedHtml()
        println("ROWS: " + rows.map { it.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim().take(60) })
        assertTrue(rows.toString(), rows.any { it.contains("Task") && it.contains("say hello") })
        assertTrue("the failure should show as an error row: $rows", rows.any { it.contains("Error") })
        panel.dispose()
    }
}

