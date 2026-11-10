package dev.openidea.goide.debug

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.ColoredProcessHandler
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Wait for the owner's shared DAP shutdown before touching its exact adapter.
 * The callback decides launch termination versus attach detach; this handler
 * never signals a debuggee or recursively walks/kills descendants.
 */
internal open class GoOwnedDapAdapterProcessHandler(command: GeneralCommandLine, private val graceSeconds: Long = 3) : ColoredProcessHandler(command) {
    @Volatile var disconnect: (() -> CompletableFuture<Void>)? = null
    private val stopping = AtomicBoolean()

    init { setShouldDestroyProcessRecursively(false) }

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
        process.onExit().whenComplete { _, _ -> waiting.set(false) }
        AppExecutorUtil.getAppScheduledExecutorService().schedule({
            if (waiting.compareAndSet(true, false)) terminateAdapter()
        }, graceSeconds, TimeUnit.SECONDS)
    }

    private fun terminateAdapter() {
        if (!process.isAlive) return
        // SIGTERM gives Delve's own shutdown handler another opportunity to detach.
        // No process tree walk, PID lookup, or signal to the debuggee is permitted.
        super.doDestroyProcess()
        val waiting = AtomicBoolean(true)
        process.onExit().whenComplete { _, _ -> waiting.set(false) }
        AppExecutorUtil.getAppScheduledExecutorService().schedule({
            if (waiting.compareAndSet(true, false) && process.isAlive) process.destroyForcibly()
        }, graceSeconds, TimeUnit.SECONDS)
    }
}
