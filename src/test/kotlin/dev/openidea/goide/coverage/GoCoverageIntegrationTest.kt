package dev.openidea.goide.coverage

import dev.openidea.goide.run.GoPackageDirectories
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GoCoverageIntegrationTest {
    @Test fun realWorkspaceProfilesResolveBothModulesAndMatchGoToolStatementTotals() {
        val root = Files.createTempDirectory("openidea-go-coverage-workspace").toRealPath()
        try {
            val first = Files.createDirectory(root.resolve("a"))
            val second = Files.createDirectory(root.resolve("b"))
            Files.writeString(first.resolve("go.mod"), "module example.com/coverage/a\n\ngo 1.20\n")
            Files.writeString(second.resolve("go.mod"), "module example.com/coverage/b\n\ngo 1.20\n")
            Files.writeString(root.resolve("go.work"), "go 1.20\n\nuse (\n ./a\n ./b\n)\n")
            Files.writeString(first.resolve("abs.go"), "package calc\nfunc Abs(x int) int { if x < 0 { return -x }; return x }\n")
            Files.writeString(first.resolve("abs_test.go"), "package calc\nimport \"testing\"\nfunc TestAbs(t *testing.T) { if Abs(3) != 3 { t.Fatal(\"incorrect\") } }\n")
            Files.writeString(second.resolve("double.go"), "package other\nfunc Double(x int) int { return x * 2 }\n")
            Files.writeString(second.resolve("double_test.go"), "package other\nimport \"testing\"\nfunc TestDouble(t *testing.T) { if Double(3) != 6 { t.Fatal(\"incorrect\") } }\n")
            val profileA = first.resolve("coverage.out")
            val profileB = second.resolve("coverage.out")
            run(root, first, "test", "-coverprofile=$profileA", "./...")
            run(root, second, "test", "-coverprofile=$profileB", "./...")
            val modules = listOf(GoCoverageModule("example.com/coverage/a", first.toString()), GoCoverageModule("example.com/coverage/b", second.toString()))
            val firstText = Files.readString(profileA)
            val firstReport = GoCoverageProfile.parse(firstText, modules)
            val expected = Regex("total:\\s+\\(statements\\)\\s+([0-9.]+%)").find(run(root, first, "tool", "cover", "-func=$profileA"))!!.groupValues[1]
            assertEquals(expected, GoCoverageService.formatCoverage(firstReport.totals.percent))
            assertTrue(firstReport.totals.coveredStatements in 1 until firstReport.totals.statements)
            val combined = GoCoverageProfile.parse(firstText + Files.readString(profileB).substringAfter('\n'), modules)
            assertEquals(setOf(first.resolve("abs.go").toString(), second.resolve("double.go").toString()), combined.files.mapNotNull { it.localPath }.toSet())
            assertTrue(combined.unmappedFiles.isEmpty())
            val metadata = GoPackageDirectories.parse(run(root, first, "list", "-e", "-json=ImportPath,Dir", "./...") + run(root, second, "list", "-e", "-json=ImportPath,Dir", "./..."))
            assertEquals(first.toString(), metadata["example.com/coverage/a"])
            assertEquals(second.toString(), metadata["example.com/coverage/b"])
        } finally { Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }

    private fun run(root: Path, cwd: Path, vararg args: String): String {
        val output = Files.createTempFile(root, ".coverage-command-", ".log")
        val builder = ProcessBuilder(listOf("go") + args).directory(cwd.toFile()).redirectErrorStream(true).redirectOutput(output.toFile())
        builder.environment()["GOWORK"] = root.resolve("go.work").toString()
        builder.environment()["GOTOOLCHAIN"] = "local"
        val process = builder.start()
        try {
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.descendants().use { children -> children.forEach { it.destroyForcibly() } }
                process.destroyForcibly()
                error("Go fixture command timed out: ${args.joinToString(" ")}")
            }
            val text = Files.readString(output)
            assertEquals(0, process.exitValue(), text)
            return text
        } finally { Files.deleteIfExists(output) }
    }
}
