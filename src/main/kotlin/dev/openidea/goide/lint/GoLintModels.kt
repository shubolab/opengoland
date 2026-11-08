package dev.openidea.goide.lint

import com.intellij.util.execution.ParametersListUtil
import java.nio.file.Files
import java.nio.file.Path

enum class GoInspectionTool(val title: String) {
    VET("Go Vet"), GOLANGCI("golangci-lint");
    override fun toString() = title
}

data class GoInspectionModule(val path: String, val directory: String) {
    override fun toString() = path
}

data class GoInspectionIssue(
    val tool: GoInspectionTool, val linter: String, val severity: String,
    val message: String, val filename: String, val line: Int, val byteColumn: Int,
    val localPath: String?, val moduleDirectory: String,
)

/** Go token.Position columns count UTF-8 bytes; IntelliJ document columns count UTF-16 units. */
object GoInspectionLocations {
    fun utf16Column(line: String, oneBasedByteColumn: Int): Int {
        val target = (oneBasedByteColumn - 1).coerceAtLeast(0)
        var bytes = 0
        var chars = 0
        while (chars < line.length) {
            val point = line.codePointAt(chars)
            val width = when {
                point <= 0x7f -> 1
                point <= 0x7ff -> 2
                point <= 0xffff -> 3
                else -> 4
            }
            if (bytes + width > target) break
            bytes += width
            chars += Character.charCount(point)
        }
        return chars
    }

    fun resolve(filename: String, directory: String, additionalRoots: List<String> = emptyList()): String? {
        val path = runCatching { Path.of(filename) }.getOrNull() ?: return null
        val candidates = if (path.isAbsolute) listOf(path) else
            (listOf(directory) + additionalRoots).distinct().map { Path.of(it).resolve(path) }
        // Refuse an ambiguous relative filename rather than opening another module's same-name file.
        return candidates.map { it.toAbsolutePath().normalize() }.filter(Files::isRegularFile)
            .map { runCatching { it.toRealPath().toString() }.getOrDefault(it.toString()) }.distinct().singleOrNull()
    }
}

object GoLintCommands {
    fun flags(text: String): List<String> {
        require('\u0000' !in text) { "Inspection flags cannot contain NUL." }
        return ParametersListUtil.parse(text, false, true)
    }

    fun versionMajor(text: String): Int? {
        val json = runCatching { com.google.gson.JsonParser.parseString(text).asJsonObject.get("version")?.asString }.getOrNull()
        return Regex("(?:^|version\\s+)v?(\\d+)\\.\\d+").find(json ?: text.trim())?.groupValues?.get(1)?.toIntOrNull()
    }

    fun vet(flags: String): List<String> {
        val args = flags(flags)
        require(args.none { it.substringBefore('=') in setOf("-fix", "--fix", "-diff", "--diff") }) {
            "Go Inspections does not apply fixes; remove the fix/diff flag."
        }
        return listOf("vet") + args + "./..."
    }

    fun golangci(major: Int, flags: String, report: Path, buildTags: List<String>): List<String> {
        val args = flags(flags)
        require(args.none { it.substringBefore('=') in setOf("--fix", "--out-format", "--path-mode", "--path-prefix", "--show-stats") || it.startsWith("--output.") }) {
            "Inspection JSON output and paths are managed by the IDE; remove output/path/fix flags."
        }
        val managed = when (major) {
            1 -> listOf("--out-format=json:$report", "--show-stats=false", "--fix=false")
            2 -> listOf("--output.json.path=$report", "--path-mode=abs", "--path-prefix=", "--show-stats=false", "--color=never", "--fix=false")
            else -> error("Unsupported golangci-lint major version $major. Use version 1 or 2.")
        }
        val tags = if (buildTags.isEmpty()) emptyList() else listOf("--build-tags=${buildTags.joinToString(",")}")
        return listOf("run") + args + managed + tags + "./..."
    }
}
