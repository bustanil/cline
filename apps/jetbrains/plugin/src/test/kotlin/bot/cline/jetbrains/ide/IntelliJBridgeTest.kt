package bot.cline.jetbrains.ide

import bot.cline.jetbrains.bridge.FileDiff
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.diff.contents.DocumentContent
import com.intellij.diff.contents.FileContent
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

class IntelliJBridgeTest : BasePlatformTestCase() {
    private val text = (1..30).joinToString("\n") { "line $it" } + "\n"

    private fun bridge() = IntelliJBridge(project)

    /** The bridge opens real paths, so the tests use files on disk, not the light fixture's in-memory ones. */
    private fun diskFile(name: String, content: String): VirtualFile {
        val dir = FileUtil.createTempDirectory("cline-bridge", null)
        val file = File(dir, name).also { it.writeText(content) }
        return LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)!!
    }

    fun `test showTextDocument selects the requested lines`() {
        val file = diskFile("A.txt", text)
        bridge().showTextDocument(file.path, 12, 20)
        val editor = FileEditorManager.getInstance(project).selectedTextEditor!!
        assertEquals(file, FileEditorManager.getInstance(project).selectedFiles.single())
        assertEquals("line 12\nline 13\nline 14\nline 15\nline 16\nline 17\nline 18\nline 19\nline 20", editor.selectionModel.selectedText)
        assertEquals(11, editor.caretModel.logicalPosition.line)
    }

    fun `test a single line and an out of range line are handled`() {
        val file = diskFile("B.txt", text)
        bridge().showTextDocument(file.path, 7, null)
        assertEquals("line 7", FileEditorManager.getInstance(project).selectedTextEditor!!.selectionModel.selectedText)
        bridge().showTextDocument(file.path, 500, 900)
        assertEquals("", FileEditorManager.getInstance(project).selectedTextEditor!!.selectionModel.selectedText.orEmpty())
    }

    fun `test showTextDocument without lines just opens the file`() {
        val file = diskFile("C.txt", text)
        bridge().showTextDocument(file.path)
        assertTrue(file in FileEditorManager.getInstance(project).openFiles)
        assertEquals(listOf(file.path), bridge().openTabs())
        assertEquals(file.path, bridge().activeEditor())
    }

    fun `test a missing file is reported`() {
        try {
            bridge().showTextDocument("/no/such/file.txt", 1, 2)
            fail("expected an error")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("/no/such/file.txt"))
        }
    }

    fun `test the project directory comes first in the workspace paths`() {
        val paths = bridge().workspacePaths()
        val base = project.basePath
        assertNotNull(base)
        // The core takes the first path as the workspace root; the module content roots come after it.
        assertEquals(base, paths.first())
        assertEquals(paths.distinct(), paths)
        paths.drop(1).forEach { assertTrue(it, it.startsWith(base!!) || !it.startsWith(base)) }
    }

    fun `test the diff chain has one request per file`() {
        val onDisk = diskFile("D.txt", "new\n")
        val chain = bridge().buildDiffChain(
            listOf(FileDiff(onDisk.path, "old\n", "new\n"), FileDiff("/gone/E.txt", "", "created\n"), FileDiff(onDisk.path, "x\n", "edited since\n")),
        )
        val requests = chain.requests.map { (it as com.intellij.diff.chains.SimpleDiffRequestChain.DiffRequestProducerWrapper).request as SimpleDiffRequest }
        assertEquals(listOf("D.txt", "E.txt", "D.txt"), requests.map { it.title })
        // Matches the disk: the right side is the file, so it can be edited and reverted in place.
        assertTrue(requests[0].contents[1] is FileContent)
        // The file is gone: a read-only text copy.
        assertTrue(requests[1].contents[1] is DocumentContent && requests[1].contents[1] !is FileContent)
        assertEquals("created\n", (requests[1].contents[1] as DocumentContent).document.text)
        // The file changed since the diff was computed: show the diff's own right side, not the disk.
        assertEquals("edited since\n", (requests[2].contents[1] as DocumentContent).document.text)
        assertEquals("old\n", (requests[0].contents[0] as DocumentContent).document.text)
    }

    fun `test the host version names the IDE`() {
        val (name, version) = bridge().hostVersion()
        assertTrue(name.isNotBlank())
        assertTrue(version.isNotBlank())
    }
}
