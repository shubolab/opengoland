package dev.openidea.goide.run

import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.runners.ProgramRunner
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import java.util.concurrent.atomic.AtomicBoolean

/** Like the platform compound state: real child executions, with no duplicate outer console. */
internal class GoFailedTestsRunState(
    private val project: Project,
    private val profiles: List<RunConfiguration>,
    private val stillOwned: () -> Boolean,
) : RunProfileState {
    private val dispatched = AtomicBoolean()

    override fun execute(executor: Executor, runner: ProgramRunner<*>): ExecutionResult? {
        if (project.isDisposed || !stillOwned() || !dispatched.compareAndSet(false, true)) return null
        ApplicationManager.getApplication().invokeLater({
            if (project.isDisposed || !stillOwned()) return@invokeLater
            val manager = RunManager.getInstance(project)
            val settings = profiles.map { profile ->
                // RunManager initializes tasks from the current template even
                // when supplied an existing profile. Preserve this retry's
                // frozen task list instead of substituting that template.
                val tasks = profile.beforeRunTasks.map { it.clone() }
                manager.createConfiguration(profile, requireNotNull(profile.factory)).also {
                    profile.beforeRunTasks = tasks
                }
            }
            // Starting the first retry may replace/dispose the original console.
            // Once this batch is accepted that must not drop its other exact paths.
            for (configuration in settings) {
                if (project.isDisposed) break
                ProgramRunnerUtil.executeConfiguration(configuration, executor)
            }
        }, project.disposed)
        return null
    }
}
