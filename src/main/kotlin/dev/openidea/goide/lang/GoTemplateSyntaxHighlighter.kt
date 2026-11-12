package dev.openidea.goide.lang

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors as Colors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.tree.IElementType

class GoTemplateSyntaxHighlighter : SyntaxHighlighterBase() {
    override fun getHighlightingLexer() = GoTemplateLexer()
    override fun getTokenHighlights(tokenType: IElementType): Array<TextAttributesKey> = when (tokenType) {
        GoTemplateTokens.ACTION_START, GoTemplateTokens.ACTION_END -> pack(Colors.BRACES)
        GoTemplateTokens.KEYWORD -> pack(Colors.KEYWORD)
        GoTemplateTokens.BUILTIN -> pack(Colors.PREDEFINED_SYMBOL)
        GoTemplateTokens.IDENTIFIER -> pack(Colors.IDENTIFIER)
        GoTemplateTokens.VARIABLE -> pack(Colors.INSTANCE_FIELD)
        GoTemplateTokens.NUMBER -> pack(Colors.NUMBER)
        GoTemplateTokens.STRING -> pack(Colors.STRING)
        GoTemplateTokens.COMMENT -> pack(Colors.BLOCK_COMMENT)
        GoTemplateTokens.OPERATOR -> pack(Colors.OPERATION_SIGN)
        GoTemplateTokens.LPAREN, GoTemplateTokens.RPAREN -> pack(Colors.PARENTHESES)
        GoTemplateTokens.LBRACKET, GoTemplateTokens.RBRACKET -> pack(Colors.BRACKETS)
        else -> emptyArray()
    }
}

class GoTemplateSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?) = GoTemplateSyntaxHighlighter()
}
