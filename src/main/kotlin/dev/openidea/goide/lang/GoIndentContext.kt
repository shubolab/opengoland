package dev.openidea.goide.lang

import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import kotlin.math.max

/** Local lexical context for incomplete Go while typing. No PSI rebuild, SDK or filesystem access. */
internal class GoIndentContext(private val text: CharSequence) {
    internal data class Token(val type: IElementType, val start: Int, val end: Int, val value: String)
    internal data class Options(val useTabs: Boolean, val tabSize: Int, val indentSize: Int, val continuationSize: Int, val smartTabs: Boolean = false) {
        fun render(columns: Int, continuationBase: Int = columns): String {
            val width = columns.coerceAtLeast(0)
            if (!useTabs) return " ".repeat(width)
            val base = if (smartTabs) continuationBase.coerceIn(0, width) else width
            val tab = tabSize.coerceAtLeast(1)
            return "\t".repeat(base / tab) + " ".repeat(base % tab + width - base)
        }
    }
    private enum class Kind { BLOCK, STRUCT, INTERFACE, COMPOSITE, SWITCH, SELECT, PAREN, BRACKET, GROUP }
    private data class Header(val last: String? = null, val body: Kind? = null, val pendingControl: Kind? = null, val function: Boolean = false, val typeContext: Boolean = false) {
        fun append(value: String): Header = when (value) {
            "switch" -> Header(value, Kind.SWITCH, Kind.SWITCH)
            "select" -> Header(value, Kind.SELECT)
            "if", "for" -> Header(value, Kind.BLOCK, Kind.BLOCK)
            "else" -> Header(value, Kind.BLOCK)
            "map" -> copy(last = value, typeContext = true)
            "func" -> if (typeContext) copy(last = value) else copy(last = value, body = Kind.BLOCK, function = true)
            "=", ":=" -> Header(value, pendingControl, pendingControl)
            // Ordinary statements end here. A control initializer still owns
            // its following condition/post statement and eventual body.
            ";" -> if (pendingControl == null) Header() else Header(value, pendingControl, pendingControl)
            else -> copy(last = value)
        }
        fun afterBlock(): Header = if (function && pendingControl != null) Header("}", pendingControl, pendingControl) else Header()
    }
    private data class Frame(val tokenIndex: Int, val opening: String, val line: Int, val base: Int, val child: Int, val kind: Kind, val header: Header, var caseHeader: Boolean = false, var caseBody: Boolean = false) {
        fun body() = if (kind == Kind.SWITCH || kind == Kind.SELECT) { if (caseBody) child else base } else child
    }
    private val tokens: List<Token> = buildList {
        val lexer = GoLexer(); lexer.start(text)
        while (lexer.tokenType != null) {
            if (lexer.tokenType != TokenType.WHITE_SPACE) add(Token(lexer.tokenType!!, lexer.tokenStart, lexer.tokenEnd, text.subSequence(lexer.tokenStart, lexer.tokenEnd).toString()))
            lexer.advance()
        }
    }
    private val code = tokens.filter { it.type != GoTokens.COMMENT }

    fun protectedTokenAt(offset: Int): Token? = tokens.firstOrNull {
        it.type in setOf(GoTokens.STRING, GoTokens.COMMENT) && it.start < offset &&
            (offset < it.end || offset == it.end && unclosedLiteral(it))
    }
    private fun unclosedLiteral(token: Token): Boolean {
        if (token.type != GoTokens.STRING) return false
        val value = token.value
        if (value.length == 1 || value.lastOrNull() != value.firstOrNull()) return true
        if (value.first() == '`') return false
        var escapes = 0
        var index = value.lastIndex - 1
        while (index >= 0 && value[index--] == '\\') escapes++
        return escapes % 2 == 1
    }

    /** null means leave literal/comment content untouched, not "use another formatter". */
    fun lineIndent(offset: Int, options: Options): String? {
        val at = offset.coerceIn(0, text.length)
        if (protectedTokenAt(at) != null) return null
        val start = lineStart(at)
        val end = lineEnd(start)
        if (tokens.any { it.start < start && it.end > start && (it.type == GoTokens.STRING || it.type == GoTokens.COMMENT) }) return null
        val frames = mutableListOf<Frame>()
        // Each frame is pushed/popped once. Separate delimiter indexes keep a
        // missing/mismatched closer and a long non-curly nesting chain linear.
        val openingFrames = mapOf("{" to mutableListOf<Int>(), "(" to mutableListOf(), "[" to mutableListOf())
        val pairs = mutableMapOf<Int, Int>()
        var previous: Token? = null
        var header = Header()
        var scanOffset = 0
        var openingLine = 0
        var indentLine = -1
        var actualIndent = 0
        for ((index, token) in code.withIndex()) {
            if (token.start >= start) break
            // A monotonic cursor avoids rescanning the same long line for
            // every opener. Its leading whitespace is also measured only once.
            while (scanOffset < token.start) if (text[scanOffset++] == '\n') openingLine = scanOffset
            if (indentLine != openingLine) { indentLine = openingLine; actualIndent = leadingColumns(openingLine, options) }
            val before = if (previous != null && hasLineBreak(previous.end, token.start) && canEndStatement(previous)) Header() else header
            header = before.append(token.value)
            when (token.value) {
                "{", "(", "[" -> {
                    val curly = openingFrames.getValue("{").lastOrNull()?.let(frames::get)
                    val parent = frames.lastOrNull()
                    val structural = curly?.body() ?: 0
                    val continued = if (parent != null && parent.opening != "{" && parent.line != openingLine) parent.body() else 0
                    val base = max(actualIndent, max(structural, continued))
                    val kind = when (token.value) {
                        "[" -> Kind.BRACKET
                        "(" -> if (previous?.value in setOf("import", "var", "const", "type")) Kind.GROUP else Kind.PAREN
                        else -> braceKind(index, parent, before)
                    }
                    val increment = if (kind in setOf(Kind.PAREN, Kind.BRACKET)) options.continuationSize else options.indentSize
                    openingFrames.getValue(token.value) += frames.size
                    frames += Frame(index, token.value, openingLine, base, base + increment.coerceAtLeast(0), kind, before)
                    header = Header()
                }
                "}", ")", "]" -> {
                    val opening = mapOf("}" to "{", ")" to "(", "]" to "[")[token.value]
                    val indexOfFrame = openingFrames.getValue(opening!!).lastOrNull()
                    if (indexOfFrame != null) {
                        val match = frames[indexOfFrame]
                        pairs[index] = match.tokenIndex
                        while (frames.size > indexOfFrame) {
                            val removed = frames.removeAt(frames.lastIndex)
                            openingFrames.getValue(removed.opening).removeAt(openingFrames.getValue(removed.opening).lastIndex)
                        }
                        // Skip balanced header expressions without walking back
                        // over all earlier pairs for each subsequent brace.
                        header = when (match.kind) {
                            Kind.BLOCK -> match.header.afterBlock()
                            Kind.SWITCH, Kind.SELECT -> Header()
                            Kind.BRACKET -> match.header.copy(typeContext = match.header.typeContext || match.header.last in arrayTypeStarts)
                            Kind.COMPOSITE -> match.header.copy(typeContext = false)
                            else -> match.header
                        }
                    }
                }
                "case", "default" -> frames.lastOrNull()?.takeIf { it.kind == Kind.SWITCH || it.kind == Kind.SELECT }?.let { it.caseHeader = true; it.caseBody = false }
                ":" -> frames.lastOrNull()?.let {
                    if (it.caseHeader && it.kind in setOf(Kind.SWITCH, Kind.SELECT)) {
                        it.caseHeader = false; it.caseBody = true; header = Header()
                    } else if (it.kind in setOf(Kind.BLOCK, Kind.SWITCH, Kind.SELECT)) header = Header(":")
                }
            }
            previous = token
        }
        val current = code.firstOrNull { it.start >= start && it.start < end }
        if (current != null && current.value in setOf("}", ")", "]")) {
            val open = mapOf("}" to "{", ")" to "(", "]" to "[")[current.value]
            openingFrames.getValue(open!!).lastOrNull()?.let { return options.render(frames[it].base) }
        }
        val parent = frames.lastOrNull()
        if (current?.value in setOf("case", "default") && parent?.kind in setOf(Kind.SWITCH, Kind.SELECT)) return options.render(parent!!.base)
        // Go labels belong to the enclosing block, not the case-body or map-key
        // indentation rule. Do not apply this while typing ':' (it may become :=).
        val onLine = code.filter { it.start >= start && it.start < end }
        if (onLine.size >= 2 && onLine[0].type == GoTokens.IDENTIFIER && onLine[1].value == ":" && parent?.kind in setOf(Kind.BLOCK, Kind.SWITCH, Kind.SELECT)) return options.render(parent!!.base)
        var columns = parent?.body() ?: 0
        var continuationBase = if (parent?.kind in setOf(Kind.PAREN, Kind.BRACKET)) parent!!.base else columns
        val commaInList = previous?.value == "," && parent?.kind in setOf(Kind.PAREN, Kind.BRACKET, Kind.GROUP, Kind.COMPOSITE)
        if (previous?.value in continuationOperators && !commaInList || parent?.caseHeader == true) {
            val anchor = statementStartBefore(start, pairs)
            val anchorColumns = leadingColumns(lineStart(anchor), options)
            columns = max(columns, anchorColumns + options.continuationSize.coerceAtLeast(0))
            continuationBase = minOf(continuationBase, anchorColumns)
        }
        return options.render(columns, continuationBase)
    }

    private fun braceKind(index: Int, parent: Frame?, header: Header): Kind {
        when (code.getOrNull(index - 1)?.value) { "struct" -> return Kind.STRUCT; "interface" -> return Kind.INTERFACE }
        // A func inside map/array/slice element types is not a function
        // literal body. An outer actual func signature can itself return such
        // a type, so keep that body's marker distinct from the element type.
        if (header.typeContext && !header.function) return Kind.COMPOSITE
        header.body?.let { return it }
        if (header.last == null || header.last == ":" && parent?.kind in setOf(Kind.BLOCK, Kind.SWITCH, Kind.SELECT)) return Kind.BLOCK
        return Kind.COMPOSITE
    }
    private fun statementStartBefore(offset: Int, pairs: Map<Int, Int>): Int {
        var i = code.indexOfLast { it.start < offset }
        if (i < 0) return 0
        var first = code[i].start
        while (i >= 0) {
            val token = code[i]
            if (token.value in setOf("{", "}", ";")) break
            first = token.start
            val pair = pairs[i]
            if (pair != null) { first = code[pair].start; i = pair - 1; continue }
            val previous = code.getOrNull(i - 1) ?: break
            if (hasLineBreak(previous.end, token.start) && canEndStatement(previous)) break
            i--
        }
        return first
    }
    private fun canEndStatement(token: Token) = token.type in setOf(GoTokens.IDENTIFIER, GoTokens.NUMBER, GoTokens.STRING) || token.value in setOf(")", "]", "}", "++", "--", "break", "continue", "fallthrough", "return")
    private fun hasLineBreak(start: Int, end: Int) = (start until end).any { text[it] == '\n' || text[it] == '\r' }
    fun lineStart(offset: Int): Int { var i = offset.coerceAtMost(text.length); while (i > 0 && text[i - 1] != '\n') i--; return i }
    fun startsCaseAt(offset: Int): Boolean {
        val start = lineStart(offset)
        val end = lineEnd(start)
        return code.firstOrNull { it.start >= start && it.start < end }?.value in setOf("case", "default")
    }
    private fun lineEnd(start: Int): Int { var i = start; while (i < text.length && text[i] != '\n') i++; return i }
    private fun leadingColumns(start: Int, options: Options): Int {
        var result = 0; var i = start
        while (i < text.length) { when (text[i++]) { ' ' -> result++; '\t' -> result += options.tabSize.coerceAtLeast(1) - result % options.tabSize.coerceAtLeast(1); else -> return result } }
        return result
    }
    companion object {
        private val arrayTypeStarts = setOf(null, "=", ":=", ",", ":", "return")
        private val continuationOperators = setOf("+", "-", "*", "/", "%", "&", "|", "^", "&^", "<<", ">>", "==", "!=", "<", "<=", ">", ">=", "&&", "||", "=", ":=", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "<<=", ">>=", "&^=", "<-", ",", ".")
    }
}
