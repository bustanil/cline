package bot.cline.jetbrains.ide

import bot.cline.jetbrains.bridge.CoreApi
import bot.cline.jetbrains.bridge.Subscription
import bot.cline.jetbrains.chat.ChatMessage
import bot.cline.jetbrains.chat.FileLinks
import bot.cline.jetbrains.chat.Transcript
import bot.cline.jetbrains.chat.TranscriptItem
import com.google.gson.JsonObject
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import java.awt.Dimension
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.CompletableFuture
import javax.imageio.ImageIO
import javax.swing.JComponent

class ActivityRowTest : BasePlatformTestCase() {
    private class Core : CoreApi {
        override fun call(service: String, method: String, messageJson: String) = CompletableFuture.completedFuture("""{"path":""}""")
        override fun subscribe(service: String, method: String, messageJson: String, onMessage: (String) -> Unit, onClose: (Throwable?) -> Unit) = Subscription {}
    }

    private fun msg(ts: Long, type: String, kind: String, text: String) =
        ChatMessage(JsonObject().apply { addProperty("ts", ts); addProperty("type", type); addProperty(type, kind); addProperty("text", text) })

    private val messages = listOf(
        msg(1, "say", "command", "python3 scripts/make_report.py --verbose"),
        msg(2, "say", "command_output", "report written\n42 rows"),
        msg(3, "say", "command", "sed -i '' 's/old/new/' src/Main.java"),
        msg(4, "say", "tool", """{"tool":"readFile","path":"src/main/java/App.java","readLineStart":1,"readLineEnd":40}"""),
        msg(5, "say", "command", "cd /work/project && ./gradlew :plugin:test --console=plain 2>&1 | tail -20"),
        msg(6, "say", "command_output", "BUILD SUCCESSFUL in 24s\ntests=7 failures=0"),
    )

    private fun activity(phase: String?): TranscriptItem.Activity = Transcript.build(messages, phase).filterIsInstance<TranscriptItem.Activity>().single()

    private fun row(phase: String?) = ActivityRow(activity(phase), RowContext(FileLinks(Core())))

    private fun layoutAll(c: java.awt.Component) {
        if (c is java.awt.Container) {
            c.invalidate()
            c.doLayout()
            c.components.forEach(::layoutAll)
        }
    }

    private fun paint(component: JComponent, name: String) {
        val dir = System.getenv("CLINE_SNAPSHOT_DIR") ?: return
        UIUtil.dispatchAllInvocationEvents()
        val width = 760
        // A row's height depends on its width, so lay out again until the height stops changing.
        var height = 10
        repeat(8) {
            component.setSize(width, height)
            layoutAll(component)
            height = component.getPreferredSize().height.coerceAtLeast(30)
        }
        component.setSize(width, height)
        layoutAll(component)
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.color = ChatColors.page
        g.fillRect(0, 0, width, height)
        g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        component.paint(g)
        g.dispose()
        File(dir).mkdirs()
        ImageIO.write(image, "png", File(dir, "$name.png"))
    }

    fun `test a finished activity folds under its header`() {
        val r = row("idle")
        assertEquals("Ran 4 steps", r.title)
        assertFalse(r.isGroupOpen)
        r.toggleGroup()
        assertTrue(r.isGroupOpen)
        val lines = r.stepPanels().map { it.summary.replace(Regex("\\s+"), " ") }
        assertEquals(listOf("Ran python3 scripts/make_report.py --verbose", "Ran sed -i '' 's/old/new/' src/Main.java", "Read App.java"), lines.take(3))
        assertTrue(lines[3], lines[3].startsWith("Ran cd /work/project && ./gradlew") && lines[3].endsWith("…"))
    }

    fun `test a running activity is open and its last step says Running`() {
        val r = row("streaming")
        assertEquals("Running 4 steps", r.title)
        assertTrue(r.isGroupOpen)
        val steps = r.stepPanels()
        assertTrue(steps.last().isOpen)
        assertTrue(steps.last().summary, steps.last().summary.startsWith("Running"))
        assertFalse(steps.first().isOpen)
        assertTrue(steps.first().summary.startsWith("Ran "))
    }

    fun `test opening a step shows the command and its output`() {
        val r = row("idle")
        r.toggleGroup()
        val first = r.stepPanels().first()
        first.toggle()
        assertTrue(first.isOpen)
        val text = first.html.replace("<wbr>", "")
        assertTrue(first.html, text.contains("make_report.py") && text.contains("report written"))
        assertEquals("Ran a command", first.summary)
    }

    fun `test the user's choice survives an update and the running step finishes`() {
        val r = row("streaming")
        r.stepPanels().first().toggle()
        r.toggleGroup() // closes the group the user was watching
        assertFalse(r.isGroupOpen)
        assertTrue(r.update(activity("idle")))
        assertFalse(r.isGroupOpen)
        assertEquals("Ran 4 steps", r.title)
        assertTrue(r.stepPanels().first().isOpen)
    }

    fun `test a lone step has no header and is open`() {
        val r = ActivityRow(Transcript.build(listOf(messages[0], messages[1]), "idle").filterIsInstance<TranscriptItem.Activity>().single(), RowContext(FileLinks(Core())))
        assertTrue(r.isGroupOpen)
        assertEquals("Ran a command", r.title)
        assertFalse(r.update(TranscriptItem.Single(messages[0])))
    }

    private val javaReply = """
        **3. Execution** (`DefaultPaymentService.callAcquirer`)

        ```java
        if (type == PURCHASE && acquirer instanceof PurchaseAcquirer purchaser) {
            ... purchaser.purchase(...)                       // single call
        }

        AuthCaptureAcquirer authCapturer = (AuthCaptureAcquirer) acquirer; // safe: router guaranteed it
        AcquirerResult authorization = authCapturer.authorize(id, money, card);
        if (type == AUTHORIZE || authorization instanceof Declined) {
            return record(id, authorization, Payment::authorize);
        }
        String s = "text"; int n = 42; return n > 0 ? s : null;
        ```

        The unchecked cast is justified by the router contract: the router only returned this acquirer because it *is* an `AuthCaptureAcquirer`.
    """.trimIndent()

    fun `test a java block is highlighted in the light scheme`() {
        val html = IdeCodeHighlighter(project).highlight("java", "int n = 42; // note\nString s = \"x\";")!!
        assertTrue(html, Regex("<font color=\"#[0-9a-fA-F]{6}\">").containsMatchIn(html))
        assertTrue(html, html.contains("<b>") && html.contains("int"))
        assertTrue("comments are italic: $html", html.contains("<i>"))
        assertNull(IdeCodeHighlighter(project).highlight("brainfudge", "+++"))
        assertNull(IdeCodeHighlighter(project).highlight(null, "x"))
        assertTrue(IdeCodeHighlighter(project).highlight("diff", "-a\n+b")!!.contains("</font>"))
    }

    fun `test snapshot of tables`() {
        val table = """
            | Domain method | REST call | `fake-acquirer` resource | Behavior here |
            |---|---|---|---|
            | `authorize` | `POST /authorize` | `AuthorizeResource` | Card number decides; `4000...9995` → INSUFFICIENT_FUNDS |
            | `capture` | `POST /capture` | `CaptureResource` | Always approved |
            | `voidAuthorization` | `POST /void` | `VoidResource` | Always approved (stub, no idempotency) |
            | *(no interface method)* | `POST /refund` | `RefundResource` | Always approved (stub — not modeled in the domain yet) |

            | Your need | Where it lives | Native with a webview chat? |
            |---|---|---|
            | Send snippets to the agent | Editor actions, Alt+Enter intention | ✅ IntelliJ actions |
            | Jump from the agent to the code | `showTextDocument` in the plugin | ✅ `OpenFileDescriptor` |
            | Doesn't look ugly | The chat panel | Fixable: map IntelliJ's colors to the `--vscode-*` variables it uses |
        """.trimIndent()
        val message = ChatMessage(JsonObject().apply { addProperty("ts", 8); addProperty("type", "say"); addProperty("say", "text"); addProperty("text", table) })
        paint(HtmlRow(TranscriptItem.Single(message), RowContext(FileLinks(Core()), highlighter = IdeCodeHighlighter(project))).component, "markdown-tables")
    }

    fun `test snapshot of a markdown reply with highlighted code`() {
        val message = ChatMessage(JsonObject().apply { addProperty("ts", 9); addProperty("type", "say"); addProperty("say", "text"); addProperty("text", javaReply) })
        val ctx = RowContext(FileLinks(Core()), highlighter = IdeCodeHighlighter(project))
        paint(HtmlRow(TranscriptItem.Single(message), ctx).component, "markdown-code")
    }

    fun `test snapshots of the row`() {
        paint(row("idle").also { it.toggleGroup() }.component, "activity-open")
        paint(row("idle").component, "activity-folded")
        val running = row("streaming")
        running.stepPanels()[1].toggle()
        paint(running.component, "activity-running")
        val one = ActivityRow(Transcript.build(listOf(messages[0], messages[1]), "idle").filterIsInstance<TranscriptItem.Activity>().single(), RowContext(FileLinks(Core())))
        one.stepPanels().single().toggle()
        paint(one.component, "activity-single-open")
    }
}
