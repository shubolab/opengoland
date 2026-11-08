package dev.openidea.goide.profile

import com.google.gson.JsonObject
import com.intellij.util.execution.ParametersListUtil
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

data class GoProfileModule(val path: String, val directory: String) { override fun toString() = path }
enum class GoProfileKind(val protocol: String, private val label: String) {
    CPU("cpu", "CPU"), MEMORY("memory", "Memory"), BLOCK("block", "Block"), MUTEX("mutex", "Mutex");
    override fun toString() = label
}
enum class GoProfileMode(val protocol: String, private val label: String) {
    BENCHMARK("benchmark", "Benchmarks"), TEST("test", "Tests"); override fun toString() = label
}
data class GoProfileOptions(
    val kind: GoProfileKind = GoProfileKind.CPU, val mode: GoProfileMode = GoProfileMode.BENCHMARK,
    val target: String = ".", val pattern: String = "", val arguments: String = "",
) {
    fun request(id: String, cwd: String, environment: Map<String, String>): JsonObject {
        require(target.isNotBlank() && !target.startsWith('-') && !target.contains("...")) { "Choose one concrete Go package to profile." }
        require('\u0000' !in target + pattern + arguments) { "Profile settings cannot contain NUL characters." }
        return JsonObject().apply {
            addProperty("id", id); addProperty("command", "profile"); addProperty("cwd", cwd)
            add("profile", JsonObject().apply {
                addProperty("kind", kind.protocol); addProperty("mode", mode.protocol); addProperty("target", target); addProperty("pattern", pattern)
            })
            add("args", com.google.gson.JsonArray().also { array -> ParametersListUtil.parse(arguments, false, true).forEach(array::add) })
            add("env", JsonObject().also { env -> environment.forEach { (name, value) -> env.addProperty(name, value) } })
        }
    }
}
data class GoProfileMetric(val type: String = "", val unit: String = "", val total: Long = 0) {
    override fun toString() = "$type ($unit)"
}
data class GoProfileHotspot(
    val name: String = "", val file: String? = null, val line: Long = 0,
    val flat: Long = 0, val cumulative: Long = 0, val flatPercent: Double = 0.0, val cumulativePercent: Double = 0.0,
)
data class GoProfileReport(
    val path: String = "", val sampleTypes: List<GoProfileMetric> = emptyList(), val sampleIndex: Int = 0,
    val sampleCount: Int = 0, val durationNanos: Long = 0, val timeNanos: Long = 0,
    val functionCount: Int = 0, val rows: List<GoProfileHotspot> = emptyList(), val truncated: Boolean = false,
    val warnings: List<String> = emptyList(),
) { val metric: GoProfileMetric? get() = sampleTypes.getOrNull(sampleIndex) }

/** Exact recorded paths or compiler trimpath module prefixes only; never search by basename. */
object GoProfileSources {
    fun resolve(row: GoProfileHotspot, modules: List<GoProfileModule>): Path? {
        if (row.line <= 0 || row.line > Int.MAX_VALUE || row.file.isNullOrBlank()) return null
        val raw = runCatching { Path.of(row.file) }.getOrNull() ?: return null
        if (raw.isAbsolute) return raw.normalize().takeIf(Files::isRegularFile)
        val recorded = row.file.replace('\\', '/')
        val matches = modules.filter { recorded.startsWith(it.path + "/") }.groupBy { it.path.length }
        val longest = matches.keys.maxOrNull() ?: return null
        val candidates = matches.getValue(longest).mapNotNull { module ->
            val root = Path.of(module.directory).toAbsolutePath().normalize()
            val file = root.resolve(recorded.removePrefix(module.path + "/")).normalize()
            file.takeIf { it.startsWith(root) && Files.isRegularFile(it) }
        }.distinct()
        return candidates.singleOrNull()
    }
}

object GoProfileUnits {
    fun format(value: Long, unit: String): String {
        val absolute = kotlin.math.abs(value.toDouble())
        val (factor, label) = when (unit) {
            "nanoseconds" -> when { absolute >= 1e9 -> 1e9 to "s"; absolute >= 1e6 -> 1e6 to "ms"; absolute >= 1e3 -> 1e3 to "µs"; else -> 1.0 to "ns" }
            "bytes" -> when { absolute >= 1073741824 -> 1073741824.0 to "GiB"; absolute >= 1048576 -> 1048576.0 to "MiB"; absolute >= 1024 -> 1024.0 to "KiB"; else -> 1.0 to "B" }
            else -> 1.0 to unit
        }
        return if (factor == 1.0) "$value $label" else String.format(Locale.ROOT, "%.2f %s", value / factor, label)
    }
}
