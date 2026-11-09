package dev.openidea.goide.lang

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegate
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.EditorActionHandler
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiFile
import com.intellij.psi.codeStyle.CodeStyleManager

/** Go comments are lexical comments, not Java documentation PSI. */
class GoCommentEnterHandler : EnterHandlerDelegate {
    override fun preprocessEnter(
        file: PsiFile, editor: Editor, caretOffset: Ref<Int>, caretAdvance: Ref<Int>,
        dataContext: DataContext, originalHandler: EditorActionHandler?,
    ): EnterHandlerDelegate.Result {
        if (file.language != GoLanguage || !editor.isInsertMode) return EnterHandlerDelegate.Result.Continue
        val document = editor.document
        val text = document.charsSequence
        val offset = caretOffset.get()
        if (offset == 0) return EnterHandlerDelegate.Result.Continue
        val iterator = editor.highlighter.createIterator(offset - 1)
        if (iterator.atEnd() || iterator.tokenType != GoTokens.COMMENT) return EnterHandlerDelegate.Result.Continue
        val start = iterator.start
        val end = iterator.end
        if (offset < start + 2) return EnterHandlerDelegate.Result.Continue
        val line = document.getLineNumber(offset)
        val lineStart = document.getLineStartOffset(line)
        val lineEnd = document.getLineEndOffset(line)
        val indentEnd = horizontalEnd(text, lineStart, lineEnd)
        val indent = text.subSequence(lineStart, indentEnd).toString()

        if (text[start + 1] == '/') {
            // Directives must retain their single-line meaning. At EOL normal
            // Enter exits a line comment, as in the platform's line-comment action.
            val content = text.subSequence(start + 2, end).toString()
            val legacy = content.trimStart(' ', '\t')
            if (content.startsWith("go:") || content.startsWith("line ") || content.startsWith("export ") ||
                legacy == "+build" || legacy.startsWith("+build ") || legacy.startsWith("+build\t")) return EnterHandlerDelegate.Result.Continue
            val rest = horizontalEnd(text, offset, lineEnd)
            if (rest == lineEnd) return EnterHandlerDelegate.Result.Continue
            val spacingEnd = horizontalEnd(text, start + 2, end)
            val spacing = text.subSequence(start + 2, spacingEnd).toString().ifEmpty { " " }
            val alreadyPrefixed = rest + 1 < lineEnd && text[rest] == '/' && text[rest + 1] == '/'
            val prefix = "\n" + indent + if (alreadyPrefixed) "" else "//$spacing"
            document.replaceString(offset, rest, prefix)
            editor.caretModel.moveToOffset(offset + prefix.length)
            return EnterHandlerDelegate.Result.Stop
        }

        val complete = end - start >= 4 && text[end - 2] == '*' && text[end - 1] == '/'
        if (complete && offset > end - 2) return EnterHandlerDelegate.Result.Continue
        val openingLine = document.getLineNumber(start)
        val openingStart = document.getLineStartOffset(openingLine)
        val openingIndent = text.subSequence(openingStart, horizontalEnd(text, openingStart, start)).toString()
        val starred = CodeStyleManager.getInstance(file.project).getDocCommentSettings(file).isLeadingAsteriskEnabled &&
            (start + 2 < (if (complete) end - 2 else end) && text[start + 2] == '*' ||
                line > openingLine && indentEnd < offset && text[indentEnd] == '*')
        val continuation = if (starred) openingIndent + " * " else if (line == openingLine) openingIndent + " " else indent
        val rest = horizontalEnd(text, offset, lineEnd)
        // A closer immediately to the right gets its own line, keeping a blank
        // editable content line and the original closer (never a duplicate).
        val closeOnRight = complete && rest == end - 2 && document.getLineNumber(end - 2) == line
        val prefix = "\n$continuation"
        if (closeOnRight) {
            document.replaceString(offset, rest, prefix + "\n$openingIndent ")
        } else {
            if (!complete && line == openingLine && CodeInsightSettings.getInstance().CLOSE_COMMENT_ON_ENTER) {
                document.insertString(lineEnd, "\n$openingIndent */")
            }
            document.replaceString(offset, rest, prefix)
        }
        editor.caretModel.moveToOffset(offset + prefix.length)
        return EnterHandlerDelegate.Result.Stop
    }

    private fun horizontalEnd(text: CharSequence, start: Int, end: Int): Int {
        var offset = start
        while (offset < end && (text[offset] == ' ' || text[offset] == '\t')) offset++
        return offset
    }
}
