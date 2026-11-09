package dev.openidea.goide.lsp

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.intellij.icons.AllIcons
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.util.FileStructureFilter
import com.intellij.ide.util.treeView.smartTree.ActionPresentationData
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.Shortcut
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.util.ProgressIndicatorBase
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.TokenType
import com.intellij.util.concurrency.AppExecutorUtil
import com.redhat.devtools.lsp4ij.LSPIJUtils
import com.redhat.devtools.lsp4ij.LanguageServerItem
import com.redhat.devtools.lsp4ij.LanguageServiceAccessor
import com.redhat.devtools.lsp4ij.ServerStatus
import com.redhat.devtools.lsp4ij.features.AbstractLSPDocumentFeatureSupport
import com.redhat.devtools.lsp4ij.features.documentSymbol.DocumentSymbolData
import com.redhat.devtools.lsp4ij.internal.CancellationSupport
import dev.openidea.goide.lang.GoLanguage
import dev.openidea.goide.lang.GoLexer
import dev.openidea.goide.lang.GoTokens
import org.eclipse.lsp4j.Command
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.DocumentSymbolParams
import org.eclipse.lsp4j.ExecuteCommandParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.SymbolTag
import org.eclipse.lsp4j.TextDocumentIdentifier
import java.util.IdentityHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future

/** One backend-provided package symbol graph, filtered by origin by both native hosts. */
internal class GoPackageStructureRoot(
    private val file: PsiFile,
    private val fallback: StructureViewTreeElement,
    private val changed: () -> Unit
) : StructureViewTreeElement by fallback, Disposable {
    internal val support = GoPackageSymbolSupport(file)
    private val lifecycle = Any()
    private class OwnedLoad(val indicator: ProgressIndicatorBase = ProgressIndicatorBase()) { var future: Future<*>? = null }
    private var loader: OwnedLoad? = null
    private var revision = 0L
    @Volatile private var disposed = false
    @Volatile private var published: Pair<CompletableFuture<GoPackageSnapshot?>, Array<StructureViewTreeElement>>? = null
    @Volatile private var fallbackPublished: Array<StructureViewTreeElement>? = null

    val isAvailable: Boolean get() = !disposed && !file.project.isDisposed && file.isValid &&
        LanguageServiceAccessor.getInstance(file.project).hasAny(file) {
            it.serverDefinition.id == "openidea.gopls" &&
                it.serverCapabilitiesSync?.executeCommandProvider?.commands?.contains(GoPackageSymbolSupport.COMMAND) == true
        }

    val filter: FileStructureFilter = object : FileStructureFilter {
        override fun getName() = "OPENGOLAND_GO_SHOW_PACKAGE_STRUCTURE"
        override fun getPresentation() = ActionPresentationData("Show Package Structure", "Include declarations from the current Go package", AllIcons.Nodes.Package)
        override fun getCheckBoxText() = "Show Package Structure"
        override fun getShortcut(): Array<Shortcut> = emptyArray()
        override fun isReverted() = true
        override fun isVisible(treeNode: TreeElement) = (treeNode as? GoPackageElement)?.containsCurrentFile ?: true
    }

    init {
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                val path = FileDocumentManager.getInstance().getFile(event.document)?.path ?: return
                if (relevant(path)) invalidate()
            }
        }, this)
        file.project.messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                if (events.any { it.file?.isDirectory == true || relevant(it.path) }) invalidate()
            }
        })
    }

    private fun invalidate() {
        synchronized(lifecycle) {
            if (disposed) return
            revision++
            support.cancel()
            published = null
            fallbackPublished = null
            stopLoader()
        }
        notifyChanged()
    }

    private fun relevant(path: String) = path.endsWith(".go") || path.substringAfterLast('/') in setOf("go.mod", "go.sum", "go.work", "go.work.sum")

    private fun stopLoader() { loader?.let { it.indicator.cancel(); it.future?.cancel(true) }; loader = null }

    private fun scheduleLoad() = synchronized(lifecycle) {
        if (disposed || loader != null) return@synchronized
        val task = OwnedLoad()
        loader = task
        task.future = AppExecutorUtil.getAppExecutorService().submit {
            try { ProgressManager.getInstance().runProcess(Runnable { getChildren() }, task.indicator) }
            catch (_: ProcessCanceledException) { }
            catch (_: CancellationException) { }
            finally {
                synchronized(lifecycle) { if (loader === task) loader = null }
                notifyChanged()
            }
        }
    }

    private fun notifyChanged() = ApplicationManager.getApplication().invokeLater({
        if (!disposed && !file.project.isDisposed) changed()
    }, file.project.disposed)

    override fun getChildren(): Array<StructureViewTreeElement> {
        if (disposed || file.project.isDisposed || !file.isValid) return emptyArray()
        val started = synchronized(lifecycle) { if (disposed) return emptyArray(); revision }
        if (ApplicationManager.getApplication().isDispatchThread) {
            // Model information queries may run on the EDT. Never start an RPC,
            // wait for a response, resolve a URI or load a target document there.
            if (isAvailable) published?.takeIf { it.first === support.validLSPFuture }?.let { return it.second.copyOf() }
            else fallbackPublished?.let { return it.copyOf() }
            scheduleLoad()
            return emptyArray()
        }
        if (!isAvailable) {
            // A client without this optional command keeps its existing model.
            // Do not create/start another server just to discover that fact.
            val children = fallback.children.map { it as StructureViewTreeElement }.toTypedArray()
            synchronized(lifecycle) {
                if (disposed || started != revision) throw ProcessCanceledException()
                fallbackPublished = children
            }
            return children.copyOf()
        }
        val future = synchronized(lifecycle) {
            if (disposed || started != revision || file.project.isDisposed) throw ProcessCanceledException()
            support.getFeatureData(Unit) ?: throw ProcessCanceledException()
        }
        val snapshot = CancellationSupport.awaitWithCheckCanceled(future)
        if (disposed || file.project.isDisposed || support.validLSPFuture !== future) throw ProcessCanceledException()
        val children: Array<StructureViewTreeElement> = snapshot?.elements?.toTypedArray<StructureViewTreeElement>()
            ?: fallback.children.map { it as StructureViewTreeElement }.toTypedArray()
        synchronized(lifecycle) {
            if (disposed || started != revision || support.validLSPFuture !== future) throw ProcessCanceledException()
            published = future to children
        }
        return children.copyOf()
    }

    /** Cached only: editor-position callbacks do not perform language-server or file work. */
    fun currentElement(element: PsiElement): PsiElement? {
        if (disposed || !isAvailable) return null
        val current = support.validLSPFuture?.takeIf { it.isDone && !it.isCompletedExceptionally } ?: return null
        val snapshot = try { current.getNow(null) } catch (_: CancellationException) { null }
            catch (_: CompletionException) { null } ?: return null
        val offset = element.textRange.startOffset
        return snapshot.allElements.asSequence().map { it.data }
            .filter { it.isValid && it.containingFile == element.containingFile }
            .mapNotNull { data -> data.textRange?.takeIf { it.containsOffset(offset) }?.let { data to it.length } }
            .minByOrNull { it.second }?.first
    }

    override fun navigate(requestFocus: Boolean) = fallback.navigate(requestFocus)
    override fun canNavigate() = fallback.canNavigate()
    override fun canNavigateToSource() = fallback.canNavigateToSource()
    override fun dispose() = synchronized(lifecycle) {
        disposed = true; revision++; support.cancel(); stopLoader(); published = null; fallbackPublished = null
    }
}

/** Reuses the pinned document/dependency/server epochs and cancellation ownership. */
internal class GoPackageSymbolSupport(file: PsiFile) : AbstractLSPDocumentFeatureSupport<Unit, GoPackageSnapshot?>(file, true, true) {
    override fun doLoad(params: Unit, cancellation: CancellationSupport): CompletableFuture<GoPackageSnapshot?> {
        val available = LanguageServiceAccessor.getInstance(file.project).hasAny(file) {
            it.serverDefinition.id == "openidea.gopls" && it.serverCapabilitiesSync?.executeCommandProvider?.commands?.contains(COMMAND) == true
        }
        if (!available) return getNavigationLanguageServers(file, { false }, { false }).thenApply { null }
        val goClient = java.util.function.Predicate<com.redhat.devtools.lsp4ij.client.features.LSPClientFeatures> {
            it.documentSymbolFeature is GoDocumentSymbolFeature
        }
        // Connect all open Go buffers before freezing the server generation. This
        // includes unsaved new files and avoids a package query racing their open.
        val pending: CompletableFuture<GoPackageSnapshot?> = getLanguageServers(file, goClient, goClient).thenComposeAsync {
            cancellation.checkCanceled()
            val openFiles = ReadAction.compute<List<PsiFile>, RuntimeException> {
                val documents = FileDocumentManager.getInstance()
                val dirtyPackageFiles = documents.unsavedDocuments.mapNotNull(documents::getFile)
                    .filter { it.parent == file.virtualFile.parent }
                val buffers = FileEditorManager.getInstance(file.project).openFiles.toList() + dirtyPackageFiles
                buffers.distinct().mapNotNull { PsiManager.getInstance(file.project).findFile(it) }
                    .filter { it.language == GoLanguage && it.isValid }
            }
            val connections = openFiles.map { getLanguageServers(it, goClient, goClient).thenApply { Unit } }
            CompletableFuture.allOf(*connections.toTypedArray())
        }.thenCompose {
            cancellation.checkCanceled()
            getNavigationLanguageServers(file, goClient, goClient)
        }.thenComposeAsync { servers ->
            cancellation.checkCanceled()
            val item = servers.firstOrNull {
                it.serverDefinition.id == "openidea.gopls" && it.supportsCommand(Command("", COMMAND))
            } ?: return@thenComposeAsync CompletableFuture.completedFuture(null)
            // A completed open does not imply that a debounced change was sent.
            // Wait for the real per-document dispatch barriers without cancelling
            // these shared synchronization futures when this view is disposed.
            val barriers = item.serverWrapper.openedDocuments.mapNotNull { it.synchronizer }
                .map { sync -> sync.didOpenFuture.thenCompose { sync.flushPendingChanges() }.thenApply { Unit } }
            CompletableFuture.allOf(*barriers.toTypedArray()).thenCompose {
                cancellation.checkCanceled()
                val uri = LSPIJUtils.toUriAsString(file) ?: throw ProcessCanceledException()
                val response = item.workspaceService.executeCommand(ExecuteCommandParams(COMMAND, listOf(mapOf("URI" to uri))))
                cancellation.execute(response, item, COMMAND).thenApplyAsync { result ->
                    cancellation.checkCanceled()
                    decode(result, file, item, cancellation) { nodeCurrent(cancellation) }
                }.thenCompose { snapshot ->
                    if (snapshot == null) CompletableFuture.completedFuture(null)
                    else supplementMissingMethods(snapshot, item, cancellation)
                }
            }
        }
        return pending.handle { result, error ->
            if (error == null) result else {
                var cause = error
                while ((cause is CompletionException || cause is ExecutionException) && cause.cause != null) cause = cause.cause!!
                if (cause is ProcessCanceledException || cause is CancellationException || cause is Error) throw CompletionException(cause)
                // Retain the file view for this failed generation. Repeated EDT
                // information queries must not create a request/rebuild loop.
                LOG.warn("Could not load Go package structure; keeping the file structure", cause)
                null
            }
        }
    }

    @Synchronized private fun nodeCurrent(cancellation: CancellationSupport): Boolean = try {
        cancellation.checkCanceled()
        checkValid()
    } catch (_: CancellationException) { false }

    private fun supplementMissingMethods(snapshot: GoPackageSnapshot, item: LanguageServerItem,
                                         cancellation: CancellationSupport): CompletableFuture<GoPackageSnapshot> {
        // The package command deliberately omits methods whose receiver has no
        // declaration. They are common while typing. Check lexical heads in the
        // background (including files with no other symbol to load their document)
        // and ask documentSymbol only for affected files, rather than silently
        // losing those methods or querying every file in a large valid package.
        val missing = ReadAction.compute<List<PsiFile>, RuntimeException> {
            snapshot.files.filter { target ->
                val covered = snapshot.allElements.asSequence().map { it.data }
                    .filter { it.containingFile == target && it.documentSymbol.kind == SymbolKind.Method }
                    .map { it.documentSymbol.selectionRange }.toSet()
                receiverSelections(target, cancellation).any { it !in covered }
            }
        }
        if (missing.isEmpty()) return CompletableFuture.completedFuture(snapshot)
        val requests = missing.map { target ->
            cancellation.checkCanceled()
            val uri = LSPIJUtils.toUriAsString(target) ?: throw ProcessCanceledException()
            cancellation.execute(item.textDocumentService.documentSymbol(DocumentSymbolParams(TextDocumentIdentifier(uri))),
                item, "textDocument/documentSymbol").thenApply { reply ->
                target to requireNotNull(reply) { "Missing Go document symbols" }.map { either ->
                    if (either.isRight) either.right else {
                        val symbol = either.left
                        require(java.net.URI(symbol.location.uri) == java.net.URI(uri)) { "Unexpected Go document-symbol file" }
                        DocumentSymbol(symbol.name, symbol.kind, symbol.location.range, symbol.location.range)
                    }
                }
            }
        }
        return CompletableFuture.allOf(*requests.toTypedArray()).thenApplyAsync {
            cancellation.checkCanceled()
            mergeMissing(snapshot, requests.map { it.join() }, file, item, cancellation) { nodeCurrent(cancellation) }
        }
    }

    companion object {
        const val COMMAND = "gopls.package_symbols"
        private val LOG = Logger.getInstance(GoPackageSymbolSupport::class.java)

        private fun decode(result: Any?, current: PsiFile, item: LanguageServerItem, cancellation: CancellationSupport,
                           currentGeneration: () -> Boolean): GoPackageSnapshot? {
            if (result == null) return null
            check(!ApplicationManager.getApplication().isDispatchThread)
            val objectValue = (if (result is JsonElement) result else Gson().toJsonTree(result)).asJsonObject
            val uris = requireNotNull(objectValue.getAsJsonArray("Files")) { "Missing Go package files" }.map { it.asString }
            val directory = java.net.URI(LSPIJUtils.toUriAsString(current) ?: throw ProcessCanceledException()).resolve(".").normalize()
            val files = uris.map { uri ->
                // CompiledGoFiles may include cgo compiler cache artifacts. They
                // are not source declarations in the queried package directory.
                // Keep their original index slots, but never load or render them.
                if (java.net.URI(uri).resolve(".").normalize() != directory) return@map null
                val virtual = item.clientFeatures.findFileByUri(uri) ?: throw ProcessCanceledException()
                ReadAction.compute<PsiFile?, RuntimeException> {
                    PsiManager.getInstance(current.project).findFile(virtual)?.takeIf { it.language == GoLanguage }
                }
            }
            val sourceFiles = files.filterNotNull()
            if (current !in sourceFiles) return null
            val fileOrder = sourceFiles.withIndex().associate { it.value to it.index }
            val all = mutableListOf<GoPackageElement>()
            fun build(raw: JsonObject): GoPackageElement? {
                cancellation.checkCanceled()
                val index = raw.get("file")?.let(::integer) ?: 0
                require(index in files.indices) { "Invalid Go package file index" }
                val file = files[index] ?: return null
                val symbol = DocumentSymbol(raw.get("name").asString, SymbolKind.forValue(integer(raw.get("kind"))), range(raw.getAsJsonObject("range")), range(raw.getAsJsonObject("selectionRange")))
                symbol.detail = raw.get("detail")?.takeUnless { it.isJsonNull }?.asString
                symbol.tags = raw.get("tags")?.takeUnless { it.isJsonNull }?.asJsonArray?.map { SymbolTag.forValue(integer(it)) }
                // Each child's file index is independent. In particular index 0
                // is omitted on the wire, even beneath a type from another file.
                val children = raw.get("children")?.takeUnless { it.isJsonNull }?.asJsonArray?.mapNotNull { build(it.asJsonObject) } ?: emptyList()
                val data = data(symbol, file, item, cancellation, currentGeneration)
                return GoPackageElement(data, children, file == current).also { all += it }
            }
            val elements = requireNotNull(objectValue.getAsJsonArray("Symbols")) { "Missing Go package symbols" }
                .mapNotNull { build(it.asJsonObject) }
                // gopls sorts this response by name. Restore a deterministic
                // file/source order; native alphabetical/export sorters remain optional.
                .sortedWith(compareBy({ fileOrder[it.data.containingFile] }, { it.data.documentSymbol.range.start.line }, { it.data.documentSymbol.range.start.character }))
            return GoPackageSnapshot(elements, all, sourceFiles)
        }

        private fun data(symbol: DocumentSymbol, file: PsiFile, item: LanguageServerItem,
                         cancellation: CancellationSupport, currentGeneration: () -> Boolean): GoPackageSymbolData =
            ReadAction.compute<GoPackageSymbolData, RuntimeException> {
                val document = PsiDocumentManager.getInstance(file.project).getDocument(file) ?: throw ProcessCanceledException()
                val source = GoStructureSource.create(file) ?: throw ProcessCanceledException()
                val declaration = source.range(symbol.range) ?: error("Invalid Go package declaration range")
                val selection = source.range(symbol.selectionRange) ?: error("Invalid Go package selection range")
                require(declaration.contains(selection)) { "Invalid Go package selection range" }
                GoPackageSymbolData(symbol, file, item, document) {
                    try { cancellation.checkCanceled(); currentGeneration() && item.serverWrapper.isEnabled && item.serverWrapper.serverStatus == ServerStatus.started && item.serverWrapper.languageServer === item.server }
                    catch (_: CancellationException) { false }
                }
            }

        private fun receiverSelections(file: PsiFile, cancellation: CancellationSupport): List<Range> {
            check(!ApplicationManager.getApplication().isDispatchThread)
            val document = PsiDocumentManager.getInstance(file.project).getDocument(file) ?: return emptyList()
            val lexer = GoLexer().apply { start(document.immutableCharSequence) }
            val result = mutableListOf<Range>()
            var tokens = 0
            fun trivia() { while (lexer.tokenType == TokenType.WHITE_SPACE || lexer.tokenType == GoTokens.COMMENT) lexer.advance() }
            while (lexer.tokenType != null) {
                if (++tokens % 256 == 0) cancellation.checkCanceled()
                if (lexer.tokenText != "func") { lexer.advance(); continue }
                lexer.advance(); trivia()
                if (lexer.tokenText != "(") continue
                var depth = 0
                do {
                    cancellation.checkCanceled()
                    if (lexer.tokenText == "(") depth++
                    if (lexer.tokenText == ")") depth--
                    lexer.advance()
                } while (lexer.tokenType != null && depth > 0)
                trivia()
                if (depth != 0 || lexer.tokenType != GoTokens.IDENTIFIER) continue
                val start = lexer.tokenStart; val end = lexer.tokenEnd
                lexer.advance(); trivia()
                if (lexer.tokenText == "(") result += Range(LSPIJUtils.toPosition(start, document), LSPIJUtils.toPosition(end, document))
            }
            return result
        }

        private data class SymbolKey(val file: PsiFile, val kind: SymbolKind, val selection: Range)
        private fun key(data: DocumentSymbolData) = SymbolKey(requireNotNull(data.containingFile), data.documentSymbol.kind, data.documentSymbol.selectionRange)

        private fun mergeMissing(snapshot: GoPackageSnapshot, replies: List<Pair<PsiFile, List<DocumentSymbol>>>, current: PsiFile,
                                 item: LanguageServerItem, cancellation: CancellationSupport, currentGeneration: () -> Boolean): GoPackageSnapshot {
            val known = snapshot.allElements.associateBy { key(it.data) }.toMutableMap()
            val added = IdentityHashMap<GoPackageElement, MutableList<GoPackageElement>>()
            val roots = snapshot.elements.toMutableList()
            fun merge(symbol: DocumentSymbol, file: PsiFile, parent: GoPackageElement?) {
                cancellation.checkCanceled()
                val symbolKey = SymbolKey(file, symbol.kind, symbol.selectionRange)
                val node = known[symbolKey] ?: GoPackageElement(data(symbol, file, item, cancellation, currentGeneration), emptyList(), file == current).also {
                    known[symbolKey] = it
                    if (parent == null) roots += it else added.getOrPut(parent) { mutableListOf() } += it
                }
                symbol.children?.forEach { merge(it, file, node) }
            }
            replies.forEach { (file, symbols) -> symbols.forEach { merge(it, file, null) } }
            val all = mutableListOf<GoPackageElement>()
            fun finish(node: GoPackageElement): GoPackageElement = node.withMembers((node.members + added[node].orEmpty()).map(::finish)).also { all += it }
            val fileOrder = snapshot.files.withIndex().associate { it.value to it.index }
            val ordered = roots.map(::finish).sortedWith(compareBy({ fileOrder[it.data.containingFile] },
                { it.data.documentSymbol.range.start.line }, { it.data.documentSymbol.range.start.character }))
            return GoPackageSnapshot(ordered, all, snapshot.files)
        }

        private fun range(raw: JsonObject) = Range(position(raw.getAsJsonObject("start")), position(raw.getAsJsonObject("end")))
        private fun position(raw: JsonObject): Position {
            val line = integer(raw.get("line")); val character = integer(raw.get("character"))
            require(line >= 0 && character >= 0) { "Negative Go package position" }
            return Position(line, character)
        }

        private fun integer(value: JsonElement?): Int {
            require(value?.isJsonPrimitive == true && value.asJsonPrimitive.isNumber) { "Invalid Go package integer" }
            return value.asBigDecimal.intValueExact()
        }
    }
}

internal data class GoPackageSnapshot(val elements: List<GoPackageElement>, val allElements: List<GoPackageElement>, val files: List<PsiFile>)

/** Native tree keys must distinguish identical declarations in different files. */
private class GoPackageSymbolData(symbol: DocumentSymbol, private val file: PsiFile, item: LanguageServerItem,
    private val document: Document, private val generationValid: () -> Boolean) : DocumentSymbolData(symbol, file, item) {
    private val stamp = document.modificationStamp
    override fun isValid() = file.isValid && !file.project.isDisposed && document.modificationStamp == stamp && generationValid()
    override fun equals(other: Any?): Boolean = other is GoPackageSymbolData &&
        file.project === other.file.project && file.virtualFile == other.file.virtualFile && documentSymbol == other.documentSymbol
    override fun hashCode() = 31 * (31 * System.identityHashCode(file.project) + file.virtualFile.hashCode()) + documentSymbol.hashCode()
}

internal class GoPackageElement(
    val data: DocumentSymbolData,
    val members: List<GoPackageElement>,
    private val current: Boolean
) : StructureViewTreeElement {
    val containsCurrentFile: Boolean = current || members.any { it.containsCurrentFile }
    fun withMembers(members: List<GoPackageElement>) = GoPackageElement(data, members, current)
    private val delegate = requireNotNull(data.clientFeatures.documentSymbolFeature.getStructureViewTreeElement(data))
    override fun getValue() = data
    override fun getChildren(): Array<StructureViewTreeElement> = members.toTypedArray()
    override fun navigate(requestFocus: Boolean) { if (data.isValid) delegate.navigate(requestFocus) }
    override fun canNavigate() = data.isValid && delegate.canNavigate()
    override fun canNavigateToSource() = data.isValid && delegate.canNavigateToSource()
    override fun getPresentation(): ItemPresentation = object : ItemPresentation {
        override fun getPresentableText() = delegate.presentation.presentableText
        override fun getIcon(unused: Boolean) = delegate.presentation.getIcon(unused)
        override fun getLocationString(): String? = listOfNotNull(delegate.presentation.locationString,
            data.containingFile?.name?.takeUnless { current }).takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }
}
