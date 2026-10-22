package bot.cline.jetbrains.ide

import bot.cline.jetbrains.chat.ChatMessage
import bot.cline.jetbrains.chat.FileLink
import bot.cline.jetbrains.chat.MarkdownHtml
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.ui.components.JBHtmlPane
import com.intellij.ui.components.JBHtmlPaneConfiguration
import com.intellij.ui.components.JBHtmlPaneStyleConfiguration
import com.intellij.util.ui.JBUI
import javax.swing.JComponent
import javax.swing.event.HyperlinkEvent

/**
 * A row drawn from Markdown. Inline code that names an existing file becomes
 * a link; the core decides which paths exist, so the links appear a moment
 * after the text.
 */
class MarkdownRow(message: ChatMessage, private val context: RowContext) : ChatRow {
    private val pane = JBHtmlPane(JBHtmlPaneStyleConfiguration.builder().build(), JBHtmlPaneConfiguration.builder().build()).apply {
        isEditable = false
        isOpaque = false
        border = JBUI.Borders.empty(6, 10)
        addHyperlinkListener { event -> if (event.eventType == HyperlinkEvent.EventType.ACTIVATED) openLink(event.description ?: event.url?.toString()) }
    }
    override val component: JComponent get() = pane

    private val resolved = HashMap<String, FileLink>()
    private val asked = HashSet<String>()
    private var markdown = ""

    init {
        update(message)
    }

    override fun update(message: ChatMessage) {
        val text = message.raw.get("text")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        if (text == markdown && pane.text.isNotEmpty()) return
        markdown = text
        draw()
        // Streaming text changes often; ask the core only about paths not asked about yet.
        for (candidate in MarkdownHtml.fileCandidates(text)) {
            if (!asked.add(candidate)) continue
            context.fileLinks.resolve(candidate).whenComplete { link, _ ->
                if (link != null) {
                    ApplicationManager.getApplication().invokeLater({
                        resolved[candidate] = link
                        draw()
                    }, ModalityState.any())
                }
            }
        }
    }

    private fun draw() {
        pane.text = MarkdownHtml.render(markdown, resolved)
        pane.revalidate()
        pane.repaint()
    }

    internal fun openLink(href: String?) {
        if (href == null) return
        val reference = MarkdownHtml.referenceOf(href)
        if (reference != null) {
            resolved[reference]?.let { context.fileLinks.open(it) }
        } else if (href.startsWith("http://") || href.startsWith("https://")) {
            BrowserUtil.browse(href)
        }
    }

    /** The HTML now shown; for tests. */
    internal val html: String get() = pane.text
}
