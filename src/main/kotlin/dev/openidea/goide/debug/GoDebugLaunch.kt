package dev.openidea.goide.debug

import com.intellij.util.execution.ParametersListUtil
import com.redhat.devtools.lsp4ij.dap.configurations.DAPRunConfigurationOptionsBase
import dev.openidea.goide.run.GoRunKind
import dev.openidea.goide.run.GoRunParameters
import dev.openidea.goide.settings.GoToolchain
import java.nio.file.Files
import java.nio.file.Path

/** Immutable session input: editing a saved configuration cannot mutate a running launch. */
data class GoDebugLaunch(
    val program: String,
    val workingDirectory: String,
    val mode: String,
    val arguments: List<String>,
    val buildFlags: List<String>,
    val environment: Map<String, String>,
    val passParentEnvironment: Boolean = true,
) {
    fun dapParameters(name: String, projectEnvironment: Map<String, String>, projectTags: String): Map<String, Any> {
        val flags = buildFlags.toMutableList()
        if (flags.none { it == "-tags" || it.startsWith("-tags=") }) {
            val tags = GoToolchain.buildTags(projectTags)
            if (tags.isNotEmpty()) flags.add("-tags=${tags.joinToString(",")}")
        }
        return mapOf(
            "type" to "go", "request" to "launch", "name" to name,
            "mode" to mode, "program" to program,
            "cwd" to workingDirectory, "dlvCwd" to workingDirectory,
            "args" to arguments, "buildFlags" to flags,
            "env" to projectEnvironment + environment,
            "stopOnEntry" to false, "outputMode" to "remote",
        )
    }

    companion object {
        fun fromRun(kind: GoRunKind, parameters: GoRunParameters, projectRoot: String): GoDebugLaunch {
            parameters.validate()
            if (kind == GoRunKind.TEST) parameters.commandArguments(kind, "")
            val targets = parseArguments(parameters.target)
            require(targets.size == 1) { "Debug one Go package or source file at a time; select its package directory for multiple files." }
            val cwd = resolveWorkingDirectory(parameters.workingDirectory, projectRoot)
            val mode = if (kind == GoRunKind.TEST) "test" else "debug"
            val test = if (kind == GoRunKind.TEST) GoTestDebugArguments.create(
                parseArguments(parameters.buildFlags), parameters.testRuntimeArguments(debug = true) + parseArguments(parameters.arguments)) else null
            return GoDebugLaunch(
                resolveProgram(targets.single(), cwd, mode), cwd, mode, test?.arguments ?: parseArguments(parameters.arguments),
                test?.buildFlags ?: parseArguments(parameters.buildFlags), parameters.environment.toMap(), parameters.passParentEnvironment,
            )
        }

        fun fromOptions(options: GoDebugOptions, projectRoot: String): GoDebugLaunch {
            require(options.mode in setOf("debug", "test", "exec")) { "Select a valid Go debug mode." }
            val cwd = resolveWorkingDirectory(options.workingDirectory, projectRoot)
            val test = if (options.mode == "test") GoTestDebugArguments.create(
                parseArguments(options.buildFlags), parseArguments(options.arguments)) else null
            return GoDebugLaunch(
                resolveProgram(options.program, cwd, options.mode), cwd, options.mode,
                test?.arguments ?: parseArguments(options.arguments), test?.buildFlags ?: parseArguments(options.buildFlags),
                GoToolchain.parseEnvironment(options.environmentVariables).toMap(),
            )
        }

        private fun parseArguments(value: String): List<String> = ParametersListUtil.parse(value, false, true).toList()

        private fun resolveWorkingDirectory(value: String, projectRoot: String): String {
            val raw = value.ifBlank { projectRoot }
            require(raw.isNotBlank() && '\u0000' !in raw) { "Select a Go working directory." }
            val path = Path.of(raw).let { if (it.isAbsolute) it else Path.of(projectRoot).resolve(it) }.toAbsolutePath().normalize()
            require(Files.isDirectory(path)) { "Go working directory does not exist: $path" }
            return path.toString()
        }

        private fun resolveProgram(target: String, cwd: String, mode: String): String {
            require(target.isNotBlank() && '\u0000' !in target && !target.startsWith('-')) { "Select the Go package, source file, or executable to debug." }
            require(!target.contains("...")) { "Debug one concrete Go package; recursive package patterns cannot share one debug session." }
            val path = Path.of(target)
            val resolved = (if (path.isAbsolute) path else Path.of(cwd).resolve(path)).normalize()
            if (mode == "exec") {
                require(Files.isRegularFile(resolved) && Files.isExecutable(resolved)) { "Go executable does not exist or is not executable: $resolved" }
                return resolved.toString()
            }
            if (Files.exists(resolved)) return resolved.toString()
            require(!path.isAbsolute && !target.startsWith('.') && !target.endsWith(".go")) { "Go source path does not exist: $resolved" }
            // Import paths are valid go build operands; Delve resolves them from dlvCwd.
            return target
        }
    }
}

class GoDebugSessionOptions(val launch: GoDebugLaunch) : DAPRunConfigurationOptionsBase()
