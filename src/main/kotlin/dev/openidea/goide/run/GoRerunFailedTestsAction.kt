package dev.openidea.goide.run

import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.Executor
import com.intellij.execution.configurations.RunConfiguration
import dev.openidea.goide.debug.GoDebugTestProfiles

/** One invocation per failed package/test avoids Go -run's cross-product matching of slash components. */
class GoRerunFailedTestsAction(
    private val configuration: RunConfiguration, private val output: GoTestOutput,
    parameters: GoRunParameters, private val handler: ProcessHandler? = null,
    private val executor: Executor? = null,
) :
    AnAction("Rerun Failed Go Tests", "Rerun failed tests in their original packages", AllIcons.RunConfigurations.RerunFailedTests) {
    constructor(configuration: GoRunConfiguration, output: GoTestOutput, parameters: GoRunParameters = configuration.parameters,
        handler: ProcessHandler? = null, executor: Executor? = null) : this(configuration as RunConfiguration, output, parameters, handler, executor)
    private val original = parameters.independentCopy()
    private val originalProfile = configuration.clone()
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(event: AnActionEvent) {
        event.presentation.isEnabled = !configuration.project.isDisposed && handler?.isProcessTerminated != false && output.failures().isNotEmpty()
    }
    override fun actionPerformed(event: AnActionEvent) {
        val manager = RunManager.getInstance(configuration.project)
        val factory = configuration.factory ?: return
        GoTestRerunPlan.create(output.failures(), original).forEach { retry ->
            val profile = originalProfile.clone().apply { name = retry.name }
            GoDebugTestProfiles.applyRetry(profile, retry.parameters)
            val settings = manager.createConfiguration(profile, factory)
            ProgramRunnerUtil.executeConfiguration(settings, executor ?: DefaultRunExecutor.getRunExecutorInstance())
        }
    }

    companion object {
        fun goQuoteMeta(value: String) = GoTestRerunPlan.quote(value)
    }
}
