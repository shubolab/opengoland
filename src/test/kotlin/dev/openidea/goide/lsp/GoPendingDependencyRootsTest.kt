package dev.openidea.goide.lsp

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.LightPlatformTestCase
import dev.openidea.goide.run.GoDependencyRootState
import java.nio.file.Files
import java.nio.file.Path
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Exact-path NIO transitions plus controlled scheduling, not watcher/Go/native-IDE acceptance. */
class GoPendingDependencyRootsTest : LightPlatformTestCase() {
    override fun runInDispatchThread() = false

    fun testUnchangedMissingRootOnlyProbesAndNeverRequestsDiscovery() = fixture { f ->
        val path = f.root.resolve("never-created")
        f.pending.update(mapOf(path to GoDependencyRootState.capture(path)), emptySet())
        repeat(50) { f.seconds(2); f.tick() }
        assertEquals(50, f.probes.get())
        assertEquals(0, f.changes.get())
        assertEquals(1, f.scheduler.tasks.size)
    }

    fun testCreationBeforeInterestUpdateKeepsTheLoadersMissingBaseline() = fixture { f ->
        val path = f.root.resolve("checkout")
        val missing = GoDependencyRootState.capture(path)
        Files.createDirectory(path)
        f.pending.update(mapOf(path to missing), emptySet())
        f.tick()
        assertEquals("Sampling inside update would lose this transition", 1, f.changes.get())
        repeat(14) { f.seconds(2); f.tick() }
        assertEquals(1, f.changes.get())
        f.seconds(2); f.tick()
        assertEquals("A failed metadata handoff remains retryable, but not every tick", 2, f.changes.get())
    }

    fun testExistingUncoveredGraceAndFreshEqualSnapshotsDoNotPostponeRetry() = fixture { f ->
        val path = Files.createDirectory(f.root.resolve("empty-checkout"))
        f.pending.update(mapOf(path to GoDependencyRootState.capture(path)), emptySet())
        f.tick()
        assertEquals(0, f.changes.get())
        repeat(14) {
            f.seconds(2)
            f.pending.update(mapOf(path to GoDependencyRootState.capture(path)), emptySet())
            f.tick()
        }
        assertEquals(0, f.changes.get())
        f.seconds(2)
        f.pending.update(mapOf(path to GoDependencyRootState.capture(path)), emptySet())
        f.tick()
        assertEquals("Equal new query objects must not restart the thirty-second grace", 1, f.changes.get())
        f.seconds(2); f.tick()
        assertEquals(1, f.changes.get())
    }

    fun testFreshDirectoryBaselineRetainsThePreviousChangedAttemptClock() = fixture { f ->
        val path = f.root.resolve("partial")
        f.pending.update(mapOf(path to GoDependencyRootState.capture(path)), emptySet())
        Files.createDirectory(path)
        f.tick()
        assertEquals(1, f.changes.get())
        f.seconds(29)
        f.pending.update(mapOf(path to GoDependencyRootState.capture(path)), emptySet())
        f.tick()
        assertEquals(1, f.changes.get())
        f.seconds(1); f.tick()
        assertEquals(2, f.changes.get())
    }

    fun testActualCoverageStopsTheTimerAndAnOldTimerCannotProbeAnotherOwner() = fixture { f ->
        val first = f.root.resolve("first")
        val firstMissing = GoDependencyRootState.capture(first)
        f.pending.update(mapOf(first to firstMissing), emptySet())
        val oldTimer = f.scheduler.tasks.single()
        f.pending.update(emptyMap(), emptySet())
        assertTrue(oldTimer.closed)
        val second = f.root.resolve("second")
        val secondMissing = GoDependencyRootState.capture(second)
        Files.createDirectory(second)
        f.pending.update(mapOf(second to secondMissing), emptySet())
        oldTimer.action.run()
        f.executor.drain()
        assertEquals("A cancelled old timer cannot adopt a new owner's candidate", 0, f.probes.get())
        f.tick()
        assertEquals(1, f.changes.get())
        val currentTimer = f.scheduler.tasks.last()
        f.pending.update(mapOf(second to GoDependencyRootState.capture(second)), setOf(f.root))
        assertTrue(currentTimer.closed)
        val before = f.probes.get()
        currentTimer.action.run(); f.executor.drain()
        assertEquals(before, f.probes.get())
    }

    fun testHeldProbeCannotRefreshAfterAnInputGenerationChanges() = fixture { f ->
        val first = f.root.resolve("old")
        f.pending.update(mapOf(first to GoDependencyRootState.capture(first)), emptySet())
        Files.createDirectory(first)
        f.heldProbe = CountDownLatch(1)
        val pending = f.pending.requestCheck()
        val thread = Executors.newSingleThreadExecutor()
        try {
            val running = thread.submit { f.executor.runOne() }
            assertTrue(f.probeStarted.await(5, TimeUnit.SECONDS))
            val second = f.root.resolve("new")
            val missing = GoDependencyRootState.capture(second)
            Files.createDirectory(second)
            f.pending.update(mapOf(second to missing), emptySet())
            f.heldProbe!!.countDown()
            running.get(5, TimeUnit.SECONDS); pending.get(5, TimeUnit.SECONDS)
            assertEquals(0, f.changes.get())
            f.heldProbe = null
            f.tick()
            assertEquals("Old completion did not throttle the new owner's first change", 1, f.changes.get())
        } finally { f.heldProbe?.countDown(); thread.shutdownNow() }
    }

    fun testDisposeWhileAStatIsHeldSuppressesItsLateRefreshAndTimer() = fixture { f ->
        val path = f.root.resolve("disposed")
        f.pending.update(mapOf(path to GoDependencyRootState.capture(path)), emptySet())
        Files.createDirectory(path)
        f.heldProbe = CountDownLatch(1)
        val pending = f.pending.requestCheck()
        val thread = Executors.newSingleThreadExecutor()
        try {
            val running = thread.submit { f.executor.runOne() }
            assertTrue(f.probeStarted.await(5, TimeUnit.SECONDS))
            Disposer.dispose(f.pending)
            f.heldProbe!!.countDown()
            running.get(5, TimeUnit.SECONDS); pending.get(5, TimeUnit.SECONDS)
            assertEquals(0, f.changes.get())
            assertTrue(f.scheduler.tasks.single().closed)
            f.pending.update(emptyMap(), emptySet())
            f.scheduler.tasks.single().action.run(); f.executor.drain()
            assertEquals(1, f.probes.get())
        } finally { f.heldProbe?.countDown(); thread.shutdownNow() }
    }

    fun testBudgetRefusalDoesNotReplaceThePreviousInterest() = fixture { f ->
        val path = f.root.resolve("kept")
        f.pending.update(mapOf(path to GoDependencyRootState.capture(path)), emptySet())
        val tooMany = (0..128).associate { n -> f.root.resolve("root-$n").let { it to GoDependencyRootState.capture(it) } }
        assertNotNull(runCatching { f.pending.update(tooMany, emptySet()) }.exceptionOrNull())
        val alreadyInstalled = (0..127).mapTo(linkedSetOf()) { f.root.resolve("installed-$it") }
        assertNotNull(runCatching { f.pending.update(mapOf(path to GoDependencyRootState.capture(path)), alreadyInstalled) }.exceptionOrNull())
        Files.createDirectory(path)
        f.tick()
        assertEquals(1, f.changes.get())
        assertEquals(1, f.scheduler.tasks.size)
    }

    fun testMismatchedOrNonLiteralKeysAreRejectedBeforeScheduling() = fixture { f ->
        val path = f.root.resolve("safe")
        val state = GoDependencyRootState.capture(path)
        assertNotNull(runCatching { f.pending.update(mapOf(Path.of("relative") to state), emptySet()) }.exceptionOrNull())
        assertNotNull(runCatching { f.pending.update(mapOf(path.resolve("..").resolve("safe") to state), emptySet()) }.exceptionOrNull())
        assertTrue(f.scheduler.tasks.isEmpty())
    }

    fun testStatFailureRetriesDiscoveryWithBackoffAndRecoveryStopsIt() = fixture { f ->
        val path = f.root.resolve("stat-refused")
        f.pending.update(mapOf(path to GoDependencyRootState.capture(path)), emptySet())
        f.probeFailure = IOException("controlled exact-path stat failure")
        f.tick()
        assertEquals(1, f.changes.get())
        repeat(14) { f.seconds(2); f.tick() }
        assertEquals(1, f.changes.get())
        f.seconds(2); f.tick()
        assertEquals(2, f.changes.get())
        f.probeFailure = null
        f.seconds(30); f.tick()
        assertEquals("A recovered unchanged missing target is not a perpetual Go query", 2, f.changes.get())
    }

    fun testDisposeBeforeTheSchedulerReturnsClosesItsLateHandle() = fixture { f ->
        val path = f.root.resolve("timer-race")
        val state = GoDependencyRootState.capture(path)
        f.scheduler.releaseSchedule = CountDownLatch(1)
        val thread = Executors.newSingleThreadExecutor()
        try {
            val updating = thread.submit { f.pending.update(mapOf(path to state), emptySet()) }
            assertTrue(f.scheduler.scheduleStarted.await(5, TimeUnit.SECONDS))
            Disposer.dispose(f.pending)
            f.scheduler.releaseSchedule!!.countDown()
            updating.get(5, TimeUnit.SECONDS)
            assertTrue(f.scheduler.tasks.single().closed)
            f.scheduler.tasks.single().action.run(); f.executor.drain()
            assertEquals(0, f.probes.get())
        } finally { f.scheduler.releaseSchedule?.countDown(); thread.shutdownNow() }
    }

    private fun fixture(action: (Fixture) -> Unit) {
        val f = Fixture()
        try { action(f) } finally { Disposer.dispose(f.pending); f.heldProbe?.countDown(); f.root.toFile().deleteRecursively() }
    }

    private inner class Fixture {
        val root: Path = Files.createTempDirectory("go pending exact root ").toRealPath()
        val scheduler = ManualScheduler()
        val executor = ManualExecutor()
        val changes = AtomicInteger()
        val probes = AtomicInteger()
        val probeStarted = CountDownLatch(1)
        @Volatile var heldProbe: CountDownLatch? = null
        @Volatile var probeFailure: IOException? = null
        var clock = 0L
        val pending = GoPendingDependencyRoots(project, {
            assertFalse(ApplicationManager.getApplication().isDispatchThread)
            changes.incrementAndGet()
        }, { clock }, { state ->
            assertFalse(ApplicationManager.getApplication().isDispatchThread)
            probes.incrementAndGet(); probeStarted.countDown()
            heldProbe?.let { check(it.await(5, TimeUnit.SECONDS)) }
            probeFailure?.let { throw it }
            state.isCurrent()
        }, executor, scheduler)
        fun seconds(value: Long) { clock += TimeUnit.SECONDS.toNanos(value) }
        fun tick() { scheduler.tasks.last().action.run(); executor.drain() }
    }

    private class ManualExecutor : Executor {
        private val tasks = ConcurrentLinkedQueue<Runnable>()
        override fun execute(command: Runnable) { tasks.add(command) }
        fun runOne() { requireNotNull(tasks.poll()).run() }
        fun drain() { while (true) (tasks.poll() ?: return).run() }
    }
    private class ManualScheduler : GoPendingDependencyRoots.Scheduler {
        class Task(val action: Runnable) : AutoCloseable {
            var closed = false
            override fun close() { closed = true }
        }
        val tasks = mutableListOf<Task>()
        val scheduleStarted = CountDownLatch(1)
        @Volatile var releaseSchedule: CountDownLatch? = null
        override fun schedule(action: Runnable): AutoCloseable = Task(action).also {
            scheduleStarted.countDown()
            releaseSchedule?.let { latch -> check(latch.await(5, TimeUnit.SECONDS)) }
            tasks += it
        }
    }
}
