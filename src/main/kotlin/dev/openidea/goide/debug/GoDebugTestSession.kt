package dev.openidea.goide.debug

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.process.KillableProcessHandler
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.testframework.sm.SMTestRunnerConnectionUtil
import com.intellij.execution.ui.ConsoleView
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.execution.ParametersListUtil
import com.redhat.devtools.lsp4ij.dap.configurations.DAPRunConfigurationOptionsBase
import com.redhat.devtools.lsp4ij.dap.DAPServerReadyTracker
import com.redhat.devtools.lsp4ij.dap.runInTerminal.RunInTerminalManager
import dev.openidea.goide.run.GoPackageDirectories
import dev.openidea.goide.run.GoRunConfiguration
import dev.openidea.goide.run.GoRunKind
import dev.openidea.goide.run.GoRunParameters
import dev.openidea.goide.run.GoTestConsoleProperties
import dev.openidea.goide.run.GoTestOutput
import dev.openidea.goide.settings.GoToolchain
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

internal class GoDebugTestOptions(val testSession: GoDebugTestSession) : DAPRunConfigurationOptionsBase()

/** One test binary, its official test2json converter, and one native test event stream. */
internal class GoDebugTestSession(val configuration: RunConfiguration) : Disposable {
    val profileSnapshot: RunConfiguration = configuration.clone()
    val values: GoRunParameters = GoDebugTestProfiles.parameters(profileSnapshot)
    val settings = GoToolchain.capture(configuration.project)
    private val directories = ConcurrentHashMap<String, String>()
    val output = GoTestOutput(values.workingDirectory, directories)
    val events = GoDebugTestEventsHandler(this)
    val completed = CompletableFuture<Int>()
    private val lock = Any()
    private val queue = ArrayDeque<String?>()
    private var queuedChars = 0L
    private var draining = false
    private var ending = false
    private var disposed = false
    private var cancelled = false
    private var sawTest = false
    private var targetExit: Int? = null
    private var runFailure: String? = null
    private var targetPid: Long? = null
    private var explicitExit = false
    private var naturalTermination = false
    private var disconnecting = false
    private var delve1242 = false
    @Volatile var disconnect: (() -> CompletableFuture<Void>)? = null
    private var converter: KillableProcessHandler? = null
    private var writer: OutputStreamWriter? = null
    private var adapter: ProcessHandler? = null
    private var packageName = ""
    private val converterReady = CompletableFuture<JsonObject>()
    private var preparingConverter = false
    @Volatile private var preparedLaunch: GoDebugLaunch? = null
    val launch: GoDebugLaunch get() = preparedLaunch ?: error("Go Debug Test has not been prepared")

    init { Disposer.register(configuration.project, this) }

    /** Called only by the runner's background preparation, before the adapter starts. */
    fun prepare() {
        check(!ApplicationManager.getApplication().isDispatchThread) { "Go test preparation must run in the background" }
        synchronized(lock) { check(!disposed && !ending) { "Debug test session was closed" } }
        // Upstream DAPClient.dispose accesses this memory-only project service.
        // Initialize it while the owner is alive, not during project disposal.
        RunInTerminalManager.getInstance(configuration.project)
        val raw = GoDebugTestProfiles.launch(profileSnapshot, values, configuration.project.basePath.orEmpty())
        val framed = GoTestDebugArguments.create(ParametersListUtil.parse(values.buildFlags, false, true),
            values.testRuntimeArguments(debug = true) + ParametersListUtil.parse(values.arguments, false, true), "", framed = true)
        preparedLaunch = raw.copy(arguments = framed.arguments, buildFlags = framed.buildFlags)
        val metadataValues = values.independentCopy().apply { buildFlags = ParametersListUtil.join(framed.buildFlags) }
        val metadata = GoPackageDirectories.loadMetadata(configuration.project, metadataValues, Path.of(raw.workingDirectory), settings)
        if (metadata.directories.size != 1) throw ExecutionException("Debug one Go test package with available package metadata.")
        directories.putAll(metadata.directories)
        packageName = directories.keys.single()
        // Delve v1.24.2 service/dap/server.go sends terminated without exited.
        // stopDebugSession emits proc.ErrProcessExited during disconnect only.
        delve1242 = pinnedDelveVersion(raw.workingDirectory)
        val command = GoToolchain.command(configuration.project, "go", listOf("tool", "test2json", "-t", "-p", packageName), settings)
            .withWorkDirectory(raw.workingDirectory).withEnvironment(values.environment).withCharset(StandardCharsets.UTF_8)
        if (!values.passParentEnvironment) command.withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.NONE)
        val process = KillableProcessHandler(command)
        process.setShouldDestroyProcessRecursively(true)
        process.addProcessListener(object : ProcessAdapter() {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                if (outputType === ProcessOutputTypes.STDOUT) converted(event.text)
                else if (outputType === ProcessOutputTypes.STDERR) toolOutput("test2json: ${event.text}")
            }
            override fun processTerminated(event: ProcessEvent) {
                converterReady.completeExceptionally(ExecutionException("The selected Go SDK's test2json exited before becoming ready (exit ${event.exitCode})."))
                converterFinished(event.exitCode)
            }
        })
        val accepted = synchronized(lock) {
            if (disposed || ending) false else {
                converter = process
                writer = OutputStreamWriter(process.processInput!!, StandardCharsets.UTF_8)
                preparingConverter = true
                true
            }
        }
        process.startNotify()
        if (!accepted) { process.destroyProcess(); throw ExecutionException("Debug test session was closed") }
        awaitConverterStart(process)
    }

    /** Tool compilation belongs to preparation, not the three-second EOF deadline. */
    private fun awaitConverterStart(process: KillableProcessHandler) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        try {
            while (true) {
                ProgressManager.checkCanceled()
                synchronized(lock) {
                    if (disposed || ending) throw CancellationException("Go test preparation was cancelled")
                }
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) throw ExecutionException("The selected Go SDK's test2json did not become ready within 60 seconds.")
                try {
                    converterReady.get(minOf(remaining, TimeUnit.MILLISECONDS.toNanos(100)), TimeUnit.NANOSECONDS)
                    synchronized(lock) {
                        if (disposed || ending) throw CancellationException("Go test preparation was cancelled")
                        preparingConverter = false
                    }
                    return
                } catch (_: java.util.concurrent.TimeoutException) { }
            }
        } catch (failure: Throwable) {
            val cause = if (failure is java.util.concurrent.ExecutionException) failure.cause ?: failure else failure
            if (cause is CancellationException || cause is ProcessCanceledException || cause is InterruptedException) {
                requestCancellation()
                end()
                if (cause is InterruptedException) Thread.currentThread().interrupt()
            }
            // This is the converter and compiler subtree started by this session.
            // prepare is always BGT; disposal only wakes this waiter, never waits on EDT.
            if (!process.isProcessTerminated) process.destroyProcess()
            throw cause
        }
    }

    fun dapParameters(): Map<String, Any> {
        val env = GoToolchain.command(configuration.project, "go", emptyList(), settings).environment
        return launch.dapParameters(configuration.name, env, settings.buildTags)
    }

    fun bindAdapter(process: ProcessHandler, ready: DAPServerReadyTracker? = null) {
        synchronized(lock) { adapter = process }
        process.addProcessListener(object : ProcessAdapter() {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                ready?.onTextAvailable(event, outputType)
                toolOutput(event.text.splitToSequence('\n').joinToString("\n") { if (it.isEmpty()) it else "Delve adapter: $it" })
            }
            override fun processTerminated(event: ProcessEvent) {
                // OS exit can race already queued DAP events. It never supplies the test exit code.
                AppExecutorUtil.getAppScheduledExecutorService().schedule({ end() }, 1, TimeUnit.SECONDS)
            }
        })
        if (synchronized(lock) { disposed }) process.destroyProcess()
    }

    fun createConsole(executor: com.intellij.execution.Executor): ConsoleView {
        val properties = GoTestConsoleProperties(configuration, executor, output)
        val console = SMTestRunnerConnectionUtil.createAndAttachConsole("OpenIDEAGo", events, properties)
        console.addMessageFilter(properties.fuzzCorpusFilter)
        Disposer.register(console, Disposable { Disposer.dispose(this) })
        events.activate()
        return console
    }

    /** DAP target stdout/stderr only; adapter/status/repl text is never parsed as test protocol. */
    fun targetOutput(text: String): Boolean {
        if (text.isEmpty()) return true
        var overflow = false
        synchronized(lock) {
            if (ending || disposed) return false
            if (queuedChars + text.length > 8L * 1024 * 1024) overflow = true
            else { queue.addLast(text); queuedChars += text.length; drainLocked() }
        }
        if (overflow) conversionFailed("Debug test output exceeded the bounded converter queue")
        return !overflow
    }

    fun processStarted(pid: Long) { synchronized(lock) { if (!ending && pid > 0) targetPid = pid } }
    fun naturalTermination() { synchronized(lock) { naturalTermination = true } }
    fun disconnecting() { synchronized(lock) { disconnecting = true } }
    fun exitedEvent(code: Int) { synchronized(lock) { if (!ending) { targetExit = code; explicitExit = true } } }

    /** Adapter console diagnostic: never stdout/stderr, arbitrary logs or REPL output. */
    fun delveConsoleStatus(text: String) {
        val match = Regex("Process ([0-9]+) has exited with status (-?[0-9]+)\\r?\\n?").matchEntire(text) ?: return
        val pid = match.groupValues[1].toLongOrNull() ?: return
        val status = match.groupValues[2].toIntOrNull() ?: return
        synchronized(lock) {
            if (delve1242 && naturalTermination && disconnecting && !explicitExit && !ending && targetPid == pid)
                targetExit = status
        }
    }

    private fun pinnedDelveVersion(cwd: String): Boolean = runCatching {
        val command = GoToolchain.command(configuration.project, "dlv", listOf("version"), settings)
            .withWorkDirectory(cwd).withEnvironment(values.environment)
        if (!values.passParentEnvironment) command.withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.NONE)
        val process = CapturingProcessHandler(command).apply { setShouldDestroyProcessRecursively(true) }
        val cleanup = Disposable { if (!process.isProcessTerminated) process.destroyProcess() }
        try {
            Disposer.register(configuration.project, cleanup)
            val result = process.runProcess(3_000, true)
            !result.isTimeout && !result.isCancelled && result.exitCode == 0 &&
                Regex("(?m)^Version: 1\\.24\\.2\\s*$").containsMatchIn(result.stdout)
        } finally { Disposer.dispose(cleanup) }
    }.getOrDefault(false)

    fun requestCancellation() {
        synchronized(lock) {
            // Stop after a genuine exited event must not rewrite a completed result as skipped.
            if (targetExit == null && !ending) { cancelled = true; output.requestCancellation() }
        }
    }

    fun stop() {
        requestCancellation()
        end()
        cleanupAdapter()
    }

    private fun cleanupAdapter() {
        val process = synchronized(lock) { adapter } ?: return
        if (process.isProcessTerminated) return
        val ready = CompletableFuture<Boolean>()
        val deadline = AppExecutorUtil.getAppScheduledExecutorService().schedule({ ready.complete(false) }, 3, TimeUnit.SECONDS)
        ready.whenComplete { ack, _ ->
            deadline.cancel(false)
            if (ack == true) AppExecutorUtil.getAppScheduledExecutorService().schedule({
                if (!process.isProcessTerminated) process.destroyProcess()
            }, 1, TimeUnit.SECONDS)
            else if (!process.isProcessTerminated) process.destroyProcess()
        }
        AppExecutorUtil.getAppExecutorService().execute {
            try { disconnect?.invoke()?.whenComplete { _, error -> ready.complete(error == null) } ?: ready.complete(false) }
            catch (_: Exception) { ready.complete(false) }
        }
    }

    fun end(failureCode: Int? = null) {
        val process = synchronized(lock) {
            if (ending) return
            if (failureCode != null && failureCode != 0) runFailure = runFailure ?: "Go test debugger failed before confirming the result"
            ending = true
            queue.addLast(null)
            drainLocked()
            converter
        }
        converterReady.completeExceptionally(CancellationException("Go test session ended during converter preparation"))
        if (process == null) finishEvents()
        else AppExecutorUtil.getAppScheduledExecutorService().schedule({
            if (!completed.isDone) {
                synchronized(lock) { runFailure = runFailure ?: "Debug test converter did not finish within its cleanup deadline" }
                process.destroyProcess()
            }
        }, 3, TimeUnit.SECONDS)
    }

    fun toolOutput(text: String) { events.publish(GoTestOutput.message("message", "text" to text, "status" to "NORMAL")) }

    /** Queue manipulation is short; pipe encoding/writes and EOF always happen off the EDT. */
    private fun drainLocked() {
        if (draining) return
        draining = true
        AppExecutorUtil.getAppExecutorService().execute {
            try {
                while (true) {
                    val next = synchronized(lock) {
                        if (queue.isEmpty()) { draining = false; return@execute }
                        queue.removeFirst().also { queuedChars -= it?.length ?: 0 }
                    }
                    val sink = synchronized(lock) { writer }
                    if (next == null) { sink?.close(); return@execute }
                    // One persistent encoder preserves surrogate pairs split across DAP chunks.
                    sink?.write(next); sink?.flush()
                }
            } catch (failure: Exception) {
                conversionFailed("Debug test conversion failed: ${failure.message}")
            }
        }
    }

    private val jsonLine = StringBuilder()
    @Synchronized private fun converted(chunk: String) {
        jsonLine.append(chunk)
        while (true) {
            val end = jsonLine.indexOf("\n")
            if (end < 0) return
            val line = jsonLine.substring(0, end); jsonLine.delete(0, end + 1)
            val json = runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull()
            val action = json?.get("Action")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            if (!converterReady.isDone) {
                val announcedPackage = json?.get("Package")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                if (action == "start" && announcedPackage == packageName && json != null && !json.has("Test"))
                    converterReady.complete(json.deepCopy())
                else {
                    converterReady.completeExceptionally(ExecutionException("The selected Go SDK's test2json did not emit the expected package start event."))
                    return
                }
            }
            if (action == "run" && json.has("Test")) synchronized(lock) { sawTest = true }
            // The converter reading stdin exits 0 even if the test binary failed.
            // Its unversioned package terminal action is replaced by the actual DAP exited code.
            if (json != null && !json.has("Test") && action in setOf("pass", "fail", "skip")) continue
            output.consume(line + "\n", events::publish)
        }
    }

    private fun converterFinished(code: Int) {
        if (synchronized(lock) { preparingConverter && cancelled && ending }) { finishEvents(); return }
        val failed = synchronized(lock) { code != 0 || !ending }
        if (failed) { conversionFailed("Go test output converter ended unexpectedly (exit $code)"); return }
        finishEvents()
    }

    private fun conversionFailed(reason: String) {
        synchronized(lock) {
            if (!(preparingConverter && cancelled && ending)) runFailure = runFailure ?: reason
            ending = true
            queue.clear(); queuedChars = 0
        }
        AppExecutorUtil.getAppExecutorService().execute {
            synchronized(lock) { converter }?.let { if (!it.isProcessTerminated) it.destroyProcess() }
            cleanupAdapter()
            finishEvents()
        }
    }

    private data class Finished(val code: Int, val stopped: Boolean, val seen: Boolean, val actualExit: Int?, val failure: String?)

    @Synchronized private fun finishEvents() {
        if (completed.isDone) return
        val (code, stopped, seen, actualExit, failure) = synchronized(lock) {
            Finished(if (runFailure != null) 1 else targetExit ?: if (cancelled) 0 else 1, cancelled, sawTest, targetExit, runFailure)
        }
        if (!stopped && actualExit == null) toolOutput("Debug session ended without a confirmed Go test process exit status.\n")
        failure?.let { toolOutput("$it.\n") }
        if (packageName.isNotEmpty()) {
            if (stopped && !seen) events.publish(GoTestOutput.message("testIgnored", "name" to packageName,
                "nodeId" to "go-package:$packageName", "message" to "Debugging stopped before tests started"))
            val terminal = JsonObject().apply {
                addProperty("Package", packageName)
                addProperty("Action", if (stopped) "skip" else if (code == 0) "pass" else "fail")
            }
            output.consume(terminal.toString() + "\n", events::publish)
        }
        output.finish(code, stopped, events::publish)
        toolOutput(when {
            stopped -> "Go test debugging was stopped.\n"
            actualExit != null -> "Go test exited with code $actualExit.\n"
            else -> "Debug test results are incomplete; the IDE run is failed.\n"
        })
        events.finish(code)
        completed.complete(code)
    }

    override fun dispose() {
        synchronized(lock) { if (disposed) return; disposed = true }
        stop()
    }
}

internal class GoDebugTestEventsHandler(private val owner: GoDebugTestSession) : ProcessHandler() {
    private val delivery = Any()
    private val beforeConsole = mutableListOf<String>()
    private var active = false
    private var pendingExit: Int? = null
    override fun destroyProcessImpl() = owner.stop()
    override fun detachProcessImpl() = owner.stop()
    override fun detachIsDefault() = false
    override fun getProcessInput(): java.io.OutputStream? = null
    fun activate() = synchronized(delivery) {
        if (active) return@synchronized
        active = true
        startNotify()
        beforeConsole.forEach { notifyTextAvailable(it, ProcessOutputTypes.STDOUT) }
        beforeConsole.clear()
        pendingExit?.let { notifyProcessTerminated(it) }
    }
    fun publish(text: String) = synchronized(delivery) {
        if (!isProcessTerminated) {
            if (active) notifyTextAvailable(text, ProcessOutputTypes.STDOUT) else beforeConsole += text
        }
    }
    fun finish(code: Int) = synchronized(delivery) {
        if (!isProcessTerminated) {
            if (active) notifyProcessTerminated(code) else pendingExit = code
        }
    }
}
