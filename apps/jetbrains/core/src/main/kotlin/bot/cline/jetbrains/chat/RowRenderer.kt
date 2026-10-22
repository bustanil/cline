package bot.cline.jetbrains.chat

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser

/** Colors for the HTML, as `#rrggbb`. The IDE passes ones that suit the current theme. */
data class RowColors(
    val added: String = "#2e9e4f",
    val removed: String = "#d03b3b",
    val muted: String = "#808080",
    val error: String = "#d03b3b",
    val codeBackground: String = "#ffffff",
    val codeBorder: String = "#e3e2e2",
    val tableHeader: String = "#f7f6f4",
)

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
    /** For Markdown rows: the same content as separate text, code and table blocks, so the IDE can draw cards. */
    val blocks: ((resolved: Map<String, FileLink>) -> List<MarkdownHtml.Block>)? = null,
    /** True for what the user wrote: drawn as a rounded bubble on the right, with no heading. */
    val userBubble: Boolean = false,
    /** The images and files that went with a prompt. */
    val attachments: List<Attachment> = emptyList(),
    /** The quotes a sent prompt carried, drawn as cards above its text. */
    val quotes: List<QuoteEntry> = emptyList(),
    /** False when the prompt was only quotes and replies, so there is no text of its own to draw. */
    val hasText: Boolean = true,
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

    fun render(item: TranscriptItem, colors: RowColors = RowColors(), highlighter: CodeHighlighter? = null): RowView = when (item) {
        is TranscriptItem.Activity -> single(item.messages.first(), colors, highlighter)
        is TranscriptItem.Single -> single(item.message, colors, highlighter)
    }

    private fun single(m: ChatMessage, c: RowColors, highlighter: CodeHighlighter?): RowView {
        val type = m.raw.str("type")
        return when {
            m.isSay("task") || m.isSay("user_feedback") -> {
                val (quotes, rest) = Quote.split(m.text())
                markdown(null, if (quotes.isEmpty()) m else m.withText(rest), c, highlighter).copy(userBubble = true, attachments = attachmentsOf(m), quotes = quotes, hasText = quotes.isEmpty() || rest.isNotBlank())
            }
            m.isSay("text") || m.isSay("plan_completion_result") -> markdown(null, m, c, highlighter)
            m.isSay("reasoning") -> markdown(null, m, c, highlighter).copy(collapsedTitle = "Thinking")
            m.isSay("completion_result") || m.isAsk("completion_result") -> markdown("✓ Task completed", m, c, highlighter)
            m.isSay("tool") || m.isAsk("tool") -> tool(m, c)
            m.isUseMcp() -> McpInfo.parse(m).let { info -> RowView(emptyList(), { mcpHtml(info, c, asking = m.isAsk("use_mcp_server")) }) }
            m.isCommand() -> command(m, c)
            m.isAsk("followup") || m.isAsk("plan_mode_respond") -> question(m, c, highlighter)
            m.isSay("error") -> error(m.text(), c)
            m.isAsk("api_req_failed") -> error(ErrorText.of(m), c)
            m.isAsk("mistake_limit_reached") -> error(m.text().ifEmpty { "Cline is having trouble. It made several mistakes in a row." }, c)
            m.isSay("api_req_started") -> requestProblem(m, c)
            else -> fallback(m, type, c)
        }
    }

    /** The `images` (data URLs) and `files` (paths) of a prompt, as attachments. */
    internal fun attachmentsOf(m: ChatMessage): List<Attachment> {
        val images = m.raw.getAsJsonArray("images")?.mapIndexedNotNull { i, e -> e.takeIf { it.isJsonPrimitive }?.asString?.let { Attachment(Attachment.Kind.IMAGE, "Image ${i + 1}", it, it.length.toLong()) } }.orEmpty()
        val files = m.raw.getAsJsonArray("files")?.mapNotNull { e -> e.takeIf { it.isJsonPrimitive }?.asString?.let { Attachment(Attachment.Kind.FILE, it.substringAfterLast('/').substringAfterLast('\\'), it, 0) } }.orEmpty()
        return images + files
    }

    private fun codeStyle(c: RowColors, highlighter: CodeHighlighter?) = CodeBlockStyle(c.codeBackground, c.codeBorder, c.tableHeader, highlighter)

    private fun markdown(title: String?, m: ChatMessage, c: RowColors, highlighter: CodeHighlighter?): RowView {
        val text = m.text()
        val heading = title?.let { listOf(MarkdownHtml.Block(MarkdownHtml.BlockKind.TEXT, "<p><b>${escape(it)}</b></p>")) }.orEmpty()
        return RowView(
            MarkdownHtml.fileCandidates(text),
            { links -> (title?.let { "<p><b>${escape(it)}</b></p>" } ?: "") + MarkdownHtml.render(text, links, codeStyle(c, highlighter)) },
            blocks = { links -> heading + MarkdownHtml.blocks(text, links, codeStyle(c, highlighter)) },
        )
    }

    private fun question(m: ChatMessage, c: RowColors, highlighter: CodeHighlighter?): RowView {
        val q = AskQuestion.parse(m) ?: return fallback(m, m.raw.str("type"), c)
        return RowView(
            MarkdownHtml.fileCandidates(q.text),
            { links -> MarkdownHtml.render(q.text, links, codeStyle(c, highlighter)) },
            options = q.options,
            blocks = { links -> MarkdownHtml.blocks(q.text, links, codeStyle(c, highlighter)) },
        )
    }

    private fun error(text: String, c: RowColors) = RowView(emptyList(), { colored(c.error, "<b>Error</b>") + "<pre>${escape(text)}</pre>" })

    private fun requestProblem(m: ChatMessage, c: RowColors): RowView {
        val info = ApiReqInfo.parse(m)
        val what = info.failure?.let { "Request failed: $it" } ?: info.cancelReason?.let { "Request cancelled ($it)" } ?: "Request status unknown"
        return RowView(emptyList(), { colored(c.error, escape(what)) })
    }

    internal fun tool(m: ChatMessage, c: RowColors): RowView {
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

    // The summary text is built from known verbs; only the subject and the search term come from the model.
    private fun summaryHtml(info: ToolInfo, past: Boolean, subjectHtml: String): String =
        info.summary(past, SUBJECT).split(SUBJECT).joinToString(subjectHtml) { escape(it) }

    private fun pathHtml(path: String?, reference: String?, links: Map<String, FileLink>): String {
        val shown = path ?: return "the project"
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

    internal fun command(m: ChatMessage, c: RowColors): RowView {
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

    /** An MCP call: which server and tool, the arguments, and the answer once there is one. */
    internal fun mcpHtml(info: McpInfo, c: RowColors, asking: Boolean): String {
        val what = if (info.tool != null) "tool <b>${escape(info.tool)}</b>" else "resource <b>${escape(info.uri ?: "")}</b>"
        val head = "<p><b>${if (asking) "Cline wants to use" else "Cline used"}</b> $what on <b>${escape(info.server)}</b></p>"
        val args = info.arguments?.takeIf { it.isNotBlank() }?.let { "<p>${colored(c.muted, "Arguments")}</p><pre>${escape(clip(prettyText(it)))}</pre>" }.orEmpty()
        val response = info.response?.takeIf { it.isNotBlank() }?.let { "<p>${colored(c.muted, "Response")}</p><pre>${escape(clip(prettyText(it)))}</pre>" }.orEmpty()
        return head + args + response
    }

    /** Keeps the first lines of a long text; a server's answer can be thousands of lines. */
    private fun clip(text: String): String {
        val lines = text.lines()
        return if (lines.size <= MAX_OUTPUT_LINES) text else lines.take(MAX_OUTPUT_LINES).joinToString("\n") + "\n… ${lines.size - MAX_OUTPUT_LINES} more lines"
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
