package bot.cline.jetbrains.ide

import bot.cline.jetbrains.bridge.CoreApi
import bot.cline.jetbrains.bridge.Subscription
import bot.cline.jetbrains.chat.ChangedFile
import bot.cline.jetbrains.chat.ChatStore
import com.google.gson.JsonParser
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import java.util.concurrent.CompletableFuture

class ChangedFilesPanelTest : BasePlatformTestCase() {
    private class FakeCore : CoreApi {
        val calls = mutableListOf<Triple<String, String, String>>()
        var files = """{"files":[
            {"path":"/ws/a.kt","relativePath":"a.kt","addedLines":3,"removedLines":1},
            {"path":"/ws/new.kt","relativePath":"new.kt","status":"CHANGED_FILE_ADDED","addedLines":9},
            {"path":"/ws/gone.kt","relativePath":"gone.kt","status":"CHANGED_FILE_DELETED","removedLines":5}]}"""

        override fun call(service: String, method: String, messageJson: String): CompletableFuture<String> {
            calls += Triple(service, method, messageJson)
            if (method == "checkpointRevertFile") files = """{"files":[]}"""
            return CompletableFuture.completedFuture(if (method == "checkpointLatestChangedFiles") files else "{}")
        }

        override fun subscribe(service: String, method: String, messageJson: String, onMessage: (String) -> Unit, onClose: (Throwable?) -> Unit) =
            Subscription {}
    }

    private fun panel(core: FakeCore, confirm: (ChangedFile) -> Boolean = { true }) =
        ChangedFilesPanel(project, { ChatStore(core) }, confirm)

    private fun settle(panel: ChangedFilesPanel) {
        repeat(20) { UIUtil.dispatchAllInvocationEvents(); Thread.sleep(20) }
    }

    fun `test the panel is hidden until there are changed files`() {
        val core = FakeCore().also { it.files = """{"files":[]}""" }
        val panel = panel(core)
        assertFalse(panel.isVisible)
        panel.refresh(); settle(panel)
        assertFalse(panel.isVisible)
    }

    fun `test the list shows every file and is visible`() {
        val panel = panel(FakeCore())
        panel.refresh(); settle(panel)
        assertTrue(panel.isVisible)
        assertEquals(listOf("a.kt", "new.kt", "gone.kt"), panel.files.map { it.relativePath })
        assertEquals(listOf('M', 'A', 'D'), panel.files.map { it.status.letter })
    }

    fun `test diff asks the core to open that file`() {
        val core = FakeCore()
        val panel = panel(core)
        panel.refresh(); settle(panel)
        panel.select("/ws/a.kt")
        panel.viewSelected()
        val call = core.calls.last()
        assertEquals("checkpointViewFileChanges", call.second)
        assertEquals("/ws/a.kt", JsonParser.parseString(call.third).asJsonObject.get("value").asString)
    }

    fun `test revert reverts the file after confirmation and refreshes the list`() {
        val core = FakeCore()
        val asked = mutableListOf<ChangedFile>()
        val panel = panel(core) { asked += it; true }
        panel.refresh(); settle(panel)
        panel.select("/ws/new.kt")
        panel.revertSelected(); settle(panel)
        assertEquals(listOf("/ws/new.kt"), asked.map { it.path })
        assertTrue(core.calls.any { it.second == "checkpointRevertFile" && it.third.contains("/ws/new.kt") })
        assertEquals(emptyList<ChangedFile>(), panel.files)
        assertFalse(panel.isVisible)
    }

    fun `test declining the confirmation reverts nothing`() {
        val core = FakeCore()
        val panel = panel(core) { false }
        panel.refresh(); settle(panel)
        panel.select("/ws/a.kt")
        panel.revertSelected(); settle(panel)
        assertTrue(core.calls.none { it.second == "checkpointRevertFile" })
        assertEquals(3, panel.files.size)
    }

    fun `test the warning says what happens to a created file and a modified file`() {
        val created = ChangedFile("/ws/n.kt", "n.kt", ChangedFile.Status.ADDED, 1, 0)
        val modified = ChangedFile("/ws/a.kt", "a.kt", ChangedFile.Status.MODIFIED, 1, 1)
        assertTrue(ChangedFilesPanel.revertWarning(created).contains("delete n.kt"))
        assertTrue(ChangedFilesPanel.revertWarning(modified).contains("restore a.kt"))
        assertTrue(ChangedFilesPanel.revertWarning(modified).contains("Cline is not told"))
    }
}
