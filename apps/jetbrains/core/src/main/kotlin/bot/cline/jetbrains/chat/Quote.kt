package bot.cline.jetbrains.chat

/** Quoting part of a message when replying to it. */
object Quote {
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
