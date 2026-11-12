package dev.openidea.goide.terminal

import com.intellij.openapi.project.Project
import dev.openidea.goide.settings.GoToolchain
import org.jetbrains.plugins.terminal.LocalTerminalCustomizer
import java.io.File

/** Make the project-selected Go toolchain authoritative in new local terminals. */
@Suppress("DEPRECATION")
class GoTerminalCustomizer : LocalTerminalCustomizer() {
    override fun customizeCommandAndEnvironment(
        project: Project,
        shellCommand: String?,
        command: Array<String>,
        envs: MutableMap<String, String>,
    ): Array<String> {
        GoTerminalEnvironment.apply(project, envs)
        return command
    }
}

internal object GoTerminalEnvironment {
    fun apply(project: Project, terminalEnvironment: MutableMap<String, String>) {
        val settings = GoToolchain.capture(project)
        val configured = GoToolchain.parseEnvironment(settings.environment)
        val command = GoToolchain.command(project, "go", emptyList(), settings)
        val shellPath = environmentValue(terminalEnvironment, "PATH")
        terminalEnvironment.putAll(configured)
        command.environment.forEach { (name, value) ->
            if (name == "GOROOT" || name == "GOFLAGS" || name == "GOOS" || name == "GOARCH" || name == "CGO_ENABLED") {
                putEnvironment(terminalEnvironment, name, value)
                putEnvironment(terminalEnvironment, "_INTELLIJ_FORCE_SET_$name", value)
            }
        }
        configured.filterKeys { !it.equals("PATH", ignoreCase = System.getProperty("os.name").startsWith("Windows")) }
            .forEach { (name, value) -> putEnvironment(terminalEnvironment, "_INTELLIJ_FORCE_SET_$name", value) }
        val projectPath = command.environment["PATH"].orEmpty()
        val combinedPath = (projectPath.split(File.pathSeparatorChar) + shellPath.split(File.pathSeparatorChar))
            .filter(String::isNotBlank).distinct().joinToString(File.pathSeparator)
        putEnvironment(terminalEnvironment, "PATH", combinedPath)
        val forcedPrefix = projectPath.split(File.pathSeparatorChar).take(4)
            .filter(String::isNotBlank).distinct().joinToString(File.pathSeparator)
        if (forcedPrefix.isNotEmpty()) putEnvironment(terminalEnvironment, "_INTELLIJ_FORCE_PREPEND_PATH", forcedPrefix)
    }

    private fun environmentValue(environment: Map<String, String>, name: String): String =
        if (System.getProperty("os.name").startsWith("Windows")) {
            environment.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value.orEmpty()
        } else environment[name].orEmpty()

    private fun putEnvironment(environment: MutableMap<String, String>, name: String, value: String) {
        if (System.getProperty("os.name").startsWith("Windows")) {
            environment.keys.firstOrNull { it.equals(name, ignoreCase = true) }?.let(environment::remove)
        }
        environment[name] = value
    }
}
