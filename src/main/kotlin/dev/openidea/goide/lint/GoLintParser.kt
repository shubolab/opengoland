package dev.openidea.goide.lint

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Accepts normal vet diagnostics and its package/analyzer JSON objects mixed with # headers. */
object GoLintParser {
    private val withColumn = Regex("^(.+?):(\\d+):(\\d+):\\s*(.+)$")
    private val withoutColumn = Regex("^(.+?):(\\d+):\\s*(.+)$")

    fun vet(text: String, directory: String): List<GoInspectionIssue> {
        val issues = mutableListOf<GoInspectionIssue>()
        val objects = jsonObjects(text)
        var lineOffset = 0
        var blockIndex = 0
        text.splitToSequence('\n').forEach { line ->
            val start = lineOffset
            lineOffset += line.length + 1
            while (blockIndex < objects.size && objects[blockIndex].end < start) blockIndex++
            // JSON posn fields themselves resemble file:line text; exclude complete JSON blocks.
            if (blockIndex < objects.size && objects[blockIndex].start <= start + line.length) return@forEach
            val match = withColumn.matchEntire(line.trim())
            if (match != null) {
                val (file, row, column, message) = match.destructured
                issues += issue(GoInspectionTool.VET, "vet", "warning", message, file, row.toIntOrNull() ?: 0, column.toIntOrNull() ?: 0, directory)
            } else withoutColumn.matchEntire(line.trim())?.let { found ->
                val (file, row, message) = found.destructured
                issues += issue(GoInspectionTool.VET, "vet", "warning", message, file, row.toIntOrNull() ?: 0, 0, directory)
            }
        }
        objects.forEach { block ->
            val root = runCatching { JsonParser.parseString(block.text).asJsonObject }.getOrNull() ?: return@forEach
            root.entrySet().forEach { (_, analyzers) ->
                if (!analyzers.isJsonObject) return@forEach
                analyzers.asJsonObject.entrySet().forEach { (analyzer, diagnostics) ->
                    if (!diagnostics.isJsonArray) return@forEach
                    diagnostics.asJsonArray.forEach { diagnostic ->
                        if (!diagnostic.isJsonObject) return@forEach
                        val value = diagnostic.asJsonObject
                        val position = value.string("posn") ?: return@forEach
                        val message = value.string("message") ?: return@forEach
                        val match = withColumn.matchEntire("$position: $message") ?: return@forEach
                        val (file, row, column, description) = match.destructured
                        issues += issue(GoInspectionTool.VET, analyzer, "warning", description, file, row.toIntOrNull() ?: 0, column.toIntOrNull() ?: 0, directory)
                    }
                }
            }
        }
        return issues.distinctBy { listOf(it.filename, it.line, it.byteColumn, it.message) }
    }

    /** Official golangci-lint v1.64 and v2.15 both use Issues/FromLinter/Text/Severity/Pos. */
    fun golangci(text: String, directory: String, additionalRoots: List<String> = emptyList()): List<GoInspectionIssue> {
        val root = JsonParser.parseString(text).asJsonObject
        require(root.has("Issues")) { "Invalid golangci-lint JSON: missing Issues." }
        val values = root.get("Issues")
        if (values.isJsonNull) return emptyList()
        require(values.isJsonArray) { "Invalid golangci-lint JSON: Issues must be an array." }
        return values.asJsonArray.map { entry ->
            require(entry.isJsonObject) { "Invalid golangci-lint issue." }
            val value = entry.asJsonObject
            val position = value.getAsJsonObject("Pos") ?: error("Invalid golangci-lint issue: missing Pos.")
            val filename = position.string("Filename") ?: error("Invalid golangci-lint issue: missing Filename.")
            issue(GoInspectionTool.GOLANGCI, value.string("FromLinter") ?: "golangci-lint", value.string("Severity").orEmpty().ifBlank { "warning" },
                value.string("Text") ?: error("Invalid golangci-lint issue: missing Text."), filename, position.int("Line"), position.int("Column"), directory, additionalRoots)
        }.distinct()
    }

    private fun issue(tool: GoInspectionTool, linter: String, severity: String, message: String, filename: String, line: Int, column: Int, directory: String, roots: List<String> = emptyList()) =
        GoInspectionIssue(tool, linter, severity, message, filename, line, column, GoInspectionLocations.resolve(filename, directory, roots), directory)

    private fun JsonObject.string(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
    private fun JsonObject.int(name: String): Int = runCatching { get(name)?.asInt ?: 0 }.getOrDefault(0)

    private data class JsonBlock(val start: Int, val end: Int, val text: String)
    private fun jsonObjects(text: String): List<JsonBlock> {
        val result = mutableListOf<JsonBlock>()
        var start = -1
        var depth = 0
        var quoted = false
        var escaped = false
        text.forEachIndexed { index, char ->
            if (start < 0) {
                if (char == '{' && text.substring(text.lastIndexOf('\n', index).coerceAtLeast(-1) + 1, index).isBlank()) { start = index; depth = 1 }
            } else if (quoted) {
                if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false
            } else when (char) {
                '"' -> quoted = true
                '{' -> depth++
                '}' -> if (--depth == 0) { result += JsonBlock(start, index, text.substring(start, index + 1)); start = -1 }
            }
        }
        return result
    }
}
