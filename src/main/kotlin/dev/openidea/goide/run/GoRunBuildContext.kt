package dev.openidea.goide.run

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.ex.DocumentEx
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.intellij.psi.PsiFile
import com.intellij.util.concurrency.AppExecutorUtil
import dev.openidea.goide.lsp.GoDocumentSync
import dev.openidea.goide.lsp.GoBuildContextLspRefresh
import dev.openidea.goide.navigation.search.GoTypeCatalogCodec
import dev.openidea.goide.settings.GoToolchain
import dev.openidea.goide.debug.GoTestDebugArguments
import com.intellij.util.execution.ParametersListUtil
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** Only native producers attach this immutable provenance. Handwritten patterns have no provenance. */
internal data class GoRunSourceSelection(val uri: String, val name: String, val mode: GoTestMode,
                                        val pattern: String, val directory: String) {
    fun applies(values: GoRunParameters): Boolean = values.target == "." && values.testMode == mode &&
        runCatching { Path.of(values.workingDirectory).toAbsolutePath().normalize() == Path.of(directory).toAbsolutePath().normalize() }.getOrDefault(false) &&
        pattern == when (mode) {
            GoTestMode.TEST -> values.testPattern
            GoTestMode.BENCHMARK -> values.benchmarkPattern
            GoTestMode.FUZZ -> values.fuzzPattern
        } && matchesRuntimeSelector(values)
    private fun matchesRuntimeSelector(values: GoRunParameters): Boolean {
        val selector = GoTestDebugArguments.explicitSelector(ParametersListUtil.parse(values.arguments, false, true), when (mode) {
            GoTestMode.TEST -> "run"
            GoTestMode.BENCHMARK -> "bench"
            GoTestMode.FUZZ -> "fuzz"
        })
        return !selector.declared || !selector.uncertain && selector.value == pattern
    }
    fun containsEntry(text: String): Boolean = GoSourceFunctions.scan(text).functions.any { it.name == name && when (mode) {
        GoTestMode.TEST -> it.kind in setOf(GoFunctionKind.TEST, GoFunctionKind.EXAMPLE)
        GoTestMode.BENCHMARK -> it.kind == GoFunctionKind.BENCHMARK
        GoTestMode.FUZZ -> it.kind == GoFunctionKind.FUZZ
    } }
}

internal enum class GoRunBuildStatus { ACTIVE, IGNORED, INVALID, CHECKING, ERROR }
internal data class GoRunBuildView(val status: GoRunBuildStatus, val message: String)

@Service(Service.Level.PROJECT)
internal class GoRunBuildContextService(project: Project) : Disposable {
    internal val contexts = GoRunBuildContexts(project, GoRunBuildContextLoader(project))
    init { Disposer.register(this, contexts) }
    override fun dispose() {}
    companion object {
        fun get(project: Project) = project.getService(GoRunBuildContextService::class.java).contexts
        fun settingsChanged(project: Project) { project.getServiceIfCreated(GoRunBuildContextService::class.java)?.contexts?.settingsChanged() }
        fun refreshLspFeatures(project: Project) { project.getServiceIfCreated(GoRunBuildContextService::class.java)?.contexts?.refreshLspFeatures() }
    }
}

/**
 * The gutter and producer only consult short in-memory state. Each package miss schedules one
 * background capture/metadata operation; source parsing, physical aliases and pipe IO stay there.
 */
internal class GoRunBuildContexts(
    private val project: Project,
    private val source: GoRunBuildContextSource,
    private val refresh: (List<PsiFile>) -> Unit = { files -> GoBuildContextLspRefresh.refresh(project, files) },
    private val executor: Executor = AppExecutorUtil.getAppExecutorService(),
) : Disposable {
    private val lock = Any()
    @Volatile private var closed = false
    private val entries = linkedMapOf<String, Load>()
    private val launches = mutableSetOf<Load>()
    private val refreshQueued = AtomicBoolean()
    private val pendingRefresh = linkedSetOf<PsiFile>()

    private data class Observed(val file: VirtualFile, val document: Document, val stamp: Long, val sequence: Int?, val uri: String, val unsaved: Boolean) {
        fun current(): Boolean = file.isValid && FileDocumentManager.getInstance().getCachedDocument(file) === document &&
            document.modificationStamp == stamp && (document as? DocumentEx)?.modificationSequence == sequence
    }
    private inner class Load(val uri: String, val directory: String, val settings: GoToolchain.Settings,
                             val values: GoRunParameters, val debug: Boolean = false, val cancelled: () -> Boolean = { false }, val targets: MutableSet<PsiFile> = linkedSetOf()) {
        val future = CompletableFuture<GoRunBuildContextSnapshot>()
        @Volatile var invalid = false
        @Volatile var snapshot: GoRunBuildContextSnapshot? = null
        @Volatile var error: String? = null
        @Volatile var operation: GoRunBuildContextOperation? = null
        @Volatile var observed: List<Observed> = emptyList()
        fun current(checkDocuments: Boolean = true) = !closed && !project.isDisposed && !invalid && !cancelled() && settings == GoToolchain.capture(project) &&
            (!checkDocuments || observed.all { it.current() })
        fun cancel() { invalid = true; future.cancel(false); operation?.dispose() }
        fun affects(path: String, directoryEvent: Boolean): Boolean {
            val changed = runCatching { GoDocumentSync.fileUri(Path.of(path)) }.getOrNull() ?: return true
            val ownDirectory = GoDocumentSync.fileUri(Path.of(directory))
            if ((relevant(path) && parent(changed) == ownDirectory) || changed == ownDirectory) return true
            if (observed.any { it.file.path == path }) return true
            val result = snapshot ?: return relevant(path)
            val name = path.substringAfterLast('/').substringAfterLast('\\')
            if (changed in result.watchedFileUris || (name in metadata || name == "vendor") && parent(changed) in result.watchedDirectoryUris) return true
            if (changed in result.watchedAllDirectoryUris || parent(changed) in result.watchedAllDirectoryUris) return true
            return directoryEvent && (below(ownDirectory, changed) || result.watchedFileUris.any { below(it, changed) })
        }
    }

    init {
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                val file = FileDocumentManager.getInstance().getFile(event.document) ?: return
                // Explicit GOENV/-modfile/-overlay inputs can have arbitrary names.
                // affects() is a memory-only check against known watched inputs.
                invalidate(file.path, false)
            }
        }, this)
        project.messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun before(events: List<VFileEvent>) = changes(events)
            override fun after(events: List<VFileEvent>) = changes(events)
            private fun changes(events: List<VFileEvent>) {
                for (event in events) {
                    val directory = event.file?.isDirectory == true
                    val paths = when (event) {
                        is VFileMoveEvent -> listOf(event.oldPath, event.newPath)
                        is VFilePropertyChangeEvent -> if (event.isRename) listOf(event.oldPath, event.newPath) else listOf(event.path)
                        else -> listOf(event.path)
                    }
                    paths.forEach { invalidate(it, directory) }
                }
            }
        })
    }

    fun lookup(file: PsiFile): GoRunBuildView {
        if (closed || project.isDisposed) return GoRunBuildView(GoRunBuildStatus.ERROR, "Go project is closed")
        val virtual = file.virtualFile?.takeIf { it.isValid } ?: return GoRunBuildView(GoRunBuildStatus.ERROR, "Save the Go file before running this entry")
        val directory = virtual.parent?.path ?: return GoRunBuildView(GoRunBuildStatus.ERROR, "No Go package directory")
        val settings = GoToolchain.capture(project) // An immutable memory-only settings capture.
        val uri = GoDocumentSync.fileUri(Path.of(virtual.path))
        var start: Load? = null
        var stale: Load? = null
        val load = synchronized(lock) {
            val previous = entries[directory]
            if (previous != null && previous.current(checkDocuments = false)) { previous.targets += file; previous }
            else {
                stale = previous
                Load(uri, directory, settings, GoRunParameters(workingDirectory = directory), targets = linkedSetOf(file))
                    .also { entries[directory] = it; start = it }
            }
        }
        stale?.cancel()
        start?.let(::schedule)
        load.error?.let { return GoRunBuildView(GoRunBuildStatus.ERROR, it) }
        val snapshot = load.snapshot ?: return GoRunBuildView(GoRunBuildStatus.CHECKING, "Checking selected Go build context…")
        val physicalUri = GoDocumentSync.fileUri(GoTypeCatalogCodec.path(snapshot.packageDirectoryUri).resolve(virtual.name))
        return view(snapshot, physicalUri)
    }

    fun retry(file: PsiFile) {
        val directory = file.virtualFile?.parent?.path ?: return
        val previous = synchronized(lock) { entries.remove(directory) }
        previous?.cancel()
        lookup(file)
        queueRefresh(listOf(file))
    }

    fun settingsChanged() {
        val all = synchronized(lock) { (entries.values + launches).distinct().also { entries.clear() } }
        all.forEach { it.cancel() }
        queueRefresh(synchronized(lock) { all.flatMap { it.targets }.distinct() })
    }

    /** A new current server can replace one whose old initialize context rejected a request. */
    fun refreshLspFeatures() {
        queueRefresh(synchronized(lock) { entries.values.flatMap { it.targets }.distinct() })
    }

    private fun view(snapshot: GoRunBuildContextSnapshot, uri: String): GoRunBuildView = when {
        sameUri(uri, snapshot.invalidSourceUris) -> GoRunBuildView(GoRunBuildStatus.INVALID, "Go reports this source file as invalid; correct it before running this entry")
        sameUri(uri, snapshot.activeSourceUris) -> GoRunBuildView(GoRunBuildStatus.ACTIVE, "Run in the selected Go build context")
        sameUri(uri, snapshot.ignoredSourceUris) -> GoRunBuildView(GoRunBuildStatus.IGNORED, "File excluded by the selected Go build context (OS, architecture, tags or cgo)")
        else -> GoRunBuildView(GoRunBuildStatus.ERROR, "Go did not classify this file; retry build context checking")
    }

    /** Fresh operation-owned preflight, never a synchronous UI query or a shared initialization cancel. */
    fun verify(selection: GoRunSourceSelection, values: GoRunParameters, settings: GoToolchain.Settings, debug: Boolean = false, cancelled: () -> Boolean = { false }): Token {
        check(!ApplicationManager.getApplication().isDispatchThread) { "Go source-entry verification must run in the background" }
        if (!selection.applies(values)) throw IllegalArgumentException("Source selection no longer matches the run configuration")
        val load = Load(selection.uri, selection.directory, settings, values.independentCopy(), debug, cancelled)
        synchronized(lock) { if (closed || project.isDisposed) throw ProcessCanceledException(); launches += load }
        schedule(load)
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
            while (true) {
                ProgressManager.checkCanceled()
                if (!load.current()) throw ProcessCanceledException()
                val left = deadline - System.nanoTime()
                if (left <= 0) error("Go build context checking timed out")
                try {
                    val result = load.future.get(minOf(left, TimeUnit.MILLISECONDS.toNanos(100)), TimeUnit.NANOSECONDS)
                    val state = view(result, result.sourceUri)
                    check(state.status == GoRunBuildStatus.ACTIVE) { state.message }
                    check(selection.containsEntry(result.sourceText)) { "The selected Go source entry changed or no longer exists; select it again" }
                    return Token(
                        memoryCheck = { if (!load.current()) throw ProcessCanceledException() },
                        diskCheck = { requireNotNull(load.snapshot).validateDisk() },
                        release = { load.cancel(); synchronized(lock) { launches.remove(load) } },
                        packageMetadata = result.packageMetadata,
                    ).also { it.checkBeforeLaunch() }
                } catch (_: TimeoutException) {}
            }
        } catch (failure: Throwable) { load.cancel(); synchronized(lock) { launches.remove(load) }; throw unwrap(failure) }
    }

    class Token internal constructor(private val memoryCheck: () -> Unit, private val diskCheck: () -> Unit,
                                     private val release: () -> Unit, packageMetadata: GoPackageMetadata) : Disposable {
        val packageMetadata = GoPackageMetadata(
            java.util.Collections.unmodifiableMap(LinkedHashMap(packageMetadata.directories)),
            java.util.Collections.unmodifiableList(packageMetadata.modules.toList()),
        )
        /** Memory-only: safe even when an SDK state API asks for launch parameters on EDT. */
        fun checkCurrent() = memoryCheck()
        fun checkBeforeLaunch() {
            check(!ApplicationManager.getApplication().isDispatchThread)
            checkCurrent(); diskCheck(); checkCurrent()
        }
        override fun dispose() = release()
    }

    private fun schedule(load: Load) {
        executor.execute {
            try {
                if (!load.current()) throw CancellationException()
                val request = capture(load)
                if (!load.current()) throw CancellationException()
                val operation = source.create(request)
                synchronized(lock) {
                    if (load.current()) load.operation = operation
                    else operation.dispose()
                }
                if (!load.current()) { operation.dispose(); throw CancellationException() }
                operation.future.whenComplete { value, failure ->
                    executor.execute { finish(load, value, failure) }
                }
                operation.start()
            } catch (failure: Throwable) { finish(load, null, failure) }
        }
    }

    private fun capture(load: Load): GoRunBuildContextRequest {
        check(!ApplicationManager.getApplication().isDispatchThread)
        val overlays = mutableListOf<GoRunBuildOverlay>()
        val seen = mutableListOf<Observed>()
        ReadAction.run<RuntimeException> {
            val manager = FileDocumentManager.getInstance()
            val open = EditorFactory.getInstance().allEditors.filter { it.project === project }.map { it.document }
            for (document in (open + manager.unsavedDocuments.toList()).distinct()) {
                val file = manager.getFile(document)?.takeIf { it.isValid && it.isInLocalFileSystem } ?: continue
                val uri = GoDocumentSync.fileUri(Path.of(file.path))
                val item = Observed(file, document, document.modificationStamp, (document as? DocumentEx)?.modificationSequence, uri, manager.isDocumentUnsaved(document))
                seen += item
                if (item.unsaved) overlays += GoRunBuildOverlay(uri, document.text, item.stamp, item.sequence)
            }
        }
        val physicalDirectory = GoTypeCatalogCodec.physicalPath(Path.of(load.directory))
        val relevantUris = seen.filter { item ->
            item.file.name in metadata ||
                item.file.name.endsWith(".go") && GoTypeCatalogCodec.physicalPath(Path.of(item.file.path)).parent == physicalDirectory ||
                !item.file.name.endsWith(".go") && item.unsaved
        }.mapTo(hashSetOf()) { it.uri }
        load.observed = seen.filter { it.uri in relevantUris }
        return GoRunBuildContextRequest(load.uri, load.directory, load.values.independentCopy(), load.settings,
            overlays.filter { it.uri in relevantUris }, debug = load.debug)
    }

    private fun finish(load: Load, snapshot: GoRunBuildContextSnapshot?, failure: Throwable?) {
        check(!ApplicationManager.getApplication().isDispatchThread)
        if (load.future.isDone) return
        try {
            if (!load.current()) throw CancellationException()
            if (failure != null) throw unwrap(failure)
            val result = requireNotNull(snapshot)
            result.validateDisk()
            // Only related native buffers participate in the published generation.
            load.observed = load.observed.filter { item ->
                val uri = GoDocumentSync.fileUri(GoTypeCatalogCodec.physicalPath(Path.of(item.file.path)))
                uri in result.watchedFileUris || parent(uri) in result.watchedDirectoryUris || uri == result.sourceUri
            }
            if (!load.current()) throw CancellationException()
            synchronized(lock) {
                if (!load.current()) throw CancellationException()
                load.snapshot = result
            }
            if (!load.current()) throw CancellationException()
            load.future.complete(result)
        } catch (error: Throwable) {
            if (error is CancellationException || error is ProcessCanceledException) load.cancel()
            else {
                load.error = error.message?.takeIf { it.isNotBlank() }?.take(500) ?: "Unable to determine Go build context; check the SDK/settings and retry"
                load.future.completeExceptionally(error)
            }
        } finally { queueRefresh(synchronized(lock) { load.targets.toList() }) }
    }

    private fun invalidate(path: String, directory: Boolean) {
        if (closed) return
        val affected = synchronized(lock) {
            (entries.values + launches).filter { it.affects(path, directory) }.distinct().also { invalidated ->
                // Deleted packages may never be looked up again. Release their
                // old PSI/documents/guards instead of retaining invalid tombstones.
                entries.entries.removeIf { it.value in invalidated }
            }
        }
        affected.forEach { it.cancel() }
        queueRefresh(synchronized(lock) { affected.flatMap { it.targets }.distinct() })
    }

    private fun queueRefresh(files: List<PsiFile>) {
        if (files.isEmpty() || closed || project.isDisposed) return
        synchronized(lock) { pendingRefresh += files }
        if (!refreshQueued.compareAndSet(false, true)) return
        ApplicationManager.getApplication().invokeLater {
            refreshQueued.set(false)
            val pending = synchronized(lock) { pendingRefresh.toList().also { pendingRefresh.clear() } }
            if (!closed && !project.isDisposed) refresh(pending)
        }
    }

    override fun dispose() {
        val all = synchronized(lock) {
            if (closed) return
            closed = true
            (entries.values + launches).distinct().also { entries.clear(); launches.clear(); pendingRefresh.clear() }
        }
        all.forEach { it.cancel() }
        source.dispose()
    }

    companion object {
        private val metadata = setOf("go.mod", "go.sum", "go.work", "go.work.sum")
        private fun relevant(path: String) = path.endsWith(".go") || path.substringAfterLast('/').substringAfterLast('\\') in metadata
        private fun parent(uri: String) = uri.trimEnd('/').substringBeforeLast('/')
        private fun below(uri: String, directory: String) = uri == directory || uri.startsWith(directory.trimEnd('/') + "/")
        private fun sameUri(uri: String, values: Set<String>): Boolean {
            if (uri in values) return true
            // Windows NIO Path equality supplies the native case rules; no case guessing on POSIX.
            val path = GoTypeCatalogCodec.path(uri)
            return values.any { GoTypeCatalogCodec.path(it) == path }
        }
        private fun unwrap(error: Throwable): Throwable =
            if (error is java.util.concurrent.ExecutionException || error is java.util.concurrent.CompletionException) error.cause?.let(::unwrap) ?: error else error
    }
}
