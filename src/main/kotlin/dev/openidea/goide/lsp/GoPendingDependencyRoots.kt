package dev.openidea.goide.lsp

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.concurrency.SequentialTaskExecutor
import dev.openidea.goide.run.GoDependencyRootState
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * A selected but unwatched replacement is an interest, never VFS coverage.
 * The loader supplies its original exact-path baseline. A changed baseline
 * requests fresh discovery; only the owning coordinator may install a lease.
 */
internal class GoPendingDependencyRoots(
    private val project: Project,
    private val changed: () -> Unit,
    private val nanoTime: () -> Long = System::nanoTime,
    private val probe: (GoDependencyRootState) -> Boolean = { it.isCurrent() },
    private val executor: Executor = SequentialTaskExecutor.createSequentialApplicationPoolExecutor("Go pending dependency roots"),
    private val scheduler: Scheduler = Scheduler { action ->
        val future = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(action, 2, 2, TimeUnit.SECONDS)
        AutoCloseable { future.cancel(false) }
    },
) : Disposable {
    internal fun interface Scheduler { fun schedule(action: Runnable): AutoCloseable }

    private class Interest(val state: GoDependencyRootState, var attemptedAt: Long?)
    /** A close may win before the scheduler has returned its actual handle. */
    private class TimerLease : AutoCloseable {
        private var closed = false
        private var actual: AutoCloseable? = null
        @Synchronized fun attach(value: AutoCloseable) {
            if (closed) value.close() else actual = value
        }
        @Synchronized override fun close() {
            closed = true
            actual?.close()
            actual = null
        }
    }

    private val lock = Any()
    private var closed = false
    private var generation = 0L
    private var checking = false
    private var interests = linkedMapOf<Path, Interest>()
    private var timer: TimerLease? = null

    init { Disposer.register(project, this) }

    /** Memory-only. Neither sampling the new baseline nor IO is allowed here. */
    fun update(candidates: Map<Path, GoDependencyRootState>, installedRoots: Set<Path>) {
        if (synchronized(lock) { closed || project.isDisposed }) return
        require(candidates.size <= MAX_ROOTS) { "Too many pending Go dependency roots" }
        val projectRoot = project.basePath?.let { Path.of(it).normalize() }
        require(candidates.all { (path, state) ->
            path == state.path && path.toString().length <= 4096 && GoExternalFileWatches.isSafeDependencyRoot(path, projectRoot)
        }) { "An unsafe pending Go dependency root was refused" }
        val pending = candidates.filterKeys { path -> installedRoots.none { path.startsWith(it) } }
        require((installedRoots + pending.keys).size <= MAX_ROOTS) { "Go dependency roots exceed the shared watch budget" }
        var start: TimerLease? = null
        var stop: TimerLease? = null
        synchronized(lock) {
            if (closed || project.isDisposed) return
            ++generation
            val now = nanoTime()
            interests = pending.mapValuesTo(linkedMapOf()) { (path, state) ->
                // A fresh equal snapshot must not postpone a failed publication
                // forever. Preserve this path's attempt even across new baselines.
                Interest(state, interests[path]?.attemptedAt ?: if (state.isDirectory) now else null)
            }
            if (interests.isEmpty()) {
                stop = timer
                timer = null
            } else if (timer == null) {
                start = TimerLease().also { timer = it }
            }
        }
        stop?.close()
        start?.let { lease ->
            try { lease.attach(scheduler.schedule(Runnable { requestCheck(lease) })) }
            catch (failure: Exception) {
                synchronized(lock) { if (timer === lease) timer = null }
                lease.close()
                throw failure
            }
        }
    }

    /** Deterministic BGT check hook; the native timer calls this same path. */
    internal fun requestCheck(): CompletableFuture<Void> = requestCheck(null)

    private fun requestCheck(expectedTimer: TimerLease?): CompletableFuture<Void> {
        val epoch = synchronized(lock) {
            if (closed || project.isDisposed || interests.isEmpty() || checking || expectedTimer != null && timer !== expectedTimer)
                return CompletableFuture.completedFuture(null)
            checking = true
            generation
        }
        val result = CompletableFuture<Void>()
        try {
            executor.execute {
                try { check(epoch); result.complete(null) }
                catch (failure: Exception) { result.completeExceptionally(failure) }
                finally { synchronized(lock) { checking = false } }
            }
        } catch (failure: Exception) {
            synchronized(lock) { checking = false }
            result.completeExceptionally(failure)
        }
        return result
    }

    private fun check(epoch: Long) {
        val snapshot = synchronized(lock) {
            if (!current(epoch)) return
            interests.toMap()
        }
        val now = nanoTime()
        val refresh = linkedSetOf<Path>()
        for ((path, interest) in snapshot) {
            if (!synchronized(lock) { current(epoch) }) return
            val previous = interest.attemptedAt
            val retryReady = previous == null || now - previous >= RETRY_NANOS
            val unchanged = try { probe(interest.state) }
            catch (failure: Exception) {
                if (retryReady) LOG.warn("Unable to check selected Go dependency root $path", failure)
                false
            }
            // Existing but uncovered targets retry failed installation after a
            // grace period. An unchanged missing target never re-runs discovery.
            if (retryReady && (!unchanged || interest.state.isDirectory)) refresh.add(path)
        }
        val claimed = synchronized(lock) {
            if (!current(epoch) || refresh.isEmpty()) false else {
                refresh.forEach { path -> if (interests[path] === snapshot[path]) interests.getValue(path).attemptedAt = now }
                true
            }
        }
        // Only a memory-only request is sent outside our lifecycle lock. The
        // owning scopes also reject refresh after their own close/generation.
        if (claimed && synchronized(lock) { current(epoch) }) changed()
    }

    private fun current(epoch: Long) = !closed && !project.isDisposed && generation == epoch

    override fun dispose() {
        val previous = synchronized(lock) {
            if (closed) return
            closed = true
            ++generation
            interests.clear()
            timer.also { timer = null }
        }
        previous?.close()
    }

    companion object {
        private val LOG = Logger.getInstance(GoPendingDependencyRoots::class.java)
        private const val MAX_ROOTS = 128
        private val RETRY_NANOS = TimeUnit.SECONDS.toNanos(30)
    }
}
