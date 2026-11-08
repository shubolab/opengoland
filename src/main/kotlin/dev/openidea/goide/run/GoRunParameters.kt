package dev.openidea.goide.run

import com.intellij.util.execution.ParametersListUtil

enum class GoRunKind { BUILD, TEST }
enum class GoTestMode(private val label: String) {
    TEST("gotest"), BENCHMARK("gobench"), FUZZ("go test -fuzz");
    override fun toString() = label
}

data class GoRunParameters(
    var target: String = ".",
    var workingDirectory: String = "",
    var buildFlags: String = "",
    var arguments: String = "",
    var environment: MutableMap<String, String> = linkedMapOf(),
    var passParentEnvironment: Boolean = true,
    var runAfterBuild: Boolean = true,
    var outputPath: String = "",
    var testPattern: String = "",
    var testMode: GoTestMode = GoTestMode.TEST,
    var benchmarkPattern: String = ".",
    var benchmarkTime: String = "1s",
    var benchmarkMemory: Boolean = true,
    var fuzzPattern: String = "",
    var fuzzTime: String = "30s",
    var fuzzMinimizeTime: String = "60s",
    var fuzzParallelism: String = "",
) {
    fun validate() {
        require(target.isNotBlank()) { "Specify a Go package or source file." }
        require('\u0000' !in target + workingDirectory + buildFlags + arguments + outputPath + testPattern +
            benchmarkPattern + benchmarkTime + fuzzPattern + fuzzTime + fuzzMinimizeTime + fuzzParallelism) {
            "Go configuration values cannot contain NUL characters."
        }
        environment.forEach { (key, value) ->
            require(key.isNotEmpty() && '=' !in key && '\u0000' !in key && '\u0000' !in value) {
                "Invalid environment variable name or value."
            }
        }
        require(targets().none { it.startsWith('-') }) { "Put Go flags in the Go tool arguments field." }
        require(targets().all(String::isNotBlank)) { "Specify a nonempty Go package or source file." }
    }

    fun commandArguments(kind: GoRunKind, projectTags: String, coverageProfile: String? = null): List<String> {
        validate()
        val flags = ParametersListUtil.parse(buildFlags, false, true)
        if (kind == GoRunKind.TEST) validateTestWorkflow()
        if (kind == GoRunKind.TEST) {
            require(flags.none { it == "-json" || it.startsWith("-json=") }) { "The IDE manages -json for the Go test results tree." }
            require(flags.none { it in setOf("-args", "--args", "--") }) { "Put -args and custom test arguments in the Test arguments field." }
            if (testMode != GoTestMode.TEST)
                GoTestWorkflow.rejectManagedFlags(flags + ParametersListUtil.parse(arguments, false, true), testMode)
        }
        if (coverageProfile != null) {
            require(kind == GoRunKind.TEST && testMode == GoTestMode.TEST) { "Run with Coverage is available for the gotest framework." }
            require(coverageProfile.isNotBlank() && '\u0000' !in coverageProfile) { "Invalid coverage profile path." }
            val allFlags = flags + ParametersListUtil.parse(arguments, false, true)
            require(allFlags.none { it == "-coverprofile" || it.startsWith("-coverprofile=") || it == "-test.coverprofile" || it.startsWith("-test.coverprofile=") }) {
                "Remove -coverprofile from the configuration; Run with Coverage manages the profile file."
            }
        }
        val explicitTags = flags.any { it == "-tags" || it.startsWith("-tags=") }
        val tags = projectTags.split(',', ' ', '\n', '\t').filter(String::isNotBlank)
        return buildList {
            add(if (kind == GoRunKind.TEST) "test" else if (runAfterBuild) "run" else "build")
            if (kind == GoRunKind.TEST) add("-json")
            coverageProfile?.let { add("-coverprofile=$it") }
            if (!explicitTags && tags.isNotEmpty()) add("-tags=${tags.joinToString(",")}")
            addAll(flags)
            if (kind == GoRunKind.TEST) addAll(testRuntimeArguments())
            if (kind == GoRunKind.BUILD && !runAfterBuild && outputPath.isNotBlank()) {
                add("-o")
                add(outputPath)
            }
            addAll(targets())
            if (kind == GoRunKind.TEST || runAfterBuild) addAll(ParametersListUtil.parse(arguments, false, true))
        }
    }

    private fun targets(): List<String> = ParametersListUtil.parse(target, false, true).ifEmpty { listOf(".") }

    fun validateTestWorkflow() {
        when (testMode) {
            GoTestMode.TEST -> Unit
            GoTestMode.BENCHMARK -> {
                require(benchmarkPattern.isNotBlank()) { "Enter a benchmark pattern, for example . or ^BenchmarkEncode$." }
                GoTestWorkflow.validateDurationOrCount(benchmarkTime, "Benchmark time")
            }
            GoTestMode.FUZZ -> {
                require(fuzzPattern.isNotBlank()) { "Enter a pattern matching one fuzz function, for example ^FuzzDecode$." }
                require(targets().size == 1 && targets().none { "..." in it }) { "Fuzz one concrete Go package at a time." }
                if (fuzzTime.isNotBlank()) GoTestWorkflow.validateDurationOrCount(fuzzTime, "Fuzz time")
                if (fuzzMinimizeTime.isNotBlank()) GoTestWorkflow.validateDurationOrCount(fuzzMinimizeTime, "Fuzz minimization time", allowZero = true)
                require(fuzzParallelism.isBlank() || fuzzParallelism.toIntOrNull()?.let { it > 0 } == true) {
                    "Fuzz workers must be a positive integer, or empty to use the Go default."
                }
            }
        }
    }

    /** Delve compiles a test binary: fuzz Debug deliberately executes its seed corpus. */
    fun testRuntimeArguments(debug: Boolean = false): List<String> {
        validateTestWorkflow()
        return buildList {
            when (testMode) {
                GoTestMode.TEST -> if (testPattern.isNotBlank()) addAll(listOf("-run", testPattern))
                GoTestMode.BENCHMARK -> {
                    addAll(listOf("-run", testPattern.ifBlank { "^$" }, "-bench", benchmarkPattern, "-benchtime", benchmarkTime))
                    if (benchmarkMemory) add("-benchmem")
                }
                GoTestMode.FUZZ -> {
                    if (debug) addAll(listOf("-run", fuzzPattern))
                    else {
                        addAll(listOf("-run", testPattern.ifBlank { "^$" }, "-fuzz", fuzzPattern))
                        if (fuzzTime.isNotBlank()) addAll(listOf("-fuzztime", fuzzTime))
                        if (fuzzMinimizeTime.isNotBlank()) addAll(listOf("-fuzzminimizetime", fuzzMinimizeTime))
                    }
                    if (fuzzParallelism.isNotBlank()) addAll(listOf("-parallel", fuzzParallelism))
                }
            }
        }
    }

    fun independentCopy() = copy(environment = LinkedHashMap(environment))
}
