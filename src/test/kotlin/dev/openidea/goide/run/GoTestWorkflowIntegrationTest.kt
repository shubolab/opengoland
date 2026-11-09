package dev.openidea.goide.run

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.testFramework.LightPlatformTestCase
import dev.openidea.goide.debug.GoDebugLaunch
import dev.openidea.goide.settings.GoToolchain
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** Actual selected Go toolchain and native result adapter; no mocked JSON completion protocol. */
class GoTestWorkflowIntegrationTest : LightPlatformTestCase() {
    override fun runInDispatchThread() = false

    fun testActualBenchmarkFuzzAndCompiledSeedDebugUseTheSelectedTargets() {
        withFixture { root, go ->
            val benchmark = GoRunParameters(testMode = GoTestMode.BENCHMARK,
                benchmarkPattern = "^BenchmarkEcho$/^small$", benchmarkTime = "3x", workingDirectory = root.toString())
            val bench = run(root, listOf(go) + benchmark.commandArguments(GoRunKind.TEST, ""), go)
            assertEquals(bench.text, 0, bench.exit)
            assertTrue(bench.text.contains("ns/op") && bench.text.contains("B/op") && bench.text.contains("allocs/op"))
            assertFalse(bench.text.contains("BenchmarkOther"))
            assertFalse(bench.text.contains("TestOrdinary"))
            val output = GoTestOutput(root.toString(), mapOf("example.org/workflowfixture" to root.toString()))
            val events = output.feed(bench.text) + output.finish(bench.exit)
            assertTrue(output.failures().toString(), output.failures().isEmpty())
            assertTrue(events.any { it.contains("nodeId='go-test:example.org/workflowfixture#BenchmarkEcho/small:1'") && it.contains("outcome='pass'") })
            assertFalse(events.any { it.startsWith("##teamcity[testFailed ") })

            val fuzz = GoRunParameters(testMode = GoTestMode.FUZZ, fuzzPattern = "^FuzzEcho$", fuzzTime = "100x",
                fuzzMinimizeTime = "0", fuzzParallelism = "1", arguments = "-v", workingDirectory = root.toString())
            val fuzzed = run(root, listOf(go) + fuzz.commandArguments(GoRunKind.TEST, ""), go)
            assertEquals(fuzzed.text, 0, fuzzed.exit)
            assertTrue(fuzzed.text.contains("fuzz:") && fuzzed.text.contains("FuzzEcho"))
            assertFalse(fuzzed.text.contains("TestOrdinary"))
            val fuzzOutput = GoTestOutput(root.toString())
            fuzzOutput.feed(fuzzed.text); fuzzOutput.finish(fuzzed.exit)
            assertTrue(fuzzOutput.failures().isEmpty())

            // This is the binary Delve's test mode launches. Its seed execution
            // must work without go test's fuzz-worker instrumentation.
            val binary = root.resolve(if (System.getProperty("os.name").startsWith("Windows")) "fixture.test.exe" else "fixture.test")
            val compiled = run(root, listOf(go, "test", "-c", "-o", binary.toString(), "."), go)
            assertEquals(compiled.text, 0, compiled.exit)
            val seedLaunch = GoDebugLaunch.fromRun(GoRunKind.TEST, fuzz, root.toString())
            val seed = run(root, listOf(binary.toString()) + seedLaunch.arguments)
            assertEquals(seed.text, 0, seed.exit)
            assertTrue(seed.text.contains("FuzzEcho/seed#0") && seed.text.contains("FuzzEcho/seed#1"))
            assertFalse(seed.text.contains("fuzz:"))
            val benchLaunch = GoDebugLaunch.fromRun(GoRunKind.TEST, benchmark, root.toString())
            val debugBench = run(root, listOf(binary.toString()) + benchLaunch.arguments)
            assertEquals(debugBench.text, 0, debugBench.exit)
            assertTrue(debugBench.text.contains("BenchmarkEcho/small") && debugBench.text.contains("allocs/op"))
            assertFalse(debugBench.text.contains("TestOrdinary"))
        }
    }

    fun testStoppingAnActualInfiniteFuzzerReapsWorkersAndClosesTheNativeTreeWithoutFailures() {
        withFixture { root, go ->
            val parameters = GoRunParameters(testMode = GoTestMode.FUZZ, fuzzPattern = "^FuzzEcho$",
                fuzzTime = "", fuzzParallelism = "1", fuzzMinimizeTime = "0")
            stopAfterOutput(root, go, parameters, "now fuzzing with")
        }
    }

    fun testStoppingANumericLoggingBenchmarkDoesNotReportItAsPassed() {
        withFixture { root, go ->
            val parameters = GoRunParameters(testMode = GoTestMode.BENCHMARK, benchmarkPattern = "^BenchmarkHang$", benchmarkTime = "1x")
            val events = stopAfterOutput(root, go, parameters, "still active")
            assertTrue(events.any { it.contains("testIgnored") && it.contains("BenchmarkHang") })
            assertFalse(events.any { it.contains("goTestLeafResult") && it.contains("BenchmarkHang") && it.contains("outcome='pass'") })
        }
    }

    private fun stopAfterOutput(root: Path, go: String, parameters: GoRunParameters, marker: String): List<String> {
            val line = GeneralCommandLine(listOf(go) + parameters.commandArguments(GoRunKind.TEST, ""))
                .withWorkingDirectory(root).withEnvironment(toolEnvironment(go))
            val handler = GoTestProcessHandler(line, root.toString(), mapOf("example.org/workflowfixture" to root.toString()))
            handler.setShouldDestroyProcessRecursively(true)
            val started = CompletableFuture<Unit>()
            val stopped = CompletableFuture<Unit>()
            val events = CopyOnWriteArrayList<String>()
            handler.addProcessListener(object : ProcessAdapter() {
                override fun onTextAvailable(event: ProcessEvent, outputType: com.intellij.openapi.util.Key<*>) {
                    events += event.text
                    if (event.text.contains(marker)) started.complete(Unit)
                }
                override fun processTerminated(event: ProcessEvent) { stopped.complete(Unit) }
            })
            handler.startNotify()
            try {
                started.get(45, TimeUnit.SECONDS)
                val ownedChildren = handler.process.toHandle().descendants().use { it.toList() }
                assertTrue("The regression must stop a real Go command with actual owned processes", ownedChildren.isNotEmpty())
                handler.destroyProcess()
                stopped.get(15, TimeUnit.SECONDS)
                for (child in ownedChildren) child.onExit().get(5, TimeUnit.SECONDS)
                assertTrue(handler.cancellationRequested)
                assertTrue(handler.output.failures().toString(), handler.output.failures().isEmpty())
                assertFalse(handler.process.isAlive)
                return events.toList()
            } finally {
                if (!handler.isProcessTerminated) {
                    handler.destroyProcess()
                    if (!handler.waitFor(5_000)) handler.killProcess()
                    handler.waitFor(5_000)
                }
            }
    }

    private data class Result(val exit: Int, val text: String)
    private fun run(root: Path, arguments: List<String>, go: String? = null): Result {
        val builder = ProcessBuilder(arguments).directory(root.toFile()).redirectErrorStream(true)
        builder.environment().putAll(toolEnvironment(go))
        val child = builder.start()
        val output = CompletableFuture.supplyAsync { child.inputStream.bufferedReader().use { it.readText() } }
        try {
            assertTrue("Owned Go fixture timed out: ${arguments.take(3)}", child.waitFor(90, TimeUnit.SECONDS))
            return Result(child.exitValue(), output.get(5, TimeUnit.SECONDS))
        } finally {
            if (child.isAlive) {
                child.toHandle().descendants().use { it.toList() }.asReversed().forEach { if (it.isAlive) it.destroyForcibly() }
                child.destroyForcibly(); child.waitFor(5, TimeUnit.SECONDS)
            }
        }
    }

    private fun toolEnvironment(go: String? = null) = mapOf("GOWORK" to "off", "GOPROXY" to "off", "GOTOOLCHAIN" to "local", "GOTELEMETRY" to "off") +
        (go?.let { mapOf("GOROOT" to Path.of(it).toRealPath().parent.parent.toString(), "GOOS" to "", "GOARCH" to "") } ?: emptyMap())

    private fun withFixture(test: (Path, String) -> Unit) {
        val root = Files.createTempDirectory("opengoland-real-test-workflows-").toRealPath()
        val go = GoToolchain.resolve("go", System.getProperty("opengoland.testGo") ?: System.getenv("OPENGOLAND_GO_TEST").orEmpty()) ?: throw AssertionError("The Go toolchain required by this repository is unavailable")
        try {
            Files.writeString(root.resolve("go.mod"), "module example.org/workflowfixture\n\ngo 1.24.0\n")
            Files.writeString(root.resolve("workflow_test.go"), """
                package workflowfixture
                import ("testing"; "fmt"; "time")
                var sink string
                func TestOrdinary(t *testing.T) { t.Fatal("unrelated unit test must not execute") }
                func BenchmarkEcho(b *testing.B) {
                    b.Run("small", func(b *testing.B) { for i := 0; i < b.N; i++ { sink = "中文 😀" } })
                    b.Run("large", func(b *testing.B) { for i := 0; i < b.N; i++ { sink = "a longer value" } })
                }
                func BenchmarkOther(b *testing.B) { b.Fatal("unselected benchmark executed") }
                func BenchmarkHang(b *testing.B) { fmt.Println(b.Name(), b.N, "still active"); for { time.Sleep(time.Second) } }
                func FuzzEcho(f *testing.F) {
                    f.Add("seed"); f.Add("中文 😀")
                    f.Fuzz(func(t *testing.T, value string) { sink = value })
                }
            """.trimIndent() + "\n")
            test(root, go)
        } finally {
            Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
}
