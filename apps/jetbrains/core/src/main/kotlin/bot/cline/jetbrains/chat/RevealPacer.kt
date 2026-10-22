package bot.cline.jetbrains.chat

/**
 * Paints the agent's answer a few words at a time, so an answer that arrives whole still reads as if it were
 * streaming in.
 *
 * The chat holds an answer back while it is being written ([Transcript.holdUnfinished]). All of it is already
 * here by the time the chat draws it, so nothing would move; [apply] cuts it to its first words and [advance]
 * lets a little more out on every tick, until it is whole.
 *
 * A row that is already on screen the first time a conversation is drawn (history, a reopened task) is shown
 * whole, so reopening a chat does not replay it. Each row keeps its own pace, set from its length, so a long
 * answer clears in a few seconds instead of hanging on its last line.
 */
class RevealPacer(private val baseStep: Int = 4) {
    /** How much of each row is shown, in characters, word-aligned. A key missing here has not been drawn yet. */
    private val shown = HashMap<Long, Int>()

    /** The full length of each row that is not shown in full yet. */
    private val lengths = HashMap<Long, Int>()

    /** The first row of the conversation now drawn, to tell a conversation that is already there from a new one. */
    private var conversation: Long? = null

    /** True while some row is not shown in full yet, so [advance] should keep being called. */
    var revealing = false
        private set

    /** The rows to draw for [items]: a row that is not shown in full yet is cut short to its first words. */
    fun apply(items: List<TranscriptItem>): List<TranscriptItem> {
        val first = items.firstOrNull()?.key
        if (first != conversation) {
            conversation = first
            shown.clear()
            lengths.clear()
            revealing = false
            // Nothing new is arriving: this is the chat as it was, so draw all of it.
            for (item in items) if (item is TranscriptItem.Single && paced(item.message)) shown[item.key] = item.message.text().length
            return items
        }
        var out: ArrayList<TranscriptItem>? = null
        revealing = false
        for ((i, item) in items.withIndex()) {
            if (item !is TranscriptItem.Single || !paced(item.message)) continue
            val text = item.message.text()
            val n = shown[item.key] ?: 0
            if (n >= text.length) continue
            val end = wordEnd(text, n)
            shown[item.key] = end
            lengths[item.key] = text.length
            revealing = true
            if (out == null) out = ArrayList(items)
            out[i] = TranscriptItem.Single(item.message.withText(text.substring(0, end)))
        }
        return out ?: items
    }

    /** Shows a little more of each row that is still being revealed. */
    fun advance() {
        var left = false
        for ((key, length) in lengths) {
            val n = shown[key] ?: continue
            if (n >= length) continue
            val next = minOf(length, n + paceFor(length))
            shown[key] = next
            if (next < length) left = true
        }
        revealing = left
    }

    private fun paced(message: ChatMessage) = message.isSay("text") || message.isSay("completion_result")

    /** The end of the word at or after [from], so a word is never cut in half; at least [baseStep] characters. */
    private fun wordEnd(text: String, from: Int): Int {
        var end = maxOf(from, baseStep).coerceAtMost(text.length)
        while (end < text.length && !text[end].isWhitespace()) end++
        return end
    }

    /**
     * How many characters one step adds to a row of [length] characters. About [CHARS_PER_STEP] per step for a
     * short answer, spread thinner over a long one so the whole answer clears within [MAX_STEPS] steps.
     */
    private fun paceFor(length: Int): Int {
        val steps = (length / CHARS_PER_STEP).coerceIn(MIN_STEPS, MAX_STEPS)
        return maxOf(baseStep, (length + steps - 1) / steps)
    }

    private companion object {
        /** Characters revealed per step when the answer is short enough for the pace to follow it. */
        const val CHARS_PER_STEP = 8

        /** Fewer steps than this would show a short answer almost at once. */
        const val MIN_STEPS = 8

        /** More steps than this would drag a long answer out; at 60 ms a step that is under five seconds. */
        const val MAX_STEPS = 80
    }
}
