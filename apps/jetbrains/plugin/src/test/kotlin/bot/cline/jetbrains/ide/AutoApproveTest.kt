package bot.cline.jetbrains.ide

import bot.cline.jetbrains.bridge.CoreApi
import bot.cline.jetbrains.bridge.Subscription
import bot.cline.jetbrains.chat.ChatStore
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import java.util.concurrent.CompletableFuture

class AutoApproveTest : BasePlatformTestCase() {
    private class Core : CoreApi {
        val calls = mutableListOf<Triple<String, String, String>>()
        private val state = Gson().toJson(mapOf("stateJson" to """{"mode":"act","epoch":1,"stateVersion":1,"clineMessages":[],"autoApprovalSettings":{"version":3,"actions":{"readFiles":true,"readFilesExternally":true,"editFiles":true,"editFilesExternally":false,"executeSafeCommands":false,"executeAllCommands":true,"useBrowser":true,"useMcp":true},"enableNotifications":false}}"""))

        override fun call(service: String, method: String, messageJson: String): CompletableFuture<String> {
            calls += Triple(service, method, messageJson)
            return CompletableFuture.completedFuture(if (method == "getLatestState") state else "{}")
        }

        override fun subscribe(service: String, method: String, messageJson: String, onMessage: (String) -> Unit, onClose: (Throwable?) -> Unit) = Subscription {}
    }

    private val core = Core()
    private fun store() = ChatStore(core).also { it.start().get() }
    private fun settle() = repeat(10) { UIUtil.dispatchAllInvocationEvents(); Thread.sleep(20) }

    fun `test the bar summarizes what is allowed and hides without a connection`() {
        val bar = AutoApproveBar { null }
        bar.refresh()
        assertFalse(bar.isVisible)
        val store = store()
        val connected = AutoApproveBar { store }
        connected.refresh()
        assertTrue(connected.isVisible)
        assertEquals("Read, Edit, Web Fetch, MCP", connected.summaryText)
    }

    fun `test the panel shows the checkboxes and an extra needs the action it widens`() {
        val panel = AutoApprovePanel(store())
        val checked = panel.checked()
        assertTrue(checked.getValue("readFiles") && checked.getValue("editFiles") && !checked.getValue("executeSafeCommands"))
        assertFalse(checked.getValue("editFilesExternally"))
        val enabled = panel.enabled()
        assertTrue(enabled.getValue("readFilesExternally"))
        assertFalse("run all commands needs run safe commands: $enabled", enabled.getValue("executeAllCommands"))
    }

    fun `test a click sends every action with the next version`() {
        val panel = AutoApprovePanel(store())
        panel.click("executeSafeCommands"); settle()
        val update = core.calls.last { it.second == "updateAutoApprovalSettings" }
        val body = JsonParser.parseString(update.third).asJsonObject
        assertEquals(4, body.get("version").asInt)
        assertTrue(body.getAsJsonObject("actions").get("executeSafeCommands").asBoolean)
        assertEquals(8, body.getAsJsonObject("actions").size())
    }

    fun `test snapshot of the footer`() {
        val store = ChatStore(Core()).also { it.start().get() }
        val bar = AutoApproveBar { store }
        bar.refresh()
        val composer = Composer(project, store = { store }, onSubmit = { _, _, _ -> }, clipboardImage = { null })
        composer.update(true)
        repeat(10) { UIUtil.dispatchAllInvocationEvents(); Thread.sleep(20) }
        composer.update(true)
        val footer = javax.swing.JPanel(java.awt.BorderLayout()).apply { isOpaque = true; background = ChatColors.page; add(bar, java.awt.BorderLayout.NORTH); add(composer, java.awt.BorderLayout.CENTER) }
        Snapshots.save(footer, "footer", 520)
        Snapshots.save(footer, "footer-narrow", 330)
    }

    fun `test the title bar has a new chat and a history button with icons`() {
        val new = NewChatTitleAction()
        val history = HistoryTitleAction()
        assertNotNull(new.templatePresentation.icon)
        assertNotNull(history.templatePresentation.icon)
        assertEquals("New Chat", new.templatePresentation.text)
        assertEquals("Task History", history.templatePresentation.text)
        assertTrue(new is TitleAction && history is TitleAction)
    }
}

class SegmentedToggleTest : BasePlatformTestCase() {
    fun `test choosing an option reports it and showing one does not`() {
        val chosen = mutableListOf<String>()
        val toggle = SegmentedToggle(listOf("Plan", "Act"), accent = { java.awt.Color.ORANGE }, initial = "Act", onSelect = { chosen += it })
        toggle.show("Plan")
        assertEquals("Plan", toggle.selected)
        assertTrue(chosen.isEmpty())
        toggle.choose("Act")
        assertEquals(listOf("Act"), chosen)
        toggle.choose("Other")
        assertEquals("Act", toggle.selected)
    }

    fun `test a click on a segment chooses it`() {
        val chosen = mutableListOf<String>()
        val toggle = SegmentedToggle(listOf("Plan", "Act"), accent = { java.awt.Color.ORANGE }, initial = "Act", onSelect = { chosen += it })
        toggle.setSize(toggle.preferredSize)
        val half = toggle.preferredSize.width / 2
        fun click(x: Int) = toggle.mouseListeners.forEach { it.mouseClicked(java.awt.event.MouseEvent(toggle, java.awt.event.MouseEvent.MOUSE_CLICKED, 0, 0, x, 5, 1, false)) }
        click(5)
        click(half + 5)
        click(half + 6)
        assertEquals(listOf("Plan", "Act"), chosen)
    }

    fun `test a disabled switch ignores clicks`() {
        val chosen = mutableListOf<String>()
        val toggle = SegmentedToggle(listOf("Plan", "Act"), accent = { java.awt.Color.ORANGE }, initial = "Act", onSelect = { chosen += it })
        toggle.isEnabled = false
        toggle.mouseListeners.forEach { it.mouseClicked(java.awt.event.MouseEvent(toggle, java.awt.event.MouseEvent.MOUSE_CLICKED, 0, 0, 3, 5, 1, false)) }
        assertTrue(chosen.isEmpty())
    }
}
