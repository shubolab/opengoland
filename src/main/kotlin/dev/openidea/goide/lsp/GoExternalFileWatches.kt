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
import com.intellij.openapi.vfs.newvfs.NewVirtualFile
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
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * LSP4IJ matches registered globs against VFS events, but does not discover or
 * watch a cold directory outside the project. Own just those additional roots.
 * All IO, initial VFS loading and watch changes run on the serial background queue.
 */
internal class GoExternalFileWatches(
    private val project: Project,
    private val platform: Platform = NativePlatform(project),
    private val rootsChanged: (Set<Path>) -> CompletableFuture<Void> = { CompletableFuture.completedFuture(null) },
) : Disposable {
    internal interface Root : AutoCloseable {
        fun usable(): Boolean = true
        fun pendingChange(): Long? = null
        fun acknowledgeChange(revision: Long) {}
    }
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
        check(!closed.get() && registrations[id] == expected && expected.all { path -> roots.any { (base, root) -> path.startsWith(base) && root.usable() } }) {
            "Some external Go dependency roots could not be watched within the VFS limits"
        }
    }

    private fun reconcile() {
        val all = registrations.values.flatten().toSet()
        val desired = linkedSetOf<Path>()
        val covering = linkedSetOf<Path>()
        // Only a successfully installed ancestor covers a descendant. A broad
        // root may exceed the entry/depth budget while a narrow root is usable.
        for (path in all.sortedBy { it.nameCount }) {
            if (closed.get() || project.isDisposed) break
            if (covering.any { path.startsWith(it) }) continue
            if (desired.size >= MAX_ROOTS) break
            val existing = roots[path]
            if (existing != null) {
                desired.add(path)
                if (existing.usable()) covering.add(path)
                continue
            }
            runCatching { platform.install(path) { closed.get() || project.isDisposed } }
                .onSuccess { root -> if (root != null) {
                    roots[path] = root; desired.add(path)
                    if (root.usable()) covering.add(path)
                } }
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
            else try {
                val refreshing = roots.toMap()
                platform.refresh(refreshing.values.toList()) {
                    submit {
                        val changes = refreshing.mapNotNull { (path, root) ->
                            if (roots[path] === root) root.pendingChange()?.let { Triple(path, root, it) } else null
                        }
                        val delivered = try {
                            // The callback must only enqueue client reconciliation.
                            // No IO or future wait occurs under this lifecycle lock.
                            synchronized(lock) {
                                if (!closed.get() && !project.isDisposed && changes.isNotEmpty())
                                    rootsChanged(changes.mapTo(linkedSetOf()) { it.first })
                                else CompletableFuture.completedFuture(null)
                            }
                        } catch (failure: Exception) { CompletableFuture.failedFuture(failure) }
                        delivered.whenComplete { _, failure ->
                            submit {
                                try {
                                    synchronized(lock) {
                                        if (!closed.get() && !project.isDisposed) {
                                            if (failure == null) changes.forEach { (path, root, revision) ->
                                                if (roots[path] === root) root.acknowledgeChange(revision)
                                            } else LOG.warn("Unable to reconcile replaced external Go directories", failure)
                                        }
                                    }
                                } finally { refreshPending.set(false) }
                            }
                        }
                    }
                }
            }
            catch (failure: Exception) { refreshPending.set(false); LOG.warn("Unable to refresh external Go directories", failure) }
        }
    }

    private fun submit(action: () -> Unit): CompletableFuture<Void> = synchronized(lock) {
        queue.handle { _, _ -> null }.thenRunAsync(action, AppExecutorUtil.getAppExecutorService()).also { queue = it }
    }

    override fun dispose() {
        synchronized(lock) { if (!closed.compareAndSet(false, true)) return }
        submit {
            timer?.cancel(false); timer = null
            registrations.clear()
            roots.values.forEach { it.close() }; roots.clear()
        }
    }

    private data class DirectoryIdentity(val key: Any?, val created: FileTime)
    private data class NativeLease(val file: VirtualFile, val request: LocalFileSystem.WatchRequest, val identity: DirectoryIdentity) {
        fun close() { LocalFileSystem.getInstance().removeWatchedRoot(request) }
    }

    private class NativeRoot(val path: Path, initial: NativeLease, private val cancelled: () -> Boolean) : Root {
        private val lifetime = Any()
        @Volatile private var closed = false
        private var lease: NativeLease? = initial
        private var cachedFile: VirtualFile? = initial.file
        private val revision = AtomicLong()
        private val acknowledged = AtomicLong()
        @Volatile private var retryAfterNanos = 0L

        fun cancelled() = closed || cancelled.invoke()
        fun current(): NativeLease? = synchronized(lifetime) { if (cancelled()) null else lease }
        fun cachedFile(): VirtualFile? = synchronized(lifetime) {
            if (cancelled()) null else cachedFile?.takeIf { Path.of(it.path).normalize() == path }
        }
        fun retryReady() = System.nanoTime() >= retryAfterNanos
        fun deferFailure() { retryAfterNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(30) }
        override fun usable(): Boolean = current()?.file?.let { it.isValid && Path.of(it.path).normalize() == path } == true
        override fun pendingChange(): Long? = revision.get().takeIf { it != acknowledged.get() }
        override fun acknowledgeChange(revision: Long) { acknowledged.updateAndGet { maxOf(it, revision) } }

        fun replace(expected: NativeLease?, replacement: NativeLease?): Boolean {
            val accepted = synchronized(lifetime) {
                if (cancelled() || lease !== expected) false else {
                    lease = replacement
                    if (replacement != null) cachedFile = replacement.file
                    if (expected !== replacement) revision.incrementAndGet()
                    true
                }
            }
            if (accepted) expected?.close() else replacement?.close()
            return accepted
        }

        override fun close() {
            val previous = synchronized(lifetime) {
                if (closed) return
                closed = true
                cachedFile = null
                lease.also { lease = null }
            }
            previous?.close()
        }
    }

    private class NativePlatform(private val project: Project) : Platform {
        override fun install(path: Path, cancelled: () -> Boolean): Root? =
            installLease(path, cancelled, refreshKnown = false)?.let { NativeRoot(path, it, cancelled) }

        private fun identity(path: Path): DirectoryIdentity? = try {
            Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).let {
                if (it.isDirectory) DirectoryIdentity(it.fileKey(), it.creationTime()) else null
            }
        } catch (_: NoSuchFileException) { null } catch (_: NotDirectoryException) { null }

        private fun installLease(path: Path, cancelled: () -> Boolean, refreshKnown: Boolean): NativeLease? {
            check(!ApplicationManager.getApplication().isDispatchThread)
            if (cancelled()) return null
            val incarnation = identity(path) ?: return null
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
                    val directoryPath = Path.of(directory.path)
                    if (!Files.isDirectory(directoryPath, LinkOption.NOFOLLOW_LINKS)) continue
                    require(directoryPath.toRealPath().startsWith(physical)) { "External Go directory escaped its physical root" }
                    // getChildren() materializes the whole immediate directory.
                    // Check its size first so one enormous cold folder cannot
                    // populate an unbounded VFS snapshot before the limit fires.
                    Files.newDirectoryStream(directoryPath).use { entries ->
                        var count = 0
                        for (ignored in entries) {
                            if (cancelled()) { local.removeWatchedRoot(request); return null }
                            require(visited + ++count <= MAX_ENTRIES) { "External Go directory exceeds the VFS entry limit" }
                        }
                    }
                    if (refreshKnown) {
                        // A fast rename/recreate can retain a valid same-path VFS
                        // object with old children. Refresh only this already
                        // budget-checked immediate directory, never its parent.
                        (directory as? NewVirtualFile)?.markDirty()
                        directory.refresh(false, false)
                    }
                    if (!directory.isValid || directory.`is`(VFileProperty.SYMLINK) || !Files.isDirectory(directoryPath, LinkOption.NOFOLLOW_LINKS)) continue
                    require(directoryPath.toRealPath().startsWith(physical)) { "External Go directory escaped while priming" }
                    val children = ReadAction.compute<Array<VirtualFile>, RuntimeException> { directory.children }
                    visited += children.size
                    require(visited <= MAX_ENTRIES) { "External Go directory exceeds the VFS entry limit" }
                    children.filter { it.isDirectory && !it.`is`(VFileProperty.SYMLINK) && it.name !in VCS_DIRECTORIES }
                        .forEach { pending.add(it to depth + 1) }
                }
                if (cancelled()) { local.removeWatchedRoot(request); return null }
                check(identity(path) == incarnation && path.toRealPath() == physical) { "External Go directory changed while priming" }
                return NativeLease(root, request, incarnation)
            } catch (failure: Throwable) {
                local.removeWatchedRoot(request)
                throw failure
            }
        }

        override fun refresh(roots: List<Root>, finished: () -> Unit) {
            val native = roots.map { it as NativeRoot }
            fun repair(root: NativeRoot) {
                if (root.cancelled() || !root.retryReady()) return
                try {
                    val previous = root.current()
                    val incarnation = identity(root.path)
                    if (previous != null && incarnation == previous.identity && root.usable()) return
                    if (!root.replace(previous, null) || incarnation == null || root.cancelled()) return
                    val replacement = installLease(root.path, root::cancelled, refreshKnown = true)
                    if (replacement != null) root.replace(null, replacement)
                } catch (_: NoSuchFileException) {
                    root.replace(root.current(), null)
                } catch (_: NotDirectoryException) {
                    root.replace(root.current(), null)
                } catch (failure: Exception) {
                    root.replace(root.current(), null)
                    if (!root.cancelled()) {
                        // A refused large tree must not be rescanned or logged
                        // every two seconds. Missing paths use the cheap retry.
                        root.deferFailure()
                        LOG.warn("Unable to reacquire external Go directory ${root.path}", failure)
                    }
                }
            }
            val complete = Runnable {
                // Refresh completion may run on the EDT. All stat, path checking
                // and bounded re-priming stay on a background executor.
                AppExecutorUtil.getAppExecutorService().execute {
                    try {
                        native.forEach(::repair)
                    } finally { finished() }
                }
            }
            val shallow = mutableListOf<VirtualFile>()
            val files = native.mapNotNull { root ->
                if (root.cancelled() || !root.retryReady()) return@mapNotNull null
                val previous = root.current()
                val incarnation = try { identity(root.path) } catch (_: Exception) { repair(root); return@mapNotNull null }
                if (incarnation == null) {
                    // Missing/type-replaced roots need only their own delete or
                    // type event. Never recurse into a replacement symlink.
                    // A preceding refresh may have noticed the disappearance
                    // only in its post-refresh stat. Keep that cached generation
                    // until a later refresh can publish the real VFS deletion.
                    val old = root.cachedFile()?.takeIf(VirtualFile::isValid)
                    if (Files.exists(root.path, LinkOption.NOFOLLOW_LINKS)) { old?.let(shallow::add); null }
                    else old // A genuinely missing root is safe to invalidate recursively.
                } else if (previous == null || incarnation != previous.identity || !root.usable()) {
                    // Detect a fast replacement before a recursive refresh can
                    // traverse a new incarnation using the old cached tree.
                    repair(root)
                    null
                } else previous?.file?.takeIf(VirtualFile::isValid)
            }
            val refreshStable = Runnable {
                if (files.isEmpty()) complete.run()
                else LocalFileSystem.getInstance().refreshFiles(files, true, true, complete)
            }
            if (shallow.isEmpty()) refreshStable.run()
            else LocalFileSystem.getInstance().refreshFiles(shallow, true, false, refreshStable)
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
