package dev.openidea.goide.run

import com.google.gson.JsonParser
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.testFramework.LightPlatformTestCase
import dev.openidea.goide.settings.GoToolchain
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** Actual mutation JSON has only the function as Test; its output supplies the saved seed. */
class GoFuzzFailureRerunToolTest : LightPlatformTestCase() {
    override fun runInDispatchThread() = false

    fun testMutationFailureRetriesOnlyItsNewSavedInputRatherThanAllFunctionSeeds() = fixture(failing = true) { root, go ->
        val pkg = module(root)
        val original = GoRunParameters(testMode = GoTestMode.FUZZ, fuzzPattern = "^FuzzExact$", fuzzTime = "3s",
            fuzzMinimizeTime = "0", fuzzParallelism = "1", workingDirectory = root.toString())
        val mutation = run(root, listOf(go) + original.commandArguments(GoRunKind.TEST, ""))
        assertEquals(mutation.text, 1, mutation.exit)
        val corpus = Files.list(root.resolve("testdata/fuzz/FuzzExact")).use { paths ->
            paths.filter { it.fileName.toString() != "manual-case.json" }.toList().single()
        }
        val expected = "FuzzExact/${corpus.fileName}"
        assertEquals(listOf("FuzzExact"), runNames(mutation.text))
        val failureWitness = Files.readAllBytes(root.resolve("executed.txt"))
        val output = GoTestOutput(root.toString(), mapOf(pkg to root.toString()))
        output.feed(mutation.text); output.finish(mutation.exit)
        assertEquals(mapOf(pkg to listOf(expected)), output.failures())
        val retry = GoTestRerunPlan.create(output.failures(), original).single().parameters
        assertEquals(GoTestMode.TEST, retry.testMode)
        assertEquals("^FuzzExact$/^${corpus.fileName}$", retry.testPattern)
        Files.delete(root.resolve("executed.txt"))
        val replay = run(root, listOf(go) + retry.commandArguments(GoRunKind.TEST, ""))
        assertEquals(replay.text, 1, replay.exit)
        assertEquals(listOf("FuzzExact", expected), runNames(replay.text))
        assertTrue(failureWitness.contentEquals(Files.readAllBytes(root.resolve("executed.txt"))))
        assertFalse(replay.text.contains("fuzz:"))
        val replayOutput = GoTestOutput(root.toString(), mapOf(pkg to root.toString()))
        replayOutput.feed(replay.text); replayOutput.finish(replay.exit)
        assertEquals(mapOf(pkg to listOf(expected)), replayOutput.failures())
    }

    fun testStoppingActualFuzzCannotReportTheStillRunningFunctionAsPassed() = fixture(failing = false) { root, go ->
        val values = GoRunParameters(testMode = GoTestMode.FUZZ, fuzzPattern = "^FuzzExact$", fuzzTime = "",
            fuzzMinimizeTime = "0", fuzzParallelism = "1", workingDirectory = root.toString())
        val command = GeneralCommandLine(listOf(go) + values.commandArguments(GoRunKind.TEST, ""))
            .withWorkingDirectory(root).withEnvironment(toolEnvironment(go))
        val handler = GoTestProcessHandler(command, root.toString(), mapOf(module(root) to root.toString()))
        handler.setShouldDestroyProcessRecursively(true)
        val started = CompletableFuture<Unit>()
        val ended = CompletableFuture<Unit>()
        val events = CopyOnWriteArrayList<String>()
        handler.addProcessListener(object : ProcessAdapter() {
            override fun onTextAvailable(event: ProcessEvent, outputType: com.intellij.openapi.util.Key<*>) {
                events += event.text
                if (event.text.contains("now fuzzing with")) started.complete(Unit)
            }
            override fun processTerminated(event: ProcessEvent) { ended.complete(Unit) }
        })
        handler.startNotify()
        try {
            started.get(45, TimeUnit.SECONDS)
            val ownedChildren = handler.process.toHandle().descendants().use { it.toList() }
            assertFalse(ownedChildren.isEmpty())
            handler.destroyProcess(); ended.get(15, TimeUnit.SECONDS)
            for (child in ownedChildren) child.onExit().get(5, TimeUnit.SECONDS)
            val text = events.joinToString("")
            assertTrue(text, text.contains("testIgnored name='FuzzExact'"))
            assertFalse(text, text.contains("outcome='pass'"))
            assertTrue(handler.output.failures().toString(), handler.output.failures().isEmpty())
            assertTrue(handler.cancellationRequested)
            assertFalse(handler.process.isAlive)
        } finally {
            if (!handler.isProcessTerminated) {
                handler.destroyProcess()
                if (!handler.waitFor(5_000)) handler.killProcess()
                handler.waitFor(5_000)
            }
        }
    }

    private data class Result(val exit: Int, val text: String)
    private fun run(root: Path, arguments: List<String>): Result {
        val capture = Files.createTempFile(root, "owned-fuzz-retry-command-", ".log")
        val child = ProcessBuilder(arguments).directory(root.toFile()).redirectErrorStream(true).redirectOutput(capture.toFile()).apply {
            environment().putAll(toolEnvironment(arguments.first()))
        }.start()
        try {
            assertTrue("Owned Go fixture timed out", child.waitFor(45, TimeUnit.SECONDS))
            return Result(child.exitValue(), Files.readString(capture))
        } finally {
            if (child.isAlive) {
                child.descendants().use { it.toList() }.asReversed().forEach { it.destroyForcibly() }
                child.destroyForcibly(); child.waitFor(5, TimeUnit.SECONDS)
            }
        }
    }

    private fun runNames(text: String) = text.lineSequence().mapNotNull { line ->
        runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull()
    }.filter { it.get("Action")?.asString == "run" }.mapNotNull { it.get("Test")?.asString }.toList()

    private fun toolEnvironment(go: String) = mapOf("GOROOT" to Path.of(go).toRealPath().parent.parent.toString(), "GOOS" to "", "GOARCH" to "", "GOWORK" to "off", "GOPROXY" to "off", "GOTOOLCHAIN" to "local", "GOTELEMETRY" to "off")

    private fun fixture(failing: Boolean, action: (Path, String) -> Unit) {
        val root = Files.createTempDirectory("opengoland-fuzz-failure-rerun-").toRealPath()
        val go = GoToolchain.resolve("go", System.getProperty("opengoland.testGo") ?: System.getenv("OPENGOLAND_GO_TEST").orEmpty()) ?: throw AssertionError("Go is required for this actual-tool regression")
        try {
            // Go's fuzz cache is package/function keyed. A unique owned module
            // prevents a previous passing fixture's cache becoming baseline input.
            Files.writeString(root.resolve("go.mod"), "module ${module(root)}\n\ngo 1.24.0\n")
            Files.writeString(root.resolve("fuzz_test.go"), """
                package fuzzfailureretry
                import ("os"; "testing")
                func TestUnrelated(t *testing.T) { t.Fatal("ordinary test must not run") }
                func FuzzExact(f *testing.F) {
                    f.Add("start")
                    f.Fuzz(func(t *testing.T, input string) {
                        if err := os.WriteFile("executed.txt", []byte(input), 0600); err != nil { t.Fatal(err) }
                        if $failing && input != "start" { t.Fatal("mutated failure") }
                    })
                }
            """.trimIndent())
            val directory = root.resolve("testdata/fuzz/FuzzExact")
            Files.createDirectories(directory)
            Files.writeString(directory.resolve("manual-case.json"), "go test fuzz v1\nstring(\"start\")\n")
            action(root, go)
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    private fun module(root: Path) = "example.org/fuzzfailureretry/${root.fileName}"
}
