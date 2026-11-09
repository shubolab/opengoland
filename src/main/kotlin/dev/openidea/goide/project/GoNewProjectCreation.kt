package dev.openidea.goide.project

import com.intellij.ide.impl.OpenProjectTask
import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import dev.openidea.goide.settings.GoProjectSettings
import dev.openidea.goide.settings.GoToolchain
import java.nio.file.Path

/** Immutable UI input. An empty SDK selection means the relocatable bundled default. */
internal data class GoNewProjectSettings(
    val name: String,
    val location: String,
    val modulePath: String,
    val configuredExecutable: String,
    val createExample: Boolean,
    val environment: String = "",
) {
    fun directory(): Path {
        require(name.isNotBlank() && name !in setOf(".", "..") && name.none { it == '/' || it == '\\' || it == ':' || it == '\u0000' }) {
            "Enter a project name without path separators."
        }
        val parent = Path.of(location).normalize()
        require(parent.isAbsolute) { "Choose an absolute project location." }
        val destination = parent.resolve(name).normalize()
        require(destination.parent == parent) { "Choose a project name below the selected location." }
        return destination
    }

    fun validate() {
        require('\u0000' !in configuredExecutable) { "Choose a valid Go SDK." }
        // The executable is resolved on the background worker, including automatic selection.
        GoModuleRequest(modulePath, directory(), "go", createExample).validate()
        GoToolchain.parseEnvironment(environment)
    }
}

/** Generation and publication are separate so an invalid/cancelled module never opens a project. */
internal class GoNewProjectCreator(
    private val openProject: (Path, OpenProjectTask) -> Project? = ProjectUtil::openOrImport,
) {
    data class Created(val directory: Path, val settings: GoNewProjectSettings, val sdk: GoModuleSdk) {
        val persistedExecutable: String get() = if (settings.configuredExecutable.isBlank()) "" else sdk.executable.toString()
    }

    fun generate(settings: GoNewProjectSettings, cancelled: () -> Boolean, output: (String) -> Unit = {}): Created {
        check(!ApplicationManager.getApplication().isDispatchThread) { "Go project creation must run in the background." }
        settings.validate()
        GoModuleProcesses.checkCancelled(cancelled)
        val executable = GoToolchain.resolve("go", settings.configuredExecutable)
            ?: throw IllegalArgumentException("The selected Go SDK is unavailable. Choose a complete Go installation or use the bundled SDK.")
        val generated = GoModuleGenerator.generateVerified(
            GoModuleRequest(settings.modulePath, settings.directory(), executable, settings.createExample), cancelled, output)
        return Created(generated.directory, settings, generated.sdk)
    }

    fun openOptions(created: Created, owner: Project?): OpenProjectTask = OpenProjectTask.build().copy(
        projectToClose = owner,
        isNewProject = true,
        runConfigurators = true,
        isProjectCreatedWithWizard = true,
        projectName = created.settings.name,
        // The platform decides whether to reuse a window or ask the user.
        beforeInit = { project ->
            GoProjectSettings.get(project).apply {
                goExecutable = created.persistedExecutable
                environment = created.settings.environment
            }
        },
    )

    fun open(created: Created, owner: Project?, cancelled: () -> Boolean): Project? {
        check(ApplicationManager.getApplication().isDispatchThread)
        if (cancelled() || owner?.isDisposed == true) return null
        return openProject(created.directory, openOptions(created, owner))
    }
}
