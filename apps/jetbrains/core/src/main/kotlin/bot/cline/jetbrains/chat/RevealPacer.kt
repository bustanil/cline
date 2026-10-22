package bot.cline.jetbrains.chat

/**
 * Decides how much of the agent's answer text the chat shows, so the answer paints at a steady, readable pace
 * instead of jumping with every network update.
 *
 * - A text row that is still streaming is held back. It shows once it is finished, or once something follows it
 *   (a tool call, thinking), which means the model has moved on.
 * - A row that is shown reveals word by word, as if it were streaming, even though all of it is already here.
 *   The pace is [baseStep] characters per [advance]; a long backlog speeds up so the tail never drags.
 * - Rows after a row that is still being revealed wait for it, so the chat reads in order.
 * - A conversation that is already there when it first appears (history, a reopened task) is shown whole.
 */
class RevealPacer(private val baseStep: Int = 6) {
    private val revealed = HashMap<Long, Int>()
    private var firstKey: Long? = null
    private var current: Long? = null
    private var currentLength = 0

    /** True while some row is not shown in full yet, so [advance] should keep being called. */
    var revealing = false
        private set

    /** The rows to draw for [items]: held-back rows are left out and the row being revealed is cut short. */
    fun apply(items: List<TranscriptItem>, turnPhase: String?): List<TranscriptItem> {
        val first = items.firstOrNull()?.key
        if (first != firstKey) {
            revealed.clear()
            firstKey = first
            items.forEachIndexed { i, item -> if (item is TranscriptItem.Single && paced(item.message) && !held(item.message, i == items.lastIndex, turnPhase)) revealed[item.key] = DONE }
        }
        val out = ArrayList<TranscriptItem>(items.size)
        revealing = false
        current = null
        for ((i, item) in items.withIndex()) {
            if (item !is TranscriptItem.Single || !paced(item.message)) {
                out += item
                continue
            }
            val message = item.message
            if (held(message, i == items.lastIndex, turnPhase)) break
            val text = message.text()
            val shown = revealed.getOrPut(message.ts) { minOf(baseStep, text.length) }
            if (shown >= text.length) {
                out += item
                continue
            }
            out += TranscriptItem.Single(message.withText(cut(text, shown)))
            revealing = true
            current = message.ts
            currentLength = text.length
            break
        }
        return out
    }

    /** Shows a little more of the row being revealed. */
    fun advance() {
        val ts = current ?: return
        val shown = revealed[ts] ?: return
        val remaining = currentLength - shown
        revealed[ts] = minOf(currentLength, shown + maxOf(baseStep, remaining / CATCH_UP))
    }

    private fun paced(message: ChatMessage) = message.isSay("text") || message.isSay("completion_result")

    /** The last row of a running turn that is still being written. */
    private fun held(message: ChatMessage, last: Boolean, turnPhase: String?): Boolean =
        last && turnPhase == "streaming" && message.raw.get("partial")?.let { it.isJsonPrimitive && it.asBoolean } == true

    /** The first [n] characters, extended to the end of the word so a word is never cut in half. */
    private fun cut(text: String, n: Int): String {
        var end = n.coerceAtMost(text.length)
        while (end < text.length && !text[end].isWhitespace()) end++
        return text.substring(0, end)
    }

    private companion object {
        const val DONE = Int.MAX_VALUE
        /** A backlog of N characters is cleared at about N / CATCH_UP per step, so it ends within about CATCH_UP steps. */
        const val CATCH_UP = 50
    }
}
