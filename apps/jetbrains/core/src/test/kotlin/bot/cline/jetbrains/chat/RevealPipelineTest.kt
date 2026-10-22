package bot.cline.jetbrains.chat

import com.google.gson.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The path the chat takes while a turn runs: the answer is held while it streams, then handed to the pacer, which
 * shows it a few words at a time. This follows one turn the way the panel sees it, update by update.
 */
class RevealPipelineTest {
    private fun say(ts: Long, kind: String, text: String, partial: Boolean = false) = ChatMessage(
        JsonObject().apply {
            addProperty("ts", ts)
            addProperty("type", "say")
            addProperty("say", kind)
            addProperty("text", text)
            if (partial) addProperty("partial", true)
        },
    )

    private val answer = "alpha beta gamma delta epsilon zeta eta theta iota kappa lambda"

    private fun draw(messages: List<ChatMessage>, phase: String?, pacer: RevealPacer): List<TranscriptItem> =
        pacer.apply(Transcript.holdUnfinished(Transcript.build(messages, phase), phase))

    private fun lastText(rows: List<TranscriptItem>) = (rows.last() as TranscriptItem.Single).message.text()

    @Test
    fun `an answer held while it streams is revealed once it lands`() {
        val pacer = RevealPacer()
        val turn = mutableListOf(say(1, "task", "go"))
        draw(turn, "streaming", pacer)
        turn += say(2, "text", answer.substring(0, 20), partial = true)
        // Held: the row is not there at all.
        assertEquals(1, draw(turn, "streaming", pacer).size)
        // The finished answer replaces the partial one and is revealed a few words at a time.
        turn[turn.lastIndex] = say(2, "text", answer)
        val first = draw(turn, "streaming", pacer)
        assertEquals(2, first.size)
        assertTrue(lastText(first).length < answer.length, "shown whole: ${lastText(first)}")
        assertTrue(answer.startsWith(lastText(first)))
        assertTrue(pacer.revealing)
        var steps = 0
        while (pacer.revealing && steps++ < 200) {
            pacer.advance()
            draw(turn, "streaming", pacer)
        }
        assertEquals(answer, lastText(draw(turn, "streaming", pacer)))
    }

    @Test
    fun `an answer that lands after the turn ended is still revealed`() {
        val pacer = RevealPacer()
        val turn = mutableListOf(say(1, "task", "go"))
        draw(turn, "streaming", pacer)
        turn += say(2, "text", answer)
        val first = draw(turn, "idle", pacer)
        assertEquals(2, first.size)
        assertTrue(lastText(first).length < answer.length, "shown whole: ${lastText(first)}")
    }

    @Test
    fun `the answer the core retags as a plan result is revealed too`() {
        // In plan mode the core retags the turn's last text row in place, ts and all, so the panel sees a
        // plan_completion_result where it saw a partial text row a moment before.
        val pacer = RevealPacer()
        val turn = mutableListOf(say(1, "task", "go"))
        draw(turn, "streaming", pacer)
        turn += say(2, "text", answer.substring(0, 20), partial = true)
        assertEquals(1, draw(turn, "streaming", pacer).size)
        turn[turn.lastIndex] = say(2, "plan_completion_result", answer)
        val first = draw(turn, "idle", pacer)
        assertEquals(2, first.size)
        assertTrue(lastText(first).length < answer.length, "shown whole: ${lastText(first)}")
        assertTrue(pacer.revealing)
    }

    @Test
    fun `an answer the core reports as an ask is revealed too`() {
        val pacer = RevealPacer()
        val turn = mutableListOf(say(1, "task", "go"))
        draw(turn, "streaming", pacer)
        val ask = ChatMessage(
            JsonObject().apply {
                addProperty("ts", 2)
                addProperty("type", "ask")
                addProperty("ask", "completion_result")
                addProperty("text", answer)
            },
        )
        turn += ask
        val first = draw(turn, "idle", pacer)
        assertEquals(2, first.size)
        assertTrue(lastText(first).length < answer.length, "shown whole: ${lastText(first)}")
    }
}
