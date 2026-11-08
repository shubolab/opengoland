package dev.openidea.goide.coverage

import java.nio.file.Path

data class GoCoverageModule(val path: String, val directory: String) {
    override fun toString() = path
}

data class GoCoverageBlock(
    val startLine: Int, val startColumn: Int, val endLine: Int, val endColumn: Int,
    val statements: Long, val count: Long,
)

data class GoCoverageTotals(val statements: Long, val coveredStatements: Long) {
    val percent: Double get() = if (statements == 0L) 0.0 else 100.0 * coveredStatements / statements
}

enum class GoLineCoverage { COVERED, UNCOVERED, PARTIAL }

data class GoCoverageFile(val profilePath: String, val localPath: String?, val blocks: List<GoCoverageBlock>) {
    val totals: GoCoverageTotals get() = totals(blocks)

    /** Go block end positions are exclusive; an end column of one excludes the final line. */
    fun lines(maxLine: Int = Int.MAX_VALUE): Map<Int, GoLineCoverage> {
        val values = sortedMapOf<Int, Int>()
        blocks.filter { it.statements > 0 }.forEach { block ->
            val end = minOf(maxLine, block.endLine - if (block.endColumn == 1) 1 else 0)
            for (line in block.startLine..end) {
                val bit = if (block.count > 0) 1 else 2
                values[line] = (values[line] ?: 0) or bit
            }
        }
        return values.mapValues { (_, flags) -> when (flags) { 1 -> GoLineCoverage.COVERED; 2 -> GoLineCoverage.UNCOVERED; else -> GoLineCoverage.PARTIAL } }
    }
}

data class GoCoverageReport(val mode: String, val files: List<GoCoverageFile>) {
    val totals: GoCoverageTotals get() = totals(files.flatMap { it.blocks })
    val unmappedFiles: List<GoCoverageFile> get() = files.filter { it.localPath == null }
}

private fun totals(blocks: List<GoCoverageBlock>) = GoCoverageTotals(
    blocks.fold(0L) { sum, block -> Math.addExact(sum, block.statements) },
    blocks.filter { it.count > 0 }.fold(0L) { sum, block -> Math.addExact(sum, block.statements) },
)

object GoCoverageProfile {
    private data class BlockKey(val file: String, val startLine: Int, val startColumn: Int, val endLine: Int, val endColumn: Int)
    private val blockPattern = Regex("^(.+):(\\d+)\\.(\\d+),(\\d+)\\.(\\d+)\\s+(\\d+)\\s+(\\d+)$")

    fun parse(text: String, modules: List<GoCoverageModule>, relativeRoot: String? = null): GoCoverageReport {
        val lines = text.removePrefix("\uFEFF").lineSequence().iterator()
        require(lines.hasNext()) { "Coverage profile is empty." }
        val header = lines.next().trim()
        require(header in setOf("mode: set", "mode: count", "mode: atomic")) { "Expected a Go coverage mode: set, count, or atomic." }
        val mode = header.substringAfter(": ")
        val blocks = linkedMapOf<BlockKey, GoCoverageBlock>()
        val paths = linkedMapOf<String, Pair<String, String?>>()
        var lineNumber = 1
        while (lines.hasNext()) {
            lineNumber++
            val value = lines.next().trim()
            if (value.isEmpty()) continue
            val match = requireNotNull(blockPattern.matchEntire(value)) { "Malformed coverage block on line $lineNumber." }
            fun positive(index: Int): Int = requireNotNull(match.groupValues[index].toIntOrNull()?.takeIf { it > 0 }) { "Invalid source position on line $lineNumber." }
            val rawPath = match.groupValues[1]
            val localPath = resolvePath(rawPath, modules, relativeRoot)
            val identity = localPath ?: rawPath
            val block = GoCoverageBlock(positive(2), positive(3), positive(4), positive(5),
                requireNotNull(match.groupValues[6].toLongOrNull()) { "Statement count is too large on line $lineNumber." },
                requireNotNull(match.groupValues[7].toLongOrNull()) { "Execution count is too large on line $lineNumber." })
            require(block.endLine > block.startLine || block.endLine == block.startLine && block.endColumn >= block.startColumn) { "Reversed source range on line $lineNumber." }
            val key = BlockKey(identity, block.startLine, block.startColumn, block.endLine, block.endColumn)
            val previous = blocks[key]
            if (previous == null) blocks[key] = block
            else {
                require(previous.statements == block.statements) { "Conflicting statement counts for the same coverage block on line $lineNumber." }
                val count = if (mode == "set") if (previous.count > 0 || block.count > 0) 1L else 0L else Math.addExact(previous.count, block.count)
                blocks[key] = previous.copy(count = count)
            }
            paths.putIfAbsent(identity, rawPath to localPath)
        }
        val grouped = blocks.entries.groupBy({ it.key.file }, { it.value })
        val files = paths.map { (identity, path) ->
            GoCoverageFile(path.first, path.second, grouped[identity].orEmpty().sortedWith(compareBy({ it.startLine }, { it.startColumn }, { it.endLine }, { it.endColumn })))
        }.sortedBy { it.profilePath }
        return GoCoverageReport(mode, files).also { it.totals }
    }

    fun resolvePath(profilePath: String, modules: List<GoCoverageModule>, relativeRoot: String? = null): String? {
        if ('\u0000' in profilePath) return null
        val normalized = profilePath.replace('\\', '/')
        val module = modules.filter { normalized.startsWith(it.path.trimEnd('/') + "/") }.maxByOrNull { it.path.length }
        if (module != null) {
            val root = Path.of(module.directory).normalize()
            val resolved = root.resolve(normalized.removePrefix(module.path.trimEnd('/') + "/")).normalize()
            return resolved.takeIf { it.startsWith(root) }?.toString()
        }
        val candidate = runCatching { Path.of(profilePath).normalize() }.getOrNull() ?: return null
        if (candidate.isAbsolute) {
            return candidate.takeIf { path -> modules.any { path.startsWith(Path.of(it.directory).normalize()) } }?.toString()
        }
        // An import path that did not match a module is not a file relative to the selected module.
        if (normalized.startsWith("./") || !normalized.contains('/')) {
            val root = relativeRoot?.let { Path.of(it).normalize() } ?: return null
            val resolved = root.resolve(candidate).normalize()
            return resolved.takeIf { it.startsWith(root) }?.toString()
        }
        return null
    }
}
