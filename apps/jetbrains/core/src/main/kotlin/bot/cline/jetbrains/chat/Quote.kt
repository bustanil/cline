package bot.cline.jetbrains.chat

/** One quoted passage and what the user said about it; the reply may be empty. */
data class QuoteEntry(val quote: String, val reply: String = "")

/** Quoting part of a message when replying to it. */
object Quote {
    /** The quote on its own, with nothing said about it. */
    fun context(quote: String): String = "[context] \n>  ${clean(quote)} \n[/context]"

    /**
     * The message for several quotes and the text typed in the box. A quote with its own reply is followed by it. The
     * box text answers the last quote when that quote has no reply of its own, which for one quote is exactly what the
     * VS Code client sends; otherwise it follows the quotes. Blocks are separated by a blank line. A quote with
     * nothing said about it is sent alone.
     */
    fun formatAll(entries: List<QuoteEntry>, text: String): String {
        val typed = text.trim()
        val parts = mutableListOf<String>()
        entries.forEachIndexed { i, entry ->
            val reply = entry.reply.trim()
            val last = i == entries.lastIndex
            parts += when {
                clean(entry.quote).isEmpty() -> reply
                reply.isNotEmpty() -> format(entry.quote, reply)
                last && typed.isNotEmpty() -> format(entry.quote, typed)
                else -> context(entry.quote)
            }
        }
        val usedByLastQuote = entries.isNotEmpty() && entries.last().reply.isBlank() && clean(entries.last().quote).isNotEmpty() && typed.isNotEmpty()
        if (typed.isNotEmpty() && !usedByLastQuote) parts += typed
        return parts.filter { it.isNotEmpty() }.joinToString("\n\n")
    }

    /**
     * The text sent to the model: the quote in a `[context]` block, then the reply. This is the same text the
     * VS Code client builds (`formatDraftText`), spaces included, so a conversation can move between clients.
     */
    fun format(quote: String?, text: String): String {
        val q = clean(quote)
        if (q.isEmpty()) return text
        return "[context] \n>  $q \n[/context] \n\n $text"
    }

    /** The selected text with its edges trimmed; blank when there is nothing worth quoting. */
    fun clean(selected: String?): String = selected?.trim().orEmpty()

    /** A short form for showing in a card: at most [max] characters, ending in an ellipsis when cut. */
    fun preview(quote: String, max: Int = 200): String {
        val single = quote.replace(Regex("\\s+"), " ").trim()
        return if (single.length <= max) single else single.take(max).trimEnd() + "…"
    }
}
