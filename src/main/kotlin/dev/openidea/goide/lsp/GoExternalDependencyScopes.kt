package dev.openidea.goide.lsp

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.ex.DocumentEx
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.concurrency.SequentialTaskExecutor
import dev.openidea.goide.engine.GoTaskTargetsService
import dev.openidea.goide.engine.GoTaskRefreshListener
import dev.openidea.goide.navigation.search.GoTypeCatalogCodec
import dev.openidea.goide.run.GoRunBuildContextLoader
import dev.openidea.goide.run.GoRunBuildContextRequest
import dev.openidea.goide.run.GoRunBuildOverlay
import dev.openidea.goide.run.GoRunParameters
import dev.openidea.goide.settings.GoToolchain
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** One client's active editor packages; no module is added to the gopls workspace. */
internal class GoExternalDependencyScopes(
    private val project: Project,
    register: (String, Set<Path>) -> CompletableFuture<Void>,
    reconcile: (Set<Path>) -> CompletableFuture<Void>,
    unregister: (String) -> CompletableFuture<Void>,
) : Disposable {
    private val lock = Any()
    private var closed = false
    private var generation = 0L
    private var scheduled: ScheduledFuture<*>? = null
    @Volatile private var packages = emptySet<String>()
    private val loader = GoRunBuildContextLoader(project)
    private val executor = SequentialTaskExecutor.createSequentialApplicationPoolExecutor("Go local dependency discovery")
    private val captureExecutor = SequentialTaskExecutor.createSequentialApplicationPoolExecutor("Go dependency input capture")
    private val scopes = GoDependencyScopeCoordinator(loader::createDependencyScope, register, reconcile, unregister,
        AppExecutorUtil.getAppExecutorService(), executor)

    private data class Observed(val file: VirtualFile, val document: Document, val stamp: Long, val sequence: Int?, val uri: String,
                                val text: String?, val path: String, val oversized: Boolean) {
        fun current(): Boolean = file.isValid && FileDocumentManager.getInstance().getCachedDocument(file) === document &&
            document.modificationStamp == stamp && (document as? DocumentEx)?.modificationSequence == sequence
    }

    init {
        Disposer.register(project, this)
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                FileDocumentManager.getInstance().getFile(event.document)?.let { changed(it.path, it.isDirectory) }
            }
        }, this)
        val connection = project.messageBus.connect(this)
        connection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun fileOpened(source: FileEditorManager, file: VirtualFile) { if (file.extension == "go") refresh() }
            override fun fileClosed(source: FileEditorManager, file: VirtualFile) { if (file.extension == "go") refresh() }
        })
        connection.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                for (event in events) {
                    val paths = when (event) {
                        is VFileMoveEvent -> listOf(event.oldPath, event.newPath)
                        is VFilePropertyChangeEvent -> if (event.isRename) listOf(event.oldPath, event.newPath) else listOf(event.path)
                        else -> listOf(event.path)
                    }
                    paths.forEach { changed(it, event.file?.isDirectory == true) }
                }
            }
        })
        connection.subscribe(GoTaskTargetsService.REFRESH_TOPIC, GoTaskRefreshListener { refresh() })
    }

    fun refresh() {
        val epoch = synchronized(lock) {
            if (closed || project.isDisposed) return
            scheduled?.cancel(false)
            ++generation
        }
        scopes.invalidateQueries()
        synchronized(lock) {
            if (closed || project.isDisposed || generation != epoch) return
            scheduled = AppExecutorUtil.getAppScheduledExecutorService().schedule({
                captureExecutor.execute { capture(epoch) }
            }, 400, TimeUnit.MILLISECONDS)
        }
    }

    private fun changed(path: String, directory: Boolean) {
        val parent = path.substringBeforeLast('/', "")
        val name = path.substringAfterLast('/')
        // The ordinary .go edits in dependencies go directly through the VFS/LSP
        // listener. Debounced discovery also catches newly introduced transitive
        // imports in a watched dependency, without changing workspace membership.
        val uri = runCatching { GoDocumentSync.fileUri(Path.of(path)) }.getOrNull() ?: return
        val parentUri = runCatching { GoDocumentSync.fileUri(Path.of(parent)) }.getOrNull() ?: return
        if (name in METADATA && packages.any { it == parent || it.startsWith(parent.trimEnd('/') + "/") } || name.endsWith(".go") && parent in packages ||
            directory && packages.any { it == path || it.startsWith(path.trimEnd('/') + "/") } ||
            scopes.affected(uri, parentUri, directory)) refresh()
    }

    private fun capture(epoch: Long) {
        check(!ApplicationManager.getApplication().isDispatchThread)
        if (!current(epoch)) return
        try {
            val settings = GoToolchain.capture(project).copy(delveExecutable = "")
            val open = mutableListOf<Pair<String, String>>()
            val observed = mutableListOf<Observed>()
            ReadAction.run<RuntimeException> {
                val documents = FileDocumentManager.getInstance()
                FileEditorManager.getInstance(project).openFiles.filter { it.isValid && it.isInLocalFileSystem && it.extension == "go" }
                    .forEach { open += it.path to (it.parent?.path ?: return@forEach) }
                val all = EditorFactory.getInstance().allEditors.filter { it.project === project }.map { it.document } + documents.unsavedDocuments
                for (document in all.distinct()) {
                    val file = documents.getFile(document)?.takeIf { it.isValid && it.isInLocalFileSystem } ?: continue
                    val dirty = documents.isDocumentUnsaved(document)
                    val oversized = dirty && document.textLength > 2 * 1024 * 1024
                    observed += Observed(file, document, document.modificationStamp, (document as? DocumentEx)?.modificationSequence,
                        GoDocumentSync.fileUri(Path.of(file.path)), if (dirty) { if (oversized) "" else document.text } else null, file.path, oversized)
                }
            }
            if (!current(epoch)) return
            val physical = observed.associateWith { GoDocumentSync.fileUri(GoTypeCatalogCodec.physicalPath(Path.of(it.path))) }
            val grouped = open.groupBy({ it.second }, { it.first })
            require(grouped.size <= 128) { "Too many open Go packages to discover dependency watches" }
            packages = grouped.keys.toSet()
            val overlays = observed.filter { it.text != null }.map { GoRunBuildOverlay(it.uri, requireNotNull(it.text), it.stamp, it.sequence, it.oversized) }
            val inputs = grouped.mapValues { (directory, sources) ->
                val request = GoRunBuildContextRequest(GoDocumentSync.fileUri(Path.of(sources.first())), directory,
                    GoRunParameters(workingDirectory = directory), settings, overlays)
                GoDependencyScopeInput(request) { snapshot ->
                    current(epoch) && settings == GoToolchain.capture(project).copy(delveExecutable = "") &&
                        ReadAction.compute<Boolean, RuntimeException> {
                            observed.filter { item ->
                                item.path.substringBeforeLast('/') == directory || snapshot != null &&
                                    (physical[item] in snapshot.watchedFileUris || parent(physical.getValue(item)) in snapshot.watchedDirectoryUris)
                            }.all { it.current() }
                        }
                }
            }
            if (current(epoch)) scopes.update(inputs)
        } catch (failure: Exception) {
            if (current(epoch)) LOG.warn("Unable to capture local Go dependency watch inputs", failure)
            // Failure is not a successful empty graph: retain verified watches.
        }
    }

    private fun current(epoch: Long) = synchronized(lock) { !closed && !project.isDisposed && generation == epoch }

    override fun dispose() {
        synchronized(lock) {
            if (closed) return
            closed = true; ++generation
            scheduled?.cancel(false); scheduled = null
        }
        scopes.dispose()
        loader.dispose()
    }

    companion object {
        private val LOG = Logger.getInstance(GoExternalDependencyScopes::class.java)
        private val METADATA = setOf("go.mod", "go.sum", "go.work", "go.work.sum")
        private fun parent(uri: String) = uri.substringBeforeLast('/')
    }
}
