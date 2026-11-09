package dev.openidea.goide.run.corpus

import com.google.gson.JsonParser
import dev.openidea.goide.run.GoRunKind
import dev.openidea.goide.run.GoRunParameters
import dev.openidea.goide.run.GoTestMode
import dev.openidea.goide.settings.GoToolchain
import org.junit.Assume.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Real Go seed files and exact selectors, without starting a mutation worker. */
class GoFuzzCorpusToolTest {
    @Test fun selectedFailingAndPassingCorpusInputsReplayIndependently() {
        val go = GoToolchain.resolve("go", System.getProperty("opengoland.testGo") ?: System.getenv("OPENGOLAND_GO_TEST").orEmpty())
        assumeTrue("Go is required for the real corpus replay probe", go != null)
        val root = Files.createTempDirectory("opengoland-real-corpus-replay-").toRealPath()
        try {
            Files.writeString(root.resolve("go.mod"), "module example.org/corpusreplay\n\ngo 1.24.0\n")
            Files.writeString(root.resolve("decode_test.go"), """
                package corpusreplay
                import ("os"; "testing")
                func TestUnrelated(t *testing.T) { t.Fatal("unrelated ordinary test must not run") }
                func FuzzDecode(f *testing.F) {
                    f.Fuzz(func(t *testing.T, input string) {
                        if err := os.WriteFile("executed.txt", []byte(input), 0600); err != nil { t.Fatal(err) }
                        if input == "bad" { t.Fatal("corpus witness failure") }
                    })
                }
            """.trimIndent())
            val directory = root.resolve("testdata/fuzz/FuzzDecode")
            Files.createDirectories(directory)
            val bad = directory.resolve("0123456789abcdef")
            val good = directory.resolve("fedcba9876543210")
            val manual = directory.resolve("manual-case.json")
            Files.writeString(bad, "go test fuzz v1\nstring(\"bad\")\n")
            Files.writeString(good, "go test fuzz v1\nstring(\"good\")\n")
            Files.writeString(manual, "go test fuzz v1\nstring(\"manual\")\n")
            for ((file, code, value) in listOf(Triple(bad, 1, "bad"), Triple(good, 0, "good"), Triple(manual, 0, "manual"))) {
                val corpus = GoFuzzCorpusPath.fromPath(file)!!
                val values = GoRunParameters(target = ".", workingDirectory = root.toString(), testMode = GoTestMode.TEST, testPattern = corpus.testPattern)
                val arguments = values.commandArguments(GoRunKind.TEST, "")
                assertFalse(arguments.any { it.startsWith("-fuzz") })
                val (exit, output) = run(listOf(go!!) + arguments, root)
                assertEquals(code, exit, output)
                val ran = output.lineSequence().mapNotNull { line -> runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull() }
                    .filter { it.get("Action")?.asString == "run" }.mapNotNull { it.get("Test")?.asString }.toList()
                assertEquals(listOf("FuzzDecode", "FuzzDecode/${file.fileName}"), ran, output)
                assertEquals(value, Files.readString(root.resolve("executed.txt")))
                assertFalse(output.contains("fuzz: elapsed:"), "A selected corpus case must not enter mutation fuzzing")
            }
            // Delve uses a compiled test binary. Exercise the exact same -test.run
            // selector directly, independently of the go test frontend's selection.
            val binary = root.resolve(if (System.getProperty("os.name").startsWith("Windows")) "corpus.test.exe" else "corpus.test")
            assertEquals(0, run(listOf(go!!, "test", "-c", "-o", binary.toString()), root).first)
            val pattern = GoFuzzCorpusPath.fromPath(bad)!!.testPattern
            val (code, output) = run(listOf(binary.toString(), "-test.v", "-test.run", pattern), root)
            assertEquals(1, code, output)
            assertTrue(output.contains("=== RUN   FuzzDecode/0123456789abcdef"), output)
            assertFalse(output.contains("=== RUN   FuzzDecode/fedcba9876543210"), output)
            assertFalse(output.contains("=== RUN   TestUnrelated"), output)
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    private fun run(command: List<String>, root: Path): Pair<Int, String> {
        val capture = Files.createTempFile(root, "owned-corpus-command-", ".log")
        val child = ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).redirectOutput(capture.toFile()).apply {
            environment()["GOTOOLCHAIN"] = "local"
            environment()["GOPROXY"] = "off"
            environment()["GOWORK"] = "off"
            environment()["GOTELEMETRY"] = "off"
        }.start()
        try {
            assertTrue(child.waitFor(40, TimeUnit.SECONDS), "Owned corpus command timed out: ${command.first()}")
            return child.exitValue() to Files.readString(capture)
        } finally {
            if (child.isAlive) {
                child.descendants().use { descendants -> descendants.forEach { it.destroyForcibly() } }
                child.destroyForcibly()
                child.waitFor(4, TimeUnit.SECONDS)
            }
        }
    }
}
