package dev.openidea.goide.lang

import com.intellij.lexer.LexerBase
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType

object DockerfileTokens {
    val INSTRUCTION = IElementType("DOCKERFILE_INSTRUCTION", DockerfileLanguage)
    val KEYWORD = IElementType("DOCKERFILE_KEYWORD", DockerfileLanguage)
    val IDENTIFIER = IElementType("DOCKERFILE_IDENTIFIER", DockerfileLanguage)
    val VARIABLE = IElementType("DOCKERFILE_VARIABLE", DockerfileLanguage)
    val NUMBER = IElementType("DOCKERFILE_NUMBER", DockerfileLanguage)
    val STRING = IElementType("DOCKERFILE_STRING", DockerfileLanguage)
    val COMMENT = IElementType("DOCKERFILE_COMMENT", DockerfileLanguage)
    val OPERATOR = IElementType("DOCKERFILE_OPERATOR", DockerfileLanguage)
    val LPAREN = IElementType("DOCKERFILE_LPAREN", DockerfileLanguage)
    val RPAREN = IElementType("DOCKERFILE_RPAREN", DockerfileLanguage)
    val LBRACKET = IElementType("DOCKERFILE_LBRACKET", DockerfileLanguage)
    val RBRACKET = IElementType("DOCKERFILE_RBRACKET", DockerfileLanguage)
    val LBRACE = IElementType("DOCKERFILE_LBRACE", DockerfileLanguage)
    val RBRACE = IElementType("DOCKERFILE_RBRACE", DockerfileLanguage)

    val instructions = setOf(
        "add", "arg", "cmd", "copy", "entrypoint", "env", "expose", "from", "healthcheck",
        "label", "maintainer", "onbuild", "run", "shell", "stopsignal", "user", "volume", "workdir",
    )
    val keywords = setOf("as", "json")
}

/** Dockerfile lexical highlighting; builds and daemon operations are intentionally out of scope. */
class DockerfileLexer : LexerBase() {
    private var text: CharSequence = ""
    private var end = 0
    private var start = 0
    private var next = 0
    private var type: IElementType? = null

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        text = buffer
        end = endOffset
        next = startOffset
        advance()
    }
    override fun getState() = 0
    override fun getTokenType() = type
    override fun getTokenStart() = start
    override fun getTokenEnd() = next
    override fun getBufferSequence() = text
    override fun getBufferEnd() = end

    override fun advance() {
        start = next
        if (start >= end) {
            type = null
            return
        }
        val c = text[next++]
        type = when {
            c == ' ' || c == '\t' || c == '\r' || c == '\n' -> {
                while (next < end && text[next] in WHITESPACE) next++
                TokenType.WHITE_SPACE
            }
            c == '#' && isFirstNonWhitespace(start) -> {
                while (next < end && text[next] != '\n') next++
                DockerfileTokens.COMMENT
            }
            c == '"' || c == '\'' -> {
                scanString(c)
                DockerfileTokens.STRING
            }
            c == '$' -> scanVariable()
            c.isAsciiDigit() -> {
                while (next < end && (text[next].isAsciiDigit() || text[next] == '.')) next++
                DockerfileTokens.NUMBER
            }
            c.isWordStart() -> {
                while (next < end && text[next].isWordPart()) next++
                val word = text.subSequence(start, next).toString().lowercase()
                when {
                    word in DockerfileTokens.instructions && isFirstNonWhitespace(start) -> DockerfileTokens.INSTRUCTION
                    word in DockerfileTokens.keywords -> DockerfileTokens.KEYWORD
                    else -> DockerfileTokens.IDENTIFIER
                }
            }
            c == '(' -> DockerfileTokens.LPAREN
            c == ')' -> DockerfileTokens.RPAREN
            c == '[' -> DockerfileTokens.LBRACKET
            c == ']' -> DockerfileTokens.RBRACKET
            c == '{' -> DockerfileTokens.LBRACE
            c == '}' -> DockerfileTokens.RBRACE
            c in OPERATORS -> {
                val width = when {
                    start + 2 <= end && text.subSequence(start, start + 2).toString() in TWO_CHAR_OPERATORS -> 2
                    else -> 1
                }
                next = start + width
                DockerfileTokens.OPERATOR
            }
            else -> TokenType.BAD_CHARACTER
        }
    }

    private fun scanString(quote: Char) {
        while (next < end) {
            when (text[next++]) {
                quote -> return
                '\\' -> if (next < end && text[next] != '\n') next++
            }
        }
    }

    private fun scanVariable(): IElementType {
        if (peek('{')) {
            next++
            while (next < end && text[next] != '}') {
                if (text[next] == '\n') return DockerfileTokens.VARIABLE
                next++
            }
            if (peek('}')) next++
            return DockerfileTokens.VARIABLE
        }
        var cursor = next
        while (cursor < end && (text[cursor] == '_' || Character.isLetterOrDigit(text[cursor]))) cursor++
        if (cursor == next) return TokenType.BAD_CHARACTER
        next = cursor
        return DockerfileTokens.VARIABLE
    }

    private fun isFirstNonWhitespace(offset: Int): Boolean {
        var cursor = offset - 1
        while (cursor >= 0 && text[cursor] != '\n') {
            if (text[cursor] != ' ' && text[cursor] != '\t' && text[cursor] != '\r') return false
            cursor--
        }
        return true
    }

    private fun peek(expected: Char) = next < end && text[next] == expected
    private fun Char.isAsciiDigit() = this in '0'..'9'
    private fun Char.isWordStart() = this == '_' || Character.isLetter(this)
    private fun Char.isWordPart() = this == '_' || this == '-' || this == '.' || Character.isLetterOrDigit(this)

    private companion object {
        const val WHITESPACE = " \t\r\n"
        const val OPERATORS = "\\&|=+*?:,;<>"
        val TWO_CHAR_OPERATORS = setOf("->", "<<", ">>", "&&", "||", "==", "!=", "<=", ">=")
    }
}
