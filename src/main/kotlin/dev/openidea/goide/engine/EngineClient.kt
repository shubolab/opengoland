package dev.openidea.goide.engine

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import dev.openidea.goide.settings.GoToolchain
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

@Service(Service.Level.PROJECT)
class EngineClient(private val project: Project) : Disposable {
    private val sequence = AtomicLong()
    private val pending = ConcurrentHashMap<Long, CompletableFuture<JsonElement>>()
    private val listeners = CopyOnWriteArrayList<(String, JsonObject) -> Unit>()
    private val io = Executors.newCachedThreadPool { r -> Thread(r, "OpenIDEA-Go-engine").apply { isDaemon = true } }
    @Volatile private var process: Process? = null
    @Volatile private var disposed = false
    @Volatile private var initialized: CompletableFuture<JsonElement>? = null
    private var configurationKey: List<String>? = null
    private val activeTasks = ConcurrentHashMap.newKeySet<String>()
    private val writeLock = Any()

    fun connect(): CompletableFuture<JsonElement> {
        if (disposed) return CompletableFuture.failedFuture(IllegalStateException("Project is closed"))
        return try { connect(GoToolchain.capture(project)) }
        catch (error: Exception) { CompletableFuture.failedFuture(error) }
    }

    @Synchronized
    fun connect(settings: GoToolchain.Settings): CompletableFuture<JsonElement> {
        if (disposed) return CompletableFuture.failedFuture(IllegalStateException("Project is closed"))
        val root = project.basePath ?: return CompletableFuture.failedFuture(IllegalStateException("Open a Go project first"))
        val executable = executable()
            ?: return CompletableFuture.failedFuture(IllegalStateException("Go engine is missing. Run ./gradlew buildEngine or scripts/build.sh."))
        val command = try { GoToolchain.command(project, "go", emptyList(), settings) }
            catch (error: Exception) { return CompletableFuture.failedFuture(error) }
        val key = settings.key + command.exePath + command.environment.toSortedMap().flatMap { listOf(it.key, it.value) }
        initialized?.let { ready ->
            if (configurationKey == key && process?.isAlive == true) return ready
            disconnect(IllegalStateException("Go toolchain settings changed or the engine exited"), reason = "configuration_changed")
        }
        val ready = CompletableFuture<JsonElement>()
        initialized = ready
        configurationKey = key
        val builder = ProcessBuilder(executable.toString()).directory(java.io.File(root))
        // Preserve the platform's complete environment merge. On Windows a
        // raw ProcessBuilder map can otherwise retain both inherited Path and
        // overridden PATH, selecting a different Go SDK in the child.
        builder.environment().clear()
        builder.environment().putAll(command.effectiveEnvironment)
        val child = try { builder.start() } catch (error: Exception) {
            initialized = null; configurationKey = null
            ready.completeExceptionally(error)
            return ready
        }
        process = child
        io.execute { readMessages(child) }
        io.execute {
            runCatching {
                child.errorStream.bufferedReader().useLines { lines -> lines.forEach {
                    if (process === child) notify("engine/log", JsonObject().apply { addProperty("text", it) })
                } }
            }
        }
        requestRaw("initialize", JsonObject().apply {
            addProperty("root", root)
            addProperty("goExecutable", command.exePath)
        }, child).whenComplete { value, error ->
            if (error == null) ready.complete(value)
            else {
                ready.completeExceptionally(error)
                disconnect(error, child)
            }
        }
        return ready
    }

    fun request(method: String, params: JsonObject = JsonObject(), timeoutSeconds: Long = 45): CompletableFuture<JsonElement> =
        try { requestConnected(connect(), method, params, timeoutSeconds) } catch (e: Exception) { CompletableFuture.failedFuture(e) }

    fun request(method: String, params: JsonObject, settings: GoToolchain.Settings, timeoutSeconds: Long = 45): CompletableFuture<JsonElement> =
        try { requestConnected(connect(settings), method, params, timeoutSeconds) } catch (e: Exception) { CompletableFuture.failedFuture(e) }

    private fun requestConnected(connection: CompletableFuture<JsonElement>, method: String, params: JsonObject, timeoutSeconds: Long): CompletableFuture<JsonElement> =
        connection.thenCompose {
            // Another task may have selected another SDK while initialization was in flight.
            // Bind the request to this connection; never send it to a replacement engine.
            val child = synchronized(this) { if (initialized === connection) process else null }
                ?: return@thenCompose CompletableFuture.failedFuture(IllegalStateException("Go engine configuration changed before the request was sent"))
            requestRaw(method, params, child, timeoutSeconds)
        }

    /** Cancellation must not initialize a new engine or resolve executables on the EDT. */
    fun cancelOperation(operationId: String) {
        val child = process ?: return
        if (disposed) return
        runCatching { io.execute { runCatching {
            requestRaw("refactor/cancel", JsonObject().apply { addProperty("operationId", operationId) }, child)
        } } }
    }

    /** Cancellation must never initialize or replace an engine connection. */
    fun cancelTask(taskId: String) {
        val child = process ?: return
        if (disposed) return
        runCatching { io.execute { runCatching {
            requestRaw("task/cancel", JsonObject().apply { addProperty("id", taskId) }, child)
        } } }
    }

    fun listen(listener: (String, JsonObject) -> Unit): AutoCloseable {
        listeners.add(listener)
        return AutoCloseable { listeners.remove(listener) }
    }

    private fun requestRaw(method: String, params: JsonObject, child: Process? = process, timeoutSeconds: Long = 45): CompletableFuture<JsonElement> {
        child ?: return CompletableFuture.failedFuture(IllegalStateException("Go engine is not running"))
        if (process !== child || !child.isAlive) return CompletableFuture.failedFuture(IllegalStateException("Go engine is not running"))
        val id = sequence.incrementAndGet()
        val packet = JsonObject().apply {
            addProperty("jsonrpc", "2.0"); addProperty("id", id); addProperty("method", method); add("params", params)
        }.toString().toByteArray(StandardCharsets.UTF_8)
        // Enforce the wire limit on the complete encoded envelope, before a
        // header, pending request, or task becomes visible to the shared engine.
        if (packet.size > EngineFraming.MAX_MESSAGE_BYTES) return CompletableFuture.failedFuture(
            IllegalArgumentException("Go engine request exceeds the 16 MiB UTF-8 message limit (${packet.size} bytes)"))
        val result = CompletableFuture<JsonElement>()
        pending[id] = result
        val taskId = if (method == "task/start" || method == "task/prepare") params.get("id")?.asString else null
        if (taskId != null) activeTasks.add(taskId)
        try {
            synchronized(writeLock) {
                check(process === child && child.isAlive) { "Go engine was disconnected" }
                child.outputStream.write("Content-Length: ${packet.size}\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
                child.outputStream.write(packet)
                child.outputStream.flush()
            }
        } catch (e: Exception) { pending.remove(id)?.completeExceptionally(e) }
        result.orTimeout(timeoutSeconds, TimeUnit.SECONDS).whenComplete { _, error ->
            pending.remove(id)
            if (error != null && taskId != null) activeTasks.remove(taskId)
        }
        return result
    }

    private fun readMessages(child: Process) {
        try {
            val input = BufferedInputStream(child.inputStream)
            while (true) {
                val message = JsonParser.parseString(String(EngineFraming.read(input) ?: break, StandardCharsets.UTF_8)).asJsonObject
                val id = message.get("id")
                if (id != null && !id.isJsonNull) {
                    val future = pending.remove(id.asLong) ?: continue
                    if (message.has("error")) future.completeExceptionally(IllegalStateException(message.getAsJsonObject("error").get("message").asString))
                    else future.complete(message.get("result"))
                } else if (message.has("method") && process === child) {
                    val method = message.get("method").asString
                    val params = message.getAsJsonObject("params") ?: JsonObject()
                    if (method == "task/exit") params.get("id")?.asString?.let(activeTasks::remove)
                    notify(method, params)
                }
            }
            disconnect(IllegalStateException("Go engine stopped"), child)
        } catch (e: Exception) { disconnect(e, child) }
    }

    private fun notify(method: String, params: JsonObject) {
        listeners.forEach { listener -> runCatching { listener(method, params) } }
    }
    @Synchronized private fun disconnect(error: Throwable, expected: Process? = null, reason: String = "disconnected") {
        if (expected != null && process !== expected) return
        val child = process
        val failed = pending.values.toList()
        val tasks = activeTasks.toList()
        pending.clear(); activeTasks.clear()
        process = null; initialized = null; configurationKey = null
        failed.forEach { it.completeExceptionally(error) }
        if (child != null) {
            // Project disposal can run on the EDT. Enumeration and every pipe
            // operation stay on workers, with a separate deadline worker so a
            // blocked writer cannot prevent owned-process cleanup.
            val descendantsReady = CompletableFuture<List<ProcessHandle>>()
            io.execute {
                descendantsReady.complete(runCatching { child.descendants().toList() }.getOrDefault(emptyList()))
                runCatching {
                    val payload = JsonObject().apply { addProperty("jsonrpc", "2.0"); addProperty("method", "shutdown"); add("params", JsonObject()) }
                        .toString().toByteArray(StandardCharsets.UTF_8)
                    synchronized(writeLock) {
                        child.outputStream.write("Content-Length: ${payload.size}\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
                        child.outputStream.write(payload); child.outputStream.flush()
                    }
                }
            }
            io.execute {
                val descendants = runCatching { descendantsReady.get(1, TimeUnit.SECONDS) }.getOrDefault(emptyList())
                runCatching { child.waitFor(3, TimeUnit.SECONDS) }
                val remaining = descendants + runCatching { child.descendants().toList() }.getOrDefault(emptyList())
                remaining.asReversed().forEach { handle -> if (handle.isAlive) runCatching { handle.destroyForcibly() } }
                if (child.isAlive) runCatching { child.destroyForcibly() }
                runCatching { child.outputStream.close() }
            }
            notify("engine/stopped", JsonObject().apply {
                addProperty("message", error.message ?: "Go engine stopped")
                addProperty("reason", reason)
                add("taskIds", com.google.gson.JsonArray().also { ids -> tasks.forEach(ids::add) })
            })
        }
    }
    @Synchronized override fun dispose() {
        if (disposed) return
        disposed = true
        disconnect(IllegalStateException("Project closed"), reason = "project_closed")
        listeners.clear(); io.shutdown()
    }
    private fun executable(): Path? = GoEngineExecutable.resolve()
}

/** Background-only executable resolution shared with finite command ownership. */
internal object GoEngineExecutable {
    fun resolve(): Path? {
        val workspace = System.getProperty("openidea.workspace")
        val filename = if (System.getProperty("os.name").startsWith("Windows")) "opengoland-engine.exe" else "opengoland-engine"
        workspace?.let { Path.of(it, "engine", "bin", filename) }?.takeIf { Files.isExecutable(it) }?.let { return it }
        val plugin = PluginManagerCore.getPlugin(PluginId.getId("dev.openidea.go"))?.pluginPath
        return plugin?.resolve("bin/$filename")?.takeIf { Files.isExecutable(it) }
    }
}

object EngineFraming {
    const val MAX_MESSAGE_BYTES = 16 * 1024 * 1024

    fun read(input: BufferedInputStream): ByteArray? {
        var length: Int? = null
        var headerBytes = 0
        while (true) {
            val line = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b == -1) {
                    if (headerBytes == 0 && line.size() == 0) return null
                    throw IllegalStateException("Truncated engine message header")
                }
                headerBytes++
                require(headerBytes <= 8192) { "Engine message header is too large" }
                if (b == 10) break
                line.write(b)
            }
            val text = line.toString(StandardCharsets.US_ASCII).trimEnd('\r')
            if (text.isEmpty()) break
            val parts = text.split(':', limit = 2)
            require(parts.size == 2) { "Malformed engine message header" }
            if (parts[0].equals("Content-Length", true)) {
                require(length == null) { "Duplicate Content-Length" }
                length = parts[1].trim().toInt()
            }
        }
        val size = requireNotNull(length) { "Missing Content-Length" }
        require(size in 0..MAX_MESSAGE_BYTES) { "Engine message length is out of range" }
        val bytes = input.readNBytes(size)
        require(bytes.size == size) { "Truncated engine message body" }
        return bytes
    }
}
