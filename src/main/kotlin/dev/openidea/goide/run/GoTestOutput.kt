package dev.openidea.goide.run

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Path
import kotlin.math.roundToLong

/** Converts go test -json into native test-runner events, keyed by package and full test name. */
class GoTestOutput(@Suppress("UNUSED_PARAMETER") workingDirectory: String, private val packageDirectories: Map<String, String> = emptyMap()) : AutoCloseable {
    private data class Test(
        val name: String, val attempt: Int,
        var suite: Boolean = false, var started: Boolean = false, var finished: Boolean = false,
        var location: String? = null,
        val pending: GoPendingTestOutput = GoPendingTestOutput(),
    )
    private data class Package(
        val name: String,
        val tests: LinkedHashMap<String, Test> = linkedMapOf(),
        val attempts: MutableMap<String, Int> = linkedMapOf(),
        var finished: Boolean = false, var failedTest: Boolean = false,
    )
    private val packages = linkedMapOf<String, Package>()
    private val failed = linkedMapOf<String, MutableSet<String>>()
    private val pendingLine = StringBuilder()
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
                pkg.tests.values.filterNot { it.finished }.sortedByDescending { it.name.count { c -> c == '/' } }.forEach {
                    finishTest(pkg, it, "fail", null, emit, "Test process ended before reporting a result (exit $exitCode).")
                }
                if (pkg.tests.isEmpty() && exitCode != 0) packageFailure(pkg, emit)
                emit(message("testSuiteFinished", "name" to pkg.name, "nodeId" to packageId(pkg.name)))
                pkg.finished = true
            }
        } finally { close() }
    }

    @Synchronized
    fun failures(): Map<String, List<String>> = failed.mapValues { (_, names) ->
        names.filter { name -> name.isEmpty() || names.none { other -> other.startsWith("$name/") } }
    }

    private fun parseLine(value: String, emit: (String) -> Unit) {
        val event = try { JsonParser.parseString(value).takeIf { it.isJsonObject }?.asJsonObject } catch (_: RuntimeException) { null }
        val action = event?.string("Action")
        val packageName = event?.string("Package")
        if (event == null || action == null || packageName == null) { emit(consoleOutput("$value\n")); return }
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
                    pkg.tests.values.filterNot { it.finished }.sortedByDescending { it.name.count { c -> c == '/' } }.forEach {
                        finishTest(pkg, it, "fail", null, emit, "Package ended before reporting this test result.")
                    }
                    if (action == "fail" && !pkg.failedTest) packageFailure(pkg, emit)
                    // Native suite-finished events have no duration. Package elapsed remains in Go's package output.
                    emit(message("testSuiteFinished", "name" to packageName, "nodeId" to packageId(packageName)))
                    pkg.finished = true
                }
            }
            return
        }
        if (action == "run" && pkg.tests[name]?.finished == true) pkg.tests.remove(name)?.pending?.close()
        val test = ensureTest(pkg, name, emit)
        when (action) {
            "output" -> {
                val output = event.string("Output").orEmpty()
                if (test.location == null) test.location = findLocation(output, pkg.name)
                if (test.started) emitTestOutput(pkg, test, output, emit) else test.pending.append(output)
            }
            "pass", "fail", "skip" -> finishTest(pkg, test, action, event.elapsedSeconds(), emit)
        }
    }

    private fun ensureTest(pkg: Package, name: String, emit: (String) -> Unit): Test {
        val parentName = name.substringBeforeLast('/', "")
        if (parentName.isNotEmpty()) {
            val parent = ensureTest(pkg, parentName, emit)
            if (!parent.suite) { parent.suite = true; startTest(pkg, parent, emit) }
        }
        return pkg.tests.getOrPut(name) {
            val attempt = (pkg.attempts[name] ?: 0) + 1
            pkg.attempts[name] = attempt
            Test(name, attempt)
        }
    }

    private fun startTest(pkg: Package, test: Test, emit: (String) -> Unit) {
        if (test.started) return
        val parentName = test.name.substringBeforeLast('/', "")
        val attrs = mutableListOf("name" to displayName(test.name), "nodeId" to testId(pkg, test.name),
            "parentNodeId" to if (parentName.isEmpty()) packageId(pkg.name) else testId(pkg, parentName))
        test.location?.let { attrs += "locationHint" to it }
        emit(message(if (test.suite) "testSuiteStarted" else "testStarted", *attrs.toTypedArray()))
        test.started = true
        // SM aggregates child output into ancestor consoles; do not emit an extra root-level copy.
        test.pending.drain { emitTestOutput(pkg, test, it, emit) }
    }

    private fun emitTestOutput(pkg: Package, test: Test, output: String, emit: (String) -> Unit) =
        emit(message("testStdOut", "name" to displayName(test.name), "nodeId" to testId(pkg, test.name), "out" to output))

    private fun finishTest(pkg: Package, test: Test, action: String, seconds: Double?, emit: (String) -> Unit, reason: String? = null) {
        if (test.finished) return
        startTest(pkg, test, emit)
        val id = testId(pkg, test.name)
        if (action == "fail") {
            failed.getOrPut(pkg.name) { linkedSetOf() } += test.name
            pkg.failedTest = true
            if (!test.suite) emit(message("testFailed", "name" to displayName(test.name), "nodeId" to id,
                "message" to (reason ?: "Go test failed"), "details" to ""))
            else if (pkg.tests.values.none { child -> child.name.startsWith("${test.name}/") && failed[pkg.name]?.contains(child.name) == true }) {
                val own = "$id:parent-result"
                val attrs = mutableListOf("name" to "${displayName(test.name)} result", "nodeId" to own, "parentNodeId" to id)
                test.location?.let { attrs += "locationHint" to it }
                emit(message("testStarted", *attrs.toTypedArray()))
                emit(message("testFailed", "name" to "${displayName(test.name)} result", "nodeId" to own,
                    "message" to (reason ?: "Parent Go test failed"), "details" to ""))
                emit(message("testFinished", "name" to "${displayName(test.name)} result", "nodeId" to own))
            }
        } else if (action == "skip" && !test.suite) emit(message("testIgnored", "name" to displayName(test.name), "nodeId" to id, "message" to "Go test skipped"))
        val attrs = mutableListOf("name" to displayName(test.name), "nodeId" to id)
        if (!test.suite && seconds != null) attrs += "duration" to (seconds * 1000.0).roundToLong().coerceAtLeast(0).toString()
        emit(message(if (test.suite) "testSuiteFinished" else "testFinished", *attrs.toTypedArray()))
        test.finished = true
    }

    private fun packageFailure(pkg: Package, emit: (String) -> Unit) {
        failed.getOrPut(pkg.name) { linkedSetOf() } += ""
        val id = packageId(pkg.name) + ":build"
        emit(message("testStarted", "name" to "Package build or initialization", "nodeId" to id, "parentNodeId" to packageId(pkg.name)))
        emit(message("testFailed", "name" to "Package build or initialization", "nodeId" to id, "message" to "Go package failed", "details" to ""))
        emit(message("testFinished", "name" to "Package build or initialization", "nodeId" to id))
    }

    private fun findLocation(output: String, packageName: String): String? {
        val match = Regex("^\\s*([^\\r\\n]+\\.go):(\\d+)(?::\\d+)?(?::|\\s|$)", RegexOption.MULTILINE).find(output) ?: return null
        return try {
            val source = Path.of(match.groupValues[1])
            val file = if (source.isAbsolute) source.normalize() else packageDirectories[packageName]?.let { Path.of(it).resolve(source).normalize() } ?: return null
            "file://${file}:${match.groupValues[2]}"
        } catch (_: RuntimeException) { null }
    }

    private fun testId(pkg: Package, name: String) = "go-test:${pkg.name}#$name:${pkg.tests[name]?.attempt ?: 1}"

    @Synchronized
    override fun close() {
        packages.values.forEach { pkg -> pkg.tests.values.forEach { it.pending.close() } }
        pendingLine.setLength(0)
        closed = true
    }

    companion object {
        private const val MAX_LINE = 4 * 1024 * 1024
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
