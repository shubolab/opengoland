package dev.openidea.goide.lang

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors as Colors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.tree.IElementType

class SqlSyntaxHighlighter : SyntaxHighlighterBase() {
    override fun getHighlightingLexer() = SqlLexer()
    override fun getTokenHighlights(tokenType: IElementType): Array<TextAttributesKey> = when (tokenType) {
        SqlTokens.KEYWORD -> pack(Colors.KEYWORD)
        SqlTokens.IDENTIFIER -> pack(Colors.IDENTIFIER)
        SqlTokens.QUOTED_IDENTIFIER -> pack(Colors.INSTANCE_FIELD)
        SqlTokens.VARIABLE -> pack(Colors.CONSTANT)
        SqlTokens.NUMBER -> pack(Colors.NUMBER)
        SqlTokens.STRING -> pack(Colors.STRING)
        SqlTokens.COMMENT -> pack(Colors.LINE_COMMENT)
        SqlTokens.OPERATOR -> pack(Colors.OPERATION_SIGN)
        SqlTokens.LPAREN, SqlTokens.RPAREN -> pack(Colors.PARENTHESES)
        SqlTokens.LBRACKET, SqlTokens.RBRACKET -> pack(Colors.BRACKETS)
        SqlTokens.LBRACE, SqlTokens.RBRACE -> pack(Colors.BRACES)
        else -> emptyArray()
    }
}

class SqlSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?) = SqlSyntaxHighlighter()
}
