package bot.cline.jetbrains.ide

import bot.cline.jetbrains.chat.EditorCommand
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile

/** Alt+Enter on an error or warning: "Fix with Cline". */
class FixWithClineIntention : IntentionAction, DumbAware {
    override fun getText() = "Fix with Cline"

    override fun getFamilyName() = "Cline Native"

    override fun startInWriteAction() = false

    override fun generatePreview(project: Project, editor: Editor, file: PsiFile) = IntentionPreviewInfo.EMPTY

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        if (editor == null || file == null) return false
        val line = editor.document.getLineNumber(editor.caretModel.offset)
        val start = editor.document.getLineStartOffset(line)
        val end = editor.document.getLineEndOffset(line)
        return EditorContextBuilder.diagnostics(project, editor, start, end).isNotEmpty()
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        if (editor == null) return
        ClineEditorAction.send(project, editor, EditorCommand.FIX)
    }
}
