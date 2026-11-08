package dev.openidea.goide.run

import dev.openidea.goide.debug.GoDebugLaunch
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GoTestWorkflowTest {
    @Test fun benchmarkSelectorsAndMemoryAreNativeFields() {
        val values = GoRunParameters(testMode = GoTestMode.BENCHMARK, benchmarkPattern = "^BenchmarkEcho$/^small$",
            benchmarkTime = "3x", buildFlags = "-race", arguments = "-timeout 20s")
        assertEquals(listOf("test", "-json", "-tags=fixture", "-race", "-run", "^$", "-bench", "^BenchmarkEcho$/^small$",
            "-benchtime", "3x", "-benchmem", ".", "-timeout", "20s"), values.commandArguments(GoRunKind.TEST, "fixture"))
        assertFalse(values.copy(benchmarkMemory = false).commandArguments(GoRunKind.TEST, "").contains("-benchmem"))
        assertFailsWith<IllegalArgumentException> { values.commandArguments(GoRunKind.TEST, "", "/tmp/coverage") }
    }

    @Test fun fuzzSelectorsSupportBoundedAndUntilStoppedRuns() {
        val values = GoRunParameters(testMode = GoTestMode.FUZZ, fuzzPattern = "^FuzzEcho$", fuzzTime = "100x",
            fuzzMinimizeTime = "0", fuzzParallelism = "1")
        assertEquals(listOf("test", "-json", "-run", "^$", "-fuzz", "^FuzzEcho$", "-fuzztime", "100x",
            "-fuzzminimizetime", "0", "-parallel", "1", "."), values.commandArguments(GoRunKind.TEST, ""))
        val unlimited = values.copy(fuzzTime = "", fuzzMinimizeTime = "", fuzzParallelism = "").commandArguments(GoRunKind.TEST, "")
        assertFalse(unlimited.contains("-fuzztime"))
        for (target in listOf("./...", "example.org/a example.org/b")) {
            assertFailsWith<IllegalArgumentException> { values.copy(target = target).commandArguments(GoRunKind.TEST, "") }
        }
        assertFailsWith<IllegalArgumentException> { values.copy(fuzzPattern = "").commandArguments(GoRunKind.TEST, "") }
    }

    @Test fun fieldValidationDoesNotSilentlyAcceptInvalidWorkOrOverrideTheWorkflow() {
        for (value in listOf("", "0", "0s", "0x", "-1s", "1.5x", "1 s", "0.6ns0.6ns", "nan", "100000000000000000000h"))
            assertFailsWith<IllegalArgumentException> { GoTestWorkflow.validateDurationOrCount(value, "Benchmark time") }
        for (value in listOf("1s", "1m30s", ".5s", "1.5ms", "1µs", "1μs", "+1s", "100x"))
            GoTestWorkflow.validateDurationOrCount(value, "Benchmark time")
        for (value in listOf("0", "+0", "-0", "0s", "0x")) GoTestWorkflow.validateDurationOrCount(value, "Minimization", allowZero = true)
        assertFailsWith<IllegalArgumentException> {
            GoRunParameters(buildFlags = "-args -custom=ok").commandArguments(GoRunKind.TEST, "")
        }
        for (flag in listOf("-bench=.", "-test.benchtime=2x", "-args -fuzz=Other", "-run .")) {
            assertFailsWith<IllegalArgumentException> {
                GoRunParameters(testMode = GoTestMode.BENCHMARK, arguments = flag).commandArguments(GoRunKind.TEST, "")
            }
        }
        assertFailsWith<IllegalArgumentException> {
            GoRunParameters(testMode = GoTestMode.FUZZ, fuzzPattern = "Fuzz", fuzzParallelism = "0").commandArguments(GoRunKind.TEST, "")
        }
    }

    @Test fun debugRunsBenchmarksAndOnlyTheFuzzSeedCorpus() {
        val root = Files.createTempDirectory("opengoland-test-workflow-debug-")
        try {
            val bench = GoDebugLaunch.fromRun(GoRunKind.TEST, GoRunParameters(testMode = GoTestMode.BENCHMARK,
                benchmarkPattern = "^BenchmarkEcho$", benchmarkTime = "2x", workingDirectory = root.toString()), root.toString())
            assertTrue(bench.arguments.containsAll(listOf("-test.run", "^$", "-test.bench", "^BenchmarkEcho$", "-test.benchtime", "2x", "-test.benchmem")))
            val fuzz = GoDebugLaunch.fromRun(GoRunKind.TEST, GoRunParameters(testMode = GoTestMode.FUZZ,
                fuzzPattern = "^FuzzEcho$", workingDirectory = root.toString()), root.toString())
            assertEquals(listOf("-test.run", "^FuzzEcho$"), fuzz.arguments)
            assertFalse(fuzz.arguments.any { it.startsWith("-test.fuzz") })
        } finally { Files.deleteIfExists(root) }
    }

    @Test fun failedFuzzCasesReproduceTheSeedAndBenchmarkRetriesKeepTheirOwnSelector() {
        val fuzz = GoRunParameters(testMode = GoTestMode.FUZZ, fuzzPattern = "^FuzzEcho$", fuzzTime = "30s", fuzzParallelism = "1")
        val retry = GoTestRerunPlan.create(mapOf("example.org/p" to listOf("FuzzEcho/a[1]")), fuzz).single().parameters
        assertEquals(GoTestMode.TEST, retry.testMode)
        assertEquals("^FuzzEcho$/^a\\[1\\]$", retry.testPattern)
        assertFalse(retry.commandArguments(GoRunKind.TEST, "").contains("-fuzz"))
        val benchmark = GoRunParameters(testMode = GoTestMode.BENCHMARK, benchmarkTime = "5x", benchmarkMemory = false)
        val benchRetry = GoTestRerunPlan.create(mapOf("example.org/p" to listOf("BenchmarkEcho/small")), benchmark).single().parameters
        assertEquals(GoTestMode.BENCHMARK, benchRetry.testMode)
        assertEquals("^BenchmarkEcho$/^small$", benchRetry.benchmarkPattern)
        assertEquals("5x", benchRetry.benchmarkTime)
        assertFalse(benchRetry.benchmarkMemory)
        val pretest = GoTestRerunPlan.create(mapOf("example.org/p" to listOf("TestSetup")), benchmark).single().parameters
        assertEquals(GoTestMode.TEST, pretest.testMode)
    }

    @Test fun cancellationBeforeTerminalJsonDoesNotCreateNewFailures() {
        val output = GoTestOutput("/owned")
        output.feed("{\"Action\":\"run\",\"Package\":\"p\",\"Test\":\"FuzzEcho\"}\n")
        output.requestCancellation()
        val events = output.feed("{\"Action\":\"fail\",\"Package\":\"p\",\"Test\":\"FuzzEcho\"}\n{\"Action\":\"fail\",\"Package\":\"p\"}\n") + output.finish(130)
        assertTrue(output.failures().isEmpty())
        assertTrue(events.any { it.contains("testIgnored") && it.contains("Go test run was stopped") })
        assertFalse(events.any { it.startsWith("##teamcity[testFailed ") })
    }
}
