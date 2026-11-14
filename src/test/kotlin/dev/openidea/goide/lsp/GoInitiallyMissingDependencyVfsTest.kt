package dev.openidea.goide.lsp

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.NewVirtualFile
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.LightPlatformTestCase
import dev.openidea.goide.run.GoLocalDependencyScopeOperation
import dev.openidea.goide.run.GoLocalDependencyScopeSnapshot
import dev.openidea.goide.run.GoRunBuildContextLoader
import dev.openidea.goide.run.GoRunBuildContextRequest
import dev.openidea.goide.run.GoRunParameters
import dev.openidea.goide.settings.GoToolchain
import org.eclipse.lsp4j.Unregistration
import org.eclipse.lsp4j.UnregistrationParams
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Real selected-Go Loader -> production coordinator/probe -> NativePlatform/VFS.
 * The test app has no fsnotifier: dirty markers are supplied only on the owned
 * root or its already-cached source, never a parent/global refresh. This is not
 * an LSP wire, gopls diagnostics, native desktop or actual fsnotifier claim.
 */
class GoInitiallyMissingDependencyVfsTest : LightPlatformTestCase() {
    override fun runInDispatchThread() = false

    fun testAnInitiallyMissingRootIsPrimedAfterAnAtomicCompleteCheckout() = fixture { f ->
        val missing = f.startMissing()
        assertFalse(missing.complete)
        assertEquals(setOf(f.dependency), missing.recoveryCandidates.keys)
        assertTrue(f.registrations.isEmpty())
        assertNull(f.local.findFileByPathIfCached(f.dependency.toString()))

        val after = f.queryCheckpoint()
        val staging = Files.createDirectory(f.temporary.resolve("prepared-checkout"))
        Files.writeString(staging.resolve("go.mod"), f.dependencyMod)
        val prepared = Files.createDirectories(staging.resolve("deep/a/b")).resolve("leaf.go")
        Files.writeString(prepared, f.originalDependencySource)
        Files.move(staging, f.dependency)
        assertFalse("Creating the root must not rely on a fabricated parent dirty marker", f.cachedParent.isDirty)

        f.pending.requestCheck().get(10, TimeUnit.SECONDS)
        val loaded = f.awaitSnapshot(after) { it.complete && it.roots == setOf(f.dependency) }
        assertTrue(loaded.diagnostics.toString(), loaded.complete)
        f.awaitReconciliation()
        f.assertRealCoverage()
        f.assertColdSourceAndOwnedRefresh()
    }

    fun testSlowCheckoutInstallsARealPartialLeaseBeforeTheDeepSourceArrives() = fixture { f ->
        f.startMissing()
        val beforeDirectory = f.queryCheckpoint()
        Files.createDirectory(f.dependency)
        assertFalse(f.cachedParent.isDirty)
        f.pending.requestCheck().get(10, TimeUnit.SECONDS)
        val emptyDirectory = f.awaitSnapshot(beforeDirectory) { !it.complete && it.recoveryCandidates[f.dependency]?.isDirectory == true }
        assertEquals("A partial discovery does not become a complete loaded graph", emptySet<Path>(), emptyDirectory.roots)
        f.awaitReconciliation()
        f.assertRealCoverage()

        val beforeManifest = f.queryCheckpoint()
        Files.writeString(f.dependency.resolve("go.mod"), f.dependencyMod)
        f.refreshOwnedRoot()
        f.awaitSnapshot(beforeManifest) { !it.complete && it.recoveryCandidates[f.dependency]?.isDirectory == true }
        f.assertRealCoverage()

        val beforeSource = f.queryCheckpoint()
        Files.createDirectories(f.source.parent)
        Files.writeString(f.source, f.originalDependencySource)
        f.refreshOwnedRoot()
        f.awaitSnapshot(beforeSource) { it.complete && it.roots == setOf(f.dependency) }
        f.assertRealCoverage()
        f.assertColdSourceAndOwnedRefresh()
    }

    private fun fixture(action: (Fixture) -> Unit) {
        Fixture().use { fixture -> action(fixture) }
    }

    private inner class Fixture : AutoCloseable {
        private val go = Path.of(requireNotNull(GoToolchain.resolve("go",
            System.getProperty("opengoland.testGo") ?: System.getenv("OPENGOLAND_GO_TEST").orEmpty())))
        val temporary: Path = Files.createTempDirectory("go initially missing VFS 中文😀 ").toRealPath()
        private val directory = Files.createDirectory(temporary.resolve("module"))
        val dependency = temporary.resolve("dependency")
        val source = dependency.resolve("deep/a/b/leaf.go")
        val dependencyMod = "module example.test/initial/dependency\n\ngo 1.26\n"
        val originalDependencySource = "package leaf\n\nfunc Message() string { return \"依赖-v1😀\" }\n"
        val local = LocalFileSystem.getInstance()
        private val lifetime = Disposer.newDisposable("initial missing dependency VFS contract")
        private val closing = AtomicBoolean()
        private val control = Executors.newSingleThreadExecutor()
        private val queries = Executors.newFixedThreadPool(2)
        private val loader = GoRunBuildContextLoader(project)
        private val querySequence = AtomicInteger()
        private val queryResults = LinkedBlockingQueue<QueryResult>()
        private val reconciled = LinkedBlockingQueue<Set<String>>()
        private val operations = CopyOnWriteArrayList<GoLocalDependencyScopeOperation>()
        val registrations = ConcurrentHashMap<String, Set<Path>>()
        private val events = CopyOnWriteArrayList<VFileEvent>()
        private val contentChanged = CountDownLatch(1)
        private var clock = 0L
        private lateinit var scopes: GoDependencyScopeCoordinator
        val pending = GoPendingDependencyRoots(project, { discover(retainRecovery = true) }, { clock }, executor = control,
            scheduler = GoPendingDependencyRoots.Scheduler { AutoCloseable {} })
        private val watches = GoExternalFileWatches(project, rootsChanged = ::reconcile)
        private val settings = GoToolchain.Settings(go.toString(), "", "", "", "", "", "0",
            "GOROOT=${go.toRealPath().parent.parent}\nGOENV=off\nGOTOOLCHAIN=local\nGOWORK=off\nGOPROXY=off\nGOSUMDB=off\nGOTELEMETRY=off\nGOFLAGS=")
        private val values = GoRunParameters(passParentEnvironment = false, environment = linkedMapOf(
            "GOCACHE" to (System.getenv("GOCACHE") ?: temporary.resolve("cache").toString()),
            "GOPATH" to temporary.resolve("gopath").toString(),
        ).apply { if (SystemInfo.isWindows) System.getenv("SystemRoot")?.let { put("SystemRoot", it) } })
        private val caller = directory.resolve("main.go")
        private val callerInputs: Map<Path, String>
        val cachedParent: NewVirtualFile

        init {
            VfsRootAccess.allowRootAccess(lifetime, temporary.toString())
            Files.writeString(directory.resolve("go.mod"), "module example.test/initial/importer\n\ngo 1.26\n" +
                "require example.test/initial/dependency v0.0.0\nreplace example.test/initial/dependency => ../dependency\n")
            Files.writeString(caller, "package main\n\nimport dep \"example.test/initial/dependency/deep/a/b\"\n" +
                "\nfunc Compose() string { return dep.Message() }\nfunc main() { _ = Compose() }\n")
            Files.writeString(directory.resolve("main_test.go"), "package main\n\nimport \"testing\"\nfunc TestCompose(t *testing.T) { _ = Compose() }\n")
            callerInputs = callerHashes()
            cachedParent = requireNotNull(local.refreshAndFindFileByNioFile(temporary)) as NewVirtualFile
            ReadAction.run<RuntimeException> { cachedParent.children }
            cachedParent.markClean()
            assertFalse(cachedParent.isDirty)
            assertNull(local.findFileByPathIfCached(dependency.toString()))
            scopes = GoDependencyScopeCoordinator(loaderQuery(), ::register, ::reconcile, ::unregister, control, queries,
                recoveryChanged = { candidates, installed -> pending.update(candidates, installed) }, retryRequired = { discover() })
            project.messageBus.connect(lifetime).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
                override fun after(values: List<VFileEvent>) {
                    events.addAll(values)
                    if (values.any { it is VFileContentChangeEvent && Path.of(it.path) == source }) contentChanged.countDown()
                    if (!closing.get() && values.any { event ->
                            val path = runCatching { Path.of(event.path) }.getOrNull()
                            val parent = path?.parent
                            path != null && parent != null && path.startsWith(temporary) &&
                                scopes.affected(GoDocumentSync.fileUri(path), GoDocumentSync.fileUri(parent), event.file?.isDirectory == true)
                        }) discover()
                }
            })
        }

        private fun loaderQuery(): (GoRunBuildContextRequest) -> GoLocalDependencyScopeOperation = { request ->
            val ordinal = querySequence.incrementAndGet()
            loader.createDependencyScope(request).also { operation ->
                operations += operation
                operation.future.whenComplete { value, error -> queryResults.offer(QueryResult(ordinal, value, error)) }
            }
        }

        private fun discover(retainRecovery: Boolean = false) {
            if (closing.get()) return
            val request = GoRunBuildContextRequest(GoDocumentSync.fileUri(caller), directory.toString(), values.independentCopy(), settings, emptyList())
            scopes.invalidateQueries(retainRecovery)
            scopes.update(mapOf(directory.toString() to GoDependencyScopeInput(request) { !closing.get() }))
        }

        fun startMissing(): GoLocalDependencyScopeSnapshot {
            assertFalse(Files.exists(dependency))
            discover()
            val result = awaitSnapshot { !it.complete && it.recoveryCandidates.keys == setOf(dependency) }
            assertFalse(result.recoveryCandidates.getValue(dependency).isDirectory)
            assertTrue(registrations.isEmpty())
            assertFalse(cachedParent.isDirty)
            return result
        }

        private fun register(id: String, roots: Set<Path>): CompletableFuture<Void> {
            check(!ApplicationManager.getApplication().isDispatchThread)
            assertTrue("No nonexistent path may be treated as covered", roots.all { Files.isDirectory(it) })
            return watches.registerOwned(id, roots).thenCompose { watches.requireCoverage(id, roots) }.thenRun { registrations[id] = roots }
        }

        private fun unregister(id: String): CompletableFuture<Void> = watches.unregister(
            UnregistrationParams(listOf(Unregistration(id, "workspace/didChangeWatchedFiles"))))
            .thenRun { registrations.remove(id) }

        private fun reconcile(roots: Set<Path>): CompletableFuture<Void> {
            check(!ApplicationManager.getApplication().isDispatchThread)
            reconciled.offer(roots.mapTo(linkedSetOf()) { GoDocumentSync.fileUri(it) })
            return CompletableFuture.completedFuture(null)
        }

        fun queryCheckpoint(): Int = querySequence.get()

        fun awaitSnapshot(after: Int = 0, predicate: (GoLocalDependencyScopeSnapshot) -> Boolean): GoLocalDependencyScopeSnapshot {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            val failures = mutableListOf<String>()
            while (System.nanoTime() < deadline) {
                val next = queryResults.poll((deadline - System.nanoTime()).coerceAtLeast(1), TimeUnit.NANOSECONDS) ?: break
                if (next.ordinal <= after) continue
                val snapshot = next.snapshot
                if (snapshot == null) { failures += next.failure.toString(); continue }
                if (!predicate(snapshot)) continue
                if (!snapshotCurrent(snapshot)) continue
                // Query completion and publication are different boundaries.
                CompletableFuture.runAsync({}, control).get(10, TimeUnit.SECONDS)
                scopes.idle().get(10, TimeUnit.SECONDS)
                // A queued event/publication must not turn the selected sample
                // stale while the fixture waits for those separate boundaries.
                if (!snapshotCurrent(snapshot)) continue
                return snapshot
            }
            error("No expected selected-Go snapshot: failures=$failures scope=${scopes.failures()} events=$events")
        }

        private fun snapshotCurrent(snapshot: GoLocalDependencyScopeSnapshot): Boolean = runCatching {
            snapshot.validateDisk()
            snapshot.recoveryCandidates.values.all { it.isCurrent() }
        }.getOrDefault(false)

        fun awaitReconciliation() {
            val uris = reconciled.poll(10, TimeUnit.SECONDS)
            assertEquals("Only stable lexical directory URIs are recorded; no gopls wire claim", setOf(GoDocumentSync.fileUri(dependency)), uris)
        }

        fun assertRealCoverage() {
            assertEquals(setOf(dependency), registrations.values.flatten().toSet())
            registrations.forEach { (id, roots) -> watches.requireCoverage(id, roots).get(10, TimeUnit.SECONDS) }
        }

        fun refreshOwnedRoot() {
            val owned = requireNotNull(local.findFileByPathIfCached(dependency.toString())) as NewVirtualFile
            // Once a real lease exists, SDK events may naturally dirty an
            // ancestor. The test neither marks nor refreshes that ancestor;
            // only this already-owned root receives the headless dirty marker.
            owned.markDirty()
            watches.requestRefresh().get(10, TimeUnit.SECONDS)
            awaitOwnedRefreshIdle()
        }

        private fun awaitOwnedRefreshIdle() {
            // The public refresh future covers enqueueing. These are actual
            // serial-queue fences, not sleeps or parent/global refresh helpers.
            val refreshing = GoExternalFileWatches::class.java.getDeclaredField("refreshPending").apply { isAccessible = true }
                .get(watches) as AtomicBoolean
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (refreshing.get() && System.nanoTime() < deadline) {
                watches.registerOwned("test-refresh-fence", emptySet())
                    .get((deadline - System.nanoTime()).coerceAtLeast(1), TimeUnit.NANOSECONDS)
            }
            assertFalse("The owned NativePlatform refresh must finish before the next mutation", refreshing.get())
        }

        fun assertColdSourceAndOwnedRefresh() {
            awaitOwnedRefreshIdle()
            val cached = requireNotNull(local.findFileByPathIfCached(source.toString()))
            assertTrue(cached.isValid)
            assertNull(FileDocumentManager.getInstance().getCachedDocument(cached))
            assertEquals(originalDependencySource, ReadAction.compute<String, RuntimeException> { String(cached.contentsToByteArray(), Charsets.UTF_8) })
            val longer = "package leaf\n\nfunc AfterOwnedRefreshWithALongerName() string { return \"只改外部依赖😀\" }\n"
            Files.writeString(source, longer)
            Files.setLastModifiedTime(source, FileTime.fromMillis(cached.timeStamp + 2000))
            (cached as NewVirtualFile).markDirty()
            watches.requestRefresh().get(10, TimeUnit.SECONDS)
            assertTrue("A real SDK content event must come from the owned refresh: $events", contentChanged.await(10, TimeUnit.SECONDS))
            awaitOwnedRefreshIdle()
            assertEquals(longer.toByteArray(Charsets.UTF_8).size.toLong(), cached.length)
            assertEquals(longer, ReadAction.compute<String, RuntimeException> { String(cached.contentsToByteArray(), Charsets.UTF_8) })
            assertNull(FileDocumentManager.getInstance().getCachedDocument(cached))
            assertEquals("Caller bytes must not be edited to rescue discovery", callerInputs, callerHashes())
        }

        private fun callerHashes() = listOf("go.mod", "main.go", "main_test.go").associate { name ->
            val path = directory.resolve(name)
            path to MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }
        }

        override fun close() {
            closing.set(true)
            val failures = mutableListOf<Throwable>()
            fun clean(action: () -> Unit) { try { action() } catch (failure: Throwable) { failures += failure } }
            clean { Disposer.dispose(pending) }
            clean { scopes.dispose() }
            clean { loader.dispose() }
            operations.forEach { operation -> clean { operation.dispose() } }
            queries.shutdownNow()
            clean { check(queries.awaitTermination(10, TimeUnit.SECONDS)) { "Owned metadata processes did not stop" } }
            clean { CompletableFuture.runAsync({}, control).get(10, TimeUnit.SECONDS) }
            clean { scopes.idle().get(10, TimeUnit.SECONDS) }
            clean { assertTrue("All owned registration IDs must be released", registrations.isEmpty()) }
            clean { Disposer.dispose(watches) }
            clean { watches.registerOwned("closed-drain", emptySet()).get(10, TimeUnit.SECONDS) }
            control.shutdown()
            clean { check(control.awaitTermination(10, TimeUnit.SECONDS)) }
            clean { Disposer.dispose(lifetime) }
            clean { assertEquals(callerInputs, callerHashes()) }
            if (queries.isTerminated && control.isTerminated && registrations.isEmpty()) {
                clean { temporary.toFile().deleteRecursively() }
                clean { local.refreshAndFindFileByNioFile(temporary) }
            } else failures += IllegalStateException("Preserved fixture with unfinished owned work: $temporary")
            if (failures.isNotEmpty()) throw AssertionError("Initial-root fixture cleanup failed: $failures").also { error ->
                failures.forEach(error::addSuppressed)
            }
        }
    }

    private data class QueryResult(val ordinal: Int, val snapshot: GoLocalDependencyScopeSnapshot?, val failure: Throwable?)
}
