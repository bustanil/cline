package bot.cline.jetbrains.ide

import bot.cline.jetbrains.chat.EditorCommands
import bot.cline.jetbrains.chat.EditorContext
import bot.cline.jetbrains.chat.EditorDiagnostic
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.util.Processor

/** Builds what the core needs from the editor: the selection, its lines, and the problems in it. */
object EditorContextBuilder {
    /**
     * With a selection, that selection. Without one, the caret's line: that is what
     * "Fix with Cline" on a problem uses when nothing is selected.
     */
    fun build(project: Project, editor: Editor): EditorContext? {
        val document = editor.document
        val file = FileDocumentManager.getInstance().getFile(document) ?: return null
        val selection = editor.selectionModel
        val (startOffset, endOffset) =
            if (selection.hasSelection()) {
                selection.selectionStart to selection.selectionEnd
            } else {
                val line = document.getLineNumber(editor.caretModel.offset)
                document.getLineStartOffset(line) to document.getLineEndOffset(line)
            }
        if (startOffset == endOffset) return null

        val start = editor.offsetToLogicalPosition(startOffset)
        val end = editor.offsetToLogicalPosition(endOffset)
        val (firstLine, lastLine) = EditorCommands.selectedLines(start.line, start.column, end.line, end.column)
        val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document)
        return EditorContext(
            filePath = file.path,
            selectedText = document.getText(com.intellij.openapi.util.TextRange(startOffset, endOffset)),
            language = (psiFile?.language?.id ?: file.fileType.name).lowercase(),
            startLine = firstLine,
            endLine = lastLine,
            diagnostics = diagnostics(project, editor, startOffset, endOffset)
                .filter { EditorCommands.touches(it, firstLine - 1, lastLine - 1) },
        )
    }

    /** Errors and warnings the IDE has already found between the two offsets. */
    fun diagnostics(project: Project, editor: Editor, startOffset: Int, endOffset: Int): List<EditorDiagnostic> {
        val document = editor.document
        val found = mutableListOf<EditorDiagnostic>()
        DaemonCodeAnalyzerEx.processHighlights(
            document, project, HighlightSeverity.WARNING, startOffset, endOffset,
            Processor<HighlightInfo> { info ->
                toDiagnostic(editor, info)?.let { found += it }
                true
            },
        )
        return found.distinct()
    }

    private fun toDiagnostic(editor: Editor, info: HighlightInfo): EditorDiagnostic? {
        val message = info.description?.takeIf { it.isNotBlank() } ?: return null
        val severity = when {
            info.severity >= HighlightSeverity.ERROR -> EditorDiagnostic.Severity.ERROR
            info.severity >= HighlightSeverity.WARNING -> EditorDiagnostic.Severity.WARNING
            else -> return null
        }
        val start = editor.offsetToLogicalPosition(info.startOffset)
        val end = editor.offsetToLogicalPosition(info.endOffset)
        return EditorDiagnostic(message, start.line, start.column, end.line, end.column, severity, "IntelliJ")
    }
}
