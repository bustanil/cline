package bot.cline.jetbrains.ide

import bot.cline.jetbrains.chat.ChatMessage
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

/** One row of the transcript. [update] runs when a newer copy of the message arrives. */
interface ChatRow {
    val component: JComponent

    fun update(message: ChatMessage)
}

object ChatRows {
    /** Picks the row for a message. A kind without a dedicated row gets the plain-text fallback. */
    fun create(message: ChatMessage): ChatRow = PlainTextRow(message)
}

/**
 * The fallback row: the kind as a small label, then the text, pretty-printed
 * when it is JSON. It never throws, so no message can break the chat.
 */
class PlainTextRow(message: ChatMessage) : ChatRow {
    private val label = JBLabel().apply { foreground = UIUtil.getContextHelpForeground(); font = JBUI.Fonts.smallFont() }
    private val body = JBTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        isOpaque = false
        border = JBUI.Borders.empty()
    }
    override val component: JComponent = JPanel(BorderLayout()).apply {
        isOpaque = false
        border = JBUI.Borders.empty(6, 10)
        add(label, BorderLayout.NORTH)
        add(body, BorderLayout.CENTER)
    }

    init {
        update(message)
    }

    override fun update(message: ChatMessage) {
        label.text = kindLabel(message)
        body.text = prettyText(message.raw.get("text")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty())
        component.revalidate()
        component.repaint()
    }

    companion object {
        private val pretty = GsonBuilder().setPrettyPrinting().create()

        fun kindLabel(message: ChatMessage): String {
            val raw = message.raw
            val type = raw.get("type")?.asString ?: "say"
            val kind = raw.get(type)?.takeIf { it.isJsonPrimitive }?.asString ?: "?"
            return if (raw.get("partial")?.let { it.isJsonPrimitive && it.asBoolean } == true) "$type · $kind · …" else "$type · $kind"
        }

        fun prettyText(text: String): String {
            val trimmed = text.trim()
            if (!(trimmed.startsWith("{") || trimmed.startsWith("["))) return text
            return runCatching { pretty.toJson(JsonParser.parseString(trimmed)) }.getOrDefault(text)
        }
    }
}
