package dev.openidea.goide.lang

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors as Colors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.tree.IElementType

class DockerfileSyntaxHighlighter : SyntaxHighlighterBase() {
    override fun getHighlightingLexer() = DockerfileLexer()
    override fun getTokenHighlights(tokenType: IElementType): Array<TextAttributesKey> = when (tokenType) {
        DockerfileTokens.INSTRUCTION -> pack(Colors.KEYWORD)
        DockerfileTokens.KEYWORD -> pack(Colors.PREDEFINED_SYMBOL)
        DockerfileTokens.VARIABLE -> pack(Colors.INSTANCE_FIELD)
        DockerfileTokens.NUMBER -> pack(Colors.NUMBER)
        DockerfileTokens.STRING -> pack(Colors.STRING)
        DockerfileTokens.COMMENT -> pack(Colors.LINE_COMMENT)
        DockerfileTokens.OPERATOR -> pack(Colors.OPERATION_SIGN)
        DockerfileTokens.LPAREN, DockerfileTokens.RPAREN -> pack(Colors.PARENTHESES)
        DockerfileTokens.LBRACKET, DockerfileTokens.RBRACKET -> pack(Colors.BRACKETS)
        DockerfileTokens.LBRACE, DockerfileTokens.RBRACE -> pack(Colors.BRACES)
        else -> emptyArray()
    }
}

class DockerfileSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?) = DockerfileSyntaxHighlighter()
}
