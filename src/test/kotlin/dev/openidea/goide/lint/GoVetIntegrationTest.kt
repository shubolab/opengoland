package dev.openidea.goide.lint

import com.google.gson.JsonParser
import dev.openidea.goide.settings.GoToolchain
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GoVetIntegrationTest {
    private val go = Path.of(requireNotNull(GoToolchain.resolve("go",
        System.getProperty("opengoland.testGo") ?: System.getenv("OPENGOLAND_GO_TEST").orEmpty())) {
        "A real Go SDK is required for vet integration tests."
    }).toRealPath()

    @Test fun realVetFindingsInTwoWorkspaceModulesNavigateToTheCorrectUtf16Column() {
        val root = Files.createTempDirectory("openidea-vet-workspace-").toRealPath()
        try {
            val modules = listOf("first", "second").map { name ->
                val directory = Files.createDirectory(root.resolve(name))
                Files.writeString(directory.resolve("go.mod"), "module example.com/vet/$name\n\ngo 1.20\n")
                Files.writeString(directory.resolve("same.go"), "package fixture\nimport \"fmt\"\nfunc Bad() { _ = \"中文😀\"; fmt.Printf(\"%d\", \"wrong\") }\n")
                directory
            }
            Files.writeString(root.resolve("go.work"), "go 1.20\nuse (\n./first\n./second\n)\n")
            assertTrue(run(root, root, listOf("vet", "./...")).first != 0)
            val all = modules.flatMap { module ->
                val (code, text) = run(root, module, GoLintCommands.vet(""))
                assertTrue(code != 0, text)
                val issues = GoLintParser.vet(text, module.toString())
                val issue = issues.single()
                assertEquals(module.resolve("same.go").toString(), issue.localPath)
                assertEquals(3, issue.line)
                val source = Files.readAllLines(module.resolve("same.go"))[issue.line - 1]
                val (jsonCode, jsonText) = run(root, module, GoLintCommands.vet("-json"))
                assertEquals(0, jsonCode, jsonText)
                val jsonIssue = GoLintParser.vet(jsonText, module.toString()).single()
                assertEquals(issue.localPath, jsonIssue.localPath)
                assertEquals(issue.line, jsonIssue.line)
                assertEquals(issue.byteColumn, jsonIssue.byteColumn)
                assertEquals("printf", jsonIssue.linter)
                val diagnostic = JsonParser.parseString(jsonText.substring(jsonText.indexOf('{')))
                    .asJsonObject.entrySet().single().value.asJsonObject.getAsJsonArray("printf").single().asJsonObject
                val rawColumn = diagnostic.get("posn").asString.substringAfterLast(':').toInt()
                assertEquals(rawColumn, jsonIssue.byteColumn)
                // Decode the actual server's UTF-8 prefix independently of the
                // production converter. A split Chinese/emoji byte must fail.
                val expected = decodedPrefixLength(source, rawColumn)
                assertEquals(expected, GoInspectionLocations.utf16Column(source, issue.byteColumn))
                if (diagnostic.has("end")) {
                    // Current printf reports the precise bad format verb.
                    val end = decodedPrefixLength(source, diagnostic.get("end").asString.substringAfterLast(':').toInt())
                    assertEquals("%d", source.substring(expected, end))
                } else {
                    // The legacy point-only analyzer reports the call start.
                    assertEquals(source.indexOf("fmt.Printf"), expected)
                }
                issues
            }
            assertEquals(2, all.map { it.localPath }.toSet().size)
            val clean = modules.first().resolve("same.go")
            Files.writeString(clean, Files.readString(clean).replace("fmt.Printf(\"%d\", \"wrong\")", "fmt.Printf(\"%s\", \"correct\")"))
            val (code, output) = run(root, modules.first(), GoLintCommands.vet(""))
            assertEquals(0, code, output)
            assertTrue(GoLintParser.vet(output, modules.first().toString()).isEmpty())
        } finally { Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }

    @Test fun buildTagsAreRespectedByTheActualVetCommand() {
        val root = Files.createTempDirectory("openidea-vet-tags-").toRealPath()
        try {
            Files.writeString(root.resolve("go.mod"), "module example.com/vettags\n\ngo 1.20\n")
            Files.writeString(root.resolve("main.go"), "package fixture\nfunc Good() {}\n")
            Files.writeString(root.resolve("tagged.go"), "//go:build inspectfixture\n\npackage fixture\nimport \"fmt\"\nfunc Bad() { fmt.Printf(\"%d\", \"wrong\") }\n")
            assertEquals(0, run(root, root, GoLintCommands.vet("")).first)
            val result = run(root, root, GoLintCommands.vet("-tags=inspectfixture"))
            assertTrue(result.first != 0)
            assertEquals(root.resolve("tagged.go").toRealPath().toString(),
                GoLintParser.vet(result.second, root.toString()).single().localPath)
        } finally { Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }

    private fun run(root: Path, directory: Path, args: List<String>): Pair<Int, String> {
        val log = Files.createTempFile(root, ".vet-", ".log")
        val builder = ProcessBuilder(listOf(go.toString()) + args).directory(directory.toFile()).redirectErrorStream(true).redirectOutput(log.toFile())
        val sdk = go.parent.parent
        check(Files.isDirectory(sdk.resolve("src/runtime"))) { "The selected vet executable must belong to a complete Go SDK." }
        builder.environment()["GOROOT"] = sdk.toString()
        builder.environment()["PATH"] = go.parent.toString() + java.io.File.pathSeparator + builder.environment()["PATH"].orEmpty()
        builder.environment()["GOTOOLCHAIN"] = "local"
        builder.environment()["GOWORK"] = if (Files.exists(root.resolve("go.work"))) root.resolve("go.work").toString() else "off"
        builder.environment()["GOENV"] = "off"
        builder.environment()["GOTELEMETRY"] = "off"
        builder.environment()["GOPROXY"] = "off"
        builder.environment()["GOSUMDB"] = "off"
        builder.environment()["CGO_ENABLED"] = "0"
        for (key in listOf("GOFLAGS", "GOOS", "GOARCH", "GOEXPERIMENT", "GOTOOLDIR")) builder.environment().remove(key)
        val process = builder.start()
        try {
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.descendants().use { children -> children.forEach { it.destroyForcibly() } }; process.destroyForcibly()
                error("go vet fixture timed out")
            }
            return process.exitValue() to Files.readString(log)
        } finally { Files.deleteIfExists(log) }
    }

    private fun decodedPrefixLength(source: String, oneBasedByteColumn: Int): Int {
        val bytes = source.toByteArray(Charsets.UTF_8)
        val end = oneBasedByteColumn - 1
        require(end in 0..bytes.size) { "Vet position exceeds the source line." }
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes, 0, end)).length
    }
}
