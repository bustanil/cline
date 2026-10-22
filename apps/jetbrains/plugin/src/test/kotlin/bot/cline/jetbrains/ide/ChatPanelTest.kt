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
        assertFalse(panel.all(PaperPlaneButton::class.java).single().isEnabled)
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
        assertTrue(rows.toString(), rows.any { it.contains("say hello") })
        assertTrue("the failure should show as an error row: $rows", rows.any { it.contains("Error") })
        // Plan/Act is a switch at the bottom of the composer and follows the core.
        val toggle = panel.all(SegmentedToggle::class.java).single()
        assertEquals("Act", toggle.selected)
        toggle.choose("Plan")
        assertTrue("mode never switched to plan", pump(30_000) { service.store?.mode == "plan" })
        toggle.choose("Act")
        assertTrue("mode never switched back to act", pump(30_000) { service.store?.mode == "act" && toggle.selected == "Act" })
        // The old toolbar buttons are gone; New chat and History are in the window's title bar.
        assertTrue(panel.all(JButton::class.java).none { it.text == "History" || it.text == "New chat" })
        val modelPanel = ModelPanel(service.store!!)
        assertTrue("providers never loaded", pump(30_000) { modelPanel.chosenProvider != null })
        modelPanel.chooseProvider("anthropic")
        modelPanel.chooseModel("claude-sonnet-5")
        modelPanel.apply().get(30, java.util.concurrent.TimeUnit.SECONDS)
        assertTrue("the model label never showed the new model", pump(30_000) { panel.all(PillButton::class.java).any { it.text == "anthropic:claude-sonnet-5" } })
        // Leave a provider with no key behind, as the core's defaults expect.
        service.store!!.models.select("openrouter", "act", "anthropic/claude-sonnet-5.5").get(30, java.util.concurrent.TimeUnit.SECONDS)
        panel.dispose()
    }
}


class CoreLocatorTest : BasePlatformTestCase() {
    fun `test platform names match the folders under binaries`() {
        assertEquals("darwin-arm64", CoreLocator.platformTarget("Mac OS X", "aarch64"))
        assertEquals("darwin-x64", CoreLocator.platformTarget("Mac OS X", "x86_64"))
        assertEquals("linux-x64", CoreLocator.platformTarget("Linux", "amd64"))
        assertEquals("linux-aarch64", CoreLocator.platformTarget("Linux", "aarch64"))
        assertEquals("win-x64", CoreLocator.platformTarget("Windows 11", "amd64"))
        assertNull(CoreLocator.platformTarget("Linux", "riscv64"))
        assertNull(CoreLocator.platformTarget("FreeBSD", "amd64"))
    }

    fun `test node versions parse`() {
        assertEquals(22, CoreLocator.parseNodeMajor("v22.15.0\n"))
        assertEquals(24, CoreLocator.parseNodeMajor("24.16.0"))
        assertNull(CoreLocator.parseNodeMajor("not node"))
    }

    fun `test NODE_PATH puts the platform's native modules first and keeps the existing path`() {
        val dir = FileUtil.createTempDirectory("core", null)
        val core = java.io.File(dir, "cline-core.js").also { it.writeText("") }
        java.io.File(dir, "binaries/${CoreLocator.platformTarget()}/node_modules/better-sqlite3").mkdirs()
        val env = CoreLocator.environmentFor(core, "/existing")
        val parts = env.getValue("NODE_PATH").split(java.io.File.pathSeparator)
        assertEquals(java.io.File(dir, "binaries/${CoreLocator.platformTarget()}/node_modules").path, parts[0])
        assertEquals("/existing", parts[1])
        assertTrue(CoreLocator.environmentFor(java.io.File(FileUtil.createTempDirectory("bare", null), "cline-core.js")).isEmpty())
    }

    fun `test a core that already finds its native modules gets no NODE_PATH`() {
        val dir = FileUtil.createTempDirectory("checkout", null)
        java.io.File(dir, "node_modules/better-sqlite3").mkdirs()
        val core = java.io.File(dir, "dist/cline-core.js").also { it.parentFile.mkdirs(); it.writeText("") }
        java.io.File(core.parentFile, "binaries/${CoreLocator.platformTarget()}/node_modules/better-sqlite3").mkdirs()
        assertTrue(CoreLocator.hasInstalledNativeModules(core))
        assertTrue(CoreLocator.environmentFor(core).isEmpty())
    }

    fun `test the core in the plugin is found with its manifest and native modules`() {
        val bundled = CoreLocator.bundledCoreJs()
        if (bundled == null) {
            println("SKIPPED: no core is bundled in this build")
            return
        }
        println("BUNDLED: " + bundled.path)
        assertTrue(java.io.File(bundled.parentFile, "extension/package.json").isFile)
        assertNotNull("native modules for this platform", CoreLocator.nativeModules(bundled))
        assertTrue(CoreLocator.environmentFor(bundled).containsKey("NODE_PATH"))
    }

    fun `test the bundled core starts when Node 22 is available and otherwise the service says which Node it needs`() {
        val settings = ClineSettings.getInstance().state
        val bundled = CoreLocator.bundledCoreJs()
        if (bundled == null) {
            println("SKIPPED: no core is bundled in this build")
            return
        }
        settings.nodePath = ""
        settings.corePath = bundled.path
        val node = CoreLocator.node(settings) ?: error("no node")
        val major = CoreLocator.nodeMajor(node)
        settings.dataDir = FileUtil.createTempDirectory("cline-data", null).path
        val service = ClineProjectService.getInstance(project)
        try {
            service.start()
            if (major == CoreLocator.REQUIRED_NODE_MAJOR) {
                val end = System.currentTimeMillis() + 90_000
                while (System.currentTimeMillis() < end && service.status != ClineProjectService.Status.CONNECTED) {
                    UIUtil.dispatchAllInvocationEvents(); Thread.sleep(100)
                }
                assertEquals(ClineProjectService.Status.CONNECTED, service.status)
                println("BUNDLED STARTED on node $major")
            } else {
                assertEquals(ClineProjectService.Status.WRONG_NODE, service.status)
                assertTrue(service.nodeProblem, service.nodeProblem!!.contains("needs Node 22"))
                println("WRONG NODE reported: " + service.nodeProblem)
            }
        } finally {
            service.dispose()
            settings.dataDir = ""
            settings.corePath = ""
        }
    }
}
