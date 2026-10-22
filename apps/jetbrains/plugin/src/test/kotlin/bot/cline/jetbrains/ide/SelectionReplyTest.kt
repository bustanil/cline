package bot.cline.jetbrains.ide

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBHtmlPane
import com.intellij.ui.components.JBHtmlPaneConfiguration
import com.intellij.ui.components.JBHtmlPaneStyleConfiguration
import java.awt.Point
import java.awt.event.KeyEvent
import javax.swing.JComponent
import javax.swing.text.JTextComponent

class SelectionReplyTest : BasePlatformTestCase() {
    private class Presenter : ReplyPresenter {
        var shown: ReplyPopupPanel? = null
        var hides = 0
        override fun show(content: JComponent, owner: JTextComponent, at: Point) { shown = content as ReplyPopupPanel }
        override fun hide() { hides++ }
    }

    private class Recorder : QuoteActions {
        val added = mutableListOf<String>()
        val sent = mutableListOf<Pair<String, String>>()
        override fun addQuote(quote: String) { added += quote }
        override fun sendReply(quote: String, text: String) { sent += quote to text }
    }

    private fun pane(text: String) = JBHtmlPane(JBHtmlPaneStyleConfiguration.builder().build(), JBHtmlPaneConfiguration.builder().build()).apply {
        isEditable = false
        this.text = "<p>$text</p>"
    }

    /** Selects [words] where they are in the pane's text. */
    private fun selectWords(pane: JBHtmlPane, words: String) {
        val at = pane.document.getText(0, pane.document.length).indexOf(words)
        check(at >= 0) { "'$words' is not in the pane" }
        pane.select(at, at + words.length)
    }

    private fun enter(p: ReplyPopupPanel) {
        val event = KeyEvent(p.field, KeyEvent.KEY_PRESSED, 0, 0, KeyEvent.VK_ENTER, '\n')
        p.field.keyListeners.forEach { it.keyPressed(event) }
    }

    fun `test selecting words shows a popup that quotes them`() {
        val pane = pane("the provider dropdown prints the raw object")
        val presenter = Presenter()
        val controller = SelectionReplyController(pane, Recorder(), presenter)
        val start = pane.document.getText(0, pane.document.length).indexOf("provider")
        pane.select(start, start + "provider dropdown".length)
        controller.maybeShow()
        assertNotNull(presenter.shown)
        assertEquals("provider dropdown", pane.selectedText)
    }

    fun `test nothing or a single character selected shows no popup`() {
        val pane = pane("some words here")
        val presenter = Presenter()
        val controller = SelectionReplyController(pane, Recorder(), presenter)
        controller.maybeShow()
        assertNull(presenter.shown)
        pane.select(0, 1)
        controller.maybeShow()
        assertNull(presenter.shown)
        assertTrue(presenter.hides >= 2)
    }

    fun `test Enter in the popup sends the reply with the quote and an empty reply is not sent`() {
        val pane = pane("quote this part of the answer")
        val actions = Recorder()
        val presenter = Presenter()
        val controller = SelectionReplyController(pane, actions, presenter)
        selectWords(pane, "quote this")
        controller.maybeShow()
        val popup = presenter.shown!!
        enter(popup)
        assertTrue("an empty reply is not sent", actions.sent.isEmpty())
        popup.field.text = "  why?  "
        enter(popup)
        assertEquals(listOf("quote this" to "why?"), actions.sent)
    }

    fun `test the add icon puts the quote in the composer instead of sending`() {
        val pane = pane("quote this part of the answer")
        val actions = Recorder()
        val presenter = Presenter()
        val controller = SelectionReplyController(pane, actions, presenter)
        selectWords(pane, "this part")
        controller.maybeShow()
        presenter.shown!!.addToComposer()
        assertEquals(listOf("this part"), actions.added)
        assertTrue(actions.sent.isEmpty())
    }

    fun `test a pane made with quote actions gets the reply behavior`() {
        val actions = Recorder()
        val withQuotes = newHtmlPane(quotes = actions) { }
        val without = newHtmlPane { }
        assertTrue(withQuotes.mouseListeners.size > without.mouseListeners.size)
    }
}
