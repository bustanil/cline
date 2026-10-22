package bot.cline.jetbrains.chat

/** One step in an activity, such as a command or a file read. */
data class ActivityStep(
    /** Stable, so the UI can keep a step open while it updates. */
    val key: Long,
    /** True while this step is in progress: "Running", not "Ran". */
    val running: Boolean,
    /** `Ran`, `Running`, `Read`, `Reading`, ... */
    val verb: String,
    /** What the step acted on, short enough for one line: the command's first words, or a file name. */
    val target: String,
    /** The heading when the step is open: `Ran a command`, `Read a.kt`. */
    val openTitle: String,
    /** True for a shell command, whose detail is drawn as a terminal block. */
    val isCommand: Boolean,
    val references: List<String>,
    /** True for the model's thinking, which the header does not count as a step. */
    val isThinking: Boolean = false,
    /** The step's detail: the command and its output, a diff, or a preview. */
    val detailHtml: (resolved: Map<String, FileLink>) -> String,
)

/** What to draw for an [TranscriptItem.Activity]. */
data class ActivityView(val title: String, val running: Boolean, val steps: List<ActivityStep>)

/**
 * Turns a run of commands and tool calls into rows of "Ran python3…" with a
 * header such as "Ran 3 commands". A step in progress says "Running", so the
 * user can see what Cline is doing now.
 */
object ActivityRenderer {
    private const val MAX_TARGET = 48

    fun render(activity: TranscriptItem.Activity, colors: RowColors = RowColors()): ActivityView {
        val last = activity.messages.lastIndex
        val steps = activity.messages.mapIndexed { index, m -> step(m, running = activity.running && index == last, colors) }
        return ActivityView(title(steps, activity.running), activity.running, steps)
    }

    private fun step(m: ChatMessage, running: Boolean, colors: RowColors): ActivityStep {
        if (m.isSay("reasoning")) {
            val text = m.text()
            val verb = if (running) "Thinking" else "Thought"
            return ActivityStep(m.ts, running, verb, "", verb, false, MarkdownHtml.fileCandidates(text), isThinking = true) { links -> MarkdownHtml.render(text, links) }
        }
        if (m.isCommand()) {
            val info = CommandInfo.parse(m)
            val first = info.command.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            val target = if (first.length > MAX_TARGET || info.command.trim().lines().size > 1) first.take(MAX_TARGET).trimEnd() + "…" else first
            val verb = if (running) "Running" else "Ran"
            return ActivityStep(m.ts, running, verb, target, "$verb a command", true, emptyList()) { commandHtml(info, colors) }
        }
        if (m.isUseMcp()) {
            val info = McpInfo.parse(m)
            val verb = if (running) "Using" else "Used"
            val target = info.subject + if (info.server.isNotEmpty()) " · ${info.server}" else ""
            return ActivityStep(m.ts, running, verb, target, "$verb ${info.subject}", false, emptyList()) { RowRenderer.mcpHtml(info, colors, asking = false) }
        }
        val info = ToolInfo.parse(m)
        val (now, done) = verbs(info?.tool)
        val verb = if (running) now else done
        val subject = info?.path?.substringAfterLast('/')?.takeIf { it.isNotEmpty() } ?: info?.regex ?: info?.path ?: "the project"
        val view = RowRenderer.tool(m, colors)
        return ActivityStep(m.ts, running, verb, subject, "$verb $subject", false, view.references) { links -> view.html(links) }
    }

    private fun commandHtml(info: CommandInfo, c: RowColors): String {
        val output = info.output?.let { out ->
            val lines = out.lines()
            val kept = if (lines.size > MAX_OUTPUT_LINES) listOf("<font color=\"${c.muted}\">… ${lines.size - MAX_OUTPUT_LINES} earlier lines</font>") + lines.takeLast(MAX_OUTPUT_LINES).map(RowRenderer::escape) else lines.map(RowRenderer::escape)
            "<pre><font color=\"${c.muted}\">${kept.joinToString("\n")}</font></pre>"
        }.orEmpty()
        return "<pre><font color=\"${c.muted}\">$ </font>${RowRenderer.escape(info.command)}</pre>$output"
    }

    private const val MAX_OUTPUT_LINES = 40

    /** The verb in progress and in the past for a tool. */
    private fun verbs(tool: String?): Pair<String, String> = when (tool) {
        "readFile" -> "Reading" to "Read"
        "listFilesTopLevel", "listFilesRecursive" -> "Listing" to "Listed"
        "listCodeDefinitionNames" -> "Listing" to "Listed"
        "searchFiles" -> "Searching" to "Searched"
        "editedExistingFile" -> "Editing" to "Edited"
        "newFileCreated" -> "Creating" to "Created"
        "fileDeleted" -> "Deleting" to "Deleted"
        "webFetch" -> "Fetching" to "Fetched"
        "webSearch" -> "Searching" to "Searched"
        else -> "Using" to "Used"
    }

    /** `Ran 3 commands`, `Read 2 files`, `Running 4 steps`. */
    internal fun title(allSteps: List<ActivityStep>, running: Boolean): String {
        val steps = allSteps.filterNot { it.isThinking }
        val n = steps.size
        val commands = steps.count { it.isCommand }
        val reads = steps.count { it.verb in setOf("Read", "Reading") }
        return when {
            commands == n -> (if (running) "Running " else "Ran ") + if (n == 1) "a command" else "$n commands"
            reads == n -> (if (running) "Reading " else "Read ") + if (n == 1) "a file" else "$n files"
            else -> (if (running) "Running " else "Ran ") + if (n == 1) "a step" else "$n steps"
        }
    }
}
