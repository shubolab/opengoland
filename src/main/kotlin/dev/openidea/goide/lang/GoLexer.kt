package dev.openidea.goide.lang

import com.intellij.lexer.LexerBase
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType

object GoTokens {
    val KEYWORD = IElementType("GO_KEYWORD", GoLanguage)
    val IDENTIFIER = IElementType("GO_IDENTIFIER", GoLanguage)
    val NUMBER = IElementType("GO_NUMBER", GoLanguage)
    val STRING = IElementType("GO_STRING", GoLanguage)
    val COMMENT = IElementType("GO_COMMENT", GoLanguage)
    val OPERATOR = IElementType("GO_OPERATOR", GoLanguage)
    val LBRACE = IElementType("GO_LBRACE", GoLanguage)
    val RBRACE = IElementType("GO_RBRACE", GoLanguage)
    val LPAREN = IElementType("GO_LPAREN", GoLanguage)
    val RPAREN = IElementType("GO_RPAREN", GoLanguage)
    val LBRACKET = IElementType("GO_LBRACKET", GoLanguage)
    val RBRACKET = IElementType("GO_RBRACKET", GoLanguage)
    val keywords = setOf("break", "default", "func", "interface", "select", "case", "defer", "go", "map", "struct", "chan", "else", "goto", "package", "switch", "const", "fallthrough", "if", "range", "type", "continue", "for", "import", "return", "var")
}

/**
 * Lexical highlighting is local; gopls supplies semantic analysis. Unicode
 * categories use the host JBR tables, which can be newer than the selected SDK.
 */
class GoLexer : LexerBase() {
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
        if (start >= end) { type = null; return }
        val c = text[next++]
        type = when {
            c.isGoWhitespace() -> { while (next < end && text[next].isGoWhitespace()) next++; TokenType.WHITE_SPACE }
            c == '\uFEFF' && start == 0 -> TokenType.WHITE_SPACE
            c == '/' && peek('/') -> { next++; while (next < end && text[next] != '\n') next++; GoTokens.COMMENT }
            c == '/' && peek('*') -> {
                next++
                while (next < end) {
                    if (text[next++] == '*' && peek('/')) { next++; break }
                }
                GoTokens.COMMENT
            }
            c == '"' || c == '\'' || c == '`' -> {
                while (next < end) {
                    // Interpreted literals cannot cross LF, including an invalid
                    // backslash-LF escape. Leave LF for the whitespace token.
                    if (c != '`' && text[next] == '\n') break
                    val v = text[next++]
                    if (v == c) break
                    if (v == '\\' && c != '`' && next < end && text[next] != '\n') next++
                }
                GoTokens.STRING
            }
            codePointAt(start).isGoLetter() -> {
                next = start + Character.charCount(codePointAt(start))
                while (next < end) {
                    val cp = codePointAt(next)
                    if (!cp.isGoLetter() && !Character.isDigit(cp)) break
                    next += Character.charCount(cp)
                }
                if (text.subSequence(start, next).toString() in GoTokens.keywords) GoTokens.KEYWORD else GoTokens.IDENTIFIER
            }
            c.isAsciiDigit() || (c == '.' && next < end && text[next].isAsciiDigit()) -> {
                scanNumber()
                GoTokens.NUMBER
            }
            c == '{' -> GoTokens.LBRACE
            c == '}' -> GoTokens.RBRACE
            c == '(' -> GoTokens.LPAREN
            c == ')' -> GoTokens.RPAREN
            c == '[' -> GoTokens.LBRACKET
            c == ']' -> GoTokens.RBRACKET
            c in OPERATORS -> {
                // Go's longest-token rule matters to indentation: := is not a
                // label colon, and ... is not three field selectors.
                val width = when {
                    start + 3 <= end && text.subSequence(start, start + 3).toString() in THREE_CHAR_OPERATORS -> 3
                    start + 2 <= end && text.subSequence(start, start + 2).toString() in TWO_CHAR_OPERATORS -> 2
                    else -> 1
                }
                next = start + width
                GoTokens.OPERATOR
            }
            else -> TokenType.BAD_CHARACTER
        }
    }

    /** Token boundaries follow Go; validation/diagnostics belong to gopls. */
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
        if (peek('.')) { next++; scanDigits(radix) }
        if (next < end && text[next] in "eEpP") {
            next++
            if (next < end && text[next] in "+-") next++
            scanDigits(10)
        }
        if (peek('i')) next++
    }

    private fun scanDigits(radix: Int) {
        // As in the Go scanner's error recovery, binary/octal invalid decimal
        // digits stay in the numeric token; no following identifier is consumed.
        while (next < end && (text[next] == '_' || text[next].isAsciiDigit() ||
                (radix == 16 && text[next] in "aAbBcCdDeEfF"))) next++
    }

    private fun peek(c: Char) = next < end && text[next] == c
    private fun codePointAt(offset: Int): Int {
        val first = text[offset]
        return if (Character.isHighSurrogate(first) && offset + 1 < end && Character.isLowSurrogate(text[offset + 1]))
            Character.toCodePoint(first, text[offset + 1]) else first.code
    }

    private fun Int.isGoLetter() = this == '_'.code || Character.isLetter(this)
    private fun Char.isAsciiDigit() = this in '0'..'9'
    private fun Char.isGoWhitespace() = this == ' ' || this == '\t' || this == '\r' || this == '\n'

    private companion object {
        const val OPERATORS = "+-*/%&|^<>=!:;.,~"
        val THREE_CHAR_OPERATORS = setOf("<<=", ">>=", "&^=", "...")
        val TWO_CHAR_OPERATORS = setOf("+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "<<", ">>", "&^", "&&", "||", "<-", "++", "--", "==", "!=", "<=", ">=", ":=")
    }
}
