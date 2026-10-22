package bot.cline.jetbrains.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QuoteTest {
    @Test
    fun `a quoted reply has the same text as the VS Code client sends`() {
        assertEquals("[context] \n>  the router contract \n[/context] \n\n why is that safe?", Quote.format("the router contract", "why is that safe?"))
    }

    @Test
    fun `the quote is trimmed and without one the reply is unchanged`() {
        assertEquals("[context] \n>  trimmed \n[/context] \n\n ok", Quote.format("  trimmed\n ", "ok"))
        assertEquals("ok", Quote.format(null, "ok"))
        assertEquals("ok", Quote.format("   ", "ok"))
    }

    @Test
    fun `a long quote is cut for the card`() {
        assertEquals("a b c", Quote.preview("a\n  b\tc"))
        val long = "word ".repeat(100)
        val shown = Quote.preview(long, 50)
        assertTrue(shown.length in 50..51, "length ${shown.length}")
        assertTrue(shown.endsWith("…") && !shown.dropLast(1).endsWith(" "))
        assertEquals("", Quote.clean(null))
    }

    @Test
    fun `one quote and the box text is exactly what the VS Code client sends`() {
        assertEquals(Quote.format("the router contract", "why?"), Quote.formatAll(listOf(QuoteEntry("the router contract")), "why?"))
    }

    @Test
    fun `each quote carries its own reply and the box text follows`() {
        val message = Quote.formatAll(listOf(QuoteEntry("first", "reply one"), QuoteEntry("second", "reply two")), "and overall: ok?")
        assertEquals(
            "[context] \n>  first \n[/context] \n\n reply one\n\n[context] \n>  second \n[/context] \n\n reply two\n\nand overall: ok?",
            message,
        )
    }

    @Test
    fun `the box text answers the last quote when it has no reply and earlier quotes stand alone`() {
        val message = Quote.formatAll(listOf(QuoteEntry("a", "about a"), QuoteEntry("b"), QuoteEntry("c")), "about c")
        assertEquals(
            "[context] \n>  a \n[/context] \n\n about a\n\n[context] \n>  b \n[/context]\n\n[context] \n>  c \n[/context] \n\n about c",
            message,
        )
    }

    @Test
    fun `quotes alone are sent as context only and nothing at all is empty`() {
        assertEquals("[context] \n>  a \n[/context]\n\n[context] \n>  b \n[/context]", Quote.formatAll(listOf(QuoteEntry("a"), QuoteEntry("b")), ""))
        assertEquals("hello", Quote.formatAll(emptyList(), "  hello "))
        assertEquals("", Quote.formatAll(emptyList(), ""))
        assertEquals("only text", Quote.formatAll(listOf(QuoteEntry("   ")), "only text"))
    }
}
