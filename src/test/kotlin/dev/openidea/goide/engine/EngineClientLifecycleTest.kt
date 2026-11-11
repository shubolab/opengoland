package dev.openidea.goide.engine

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.testFramework.LightPlatformTestCase
import dev.openidea.goide.process.GoOwnedTestExecutable
import dev.openidea.goide.settings.GoProjectSettings
import dev.openidea.goide.settings.GoToolchain
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class EngineClientLifecycleTest {
    private fun project(root: Path, settings: () -> GoProjectSettings): Project =
        Proxy.newProxyInstance(Project::class.java.classLoader, arrayOf(Project::class.java)) { proxy, method, args ->
            when (method.name) {
                "getBasePath" -> root.toString()
                "getService" -> if (args?.firstOrNull() == GoProjectSettings::class.java) settings() else null
                "toString" -> "EngineClientLifecycleTestProject"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> null
            }
        } as Project

    private fun fixture(run: (Path, GoProjectSettings, EngineClient) -> Unit) {
        val root = Files.createTempDirectory("opengoland-engine-client")
        val previous = System.getProperty("openidea.workspace")
        val executable = root.resolve("engine/bin/" + GoOwnedTestExecutable.name("opengoland-engine"))
        Files.createDirectories(executable.parent)
        GoOwnedTestExecutable.compile(executable, """
            package main
            import ("bufio"; "encoding/json"; "fmt"; "io"; "os"; "strconv"; "strings"; "time")
            func main() {
                launches, err := os.OpenFile("launched-pids", os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0600); if err != nil { panic(err) }
                fmt.Fprintln(launches, os.Getpid()); launches.Close()
                input := bufio.NewReader(os.Stdin)
                for {
                    length := -1
                    for {
                        line, err := input.ReadString('\n'); if err == io.EOF { return }; if err != nil { panic(err) }
                        line = strings.TrimSpace(line); if line == "" { break }
                        if strings.HasPrefix(strings.ToLower(line), "content-length:") {
                            length, err = strconv.Atoi(strings.TrimSpace(strings.SplitN(line, ":", 2)[1])); if err != nil { panic(err) }
                        }
                    }
                    if length < 0 { panic("Missing Content-Length") }
                    data := make([]byte, length); if _, err := io.ReadFull(input, data); err != nil { panic(err) }
                    var message struct { ID json.RawMessage `json:"id"`; Method string `json:"method"`; Params map[string]any `json:"params"` }
                    if err := json.Unmarshal(data, &message); err != nil { panic(err) }
                    if message.Method == "shutdown" { time.Sleep(200*time.Millisecond); return }
                    if message.Method == "initialize" {
                        if _, err := os.Stat("fail-once"); err == nil { os.Remove("fail-once"); os.Exit(1) }
                    }
                    result := map[string]any{"pid":os.Getpid(), "goExecutable":message.Params["goExecutable"], "goos":os.Getenv("GOOS")}
                    if message.Method == "task/start" { result = map[string]any{"accepted":true, "id":message.Params["id"]} }
                    packet, err := json.Marshal(map[string]any{"jsonrpc":"2.0", "id":message.ID, "result":result}); if err != nil { panic(err) }
                    fmt.Fprintf(os.Stdout, "Content-Length: %d\r\n\r\n", len(packet)); if _, err := os.Stdout.Write(packet); err != nil { panic(err) }
                }
            }
        """.trimIndent())
        System.setProperty("openidea.workspace", root.toString())
        lateinit var settings: GoProjectSettings
        val owner = project(root) { settings }
        settings = GoProjectSettings(owner)
        val engine = EngineClient(owner)
        try {
            run(root, settings, engine)
        } finally {
            engine.dispose()
            if (previous == null) System.clearProperty("openidea.workspace") else System.setProperty("openidea.workspace", previous)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            val launched = root.resolve("launched-pids")
            val handles = if (Files.exists(launched)) Files.readAllLines(launched).map(String::toLong)
                .mapNotNull { ProcessHandle.of(it).orElse(null) } else emptyList()
            while (handles.any { it.isAlive } && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue(handles.none { it.isAlive }, "Disposal must release each owned engine before deleting its executable")
            root.toFile().deleteRecursively()
        }
    }

    @Test fun failedInitializationCanBeRetried() = fixture { root, _, engine ->
        Files.writeString(root.resolve("fail-once"), "")
        assertFailsWith<java.util.concurrent.ExecutionException> { engine.connect().get(10, TimeUnit.SECONDS) }
        val ready = engine.connect().get(10, TimeUnit.SECONDS).asJsonObject
        assertTrue(ready.get("pid").asLong > 0)
        assertEquals(GoOwnedTestExecutable.name("go"), Path.of(ready.get("goExecutable").asString).fileName.toString())
    }

    @Test fun settingsRestartDoesNotLetOldReaderDisconnectNewEngine() = fixture { _, settings, engine ->
        val stopped = CopyOnWriteArrayList<JsonObject>()
        val subscription = engine.listen { method, params -> if (method == "engine/stopped") stopped.add(params) }
        try {
            val first = engine.connect().get(10, TimeUnit.SECONDS).asJsonObject.get("pid").asLong
            engine.request("task/start", JsonObject().apply { addProperty("id", "old-task") }).get(10, TimeUnit.SECONDS)
            settings.state.goos = "linux"
            val second = engine.connect().get(10, TimeUnit.SECONDS).asJsonObject
            assertNotEquals(first, second.get("pid").asLong)
            assertEquals("linux", second.get("goos").asString)
            assertEquals(listOf("old-task"), stopped.single().getAsJsonArray("taskIds").map { it.asString })
            ProcessHandle.of(first).ifPresent { it.onExit().get(5, TimeUnit.SECONDS) }
            val stillRunning = engine.request("project/info").get(10, TimeUnit.SECONDS).asJsonObject
            assertEquals(second.get("pid").asLong, stillRunning.get("pid").asLong)
            assertEquals(1, stopped.size)
        } finally {
            subscription.close()
        }
    }

    @Test fun disposeShutsDownOwnedEngineAndRejectsReconnect() = fixture { _, _, engine ->
        val pid = engine.connect().get(10, TimeUnit.SECONDS).asJsonObject.get("pid").asLong
        engine.dispose()
        ProcessHandle.of(pid).ifPresent { it.onExit().get(5, TimeUnit.SECONDS) }
        assertTrue(ProcessHandle.of(pid).map { !it.isAlive }.orElse(true))
        assertFailsWith<java.util.concurrent.ExecutionException> { engine.connect().get(2, TimeUnit.SECONDS) }
    }

    @Test fun frozenRequestReusesOnlyItsCapturedConfigurationAfterTheLiveSettingsChange() = fixture { root, settings, engine ->
        settings.state.goos = "linux"
        val captured = GoToolchain.capture(project(root) { settings })
        val first = engine.connect(captured).get(10, TimeUnit.SECONDS).asJsonObject
        settings.state.goos = "freebsd"

        val frozen = engine.request("project/info", JsonObject(), captured).get(10, TimeUnit.SECONDS).asJsonObject
        assertEquals("linux", frozen.get("goos").asString)
        assertEquals(first.get("pid").asLong, frozen.get("pid").asLong)

        val live = engine.request("project/info").get(10, TimeUnit.SECONDS).asJsonObject
        assertEquals("freebsd", live.get("goos").asString)
        assertNotEquals(first.get("pid").asLong, live.get("pid").asLong)

        val capturedAgain = engine.request("project/info", JsonObject(), captured).get(10, TimeUnit.SECONDS).asJsonObject
        assertEquals("linux", capturedAgain.get("goos").asString)
        assertNotEquals(live.get("pid").asLong, capturedAgain.get("pid").asLong)
        ProcessHandle.of(first.get("pid").asLong).ifPresent { it.onExit().get(5, TimeUnit.SECONDS) }
        ProcessHandle.of(live.get("pid").asLong).ifPresent { it.onExit().get(5, TimeUnit.SECONDS) }
    }
}

/** Actual platform EDT and project settings, with a deliberately non-reading owned child process. */
class EngineClientEdtDisposalTest : LightPlatformTestCase() {
    override fun runInDispatchThread() = false

    fun testDisposalCannotWaitForABlockedEnginePipeOnTheEdt() {
        val root = Files.createTempDirectory("opengoland-engine-blocked-pipe-").toRealPath()
        val script = root.resolve("engine/bin/" + GoOwnedTestExecutable.name("opengoland-engine"))
        val token = UUID.randomUUID().toString()
        val previousWorkspace = System.getProperty("openidea.workspace")
        val realProject = project
        val effectiveProject = Proxy.newProxyInstance(Project::class.java.classLoader,
            arrayOf(Project::class.java)) { _, method, arguments ->
            if (method.name == "getBasePath") root.toString()
            else try { method.invoke(realProject, *arguments.orEmpty()) }
                catch (failure: InvocationTargetException) { throw failure.targetException }
        } as Project
        val settings = GoProjectSettings.get(realProject)
        val originalGo = settings.goExecutable
        val disposed = CompletableFuture<Long>()
        val writerDone = CompletableFuture<Unit>()
        var client: EngineClient? = null
        var owned: OwnedEngine? = null
        var writer: Thread? = null
        try {
            Files.createDirectories(script.parent)
            // The marker is set before initialization is acknowledged. Afterwards stdin
            // remains open but is never read again, so a large real pipe write must block.
            GoOwnedTestExecutable.compile(script, """
                package main
                import ("bufio"; "encoding/json"; "fmt"; "io"; "os"; "path/filepath"; "strconv"; "strings"; "time")
                func main() {
                    executable, err := os.Executable(); if err != nil { panic(err) }
                    root := filepath.Dir(filepath.Dir(filepath.Dir(executable)))
                    record := map[string]any{"pid": os.Getpid(), "token": "$token", "script": executable}
                    encoded, err := json.Marshal(record); if err != nil { panic(err) }
                    if err := os.WriteFile(filepath.Join(root, "owned.json"), encoded, 0600); err != nil { panic(err) }
                    launches, err := os.OpenFile(filepath.Join(root, "launches"), os.O_WRONLY|os.O_CREATE|os.O_APPEND, 0600); if err != nil { panic(err) }
                    if _, err := fmt.Fprintln(launches, os.Getpid()); err != nil { panic(err) }; launches.Close()
                    input := bufio.NewReader(os.Stdin)
                    length := -1
                    for { line, err := input.ReadString('\n'); if err != nil { panic(err) }; line = strings.TrimSpace(line)
                        if line == "" { break }; if strings.HasPrefix(strings.ToLower(line), "content-length:") {
                            length, err = strconv.Atoi(strings.TrimSpace(strings.SplitN(line, ":", 2)[1])); if err != nil { panic(err) }
                        }
                    }
                    if length < 0 { panic("Missing Content-Length") }
                    data := make([]byte, length); if _, err := io.ReadFull(input, data); err != nil { panic(err) }
                    var message map[string]any; if err := json.Unmarshal(data, &message); err != nil { panic(err) }
                    if message["method"] != "initialize" { panic("Expected initialize") }
                    if err := os.WriteFile(filepath.Join(root, "not-reading"), []byte("$token"), 0600); err != nil { panic(err) }
                    packet, err := json.Marshal(map[string]any{"jsonrpc":"2.0", "id":message["id"], "result":record}); if err != nil { panic(err) }
                    fmt.Fprintf(os.Stdout, "Content-Length: %d\r\n\r\n", len(packet)); if _, err := os.Stdout.Write(packet); err != nil { panic(err) }
                    for { time.Sleep(time.Hour) }
                }
            """.trimIndent())
            System.setProperty("openidea.workspace", root.toString())
            // Retain real settings/services but use our own working directory. The
            // reused light project's basePath may name a deleted earlier fixture.
            settings.goExecutable = dev.openidea.goide.settings.GoToolchain.resolve("go",
                System.getProperty("opengoland.testGo") ?: System.getenv("OPENGOLAND_GO_TEST").orEmpty())
                ?: throw AssertionError("The Go toolchain required by this repository is unavailable")
            val engine = EngineClient(effectiveProject)
            client = engine
            val ready = engine.connect().get(4, TimeUnit.SECONDS).asJsonObject
            assertEquals(token, ready.get("token").asString)
            val pid = ready.get("pid").asLong
            val handle = ProcessHandle.of(pid).orElseThrow { AssertionError("The owned fake engine exited before the test") }
            val fixture = OwnedEngine(handle, handle.info().startInstant().orElse(null), root, script, token)
            owned = fixture
            assertEquals(token, Files.readString(root.resolve("not-reading")))

            val request = JsonObject().apply { addProperty("payload", "x".repeat(4 * 1024 * 1024)) }
            val writingThread = Thread({
                try {
                    engine.request("test/blocked-pipe", request).handle { _, _ -> Unit }.join()
                    writerDone.complete(Unit)
                } catch (failure: Throwable) { writerDone.completeExceptionally(failure) }
            }, "open-Goland-owned-blocked-pipe-writer").apply { isDaemon = true; start() }
            writer = writingThread
            val readyDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (!insidePipeWrite(writingThread) && !writerDone.isDone && System.nanoTime() < readyDeadline) Thread.sleep(5)
            assertTrue("The regression requires a real blocked pipe write, rather than a timer or fake lock", insidePipeWrite(writingThread))

            val started = System.nanoTime()
            ApplicationManager.getApplication().invokeLater {
                try {
                    assertTrue(ApplicationManager.getApplication().isDispatchThread)
                    engine.dispose()
                    disposed.complete(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
                } catch (failure: Throwable) { disposed.completeExceptionally(failure) }
            }
            val elapsed = try { disposed.get(2, TimeUnit.SECONDS) }
                catch (timeout: TimeoutException) {
                    // The old implementation blocks the EDT behind this writer. Kill
                    // only the exact child before failing, so that regression cannot
                    // wedge the platform test runner or its subsequent tests.
                    fixture.killExact()
                    runCatching { disposed.get(2, TimeUnit.SECONDS) }
                    throw AssertionError("EDT disposal waited for a blocked engine pipe", timeout)
                }
            assertTrue("EDT disposal must return before the process-cleanup deadline; took ${elapsed}ms", elapsed < 2_000)
            handle.onExit().get(5, TimeUnit.SECONDS)
            writerDone.get(1, TimeUnit.SECONDS)
            assertFalse("The deadline worker must terminate the owned non-reading child", handle.isAlive)
            assertFailsWith<java.util.concurrent.ExecutionException> { engine.connect().get(1, TimeUnit.SECONDS) }
            assertFailsWith<java.util.concurrent.ExecutionException> { engine.request("project/info").get(1, TimeUnit.SECONDS) }
            assertEquals(listOf(pid.toString()), Files.readAllLines(root.resolve("launches")), "Late callbacks must not restart a disposed engine")
        } finally {
            // Also cover failures before the initialize result was received. The token,
            // script path, PID and start time constrain cleanup to this test's child.
            if (owned == null && Files.exists(root.resolve("owned.json"))) {
                val record = JsonParser.parseString(Files.readString(root.resolve("owned.json"))).asJsonObject
                if (record.get("token")?.asString == token && record.get("script")?.asString == script.toString()) {
                    ProcessHandle.of(record.get("pid").asLong).ifPresent { handle ->
                        owned = OwnedEngine(handle, handle.info().startInstant().orElse(null), root, script, token)
                    }
                }
            }
            owned?.killExact()
            if (disposed.isDone) runCatching { disposed.get(1, TimeUnit.SECONDS) }
            runCatching { client?.dispose() }
            writer?.join(1_000)
            settings.goExecutable = originalGo
            if (previousWorkspace == null) System.clearProperty("openidea.workspace") else System.setProperty("openidea.workspace", previousWorkspace)
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    private fun insidePipeWrite(thread: Thread): Boolean {
        val stack = thread.stackTrace
        return stack.any { it.className == EngineClient::class.java.name && it.methodName.startsWith("requestRaw") } &&
            stack.any { it.className == "java.io.FileOutputStream" && it.methodName.startsWith("write") ||
                it.className.contains("ProcessPipeOutputStream") && it.methodName.startsWith("write") }
    }

    private data class OwnedEngine(val handle: ProcessHandle, val start: Instant?, val root: Path, val script: Path, val token: String) {
        fun killExact() {
            if (!handle.isAlive) return
            val record = JsonParser.parseString(Files.readString(root.resolve("owned.json"))).asJsonObject
            check(record.get("pid").asLong == handle.pid() && record.get("token").asString == token && record.get("script").asString == script.toString())
            val info = handle.info()
            check(start == null || info.startInstant().orElse(null) == start) { "The fixture PID was reused" }
            check(start != null || info.commandLine().map { it.contains(script.toString()) }.orElse(false) ||
                info.arguments().map { arguments -> arguments.any { it == script.toString() } }.orElse(false)) {
                "The live fixture process identity cannot be established"
            }
            info.commandLine().ifPresent { command -> check(command.contains(script.toString())) { "The fixture command changed" } }
            handle.destroyForcibly()
            handle.onExit().get(2, TimeUnit.SECONDS)
        }
    }
}
