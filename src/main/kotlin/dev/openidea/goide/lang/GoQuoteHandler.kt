package dev.openidea.goide.lang

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.editorActions.QuoteHandler
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.highlighter.HighlighterIterator
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile

/** Literal boundaries, rather than quote counts in a line, drive native typing. */
class GoQuoteHandler : QuoteHandler {
    override fun isOpeningQuote(iterator: HighlighterIterator, offset: Int): Boolean =
        literal(iterator) && offset == iterator.start

    override fun isClosingQuote(iterator: HighlighterIterator, offset: Int): Boolean {
        if (!literal(iterator) || offset != iterator.end - 1 || offset <= iterator.start) return false
        val text = iterator.document.charsSequence
        val quote = text[iterator.start]
        return text[offset] == quote && (quote == '`' || !escaped(text, offset, iterator.start))
    }

    override fun hasNonClosedLiteral(editor: Editor, iterator: HighlighterIterator, offset: Int): Boolean =
        literal(iterator) && !isClosingQuote(iterator, iterator.end - 1)

    override fun isInsideLiteral(iterator: HighlighterIterator): Boolean = literal(iterator)

    private fun literal(iterator: HighlighterIterator): Boolean =
        !iterator.atEnd() && iterator.tokenType == GoTokens.STRING &&
            iterator.start < iterator.end && iterator.document.charsSequence[iterator.start] in "\"'`"

    private fun escaped(text: CharSequence, offset: Int, start: Int): Boolean {
        var slash = offset - 1
        while (slash > start && text[slash] == '\\') slash--
        return (offset - 1 - slash) % 2 != 0
    }
}

/** The platform's generic escape guard also runs for raw strings; Go has no raw escapes. */
class GoQuoteTypedHandler : TypedHandlerDelegate() {
    override fun beforeCharTyped(c: Char, project: Project, editor: Editor, file: PsiFile, fileType: FileType): Result {
        if (file.language != GoLanguage || c != '`' || !CodeInsightSettings.getInstance().AUTOINSERT_PAIR_QUOTE) return Result.CONTINUE
        val offset = editor.caretModel.offset
        val text = editor.document.charsSequence
        if (offset == 0 || offset >= text.length || text[offset] != '`' || text[offset - 1] != '\\') return Result.CONTINUE
        val iterator = editor.highlighter.createIterator(offset)
        if (GoQuoteHandler().isClosingQuote(iterator, offset)) {
            editor.caretModel.moveToOffset(offset + 1)
            return Result.STOP
        }
        return Result.CONTINUE
    }

    override fun beforeClosingQuoteInserted(quote: CharSequence, project: Project, editor: Editor, file: PsiFile): Result {
        if (file.language != GoLanguage) return Result.CONTINUE
        val text = editor.document.charsSequence
        val offset = editor.caretModel.offset
        if (offset >= text.length || !Character.isHighSurrogate(text[offset])) return Result.CONTINUE
        // The SDK's normal identifier guard examines one UTF-16 Char. Go
        // identifiers may start with a supplementary Unicode letter too.
        val codePoint = Character.codePointAt(text, offset)
        return if (Character.isLetter(codePoint) || Character.isDigit(codePoint)) Result.STOP else Result.CONTINUE
    }
}
