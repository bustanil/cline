package bot.cline.jetbrains.chat

import com.google.gson.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RevealPacerTest {
    private fun say(ts: Long, kind: String, text: String) = ChatMessage(
        JsonObject().apply {
            addProperty("ts", ts)
            addProperty("type", "say")
            addProperty("say", kind)
            addProperty("text", text)
        },
    )

    private fun items(vararg messages: ChatMessage): List<TranscriptItem> = messages.map { TranscriptItem.Single(it) }

    private fun texts(rows: List<TranscriptItem>) = rows.map { (it as TranscriptItem.Single).message.raw.get("text").asString }

    private val sentence = "alpha beta gamma delta epsilon zeta eta theta iota kappa lambda"

    @Test
    fun `a new answer is revealed a few words at a time and never cuts a word`() {
        val pacer = RevealPacer()
        val base = items(say(1, "task", "go"))
        pacer.apply(base)
        val all = base + items(say(2, "text", sentence))
        val first = texts(pacer.apply(all)).last()
        assertTrue(first.length < sentence.length)
        assertTrue(sentence.startsWith(first))
        assertTrue(sentence[first.length].isWhitespace(), "a word is cut: $first")
        assertTrue(pacer.revealing)
        var guard = 0
        var last = first
        while (pacer.revealing && guard++ < 200) {
            pacer.advance()
            val now = texts(pacer.apply(all)).last()
            assertTrue(now.length >= last.length)
            last = now
        }
        assertEquals(sentence, last)
        assertFalse(pacer.revealing)
    }

    @Test
    fun `the rest of the chat is still drawn while an answer is revealed`() {
        val pacer = RevealPacer()
        val base = items(say(1, "task", "go"))
        pacer.apply(base)
        val all = base + items(say(2, "text", sentence), say(3, "tool", "read"))
        val rows = pacer.apply(all)
        assertEquals(3, rows.size)
        assertEquals("read", texts(rows).last())
        assertTrue(pacer.revealing)
    }

    @Test
    fun `a chat that is already there is shown whole`() {
        val pacer = RevealPacer()
        val rows = pacer.apply(items(say(1, "task", "go"), say(2, "text", sentence)))
        assertEquals(sentence, texts(rows).last())
        assertFalse(pacer.revealing)
    }

    @Test
    fun `another conversation starts over`() {
        val pacer = RevealPacer()
        pacer.apply(items(say(1, "task", "first"), say(2, "text", sentence)))
        val other = items(say(5, "task", "second"))
        pacer.apply(other)
        val rows = pacer.apply(other + items(say(6, "text", sentence)))
        assertTrue(texts(rows).last().length < sentence.length)
        assertTrue(pacer.revealing)
    }

    @Test
    fun `a short answer still takes about a second and a half`() {
        val pacer = RevealPacer()
        val base = items(say(1, "task", "go"))
        pacer.apply(base)
        val short = "this is a short answer of about sixty characters, only just"
        val all = base + items(say(2, "text", short))
        pacer.apply(all)
        var steps = 0
        while (pacer.revealing && steps < 1000) {
            pacer.advance()
            pacer.apply(all)
            steps++
        }
        // The chat ticks every 60 ms, so 20 steps is over a second.
        assertTrue(steps >= 20, "took only $steps steps")
        assertEquals(short, texts(pacer.apply(all)).last())
    }

    @Test
    fun `a long answer clears within a few dozen steps`() {
        val pacer = RevealPacer()
        val base = items(say(1, "task", "go"))
        pacer.apply(base)
        val all = base + items(say(2, "text", "word ".repeat(2000)))
        pacer.apply(all)
        var steps = 0
        while (pacer.revealing && steps < 1000) {
            pacer.advance()
            pacer.apply(all)
            steps++
        }
        assertTrue(steps in 1..100, "took $steps steps")
    }

    @Test
    fun `a row that grows while it is revealed follows the new text`() {
        val pacer = RevealPacer()
        val base = items(say(1, "task", "go"))
        pacer.apply(base)
        val shorter = base + items(say(2, "text", "alpha beta gamma"))
        pacer.apply(shorter)
        pacer.advance()
        pacer.apply(shorter)
        val grown = base + items(say(2, "text", "alpha beta gamma delta epsilon zeta eta"))
        val shown = texts(pacer.apply(grown)).last()
        assertTrue(shown.length < 42, "shown whole: $shown")
        assertTrue("alpha beta gamma delta epsilon zeta eta".startsWith(shown))
    }
}
