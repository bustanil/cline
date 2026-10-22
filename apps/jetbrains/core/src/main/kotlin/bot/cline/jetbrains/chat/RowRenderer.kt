package bot.cline.jetbrains.chat

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser

/** Colors for the HTML, as `#rrggbb`. The IDE passes ones that suit the current theme. */
data class RowColors(val added: String = "#2e9e4f", val removed: String = "#d03b3b", val muted: String = "#808080", val error: String = "#d03b3b")

/**
 * What to draw for one row. [html] takes the file references that resolved to
 * real files, so paths become links once the core has answered.
 */
data class RowView(
    /** File references in the row worth asking the core about. */
    val references: List<String>,
    val html: (resolved: Map<String, FileLink>) -> String,
    /** Canned answers to offer as buttons. */
    val options: List<String> = emptyList(),
    /** When set, the row starts collapsed behind this heading (the model's reasoning). */
    val collapsedTitle: String? = null,
)

/**
 * Turns a [TranscriptItem] into a [RowView]. Any message kind without its own
 * layout is drawn as a small label and the message text, so a kind the core
 * adds later still shows.
 */
object RowRenderer {
    private const val MAX_DIFF_LINES = 60
    private const val MAX_PREVIEW_LINES = 40
    private const val MAX_OUTPUT_LINES = 40

    fun render(item: TranscriptItem, colors: RowColors = RowColors()): RowView = when (item) {
        is TranscriptItem.ToolGroup -> toolGroup(item, colors)
        is TranscriptItem.Single -> single(item.message, colors)
    }

    private fun single(m: ChatMessage, c: RowColors): RowView {
        val type = m.raw.str("type")
        return when {
            m.isSay("task") -> markdown("Task", m)
            m.isSay("user_feedback") -> markdown("You", m)
            m.isSay("text") || m.isSay("plan_completion_result") -> markdown(null, m)
            m.isSay("reasoning") -> markdown(null, m).copy(collapsedTitle = "Thinking")
            m.isSay("completion_result") || m.isAsk("completion_result") -> markdown("✓ Task completed", m)
            m.isSay("tool") || m.isAsk("tool") -> tool(m, c)
            m.isCommand() -> command(m, c)
            m.isAsk("followup") || m.isAsk("plan_mode_respond") -> question(m)
            m.isSay("error") -> error(m.text(), c)
            m.isAsk("api_req_failed") -> error(ErrorText.of(m), c)
            m.isAsk("mistake_limit_reached") -> error(m.text().ifEmpty { "Cline is having trouble. It made several mistakes in a row." }, c)
            m.isSay("api_req_started") -> requestProblem(m, c)
            else -> fallback(m, type, c)
        }
    }

    private fun markdown(title: String?, m: ChatMessage): RowView {
        val text = m.text()
        return RowView(MarkdownHtml.fileCandidates(text), { links ->
            (title?.let { "<p><b>${escape(it)}</b></p>" } ?: "") + MarkdownHtml.render(text, links)
        })
    }

    private fun question(m: ChatMessage): RowView {
        val q = AskQuestion.parse(m) ?: return fallback(m, m.raw.str("type"), RowColors())
        return RowView(MarkdownHtml.fileCandidates(q.text), { links -> MarkdownHtml.render(q.text, links) }, options = q.options)
    }

    private fun error(text: String, c: RowColors) = RowView(emptyList(), { colored(c.error, "<b>Error</b>") + "<pre>${escape(text)}</pre>" })

    private fun requestProblem(m: ChatMessage, c: RowColors): RowView {
        val info = ApiReqInfo.parse(m)
        val what = info.failure?.let { "Request failed: $it" } ?: info.cancelReason?.let { "Request cancelled ($it)" } ?: "Request status unknown"
        return RowView(emptyList(), { colored(c.error, escape(what)) })
    }

    private fun tool(m: ChatMessage, c: RowColors): RowView {
        val info = ToolInfo.parse(m) ?: return fallback(m, m.raw.str("type"), c)
        val past = m.isSay("tool")
        val reference = info.reference()
        return RowView(listOfNotNull(reference), { links ->
            val subject = pathHtml(info.path, reference, links)
            val body = when {
                info.tool == "editedExistingFile" && !info.diff.isNullOrEmpty() -> diffHtml(info.diff, c)
                (info.tool == "newFileCreated") && !info.content.isNullOrEmpty() -> previewHtml(info.content)
                else -> ""
            }
            "<p>${summaryHtml(info, past, subject)}</p>$body"
        })
    }

    private fun toolGroup(group: TranscriptItem.ToolGroup, c: RowColors): RowView {
        val infos = group.messages.map { it to ToolInfo.parse(it) }
        return RowView(infos.mapNotNull { it.second?.reference() }, { links ->
            infos.joinToString("") { (m, info) ->
                if (info == null) return@joinToString "<p>${escape(m.text())}</p>"
                "<p>${summaryHtml(info, m.isSay("tool"), pathHtml(info.path, info.reference(), links))}</p>"
            }
        })
    }

    // The summary text is built from known verbs; only the subject and the search term come from the model.
    private fun summaryHtml(info: ToolInfo, past: Boolean, subjectHtml: String): String =
        info.summary(past, SUBJECT).split(SUBJECT).joinToString(subjectHtml) { escape(it) }

    private fun pathHtml(path: String?, reference: String?, links: Map<String, FileLink>): String {
        val shown = path ?: return "<i>the project</i>"
        val label = "<code>${escape(shown)}</code>"
        return if (reference != null && reference in links) """<a href="${MarkdownHtml.hrefFor(reference)}">$label</a>""" else label
    }

    private fun diffHtml(diff: String, c: RowColors): String {
        val lines = diff.lines()
        val shown = lines.take(MAX_DIFF_LINES).joinToString("\n") { line ->
            val text = escape(line)
            when {
                line.startsWith("+") && !line.startsWith("+++") -> colored(c.added, text)
                line.startsWith("-") && !line.startsWith("---") -> colored(c.removed, text)
                else -> text
            }
        }
        val more = if (lines.size > MAX_DIFF_LINES) "\n" + colored(c.muted, "… ${lines.size - MAX_DIFF_LINES} more lines") else ""
        return "<pre>$shown$more</pre>"
    }

    private fun previewHtml(content: String): String {
        val lines = content.lines()
        val more = if (lines.size > MAX_PREVIEW_LINES) "\n… ${lines.size - MAX_PREVIEW_LINES} more lines" else ""
        return "<pre>${escape(lines.take(MAX_PREVIEW_LINES).joinToString("\n"))}${escape(more)}</pre>"
    }

    private fun command(m: ChatMessage, c: RowColors): RowView {
        val info = CommandInfo.parse(m)
        return RowView(emptyList(), {
            val output = info.output?.let { out ->
                val lines = out.lines()
                val kept = if (lines.size > MAX_OUTPUT_LINES) listOf(colored(c.muted, "… ${lines.size - MAX_OUTPUT_LINES} earlier lines")) + lines.takeLast(MAX_OUTPUT_LINES).map(::escape) else lines.map(::escape)
                "<pre>${kept.joinToString("\n")}</pre>"
            } ?: ""
            "<p><b>${if (m.isAsk("command")) "Cline wants to run" else "Cline ran"}</b></p><pre>${escape(info.command)}</pre>$output"
        })
    }

    private fun fallback(m: ChatMessage, type: String?, c: RowColors): RowView {
        val kind = m.raw.str(type ?: "say") ?: "?"
        val partial = if (m.raw.get("partial")?.let { it.isJsonPrimitive && it.asBoolean } == true) " · …" else ""
        return RowView(emptyList(), { colored(c.muted, "<small>${escape("${type ?: "say"} · $kind$partial")}</small>") + "<pre>${escape(prettyText(m.text()))}</pre>" })
    }

    /** JSON text is pretty-printed; anything else is kept as written. */
    fun prettyText(text: String): String {
        val trimmed = text.trim()
        if (!(trimmed.startsWith("{") || trimmed.startsWith("["))) return text
        return runCatching { GsonBuilder().setPrettyPrinting().create().toJson(JsonParser.parseString(trimmed)) }.getOrDefault(text)
    }

    private fun colored(color: String, html: String) = """<font color="$color">$html</font>"""

    private const val SUBJECT = "\u0000"

    fun escape(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
