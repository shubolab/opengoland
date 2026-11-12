package dev.openidea.goide.lang

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors as Colors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.tree.IElementType

class HttpRequestSyntaxHighlighter : SyntaxHighlighterBase() {
    override fun getHighlightingLexer() = HttpRequestLexer()
    override fun getTokenHighlights(tokenType: IElementType): Array<TextAttributesKey> = when (tokenType) {
        HttpRequestTokens.METHOD, HttpRequestTokens.VERSION -> pack(Colors.KEYWORD)
        HttpRequestTokens.HEADER_NAME -> pack(Colors.INSTANCE_FIELD)
        HttpRequestTokens.VARIABLE -> pack(Colors.CONSTANT)
        HttpRequestTokens.URL -> pack(Colors.STRING)
        HttpRequestTokens.STRING -> pack(Colors.STRING)
        HttpRequestTokens.NUMBER -> pack(Colors.NUMBER)
        HttpRequestTokens.COMMENT -> pack(Colors.LINE_COMMENT)
        HttpRequestTokens.SEPARATOR -> pack(Colors.KEYWORD)
        HttpRequestTokens.OPERATOR -> pack(Colors.OPERATION_SIGN)
        HttpRequestTokens.LBRACE, HttpRequestTokens.RBRACE -> pack(Colors.BRACES)
        HttpRequestTokens.LBRACKET, HttpRequestTokens.RBRACKET -> pack(Colors.BRACKETS)
        else -> emptyArray()
    }
}

class HttpRequestSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?) = HttpRequestSyntaxHighlighter()
}
