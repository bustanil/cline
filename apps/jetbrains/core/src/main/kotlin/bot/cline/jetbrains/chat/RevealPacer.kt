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
 * whole, so reopening a chat does not replay it. Each row is spread over a set number of steps, taken from its
 * length, so a short answer still takes about a second and a half and a long one is done in under six.
 */
class RevealPacer(private val baseStep: Int = 2) {
    /** The steps taken so far for each row that is being revealed. A row missing here has not been drawn yet. */
    private val taken = HashMap<Long, Int>()

    /** How many steps each row is spread over. Zero means the row is shown whole. */
    private val total = HashMap<Long, Int>()

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
            taken.clear()
            total.clear()
            revealing = false
            // Nothing new is arriving: this is the chat as it was, so draw all of it.
            for (item in items) if (item is TranscriptItem.Single && paced(item.message)) showWhole(item.key)
            return items
        }
        var out: ArrayList<TranscriptItem>? = null
        revealing = false
        for ((i, item) in items.withIndex()) {
            if (item !is TranscriptItem.Single || !paced(item.message)) continue
            val text = item.message.text()
            val steps = total.getOrPut(item.key) { stepsFor(text.length) }
            val done = taken.getOrPut(item.key) { 1 }
            if (done >= steps) continue
            val end = wordEnd(text, text.length * done / steps)
            revealing = true
            if (out == null) out = ArrayList(items)
            out[i] = TranscriptItem.Single(item.message.withText(text.substring(0, end)))
        }
        return out ?: items
    }

    /** Shows the next step of each row that is still being revealed. */
    fun advance() {
        var left = false
        for ((key, steps) in total) {
            val done = taken[key] ?: continue
            if (done >= steps) continue
            taken[key] = done + 1
            if (done + 1 < steps) left = true
        }
        revealing = left
    }

    private fun showWhole(key: Long) {
        total[key] = 0
        taken[key] = 0
    }

    private fun paced(message: ChatMessage) = message.isSay("text") || message.isSay("completion_result")

    /** The end of the word at or after [from], so a word is never cut in half; at least [baseStep] characters. */
    private fun wordEnd(text: String, from: Int): Int {
        var end = maxOf(from, baseStep).coerceAtMost(text.length)
        while (end < text.length && !text[end].isWhitespace()) end++
        return end
    }

    /**
     * How many steps a row of [length] characters is spread over. About [CHARS_PER_STEP] characters a step for a
     * short answer, and never more than [MAX_STEPS] so a long one still finishes.
     */
    private fun stepsFor(length: Int): Int = (length / CHARS_PER_STEP).coerceIn(MIN_STEPS, MAX_STEPS)

    private companion object {
        /** Characters revealed per step when the answer is short enough for the pace to follow it: about 50 a second. */
        const val CHARS_PER_STEP = 3

        /** At 60 ms a step, a second and a half, so even a one-line answer is seen to arrive. */
        const val MIN_STEPS = 25

        /** At 60 ms a step, under six seconds, so a long answer does not drag. */
        const val MAX_STEPS = 90
    }
}
