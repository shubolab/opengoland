package dev.openidea.goide.generate.tags

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.editor.CaretState
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import dev.openidea.goide.lang.GoLanguage
import dev.openidea.goide.lang.GoLexer
import dev.openidea.goide.lang.GoTokens
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/**
 * A native editing entry, independent of the typed bulk-tag planner. The public
 * reference describes carets inside values, not a selection/replacement policy.
 * We place unselected carets at each value's beginning, preserving all text.
 */
class GoStructTagValuesIntention : IntentionAction, DumbAware {
    @Volatile private var label = "Edit tag values"
    override fun getText() = label
    override fun getFamilyName() = "Go struct tag values"
    override fun startInWriteAction() = false

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        val targets = targets(project, editor, file) ?: return false
        label = "Edit tag values for ${targets.key}"
        return true
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        // Never retain offsets from isAvailable: the native document may change
        // while Alt+Enter is open. This synchronous operation does no I/O.
        val targets = targets(project, editor, file) ?: return
        editor ?: return
        editor.caretModel.setCaretsAndSelections(targets.offsets.map {
            val position = editor.offsetToLogicalPosition(it)
            CaretState(position, position, position)
        })
    }

    private fun targets(project: Project, editor: Editor?, file: PsiFile?): GoTagCaretTargets? {
        if (project.isDisposed || editor == null || editor.isDisposed || editor.project != project || editor.isViewer ||
            file == null || !file.isValid || file.project != project || file.language != GoLanguage ||
            !editor.document.isWritable || !editor.caretModel.supportsMultipleCarets()) return null
        val virtual = file.virtualFile ?: return null
        if (!virtual.isValid || !virtual.isInLocalFileSystem || !virtual.isWritable ||
            PsiDocumentManager.getInstance(project).getCachedPsiFile(editor.document) !== file) return null
        val result = GoStructTagCarets.find(editor.document.immutableCharSequence, editor.caretModel.offset) ?: return null
        return result.takeIf { it.offsets.size <= editor.caretModel.maxCaretCount }
    }
}

internal data class GoTagCaretTargets(val key: String, val offsets: List<Int>)

/** Lexical scope only. GoLexer offsets, decoded spans and native carets are UTF-16. */
internal object GoStructTagCarets {
    private data class Token(val kind: IElementType, val text: String, val start: Int, val end: Int, val newline: Boolean)
    private data class PairSpan(val key: String, val start: Int, val end: Int, val valueStart: Int)
    private data class Mapped(val text: String, val starts: List<Int>, val ends: List<Int>)
    private data class ByteSpan(val byte: Byte, val start: Int, val end: Int)

    fun find(source: CharSequence, offset: Int): GoTagCaretTargets? {
        if (offset !in 0..source.length) return null
        val tokens = ArrayList<Token>()
        val lexer = GoLexer()
        lexer.start(source)
        var previousEnd = 0
        while (lexer.tokenType != null) {
            if (tokens.size % 256 == 0) ProgressManager.checkCanceled()
            val kind = lexer.tokenType!!
            if (kind != TokenType.WHITE_SPACE && kind != GoTokens.COMMENT) {
                tokens += Token(kind, source.subSequence(lexer.tokenStart, lexer.tokenEnd).toString(), lexer.tokenStart,
                    lexer.tokenEnd, source.subSequence(previousEnd, lexer.tokenStart).contains('\n'))
                previousEnd = lexer.tokenEnd
            }
            lexer.advance()
        }
        val stack = ArrayDeque<Int>()
        val closes = HashMap<Int, Int>()
        for ((i, token) in tokens.withIndex()) {
            if (token.text in listOf("{", "(", "[")) stack.addLast(i)
            if (token.text in listOf("}", ")", "]")) {
                val open = stack.removeLastOrNull()
                if (open == null || tokens[open].text + token.text !in listOf("{}", "()", "[]")) {
                    // Recovery invalidates unfinished scopes only. A completed
                    // struct elsewhere remains editable while another function
                    // or block is being written. The target itself still needs
                    // a fully matched range (including its nested delimiters).
                    stack.clear()
                    continue
                }
                closes[open] = i
            }
        }
        val opening = closes.keys.filter { it > 0 && tokens[it - 1].text == "struct" && tokens[it].text == "{" &&
            offset >= tokens[it].start && offset < tokens[closes.getValue(it)].end }
            .maxByOrNull { tokens[it].start } ?: return null
        val closing = closes.getValue(opening)
        val tags = ArrayList<List<PairSpan>>()
        var first = opening + 1
        var i = first
        fun field(end: Int): Boolean {
            if (first == end) return true
            val last = tokens[end - 1]
            if (last.kind != GoTokens.STRING) return true
            // A tag must follow a complete field type, not be a field name or
            // an array-length string expression (nested delimiters are skipped).
            if (!fieldType(tokens, first, end - 1, closes)) return false
            val mapped = unquote(last.text, last.start) ?: return false
            tags += pairs(mapped) ?: return false
            return true
        }
        while (i < closing) {
            val token = tokens[i]
            val implicit = i > first && token.newline && endsStatement(tokens[i - 1])
            if (token.text == ";" || implicit) {
                if (!field(i)) return null
                first = if (token.text == ";") i + 1 else i
            }
            val end = closes[i]
            i = if (end != null) end + 1 else i + 1
        }
        if (!field(closing)) return null
        val active = tags.flatten().singleOrNull { offset >= it.start && offset < it.end } ?: return null
        val matches = ArrayList<Int>()
        for (tag in tags) {
            val same = tag.filter { it.key == active.key }
            if (same.size > 1) return null // Do not choose one duplicate silently.
            same.singleOrNull()?.let { matches += it.valueStart }
        }
        if (matches.isEmpty()) return null
        // Keep the selected field as the primary caret (the platform uses the
        // last state as primary); every other field remains a native caret.
        return GoTagCaretTargets(active.key, matches.filter { it != active.valueStart } + active.valueStart)
    }

    private fun endsStatement(token: Token) = token.kind == GoTokens.IDENTIFIER || token.kind == GoTokens.NUMBER ||
        token.kind == GoTokens.STRING || token.text in listOf(")", "]", "}", "break", "continue", "fallthrough", "return")

    private fun fieldType(tokens: List<Token>, first: Int, end: Int, closes: Map<Int, Int>): Boolean {
        fun type(start: Int): Int {
            if (start >= end) return -1
            var i = start
            when (tokens[i].text) {
                "*" -> return type(i + 1)
                "(" -> return if (closes[i] == end - 1 && type(i + 1) == end - 1) end else -1
                "struct", "interface" -> return if (tokens.getOrNull(i + 1)?.text == "{") (closes[i + 1] ?: -2) + 1 else -1
                "[" -> { i = (closes[i] ?: return -1) + 1; return type(i) }
                "map" -> { if (tokens.getOrNull(i + 1)?.text != "[") return -1; return type((closes[i + 1] ?: return -1) + 1) }
                "chan" -> { i++; if (tokens.getOrNull(i)?.text == "<" && tokens.getOrNull(i + 1)?.text == "-") i += 2; return type(i) }
                "<" -> return if (tokens.getOrNull(i + 1)?.text == "-" && tokens.getOrNull(i + 2)?.text == "chan") type(i + 3) else -1
                "func" -> {
                    if (tokens.getOrNull(i + 1)?.text != "(") return -1
                    i = (closes[i + 1] ?: return -1) + 1
                    if (i == end) return i
                    if (tokens.getOrNull(i)?.text == "(") return (closes[i] ?: -2) + 1
                    return type(i)
                }
            }
            if (tokens[i].kind != GoTokens.IDENTIFIER) return -1
            i++
            if (tokens.getOrNull(i)?.text == ".") {
                if (tokens.getOrNull(i + 1)?.kind != GoTokens.IDENTIFIER) return -1
                i += 2
            }
            if (tokens.getOrNull(i)?.text == "[") i = (closes[i] ?: return -1) + 1
            return i
        }
        if (type(first) == end) return true // Embedded field.
        var i = first
        if (tokens.getOrNull(i)?.kind != GoTokens.IDENTIFIER) return false
        i++
        while (i < end && tokens[i].text == ",") {
            if (tokens.getOrNull(i + 1)?.kind != GoTokens.IDENTIFIER) return false
            i += 2
        }
        return type(i) == end
    }

    private fun pairs(mapped: Mapped): List<PairSpan>? {
        val text = mapped.text
        val out = ArrayList<PairSpan>()
        var i = 0
        while (i < text.length) {
            while (i < text.length && text[i] == ' ') i++
            if (i == text.length) break
            val key = i
            while (i < text.length && text[i] > ' ' && text[i] != ':' && text[i] != '"' && text[i] != '\u007f') i++
            if (i == key || i + 1 >= text.length || text[i] != ':' || text[i + 1] != '"') return null
            val name = text.substring(key, i)
            val quote = ++i
            i++
            while (i < text.length && text[i] != '"') { i += if (text[i] == '\\') 2 else 1 }
            if (i >= text.length || unquote(text.substring(quote, i + 1), 0) == null) return null
            out += PairSpan(name, mapped.starts[key], mapped.ends[i], mapped.ends[quote])
            i++
        }
        return out
    }

    /** Decode Go source strings, retaining the source span of each UTF-16 unit. */
    private fun unquote(literal: String, base: Int): Mapped? {
        if (literal.length < 2 || literal.first() !in "\"`" || literal.last() != literal.first()) return null
        val raw = literal.first() == '`'
        val bytes = ArrayList<ByteSpan>()
        fun emit(value: ByteArray, start: Int, end: Int) { value.forEach { bytes += ByteSpan(it, base + start, base + end) } }
        var i = 1
        while (i < literal.lastIndex) {
            val start = i
            var cp = literal.codePointAt(i)
            if (cp in 0xd800..0xdfff) return null
            i += Character.charCount(cp)
            if (raw) {
                if (cp == '`'.code) return null
                if (cp == '\r'.code) continue // Go raw strings discard carriage returns.
            } else {
                if (cp == '\n'.code || cp == '\r'.code || cp == '"'.code) return null
                if (cp == '\\'.code) {
                    if (i >= literal.lastIndex) return null
                    val escape = literal[i++]
                    val simple = when (escape) { 'a' -> 7; 'b' -> 8; 'f' -> 12; 'n' -> 10; 'r' -> 13; 't' -> 9; 'v' -> 11; '\\' -> 92; '"' -> 34; else -> null }
                    if (simple != null) cp = simple else {
                        val count = when (escape) { 'x' -> 2; 'u' -> 4; 'U' -> 8; in '0'..'7' -> 3; else -> return null }
                        val octal = escape in '0'..'7'
                        val digits = if (octal) i - 1 else i
                        if (digits + count > literal.lastIndex) return null
                        val encoded = literal.substring(digits, digits + count)
                        if (encoded.any { it.digitToIntOrNull(if (octal) 8 else 16) == null }) return null
                        val value = encoded.toLongOrNull(if (octal) 8 else 16) ?: return null
                        i = digits + count
                        if (octal || escape == 'x') {
                            if (value > 255) return null
                            emit(byteArrayOf(value.toByte()), start, i)
                            continue
                        }
                        if (value > 0x10ffff || value in 0xd800L..0xdfffL) return null
                        cp = value.toInt()
                    }
                }
            }
            emit(String(Character.toChars(cp)).toByteArray(Charsets.UTF_8), start, i)
        }
        val data = bytes.map { it.byte }.toByteArray()
        val decoded = try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString() } catch (_: java.nio.charset.CharacterCodingException) { return null }
        val starts = ArrayList<Int>(); val ends = ArrayList<Int>()
        i = 0
        var b = 0
        while (i < decoded.length) {
            val cp = decoded.codePointAt(i)
            val width = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
            repeat(Character.charCount(cp)) { starts += bytes[b].start; ends += bytes[b + width - 1].end }
            b += width; i += Character.charCount(cp)
        }
        return Mapped(decoded, starts, ends)
    }
}
