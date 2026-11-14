package dev.openidea.goide.lsp

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.LightPlatformTestCase
import com.redhat.devtools.lsp4ij.JSONUtils
import org.eclipse.lsp4j.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Controlled platform leases prove lifecycle/concurrency separately from the real cold-VFS integration test. */
class GoExternalFileWatchesTest : LightPlatformTestCase() {
    override fun runInDispatchThread() = false

    fun testRegistrationReplacementAndUnregistrationRetainAnotherOwnersSharedRoot() = fixture { root, platform, watches ->
        val first = root.resolve("first"); val second = root.resolve("second")
        watches.register(registration("a", glob(first), glob(first))).await()
        watches.register(registration("b", glob(first))).await()
        assertEquals(listOf(first), platform.installed.toList())
        watches.register(registration("a", glob(second))).await()
        assertEquals(listOf(first, second), platform.installed.toList())
        assertTrue("b still owns the first root", platform.closed.isEmpty())
        watches.unregister(unregistration("a")).await()
        assertEquals(second, platform.closed.poll(5, TimeUnit.SECONDS))
        assertTrue(platform.closed.isEmpty())
        watches.unregister(unregistration("b")).await()
        assertEquals(first, platform.closed.poll(5, TimeUnit.SECONDS))
    }

    fun testOwnedScopeChecksActualCoverageAndReleasesPartialPrimingAfterRefusal() = fixture { root, platform, watches ->
        val accepted = root.resolve("accepted")
        val refused = root.resolve("refused")
        platform.refused.add(refused)
        watches.registerOwned("scope", setOf(accepted, refused)).await()
        assertNotNull("A matching glob is not proof that every root was primed", runCatching {
            watches.requireCoverage("scope", setOf(accepted, refused)).await()
        }.exceptionOrNull())
        watches.unregister(unregistration("scope")).await()
        assertEquals(accepted, platform.closed.poll(5, TimeUnit.SECONDS))
        assertTrue(platform.closed.isEmpty())
    }

    fun testDisposeDuringBackgroundInstallReleasesTheLateLeaseAndCannotReopenIt() = fixture { root, platform, watches ->
        platform.releaseInstall = CountDownLatch(1)
        val pending = watches.register(registration("held", glob(root.resolve("held"))))
        assertTrue(platform.installStarted.await(5, TimeUnit.SECONDS))
        Disposer.dispose(watches)
        platform.releaseInstall!!.countDown()
        pending.await()
        assertEquals(root.resolve("held"), platform.closed.poll(5, TimeUnit.SECONDS))
        watches.register(registration("after-close", glob(root.resolve("late")))).await()
        assertEquals("A disposed language client cannot install new directory watches", 1, platform.installed.size)
    }

    fun testDirtyRefreshIsBackgroundAndCoalescesWhileThePreviousRefreshIsPending() = fixture { root, platform, watches ->
        watches.register(registration("root", glob(root.resolve("watched")))).await()
        watches.requestRefresh().await()
        repeat(30) { watches.requestRefresh().await() }
        assertEquals(1, platform.refreshes.get())
        platform.completions.poll(5, TimeUnit.SECONDS)!!.invoke()
        // Completion now includes the asynchronously acknowledged client notice.
        repeat(2) { watches.register(registration("root", glob(root.resolve("watched")))).await() }
        watches.requestRefresh().await()
        assertEquals(2, platform.refreshes.get())
        Disposer.dispose(watches)
        assertNotNull(platform.closed.poll(5, TimeUnit.SECONDS))
        platform.completions.poll(5, TimeUnit.SECONDS)!!.invoke()
        watches.requestRefresh().await()
        assertEquals("Finishing an old refresh cannot revive a disposed owner", 2, platform.refreshes.get())
    }

    fun testARefusedBroadRootDoesNotHideAnInstallableNarrowRoot() = fixture { root, platform, watches ->
        val parent = root.resolve("wide")
        val child = parent.resolve("narrow")
        platform.refused.add(parent)
        // Put the child first: selection must depend on successful coverage,
        // rather than registration order or an uninstalled parent's existence.
        watches.register(registration("tree", glob(child), glob(parent))).await()
        assertEquals(listOf(parent, child), platform.installed.toList())
        watches.unregister(unregistration("tree")).await()
        assertEquals("Only the successfully installed narrow lease is owned", child, platform.closed.poll(5, TimeUnit.SECONDS))
        assertTrue(platform.closed.isEmpty())
    }

    fun testRetainedButUnusableBroadRootDoesNotHideANewOwnersNarrowRoot() = fixture { root, platform, watches ->
        val broad = root.resolve("broad")
        val narrow = broad.resolve("narrow")
        watches.registerOwned("broad-owner", setOf(broad)).await()
        platform.unusable.add(broad)
        watches.registerOwned("narrow-owner", setOf(narrow)).await()
        assertEquals("A retained logical recovery target cannot stand in for actual VFS coverage", listOf(broad, narrow), platform.installed.toList())
        watches.requireCoverage("narrow-owner", setOf(narrow)).await()
        assertNotNull(runCatching { watches.requireCoverage("broad-owner", setOf(broad)).await() }.exceptionOrNull())
        assertTrue("The broad logical root is retained for bounded recovery", platform.closed.isEmpty())
    }

    fun testFailedAsynchronousReconciliationRetainsTheTransitionAndCoalescesUntilCompletion() {
        val first = CompletableFuture<Void>()
        val calls = AtomicInteger()
        fixture(rootsChanged = {
            check(!ApplicationManager.getApplication().isDispatchThread)
            if (calls.incrementAndGet() == 1) first else CompletableFuture.completedFuture(null)
        }) { root, platform, watches ->
            val watched = root.resolve("watched")
            watches.registerOwned("scope", setOf(watched)).await()
            platform.changes.getValue(watched).incrementAndGet()
            watches.requestRefresh().await()
            platform.completions.poll(5, TimeUnit.SECONDS)!!.invoke()
            repeat(2) { watches.registerOwned("barrier", setOf(watched)).await() }
            assertEquals(1, calls.get())
            repeat(5) { watches.requestRefresh().await() }
            assertEquals("A pending client future remains the coalescing boundary", 1, platform.refreshes.get())
            first.completeExceptionally(IllegalStateException("owned reconciliation failure"))
            repeat(2) { watches.registerOwned("barrier", setOf(watched)).await() }
            watches.requestRefresh().await()
            platform.completions.poll(5, TimeUnit.SECONDS)!!.invoke()
            repeat(2) { watches.registerOwned("barrier", setOf(watched)).await() }
            assertEquals("Failure must not acknowledge and lose the directory transition", 2, calls.get())
            watches.requestRefresh().await()
            platform.completions.poll(5, TimeUnit.SECONDS)!!.invoke()
            repeat(2) { watches.registerOwned("barrier", setOf(watched)).await() }
            assertEquals("Successful delivery acknowledges this transition exactly once", 2, calls.get())
        }
    }

    fun testUnregisteredRootsCannotDeliverALateRefreshTransitionToAnotherOwner() = fixture(rootsChanged = {
        fail("An unregistered root cannot publish a late directory change")
        CompletableFuture.completedFuture(null)
    }) { root, platform, watches ->
        val watched = root.resolve("watched")
        watches.registerOwned("old", setOf(watched)).await()
        platform.changes.getValue(watched).incrementAndGet()
        watches.requestRefresh().await()
        watches.unregister(unregistration("old")).await()
        watches.registerOwned("new", setOf(watched)).await()
        platform.completions.poll(5, TimeUnit.SECONDS)!!.invoke()
        watches.registerOwned("barrier", setOf(watched)).await()
        assertEquals(listOf(watched, watched), platform.installed.toList())
    }

    fun testRelativePatternBaseUriKeepsBracketDirectoryNamesLiteral() {
        val root = Files.createTempDirectory("go watcher literal URI ").toRealPath()
        try {
            val base = root.resolve("deps[old]/external")
            val patterns = listOf("**/*.go", "nested/**/*.go").map { suffix ->
                FileSystemWatcher().apply { setGlobPattern(RelativePattern().apply { setBaseUri(base.toUri().toString()); pattern = suffix }) }
            }
            assertEquals("URI path characters are literal; only RelativePattern.pattern contains glob syntax", setOf(base, base.resolve("nested")),
                GoExternalFileWatches.watcherRoots(patterns, root.resolve("project").toString()))
        } finally { root.toFile().deleteRecursively() }
    }

    fun testRelativePatternsAndAbsoluteFilesHaveBoundedExternalRootsButBroadAndEscapingPatternsDoNot() {
        val root = Files.createTempDirectory("go watcher scope ").toRealPath()
        try {
            val project = root.resolve("project")
            val external = root.resolve("external")
            val relative = FileSystemWatcher().apply {
                setGlobPattern(RelativePattern().apply { setBaseUri(external.toUri().toString()); pattern = "nested/**/*.go" })
            }
            val exact = FileSystemWatcher().apply { setGlobPattern(external.resolve("go.mod").toString().replace('\\', '/')) }
            assertEquals(setOf(external.resolve("nested"), external), GoExternalFileWatches.watcherRoots(listOf(relative, exact), project.toString()))
            val home = Path.of(System.getProperty("user.home"))
            val rejected = listOf(
                "**/*.go", root.root.toString().replace('\\', '/') + "**/*.go",
                glob(home).globPattern.left, glob(project).globPattern.left, glob(root).globPattern.left,
                external.toString().replace('\\', '/') + "/../escape/**/*.go",
            ).map { FileSystemWatcher().apply { setGlobPattern(it) } } + FileSystemWatcher().apply {
                setGlobPattern(RelativePattern().apply { setBaseUri(external.toUri().toString()); pattern = "../escape/**/*.go" })
            }
            assertTrue(GoExternalFileWatches.watcherRoots(rejected, project.toString()).isEmpty())
            assertEquals("Rejecting a parent glob must not mask a valid sibling replacement root", setOf(external),
                GoExternalFileWatches.watcherRoots(listOf(glob(root), glob(external)), project.toString()))
        } finally { root.toFile().deleteRecursively() }
    }

    private class RecordingPlatform : GoExternalFileWatches.Platform {
        val installed = CopyOnWriteArrayList<Path>()
        val refused = java.util.concurrent.CopyOnWriteArraySet<Path>()
        val unusable = java.util.concurrent.CopyOnWriteArraySet<Path>()
        val closed = LinkedBlockingQueue<Path>()
        val installStarted = CountDownLatch(1)
        var releaseInstall: CountDownLatch? = null
        val refreshes = AtomicInteger()
        val completions = LinkedBlockingQueue<() -> Unit>()
        val changes = ConcurrentHashMap<Path, AtomicLong>()
        override fun install(path: Path, cancelled: () -> Boolean): GoExternalFileWatches.Root? {
            check(!ApplicationManager.getApplication().isDispatchThread)
            installed.add(path)
            if (path in refused) return null
            installStarted.countDown()
            check(releaseInstall?.await(5, TimeUnit.SECONDS) != false)
            // Deliberately return a lease even after close to test late cleanup.
            val current = changes.computeIfAbsent(path) { AtomicLong() }
            val acknowledged = AtomicLong(current.get())
            return object : GoExternalFileWatches.Root {
                override fun usable() = path !in unusable
                override fun pendingChange(): Long? = current.get().takeIf { it != acknowledged.get() }
                override fun acknowledgeChange(revision: Long) { acknowledged.updateAndGet { maxOf(it, revision) } }
                override fun close() { closed.offer(path) }
            }
        }
        override fun refresh(roots: List<GoExternalFileWatches.Root>, finished: () -> Unit) {
            check(!ApplicationManager.getApplication().isDispatchThread)
            check(roots.isNotEmpty())
            refreshes.incrementAndGet(); completions.offer(finished)
        }
    }

    private fun fixture(rootsChanged: (Set<Path>) -> CompletableFuture<Void> = { CompletableFuture.completedFuture(null) },
                        block: (Path, RecordingPlatform, GoExternalFileWatches) -> Unit) {
        val root = Files.createTempDirectory("go watcher lifecycle ").toRealPath()
        val platform = RecordingPlatform()
        val watches = GoExternalFileWatches(project, platform, rootsChanged)
        try { block(root, platform, watches) }
        finally { platform.releaseInstall?.countDown(); Disposer.dispose(watches); root.toFile().deleteRecursively() }
    }

    private fun registration(id: String, vararg watchers: FileSystemWatcher): RegistrationParams = RegistrationParams(listOf(
        Registration(id, "workspace/didChangeWatchedFiles", JSONUtils.getLsp4jGson().toJsonTree(DidChangeWatchedFilesRegistrationOptions(watchers.toList())))
    ))
    private fun unregistration(id: String) = UnregistrationParams(listOf(Unregistration(id, "workspace/didChangeWatchedFiles")))
    private fun glob(root: Path) = FileSystemWatcher().apply { setGlobPattern(root.toString().replace('\\', '/') + "/**/*.{go,mod,sum,work}") }
    private fun CompletableFuture<Void>.await() { get(10, TimeUnit.SECONDS) }
}
