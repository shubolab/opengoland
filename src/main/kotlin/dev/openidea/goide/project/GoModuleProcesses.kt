package dev.openidea.goide.project

import com.intellij.jna.JnaLoader
import com.pty4j.PtyProcessBuilder
import com.pty4j.unix.PtyHelpers
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal data class GoModuleCommandOutput(val stdout: String, val stderr: String)

/** Finite background commands; Unix children have an exclusively owned process group. */
internal object GoModuleProcesses {
    private const val OUTPUT_LIMIT = 256 * 1024
    private val windows = System.getProperty("os.name").startsWith("Windows")

    fun run(
        executable: String,
        arguments: List<String>,
        directory: Path,
        cancelled: () -> Boolean,
        timeoutMillis: Long = 60_000,
        onOutput: (String) -> Unit = {},
    ): GoModuleCommandOutput {
        checkCancelled(cancelled)
        require(executable.isNotBlank() && '\u0000' !in executable && arguments.none { '\u0000' in it }) { "Invalid Go command arguments." }
        val command = listOf(executable) + arguments
        val environment = System.getenv().toMutableMap().apply {
            // Probe and creation must use the selected SDK, not an ambient workspace,
            // persisted toolchain override or an automatic toolchain download.
            put("GOWORK", "off"); put("GOENV", "off"); put("GOTOOLCHAIN", "local"); put("GO111MODULE", "on"); put("GOTELEMETRY", "off")
            remove("GOROOT"); remove("GOFLAGS")
            put("TERM", "dumb")
        }
        val process = if (windows) ProcessBuilder(command).directory(directory.toFile()).apply {
            environment().clear(); environment().putAll(environment)
        }.start() else {
            if (!JnaLoader.isLoaded()) throw IOException("The IDE could not load its process launcher. Restart open Goland and try again.")
            PtyProcessBuilder(command.toTypedArray()).setDirectory(directory.toString()).setEnvironment(environment)
                .setConsole(true).setRedirectErrorStream(false).setSpawnProcessUsingJdkOnMacIntel(false).start()
        }
        val pid = process.pid()
        val descendants = linkedMapOf<Long, ProcessHandle>()
        val failure = AtomicReference<Throwable?>()
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        fun reader(stream: InputStream, target: StringBuilder, name: String) = Thread({
            try {
                val decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                InputStreamReader(stream, decoder).use { input ->
                    val buffer = CharArray(4096)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (target.length + count > OUTPUT_LIMIT) throw IOException("Go command output exceeded 256 KiB on $name.")
                        val text = String(buffer, 0, count)
                        target.append(text)
                        onOutput(text)
                    }
                }
            } catch (error: Throwable) { failure.compareAndSet(null, error) }
        }, "open Goland module $name").apply { isDaemon = true; start() }
        val readers = listOf(reader(process.inputStream, stdout, "stdout"), reader(process.errorStream, stderr, "stderr"))
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        try {
            while (true) {
                checkCancelled(cancelled)
                failure.get()?.let { throw IOException("Unable to read Go command output: ${it.message}", it) }
                if (windows) process.descendants().use { children -> children.forEach { descendants[it.pid()] = it } }
                if (process.waitFor(50, TimeUnit.MILLISECONDS)) break
                if (System.nanoTime() >= deadline) throw IOException("Go ${arguments.joinToString(" ")} timed out. Check the selected SDK and try again.")
            }
            // A launcher may exit while its children retain stdout or stderr.
            // End only its dedicated group before waiting for output completion.
            terminate(process, pid, descendants.values.toList())
            val drainDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (readers.any { it.isAlive }) {
                checkCancelled(cancelled)
                failure.get()?.let { throw IOException("Unable to read Go command output: ${it.message}", it) }
                if (System.nanoTime() >= drainDeadline) throw IOException("Go command output did not close after the process exited.")
                readers.forEach { it.join(20) }
            }
            failure.get()?.let { throw IOException("Unable to read Go command output: ${it.message}", it) }
            checkCancelled(cancelled)
            if (process.exitValue() != 0) {
                val detail = stderr.toString().trim().ifBlank { stdout.toString().trim() }.take(4096)
                throw IOException("Go ${arguments.joinToString(" ")} failed (exit ${process.exitValue()}):\n$detail")
            }
            return GoModuleCommandOutput(stdout.toString(), stderr.toString())
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw CancellationException("Go project creation was cancelled.")
        } finally {
            val interrupted = Thread.interrupted()
            try {
                terminate(process, pid, descendants.values.toList())
                runCatching { process.outputStream.close() }
                runCatching { process.inputStream.close() }
                runCatching { process.errorStream.close() }
                readers.forEach { runCatching { it.join(1000) } }
            } finally { if (interrupted) Thread.currentThread().interrupt() }
        }
    }

    private fun terminate(process: Process, pid: Long, descendants: List<ProcessHandle>) {
        if (!windows) {
            require(pid in 1..Int.MAX_VALUE.toLong()) { "Invalid owned Go process group." }
            val native = PtyHelpers.getInstance()
            // Do not use a PID fallback after the launcher has exited: only the
            // group created for this invocation belongs to this operation.
            if (native.killpg(pid.toInt(), 0) == 0) {
                native.killpg(pid.toInt(), 15)
                val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(300)
                while (native.killpg(pid.toInt(), 0) == 0 && System.nanoTime() < deadline) Thread.sleep(10)
                native.killpg(pid.toInt(), 9)
            }
        } else {
            descendants.asReversed().filter { it.isAlive }.forEach { it.destroy() }
            if (process.isAlive) process.destroy()
            if (!process.waitFor(300, TimeUnit.MILLISECONDS)) process.destroyForcibly()
            descendants.asReversed().filter { it.isAlive }.forEach { it.destroyForcibly() }
        }
        process.waitFor(1000, TimeUnit.MILLISECONDS)
    }

    fun checkCancelled(cancelled: () -> Boolean) {
        if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException("Go project creation was cancelled.")
    }
}
