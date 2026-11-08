package dev.openidea.goide.run

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Path
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToLong

/** Converts go test -json into native test-runner events, keyed by package and full test name. */
class GoTestOutput @JvmOverloads constructor(
    @Suppress("UNUSED_PARAMETER") workingDirectory: String,
    private val packageDirectories: Map<String, String> = emptyMap(),
    private val elapsedMillis: () -> Long = processTimer(),
) : AutoCloseable {
    private data class Test(
        val name: String, val id: String, val parentId: String, val ancestors: Set<String>,
        var suite: Boolean = false, var started: Boolean = false, var finished: Boolean = false,
        var declared: Boolean = false, var hasTestChildren: Boolean = false,
        var failed: Boolean = false, var location: String? = null,
    )
    private data class Package(
        val name: String,
        val tests: LinkedHashMap<String, Test> = linkedMapOf(),
        val history: MutableList<Test> = mutableListOf(),
        val attempts: MutableMap<String, Int> = linkedMapOf(),
        var finished: Boolean = false, var failedTest: Boolean = false,
    )
    private val packages = linkedMapOf<String, Package>()
    private val failed = linkedMapOf<String, MutableSet<String>>()
    private val pendingLine = StringBuilder()
    private val locations = ConcurrentHashMap<String, String>()
    private val locationChanges = AtomicLong()
    val locationRevision: Long get() = locationChanges.get()
    private var progressStarted = false
    private var maximumReportedMillis = 0L
    private var closed = false

    fun feed(chunk: String): List<String> = mutableListOf<String>().also { consume(chunk, it::add) }

    /** Native process handlers stream records without collecting large test output in an intermediate list. */
    @Synchronized
    fun consume(chunk: String, emit: (String) -> Unit) {
        if (closed) return
        pendingLine.append(chunk)
        while (true) {
            val end = pendingLine.indexOf("\n")
            if (end < 0) break
            val line = pendingLine.substring(0, end).trimEnd('\r')
            pendingLine.delete(0, end + 1)
            parseLine(line, emit)
        }
        if (pendingLine.length > MAX_LINE) {
            emit(consoleOutput(pendingLine.toString()))
            pendingLine.setLength(0)
        }
    }

    fun finish(exitCode: Int): List<String> = mutableListOf<String>().also { finish(exitCode, it::add) }

    @Synchronized
    fun finish(exitCode: Int, emit: (String) -> Unit) {
        if (closed) return
        try {
            if (pendingLine.isNotEmpty()) {
                parseLine(pendingLine.toString(), emit)
                pendingLine.setLength(0)
            }
            packages.values.filterNot { it.finished }.forEach { pkg ->
                pkg.history.filterNot { it.finished }.sortedByDescending { it.name.count { c -> c == '/' } }.forEach {
                    finishUnreported(pkg, it, emit, "Test process ended before reporting a result (exit $exitCode).")
                }
                if (pkg.tests.isEmpty() && exitCode != 0) packageFailure(pkg, emit)
                emitDuration(packageId(pkg.name), null, emit)
                emit(message("testSuiteFinished", "name" to pkg.name, "nodeId" to packageId(pkg.name)))
                pkg.finished = true
            }
            if (progressStarted) emit(message("goTestDuration", "nodeId" to "0",
                "duration" to maxOf(elapsedMillis().coerceAtLeast(0), maximumReportedMillis).toString()))
        } finally { close() }
    }

    @Synchronized
    fun failures(): Map<String, List<String>> = failed.mapValues { (_, names) ->
        if ("" in names) listOf("") else names.filter { name -> names.none { other -> name.startsWith("$other/") } }
    }

    private fun parseLine(value: String, emit: (String) -> Unit) {
        val event = try { JsonParser.parseString(value).takeIf { it.isJsonObject }?.asJsonObject } catch (_: RuntimeException) { null }
        val action = event?.string("Action")
        val packageName = event?.string("Package")
        if (event == null || action == null || packageName == null) { emit(consoleOutput("$value\n")); return }
        if (!progressStarted) {
            emit(message("goTestProgress"))
            progressStarted = true
        }
        val pkg = packages.getOrPut(packageName) {
            emit(message("testSuiteStarted", "name" to packageName, "nodeId" to packageId(packageName), "parentNodeId" to "0"))
            Package(packageName)
        }
        if (pkg.finished) return
        val name = event.string("Test")
        if (name == null) {
            when (action) {
                "output" -> emit(message("testStdOut", "name" to packageName, "nodeId" to packageId(packageName), "out" to event.string("Output").orEmpty()))
                "pass", "fail", "skip" -> {
                    pkg.history.filterNot { it.finished }.sortedByDescending { it.name.count { c -> c == '/' } }.forEach {
                        finishUnreported(pkg, it, emit, "Package ended before reporting this test result.")
                    }
                    if (action == "fail" && !pkg.failedTest) packageFailure(pkg, emit)
                    // SuiteFinished itself has no duration field; write the real Go value to its native proxy.
                    emitDuration(packageId(pkg.name), event.elapsedSeconds(), emit)
                    emit(message("testSuiteFinished", "name" to packageName, "nodeId" to packageId(packageName)))
                    pkg.finished = true
                }
            }
            return
        }
        if (action == "run" && pkg.tests[name]?.finished == true) pkg.tests.remove(name)
        val test = ensureTest(pkg, name, emit, declared = true)
        when (action) {
            "output" -> {
                val output = event.string("Output").orEmpty()
                findLocation(output, pkg.name)?.takeIf { it != test.location }?.let { location ->
                    test.location = location
                    locations[test.id] = location
                    locationChanges.incrementAndGet()
                }
                emitTestOutput(test, output, emit)
            }
            "pass", "fail", "skip" -> finishTest(pkg, test, action, event.elapsedSeconds(), emit)
        }
    }

    private fun ensureTest(pkg: Package, name: String, emit: (String) -> Unit, declared: Boolean = false): Test {
        val parentName = name.substringBeforeLast('/', "")
        val parent = parentName.takeIf(String::isNotEmpty)?.let { ensureTest(pkg, it, emit) }
        if (parent != null && parent.declared && !parent.finished) parent.hasTestChildren = true
        if (parent != null && !parent.suite) {
            parent.suite = true
            // A duplicate testSuiteStarted would be rejected by the native id processor.
            // Its existing proxy is promoted in place, retaining running state and output.
            emit(message("goTestSuite", "nodeId" to parent.id))
        }
        val parentId = parent?.id ?: packageId(pkg.name)
        pkg.tests[name]?.takeIf { it.parentId != parentId || (!declared && it.declared && it.finished) }?.let { pkg.tests.remove(name) }
        return pkg.tests.getOrPut(name) {
            val attempt = (pkg.attempts[name] ?: 0) + 1
            pkg.attempts[name] = attempt
            Test(name, "go-test:${pkg.name}#$name:$attempt", parentId, parent?.let { it.ancestors + it.id }.orEmpty(), suite = !declared, declared = declared)
                .also { pkg.history += it }
        }.also { if (declared) it.declared = true; startTest(it, emit) }
    }

    private fun startTest(test: Test, emit: (String) -> Unit) {
        if (test.started) return
        emit(message(if (test.suite) "testSuiteStarted" else "testStarted", "name" to displayName(test.name), "nodeId" to test.id,
            "parentNodeId" to test.parentId, "locationHint" to locationHint(test.id)))
        test.started = true
    }

    private fun emitTestOutput(test: Test, output: String, emit: (String) -> Unit) =
        emit(message("testStdOut", "name" to displayName(test.name), "nodeId" to test.id, "out" to output))

    private fun finishTest(pkg: Package, test: Test, action: String, seconds: Double?, emit: (String) -> Unit, reason: String? = null) {
        if (test.finished) return
        // A slash inside t.Run's own name creates grouping paths with no Go run/result event.
        // Close these virtual suites before their real ancestor, using their existing children's state.
        pkg.history.filter { !it.declared && !it.finished && test.id in it.ancestors }
            .sortedByDescending { it.name.count { character -> character == '/' } }
            .forEach { finishUnreported(pkg, it, emit, reason.orEmpty()) }
        startTest(test, emit)
        val id = test.id
        val ownLeaf = test.declared && !test.hasTestChildren
        val childFailed = pkg.history.any { child -> id in child.ancestors && child.failed }
        if (action == "fail") {
            test.failed = true
            pkg.failedTest = true
            if (!test.suite) {
                failed.getOrPut(pkg.name) { linkedSetOf() } += test.name
                emit(message("testFailed", "name" to displayName(test.name), "nodeId" to id,
                    "message" to (reason ?: "Go test failed"), "details" to ""))
            }
            else if (test.declared && (ownLeaf || !childFailed)) {
                failed.getOrPut(pkg.name) { linkedSetOf() } += test.name
                emitOwnResult(test, action, seconds, reason, emit)
            }
        } else if (test.suite && ownLeaf) {
            // A real leaf may have the same full path as an earlier virtual grouping node.
            // Preserve that real result as one child instead of losing it or flattening earlier tests.
            emitOwnResult(test, action, seconds, reason, emit)
        } else if (action == "skip" && !test.suite) emit(message("testIgnored", "name" to displayName(test.name), "nodeId" to id, "message" to "Go test skipped"))
        val attrs = mutableListOf("name" to displayName(test.name), "nodeId" to id)
        if (!test.suite && seconds != null) attrs += "duration" to recordMillis(seconds).toString()
        if (test.suite) emitDuration(id, seconds?.takeIf { test.declared && !ownLeaf }, emit)
        emit(message(if (test.suite) "testSuiteFinished" else "testFinished", *attrs.toTypedArray()))
        if (!test.suite) emit(message("goTestLeafResult", "nodeId" to id, "outcome" to action))
        test.finished = true
    }

    private fun finishUnreported(pkg: Package, test: Test, emit: (String) -> Unit, reason: String) {
        if (test.declared) finishTest(pkg, test, "fail", null, emit, reason)
        else finishTest(pkg, test, if (pkg.history.any { test.id in it.ancestors && it.failed }) "fail" else "pass", null, emit)
    }

    private fun emitOwnResult(test: Test, action: String, seconds: Double?, reason: String?, emit: (String) -> Unit) {
        val id = "${test.id}:parent-result"
        val name = "${displayName(test.name)} result"
        val attrs = mutableListOf("name" to name, "nodeId" to id, "parentNodeId" to test.id)
        test.location?.let { attrs += "locationHint" to it }
        emit(message("testStarted", *attrs.toTypedArray()))
        if (action == "fail") emit(message("testFailed", "name" to name, "nodeId" to id,
            "message" to (reason ?: "Parent Go test failed"), "details" to ""))
        if (action == "skip") emit(message("testIgnored", "name" to name, "nodeId" to id, "message" to "Go test skipped"))
        val finished = mutableListOf("name" to name, "nodeId" to id)
        seconds?.let { finished += "duration" to recordMillis(it).toString() }
        emit(message("testFinished", *finished.toTypedArray()))
        emit(message("goTestLeafResult", "nodeId" to id, "outcome" to action))
    }

    private fun emitDuration(id: String, seconds: Double?, emit: (String) -> Unit) {
        val attributes = mutableListOf("nodeId" to id)
        seconds?.let { attributes += "duration" to recordMillis(it).toString() }
        emit(message("goTestDuration", *attributes.toTypedArray()))
    }

    private fun recordMillis(seconds: Double): Long = (seconds * 1000.0).roundToLong().coerceAtLeast(0).also {
        maximumReportedMillis = maxOf(maximumReportedMillis, it)
    }

    private fun packageFailure(pkg: Package, emit: (String) -> Unit) {
        failed.getOrPut(pkg.name) { linkedSetOf() } += ""
        val id = packageId(pkg.name) + ":build"
        emit(message("testStarted", "name" to "Package build or initialization", "nodeId" to id, "parentNodeId" to packageId(pkg.name)))
        emit(message("testFailed", "name" to "Package build or initialization", "nodeId" to id, "message" to "Go package failed", "details" to ""))
        emit(message("testFinished", "name" to "Package build or initialization", "nodeId" to id))
        emit(message("goTestLeafResult", "nodeId" to id, "outcome" to "fail"))
    }

    private fun findLocation(output: String, packageName: String): String? {
        val match = Regex("^\\s*([^\\r\\n]+\\.go):(\\d+)(?::\\d+)?(?::|\\s|$)", RegexOption.MULTILINE).find(output) ?: return null
        return try {
            val source = Path.of(match.groupValues[1])
            val file = if (source.isAbsolute) source.normalize() else packageDirectories[packageName]?.let { Path.of(it).resolve(source).normalize() } ?: return null
            "file://${file}:${match.groupValues[2]}"
        } catch (_: RuntimeException) { null }
    }

    fun locationForNode(nodeId: String): String? = locations[nodeId]

    fun locationForToken(token: String): String? = runCatching {
        locations[String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8)]
    }.getOrNull()

    @Synchronized
    override fun close() {
        pendingLine.setLength(0)
        closed = true
    }

    companion object {
        private const val MAX_LINE = 4 * 1024 * 1024
        private fun processTimer(): () -> Long {
            val start = System.nanoTime()
            return { (System.nanoTime() - start).coerceAtLeast(0) / 1_000_000 }
        }
        const val LOCATION_PROTOCOL = "go-test"
        private fun locationHint(id: String) = "$LOCATION_PROTOCOL://" + Base64.getUrlEncoder().withoutPadding().encodeToString(id.toByteArray(StandardCharsets.UTF_8))
        private fun packageId(name: String) = "go-package:$name"
        private fun displayName(name: String) = name.substringAfterLast('/')
        private fun JsonObject.string(key: String) = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        private fun JsonObject.elapsedSeconds(): Double? = runCatching {
            get("Elapsed")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble?.takeIf { it.isFinite() && it >= 0 }
        }.getOrNull()
        private fun consoleOutput(value: String) = message("message", "text" to value, "status" to "NORMAL")
        fun message(event: String, vararg attributes: Pair<String, String>): String =
            "##teamcity[$event " + attributes.joinToString(" ") { (key, value) -> "$key='${escape(value)}'" } + "]\n"
        private fun escape(value: String) = value.replace("|", "||").replace("'", "|'")
            .replace("\n", "|n").replace("\r", "|r").replace("[", "|[").replace("]", "|]")
    }
}
