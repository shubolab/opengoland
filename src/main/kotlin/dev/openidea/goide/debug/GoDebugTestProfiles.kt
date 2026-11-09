package dev.openidea.goide.debug

import com.intellij.execution.configurations.RunConfiguration
import com.intellij.util.execution.ParametersListUtil
import dev.openidea.goide.run.GoRunConfiguration
import dev.openidea.goide.run.GoRunKind
import dev.openidea.goide.run.GoRunParameters
import dev.openidea.goide.settings.GoToolchain

/** Both real configuration types keep their identity; only their test inputs are normalized. */
object GoDebugTestProfiles {
    fun parameters(configuration: RunConfiguration): GoRunParameters = when (configuration) {
        is GoRunConfiguration -> {
            require(configuration.kind == GoRunKind.TEST)
            configuration.parameters.independentCopy()
        }
        is GoDebugConfiguration -> configuration.goOptions.let { options ->
            require(options.mode == "test")
            GoRunParameters(
                target = if (options.program.isBlank()) "" else ParametersListUtil.join(listOf(options.program)),
                workingDirectory = options.workingDirectory,
                buildFlags = options.buildFlags,
                arguments = options.arguments,
                environment = LinkedHashMap(GoToolchain.parseEnvironment(options.environmentVariables)),
            )
        }
        else -> error("Unsupported Go test profile")
    }

    internal fun launch(configuration: RunConfiguration, values: GoRunParameters, root: String): GoDebugLaunch =
        if (configuration is GoDebugConfiguration) GoDebugLaunch.fromOptions(configuration.goOptions, root)
        else GoDebugLaunch.fromRun(GoRunKind.TEST, values, root)

    /** Apply the existing exact retry plan to a clone of the original concrete profile. */
    fun applyRetry(configuration: RunConfiguration, values: GoRunParameters) {
        when (configuration) {
            is GoRunConfiguration -> configuration.parameters = values.independentCopy()
            is GoDebugConfiguration -> configuration.goOptions.apply {
                val targets = ParametersListUtil.parse(values.target, false, true)
                require(targets.size == 1) { "Debug one failed Go test package at a time" }
                mode = "test"
                program = targets.single()
                workingDirectory = values.workingDirectory
                buildFlags = values.buildFlags
                arguments = ParametersListUtil.join(values.testRuntimeArguments(debug = true) + ParametersListUtil.parse(values.arguments, false, true))
                environmentVariables = values.environment.entries.joinToString("\n") { (name, value) -> "$name=$value" }
            }
            else -> error("Unsupported Go test retry profile")
        }
    }
}
