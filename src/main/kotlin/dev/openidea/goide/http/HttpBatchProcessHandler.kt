package dev.openidea.goide.http

import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.Key
import java.io.OutputStream
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Callbacks run on the batch worker. Implementations must be quick and must not touch Swing directly. */
internal interface HttpBatchObserver {
    fun started(entry: HttpRequestEntry, method: String, uri: String) {}
    fun responded(entry: HttpRequestEntry, response: HttpRequestResponse) {}
    fun failed(entry: HttpRequestEntry, message: String) {}
    fun cancelled(entry: HttpRequestEntry) {}
}

/** One worker owns both the active exchange and the as-yet-unsent requests. */
internal class HttpBatchProcessHandler(
    plan: HttpRequestPlan,
    entries: List<HttpRequestEntry>,
    private val observer: HttpBatchObserver = object : HttpBatchObserver {},
    private val includeResponseDetailInConsole: Boolean = true,
    private val finishedCallback: (HttpBatchProcessHandler) -> Unit = {},
) : ProcessHandler() {
    private val snapshot = AtomicReference<HttpRequestPlan?>(plan)
    private val selected = entries.toList()
    private val started = AtomicBoolean()
    private val finished = AtomicBoolean()
    private val ownership = Any()
    private val consoleOutput = Any()
    private var consoleCharacters = 0
    private var consoleOmitted = false
    @Volatile private var stopping = false
    private var activeCall: HttpRequestCall? = null
    private var observerFailed = false // Only the worker invokes observers.

    override fun startNotify() {
        if (!started.compareAndSet(false, true)) return
        super.startNotify()
        val worker = Runnable { executeBatch() }
        try {
            ApplicationManager.getApplication().executeOnPooledThread(worker)
        } catch (_: RejectedExecutionException) {
            // Application shutdown may reject new work. The fallback only drains
            // cancelled entries; it must never begin a network exchange.
            requestStop()
            ForkJoinPool.commonPool().execute(worker)
        }
    }

    override fun destroyProcess() {
        requestStop()
        // ProcessHandler otherwise defers destruction until startNotify. A batch
        // cancelled before its console starts still needs one worker to drain it.
        super.destroyProcess()
        startNotify()
    }

    override fun detachProcess() = destroyProcess()
    override fun destroyProcessImpl() = requestStop()
    override fun detachProcessImpl() = requestStop()
    override fun detachIsDefault() = false
    override fun getProcessInput(): OutputStream? = null

    /** The batch stays bounded even when the user disables the platform's cyclic console buffer. */
    override fun notifyTextAvailable(text: String, outputType: Key<*>) {
        synchronized(consoleOutput) {
            if (consoleOmitted) return
            // Reserve the final notice inside the total limit, rather than add
            // an unaccounted suffix after reaching it.
            val remaining = CONSOLE_TEXT_LIMIT - CONSOLE_OMISSION_NOTICE.length - consoleCharacters
            if (text.length <= remaining) {
                consoleCharacters += text.length
                super.notifyTextAvailable(text, outputType)
                return
            }
            consoleOmitted = true
            var end = remaining.coerceAtLeast(0)
            if (end > 0 && end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
            if (end > 0) {
                consoleCharacters += end
                super.notifyTextAvailable(text.substring(0, end), outputType)
            }
            consoleCharacters += CONSOLE_OMISSION_NOTICE.length
            super.notifyTextAvailable(CONSOLE_OMISSION_NOTICE, ProcessOutputTypes.SYSTEM)
        }
    }

    private fun requestStop() {
        val exchange = synchronized(ownership) {
            stopping = true
            activeCall
        }
        exchange?.close()
    }

    private fun executeBatch() {
        var next = 0
        var failed = false
        var exitCode = 1
        try {
            val plan = snapshot.getAndSet(null) ?: return
            while (next < selected.size && !stopping) {
                val entry = selected[next++]
                if (!executeEntry(plan, entry)) failed = true
            }
            exitCode = if (stopping) 130 else if (failed || observerFailed) 1 else 0
        } finally {
            try {
                val exchange = synchronized(ownership) { activeCall.also { activeCall = null } }
                exchange?.close()
                if (stopping) {
                    while (next < selected.size) {
                        val entry = selected[next++]
                        observe { observer.cancelled(entry) }
                    }
                    notifyTextAvailable("HTTP batch cancelled.\n", ProcessOutputTypes.SYSTEM)
                }
            } finally {
                snapshot.set(null)
                // No exchange can be created after this notification. In particular,
                // Stop never reports termination while a constructor is still sending.
                finish(if (stopping) 130 else exitCode)
            }
        }
    }

    private fun executeEntry(plan: HttpRequestPlan, entry: HttpRequestEntry): Boolean {
        var exchange: HttpRequestCall? = null
        try {
            val spec = plan.parse(entry)
            if (stopping) {
                observe { observer.cancelled(entry) }
                return false
            }
            observe { observer.started(entry, spec.method, spec.uri.toString()) }
            if (stopping) {
                observe { observer.cancelled(entry) }
                return false
            }
            notifyTextAvailable("### ${entry.displayName}\n${spec.method} ${spec.uri}\n", ProcessOutputTypes.SYSTEM)
            // HttpRequestCall starts I/O in its constructor. Create only the
            // current request, and publish it even if Stop raced construction.
            exchange = HttpRequestCall(spec)
            val cancelImmediately = synchronized(ownership) { activeCall = exchange; stopping }
            if (cancelImmediately) exchange.close()
            val response = exchange.await()
            exchange.close()
            val completed = synchronized(ownership) {
                activeCall = null
                !stopping
            }
            if (!completed) {
                observe { observer.cancelled(entry) }
                return false
            }
            // Completion is claimed before notifying consumers. A Stop invoked
            // by this callback cancels the remaining requests, not this result.
            notifyTextAvailable("HTTP ${response.status} — ${response.elapsedMillis} ms, ${response.body.size} bytes\n", ProcessOutputTypes.SYSTEM)
            if (includeResponseDetailInConsole) {
                response.headers.forEach { (name, values) -> values.forEach { notifyTextAvailable("$name: $it\n", ProcessOutputTypes.STDOUT) } }
                notifyTextAvailable("\n${response.displayBody()}\n", ProcessOutputTypes.STDOUT)
            }
            observe { observer.responded(entry, response) }
            return true
        } catch (failure: Exception) {
            var cause: Throwable = failure
            while (cause is ExecutionException && cause.cause != null) cause = cause.cause!!
            if (cause is InterruptedException) {
                requestStop()
                Thread.currentThread().interrupt()
            }
            val cancelled = synchronized(ownership) { stopping }
            if (cancelled) {
                observe { observer.cancelled(entry) }
            } else {
                val message = when (cause) {
                    is TimeoutException -> "HTTP request timed out after 30 seconds (including response body)."
                    is CancellationException -> "HTTP request cancelled."
                    else -> cause.message ?: cause.javaClass.simpleName
                }
                notifyTextAvailable("${entry.displayName}: $message\n", ProcessOutputTypes.STDERR)
                observe { observer.failed(entry, message) }
            }
            return false
        } finally {
            exchange?.close()
            synchronized(ownership) { if (activeCall === exchange) activeCall = null }
        }
    }

    private fun observe(callback: () -> Unit) {
        try {
            callback()
        } catch (failure: Exception) {
            observerFailed = true
            LOG.warn("HTTP batch result observer failed", failure)
        }
    }

    private fun finish(code: Int) {
        if (!finished.compareAndSet(false, true)) return
        try {
            notifyProcessTerminated(code)
        } finally {
            finishedCallback(this)
        }
    }

    private companion object {
        val LOG = Logger.getInstance(HttpBatchProcessHandler::class.java)
        const val CONSOLE_TEXT_LIMIT = 512 * 1024 // UTF-16 code units, including the one final notice.
        const val CONSOLE_OMISSION_NOTICE = "\n[HTTP console output limit reached; further console text is omitted. Request execution continues.]\n"
    }
}
