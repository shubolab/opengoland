package dev.openidea.goide.debug

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.util.concurrency.AppExecutorUtil
import com.redhat.devtools.lsp4ij.dap.DAPDebugProcess
import com.redhat.devtools.lsp4ij.dap.DebugMode
import com.redhat.devtools.lsp4ij.dap.client.DAPClient
import com.redhat.devtools.lsp4ij.settings.ServerTrace
import org.eclipse.lsp4j.debug.DisconnectArguments
import org.eclipse.lsp4j.debug.ExitedEventArguments
import org.eclipse.lsp4j.debug.OutputEventArguments
import org.eclipse.lsp4j.debug.ProcessEventArguments
import org.eclipse.lsp4j.debug.TerminatedEventArguments
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Target output, adapter stdout and debugger/repl messages have distinct ownership. */
internal class GoTestDapClient(process: DAPDebugProcess, parameters: Map<String, Any>, debug: Boolean,
    mode: DebugMode, trace: ServerTrace, parent: DAPClient?, private val tests: GoDebugTestSession) :
    GoDapClient(process, parameters, debug, mode, trace, parent) {
    private val naturalEnd = AtomicBoolean()
    private val disconnection = AtomicReference<CompletableFuture<Void>?>()
    init { tests.disconnect = ::disconnectTest }

    override fun connectToServer(indicator: ProgressIndicator): CompletableFuture<Void> =
        super.connectToServer(indicator).also { future ->
            future.whenComplete { _, failure -> if (failure != null && !future.isCancelled) tests.end(1) }
        }

    override fun output(args: OutputEventArguments) {
        if (args.category in setOf("stdout", "stderr") && tests.targetOutput(args.output.orEmpty())) return
        if (args.category == "console") tests.delveConsoleStatus(args.output.orEmpty())
        super.output(args)
    }
    override fun process(args: ProcessEventArguments) {
        args.systemProcessId?.let { tests.processStarted(it.toLong()) }
        super.process(args)
    }
    override fun exited(args: ExitedEventArguments) { tests.exitedEvent(args.exitCode) }
    override fun terminated(args: TerminatedEventArguments) {
        if (!naturalEnd.compareAndSet(false, true)) return
        tests.naturalTermination()
        // Delve 1.24.2's PID/status diagnostic arrives during disconnect before
        // its ACK. Keep the transport and output converter alive until then.
        disconnectTest().whenComplete { _, _ -> super.terminated(args) }
    }

    internal fun disconnectTest(): CompletableFuture<Void> {
        disconnection.get()?.let { return it }
        val done = CompletableFuture<Void>()
        if (!disconnection.compareAndSet(null, done)) return disconnection.get()!!
        val deadline = AppExecutorUtil.getAppScheduledExecutorService().schedule({
            done.completeExceptionally(java.util.concurrent.TimeoutException("Go test debugger disconnect timed out"))
        }, 3, TimeUnit.SECONDS)
        done.whenComplete { _, _ -> deadline.cancel(false); tests.end(); dispose() }
        AppExecutorUtil.getAppExecutorService().execute {
            try {
                val server = debugProtocolServer
                if (server == null) done.complete(null)
                else {
                    tests.disconnecting()
                    // This client only owns a test binary launched by this runner.
                    server.disconnect(DisconnectArguments().apply { terminateDebuggee = true }).whenComplete { _, error ->
                        if (error == null) done.complete(null) else done.completeExceptionally(error)
                    }
                }
            } catch (failure: Exception) { done.completeExceptionally(failure) }
        }
        return done
    }

    override fun terminate() {
        if (!naturalEnd.get()) tests.requestCancellation()
        disconnectTest()
    }
    override fun dispose() { tests.end(); super.dispose() }
}
