package dev.openidea.goide.settings

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Path
import java.io.File

object GoToolchain {
    /** Captures settings in memory; executable resolution belongs to the background command path. */
    data class Settings(
        val goExecutable: String,
        val goplsExecutable: String,
        val delveExecutable: String,
        val buildTags: String,
        val goos: String,
        val goarch: String,
        val cgoEnabled: String,
        val environment: String,
    ) {
        val key: List<String> get() = listOf(goExecutable, goplsExecutable, delveExecutable, buildTags, goos, goarch, cgoEnabled, environment)
    }

    fun capture(project: Project): Settings = GoProjectSettings.get(project).let {
        Settings(it.goExecutable, it.goplsExecutable, it.delveExecutable, it.buildTags, it.goos, it.goarch, it.cgoEnabled, it.environment)
    }

    fun command(project: Project, tool: String, args: List<String>): GeneralCommandLine = command(project, tool, args, capture(project))

    fun command(project: Project, tool: String, args: List<String>, settings: Settings): GeneralCommandLine {
        val configured = when (tool) {
            "go" -> settings.goExecutable
            "gopls" -> settings.goplsExecutable
            "dlv" -> settings.delveExecutable
            else -> error("Unsupported Go tool: $tool")
        }
        val executable = resolve(tool, configured)
            ?: throw IllegalStateException("$tool was not found. Configure it in Settings > Go.")
        val cmd = GeneralCommandLine(executable).withParameters(args).withWorkDirectory(project.basePath)
        val go = resolve("go", settings.goExecutable)
        val environment = parseEnvironment(settings.environment).toMutableMap()
        val toolDirectories = listOfNotNull(
            go?.let { Path.of(it).parent.toString() },
            resolve("gopls", settings.goplsExecutable)?.let { Path.of(it).parent.toString() },
            resolve("dlv", settings.delveExecutable)?.let { Path.of(it).parent.toString() },
            Path.of(System.getProperty("user.home"), "go", "bin").toString(),
        )
        val inheritedPath = environment["PATH"] ?: System.getenv("PATH").orEmpty()
        environment["PATH"] = (toolDirectories + inheritedPath.split(File.pathSeparatorChar))
            .filter(String::isNotBlank).distinct().joinToString(File.pathSeparator)
        if (!environment.containsKey("GOROOT") && go != null) {
            runCatching { Path.of(go).toRealPath().parent.parent }.getOrNull()?.let { sdk ->
                if (Files.isDirectory(sdk.resolve("src/runtime"))) environment["GOROOT"] = sdk.toString()
            }
        }
        val tags = buildTags(settings.buildTags)
        if (tags.isNotEmpty()) {
            environment["GOFLAGS"] = listOfNotNull(
                environment["GOFLAGS"] ?: System.getenv("GOFLAGS"),
                "-tags=${tags.joinToString(",")}",
            ).filter(String::isNotBlank).joinToString(" ")
        }
        cmd.withEnvironment(if (tool == "gopls") goplsEnvironment(environment, cmd.parentEnvironment) else environment)
        // Dedicated target fields use the same precedence as gopls initialization.
        if (settings.goos.isNotBlank()) cmd.withEnvironment("GOOS", settings.goos)
        if (settings.goarch.isNotBlank()) cmd.withEnvironment("GOARCH", settings.goarch)
        if (settings.cgoEnabled.isNotBlank()) cmd.withEnvironment("CGO_ENABLED", settings.cgoEnabled)
        return cmd
    }

    /**
     * gopls 0.24 treats every explicit nonempty/non-off GOWORK as a file path,
     * whereas cmd/go also accepts "auto". Keep this adaptation at the gopls
     * boundary: an explicit empty value preserves workspace discovery and also
     * prevents gopls from falling back to an inherited "auto".
     *
     * Pure memory only. The caller's settings, parent environment and raw Go
     * commands are untouched; values other than the exact "auto" are preserved.
     * Launch preparation supplies the command's actual CONSOLE parent snapshot;
     * initialization passes only explicit project overrides and does not resolve
     * a console environment or read the IDE process's system environment.
     */
    internal fun goplsEnvironment(
        overrides: Map<String, String>,
        inherited: Map<String, String> = emptyMap(),
        windows: Boolean = System.getProperty("os.name").startsWith("Windows"),
    ): Map<String, String> {
        fun isGoWork(name: String) = name.equals("GOWORK", ignoreCase = windows)
        val own = overrides.entries.lastOrNull { isGoWork(it.key) }
        val effective = own?.value ?: inherited.entries.lastOrNull { isGoWork(it.key) }?.value
        val result = LinkedHashMap(overrides)
        // Windows' process environment is case insensitive, but gopls' Env map
        // lookup is not. Preserve the effective winner under its canonical key.
        if (own != null && windows) {
            result.keys.filter(::isGoWork).forEach { result.remove(it) }
            result["GOWORK"] = own.value
        }
        if (effective == "auto") result["GOWORK"] = ""
        return result
    }

    fun resolve(tool: String, configured: String = ""): String? {
        // An explicit SDK/tool remains authoritative, including an invalid path.
        // Resolve the installed plugin only for the empty/default selection.
        if (configured.isNotBlank()) return resolve(tool, configured, null)
        val bundled = if (ApplicationManager.getApplication() == null) null else
            PluginManagerCore.getPlugin(PluginId.getId("dev.openidea.go"))?.pluginPath?.resolve("tools")
        return resolve(tool, configured, bundled)
    }

    internal fun resolve(tool: String, configured: String, bundledTools: Path?): String? {
        if (configured.isNotBlank()) {
            val path = configured.trim().let {
                if (it.startsWith("~/")) Path.of(System.getProperty("user.home"), it.substring(2)) else runCatching { Path.of(it) }.getOrNull()
            } ?: return null
            return path.toAbsolutePath().normalize().takeIf { Files.isRegularFile(it) && Files.isExecutable(it) }?.toString()
        }
        bundledExecutable(tool, bundledTools)?.let { return it.toString() }
        // findInPath is an exact-name lookup; Windows PATH entries contain
        // go.exe/gopls.exe/dlv.exe (or standard PATHEXT shims), not bare names.
        PathEnvironmentVariableUtil.findExecutableInPathOnAnyOS(tool)?.let { return it.absolutePath }
        val home = Path.of(System.getProperty("user.home"))
        val filename = if (System.getProperty("os.name").startsWith("Windows")) "$tool.exe" else tool
        val candidates = mutableListOf(home.resolve("go/bin/$filename"), Path.of("/opt/homebrew/bin/$filename"), Path.of("/usr/local/go/bin/$filename"), Path.of("/usr/local/bin/$filename"))
        val mise = home.resolve(".local/share/mise/installs/go")
        if (Files.isDirectory(mise)) Files.list(mise).use { versions ->
            versions.sorted { a, b -> compareSDKVersions(b.fileName.toString(), a.fileName.toString()) }
                .forEach { candidates.add(it.resolve("bin/$filename")) }
        }
        return candidates.firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }?.toString()
    }

    /** The same relocatable plugin layout is used by the macOS and Windows installers. */
    internal fun bundledExecutable(tool: String, tools: Path?, windows: Boolean = System.getProperty("os.name").startsWith("Windows")): Path? {
        if (tools == null || tool !in setOf("go", "gofmt", "gopls", "dlv")) return null
        val filename = tool + if (windows) ".exe" else ""
        val executable = tools.resolve(if (tool == "go" || tool == "gofmt") "go/bin/$filename" else "bin/$filename")
        if (tool in setOf("go", "gofmt") && !Files.isDirectory(tools.resolve("go/src/runtime"))) return null
        return executable.toAbsolutePath().normalize().takeIf { Files.isRegularFile(it) && Files.isExecutable(it) }
    }

    fun buildTags(text: String): List<String> = text.split(Regex("[,\\s]+")).filter(String::isNotBlank).distinct()

    fun configurationKey(project: Project): List<String> = capture(project).key

    private fun compareSDKVersions(a: String, b: String): Int {
        val av = a.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val bv = b.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(av.size, bv.size)) {
            val result = (av.getOrElse(i) { 0 }).compareTo(bv.getOrElse(i) { 0 })
            if (result != 0) return result
        }
        return a.compareTo(b)
    }

    fun parseEnvironment(text: String): Map<String, String> = buildMap {
        text.lineSequence().map(String::trimStart).filter(String::isNotBlank).forEach { line ->
            val separator = line.indexOf('=')
            require(separator > 0 && '\u0000' !in line) { "Environment variables must use NAME=value, one per line." }
            val key = line.substring(0, separator).trim()
            require(key.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) { "Invalid environment variable name: $key" }
            put(key, line.substring(separator + 1))
        }
    }
}
