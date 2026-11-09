package dev.openidea.goide.run

import com.google.gson.JsonStreamParser
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.util.execution.ParametersListUtil
import dev.openidea.goide.settings.GoProjectSettings
import dev.openidea.goide.settings.GoToolchain
import dev.openidea.goide.coverage.GoCoverageModule
import java.nio.file.Path

/** Uses the selected Go toolchain's package metadata, not filename guesses, for test failure links. */
data class GoPackageMetadata(val directories: Map<String, String>, val modules: List<GoCoverageModule>)

object GoPackageDirectories {
    fun load(project: Project, values: GoRunParameters, cwd: Path): Map<String, String> = loadMetadata(project, values, cwd).directories

    fun loadMetadata(project: Project, values: GoRunParameters, cwd: Path): GoPackageMetadata =
        loadMetadata(project, values, cwd, GoToolchain.capture(project))

    fun loadMetadata(project: Project, values: GoRunParameters, cwd: Path, settings: GoToolchain.Settings): GoPackageMetadata {
        return runCatching {
            val options = ParametersListUtil.parse(values.buildFlags, false, true)
            val metadataFlags = mutableListOf<String>()
            var index = 0
            while (index < options.size) {
                val flag = options[index++]
                if (flag in setOf("-tags", "-mod", "-modfile", "-overlay", "-compiler")) {
                    metadataFlags += flag
                    options.getOrNull(index++)?.let(metadataFlags::add)
                } else if (listOf("-tags=", "-mod=", "-modfile=", "-overlay=", "-compiler=").any(flag::startsWith)) metadataFlags += flag
            }
            if (metadataFlags.none { it == "-tags" || it.startsWith("-tags=") }) {
                val tags = settings.buildTags.split(Regex("[,\\s]+")).filter(String::isNotBlank)
                if (tags.isNotEmpty()) metadataFlags += "-tags=${tags.joinToString(",")}"
            }
            val targets = ParametersListUtil.parse(values.target, false, true).ifEmpty { listOf(".") }
            val command = GoToolchain.command(project, "go", listOf("list", "-e", "-json=ImportPath,Dir,Module") + metadataFlags + targets, settings)
                .withWorkingDirectory(cwd).withEnvironment(values.environment)
            if (!values.passParentEnvironment) command.withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.NONE)
            val handler = CapturingProcessHandler(command)
            handler.setShouldDestroyProcessRecursively(true)
            val cleanup = Disposable { if (!handler.isProcessTerminated) handler.destroyProcess() }
            Disposer.register(project, cleanup)
            try {
                val output = handler.runProcess(10_000, true)
                if (output.isTimeout || output.isCancelled || output.stdout.length > 16 * 1024 * 1024) GoPackageMetadata(emptyMap(), emptyList())
                else parseMetadata(output.stdout)
            } finally { Disposer.dispose(cleanup) }
        }.getOrDefault(GoPackageMetadata(emptyMap(), emptyList()))
    }

    fun parse(jsonStream: String): Map<String, String> = parseMetadata(jsonStream).directories

    fun parseMetadata(jsonStream: String): GoPackageMetadata {
        val result = linkedMapOf<String, String>()
        val ambiguous = mutableSetOf<String>()
        val modules = linkedMapOf<String, String>()
        val ambiguousModules = mutableSetOf<String>()
        val parser = JsonStreamParser(jsonStream)
        while (parser.hasNext()) {
            val entry = parser.next().takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val name = entry.get("ImportPath")?.takeUnless { it.isJsonNull }?.asString ?: continue
            val directory = entry.get("Dir")?.takeUnless { it.isJsonNull }?.asString ?: continue
            val path = runCatching { Path.of(directory).normalize() }.getOrNull()?.takeIf { it.isAbsolute } ?: continue
            val previous = result.putIfAbsent(name, path.toString())
            if (previous != null && previous != path.toString()) ambiguous += name
            val module = entry.get("Module")?.takeIf { it.isJsonObject }?.asJsonObject
            val modulePath = module?.get("Path")?.takeUnless { it.isJsonNull }?.asString
            val replacement = module?.get("Replace")?.takeIf { it.isJsonObject }?.asJsonObject
            val moduleDirectory = (replacement?.get("Dir") ?: module?.get("Dir"))?.takeUnless { it.isJsonNull }?.asString
            if (modulePath != null && moduleDirectory != null) {
                val moduleRoot = runCatching { Path.of(moduleDirectory).normalize() }.getOrNull()?.takeIf { it.isAbsolute }
                if (moduleRoot != null) {
                    val previousModule = modules.putIfAbsent(modulePath, moduleRoot.toString())
                    if (previousModule != null && previousModule != moduleRoot.toString()) ambiguousModules += modulePath
                }
            } else if (name == "command-line-arguments") modules[name] = path.toString()
        }
        ambiguous.forEach(result::remove)
        ambiguousModules.forEach(modules::remove)
        return GoPackageMetadata(result, modules.map { (name, directory) -> GoCoverageModule(name, directory) })
    }
}
