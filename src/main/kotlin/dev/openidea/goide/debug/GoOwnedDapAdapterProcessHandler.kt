package dev.openidea.goide.debug

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.ColoredProcessHandler
import com.intellij.util.concurrency.AppExecutorUtil
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Wait for the owner's shared DAP shutdown before touching its exact adapter.
 * The callback decides launch termination versus attach detach. For an owned
 * launch, descendants observed under that exact adapter are retained with their
 * process start identities so a pre-configuration failure cannot orphan
 * debugserver or its stopped target. Attach handlers disable that cleanup.
 */
internal open class GoOwnedDapAdapterProcessHandler(
    command: GeneralCommandLine,
    private val graceSeconds: Long = 3,
    private val terminateTrackedDescendantsOnStop: Boolean = true,
) : ColoredProcessHandler(command) {
    @Volatile var disconnect: (() -> CompletableFuture<Void>)? = null
    private val stopping = AtomicBoolean()
    private val descendantCleanupStarted = AtomicBoolean()
    private val trackedDescendants = ConcurrentHashMap<Long, TrackedProcess>()
    private val descendantMonitor: ScheduledFuture<*>?

    init {
        // The adapter itself is signalled explicitly below. Descendants are
        // terminated only if they were observed under this adapter before stop.
        setShouldDestroyProcessRecursively(false)
        descendantMonitor = if (terminateTrackedDescendantsOnStop) {
            AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay({
                captureDescendants()
            }, 0, 50, TimeUnit.MILLISECONDS)
        } else null
        process.onExit().whenComplete { _, _ -> descendantMonitor?.cancel(false) }
    }

    override fun doDestroyProcess() {
        if (!stopping.compareAndSet(false, true)) return
        // destroyProcess may be called directly by XDebugSession.stop on the EDT.
        // Only this exact owned adapter is signalled; its DAP owner handles the debuggee.
        val ready = CompletableFuture<Boolean>()
        // Arm this independently before invoking the callback: an RPC pipe write
        // can itself be blocked, before it returns a future to the caller.
        val deadline = AppExecutorUtil.getAppScheduledExecutorService().schedule({ ready.complete(false) }, graceSeconds, TimeUnit.SECONDS)
        ready.whenCompleteAsync({ acknowledged, _ ->
            deadline.cancel(false)
            if (acknowledged == true) allowNaturalExit() else terminateAdapter()
        }, AppExecutorUtil.getAppExecutorService())
        CompletableFuture.runAsync {
            val request = disconnect
            if (request == null) { ready.complete(false); return@runAsync }
            val detached = try { request() } catch (_: Exception) { ready.complete(false); return@runAsync }
            detached.whenComplete { _, failure -> ready.complete(failure == null) }
        }
    }

    private fun allowNaturalExit() {
        // dlv dap normally exits itself after acknowledging disconnect. Avoid a
        // simultaneous SIGTERM that can race its debugserver detach/cleanup.
        val waiting = AtomicBoolean(true)
        process.onExit().whenComplete { _, _ ->
            waiting.set(false)
            reapTrackedDescendants()
        }
        AppExecutorUtil.getAppScheduledExecutorService().schedule({
            if (waiting.compareAndSet(true, false)) terminateAdapter()
        }, graceSeconds, TimeUnit.SECONDS)
    }

    private fun terminateAdapter() {
        if (!process.isAlive) { reapTrackedDescendants(); return }
        // Snapshot while the adapter and its helper tree still share a live
        // ancestry. A failed macOS attach commonly leaves debugserver alive here.
        captureDescendants()
        // SIGTERM gives Delve's own shutdown handler another opportunity to detach.
        super.doDestroyProcess()
        val waiting = AtomicBoolean(true)
        process.onExit().whenComplete { _, _ ->
            waiting.set(false)
            reapTrackedDescendants()
        }
        AppExecutorUtil.getAppScheduledExecutorService().schedule({
            if (waiting.compareAndSet(true, false) && process.isAlive) {
                process.destroyForcibly()
            }
        }, graceSeconds, TimeUnit.SECONDS)
    }

    private fun captureDescendants() {
        if (!terminateTrackedDescendantsOnStop) return
        val root = process.toHandle()
        runCatching {
            root.descendants().use { descendants ->
                descendants.forEach { candidate ->
                    val start = candidate.info().startInstant().orElse(null) ?: return@forEach
                    val depth = ancestryDepth(candidate, root)
                    trackedDescendants.putIfAbsent(candidate.pid(), TrackedProcess(candidate.pid(), start, depth))
                }
            }
        }
    }

    private fun ancestryDepth(processHandle: ProcessHandle, root: ProcessHandle): Int {
        var depth = 0
        var parent = processHandle.parent().orElse(null)
        while (parent != null && depth < 64) {
            depth++
            if (parent.pid() == root.pid()) return depth
            parent = parent.parent().orElse(null)
        }
        return depth
    }

    private fun reapTrackedDescendants() {
        if (!terminateTrackedDescendantsOnStop || !descendantCleanupStarted.compareAndSet(false, true)) return
        AppExecutorUtil.getAppExecutorService().execute {
            // Deepest descendants first. A start timestamp protects against PID
            // reuse after the adapter or an earlier child exits.
            val tracked = trackedDescendants.values.sortedByDescending { it.depth }
            val alive = tracked.mapNotNull { trackedProcess ->
                ProcessHandle.of(trackedProcess.pid).orElse(null)?.takeIf { handle ->
                    handle.isAlive && handle.info().startInstant().orElse(null) == trackedProcess.start
                }
            }
            alive.forEach { processHandle ->
                runCatching { processHandle.destroy() }
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
            while (System.nanoTime() < deadline && alive.any { it.isAlive }) Thread.sleep(10)
            alive.filter { it.isAlive }.forEach { processHandle ->
                runCatching { processHandle.destroyForcibly() }
            }
        }
    }

    private data class TrackedProcess(val pid: Long, val start: Instant, val depth: Int)
}
