package dev.openidea.goide.run

import dev.openidea.goide.coverage.GoCoverageProfile
import dev.openidea.goide.coverage.GoCoverageService
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Exercises the native runner's exact argv, metadata and SM conversion with real Go subprocesses. */
class GoNativeCoverageIntegrationTest {
    @Test fun coverageRetainsWorkspaceScopeEnvironmentAndStructuredFailures() {
        val root = Files.createTempDirectory("opengoland-native-coverage").toRealPath()
        try {
            val first = Files.createDirectory(root.resolve("first"))
            val second = Files.createDirectory(root.resolve("second"))
            val modules = listOf("example.com/native/cart" to first, "example.com/native/cart/nested" to second)
            Files.writeString(root.resolve("go.work"), "go 1.20\nuse (\n ./first\n ./second\n)\n")
            modules.forEach { (name, directory) ->
                Files.writeString(directory.resolve("go.mod"), "module $name\n\ngo 1.20\n")
                Files.writeString(directory.resolve("cart.go"), "//go:build coverage_fixture\n\npackage cart\nfunc Price(selected bool) int { if selected { return 25 }; return 0 }\n")
                Files.writeString(directory.resolve("cart_test.go"), """
                    //go:build coverage_fixture

                    package cart
                    import (
                        "flag"
                        "os"
                        "testing"
                    )
                    var label = flag.String("label", "", "fixture label")
                    func TestCart(t *testing.T) {
                        t.Run("wanted", func(t *testing.T) {
                            if *label != "two products" || os.Getenv("IDE_TOKEN") != "token with spaces" { t.Fatal("configuration was lost") }
                            if Price(true) != 25 { t.Fatal("wrong price") }
                            if os.Getenv("IDE_FAIL") == "yes" { t.Error("requested fixture failure") }
                        })
                        t.Run("excluded", func(t *testing.T) { t.Fatal("unselected subtest ran") })
                    }
                    func TestExcluded(t *testing.T) { t.Fatal("unselected top-level test ran") }
                """.trimIndent() + "\n")
            }
            val values = GoRunParameters(target = "./first/... ./second/...", workingDirectory = root.toString(),
                buildFlags = "-count=1 -covermode=count", testPattern = "^TestCart$/^wanted$",
                arguments = "-timeout 20s -args -label='two products'", environment = linkedMapOf("IDE_TOKEN" to "token with spaces"))
            val metadata = GoPackageDirectories.parseMetadata(run(root, values.environment,
                listOf("list", "-e", "-tags=coverage_fixture", "-json=ImportPath,Dir,Module", "./first/...", "./second/...")).second)
            assertEquals(modules.map { it.first }.toSet(), metadata.modules.map { it.path }.toSet())
            for (failing in listOf(false, true)) {
                val profile = root.resolve("native coverage $failing.out")
                val (code, json) = run(root, values.environment + ("IDE_FAIL" to if (failing) "yes" else "no"),
                    values.commandArguments(GoRunKind.TEST, "coverage_fixture", profile.toString()))
                assertEquals(if (failing) 1 else 0, code, json)
                val converter = GoTestOutput(root.toString(), metadata.directories)
                val events = (converter.feed(json) + converter.finish(code)).joinToString("")
                assertEquals(2, Regex("##teamcity\\[testStarted name='wanted'").findAll(events).count(), events)
                assertFalse(events.contains("name='excluded'"), events)
                assertFalse(events.contains("name='TestExcluded'"), events)
                assertEquals(failing, events.contains("##teamcity[testFailed"), events)
                if (failing) {
                    assertEquals(modules.associate { it.first to listOf("TestCart/wanted") }, converter.failures())
                    modules.forEach { (_, directory) -> assertTrue(events.contains("locationHint='file://$directory/cart_test.go:"), events) }
                    val retries = GoTestRerunPlan.create(converter.failures(), values)
                    assertEquals(modules.map { it.first }.toSet(), retries.map { it.parameters.target }.toSet())
                    retries.forEach { retry ->
                        assertEquals("^TestCart$/^wanted$", retry.parameters.testPattern)
                        assertEquals(values.environment, retry.parameters.environment)
                        assertTrue(retry.parameters.commandArguments(GoRunKind.TEST, "coverage_fixture", root.resolve("retry.out").toString()).any { it.startsWith("-coverprofile=") })
                    }
                }
                val report = GoCoverageProfile.parse(Files.readString(profile), metadata.modules, root.toString())
                assertTrue(report.unmappedFiles.isEmpty(), report.unmappedFiles.toString())
                assertEquals(modules.map { it.second.resolve("cart.go").toString() }.toSet(), report.files.mapNotNull { it.localPath }.toSet())
                assertTrue(report.totals.coveredStatements in 1 until report.totals.statements)
                val (coverCode, coverText) = run(root, emptyMap(), listOf("tool", "cover", "-func=$profile"))
                assertEquals(0, coverCode, coverText)
                val expected = Regex("total:\\s+\\(statements\\)\\s+([0-9.]+%)").find(coverText)!!.groupValues[1]
                assertEquals(expected, GoCoverageService.formatCoverage(report.totals.percent))
            }
        } finally { Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }

    private fun run(root: Path, environment: Map<String, String>, args: List<String>): Pair<Int, String> {
        val log = Files.createTempFile(root, ".native-go-", ".log")
        val builder = ProcessBuilder(listOf("go") + args).directory(root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile())
        builder.environment().putAll(environment)
        builder.environment()["GOWORK"] = root.resolve("go.work").toString()
        builder.environment()["GOTOOLCHAIN"] = "local"
        val process = builder.start()
        try {
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.descendants().use { children -> children.forEach { it.destroyForcibly() } }
                process.destroyForcibly()
                error("Native Go fixture timed out: ${args.joinToString(" ")}")
            }
            return process.exitValue() to Files.readString(log)
        } finally { Files.deleteIfExists(log) }
    }
}
