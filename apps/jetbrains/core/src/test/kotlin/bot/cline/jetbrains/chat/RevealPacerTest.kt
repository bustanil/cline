package bot.cline.jetbrains.chat

import com.google.gson.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RevealPacerTest {
    private fun say(ts: Long, kind: String, text: String, partial: Boolean = false) = ChatMessage(
        JsonObject().apply {
            addProperty("ts", ts)
            addProperty("type", "say")
            addProperty("say", kind)
            addProperty("text", text)
            if (partial) addProperty("partial", true)
        },
    )

    private fun items(vararg messages: ChatMessage): List<TranscriptItem> = messages.map { TranscriptItem.Single(it) }

    private fun texts(rows: List<TranscriptItem>) = rows.map { (it as TranscriptItem.Single).message.raw.get("text").asString }

    private val sentence = "alpha beta gamma delta epsilon zeta eta theta iota kappa lambda"

    @Test
    fun `a text row that is still streaming is held back`() {
        val pacer = RevealPacer()
        val start = items(say(1, "task", "go"))
        pacer.apply(start, "streaming")
        val rows = pacer.apply(start + items(say(2, "text", sentence, partial = true)), "streaming")
        assertEquals(1, rows.size)
        assertFalse(pacer.revealing)
    }

    @Test
    fun `a finished row is revealed a few words at a time and never cuts a word`() {
        val pacer = RevealPacer(baseStep = 6)
        val base = items(say(1, "task", "go"))
        pacer.apply(base, "streaming")
        val all = base + items(say(2, "text", sentence))
        val first = texts(pacer.apply(all, "streaming")).last()
        assertTrue(first.length < sentence.length)
        assertTrue(sentence.startsWith(first))
        assertTrue(first == sentence || sentence[first.length].isWhitespace(), "a word is cut: $first")
        assertTrue(pacer.revealing)
        var guard = 0
        var last = first
        while (pacer.revealing && guard++ < 100) {
            pacer.advance()
            val now = texts(pacer.apply(all, "streaming")).last()
            assertTrue(now.length >= last.length)
            last = now
        }
        assertEquals(sentence, last)
    }

    @Test
    fun `a row that is held starts to show once something follows it`() {
        val pacer = RevealPacer()
        val base = items(say(1, "task", "go"))
        pacer.apply(base, "streaming")
        val partial = base + items(say(2, "text", sentence, partial = true))
        assertEquals(1, pacer.apply(partial, "streaming").size)
        val followed = base + items(say(2, "text", sentence, partial = true), say(3, "tool", "read"))
        val rows = pacer.apply(followed, "streaming")
        assertEquals(2, rows.size)
        assertTrue(pacer.revealing)
    }

    @Test
    fun `rows after a row being revealed wait for it`() {
        val pacer = RevealPacer()
        val base = items(say(1, "task", "go"))
        pacer.apply(base, "streaming")
        val all = base + items(say(2, "text", sentence), say(3, "tool", "read"))
        assertEquals(2, pacer.apply(all, "streaming").size)
        while (pacer.revealing) {
            pacer.advance()
            pacer.apply(all, "streaming")
        }
        assertEquals(3, pacer.apply(all, "streaming").size)
    }

    @Test
    fun `a conversation already there is shown whole`() {
        val pacer = RevealPacer()
        val rows = pacer.apply(items(say(1, "task", "go"), say(2, "text", sentence)), "idle")
        assertEquals(sentence, texts(rows).last())
        assertFalse(pacer.revealing)
    }

    @Test
    fun `a long backlog is cleared in about fifty steps`() {
        val pacer = RevealPacer()
        val base = items(say(1, "task", "go"))
        pacer.apply(base, "streaming")
        val long = "word ".repeat(2000)
        val all = base + items(say(2, "text", long))
        pacer.apply(all, "streaming")
        var steps = 0
        while (pacer.revealing && steps < 1000) {
            pacer.advance()
            pacer.apply(all, "streaming")
            steps++
        }
        assertTrue(steps in 1..400, "took $steps steps")
    }

    @Test
    fun `a partial row of an ended turn is shown`() {
        val pacer = RevealPacer()
        val base = items(say(1, "task", "go"))
        pacer.apply(base, "streaming")
        val rows = pacer.apply(base + items(say(2, "text", sentence, partial = true)), "idle")
        assertEquals(2, rows.size)
    }
}
