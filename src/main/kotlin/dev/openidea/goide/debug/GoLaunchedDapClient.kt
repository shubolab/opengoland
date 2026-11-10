package dev.openidea.goide.debug

import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.util.concurrency.AppExecutorUtil
import com.redhat.devtools.lsp4ij.dap.DAPDebugProcess
import com.redhat.devtools.lsp4ij.dap.DebugMode
import com.redhat.devtools.lsp4ij.dap.client.DAPClient
import com.redhat.devtools.lsp4ij.settings.ServerTrace
import org.eclipse.lsp4j.debug.DisconnectArguments
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

/** One acknowledged shutdown for a Go binary launched by this owned adapter. */
internal class GoLaunchedDapClient(private val process: DAPDebugProcess, parameters: Map<String, Any>, debug: Boolean,
    mode: DebugMode, trace: ServerTrace, parent: DAPClient?) :
    GoDapClient(process, parameters, debug, mode, trace, parent) {
    private val disconnection = AtomicReference<CompletableFuture<Void>?>()

    internal fun disconnectLaunched(): CompletableFuture<Void> {
        disconnection.get()?.let { return it }
        val done = CompletableFuture<Void>()
        if (!disconnection.compareAndSet(null, done)) return disconnection.get()!!
        invalidateSuspension()
        val deadline = AppExecutorUtil.getAppScheduledExecutorService().schedule({
            done.completeExceptionally(TimeoutException("Go debugger disconnect timed out"))
        }, 3, TimeUnit.SECONDS)
        done.whenCompleteAsync({ _, failure ->
            deadline.cancel(false)
            if (failure != null && failure !is CancellationException && !process.project.isDisposed)
                process.print("Go debugger shutdown was not acknowledged: ${failure.message ?: failure.javaClass.simpleName}", ConsoleViewContentType.ERROR_OUTPUT)
            dispose()
        }, AppExecutorUtil.getAppExecutorService())
        // Stop can occur while initialize is still being assembled. Wait only
        // within the independent bound for the real endpoint; never declare a
        // missing endpoint to be a successful disconnect or close it early.
        fun dispatch() {
            if (done.isDone) return
            try {
                val server = debugProtocolServer
                if (server == null) {
                    AppExecutorUtil.getAppScheduledExecutorService().schedule({ dispatch() }, 10, TimeUnit.MILLISECONDS)
                } else {
                    // Delve does not implement terminate. Its disconnect handles
                    // launched-target kill/stub cleanup before sending the ACK.
                    server.disconnect(DisconnectArguments().apply { terminateDebuggee = true }).whenComplete { _, failure ->
                        if (failure == null) done.complete(null) else done.completeExceptionally(failure)
                    }
                }
            } catch (failure: Exception) { done.completeExceptionally(failure) }
        }
        AppExecutorUtil.getAppExecutorService().execute { dispatch() }
        return done
    }

    override fun terminate() { disconnectLaunched() }
}
