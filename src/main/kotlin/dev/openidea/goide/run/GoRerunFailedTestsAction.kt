package dev.openidea.goide.run

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.Executor
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.PreferredPlace
import com.intellij.execution.runners.RunTab
import com.intellij.execution.testframework.actions.AbstractRerunFailedTestsAction
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView
import com.intellij.openapi.Disposable
import com.intellij.openapi.module.Module
import com.intellij.openapi.util.Disposer
import dev.openidea.goide.debug.GoDebugTestProfiles

/** One invocation per failed package/test avoids Go -run's cross-product matching of slash components. */
class GoRerunFailedTestsAction(
    private val configuration: RunConfiguration, private val output: GoTestOutput,
    parameters: GoRunParameters, private val handler: ProcessHandler? = null,
    private val executor: Executor? = null,
    console: SMTRunnerConsoleView,
) : AbstractRerunFailedTestsAction(console) {
    constructor(configuration: GoRunConfiguration, output: GoTestOutput, parameters: GoRunParameters = configuration.parameters,
        handler: ProcessHandler? = null, executor: Executor? = null, console: SMTRunnerConsoleView) :
        this(configuration as RunConfiguration, output, parameters, handler, executor, console)
    private val original = parameters.independentCopy()
    private val originalProfile = configuration.clone()
    @Volatile private var disposed = false

    init {
        init(console.properties)
        setModelProvider { if (disposed) null else console.resultsViewer }
        templatePresentation.text = "Rerun Failed Go Tests"
        templatePresentation.description = "Rerun failed tests in their original packages"
        templatePresentation.icon = AllIcons.RunConfigurations.RerunFailedTests
        if ((executor ?: console.properties.executor).id == DefaultDebugExecutor.EXECUTOR_ID)
            templatePresentation.putClientProperty(RunTab.PREFERRED_PLACE, PreferredPlace.MORE_GROUP)
        Disposer.register(console, Disposable {
            disposed = true
            unregisterCustomShortcutSet(console.component)
        })
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun isActive(event: AnActionEvent) = event.project === configuration.project && canRetry()

    private fun canRetry() = !disposed && !configuration.project.isDisposed &&
        handler?.isProcessTerminated != false && output.failures().isNotEmpty()

    protected override fun getRunProfile(environment: ExecutionEnvironment): MyRunProfile? {
        if (environment.project !== configuration.project || !canRetry()) return null
        val profiles = GoTestRerunPlan.create(output.failures(), original).map { retry ->
            val profile = originalProfile.clone().apply { name = retry.name }
            GoDebugTestProfiles.applyRetry(profile, retry.parameters)
            profile
        }
        if (profiles.isEmpty()) return null
        // The platform calls this getter before optional Run/Debug selection.
        // It only captures a plan; each selected concrete profile is launched
        // through ExecutionManager when the typed state actually executes.
        // WrappingRunConfiguration inherits its peer's Before Launch tasks.
        // Only the concrete retries own those tasks; the dispatch-only wrapper
        // must not execute an extra copy before it starts the batch.
        val dispatchPeer = (originalProfile.clone() as RunConfigurationBase<*>).apply { beforeRunTasks = emptyList() }
        return object : MyRunProfile(dispatchPeer) {
            override fun getModules(): Array<Module> = Module.EMPTY_ARRAY
            override fun getState(executor: Executor, environment: ExecutionEnvironment) =
                GoFailedTestsRunState(configuration.project, profiles) { !disposed }
        }
    }

    companion object {
        fun goQuoteMeta(value: String) = GoTestRerunPlan.quote(value)
    }
}
