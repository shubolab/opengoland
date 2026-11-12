package dev.openidea.goide.lang

import com.intellij.lexer.LexerBase
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType

object HttpRequestTokens {
    val METHOD = IElementType("HTTP_REQUEST_METHOD", HttpRequestLanguage)
    val URL = IElementType("HTTP_REQUEST_URL", HttpRequestLanguage)
    val VERSION = IElementType("HTTP_REQUEST_VERSION", HttpRequestLanguage)
    val HEADER_NAME = IElementType("HTTP_REQUEST_HEADER_NAME", HttpRequestLanguage)
    val TEXT = IElementType("HTTP_REQUEST_TEXT", HttpRequestLanguage)
    val VARIABLE = IElementType("HTTP_REQUEST_VARIABLE", HttpRequestLanguage)
    val STRING = IElementType("HTTP_REQUEST_STRING", HttpRequestLanguage)
    val NUMBER = IElementType("HTTP_REQUEST_NUMBER", HttpRequestLanguage)
    val COMMENT = IElementType("HTTP_REQUEST_COMMENT", HttpRequestLanguage)
    val SEPARATOR = IElementType("HTTP_REQUEST_SEPARATOR", HttpRequestLanguage)
    val OPERATOR = IElementType("HTTP_REQUEST_OPERATOR", HttpRequestLanguage)
    val LBRACE = IElementType("HTTP_REQUEST_LBRACE", HttpRequestLanguage)
    val RBRACE = IElementType("HTTP_REQUEST_RBRACE", HttpRequestLanguage)
    val LBRACKET = IElementType("HTTP_REQUEST_LBRACKET", HttpRequestLanguage)
    val RBRACKET = IElementType("HTTP_REQUEST_RBRACKET", HttpRequestLanguage)

    val methods = setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS", "TRACE", "CONNECT")
}

/** Lexical support for .http request documents; network execution is intentionally separate. */
class HttpRequestLexer : LexerBase() {
    private var text: CharSequence = ""
    private var end = 0
    private var start = 0
    private var next = 0
    private var type: IElementType? = null
    private var expectRequestTarget = false

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        text = buffer
        end = endOffset
        next = startOffset
        expectRequestTarget = initialState == STATE_EXPECT_TARGET
        advance()
    }
    override fun getState() = if (expectRequestTarget) STATE_EXPECT_TARGET else 0
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
        if (expectRequestTarget && !c.isWhitespace()) {
            while (next < end && !text[next].isWhitespace()) next++
            expectRequestTarget = false
            type = HttpRequestTokens.URL
            return
        }
        type = when {
            c.isWhitespace() -> {
                while (next < end && text[next].isWhitespace()) next++
                if (c == '\n') expectRequestTarget = false
                TokenType.WHITE_SPACE
            }
            c == '#' && isFirstNonWhitespace(start) -> {
                while (next < end && text[next] == '#') next++
                if (next - start >= 3) {
                    while (next < end && text[next] != '\n') next++
                    HttpRequestTokens.SEPARATOR
                } else {
                    while (next < end && text[next] != '\n') next++
                    HttpRequestTokens.COMMENT
                }
            }
            c == '/' && peek('/') && isFirstNonWhitespace(start) -> {
                next++
                while (next < end && text[next] != '\n') next++
                HttpRequestTokens.COMMENT
            }
            c == '@' && isFirstNonWhitespace(start) -> {
                while (next < end && text[next].isIdentifierPart()) next++
                HttpRequestTokens.VARIABLE
            }
            c == '{' && peek('{') -> {
                next++
                while (next < end && !(text[next] == '}' && next + 1 < end && text[next + 1] == '}')) next++
                if (next + 1 < end) next += 2
                HttpRequestTokens.VARIABLE
            }
            c == '"' || c == '\'' -> {
                scanString(c)
                HttpRequestTokens.STRING
            }
            c.isAsciiDigit() -> {
                while (next < end && (text[next].isAsciiDigit() || text[next] == '.')) next++
                HttpRequestTokens.NUMBER
            }
            c.isWordStart() -> scanWord(c)
            c == ':' && isHeaderColon(start) -> HttpRequestTokens.OPERATOR
            c == '{' -> HttpRequestTokens.LBRACE
            c == '}' -> HttpRequestTokens.RBRACE
            c == '[' -> HttpRequestTokens.LBRACKET
            c == ']' -> HttpRequestTokens.RBRACKET
            c in OPERATORS -> {
                val width = if (start + 2 <= end && text.subSequence(start, start + 2).toString() in TWO_CHAR_OPERATORS) 2 else 1
                next = start + width
                HttpRequestTokens.OPERATOR
            }
            else -> TokenType.BAD_CHARACTER
        }
    }

    private fun scanWord(first: Char): IElementType {
        if (expectRequestTarget) {
            while (next < end && !text[next].isWhitespace()) next++
            expectRequestTarget = false
            return HttpRequestTokens.URL
        }
        while (next < end && text[next].isIdentifierPart()) next++
        val word = text.subSequence(start, next).toString()
        if (isFirstNonWhitespace(start)) {
            if (word.uppercase() in HttpRequestTokens.methods) {
                expectRequestTarget = true
                return HttpRequestTokens.METHOD
            }
            var cursor = next
            while (cursor < end && (text[cursor] == ' ' || text[cursor] == '\t')) cursor++
            if (cursor < end && text[cursor] == ':' && word.none { it == '/' }) return HttpRequestTokens.HEADER_NAME
        }
        if (word.equals("HTTP", ignoreCase = true) && next < end && text[next] == '/') {
            while (next < end && !text[next].isWhitespace()) next++
            return HttpRequestTokens.VERSION
        }
        return HttpRequestTokens.TEXT
    }

    private fun isHeaderColon(offset: Int): Boolean {
        var cursor = offset - 1
        while (cursor >= 0 && text[cursor] != '\n' && text[cursor] != ' ') cursor--
        if (cursor >= 0 && text[cursor] == ' ') return false
        return isFirstNonWhitespaceWordEnd(offset)
    }

    private fun isFirstNonWhitespaceWordEnd(offset: Int): Boolean {
        var cursor = offset - 1
        while (cursor >= 0 && text[cursor] != '\n' && (Character.isLetterOrDigit(text[cursor]) || text[cursor] == '-')) cursor--
        while (cursor >= 0 && (text[cursor] == ' ' || text[cursor] == '\t' || text[cursor] == '\r')) cursor--
        return cursor < 0 || text[cursor] == '\n'
    }

    private fun scanString(quote: Char) {
        while (next < end) {
            val value = text[next++]
            if (value == quote) return
            if (value == '\\' && next < end) next++
        }
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
    private fun Char.isWhitespace() = this == ' ' || this == '\t' || this == '\r' || this == '\n'
    private fun Char.isAsciiDigit() = this in '0'..'9'
    private fun Char.isWordStart() = this == '_' || Character.isLetter(this)
    private fun Char.isIdentifierPart() = this == '_' || this == '-' || Character.isLetterOrDigit(this)

    private companion object {
        const val STATE_EXPECT_TARGET = 1
        const val OPERATORS = "=:+*?&,;<>()"
        val TWO_CHAR_OPERATORS = setOf("==", "!=", "<=", ">=", "&&", "||")
    }
}
