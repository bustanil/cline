package bot.cline.jetbrains.ide

import bot.cline.jetbrains.chat.EditorCommand
import bot.cline.jetbrains.chat.EditorDiagnostic
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import java.awt.Component
import java.awt.Container
import javax.swing.text.JTextComponent

class EditorActionsTest : BasePlatformTestCase() {
    private val source = """
        class A {
            void f() {
                int ok = 1;
                int x = undefinedThing;
                int y = ok;
            }
        }
    """.trimIndent() + "\n"

    private fun configure() {
        myFixture.configureByText("A.java", source)
        myFixture.doHighlighting()
    }

    private fun selectLines(first: Int, last: Int, trailingNewline: Boolean = false) {
        val doc = myFixture.editor.document
        val end = if (trailingNewline) doc.getLineStartOffset(last + 1) else doc.getLineEndOffset(last)
        myFixture.editor.selectionModel.setSelection(doc.getLineStartOffset(first), end)
    }

    fun `test the context has the selection lines language and the error inside it`() {
        configure()
        selectLines(2, 3)
        val context = EditorContextBuilder.build(project, myFixture.editor)!!
        assertEquals(3, context.startLine)
        assertEquals(4, context.endLine)
        assertEquals("java", context.language)
        assertEquals("int ok = 1;\n        int x = undefinedThing;".replace("        ", "        "), context.selectedText.trim())
        assertTrue(context.filePath.endsWith("A.java"))
        val d = context.diagnostics.single()
        assertEquals(EditorDiagnostic.Severity.ERROR, d.severity)
        assertEquals(3, d.startLine)
        assertTrue(d.message, d.message.contains("undefinedThing"))
    }

    fun `test a selection that ends at the start of the next line does not count that line`() {
        configure()
        selectLines(2, 3, trailingNewline = true)
        val context = EditorContextBuilder.build(project, myFixture.editor)!!
        assertEquals(3, context.startLine)
        assertEquals(4, context.endLine)
    }

    fun `test diagnostics outside the selection are left out`() {
        configure()
        selectLines(2, 2)
        assertTrue(EditorContextBuilder.build(project, myFixture.editor)!!.diagnostics.isEmpty())
    }

    fun `test without a selection the caret line is used`() {
        configure()
        myFixture.editor.selectionModel.removeSelection()
        myFixture.editor.caretModel.moveToLogicalPosition(com.intellij.openapi.editor.LogicalPosition(3, 5))
        val context = EditorContextBuilder.build(project, myFixture.editor)!!
        assertEquals(4, context.startLine)
        assertEquals(4, context.endLine)
        assertEquals(1, context.diagnostics.size)
    }

    fun `test the fix intention is offered on the error line only`() {
        configure()
        val intention = FixWithClineIntention()
        myFixture.editor.caretModel.moveToLogicalPosition(com.intellij.openapi.editor.LogicalPosition(3, 5))
        assertTrue(intention.isAvailable(project, myFixture.editor, myFixture.file))
        myFixture.editor.caretModel.moveToLogicalPosition(com.intellij.openapi.editor.LogicalPosition(2, 5))
        assertFalse(intention.isAvailable(project, myFixture.editor, myFixture.file))
        assertEquals("Fix with Cline", intention.text)
    }

    fun `test the tool window has an icon and the toolbar has an Open Cline button`() {
        val manager = ActionManager.getInstance()
        val open = manager.getAction("Cline.OpenToolWindow")
        assertNotNull(open)
        assertNotNull("the action needs an icon to show in the toolbar", open.templatePresentation.icon)
        for (groupId in listOf("MainToolbarRight", "ToolsMenu")) {
            val group = manager.getAction(groupId) as com.intellij.openapi.actionSystem.ActionGroup
            assertTrue("$groupId should hold Open Cline", group.getChildren(null).any { it === open })
        }
        assertNotNull("the icon file ships with the plugin", javaClass.getResource("/icons/cline.svg"))
        assertNotNull(javaClass.getResource("/icons/cline_dark.svg"))
        val window = com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow("Cline")
        // The light fixture may not create tool windows; when it does, it must have an icon.
        if (window != null) assertNotNull(window.icon)
    }

    fun `test the actions are registered and the editor menu group exists`() {
        val manager = ActionManager.getInstance()
        for (id in listOf("Cline.AddToChat", "Cline.Fix", "Cline.Explain", "Cline.Improve", "Cline.EditorActions")) assertNotNull(id, manager.getAction(id))
        val popup = manager.getAction("EditorPopupMenu") as com.intellij.openapi.actionSystem.ActionGroup
        assertTrue(popup.getChildren(null).any { it === manager.getAction("Cline.EditorActions") })
    }

    private fun Container.textAreas(): List<JTextComponent> =
        components.flatMap { c -> (if (c is JTextComponent) listOf(c) else emptyList()) + ((c as? Container)?.textAreas() ?: emptyList()) }

    fun `test add to Cline on the real core puts the file and lines in the input`() {
        val coreJs = System.getenv("CLINE_CORE_JS")
        if (coreJs == null) {
            println("SKIPPED: CLINE_CORE_JS is not set")
            return
        }
        val s = ClineSettings.getInstance().state
        s.corePath = coreJs
        s.nodePath = CoreLocator.node(s) ?: error("no node")
        s.dataDir = FileUtil.createTempDirectory("cline-data", null).path
        try {
            configure()
            selectLines(2, 3)
            val panel = ChatPanel(project)
            val service = ClineProjectService.getInstance(project)
            ClineEditorAction.send(project, myFixture.editor, EditorCommand.ADD_TO_CHAT)
            val end = System.currentTimeMillis() + 90_000
            var text = ""
            while (System.currentTimeMillis() < end && !text.contains("```")) {
                UIUtil.dispatchAllInvocationEvents()
                text = panel.textAreas().joinToString("\n") { it.text.orEmpty() }
                Thread.sleep(100)
            }
            println("INPUT: " + text.replace("\n", "\\n"))
            assertTrue(text, text.contains("A.java") && text.contains("(lines 3-4)") && text.contains("undefinedThing"))
            panel.dispose()
            service.dispose()
        } finally {
            s.corePath = ""; s.nodePath = ""; s.dataDir = ""
        }
    }
}
