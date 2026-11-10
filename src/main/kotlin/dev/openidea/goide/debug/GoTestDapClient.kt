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
import org.eclipse.lsp4j.debug.StoppedEventArguments
import org.eclipse.lsp4j.debug.TerminatedEventArguments
import org.eclipse.lsp4j.debug.services.IDebugProtocolServer
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Target output, adapter stdout and debugger/repl messages have distinct ownership. */
internal class GoTestDapClient(process: DAPDebugProcess, parameters: Map<String, Any>, debug: Boolean,
    mode: DebugMode, trace: ServerTrace, parent: DAPClient?, private val tests: GoDebugTestSession) :
    GoDapClient(process, parameters, debug, mode, trace, parent) {
    private val naturalEnd = AtomicBoolean()
    private enum class ConnectionPhase { NEW, CONNECTING, CONNECTED, CLOSING, CLOSED }
    private val connectionPhase = AtomicReference(ConnectionPhase.NEW)
    private val disconnection = AtomicReference<CompletableFuture<Void>?>()
    private val disconnectSent = AtomicBoolean()
    private val endpointRetry = AtomicReference<ScheduledFuture<*>?>()
    init { tests.disconnect = ::disconnectTest }

    override fun connectToServer(indicator: ProgressIndicator): CompletableFuture<Void> {
        if (!connectionPhase.compareAndSet(ConnectionPhase.NEW, ConnectionPhase.CONNECTING))
            return CompletableFuture<Void>().apply { cancel(false) }
        return super.connectToServer(indicator).also { future ->
            future.whenComplete { _, failure ->
                if (failure == null) connectionPhase.compareAndSet(ConnectionPhase.CONNECTING, ConnectionPhase.CONNECTED)
                else if (!future.isCancelled) tests.end(1)
            }
        }
    }

    override fun getDebugProtocolServer(): IDebugProtocolServer? {
        val server = super.getDebugProtocolServer()
        // The public superclass publishes the endpoint before initialize. If
        // Stop won while publication was pending, close the ordinary protocol
        // queue before returning this real proxy to that initialization chain.
        when (connectionPhase.get()) {
            ConnectionPhase.CLOSING, ConnectionPhase.CLOSED -> {
                if (server != null) disconnection.get()?.let { sendDisconnect(server, it) }
                // Even a completed deadline or a claimed-but-not-yet-dispatched
                // disconnect must not hand an ordinary proxy to late initialize.
                throw CancellationException("Go test debugger connection was stopped")
            }
            else -> return server
        }
    }

    override fun stopped(args: StoppedEventArguments) {
        if (connectionStopped()) return
        try {
            super.stopped(args)
        } catch (cancelled: CancellationException) {
            // Stop can win after the entry check but before the superclass
            // looks up its endpoint. Abandon only this terminal notification;
            // ordinary endpoint lookup must still cancel late initialization.
            if (!connectionStopped()) throw cancelled
        }
    }

    private fun connectionStopped() = when (connectionPhase.get()) {
        ConnectionPhase.CLOSING, ConnectionPhase.CLOSED -> true
        else -> false
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
        disconnectTest().whenComplete { _, _ ->
            // Freeze the natural result before SDK Stop can request user
            // cancellation, regardless of completion-executor ordering.
            tests.end()
            super.terminated(args)
        }
    }

    internal fun disconnectTest(): CompletableFuture<Void> {
        disconnection.get()?.let { return it }
        val done = CompletableFuture<Void>()
        if (!disconnection.compareAndSet(null, done)) return disconnection.get()!!
        invalidateSuspension()
        val deadline = AppExecutorUtil.getAppScheduledExecutorService().schedule({
            done.completeExceptionally(java.util.concurrent.TimeoutException("Go test debugger disconnect timed out"))
        }, 3, TimeUnit.SECONDS)
        done.whenCompleteAsync({ _, _ ->
            deadline.cancel(false)
            endpointRetry.getAndSet(null)?.cancel(false)
            tests.end()
            dispose()
        }, AppExecutorUtil.getAppExecutorService())
        val previous = connectionPhase.getAndUpdate {
            if (it == ConnectionPhase.CLOSED) it else ConnectionPhase.CLOSING
        }
        if (previous == ConnectionPhase.NEW || previous == ConnectionPhase.CLOSED) {
            // No connection was claimed (or it was already disposed). This is
            // explicit cancellation, never a fabricated disconnect ACK.
            done.completeExceptionally(CancellationException("Go test debugger stopped without a connected protocol endpoint"))
        } else {
            // Proxy dispatch closes its queue synchronously, but actual wire
            // work remains in GoDapProtocol's background dispatcher.
            awaitEndpoint(done)
        }
        return done
    }

    private fun awaitEndpoint(done: CompletableFuture<Void>) {
        if (done.isDone) return
        try {
            val server = super.getDebugProtocolServer()
            if (server != null) { sendDisconnect(server, done); return }
            val retry = AppExecutorUtil.getAppScheduledExecutorService().schedule({ awaitEndpoint(done) }, 10, TimeUnit.MILLISECONDS)
            endpointRetry.getAndSet(retry)?.cancel(false)
            if (done.isDone) retry.cancel(false)
        } catch (failure: Exception) { done.completeExceptionally(failure) }
    }

    private fun sendDisconnect(server: IDebugProtocolServer, done: CompletableFuture<Void>) {
        if (done.isDone || !disconnectSent.compareAndSet(false, true)) return
        try {
            tests.disconnecting()
            // The exact owned test adapter, never an attached/remote debuggee.
            server.disconnect(DisconnectArguments().apply { terminateDebuggee = true }).whenComplete { _, error ->
                if (error == null) done.complete(null) else done.completeExceptionally(error)
            }
        } catch (failure: Exception) { done.completeExceptionally(failure) }
    }

    override fun terminate() {
        if (!naturalEnd.get()) tests.requestCancellation()
        disconnectTest()
    }
    override fun dispose() {
        connectionPhase.set(ConnectionPhase.CLOSED)
        disconnection.get()?.completeExceptionally(CancellationException("Go test debugger disposed before disconnect acknowledgement"))
        tests.end()
        super.dispose()
    }
}
