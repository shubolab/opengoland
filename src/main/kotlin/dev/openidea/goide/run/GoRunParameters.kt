package dev.openidea.goide.run

import com.intellij.util.execution.ParametersListUtil

enum class GoRunKind { BUILD, TEST }

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
) {
    fun validate() {
        require(target.isNotBlank()) { "Specify a Go package or source file." }
        require('\u0000' !in target + workingDirectory + buildFlags + arguments + outputPath + testPattern) {
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
        if (kind == GoRunKind.TEST) require(flags.none { it == "-json" || it.startsWith("-json=") }) {
            "The IDE manages -json for the Go test results tree."
        }
        if (coverageProfile != null) {
            require(kind == GoRunKind.TEST) { "Coverage is available for Go Test configurations." }
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
            if (kind == GoRunKind.TEST && testPattern.isNotBlank()) {
                add("-run")
                add(testPattern)
            }
            if (kind == GoRunKind.BUILD && !runAfterBuild && outputPath.isNotBlank()) {
                add("-o")
                add(outputPath)
            }
            addAll(targets())
            if (kind == GoRunKind.TEST || runAfterBuild) addAll(ParametersListUtil.parse(arguments, false, true))
        }
    }

    private fun targets(): List<String> = ParametersListUtil.parse(target, false, true).ifEmpty { listOf(".") }

    fun independentCopy() = copy(environment = LinkedHashMap(environment))
}
