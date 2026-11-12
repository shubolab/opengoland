package dev.openidea.goide.lang

import com.intellij.lexer.LexerBase
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType

object GoTemplateTokens {
    val TEXT = IElementType("GO_TEMPLATE_TEXT", GoTemplateLanguage)
    val ACTION_START = IElementType("GO_TEMPLATE_ACTION_START", GoTemplateLanguage)
    val ACTION_END = IElementType("GO_TEMPLATE_ACTION_END", GoTemplateLanguage)
    val KEYWORD = IElementType("GO_TEMPLATE_KEYWORD", GoTemplateLanguage)
    val BUILTIN = IElementType("GO_TEMPLATE_BUILTIN", GoTemplateLanguage)
    val IDENTIFIER = IElementType("GO_TEMPLATE_IDENTIFIER", GoTemplateLanguage)
    val VARIABLE = IElementType("GO_TEMPLATE_VARIABLE", GoTemplateLanguage)
    val NUMBER = IElementType("GO_TEMPLATE_NUMBER", GoTemplateLanguage)
    val STRING = IElementType("GO_TEMPLATE_STRING", GoTemplateLanguage)
    val COMMENT = IElementType("GO_TEMPLATE_COMMENT", GoTemplateLanguage)
    val OPERATOR = IElementType("GO_TEMPLATE_OPERATOR", GoTemplateLanguage)
    val LPAREN = IElementType("GO_TEMPLATE_LPAREN", GoTemplateLanguage)
    val RPAREN = IElementType("GO_TEMPLATE_RPAREN", GoTemplateLanguage)
    val LBRACKET = IElementType("GO_TEMPLATE_LBRACKET", GoTemplateLanguage)
    val RBRACKET = IElementType("GO_TEMPLATE_RBRACKET", GoTemplateLanguage)

    val keywords = setOf(
        "block", "break", "continue", "define", "else", "end", "if", "range", "template", "with",
    )
    val builtins = setOf(
        "and", "call", "eq", "false", "ge", "gt", "html", "index", "js", "le", "len", "lt",
        "ne", "nil", "not", "or", "print", "printf", "println", "slice", "true", "urlquery",
    )
}

/**
 * A restartable lexer for text/template syntax. Text outside actions remains a
 * single token; only actions are interpreted so HTML or arbitrary output text
 * is not misreported as malformed Go.
 */
class GoTemplateLexer : LexerBase() {
    private var text: CharSequence = ""
    private var end = 0
    private var start = 0
    private var next = 0
    private var type: IElementType? = null
    private var inAction = false

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        text = buffer
        end = endOffset
        next = startOffset
        inAction = initialState == STATE_ACTION
        advance()
    }

    override fun getState() = if (inAction) STATE_ACTION else STATE_TEXT
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

        if (!inAction) {
            if (isActionStart(start)) {
                if (isCommentStart(start)) {
                    scanComment()
                    inAction = false
                    type = GoTemplateTokens.COMMENT
                } else {
                    next = start + if (peekAt(start + 2, '-')) 3 else 2
                    inAction = true
                    type = GoTemplateTokens.ACTION_START
                }
            } else {
                next = start + 1
                while (next < end && !isActionStart(next)) next++
                type = GoTemplateTokens.TEXT
            }
            return
        }

        val c = text[next++]
        type = when {
            c == '}' && peek('}') -> {
                next++
                inAction = false
                GoTemplateTokens.ACTION_END
            }
            c == '-' && peek('}') && peekAt(next + 1, '}') -> {
                next += 2
                inAction = false
                GoTemplateTokens.ACTION_END
            }
            isWhitespace(c) -> {
                while (next < end && isWhitespace(text[next])) next++
                TokenType.WHITE_SPACE
            }
            c == '"' || c == '\'' || c == '`' -> {
                scanString(c)
                GoTemplateTokens.STRING
            }
            c == '$' -> {
                while (next < end && isIdentifierPart(text[next])) next++
                GoTemplateTokens.VARIABLE
            }
            c.isGoLetter() -> {
                while (next < end && isIdentifierPart(text[next])) next++
                when (text.subSequence(start, next).toString()) {
                    in GoTemplateTokens.keywords -> GoTemplateTokens.KEYWORD
                    in GoTemplateTokens.builtins -> GoTemplateTokens.BUILTIN
                    else -> GoTemplateTokens.IDENTIFIER
                }
            }
            c.isAsciiDigit() || (c == '.' && next < end && text[next].isAsciiDigit()) -> {
                scanNumber()
                GoTemplateTokens.NUMBER
            }
            c == '(' -> GoTemplateTokens.LPAREN
            c == ')' -> GoTemplateTokens.RPAREN
            c == '[' -> GoTemplateTokens.LBRACKET
            c == ']' -> GoTemplateTokens.RBRACKET
            c in OPERATORS -> {
                val width = when {
                    start + 3 <= end && text.subSequence(start, start + 3).toString() in THREE_CHAR_OPERATORS -> 3
                    start + 2 <= end && text.subSequence(start, start + 2).toString() in TWO_CHAR_OPERATORS -> 2
                    else -> 1
                }
                next = start + width
                GoTemplateTokens.OPERATOR
            }
            else -> TokenType.BAD_CHARACTER
        }
    }

    private fun isActionStart(offset: Int) = offset + 1 < end && text[offset] == '{' && text[offset + 1] == '{'

    private fun isCommentStart(offset: Int): Boolean {
        var cursor = offset + 2
        if (cursor < end && text[cursor] == '-') cursor++
        while (cursor < end && isTrimWhitespace(text[cursor])) cursor++
        return cursor + 1 < end && text[cursor] == '/' && text[cursor + 1] == '*'
    }

    private fun scanComment() {
        var cursor = start + 2
        if (cursor < end && text[cursor] == '-') cursor++
        while (cursor < end && isTrimWhitespace(text[cursor])) cursor++
        cursor += 2
        while (cursor + 1 < end && !(text[cursor] == '*' && text[cursor + 1] == '/')) cursor++
        next = end
        if (cursor + 1 >= end) return
        cursor += 2
        while (cursor < end && isTrimWhitespace(text[cursor])) cursor++
        if (cursor < end && text[cursor] == '-') cursor++
        if (cursor + 1 < end && text[cursor] == '}' && text[cursor + 1] == '}') next = cursor + 2
    }

    private fun scanString(quote: Char) {
        while (next < end) {
            if (quote != '`' && text[next] == '\n') return
            val value = text[next++]
            if (value == quote) return
            if (value == '\\' && quote != '`' && next < end && text[next] != '\n') next++
        }
    }

    private fun scanNumber() {
        var radix = 10
        next = start
        if (text[next] != '.') {
            if (text[next] == '0' && next + 1 < end) {
                radix = when (text[next + 1]) {
                    'x', 'X' -> 16
                    'o', 'O' -> 8
                    'b', 'B' -> 2
                    else -> 10
                }
                if (radix != 10) next += 2
            }
            scanDigits(radix)
        }
        if (peek('.')) {
            next++
            scanDigits(radix)
        }
        if (next < end && text[next] in "eEpP") {
            next++
            if (next < end && text[next] in "+-") next++
            scanDigits(10)
        }
        if (peek('i')) next++
    }

    private fun scanDigits(radix: Int) {
        while (next < end && (text[next] == '_' || text[next].isAsciiDigit() ||
                radix == 16 && text[next] in "aAbBcCdDeEfF")) next++
    }

    private fun peek(expected: Char) = next < end && text[next] == expected
    private fun peekAt(offset: Int, expected: Char) = offset < end && text[offset] == expected
    private fun Char.isGoLetter() = this == '_' || Character.isLetter(this)
    private fun Char.isAsciiDigit() = this in '0'..'9'
    private fun isIdentifierPart(c: Char) = c == '_' || Character.isLetterOrDigit(c)
    private fun isWhitespace(c: Char) = c == ' ' || c == '\t' || c == '\r' || c == '\n'
    private fun isTrimWhitespace(c: Char) = isWhitespace(c)

    private companion object {
        const val STATE_TEXT = 0
        const val STATE_ACTION = 1
        const val OPERATORS = "+-*/%&|^<>=!:;.,~"
        val THREE_CHAR_OPERATORS = setOf("<<=", ">>=", "&^=", "...")
        val TWO_CHAR_OPERATORS = setOf("+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "<<", ">>", "&^", "&&", "||", "<-", "++", "--", "==", "!=", "<=", ">=", ":=")
    }
}
