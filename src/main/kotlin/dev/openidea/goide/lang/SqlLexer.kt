package dev.openidea.goide.lang

import com.intellij.lexer.LexerBase
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType

object SqlTokens {
    val KEYWORD = IElementType("SQL_KEYWORD", SqlLanguage)
    val IDENTIFIER = IElementType("SQL_IDENTIFIER", SqlLanguage)
    val QUOTED_IDENTIFIER = IElementType("SQL_QUOTED_IDENTIFIER", SqlLanguage)
    val VARIABLE = IElementType("SQL_VARIABLE", SqlLanguage)
    val NUMBER = IElementType("SQL_NUMBER", SqlLanguage)
    val STRING = IElementType("SQL_STRING", SqlLanguage)
    val COMMENT = IElementType("SQL_COMMENT", SqlLanguage)
    val OPERATOR = IElementType("SQL_OPERATOR", SqlLanguage)
    val LPAREN = IElementType("SQL_LPAREN", SqlLanguage)
    val RPAREN = IElementType("SQL_RPAREN", SqlLanguage)
    val LBRACKET = IElementType("SQL_LBRACKET", SqlLanguage)
    val RBRACKET = IElementType("SQL_RBRACKET", SqlLanguage)
    val LBRACE = IElementType("SQL_LBRACE", SqlLanguage)
    val RBRACE = IElementType("SQL_RBRACE", SqlLanguage)

    val keywords = setOf(
        "add", "all", "alter", "and", "any", "as", "asc", "begin", "between", "by", "case",
        "check", "column", "commit", "constraint", "create", "cross", "current", "database",
        "default", "delete", "desc", "distinct", "do", "drop", "else", "end", "escape", "except",
        "exists", "explain", "false", "fetch", "for", "foreign", "from", "full", "function",
        "grant", "group", "having", "if", "in", "index", "inner", "insert", "intersect", "into",
        "is", "join", "key", "lateral", "left", "like", "limit", "merge", "natural", "not", "null",
        "offset", "on", "only", "or", "order", "outer", "over", "partition", "primary", "procedure",
        "references", "returning", "revoke", "right", "rollback", "row", "rows", "schema", "select",
        "set", "some", "table", "temporary", "then", "true", "truncate", "union", "unique", "update",
        "using", "values", "view", "when", "where", "window", "with",
    )
}

/** Lexical highlighting only; SQL dialect validation and database features are separate. */
class SqlLexer : LexerBase() {
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
            c.isWhitespace() -> {
                while (next < end && text[next].isWhitespace()) next++
                TokenType.WHITE_SPACE
            }
            c == '-' && peek('-') -> {
                next++
                while (next < end && text[next] != '\n') next++
                SqlTokens.COMMENT
            }
            c == '/' && peek('*') -> {
                next++
                scanBlockComment()
                SqlTokens.COMMENT
            }
            c == '\'' -> {
                scanQuoted('\'', doubled = true, backslash = true)
                SqlTokens.STRING
            }
            c == '"' -> {
                scanQuoted('"', doubled = true, backslash = false)
                SqlTokens.QUOTED_IDENTIFIER
            }
            c == '`' -> {
                scanQuoted('`', doubled = true, backslash = true)
                SqlTokens.QUOTED_IDENTIFIER
            }
            c == '[' && looksLikeBracketIdentifier(start) -> {
                while (next < end) {
                    val value = text[next++]
                    if (value == ']') {
                        if (peek(']')) next++ else break
                    }
                }
                SqlTokens.QUOTED_IDENTIFIER
            }
            c == '$' -> scanDollarToken()
            c.isAsciiDigit() || c == '.' && peekDigit() -> {
                scanNumber()
                SqlTokens.NUMBER
            }
            c.isSqlLetter() -> {
                while (next < end && text[next].isSqlIdentifierPart()) next++
                if (text.subSequence(start, next).toString().lowercase() in SqlTokens.keywords) {
                    SqlTokens.KEYWORD
                } else {
                    SqlTokens.IDENTIFIER
                }
            }
            c == '(' -> SqlTokens.LPAREN
            c == ')' -> SqlTokens.RPAREN
            c == '[' -> SqlTokens.LBRACKET
            c == ']' -> SqlTokens.RBRACKET
            c == '{' -> SqlTokens.LBRACE
            c == '}' -> SqlTokens.RBRACE
            c in OPERATORS -> {
                val width = when {
                    start + 3 <= end && text.subSequence(start, start + 3).toString() in THREE_CHAR_OPERATORS -> 3
                    start + 2 <= end && text.subSequence(start, start + 2).toString() in TWO_CHAR_OPERATORS -> 2
                    else -> 1
                }
                next = start + width
                SqlTokens.OPERATOR
            }
            else -> TokenType.BAD_CHARACTER
        }
    }

    private fun scanBlockComment() {
        var depth = 1
        while (next < end && depth > 0) {
            if (text[next] == '/' && next + 1 < end && text[next + 1] == '*') {
                next += 2
                depth++
            } else if (text[next] == '*' && next + 1 < end && text[next + 1] == '/') {
                next += 2
                depth--
            } else {
                next++
            }
        }
    }

    private fun scanQuoted(quote: Char, doubled: Boolean, backslash: Boolean) {
        while (next < end) {
            val value = text[next++]
            if (value == quote) {
                if (doubled && peek(quote)) next++ else return
            } else if (value == '\\' && backslash && next < end) {
                next++
            }
        }
    }

    private fun looksLikeBracketIdentifier(offset: Int): Boolean {
        val first = if (offset + 1 < end) text[offset + 1] else return false
        return !first.isAsciiDigit() && first != '-' && first != '\'' && first != '"'
    }

    private fun scanDollarToken(): IElementType {
        var cursor = next
        if (cursor < end && text[cursor].isAsciiDigit()) {
            while (cursor < end && text[cursor].isAsciiDigit()) cursor++
            next = cursor
            return SqlTokens.VARIABLE
        }
        while (cursor < end && (text[cursor] == '_' || Character.isLetterOrDigit(text[cursor]))) cursor++
        if (cursor >= end || text[cursor] != '$') {
            return TokenType.BAD_CHARACTER
        }
        val delimiter = text.subSequence(start, cursor + 1).toString()
        cursor++
        val close = text.indexOf(delimiter, cursor)
        next = if (close >= 0 && close < end) close + delimiter.length else end
        return SqlTokens.STRING
    }

    private fun scanNumber() {
        while (next < end && (text[next].isAsciiDigit() || text[next] == '_')) next++
        if (peek('.')) {
            next++
            while (next < end && text[next].isAsciiDigit()) next++
        }
        if (next < end && text[next] in "eE") {
            next++
            if (next < end && text[next] in "+-") next++
            while (next < end && text[next].isAsciiDigit()) next++
        }
    }

    private fun CharSequence.indexOf(value: String, from: Int): Int {
        if (value.isEmpty()) return from
        for (index in from..length - value.length) if (subSequence(index, index + value.length) == value) return index
        return -1
    }

    private fun peek(expected: Char) = next < end && text[next] == expected
    private fun peekDigit() = next < end && text[next].isAsciiDigit()
    private fun Char.isSqlLetter() = this == '_' || Character.isLetter(this)
    private fun Char.isSqlIdentifierPart() = this == '_' || this == '$' || Character.isLetterOrDigit(this)
    private fun Char.isAsciiDigit() = this in '0'..'9'

    private companion object {
        const val OPERATORS = "+-*/%&|^<>=!~:;,."
        val THREE_CHAR_OPERATORS = setOf("->>", "<=>")
        val TWO_CHAR_OPERATORS = setOf("::", ":=", "->", "=>", "<=", ">=", "<>", "!=", "||", "&&")
    }
}
