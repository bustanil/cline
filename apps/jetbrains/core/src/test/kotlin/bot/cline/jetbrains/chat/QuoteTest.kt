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
}
