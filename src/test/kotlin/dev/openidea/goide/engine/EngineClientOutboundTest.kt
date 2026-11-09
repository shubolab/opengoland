package dev.openidea.goide.engine

import com.google.gson.JsonObject
import com.intellij.openapi.project.Project
import com.intellij.testFramework.LightPlatformTestCase
import dev.openidea.goide.settings.GoProjectSettings
import dev.openidea.goide.settings.GoToolchain
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Real engine and protocol, not an echo process: rejecting one frame must preserve other work. */
class EngineClientOutboundTest : LightPlatformTestCase() {
    override fun runInDispatchThread() = false

    fun testOversizedUtf8RequestLeavesTheSameEngineAndAnExistingTaskUsable() = fixture { f ->
        f.client.request("project/info").get(15, TimeUnit.SECONDS)
        val task = "retained-task"
        val targets = f.client.request("project/taskTargets", JsonObject().apply {
            addProperty("command", "generate"); addProperty("contextPath", f.root.resolve("probe_test.go").toString())
            addProperty("operationId", "inspect-retained")
        }).get(15, TimeUnit.SECONDS).asJsonObject
        f.client.request("task/prepare", JsonObject().apply {
            addProperty("id", task); addProperty("command", "generate"); addProperty("operationId", "prepare-retained")
            add("targetSelection", JsonObject().apply {
                add("inspectionId", targets.get("inspectionId")); add("targetId", targets.get("defaultTargetId"))
            })
        }).get(15, TimeUnit.SECONDS)
        assertTrue(active(f.client).contains(task))
        val huge = JsonObject().apply {
            addProperty("id", "rejected-task")
            addProperty("payload", "😀".repeat(LIMIT / 4))
        }
        assertTrue("UTF-16 char counts cannot enforce the UTF-8 frame limit", huge.toString().length < LIMIT)
        rejected(f.client.request("task/prepare", huge))
        assertTrue("An oversized request must not terminate the shared engine", f.child.isAlive)
        assertSame(f.child, child(f.client))
        assertEquals(setOf(task), active(f.client).toSet())
        assertTrue(pending(f.client).isEmpty())
        f.client.request("project/info").get(15, TimeUnit.SECONDS)
        assertTrue(f.stopped.isEmpty())
        f.client.request("task/resume", JsonObject().apply { addProperty("id", task) }).get(15, TimeUnit.SECONDS)
        assertEquals(0, f.taskExit.get(20, TimeUnit.SECONDS).get("code").asInt)
        assertTrue(active(f.client).isEmpty())
        assertTrue(pending(f.client).isEmpty())
        assertSame(f.child, child(f.client))
    }

    fun testCompleteEnvelopeBoundaryIsInclusiveAndRejectionWritesNoPartialFrame() = fixture { f ->
        f.client.request("project/info").get(15, TimeUnit.SECONDS)
        val nextId = sequence(f.client).get() + 1
        val empty = JsonObject().apply { addProperty("payload", "") }
        val overhead = packet(nextId, empty).toByteArray(Charsets.UTF_8).size
        val exact = JsonObject().apply { addProperty("payload", "x".repeat(LIMIT - overhead)) }
        assertEquals(LIMIT, packet(nextId, exact).toByteArray(Charsets.UTF_8).size)
        f.client.request("project/info", exact).get(20, TimeUnit.SECONDS)
        val oversized = JsonObject().apply { addProperty("payload", "x".repeat(LIMIT - overhead + 1)) }
        assertTrue("Checking only params would miss the envelope overhead", oversized.toString().toByteArray(Charsets.UTF_8).size < LIMIT)
        rejected(f.client.request("project/info", oversized))
        assertTrue(pending(f.client).isEmpty())
        assertTrue(active(f.client).isEmpty())
        f.client.request("project/info").get(15, TimeUnit.SECONDS)
        assertSame(f.child, child(f.client))
        assertTrue(f.child.isAlive)
        assertTrue(f.stopped.isEmpty())
    }

    private fun rejected(request: CompletableFuture<*>) {
        val error = try { request.get(15, TimeUnit.SECONDS); null } catch (failure: java.util.concurrent.ExecutionException) { failure.cause }
        assertNotNull("Oversized request must fail locally", error)
        assertTrue("A clear outbound error is required, got: $error", error!!.message.orEmpty().contains("16 MiB") && error.message.orEmpty().contains("UTF-8"))
    }

    private class Fixture(val root: Path, val client: EngineClient, val child: Process,
        val stopped: CopyOnWriteArrayList<JsonObject>, val taskExit: CompletableFuture<JsonObject>)

    private fun fixture(run: (Fixture) -> Unit) {
        val root = Files.createTempDirectory("open-goland-outbound-frame-空 格😀-").toRealPath()
        val before = System.getProperty("openidea.workspace")
        var client: EngineClient? = null
        var owned: Process? = null
        try {
            val configured = System.getProperty("opengoland.testEngine")?.let(Path::of)
            val source = configured ?: Path.of(System.getProperty("user.dir"), "engine/bin/opengoland-engine" + if (System.getProperty("os.name").startsWith("Windows")) ".exe" else "")
            check(Files.isExecutable(source)) { "Build the real Go engine before running outbound transport integration" }
            val executable = root.resolve("engine/bin/${source.fileName}")
            Files.createDirectories(executable.parent); Files.copy(source, executable)
            check(executable.toFile().setExecutable(true))
            Files.writeString(root.resolve("go.mod"), "module example.test/frame\n\ngo 1.24\n")
            Files.writeString(root.resolve("probe_test.go"), "package frame\nimport \"testing\"\nfunc TestPreserved(t *testing.T) {}\n")
            val settings = GoProjectSettings().apply { state.apply {
                goExecutable = GoToolchain.resolve("go", System.getProperty("opengoland.testGo") ?: System.getenv("OPENGOLAND_GO_TEST").orEmpty()) ?: error("Go SDK required")
                environment = "GOENV=off\nGOPROXY=off\nGOSUMDB=off\nGOTOOLCHAIN=local\nGOTELEMETRY=off\nGOWORK=off\nCGO_ENABLED=0"
            } }
            val owner = Proxy.newProxyInstance(Project::class.java.classLoader, arrayOf(Project::class.java)) { proxy, method, args ->
                when (method.name) {
                    "getBasePath" -> root.toString()
                    "getService" -> if (args?.firstOrNull() == GoProjectSettings::class.java) settings else null
                    "isDisposed", "isDefault" -> false
                    "toString" -> "owned outbound engine project"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    else -> null
                }
            } as Project
            System.setProperty("openidea.workspace", root.toString())
            val engine = EngineClient(owner).also { client = it }
            val stopped = CopyOnWriteArrayList<JsonObject>()
            val exit = CompletableFuture<JsonObject>()
            engine.listen { method, params ->
                if (method == "engine/stopped") stopped += params
                if (method == "task/exit" && params.get("id")?.asString == "retained-task") exit.complete(params)
            }
            engine.connect().get(20, TimeUnit.SECONDS)
            val process = child(engine)!!; owned = process
            println("OUTBOUND_ENGINE_PID=${process.pid()}")
            run(Fixture(root, engine, process, stopped, exit))
        } finally {
            client?.dispose()
            owned?.let { assertTrue("Only this test's engine is owned and must exit", it.waitFor(10, TimeUnit.SECONDS)) }
            if (before == null) System.clearProperty("openidea.workspace") else System.setProperty("openidea.workspace", before)
            root.toFile().deleteRecursively()
        }
    }

    private fun child(client: EngineClient): Process? = EngineClient::class.java.getDeclaredField("process").apply { isAccessible = true }.get(client) as? Process
    private fun pending(client: EngineClient) = EngineClient::class.java.getDeclaredField("pending").apply { isAccessible = true }.get(client) as Map<*, *>
    private fun active(client: EngineClient) = EngineClient::class.java.getDeclaredField("activeTasks").apply { isAccessible = true }.get(client) as Set<*>
    private fun sequence(client: EngineClient) = EngineClient::class.java.getDeclaredField("sequence").apply { isAccessible = true }.get(client) as AtomicLong
    private fun packet(id: Long, params: JsonObject) = JsonObject().apply {
        addProperty("jsonrpc", "2.0"); addProperty("id", id); addProperty("method", "project/info"); add("params", params)
    }.toString()
    companion object { private const val LIMIT = 16 * 1024 * 1024 }
}
