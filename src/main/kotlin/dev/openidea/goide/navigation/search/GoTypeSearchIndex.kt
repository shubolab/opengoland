package dev.openidea.goide.navigation.search

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
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.concurrency.AppExecutorUtil
import dev.openidea.goide.settings.GoToolchain
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong

@Service(Service.Level.PROJECT)
class GoTypeSearchService(project: Project) : Disposable {
    internal val index = GoTypeSearchIndex(project, GoEngineTypeCatalogSource(project))
    init { Disposer.register(this, index) }
    override fun dispose() { if (!Disposer.isDisposed(index)) Disposer.dispose(index) }
    companion object { internal fun get(project: Project) = project.getService(GoTypeSearchService::class.java).index }
}

/** A complete name catalog shared by native queries, never a bounded workspace/symbol response. */
internal class GoTypeSearchIndex(
    private val project: Project,
    private val source: GoTypeCatalogSource,
    private val physicalPath: (String) -> String = { GoTypeCatalogCodec.physicalPath(java.nio.file.Path.of(it)).toString() },
    private val problem: (String) -> Unit = { GoTypeSearchContributor.report(project, it) }
) : Disposable {
    private val lock = Any()
    private val epoch = AtomicLong()
    private val entries = mutableMapOf<Boolean, Load>()
    @Volatile private var disposed = false
    private var reported: Pair<Long, String>? = null
    private data class PendingPath(val path: String, val directory: Boolean)
    private val pendingPaths = mutableSetOf<PendingPath>()
    @Volatile private var pathValidation: CompletableFuture<Unit>? = null

    /** Immutable source/membership domain returned by this exact catalog, with lexical aliases. */
    private class Domain(val sources: Set<String>, val directories: Set<String>, val roots: Set<String>) {
        private fun within(path: String, root: String) = path == root || path.startsWith("$root/")
        fun affects(path: String, directory: Boolean): Boolean {
            if (path in sources) return true
            if (directory) return roots.any { within(path, it) || within(it, path) } ||
                directories.any { within(it, path) } || path.substringBeforeLast('/') in directories
            if (!relevant(path)) return false
            if (roots.any { within(path, it) } || path.substringBeforeLast('/') in directories) return true
            // A newly created parent go.work/go.mod can change workspace discovery.
            return path.substringAfterLast('/') in metadata && roots.any { within(it, path.substringBeforeLast('/')) }
        }
    }

    private inner class Load(val input: GoTypeSearchInput, val includeExternal: Boolean, val generation: Long) {
        val operation = UUID.randomUUID().toString()
        val future = CompletableFuture<Snapshot>()
        var readers = 0
        var cancelLater: Future<*>? = null
        @Volatile var domain: Domain? = null
        fun current() = !disposed && !project.isDisposed && epoch.get() == generation && input.isCurrent()
        fun cancel() { cancelLater?.cancel(false); if (!future.isDone) runCatching { source.cancel(operation) }; future.cancel(false) }
    }

    internal inner class Snapshot(val catalog: GoTypeCatalog, private val input: GoTypeSearchInput, private val generation: Long) {
        val byName: Map<String, List<GoTypeEntry>> = catalog.types.groupBy { it.name }
        fun current() = !disposed && !project.isDisposed && pathValidation == null && epoch.get() == generation && input.isCurrent()
        fun entries(): List<GoTypeEntry> = catalog.types
        fun materialize(entry: GoTypeEntry): GoTypeSearchItem {
            assertBackground()
            if (!current()) throw ProcessCanceledException()
            val guard = requireNotNull(catalog.guards[entry.uri])
            val file = file(entry) ?: stale()
            val document = input.document(entry.uri)?.second ?: ReadAction.compute<Document, RuntimeException> {
                FileDocumentManager.getInstance().getDocument(file) ?: error("No document for Go type ${entry.name}")
            }
            val stamp = document.modificationStamp
            val sequence = (document as? DocumentEx)?.modificationSequence
            val text = ReadAction.compute<String, RuntimeException> { document.text }
            if (GoTypeCatalogCodec.hash(text.toByteArray(Charsets.UTF_8)) != guard.text ||
                guard.stamp != null && guard.stamp != stamp) stale()
            // A source target is validated on disk as well as in the native
            // editor. A cached VFS stamp is not a substitute for that guard.
            validateGuard(guard, input)
            val selected = GoTypeSearchItem.strictRange(entry.selection, document, text)
            val declaration = GoTypeSearchItem.strictRange(entry.range, document, text)
            require(declaration.contains(selected) && text.substring(selected.startOffset, selected.endOffset) == entry.name) {
                "The Go type catalog returned a mismatched declaration for ${entry.name}"
            }
            val psi = ReadAction.compute<com.intellij.psi.PsiFile, RuntimeException> {
                requireNotNull(PsiManager.getInstance(project).findFile(file)) { "No Go source for ${entry.name}" }
            }
            if (!current() || document.modificationStamp != stamp || (document as? DocumentEx)?.modificationSequence != sequence) stale()
            val fileUrl = file.url
            return GoTypeSearchItem(entry, psi, document, stamp, sequence, selected) {
                current() && file.isValid && file.url == fileUrl &&
                    FileDocumentManager.getInstance().getCachedDocument(file) === document
            }
        }
        fun file(entry: GoTypeEntry): VirtualFile? {
            assertBackground()
            if (!current()) throw ProcessCanceledException()
            // A synchronous VFS refresh is forbidden under the native model's
            // read action. This lookup resolves existing local source only.
            return (input.document(entry.uri)?.first ?:
                LocalFileSystem.getInstance().findFileByNioFile(GoTypeCatalogCodec.path(entry.uri)))?.takeIf { it.isValid }
        }
    }

    init {
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                val file = FileDocumentManager.getInstance().getFile(event.document) ?: return
                changed(listOf(file.path), false)
            }
        }, this)
        project.messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                events.forEach { event ->
                    val paths = when (event) {
                        is VFileMoveEvent -> listOf(event.oldPath, event.newPath)
                        is VFilePropertyChangeEvent -> if (event.isRename) listOf(event.oldPath, event.newPath) else listOf(event.path)
                        else -> listOf(event.path)
                    }
                    changed(paths, event.file?.isDirectory == true)
                }
            }
        })
    }

    fun catalog(scope: GlobalSearchScope): Snapshot {
        assertBackground()
        val includeExternal = scope != GlobalSearchScope.projectScope(project)
        var load: Load
        while (true) {
            awaitPathValidation()
            val selected = synchronized(lock) {
                checkOpen()
                if (pathValidation != null) null else {
                    fun usable(candidate: Load?) = candidate?.takeIf { it.current() && !it.future.isCompletedExceptionally }
                    // The native scope still filters every row; only a complete superset may be reused.
                    usable(entries[includeExternal]) ?: (if (!includeExternal) usable(entries[true]) else null) ?: run {
                        entries.remove(includeExternal)?.cancel()
                        val input = GoTypeSearchInput.capture(project)
                        Load(input, includeExternal, epoch.get()).also { created ->
                            entries[includeExternal] = created
                            AppExecutorUtil.getAppExecutorService().execute { fetch(created) }
                        }
                    }
                }
            }
            if (selected != null) { load = selected; break }
        }
        synchronized(lock) { load.cancelLater?.cancel(false); load.cancelLater = null; load.readers++ }
        try {
            while (true) {
                ProgressManager.checkCanceled()
                if (!load.current()) throw ProcessCanceledException()
                try { return load.future.get(25, TimeUnit.MILLISECONDS).also { if (!it.current()) throw ProcessCanceledException() } }
                catch (_: TimeoutException) { }
                catch (error: ExecutionException) {
                    val cause = error.cause ?: error
                    if (cause is ProcessCanceledException || cause is CancellationException) throw ProcessCanceledException()
                    throw IllegalStateException(cause.message ?: "Go type indexing failed", cause)
                }
            }
        } finally {
            synchronized(lock) {
                load.readers--
                if (load.readers == 0 && !load.future.isDone && !disposed) {
                    // Adjacent keystrokes share an index build; Escape still
                    // cancels its owned worker after a short bounded grace.
                    load.cancelLater = AppExecutorUtil.getAppScheduledExecutorService().schedule({ synchronized(lock) {
                        if (load.readers == 0 && !load.future.isDone) { entries.remove(load.includeExternal, load); load.cancel() }
                    } }, 100, TimeUnit.MILLISECONDS)
                }
            }
        }
    }

    private fun fetch(load: Load) {
        try {
            if (!load.current()) throw ProcessCanceledException()
            source.prepare(load.input)
            if (!load.current() || load.future.isCancelled) throw ProcessCanceledException()
            val request = source.load(load.input, load.includeExternal, load.operation)
            var response: com.google.gson.JsonObject? = null
            while (response == null) {
                if (!load.current() || load.future.isCancelled) { source.cancel(load.operation); throw ProcessCanceledException() }
                try { response = request.get(25, TimeUnit.MILLISECONDS) } catch (_: TimeoutException) { }
            }
            val catalog = GoTypeCatalogCodec.decode(response)
            catalog.guards.values.forEach { if (!load.current()) throw ProcessCanceledException(); validateGuard(it, load.input) }
            catalog.directories.forEach {
                if (!load.current()) throw ProcessCanceledException()
                if (!Files.isDirectory(it.path) || GoTypeCatalogCodec.directoryEntries(it.path) != it.entries) stale()
            }
            if (!load.current()) throw ProcessCanceledException()
            load.input.retainSources(catalog.guards.keys)
            load.domain = Domain(catalog.guards.values.mapTo(mutableSetOf()) { it.path.toString().replace('\\', '/') } +
                load.input.sourceAliases(catalog.guards.keys),
                catalog.directories.mapTo(mutableSetOf()) { it.path.toString().replace('\\', '/') },
                (catalog.moduleRoots + load.input.projectRoots()).mapTo(mutableSetOf()) { it.replace('\\', '/') })
            if (!load.current()) throw ProcessCanceledException()
            load.future.complete(Snapshot(catalog, load.input, load.generation))
            if (!catalog.complete || catalog.issues.isNotEmpty()) report(catalog.issues.take(3).joinToString("\n").ifBlank { "Some Go packages could not be indexed. Check the project build settings." })
        } catch (error: Throwable) {
            source.cancel(load.operation)
            load.future.completeExceptionally(error.cause?.takeIf { error is ExecutionException } ?: error)
        } finally { source.release(load.operation) }
    }

    private fun validateGuard(guard: GoTypeGuard, input: GoTypeSearchInput) {
        assertBackground()
        if (Files.exists(guard.path) != guard.exists) stale()
        val disk = if (guard.exists) Files.readAllBytes(guard.path) else byteArrayOf()
        if (guard.exists && GoTypeCatalogCodec.hash(disk) != guard.disk) stale()
        val text = input.overlay(guard.uri) ?: GoTypeCatalogCodec.text(disk)
        if (GoTypeCatalogCodec.hash(text.toByteArray(Charsets.UTF_8)) != guard.text) stale()
    }

    fun report(message: String) {
        val pair = epoch.get() to message
        synchronized(lock) { if (reported == pair || disposed) return; reported = pair }
        problem(message)
    }
    private fun stale(): Nothing { invalidate(); throw ProcessCanceledException() }
    private fun changed(paths: List<String>, directory: Boolean) {
        synchronized(lock) {
            if (disposed) return
            if (entries.values.any { load ->
                    val domain = load.domain
                    paths.any { raw ->
                        val path = raw.replace('\\', '/')
                        if (domain != null) domain.affects(path, directory)
                        // Before the backend discovers dependency roots, retain conservative invalidation.
                        else relevant(path) || directory && load.input.projectRoots().any { root ->
                            path == root || path.startsWith("$root/") || root.startsWith("$path/")
                        }
                    }
                }) { invalidate(); return }
            // Unknown lexical aliases cannot be declared unrelated on the EDT.
            // Pause cache/navigation use until background physical membership is known.
            if (entries.isNotEmpty()) {
                pendingPaths += paths.map { PendingPath(it.replace('\\', '/'), directory) }.filter { it.directory || relevant(it.path) }
                if (pendingPaths.isNotEmpty() && pathValidation == null) {
                    val ready = CompletableFuture<Unit>()
                    pathValidation = ready
                    AppExecutorUtil.getAppExecutorService().execute { validatePaths(ready) }
                }
            }
        }
    }
    private fun awaitPathValidation() {
        while (true) {
            checkOpen(); ProgressManager.checkCanceled()
            val ready = pathValidation ?: return
            try { ready.get(25, TimeUnit.MILLISECONDS) } catch (_: TimeoutException) { }
            catch (_: CancellationException) { throw ProcessCanceledException() }
        }
    }
    private fun validatePaths(ready: CompletableFuture<Unit>) {
        while (true) {
            val batch = synchronized(lock) {
                if (disposed || project.isDisposed || pathValidation !== ready) { ready.cancel(false); return }
                pendingPaths.toList().also { pendingPaths.clear() }
            }
            val physical = batch.map { path -> runCatching { path.copy(path = physicalPath(path.path).replace('\\', '/')) } }
            synchronized(lock) {
                if (disposed || project.isDisposed || pathValidation !== ready) { ready.cancel(false); return }
                if (physical.any { result -> result.isFailure || entries.values.any { load ->
                        val value = result.getOrThrow()
                        load.domain?.affects(value.path, value.directory) ?: true
                    } }) invalidate()
                if (pendingPaths.isEmpty()) {
                    pathValidation = null; ready.complete(Unit); return
                }
            }
        }
    }
    private fun checkOpen() { if (disposed || project.isDisposed || project.isDefault) throw ProcessCanceledException() }
    private fun assertBackground() { check(!ApplicationManager.getApplication().isDispatchThread) { "Go type indexing must run in a background thread" }; checkOpen() }
    private fun invalidate() { synchronized(lock) { epoch.incrementAndGet(); entries.values.forEach { it.cancel() }; entries.clear() } }
    override fun dispose() { synchronized(lock) {
        disposed = true; epoch.incrementAndGet(); entries.values.forEach { it.cancel() }; entries.clear()
        pendingPaths.clear(); pathValidation?.cancel(false); pathValidation = null; source.dispose()
    } }

    companion object {
        private val metadata = setOf("go.mod", "go.sum", "go.work", "go.work.sum")
        private fun relevant(path: String) = path.endsWith(".go") || path.substringAfterLast('/') in metadata
    }
}
