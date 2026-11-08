package dev.openidea.goide.generate.methods

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.codeInsight.hints.declarative.HintColorKind
import com.intellij.codeInsight.hints.declarative.HintFormat
import com.intellij.codeInsight.hints.declarative.InlayActionData
import com.intellij.codeInsight.hints.declarative.InlayActionHandler
import com.intellij.codeInsight.hints.declarative.InlayActionPayload
import com.intellij.codeInsight.hints.declarative.InlayHintsProvider
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.codeInsight.hints.declarative.InlineInlayPosition
import com.intellij.codeInsight.hints.declarative.OwnBypassCollector
import com.intellij.codeInsight.hints.declarative.StringInlayActionPayload
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.TokenType
import dev.openidea.goide.lang.GoLanguage
import dev.openidea.goide.lang.GoLexer
import dev.openidea.goide.lang.GoTokens
import dev.openidea.goide.semantic.GoIdentifiers
import java.util.concurrent.atomic.AtomicLong

/** A lexical entry point only. The existing typed planner determines what can actually be implemented. */
class GoImplementInterfaceHints : InlayHintsProvider, DumbAware {
    override fun createCollector(file: PsiFile, editor: Editor): OwnBypassCollector? {
        if (file.language != GoLanguage || editor.isDisposed || editor.project !== file.project || file.project.isDisposed) return null
        return object : OwnBypassCollector {
            override fun collectHintsForFile(file: PsiFile, sink: InlayTreeSink) {
                if (!file.isValid || editor.isDisposed || file.project.isDisposed) return
                val document = editor.document
                if (PsiDocumentManager.getInstance(file.project).getCachedPsiFile(document) !== file) return
                val stamp = document.modificationStamp
                val headers = GoInterfaceHeaders.scan(document.immutableCharSequence)
                if (stamp != document.modificationStamp) return
                val virtual = file.virtualFile
                val clickable = virtual != null && virtual.isValid && virtual.isInLocalFileSystem && virtual.isWritable && !editor.isViewer
                for (header in headers) {
                    val action = if (clickable) InlayActionData(StringInlayActionPayload(JsonObject().apply {
                        addProperty("document", documentIdentity(document)); addProperty("stamp", stamp)
                        addProperty("file", virtual!!.url); addProperty("offset", header.nameOffset); addProperty("name", header.name)
                    }.toString()), ACTION_ID) else null
                    sink.addPresentation(InlineInlayPosition(header.headerEnd, true), tooltip = "Choose a type and preview its interface methods.",
                        hintFormat = HintFormat.default.withColorKind(HintColorKind.TextWithoutBackground)) {
                        text("Implement interface", action)
                    }
                }
            }
        }
    }

    companion object {
        const val PROVIDER_ID = "opengoland.implement.interface"
        const val ACTION_ID = "opengoland.implement.interface.click"
    }
}

/** Clicking never resolves files or starts a process; the service queues its analysis on a worker. */
class GoImplementInterfaceInlayAction internal constructor(private val invoke: (PsiFile, Editor) -> Unit) : InlayActionHandler {
    constructor() : this({ file, editor -> GoImplementMethodsService.get(file.project).invoke(file, editor) })

    override fun handleClick(e: EditorMouseEvent, payload: InlayActionPayload) {
        if (!ApplicationManager.getApplication().isDispatchThread || e.editor.isDisposed) return
        val editor = e.editor
        val project = editor.project?.takeUnless { it.isDisposed } ?: return
        val encoded = (payload as? StringInlayActionPayload)?.text?.takeIf { it.length <= 8192 } ?: return
        val value = runCatching { JsonParser.parseString(encoded).asJsonObject }.getOrNull() ?: return
        val document = editor.document
        val target = runCatching {
            ReadAction.compute<Triple<PsiFile, Int, Int>?, RuntimeException> {
                if (value.get("document").asLong != document.getUserData(DOCUMENT_ID) || value.get("stamp").asLong != document.modificationStamp) return@compute null
                val file = PsiDocumentManager.getInstance(project).getCachedPsiFile(document) ?: return@compute null
                val virtual = file.virtualFile ?: return@compute null
                if (!file.isValid || file.project !== project || file.language != GoLanguage || !virtual.isValid || !virtual.isInLocalFileSystem ||
                    !virtual.isWritable || editor.isViewer || virtual.url != value.get("file").asString) return@compute null
                val offset = value.get("offset").asInt
                val name = value.get("name").asString
                val text = document.immutableCharSequence
                if (!GoIdentifiers.valid(name) || offset < 0 || offset > text.length - name.length ||
                    text.subSequence(offset, offset + name.length).toString() != name || GoIdentifiers.wordAt(text, offset) != name) return@compute null
                Triple(file, offset, name.length)
            }
        }.getOrNull() ?: return
        editor.caretModel.moveToOffset(target.second)
        editor.selectionModel.setSelection(target.second, target.second + target.third)
        invoke(target.first, editor)
    }
}

private val DOCUMENT_ID = Key.create<Long>("opengoland.interface.inlay.document")
private val nextDocumentId = AtomicLong()
private fun documentIdentity(document: Document): Long = synchronized(document) {
    document.getUserData(DOCUMENT_ID) ?: nextDocumentId.incrementAndGet().also { document.putUserData(DOCUMENT_ID, it) }
}

internal data class GoInterfaceHeader(val name: String, val nameOffset: Int, val headerEnd: Int)

/** Tokens and offsets remain UTF-16; comments and quoted braces never affect structural balance. */
internal object GoInterfaceHeaders {
    private data class Token(val text: String, val start: Int, val end: Int, val identifier: Boolean, val newline: Boolean)
    private val brackets = mapOf("(" to ")", "[" to "]", "{" to "}")
    fun scan(source: CharSequence): List<GoInterfaceHeader> {
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
                constraint.text == "<" && tokens.getOrNull(start + 3)?.text == "-" && tokens.getOrNull(start + 4)?.text == "chan"
        }
        fun declaration(start: Int, limit: Int): Pair<GoInterfaceHeader, Int>? {
            val name = tokens.getOrNull(start)?.takeIf { it.identifier && GoIdentifiers.valid(it.text) } ?: return null
            var index = start + 1
            if (tokens.getOrNull(index)?.text == "[") {
                val end = closes[index]
                if (end < 0 || end >= limit || !generic(index, end)) return null
                index = end + 1
            }
            if (tokens.getOrNull(index)?.text == "=") index++
            if (tokens.getOrNull(index)?.text != "interface" || tokens.getOrNull(index + 1)?.text != "{") return null
            val end = closes[index + 1]
            if (end < 0 || end >= limit) return null
            return GoInterfaceHeader(name.text, name.start, tokens[index + 1].end) to end
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
        val result = mutableListOf<GoInterfaceHeader>()
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
