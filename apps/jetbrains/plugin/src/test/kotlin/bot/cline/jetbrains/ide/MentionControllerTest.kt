package bot.cline.jetbrains.ide

import bot.cline.jetbrains.chat.MentionMatch
import bot.cline.jetbrains.chat.MentionResults
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.UIUtil
import java.awt.event.KeyEvent
import java.util.concurrent.CompletableFuture
import javax.swing.text.JTextComponent

class MentionControllerTest : BasePlatformTestCase() {
    private class Presenter : MentionPresenter {
        var shown = 0
        var hidden = 0
        override fun show(list: JBList<MentionMatch>, input: JTextComponent) { shown++ }
        override fun hide() { hidden++ }
    }

    private val queries = mutableListOf<String>()
    private val results = listOf(MentionMatch("src/a.kt", false, "a.kt"), MentionMatch("src", true, "src"), MentionMatch("b.kt", false, "b.kt"))
    private val input = JBTextArea()
    private val presenter = Presenter()

    private fun controller(answer: (String) -> MentionResults = { MentionResults(results, null, null) }) =
        MentionController(input, { q -> queries += q; CompletableFuture.completedFuture(answer(q)) }, presenter, delayMs = 1).also { it.attach() }

    private fun key(code: Int) = KeyEvent(input, KeyEvent.KEY_PRESSED, 0, 0, code, KeyEvent.CHAR_UNDEFINED)

    private fun typeAndLookup(c: MentionController, text: String) {
        input.text = text
        input.caretPosition = text.length
        c.lookup()
        UIUtil.dispatchAllInvocationEvents()
    }

    fun `test typing an at sign lists matches and the first is selected`() {
        val c = controller()
        typeAndLookup(c, "Look at @a")
        assertEquals(listOf("a"), queries)
        assertEquals(results, c.suggestions)
        assertTrue(c.visible)
        assertEquals(1, presenter.shown)
    }

    fun `test enter inserts the selected file and is not passed on`() {
        val c = controller()
        typeAndLookup(c, "Look at @a")
        val down = key(KeyEvent.VK_DOWN)
        assertTrue(c.handleKey(down) && down.isConsumed)
        val up = key(KeyEvent.VK_UP)
        c.handleKey(up)
        val enter = key(KeyEvent.VK_ENTER)
        assertTrue(c.handleKey(enter))
        assertTrue(enter.isConsumed)
        assertEquals("Look at @/src/a.kt ", input.text)
        assertFalse(c.visible)
    }

    fun `test tab picks and down then enter picks the second`() {
        val c = controller()
        typeAndLookup(c, "@")
        c.handleKey(key(KeyEvent.VK_DOWN))
        c.handleKey(key(KeyEvent.VK_ENTER))
        assertEquals("@/src/", input.text)
        val d = controller()
        typeAndLookup(d, "@")
        d.handleKey(key(KeyEvent.VK_TAB))
        assertEquals("@/src/a.kt ", input.text)
    }

    fun `test escape closes and keys pass through when nothing is shown`() {
        val c = controller()
        assertFalse(c.handleKey(key(KeyEvent.VK_ENTER)))
        typeAndLookup(c, "@a")
        val esc = key(KeyEvent.VK_ESCAPE)
        assertTrue(c.handleKey(esc))
        assertFalse(c.visible)
        assertFalse(c.handleKey(key(KeyEvent.VK_ENTER)))
    }

    fun `test other keys while open are left for the input`() {
        val c = controller()
        typeAndLookup(c, "@a")
        val letter = key(KeyEvent.VK_B)
        assertFalse(c.handleKey(letter))
        assertFalse(letter.isConsumed)
    }

    fun `test no mention under the caret hides the list`() {
        val c = controller()
        typeAndLookup(c, "@a")
        typeAndLookup(c, "plain text")
        assertFalse(c.visible)
        assertTrue(c.suggestions.isEmpty())
    }

    fun `test no matches or a failed search shows nothing`() {
        val c = controller { MentionResults(emptyList(), "ripgrep_spawn_failed", "rg missing") }
        typeAndLookup(c, "@zzz")
        assertFalse(c.visible)
        assertEquals(0, presenter.shown)
    }

    fun `test an older answer does not replace a newer one`() {
        val pending = mutableMapOf<String, CompletableFuture<MentionResults>>()
        val c = MentionController(input, { q -> CompletableFuture<MentionResults>().also { pending[q] = it } }, presenter, delayMs = 1).also { it.attach() }
        input.text = "@a"; input.caretPosition = 2; c.lookup()
        input.text = "@ab"; input.caretPosition = 3; c.lookup()
        pending.getValue("ab").complete(MentionResults(listOf(MentionMatch("ab.kt", false, null)), null, null))
        pending.getValue("a").complete(MentionResults(listOf(MentionMatch("old.kt", false, null)), null, null))
        UIUtil.dispatchAllInvocationEvents()
        assertEquals(listOf("ab.kt"), c.suggestions.map { it.path })
    }
}
