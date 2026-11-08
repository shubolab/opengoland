package dev.openidea.goide.generate.tags

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.TokenType
import dev.openidea.goide.lang.GoLanguage
import dev.openidea.goide.lang.GoLexer
import dev.openidea.goide.lang.GoTokens

class GoStructTagsAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(event: AnActionEvent) {
        val file = event.getData(CommonDataKeys.PSI_FILE)
        val editor = event.getData(CommonDataKeys.EDITOR)
        event.presentation.isEnabledAndVisible = file != null && editor != null && editable(file, editor)
    }
    override fun actionPerformed(event: AnActionEvent) {
        val file = event.getData(CommonDataKeys.PSI_FILE) ?: return
        val editor = event.getData(CommonDataKeys.EDITOR) ?: return
        if (editable(file, editor)) GoStructTagsService.get(file.project).invoke(file, editor)
    }
}

/** Lexical availability only; background Go analysis owns the actual field selection. */
class GoStructTagsIntention : IntentionAction {
    override fun getText() = "Edit struct tags…"
    override fun getFamilyName() = "Go struct tags"
    override fun startInWriteAction() = false
    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean =
        editor != null && file != null && editable(file, editor) && GoStructTagContext.contains(editor.document.immutableCharSequence, editor.caretModel.offset)
    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        if (editor != null && file != null && isAvailable(project, editor, file)) GoStructTagsService.get(project).invoke(file, editor)
    }
}

private fun editable(file: PsiFile, editor: Editor): Boolean =
    !file.project.isDisposed && file.isValid && file.language == GoLanguage && !editor.isDisposed && !editor.isViewer &&
        file.virtualFile?.let { it.isValid && it.isInLocalFileSystem && it.isWritable } == true

/** Strings/comments never participate in brace matching. All offsets are native UTF-16. */
internal object GoStructTagContext {
    fun contains(source: CharSequence, offset: Int): Boolean {
        if (offset < 0 || offset > source.length) return false
        val lexer = GoLexer()
        lexer.start(source)
        val braces = ArrayDeque<Boolean>()
        var structDepth = 0
        var pending = false
        var count = 0
        while (lexer.tokenType != null && lexer.tokenStart <= offset) {
            if (++count % 256 == 0) ProgressManager.checkCanceled()
            val kind = lexer.tokenType
            val keyword = kind == GoTokens.KEYWORD && source.subSequence(lexer.tokenStart, lexer.tokenEnd).toString() == "struct"
            if (offset < lexer.tokenEnd) return structDepth > 0 || keyword || pending && kind == GoTokens.LBRACE
            if (kind != TokenType.WHITE_SPACE && kind != GoTokens.COMMENT) {
                when (kind) {
                    GoTokens.LBRACE -> { braces.addLast(pending); if (pending) structDepth++ }
                    GoTokens.RBRACE -> if (braces.removeLastOrNull() == true) structDepth--
                }
                pending = keyword
            }
            lexer.advance()
        }
        return structDepth > 0
    }
}
