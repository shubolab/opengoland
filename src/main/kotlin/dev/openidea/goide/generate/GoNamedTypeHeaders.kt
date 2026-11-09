package dev.openidea.goide.generate

import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.TokenType
import dev.openidea.goide.lang.GoLexer
import dev.openidea.goide.lang.GoTokens
import dev.openidea.goide.semantic.GoIdentifiers

internal data class GoNamedTypeHeader(val name: String, val nameOffset: Int, val headerEnd: Int, val bodyEnd: Int, val alias: Boolean)

/** Named package declarations only. This is a lexical UI hint; typed planners own semantics. */
internal object GoNamedTypeHeaders {
    private data class Token(val text: String, val start: Int, val end: Int, val identifier: Boolean, val newline: Boolean)
    private val brackets = mapOf("(" to ")", "[" to "]", "{" to "}")
    fun scan(source: CharSequence, kind: String): List<GoNamedTypeHeader> {
        require(kind == "struct" || kind == "interface")
        val tokens = mutableListOf<Token>()
        val lexer = GoLexer()
        lexer.start(source)
        var previousEnd = 0
        while (lexer.tokenType != null) {
            if (tokens.size % 256 == 0) ProgressManager.checkCanceled()
            if (lexer.tokenType != TokenType.WHITE_SPACE && lexer.tokenType != GoTokens.COMMENT) {
                tokens += Token(source.subSequence(lexer.tokenStart, lexer.tokenEnd).toString(), lexer.tokenStart, lexer.tokenEnd,
                    lexer.tokenType == GoTokens.IDENTIFIER, source.subSequence(previousEnd, lexer.tokenStart).contains('\n'))
                previousEnd = lexer.tokenEnd
            }
            lexer.advance()
        }
        val closes = IntArray(tokens.size) { -1 }
        val stack = ArrayDeque<Int>()
        tokens.forEachIndexed { index, token ->
            if (token.text in brackets) stack.addLast(index)
            else if (token.text in brackets.values) {
                val open = stack.lastOrNull()
                if (open != null && brackets[tokens[open].text] == token.text) { closes[open] = index; stack.removeLast() }
                else stack.clear()
            }
        }
        fun generic(start: Int, end: Int): Boolean {
            if (tokens.getOrNull(start + 1)?.identifier != true) return false
            var index = start + 2
            while (index < end) {
                if (tokens[index].text == ",") return true
                index = if (closes[index] > index) closes[index] + 1 else index + 1
            }
            // Array lengths such as [N], [N*2] and [len("x")] are not generic headers.
            val constraint = tokens.getOrNull(start + 2) ?: return false
            return constraint.identifier || constraint.text in setOf("interface", "struct", "map", "chan", "func", "[", "~") ||
                constraint.text == "<-" && tokens.getOrNull(start + 3)?.text == "chan"
        }
        fun declaration(start: Int, limit: Int): Pair<GoNamedTypeHeader, Int>? {
            val name = tokens.getOrNull(start)?.takeIf { it.identifier && GoIdentifiers.valid(it.text) } ?: return null
            var index = start + 1
            if (tokens.getOrNull(index)?.text == "[") {
                val end = closes[index]
                if (end < 0 || end >= limit || !generic(index, end)) return null
                index = end + 1
            }
            val alias = tokens.getOrNull(index)?.text == "="
            if (alias) index++
            if (tokens.getOrNull(index)?.text != kind || tokens.getOrNull(index + 1)?.text != "{") return null
            val end = closes[index + 1]
            if (end < 0 || end >= limit) return null
            return GoNamedTypeHeader(name.text, name.start, tokens[index + 1].end, tokens[end].end, alias) to end
        }
        fun canEnd(token: Token) = token.identifier || token.text in setOf(")", "]", "}") || token.text.firstOrNull()?.let { it.isDigit() || it in "\"'`" } == true
        fun nextSpec(start: Int, limit: Int): Int {
            var index = start + 1
            while (index < limit) {
                if (tokens[index].text == ";") return index + 1
                if (tokens[index].newline && canEnd(tokens[index - 1])) return index
                index = if (closes[index] > index) closes[index] + 1 else index + 1
            }
            return limit
        }
        val result = mutableListOf<GoNamedTypeHeader>()
        var index = 0
        while (index < tokens.size) {
            if (index % 256 == 0) ProgressManager.checkCanceled()
            if (tokens[index].text == "type") {
                if (tokens.getOrNull(index + 1)?.text == "(" && closes[index + 1] > index + 1) {
                    val end = closes[index + 1]
                    var spec = index + 2
                    while (spec < end) {
                        if (tokens[spec].text == ";") { spec++; continue }
                        val declared = declaration(spec, end)
                        if (declared != null) { result += declared.first; spec = declared.second + 1 }
                        else spec = nextSpec(spec, end)
                    }
                    index = end + 1
                    continue
                }
                val declared = declaration(index + 1, tokens.size)
                if (declared != null) { result += declared.first; index = declared.second + 1; continue }
            }
            if (tokens[index].text in brackets && closes[index] < 0) break
            if (index < tokens.size && closes[index] > index) index = closes[index] + 1 else index++
        }
        return result
    }
}
