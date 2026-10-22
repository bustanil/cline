package bot.cline.jetbrains.ide

import bot.cline.jetbrains.chat.CodeHighlighter
import bot.cline.jetbrains.chat.RowRenderer
import com.intellij.lang.Language
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.ui.ColorUtil
import java.awt.Font
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor

/**
 * Highlights code blocks with the IDE's own lexers, so Java, Kotlin, JSON, XML and the other
 * languages the IDE has get real token colors. The colors come from a fixed scheme, not the
 * user's editor scheme: the light "Default" scheme on a light IDE, "Darcula" on a dark one,
 * so a snippet reads the same as the box around it.
 */
class IdeCodeHighlighter(private val project: Project, private val scheme: () -> EditorColorsScheme = ::defaultScheme) : CodeHighlighter {
    override fun highlight(language: String?, code: String): String? {
        if (language != null && language.lowercase() in DIFF) return diff(code)
        val lang = findLanguage(language) ?: return null
        return runReadAction { tokens(lang, code, scheme()) }
    }

    /** Runs the language's lexer over [code] and colors each token as [scheme] colors it. Null if the language has no highlighter. */
    private fun tokens(lang: Language, code: String, scheme: EditorColorsScheme): String? {
        val highlighter = SyntaxHighlighterFactory.getSyntaxHighlighter(lang, project, null) ?: return null
        val lexer = highlighter.highlightingLexer
        lexer.start(code)
        val out = StringBuilder()
        while (true) {
            val type = lexer.tokenType ?: break
            val text = RowRenderer.escape(code.substring(lexer.tokenStart, lexer.tokenEnd))
            val attributes = highlighter.getTokenHighlights(type).mapNotNull { scheme.getAttributes(it) }
            val color = attributes.firstNotNullOfOrNull { it.foregroundColor }
            val bold = attributes.any { it.fontType and Font.BOLD != 0 }
            val italic = attributes.any { it.fontType and Font.ITALIC != 0 }
            var piece = text
            if (italic) piece = "<i>$piece</i>"
            if (bold) piece = "<b>$piece</b>"
            out.append(if (color != null) """<font color="${ColorUtil.toHtmlColor(color)}">$piece</font>""" else piece)
            lexer.advance()
        }
        return out.toString()
    }

    /** `+` and `-` lines in the colors a diff has everywhere. */
    private fun diff(code: String): String = code.lines().joinToString("\n") { line ->
        val text = RowRenderer.escape(line)
        when {
            line.startsWith("+") && !line.startsWith("+++") -> """<font color="${if (JBColor.isBright()) "#2e9e4f" else "#6cc17e"}">$text</font>"""
            line.startsWith("-") && !line.startsWith("---") -> """<font color="${if (JBColor.isBright()) "#d03b3b" else "#e06c6c"}">$text</font>"""
            line.startsWith("@@") -> """<font color="${if (JBColor.isBright()) "#808080" else "#9a9a9a"}">$text</font>"""
            else -> text
        }
    }

    companion object {
        private val DIFF = setOf("diff", "patch")

        private val aliases = mapOf(
            "kt" to "kotlin", "kts" to "kotlin", "js" to "javascript", "ts" to "typescript", "py" to "python",
            "sh" to "shell script", "bash" to "shell script", "zsh" to "shell script", "shell" to "shell script",
            "yml" to "yaml", "md" to "markdown", "htm" to "html", "xhtml" to "html", "c++" to "c++", "cs" to "c#",
        )

        fun findLanguage(name: String?): Language? {
            val wanted = (aliases[name?.lowercase()] ?: name?.lowercase()) ?: return null
            if (wanted.isEmpty()) return null
            return Language.getRegisteredLanguages().firstOrNull { it.id.equals(wanted, true) || it.displayName.equals(wanted, true) }
                // Dialects such as "TEXT" or "Plain text" carry no tokens worth coloring.
                ?.takeIf { it.id != "TEXT" && it.id != "any" }
        }

        fun defaultScheme(): EditorColorsScheme {
            val manager = EditorColorsManager.getInstance()
            return (if (JBColor.isBright()) manager.getScheme("Default") else manager.getScheme("Darcula")) ?: manager.globalScheme
        }
    }
}
