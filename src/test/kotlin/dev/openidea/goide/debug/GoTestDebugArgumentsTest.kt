package dev.openidea.goide.debug

import dev.openidea.goide.run.GoRunKind
import dev.openidea.goide.run.GoRunParameters
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class GoTestDebugArgumentsTest {
    @Test fun runOnlyMetadataProjectionPreservesValuesAndExplicitRuntimeBoundaries() {
        assertEquals(listOf("-tags", "alpha", "-count=1"), GoTestDebugArguments.runMetadataFlags(
            listOf("-c=false", "-o", "owned output", "-exec=owned wrapper", "-tags", "alpha", "-count=1")))
        assertEquals(listOf("-tags", "-o", "-ldflags", "-exec", "-timeout", "-c"), GoTestDebugArguments.runMetadataFlags(
            listOf("-tags", "-o", "-ldflags", "-exec", "-timeout", "-c")))
        for (boundary in listOf("-args", "--args", "--")) {
            val raw = listOf(boundary, "-o", "custom", "-exec=custom", "-c=false")
            assertEquals(raw, GoTestDebugArguments.runMetadataFlags(raw))
        }
        assertEquals(emptyList(), GoTestDebugArguments.runMetadataFlags(listOf("-c", "--c=0", "-o=owned", "--exec", "owned")))
        for (invalid in listOf(listOf("-o"), listOf("-exec"), listOf("-c=invalid")))
            assertFailsWith<IllegalArgumentException> { GoTestDebugArguments.runMetadataFlags(invalid) }
        // Its existing Debug rejection remains authoritative, independently of Run metadata.
        assertFailsWith<IllegalArgumentException> { GoTestDebugArguments.create(listOf("-o=owned"), emptyList()) }
    }

    @Test fun explicitRuntimeSelectorUsesKnownFlagNormalizationLastValueAndBinaryBoundaries() {
        fun selected(vararg arguments: String) = GoTestDebugArguments.explicitSelector(arguments.toList(), "run")
        assertFalse(selected("-timeout", "20s", "-count=2").declared)
        assertEquals("^A$", selected("-v", "--run", "^A$", "-timeout=20s").value)
        assertEquals("^B$", selected("-run=^A$", "-test.run", "^B$").value)
        assertEquals("", selected("-run=").value)
        assertFalse(selected("-args", "-run", "custom-binary-value").declared)
        assertEquals("^B$", selected("-args", "-test.run=^B$").value)
        assertFalse(selected("--", "-test.run=^B$").declared)
        assertFalse(selected("-args", "--", "-test.run=^B$").declared)
        assertFalse(selected("-bench=BenchmarkB").declared)
        assertTrue(selected("-custom", "value", "-run=^B$").uncertain)
        assertTrue(selected("-run").uncertain)
        assertTrue(selected("-run").declared)
    }

    @Test fun compileAndTestFlagsRetainTheirValuesAndLastFlagWinsOrder() {
        val plan = GoTestDebugArguments.create(
            listOf("-race", "-tags", "unit", "-ldflags", "-v", "-count=1", "-timeout", "20s"),
            listOf("-test.count=2", "-bench", "BenchmarkCart", "-benchmem=false", "-v", "-parallel=1"), "^TestCart$")
        assertEquals(listOf("-race", "-tags", "unit", "-ldflags", "-v"), plan.buildFlags)
        assertEquals(listOf("-test.count=1", "-test.timeout", "20s", "-test.run", "^TestCart$",
            "-test.count=2", "-test.bench", "BenchmarkCart", "-test.benchmem=false", "-test.v", "-test.parallel=1"), plan.arguments)
    }

    @Test fun argsEscapePreservesCustomFlagsWithoutRewriting() {
        val plan = GoTestDebugArguments.create(emptyList(), listOf("-timeout=20s", "-args", "-v", "-timeout", "custom", "--literal"))
        assertEquals(emptyList(), plan.buildFlags)
        assertEquals(listOf("-test.timeout=20s", "-v", "-timeout", "custom", "--literal"), plan.arguments)
        assertEquals(listOf("--", "-timeout", "custom"), GoTestDebugArguments.create(emptyList(), listOf("--", "-timeout", "custom")).arguments)
    }

    @Test fun coverageProfileEnablesInstrumentationAndManagedFlagsAreRejected() {
        val plan = GoTestDebugArguments.create(listOf("-covermode=atomic"), listOf("-coverprofile", "my coverage.out"))
        assertEquals(listOf("-covermode=atomic", "-cover"), plan.buildFlags)
        assertEquals(listOf("-test.coverprofile", "my coverage.out"), plan.arguments)
        for (flag in listOf("-json", "-json=true", "-c", "-o=out.test", "-exec=wrapper")) {
            assertFailsWith<IllegalArgumentException> { GoTestDebugArguments.create(listOf(flag), emptyList()) }
        }
        assertFailsWith<IllegalArgumentException> { GoTestDebugArguments.create(listOf("-timeout"), emptyList()) }
        assertFailsWith<IllegalArgumentException> { GoTestDebugArguments.create(listOf("-ldflags"), emptyList()) }
    }

    @Test fun advancedTestOptionsUseTheSameRewriteAsNativeTestConfigurations() {
        val root = Files.createTempDirectory("openidea-debug-advanced")
        try {
            val launch = GoDebugLaunch.fromOptions(GoDebugOptions().apply {
                mode = "test"; program = "."; workingDirectory = root.toString()
                buildFlags = "-race -count=1"; arguments = "-timeout '20s' -test.run=TestCart"
            }, root.toString())
            assertEquals(listOf("-race"), launch.buildFlags)
            assertEquals(listOf("-test.count=1", "-test.timeout", "20s", "-test.run=TestCart"), launch.arguments)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun realCompiledTestHonorsSelectionCountTimeoutCustomArgsAndCoverage() {
        val root = Files.createTempDirectory("openidea-debug-real-flags").toRealPath()
        try {
            Files.writeString(root.resolve("go.mod"), "module example.com/debugfixture\n\ngo 1.20\n")
            Files.writeString(root.resolve("value.go"), "package debugfixture\nfunc Value() int { return 37 }\n")
            Files.writeString(root.resolve("value_test.go"), """
                package debugfixture
                import ("flag"; "testing")
                var label = flag.String("label", "", "test argument")
                func TestCount(t *testing.T) {
                    if *label != "-timeout" { t.Fatalf("custom argument changed: %q", *label) }
                    if Value() != 37 { t.Fatal("wrong value") }
                    t.Log("GO_DEBUG_ITERATION")
                }
                func TestExcluded(t *testing.T) { t.Fatal("test selection was lost") }
            """.trimIndent())
            val launch = GoDebugLaunch.fromRun(GoRunKind.TEST, GoRunParameters(
                target = ".", workingDirectory = root.toString(), buildFlags = "-tags=unit -count=1",
                testPattern = "^TestCount$", arguments = "-timeout 20s -count=2 -v -coverprofile 'debug coverage.out' -args -label '-timeout'",
            ), root.toString())
            val binary = root.resolve(if (System.getProperty("os.name").startsWith("Windows")) "fixture.test.exe" else "fixture.test")
            run(root, listOf("go", "test", "-c") + launch.buildFlags + listOf("-o", binary.toString(), "."))
            val output = run(root, listOf(binary.toString()) + launch.arguments)
            assertEquals(2, Regex("GO_DEBUG_ITERATION").findAll(output).count(), output)
            assertTrue("PASS" in output, output)
            val profile = Files.readString(root.resolve("debug coverage.out"))
            assertTrue(profile.startsWith("mode: set\n"), profile)
            assertTrue("example.com/debugfixture/value.go:" in profile, profile)
        } finally { root.toFile().deleteRecursively() }
    }

    private fun run(root: Path, command: List<String>): String {
        val output = Files.createTempFile(root, ".command-", ".log")
        val builder = ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).redirectOutput(output.toFile())
        builder.environment().putAll(mapOf("GOWORK" to "off", "GOTOOLCHAIN" to "local", "GOPROXY" to "off"))
        val process = builder.start()
        try {
            if (!process.waitFor(45, TimeUnit.SECONDS)) {
                process.descendants().use { descendants -> descendants.forEach { it.destroyForcibly() } }
                process.destroyForcibly()
                error("Go debug argument fixture timed out")
            }
            val text = Files.readString(output)
            assertEquals(0, process.exitValue(), text)
            return text
        } finally { Files.deleteIfExists(output) }
    }
}
