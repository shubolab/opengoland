package dev.openidea.goide.debug

import org.eclipse.lsp4j.debug.EvaluateArguments
import org.eclipse.lsp4j.debug.EvaluateResponse
import org.eclipse.lsp4j.debug.ContinueArguments
import org.eclipse.lsp4j.debug.ContinueResponse
import org.eclipse.lsp4j.debug.NextArguments
import org.eclipse.lsp4j.debug.StepInArguments
import org.eclipse.lsp4j.debug.StepOutArguments
import org.eclipse.lsp4j.debug.StepBackArguments
import org.eclipse.lsp4j.debug.ReverseContinueArguments
import org.eclipse.lsp4j.debug.RestartArguments
import org.eclipse.lsp4j.debug.RestartFrameArguments
import org.eclipse.lsp4j.debug.GotoArguments
import org.eclipse.lsp4j.debug.Scope
import org.eclipse.lsp4j.debug.ScopesArguments
import org.eclipse.lsp4j.debug.ScopesResponse
import org.eclipse.lsp4j.debug.SetVariableArguments
import org.eclipse.lsp4j.debug.SetVariableResponse
import org.eclipse.lsp4j.debug.Variable
import org.eclipse.lsp4j.debug.VariablePresentationHint
import org.eclipse.lsp4j.debug.VariablesArguments
import org.eclipse.lsp4j.debug.VariablesResponse
import org.eclipse.lsp4j.debug.services.IDebugProtocolServer
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

/**
 * Delve's optional globals lookup can fail an otherwise valid scopes request.
 * Keep real local variables available, restore the adapter setting, and display
 * unavailable scopes as explicit error items instead of an endless native spinner.
 * Negative references are private frontend error handles, never sent to Delve.
 */
internal class GoDapProtocol(private val remote: IDebugProtocolServer, private val timeoutSeconds: Long = 12) : AutoCloseable {
    // Kotlin delegation does not forward Java default RPC methods. A public-interface
    // proxy forwards every non-intercepted method to the real LSP4J endpoint instead.
    val server: IDebugProtocolServer = Proxy.newProxyInstance(IDebugProtocolServer::class.java.classLoader, arrayOf(IDebugProtocolServer::class.java)) { proxy, method, supplied ->
        val args = supplied.orEmpty()
        when (method.name) {
            "scopes" -> scopes(args[0] as ScopesArguments)
            "variables" -> variables(args[0] as VariablesArguments)
            "setVariable" -> setVariable(args[0] as SetVariableArguments)
            "evaluate" -> evaluate(args[0] as EvaluateArguments)
            "continue_" -> continue_(args[0] as ContinueArguments)
            "next" -> next(args[0] as NextArguments)
            "stepIn" -> stepIn(args[0] as StepInArguments)
            "stepOut" -> stepOut(args[0] as StepOutArguments)
            "stepBack" -> stepBack(args[0] as StepBackArguments)
            "reverseContinue" -> reverseContinue(args[0] as ReverseContinueArguments)
            "goto_" -> goto_(args[0] as GotoArguments)
            "restart" -> restart(args[0] as RestartArguments)
            "restartFrame" -> restartFrame(args[0] as RestartFrameArguments)
            "disconnect", "terminate" -> {
                // Stop invalidates pending execution immediately, before its server ack.
                close()
                try { method.invoke(remote, *args) } catch (failure: InvocationTargetException) { throw failure.targetException }
            }
            "toString" -> "open Goland Delve protocol"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args[0]
            else -> try { method.invoke(remote, *args) } catch (failure: InvocationTargetException) { throw failure.targetException }
        }
    } as IDebugProtocolServer
    private var closed = false
    private var tail = CompletableFuture.completedFuture(Unit)
    private val pending = mutableSetOf<CompletableFuture<*>>()
    private val errors = linkedMapOf<Int, String>()
    private var nextError = -1
    @Volatile private var restoreGlobals = false

    private fun scopes(args: ScopesArguments): CompletableFuture<ScopesResponse> {
        val queued = serial { _ ->
            ensureGlobalsRestored().thenCompose { request { remote.scopes(args) } }.handle { response, failure ->
            when {
                failure == null -> CompletableFuture.completedFuture(normalize(response))
                isGlobalsFailure(failure) && !isClosed() -> recoverLocals(args, failure)
                else -> CompletableFuture.completedFuture(errorScopes(message(failure)))
            }
            }.thenCompose { it }
        }
        val result = CompletableFuture<ScopesResponse>()
        queued.whenComplete { response, failure ->
            if (isClosed() || queued.isCancelled) result.cancel(true)
            else result.complete(if (failure == null) response else errorScopes(message(failure)))
        }
        result.whenComplete { _, _ -> if (result.isCancelled) queued.cancel(true) }
        return result
    }

    private fun recoverLocals(args: ScopesArguments, globalsFailure: Throwable): CompletableFuture<ScopesResponse> {
        val locals = configureGlobals(args.frameId, false).thenCompose {
            if (isClosed()) CompletableFuture.failedFuture(CancellationException("Debug session closed"))
            else request { remote.scopes(args) }
        }
        // Restore even when disable itself timed out: the server may already have applied it.
        return locals.handle { response, localFailure -> response to localFailure }.thenCompose { (response, localFailure) ->
            configureGlobals(args.frameId, true).handle { _, restoreFailure ->
                val result = if (localFailure == null) normalize(response) else errorScopes(message(localFailure))
                val detail = buildString {
                    append(message(globalsFailure))
                    if (restoreFailure != null) append("; restoring package-variable configuration failed: ").append(message(restoreFailure))
                }
                result.scopes = result.scopes + errorScope("Package variables unavailable", detail)
                result
            }
        }
    }

    private fun variables(args: VariablesArguments): CompletableFuture<VariablesResponse> {
        if (isClosed()) return CompletableFuture.failedFuture(CancellationException("Debug session closed"))
        if (args.variablesReference < 0) {
            val error = synchronized(this) { errors[args.variablesReference] } ?: "This error belongs to an expired stack frame"
            return CompletableFuture.completedFuture(VariablesResponse().apply { variables = arrayOf(errorValue(error)) })
        }
        return request { remote.variables(args) }.handle { response, failure ->
            if (failure != null) VariablesResponse().apply { variables = arrayOf(errorValue(message(failure))) }
            else (response ?: VariablesResponse()).apply { if (variables == null) variables = emptyArray() }
        }
    }

    private fun setVariable(args: SetVariableArguments): CompletableFuture<SetVariableResponse> =
        if (args.variablesReference < 0) CompletableFuture.failedFuture(IllegalArgumentException("An unavailable-variable error cannot be edited"))
        // Delve implements string assignments with function-call injection, which
        // can resume the target. Restore globals before any variable assignment.
        else execution { remote.setVariable(args) }

    private fun evaluate(args: EvaluateArguments): CompletableFuture<EvaluateResponse> =
        if (Regex("^\\s*dlv\\s+config(?:\\s|$)").containsMatchIn(args.expression.orEmpty())) serial { _ ->
            request { remote.evaluate(args) }.thenApply { response ->
                if (Regex("^\\s*dlv\\s+config\\s+showGlobalVariables\\s+(true|false)\\s*$").matches(args.expression)) restoreGlobals = false
                response
            }
        }
        else if (Regex("^\\s*call\\s+").containsMatchIn(args.expression.orEmpty())) execution { remote.evaluate(args) }
        else remote.evaluate(args)

    // Delve rejects config evaluation while the target runs. Restore before any request
    // that may resume it; Stop/disconnect deliberately remain immediate delegates.
    private fun continue_(args: ContinueArguments): CompletableFuture<ContinueResponse> = execution { remote.continue_(args) }
    private fun next(args: NextArguments): CompletableFuture<Void> = execution { remote.next(args) }
    private fun stepIn(args: StepInArguments): CompletableFuture<Void> = execution { remote.stepIn(args) }
    private fun stepOut(args: StepOutArguments): CompletableFuture<Void> = execution { remote.stepOut(args) }
    private fun stepBack(args: StepBackArguments): CompletableFuture<Void> = execution { remote.stepBack(args) }
    private fun reverseContinue(args: ReverseContinueArguments): CompletableFuture<Void> = execution { remote.reverseContinue(args) }
    private fun goto_(args: GotoArguments): CompletableFuture<Void> = execution { remote.goto_(args) }
    private fun restart(args: RestartArguments): CompletableFuture<Void> = execution { remote.restart(args) }
    private fun restartFrame(args: RestartFrameArguments): CompletableFuture<Void> = execution { remote.restartFrame(args) }

    private fun <T> execution(start: () -> CompletableFuture<T>): CompletableFuture<T> =
        serial { life -> ensureGlobalsRestored().thenCompose { life.start { request(start) } } }
    private fun ensureGlobalsRestored(): CompletableFuture<Unit> =
        if (restoreGlobals) configureGlobals(null, true).thenApply { Unit } else CompletableFuture.completedFuture(Unit)

    private fun configureGlobals(frameId: Int?, enabled: Boolean): CompletableFuture<EvaluateResponse> {
        if (!enabled) restoreGlobals = true
        return request {
            remote.evaluate(EvaluateArguments().apply {
                expression = "dlv config showGlobalVariables $enabled"
                context = "repl"
                this.frameId = frameId
            })
        }.thenApply { response -> if (enabled) restoreGlobals = false; response }
    }

    private fun normalize(response: ScopesResponse?): ScopesResponse = (response ?: ScopesResponse()).apply {
        if (scopes == null) scopes = emptyArray()
    }

    private fun errorScopes(detail: String) = ScopesResponse().apply { scopes = arrayOf(errorScope("Variables unavailable", detail)) }
    private fun errorScope(name: String, detail: String): Scope = synchronized(this) {
        val reference = nextError--
        errors[reference] = detail
        while (errors.size > 128) errors.remove(errors.keys.first())
        Scope().apply { this.name = name; variablesReference = reference; isExpensive = false }
    }
    private fun errorValue(detail: String) = Variable().apply {
        name = "Unavailable"; value = detail; type = "error"; variablesReference = 0
        presentationHint = VariablePresentationHint().apply { attributes = arrayOf("readOnly") }
    }

    private fun <T> request(start: () -> CompletableFuture<T>): CompletableFuture<T> {
        val rpc = try { start() } catch (failure: Throwable) { return CompletableFuture.failedFuture(failure) }
        val result = CompletableFuture<T>()
        val deadline = AtomicReference<Pair<CompletableFuture<T>, CompletableFuture<T>>?>(rpc to result)
        rpc.whenComplete { value, failure -> if (failure == null) result.complete(value) else result.completeExceptionally(failure) }
        CompletableFuture.delayedExecutor(timeoutSeconds, TimeUnit.SECONDS).execute {
            deadline.getAndSet(null)?.let { (request, response) ->
                if (response.completeExceptionally(TimeoutException("Debug adapter request timed out"))) request.cancel(true)
            }
        }
        result.whenComplete { _, _ -> deadline.set(null); if (result.isCancelled) rpc.cancel(true) }
        return result
    }

    private inner class SerialFuture<T> : CompletableFuture<T>() {
        override fun cancel(mayInterruptIfRunning: Boolean): Boolean = synchronized(this@GoDapProtocol) { super.cancel(mayInterruptIfRunning) }
    }
    private inner class RequestLife(private val result: CompletableFuture<*>) {
        fun <T> start(action: () -> CompletableFuture<T>): CompletableFuture<T> = synchronized(this@GoDapProtocol) {
            if (closed || result.isDone) CompletableFuture.failedFuture(CancellationException("Debug request is no longer active"))
            else try { action() } catch (failure: Throwable) { CompletableFuture.failedFuture(failure) }
        }
    }

    /** The factory starts asynchronous work; no blocking wait occurs under this lock. */
    private fun <T> serial(start: (RequestLife) -> CompletableFuture<T>): CompletableFuture<T> = synchronized(this) {
        if (closed) return CompletableFuture.failedFuture(CancellationException("Debug session closed"))
        val result = SerialFuture<T>()
        val life = RequestLife(result)
        val queueDeadline = AtomicReference<CompletableFuture<T>?>(result)
        CompletableFuture.delayedExecutor(timeoutSeconds, TimeUnit.SECONDS).execute {
            synchronized(this) {
                queueDeadline.getAndSet(null)?.completeExceptionally(TimeoutException("Debug request timed out while waiting for variable/configuration work"))
            }
        }
        pending += result
        result.whenComplete { _, _ -> queueDeadline.set(null); synchronized(this) { pending -= result } }
        tail = tail.handle { _, _ -> Unit }.thenCompose {
            val child = synchronized(this) {
                if (closed || result.isDone) null
                else try { start(life) } catch (failure: Throwable) { CompletableFuture.failedFuture(failure) }
            }
            if (child == null) CompletableFuture.completedFuture(Unit)
            else child.handle { value, failure ->
                if (failure == null) result.complete(value) else result.completeExceptionally(failure)
                Unit
            }
        }
        result
    }

    private fun isClosed() = synchronized(this) { closed }
    override fun close() {
        val values = synchronized(this) { closed = true; errors.clear(); pending.toList().also { pending.clear() } }
        values.forEach { it.cancel(true) }
        // In-progress recovery retains its bounded restore chain; no worker thread is owned.
    }

    companion object {
        private fun cause(failure: Throwable): Throwable {
            var value = failure
            while ((value is java.util.concurrent.CompletionException || value is java.util.concurrent.ExecutionException) && value.cause != null) value = value.cause!!
            return value
        }
        private fun isGlobalsFailure(failure: Throwable): Boolean =
            (cause(failure) as? ResponseErrorException)?.responseError?.message == "Unable to list globals"
        private fun message(failure: Throwable?): String {
            val value = failure?.let(::cause) ?: return "Debug adapter returned no variable data"
            if (value is ResponseErrorException) {
                val error = value.responseError
                return listOfNotNull(error.message, error.data?.toString()).joinToString(": ")
            }
            return value.message ?: value.javaClass.simpleName
        }
    }
}
