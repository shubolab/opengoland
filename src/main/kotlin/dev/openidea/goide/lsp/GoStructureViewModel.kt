package dev.openidea.goide.lsp

import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.util.treeView.NodeDescriptorProvidingKey
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ex.DocumentEx
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.TokenType
import com.intellij.psi.impl.FakePsiElement
import com.redhat.devtools.lsp4ij.client.features.LSPClientFeatures
import com.redhat.devtools.lsp4ij.LSPFileSupport
import com.redhat.devtools.lsp4ij.features.documentSymbol.DocumentSymbolData
import com.redhat.devtools.lsp4ij.features.documentSymbol.LSPDocumentSymbolStructureViewModel
import dev.openidea.goide.lang.GoLanguage
import dev.openidea.goide.lang.GoLexer
import dev.openidea.goide.lang.GoTokens
import dev.openidea.goide.semantic.GoIdentifiers
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SymbolKind
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletionException

/** Reorganizes one immutable upstream result; the original data/cache/navigation remain owned by LSP4IJ. */
class GoStructureViewModel(file: PsiFile, editor: Editor?) : LSPDocumentSymbolStructureViewModel(file, editor) {
    private val selectionEditor = editor
    private val groupedRoot = GoReceiverStructureRoot(super.getRoot(), file)
    private val packageRoot = GoPackageStructureRoot(file, groupedRoot) { fireModelUpdate() }
    init { Disposer.register(this, packageRoot) }
    override fun getRoot(): StructureViewTreeElement = packageRoot
    override fun getFilters() = GoStructureControls.filters() + if (packageRoot.isAvailable) arrayOf(packageRoot.filter) else emptyArray()
    override fun getSorters() = GoStructureControls.sorters()
    override fun findAcceptableElement(element: PsiElement?): Any? {
        if (element == null) return null
        packageRoot.currentElement(element)?.let { return it }
        val file = element.containingFile ?: return null
        val source = GoStructureSource.create(file) ?: return null
        if (!packageRoot.isAvailable) cachedDocumentElement(element, source)?.let { return it }
        val offset = element.textRange?.startOffset ?: return null
        // FileStructurePopup captures this target before its asynchronous tree load.
        // Keep a real versioned source origin whose parent can become the actual
        // cached symbol, rather than equating different protocol DTOs or waiting here.
        return GoStructureSelectionOrigin(file, offset, source,
            { !Disposer.isDisposed(this) && selectionEditor?.isDisposed != true },
            { completedSelectionToken(file) },
            { origin -> packageRoot.currentElement(origin) ?: cachedDocumentElement(origin) })
    }

    private fun completedSelectionToken(file: PsiFile): Any {
        val packageFuture = packageRoot.support.validLSPFuture?.takeIf { it.isDone && !it.isCompletedExceptionally }
        val documentFuture = if (LSPFileSupport.hasSupport(file))
            LSPFileSupport.getSupport(file).documentSymbolSupport.validLSPFuture?.takeIf { it.isDone && !it.isCompletedExceptionally } else null
        return packageFuture to documentFuture
    }

    private fun cachedDocumentElement(element: PsiElement, snapshot: GoStructureSource? = null): DocumentSymbolData? {
        val file = element.containingFile ?: return null
        val source = snapshot ?: GoStructureSource.create(file) ?: return null
        if (!LSPFileSupport.hasSupport(file)) return null
        val support = LSPFileSupport.getSupport(file).documentSymbolSupport
        val future = support.validLSPFuture?.takeIf { it.isDone && !it.isCompletedExceptionally } ?: return null
        val roots = try { future.getNow(null) ?: return null }
        catch (_: CancellationException) { return null }
        catch (_: CompletionException) { return null }
        val offset = element.textRange?.startOffset ?: return null
        val pending = ArrayDeque<DocumentSymbolData>().apply { addAll(roots) }
        var closest: DocumentSymbolData? = null
        var length = Int.MAX_VALUE
        var count = 0
        while (pending.isNotEmpty()) {
            if (++count % 256 == 0) ProgressManager.checkCanceled()
            val data = pending.removeFirst()
            pending.addAll(data.children)
            if (data.containingFile !== file) continue
            val symbol = data.documentSymbol
            val range = source.range(symbol.range) ?: continue
            val name = if (symbol.kind == SymbolKind.Method) GoReceiverMethodName.parse(symbol.name)?.method ?: symbol.name else symbol.name
            if (source.selectionText(symbol) == name && range.containsOffset(offset) && range.length < length) {
                closest = data
                length = range.length
            }
        }
        return closest.takeIf { source.isCurrent() && support.validLSPFuture === future }
        // The upstream fallback can load/await symbols, including from a cold popup on the EDT.
    }
    // Keep ProvidingTreeModel node-provider hooks available for independent package support.
}

/** A source point for the SDK's PSI-ancestry selection, never a tree key or navigable declaration. */
private class GoStructureSelectionOrigin(
    private val file: PsiFile,
    private val offset: Int,
    private val source: GoStructureSource,
    private val ownerValid: () -> Boolean,
    private val cacheToken: () -> Any,
    private val cachedParent: (PsiElement) -> PsiElement?
) : FakePsiElement() {
    @Volatile private var resolved: Pair<Any, PsiElement>? = null
    override fun getParent(): PsiElement? {
        if (!isValid) return null
        val token = cacheToken()
        // A tree visitor asks for this parent repeatedly. Resolve once per
        // completed snapshot, but never pin a raw-document parent across the
        // package reply or keep a canceled/invalid generation alive.
        resolved?.takeIf { it.first == token && it.second.isValid }?.let { return if (isValid) it.second else null }
        val parent = cachedParent(this)?.takeIf { it.isValid } ?: file
        if (!isValid) return null
        if (token == cacheToken()) resolved = token to parent
        return parent
    }
    override fun getContainingFile() = file
    override fun getProject() = file.project
    override fun getManager() = file.manager
    override fun getLanguage() = file.language
    override fun getTextRange() = TextRange(offset, offset)
    override fun getTextOffset() = offset
    override fun isValid() = !file.project.isDisposed && ownerValid() && source.isCurrent()
    override fun canNavigate() = false
    override fun canNavigateToSource() = false
    override fun navigate(requestFocus: Boolean) = Unit
}

private open class GoDelegatingStructureElement(protected val original: StructureViewTreeElement) :
    StructureViewTreeElement by original, NodeDescriptorProvidingKey {
    override fun getKey(): Any = (original as? NodeDescriptorProvidingKey)?.key ?: original.value ?: original
    // Java default interface methods are not forwarded by Kotlin's `by` delegation.
    override fun navigate(requestFocus: Boolean) = original.navigate(requestFocus)
    override fun canNavigate() = original.canNavigate()
    override fun canNavigateToSource() = original.canNavigateToSource()
}

private class GoReceiverStructureRoot(original: StructureViewTreeElement, private val file: PsiFile) :
    GoDelegatingStructureElement(original) {
    override fun getChildren(): Array<TreeElement> {
        val source = GoStructureSource.create(file)
        val children = original.children
        if (source == null || !source.isCurrent()) return children
        val candidates = IdentityHashMap<LSPClientFeatures, MutableMap<String, MutableList<StructureViewTreeElement>>>()
        for ((index, child) in children.withIndex()) {
            if (index % 256 == 0) ProgressManager.checkCanceled()
            val element = child as? StructureViewTreeElement ?: continue
            val data = goData(element) ?: continue
            val symbol = data.documentSymbol
            if (symbol.kind !in TYPE_KINDS || !GoIdentifiers.valid(symbol.name)) continue
            candidates.getOrPut(data.clientFeatures) { mutableMapOf() }.getOrPut(symbol.name) { mutableListOf() }.add(element)
        }
        val grouped = IdentityHashMap<StructureViewTreeElement, MutableList<StructureViewTreeElement>>()
        val typeDefinitions = IdentityHashMap<StructureViewTreeElement, Boolean>()
        val moved = Collections.newSetFromMap(IdentityHashMap<TreeElement, Boolean>())
        for ((index, child) in children.withIndex()) {
            if (index % 256 == 0) ProgressManager.checkCanceled()
            val element = child as? StructureViewTreeElement ?: continue
            val data = goData(element) ?: continue
            if (data.documentSymbol.kind != SymbolKind.Method) continue
            val receiver = GoReceiverMethodName.parse(data.documentSymbol.name) ?: continue
            if (source.selectionText(data.documentSymbol) != receiver.method) continue
            val type = candidates[data.clientFeatures]?.get(receiver.type)?.singleOrNull() ?: continue
            val typeData = goData(type) ?: continue
            if (typeData.documentSymbol.kind == SymbolKind.Interface ||
                !typeDefinitions.getOrPut(type) { source.typeAlias(typeData.documentSymbol) == false }) continue
            grouped.getOrPut(type) { mutableListOf() }.add(GoReceiverMethodElement(element, receiver.prefix))
            moved.add(element)
        }
        if (!source.isCurrent()) return children
        return children.mapNotNull { child ->
            when {
                child in moved -> null
                child is StructureViewTreeElement && grouped.containsKey(child) -> GoReceiverTypeElement(child, grouped.getValue(child))
                else -> child
            }
        }.toTypedArray()
    }

    private fun goData(element: StructureViewTreeElement): DocumentSymbolData? {
        val data = element.value as? DocumentSymbolData ?: return null
        return data.takeIf { it.containingFile === file && it.clientFeatures.documentSymbolFeature is GoDocumentSymbolFeature }
    }

    companion object {
        // A named Go function type is reported as Function; its TypeSpec range is checked separately.
        private val TYPE_KINDS = setOf(SymbolKind.Struct, SymbolKind.Class, SymbolKind.Interface, SymbolKind.Function)
    }
}

private class GoReceiverTypeElement(original: StructureViewTreeElement, private val methods: List<StructureViewTreeElement>) :
    GoDelegatingStructureElement(original) {
    override fun getChildren(): Array<TreeElement> = original.children + methods
}

private class GoReceiverMethodElement(original: StructureViewTreeElement, private val receiverPrefix: String) :
    GoDelegatingStructureElement(original) {
    override fun getPresentation(): ItemPresentation = object : ItemPresentation by original.presentation {
        override fun getPresentableText(): String? = original.presentation.presentableText?.removePrefix(receiverPrefix)
        override fun getLocationString(): String? = original.presentation.locationString
    }
}

/** Only the canonical local receiver forms emitted by the pinned gopls document-symbol API. */
private data class GoReceiverMethodName(val type: String, val method: String, val prefix: String) {
    companion object {
        fun parse(name: String): GoReceiverMethodName? {
            if (!name.startsWith('(')) return null
            val end = name.indexOf(").")
            if (end < 2) return null
            val method = name.substring(end + 2)
            if (!GoIdentifiers.valid(method)) return null
            val receiver = name.substring(1, end).removePrefix("*")
            val bracket = receiver.indexOf('[')
            val type = if (bracket < 0) receiver else receiver.substring(0, bracket)
            if (!GoIdentifiers.valid(type)) return null
            if (bracket >= 0) {
                if (!receiver.endsWith(']')) return null
                val parameters = receiver.substring(bracket + 1, receiver.length - 1).split(',').map(String::trim)
                if (parameters.isEmpty() || parameters.any { it != "_" && !GoIdentifiers.valid(it) }) return null
                val named = parameters.filter { it != "_" }
                if (named.size != named.toSet().size) return null
            }
            return GoReceiverMethodName(type, method, name.substring(0, end + 2))
        }
    }
}

/** Cached document only. This is a conservative declaration hint, not Go type resolution. */
internal class GoStructureSource private constructor(private val file: PsiFile, private val document: Document,
                                                     private val stamp: Long, private val text: CharSequence,
                                                     private val sequence: Int?) {
    // Undo may restore modificationStamp. The native document sequence still
    // advances, so a captured popup origin cannot revive in a later edit version.
    fun isCurrent() = file.isValid && !file.project.isDisposed && document.modificationStamp == stamp &&
        (sequence == null || (document as DocumentEx).modificationSequence == sequence)

    fun selectionText(symbol: DocumentSymbol): String? = range(symbol.selectionRange)?.let { text.subSequence(it.startOffset, it.endOffset).toString() }

    /** null means uncertain, true an alias, false a named type definition. */
    fun typeAlias(symbol: DocumentSymbol): Boolean? {
        val selected = range(symbol.selectionRange) ?: return null
        val full = range(symbol.range) ?: return null
        if (full.startOffset != selected.startOffset || !full.contains(selected) || selectionText(symbol) != symbol.name) return null
        val lexer = GoLexer()
        lexer.start(text, selected.endOffset, full.endOffset)
        fun skipTrivia() {
            while (lexer.tokenType == TokenType.WHITE_SPACE || lexer.tokenType == GoTokens.COMMENT) lexer.advance()
        }
        skipTrivia()
        if (lexer.tokenText == "[") {
            var depth = 0
            var tokens = 0
            while (lexer.tokenType != null) {
                if (++tokens % 256 == 0) ProgressManager.checkCanceled()
                if (lexer.tokenText == "[") depth++
                if (lexer.tokenText == "]") depth--
                lexer.advance()
                if (depth == 0) break
            }
            if (depth != 0) return null
            skipTrivia()
        }
        return when {
            lexer.tokenText == "=" -> true
            lexer.tokenType == GoTokens.IDENTIFIER || lexer.tokenText in setOf("struct", "interface", "func", "map", "chan", "*", "[", "<-", "(") -> false
            else -> null
        }
    }

    internal fun range(value: Range?): TextRange? {
        if (value == null || !isCurrent()) return null
        val start = offset(value.start) ?: return null
        val end = offset(value.end) ?: return null
        return if (start <= end && isCurrent()) TextRange(start, end) else null
    }

    private fun offset(position: Position?): Int? {
        if (position == null || position.line !in 0 until document.lineCount || position.character < 0) return null
        val start = document.getLineStartOffset(position.line)
        val end = document.getLineEndOffset(position.line)
        if (position.character > end - start) return null
        val offset = start + position.character
        if (offset > 0 && offset < text.length && Character.isLowSurrogate(text[offset]) && Character.isHighSurrogate(text[offset - 1])) return null
        return offset.takeIf { it <= text.length && isCurrent() }
    }

    companion object {
        fun create(file: PsiFile): GoStructureSource? {
            if (file.language != GoLanguage || !file.isValid || file.project.isDisposed) return null
            val document = PsiDocumentManager.getInstance(file.project).getCachedDocument(file) ?: return null
            val stamp = document.modificationStamp
            val sequence = (document as? DocumentEx)?.modificationSequence
            val text = document.immutableCharSequence
            return if (document.modificationStamp == stamp && (document as? DocumentEx)?.modificationSequence == sequence)
                GoStructureSource(file, document, stamp, text, sequence) else null
        }
    }
}
