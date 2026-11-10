package dev.openidea.goide.debug

import com.redhat.devtools.lsp4ij.dap.DAPDebugProcess
import com.redhat.devtools.lsp4ij.dap.DebugMode
import com.redhat.devtools.lsp4ij.dap.client.DAPClient
import com.redhat.devtools.lsp4ij.settings.ServerTrace
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import org.eclipse.lsp4j.debug.ContinueArguments
import org.eclipse.lsp4j.debug.ContinueResponse
import org.eclipse.lsp4j.debug.EvaluateArguments
import org.eclipse.lsp4j.debug.EvaluateResponse
import org.eclipse.lsp4j.debug.NextArguments
import org.eclipse.lsp4j.debug.SetVariableArguments
import org.eclipse.lsp4j.debug.SetVariableResponse
import org.eclipse.lsp4j.debug.StepInArguments
import org.eclipse.lsp4j.debug.StepOutArguments
import org.eclipse.lsp4j.debug.SteppingGranularity
import org.eclipse.lsp4j.debug.ValueFormat
import org.eclipse.lsp4j.debug.services.IDebugProtocolServer
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

open class GoDapClient(debugProcess: DAPDebugProcess, parameters: Map<String, Any>, isDebug: Boolean,
    mode: DebugMode, trace: ServerTrace, parent: DAPClient?) : DAPClient(debugProcess, parameters, isDebug, mode, trace, parent) {
    private val ownsPauseCapability = parent == null
    private val debugPauseSupported = isDebug
    init {
        // Do not expose Pause while initialize/launch/configuration is pending.
        // Child connections share the parent's session capability declaration.
        if (ownsPauseCapability) debugProcess.session.setPauseActionSupported(false)
    }

    private var actual: IDebugProtocolServer? = null
    private var protocol: GoDapProtocol? = null
    private val goDebugProcess = debugProcess
    @Volatile private var disposed = false
    private var connecting: CompletableFuture<Void>? = null

    override fun connectToServer(indicator: ProgressIndicator): CompletableFuture<Void> {
        val ready = CompletableFuture<Void>()
        synchronized(this) {
            if (disposed) return ready.apply { cancel(false) }
            connecting = ready
        }
        ready.whenComplete { _, _ ->
            synchronized(this) { if (connecting === ready) connecting = null }
            if (ready.isCancelled && ownsPauseCapability) goDebugProcess.session.setPauseActionSupported(false)
        }
        if (ownsPauseCapability) goDebugProcess.session.setPauseActionSupported(false)
        if (ready.isDone) return ready
        val connection = try { super.connectToServer(indicator) }
        catch (failure: Throwable) {
            if (failure is CancellationException || failure is ProcessCanceledException) ready.cancel(false)
            else ready.completeExceptionally(failure)
            return ready
        }
        // Return an explicit cancellation-preserving bridge, not a dependent
        // whenComplete future: Go Test distinguishes user Stop via isCancelled.
        ready.whenComplete { _, _ -> if (ready.isCancelled) connection.cancel(true) }
        connection.whenComplete { _, failure ->
            var cause = failure
            while (cause is CompletionException && cause.cause != null) cause = cause.cause!!
            when {
                connection.isCancelled || cause is CancellationException || cause is ProcessCanceledException || ready.isCancelled || !isClientActive() -> ready.cancel(false)
                failure != null -> ready.completeExceptionally(failure)
                else -> try {
                    if (ownsPauseCapability) goDebugProcess.session.setPauseActionSupported(debugPauseSupported)
                    // Disposal/cancellation may win while a settings listener runs.
                    if (ready.isDone || !isClientActive()) {
                        if (ownsPauseCapability) goDebugProcess.session.setPauseActionSupported(false)
                        ready.cancel(false)
                    } else ready.complete(null)
                } catch (error: Throwable) { ready.completeExceptionally(error) }
            }
        }
        return ready
    }

    private fun isClientActive() = !disposed && !goDebugProcess.project.isDisposed && !goDebugProcess.session.isStopped

    override fun getDebugProtocolServer(): IDebugProtocolServer? {
        var previous: GoDapProtocol? = null
        val server = synchronized(this) {
            if (disposed) return null
            val remote = super.getDebugProtocolServer() ?: return null
            if (actual !== remote) {
                previous = protocol
                actual = remote
                protocol = GoDapProtocol(remote)
            }
            protocol!!.server
        }
        // Completing cancelled protocol requests can run callbacks immediately.
        // Never hold the client monitor while closing the protocol queue.
        previous?.close()
        return server
    }

    // LSP4IJ 0.21's public convenience methods use its private raw server field.
    // Route them explicitly through our getter so configuration handoff, resume
    // ordering, and private error references are respected by the native UI too.
    override fun continue_(threadId: Int): CompletableFuture<ContinueResponse> = control("Continue") {
        server().continue_(ContinueArguments().apply { this.threadId = threadId })
    }

    override fun next(threadId: Int, granularity: SteppingGranularity?) {
        control("Step Over") { server().next(NextArguments().apply { this.threadId = threadId; this.granularity = granularity }) }
    }

    override fun stepOut(threadId: Int, granularity: SteppingGranularity?) {
        control("Step Out") { server().stepOut(StepOutArguments().apply { this.threadId = threadId; this.granularity = granularity }) }
    }

    override fun stepIn(threadId: Int, granularity: SteppingGranularity?) = stepIn(threadId, null, granularity)

    override fun stepIn(threadId: Int, targetId: Int?, granularity: SteppingGranularity?) {
        control("Step Into") { server().stepIn(StepInArguments().apply {
            this.threadId = threadId; this.targetId = targetId; this.granularity = granularity
        }) }
    }

    override fun evaluate(expression: String, frameId: Int?, context: String): CompletableFuture<EvaluateResponse> =
        request { server().evaluate(EvaluateArguments().apply { this.expression = expression; this.frameId = frameId; this.context = context }) }

    override fun setVariable(name: String, variablesReference: Int, value: String): CompletableFuture<SetVariableResponse> =
        request { server().setVariable(SetVariableArguments().apply {
            this.name = name; this.variablesReference = variablesReference; this.value = value; format = ValueFormat()
        }) }

    private fun server(): IDebugProtocolServer = getDebugProtocolServer() ?: throw CancellationException("Debug session closed")
    private fun <T> request(start: () -> CompletableFuture<T>): CompletableFuture<T> =
        try { start() } catch (failure: Exception) { CompletableFuture.failedFuture(failure) }

    private fun <T> control(name: String, start: () -> CompletableFuture<T>): CompletableFuture<T> = request(start).also { future ->
        future.whenComplete { _, failure ->
            if (failure != null && !disposed) {
                var cause = failure
                while (cause is CompletionException && cause.cause != null) cause = cause.cause!!
                if (cause !is CancellationException)
                    goDebugProcess.print("$name failed: ${cause.message ?: cause.javaClass.simpleName}", ConsoleViewContentType.ERROR_OUTPUT)
            }
        }
    }

    override fun dispose() {
        val closing = synchronized(this) {
            if (disposed) return
            disposed = true
            (protocol to connecting).also { protocol = null; actual = null; connecting = null }
        }
        if (ownsPauseCapability) goDebugProcess.session.setPauseActionSupported(false)
        closing.second?.cancel(true)
        closing.first?.close()
        super.dispose()
    }
}
