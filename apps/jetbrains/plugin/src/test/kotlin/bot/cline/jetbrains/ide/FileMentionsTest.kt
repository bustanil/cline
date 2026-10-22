package bot.cline.jetbrains.ide

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class FileMentionsTest : BasePlatformTestCase() {
    fun `test a file and a folder become at mentions relative to the project`() {
        val file = myFixture.addFileToProject("src/a/B.kt", "class B").virtualFile
        assertEquals("@/src/a/B.kt", FileMentions.mention(project, file))
        assertEquals("@/src/a/", FileMentions.mention(project, file.parent))
    }

    fun `test the caret line becomes a file and line pointer`() {
        myFixture.configureByText("Pointer.java", "class P {\n    int a;\n    int b;\n}\n")
        val doc = myFixture.editor.document
        myFixture.editor.caretModel.moveToOffset(doc.getLineStartOffset(2))
        val pointer = FileMentions.pointer(project, myFixture.editor)!!
        assertTrue(pointer, pointer.matches(Regex("@/.*Pointer\\.java \\(line 3\\) ")))
    }

    fun `test the actions are registered`() {
        val manager = com.intellij.openapi.actionSystem.ActionManager.getInstance()
        assertNotNull(manager.getAction("ClineNative.AddFiles"))
        assertTrue(manager.getAction("ClineNative.AddFiles") is AddFilesToClineAction)
    }
}
