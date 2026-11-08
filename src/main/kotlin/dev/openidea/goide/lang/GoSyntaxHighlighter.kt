package dev.openidea.goide.lang

import com.intellij.lang.Commenter
import com.intellij.lang.BracePair
import com.intellij.lang.PairedBraceMatcher
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors as Colors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType

class GoSyntaxHighlighter : SyntaxHighlighterBase() {
    override fun getHighlightingLexer() = GoLexer()
    override fun getTokenHighlights(tokenType: IElementType): Array<TextAttributesKey> = when (tokenType) {
        GoTokens.KEYWORD -> pack(Colors.KEYWORD)
        GoTokens.STRING -> pack(Colors.STRING)
        GoTokens.NUMBER -> pack(Colors.NUMBER)
        GoTokens.COMMENT -> pack(Colors.LINE_COMMENT)
        GoTokens.OPERATOR -> pack(Colors.OPERATION_SIGN)
        GoTokens.LBRACE, GoTokens.RBRACE -> pack(Colors.BRACES)
        GoTokens.LPAREN, GoTokens.RPAREN -> pack(Colors.PARENTHESES)
        GoTokens.LBRACKET, GoTokens.RBRACKET -> pack(Colors.BRACKETS)
        else -> emptyArray()
    }
}
class GoSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?) = GoSyntaxHighlighter()
}
class GoCommenter : Commenter {
    override fun getLineCommentPrefix() = "//"
    override fun getBlockCommentPrefix() = "/*"
    override fun getBlockCommentSuffix() = "*/"
    override fun getCommentedBlockCommentPrefix(): String? = null
    override fun getCommentedBlockCommentSuffix(): String? = null
}
class GoBraceMatcher : PairedBraceMatcher {
    override fun getPairs() = arrayOf(BracePair(GoTokens.LBRACE, GoTokens.RBRACE, true), BracePair(GoTokens.LPAREN, GoTokens.RPAREN, false), BracePair(GoTokens.LBRACKET, GoTokens.RBRACKET, false))
    override fun isPairedBracesAllowedBeforeType(lbraceType: IElementType, contextType: IElementType?) = true
    override fun getCodeConstructStart(file: PsiFile, openingBraceOffset: Int) = openingBraceOffset
}
