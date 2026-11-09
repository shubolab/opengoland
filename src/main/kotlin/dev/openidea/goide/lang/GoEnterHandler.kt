package dev.openidea.goide.lang

import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.editorActions.enter.EnterBetweenBracesNoCommitDelegate
import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegate
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.EditorActionHandler
import com.intellij.openapi.project.Project
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType
import dev.openidea.goide.settings.GoProjectSettings

/** Literal Enter preserves content; raw strings optionally continue their exact leading whitespace. */
class GoEnterHandler : EnterHandlerDelegate {
    override fun preprocessEnter(file: PsiFile, editor: Editor, caretOffset: Ref<Int>, caretAdvance: Ref<Int>, dataContext: DataContext, originalHandler: EditorActionHandler?): EnterHandlerDelegate.Result {
        if (file.language != GoLanguage) return EnterHandlerDelegate.Result.Continue
        val source = editor.document.charsSequence
        val context = GoIndentContext(source)
        val token = context.protectedTokenAt(caretOffset.get())
        if (token?.type != GoTokens.STRING) return EnterHandlerDelegate.Result.Continue
        val offset = caretOffset.get()
        val indentation = if (token.value.startsWith('`') && CodeInsightSettings.getInstance().SMART_INDENT_ON_ENTER && GoProjectSettings.get(file.project).indentRawStringsOnEnter) {
            val start = context.lineStart(offset)
            var end = start
            while (end < offset && source[end] in " \t") end++
            source.subSequence(start, end).toString()
        } else ""
        editor.document.insertString(offset, "\n$indentation")
        editor.caretModel.currentCaret.moveToOffset(offset + 1 + indentation.length)
        return EnterHandlerDelegate.Result.Stop
    }
}

/** Reuse the platform's pair split and caret handling without forcing a PSI commit. */
class GoEnterBetweenBracesDelegate : EnterBetweenBracesNoCommitDelegate() {
    override fun isCommentType(type: IElementType) = type == GoTokens.COMMENT
    override fun isBracePair(left: Char, right: Char) = left == '{' && right == '}' || left == '(' && right == ')' || left == '[' && right == ']'
}

/** Closing-line alignment shares the same Go context as Enter; normal typing stays native. */
class GoTypedIndentHandler : TypedHandlerDelegate() {
    override fun beforeCharTyped(c: Char, project: Project, editor: Editor, file: PsiFile, fileType: FileType): Result {
        if (file.language == GoLanguage && c in "})]" && editor.document.charsSequence.getOrNull(editor.caretModel.offset) == c) align(c, project, editor, file, false)
        return Result.CONTINUE
    }
    override fun charTyped(c: Char, project: Project, editor: Editor, file: PsiFile): Result {
        return align(c, project, editor, file, true)
    }
    private fun align(c: Char, project: Project, editor: Editor, file: PsiFile, alreadyTyped: Boolean): Result {
        if (file.language != GoLanguage || c !in "})]:") return Result.CONTINUE
        val offset = editor.caretModel.offset
        val source = editor.document.charsSequence
        val context = GoIndentContext(source)
        if (context.protectedTokenAt((offset - if (alreadyTyped) 1 else 0).coerceAtLeast(0)) != null) return Result.CONTINUE
        val start = context.lineStart(offset)
        var end = start
        while (end < source.length && source[end] in " \t") end++
        val prefix = source.subSequence(end, offset).toString()
        if (c == ':' && !context.startsCaseAt(start)) return Result.CONTINUE
        if (c != ':' && prefix != if (alreadyTyped) c.toString() else "") return Result.CONTINUE
        val indent = GoLineIndentProvider().getLineIndent(project, editor, GoLanguage, end) ?: return Result.CONTINUE
        if (indent === com.intellij.psi.codeStyle.lineIndent.LineIndentProvider.DO_NOT_ADJUST || source.subSequence(start, end).toString() == indent) return Result.CONTINUE
        editor.document.replaceString(start, end, indent)
        editor.caretModel.currentCaret.moveToOffset(offset + indent.length - (end - start))
        return Result.CONTINUE
    }
}
