package dev.openidea.goide.lsp

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VFileProperty
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.concurrency.AppExecutorUtil
import com.redhat.devtools.lsp4ij.JSONUtils
import org.eclipse.lsp4j.DidChangeWatchedFilesRegistrationOptions
import org.eclipse.lsp4j.FileSystemWatcher
import org.eclipse.lsp4j.RegistrationParams
import org.eclipse.lsp4j.UnregistrationParams
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * LSP4IJ matches registered globs against VFS events, but does not discover or
 * watch a cold directory outside the project. Own just those additional roots.
 * All IO, initial VFS loading and watch changes run on the serial background queue.
 */
internal class GoExternalFileWatches(
    private val project: Project,
    private val platform: Platform = NativePlatform(project),
) : Disposable {
    internal interface Root : AutoCloseable
    internal interface Platform {
        fun install(path: Path, cancelled: () -> Boolean): Root?
        fun refresh(roots: List<Root>, finished: () -> Unit)
    }

    private val closed = AtomicBoolean()
    private val refreshPending = AtomicBoolean()
    private val lock = Any()
    private var queue = CompletableFuture.completedFuture<Void>(null)
    // Accessed only by the queue; each registration owns its set, not a shared count.
    private val registrations = linkedMapOf<String, Set<Path>>()
    private val roots = linkedMapOf<Path, Root>()
    private var timer: ScheduledFuture<*>? = null

    init { Disposer.register(project, this) }

    fun register(params: RegistrationParams): CompletableFuture<Void> {
        val updates = params.registrations.filter { it.method == METHOD }.map { registration ->
            val paths = runCatching {
                val gson = JSONUtils.getLsp4jGson()
                val options = gson.fromJson(gson.toJsonTree(registration.registerOptions), DidChangeWatchedFilesRegistrationOptions::class.java)
                watcherRoots(options?.watchers.orEmpty(), project.basePath)
            }.getOrElse { emptySet() }
            registration.id to paths
        }
        return submit {
            if (!closed.get()) {
                updates.forEach { (id, paths) -> registrations[id] = paths }
                reconcile()
            }
        }
    }

    fun unregister(params: UnregistrationParams): CompletableFuture<Void> = submit {
        if (!closed.get()) {
            params.unregisterations.filter { it.method == METHOD }.forEach { registrations.remove(it.id) }
            reconcile()
        }
    }

    /** Selected-Go metadata supplies literal roots, including non-workspace modules inside the project. */
    fun registerOwned(id: String, paths: Set<Path>): CompletableFuture<Void> = submit {
        if (!closed.get()) { registrations[id] = paths.toSet(); reconcile() }
    }

    /** A matcher alone is insufficient: every requested cold root needs a real lease. */
    fun requireCoverage(id: String, expected: Set<Path>): CompletableFuture<Void> = submit {
        check(!closed.get() && registrations[id] == expected && expected.all { path -> roots.keys.any { path.startsWith(it) } }) {
            "Some external Go dependency roots could not be watched within the VFS limits"
        }
    }

    private fun reconcile() {
        val all = registrations.values.flatten().toSet()
        val desired = linkedSetOf<Path>()
        // Only a successfully installed ancestor covers a descendant. A broad
        // root may exceed the entry/depth budget while a narrow root is usable.
        for (path in all.sortedBy { it.nameCount }) {
            if (closed.get() || project.isDisposed) break
            if (desired.any { path.startsWith(it) }) continue
            if (desired.size >= MAX_ROOTS) break
            if (path in roots) { desired.add(path); continue }
            runCatching { platform.install(path) { closed.get() || project.isDisposed } }
                .onSuccess { root -> if (root != null) { roots[path] = root; desired.add(path) } }
                .onFailure { LOG.warn("Unable to watch external Go directory $path", it) }
        }
        val removed = if (closed.get() || project.isDisposed) roots.keys.toList() else (roots.keys - desired)
        removed.forEach { roots.remove(it)?.close() }
        if (roots.isEmpty()) {
            timer?.cancel(false); timer = null
        } else if (timer == null && !closed.get()) {
            // fsnotifier marks cached paths dirty; it does not schedule a refresh
            // while the IDE stays foreground. Refresh only these owned roots and
            // preserve the native dirty-path pruning (never force a whole-tree scan).
            timer = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay({ requestRefresh() }, 2, 2, TimeUnit.SECONDS)
        }
    }

    internal fun requestRefresh(): CompletableFuture<Void> {
        if (closed.get() || !refreshPending.compareAndSet(false, true)) return CompletableFuture.completedFuture(null)
        return submit {
            if (closed.get() || project.isDisposed || roots.isEmpty()) refreshPending.set(false)
            else try { platform.refresh(roots.values.toList()) { refreshPending.set(false) } }
            catch (failure: Exception) { refreshPending.set(false); LOG.warn("Unable to refresh external Go directories", failure) }
        }
    }

    private fun submit(action: () -> Unit): CompletableFuture<Void> = synchronized(lock) {
        queue.handle { _, _ -> null }.thenRunAsync(action, AppExecutorUtil.getAppExecutorService()).also { queue = it }
    }

    override fun dispose() {
        if (!closed.compareAndSet(false, true)) return
        submit {
            timer?.cancel(false); timer = null
            registrations.clear()
            roots.values.forEach { it.close() }; roots.clear()
        }
    }

    private class NativeRoot(val file: VirtualFile, val request: LocalFileSystem.WatchRequest) : Root {
        override fun close() { LocalFileSystem.getInstance().removeWatchedRoot(request) }
    }

    private class NativePlatform(private val project: Project) : Platform {
        override fun install(path: Path, cancelled: () -> Boolean): Root? {
            check(!ApplicationManager.getApplication().isDispatchThread)
            if (cancelled() || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) return null
            // A symlink in an ancestor must not bypass the lexical scope guard.
            val physical = path.toRealPath()
            val home = Path.of(System.getProperty("user.home")).toRealPath()
            val projectRoot = project.basePath?.let { runCatching { Path.of(it).toRealPath() }.getOrNull() }
            if (!isSafeDependencyRoot(physical, projectRoot, home)) return null
            val local = LocalFileSystem.getInstance()
            val request = local.addRootToWatch(path.toString(), true) ?: return null
            try {
                val root = local.refreshAndFindFileByNioFile(path) ?: return null.also { local.removeWatchedRoot(request) }
                // LocalFileSystem's contract requires watched files to be loaded
                // into VFS. recursive refresh alone visits only cached children.
                val pending = ArrayDeque<Pair<VirtualFile, Int>>()
                pending.add(root to 0)
                var visited = 0
                while (pending.isNotEmpty()) {
                    if (cancelled()) { local.removeWatchedRoot(request); return null }
                    val (directory, depth) = pending.removeFirst()
                    require(depth <= MAX_DEPTH) { "External Go directory exceeds the VFS depth limit" }
                    // getChildren() materializes the whole immediate directory.
                    // Check its size first so one enormous cold folder cannot
                    // populate an unbounded VFS snapshot before the limit fires.
                    Files.newDirectoryStream(Path.of(directory.path)).use { entries ->
                        var count = 0
                        for (ignored in entries) {
                            if (cancelled()) { local.removeWatchedRoot(request); return null }
                            require(visited + ++count <= MAX_ENTRIES) { "External Go directory exceeds the VFS entry limit" }
                        }
                    }
                    val children = ReadAction.compute<Array<VirtualFile>, RuntimeException> { directory.children }
                    visited += children.size
                    require(visited <= MAX_ENTRIES) { "External Go directory exceeds the VFS entry limit" }
                    children.filter { it.isDirectory && !it.`is`(VFileProperty.SYMLINK) && it.name !in VCS_DIRECTORIES }
                        .forEach { pending.add(it to depth + 1) }
                }
                if (cancelled()) { local.removeWatchedRoot(request); return null }
                return NativeRoot(root, request)
            } catch (failure: Throwable) {
                local.removeWatchedRoot(request)
                throw failure
            }
        }

        override fun refresh(roots: List<Root>, finished: () -> Unit) {
            LocalFileSystem.getInstance().refreshFiles(roots.map { (it as NativeRoot).file }, true, true, Runnable(finished))
        }
    }

    companion object {
        private val LOG = Logger.getInstance(GoExternalFileWatches::class.java)
        private const val METHOD = "workspace/didChangeWatchedFiles"
        private const val MAX_ROOTS = 128
        private const val MAX_ENTRIES = 20_000
        private const val MAX_DEPTH = 64
        private val VCS_DIRECTORIES = setOf(".git", ".hg", ".svn")

        internal fun watcherRoots(watchers: List<FileSystemWatcher>, projectPath: String?): Set<Path> {
            val project = projectPath?.let { runCatching { Path.of(it).takeIf(Path::isAbsolute)?.normalize() }.getOrNull() }
            val home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize()
            return watchers.take(512).mapNotNull { watcher ->
                if (watcher.kind == 0) return@mapNotNull null
                runCatching {
                    val pattern = watcher.globPattern ?: return@runCatching null
                    val root = if (pattern.isLeft) absoluteGlobRoot(pattern.left) else {
                        val relative = pattern.right ?: return@runCatching null
                        val base = relative.baseUri ?: return@runCatching null
                        val uri = if (base.isRight) base.right else base.left?.uri
                        if (uri == null || URI(uri).scheme != "file") return@runCatching null
                        val suffix = relative.pattern ?: return@runCatching null
                        relativeGlobRoot(Path.of(URI(uri)), suffix)
                    } ?: return@runCatching null
                    // Never turn an unrooted glob, drive/filesystem root, home or
                    // a project ancestor into a broad disk watcher. The project
                    // tree already has its ordinary platform monitoring.
                    root.takeIf { isExternalRoot(it, project, home) }
                }.getOrNull()
            }.toSet()
        }

        private fun absoluteGlobRoot(pattern: String): Path? {
            if (pattern.isBlank() || pattern.length > 4096) return null
            val normalized = pattern.replace('\\', '/')
            val wildcard = normalized.indexOfFirst { it in "*?{[" }
            val prefix = if (wildcard < 0) normalized else normalized.substring(0, wildcard)
            val directory = prefix.substringBeforeLast('/', "")
            if (directory.isEmpty() || directory.split('/', '\\').any { it == ".." }) return null
            return Path.of(directory).takeIf(Path::isAbsolute)?.normalize()
        }

        private fun relativeGlobRoot(base: Path, pattern: String): Path? {
            if (!base.isAbsolute || pattern.isBlank() || pattern.length > 4096) return null
            val normalized = pattern.replace('\\', '/')
            if (normalized.startsWith('/') || normalized.split('/').any { it == ".." }) return null
            val wildcard = normalized.indexOfFirst { it in "*?{[" }
            val prefix = if (wildcard < 0) normalized else normalized.substring(0, wildcard)
            val directory = prefix.substringBeforeLast('/', "")
            if (Path.of(directory).isAbsolute) return null
            // baseUri is a literal URI path, including characters such as '['.
            // Only the relative pattern may contribute glob metacharacters.
            return base.resolve(directory).normalize()
        }

        private fun isExternalRoot(root: Path, project: Path?, home: Path) = root.parent != null &&
            !home.startsWith(root) && (project == null || !project.startsWith(root) && !root.startsWith(project))

        internal fun isSafeDependencyRoot(root: Path, project: Path?, home: Path = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize()) =
            root.isAbsolute && root.normalize() == root && root.parent != null && !home.startsWith(root) && (project == null || !project.startsWith(root))
    }
}
