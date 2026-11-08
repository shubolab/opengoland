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

/** Lexical highlighting is local; gopls supplies semantic analysis. */
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
            c.isWhitespace() -> { while (next < end && text[next].isWhitespace()) next++; TokenType.WHITE_SPACE }
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
                    val v = text[next++]
                    if (v == c) break
                    if (v == '\\' && c != '`' && next < end) next++
                    if (v == '\n' && c != '`') break
                }
                GoTokens.STRING
            }
            Character.isUnicodeIdentifierStart(text.codePointAt(start)) || c == '_' -> {
                next = start + Character.charCount(text.codePointAt(start))
                while (next < end) {
                    val cp = text.codePointAt(next)
                    if (!Character.isUnicodeIdentifierPart(cp)) break
                    next += Character.charCount(cp)
                }
                if (text.subSequence(start, next).toString() in GoTokens.keywords) GoTokens.KEYWORD else GoTokens.IDENTIFIER
            }
            c.isDigit() || (c == '.' && next < end && text[next].isDigit()) -> {
                while (next < end) {
                    val n = text[next]
                    if (n.isLetterOrDigit() || n == '_' || n == '.' || ((n == '+' || n == '-') && text[next - 1] in "eEpP")) next++ else break
                }
                GoTokens.NUMBER
            }
            c == '{' -> GoTokens.LBRACE
            c == '}' -> GoTokens.RBRACE
            c == '(' -> GoTokens.LPAREN
            c == ')' -> GoTokens.RPAREN
            c == '[' -> GoTokens.LBRACKET
            c == ']' -> GoTokens.RBRACKET
            else -> GoTokens.OPERATOR
        }
    }
    private fun peek(c: Char) = next < end && text[next] == c
    private fun CharSequence.codePointAt(offset: Int): Int = Character.codePointAt(this, offset)
}
