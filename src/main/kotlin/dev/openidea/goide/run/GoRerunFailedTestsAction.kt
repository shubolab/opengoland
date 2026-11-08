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

/** One invocation per failed package/test avoids Go -run's cross-product matching of slash components. */
class GoRerunFailedTestsAction(
    private val configuration: GoRunConfiguration, private val output: GoTestOutput,
    parameters: GoRunParameters = configuration.parameters, private val handler: ProcessHandler? = null,
    private val executor: Executor? = null,
) :
    AnAction("Rerun Failed Go Tests", "Rerun failed tests in their original packages", AllIcons.RunConfigurations.RerunFailedTests) {
    private val original = parameters.independentCopy()
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(event: AnActionEvent) {
        event.presentation.isEnabled = !configuration.project.isDisposed && handler?.isProcessTerminated != false && output.failures().isNotEmpty()
    }
    override fun actionPerformed(event: AnActionEvent) {
        val manager = RunManager.getInstance(configuration.project)
        val factory = configuration.factory ?: return
        GoTestRerunPlan.create(output.failures(), original).forEach { retry ->
            val settings = manager.createConfiguration(retry.name, factory)
            (settings.configuration as GoRunConfiguration).parameters = retry.parameters
            ProgramRunnerUtil.executeConfiguration(settings, executor ?: DefaultRunExecutor.getRunExecutorInstance())
        }
    }

    companion object {
        fun goQuoteMeta(value: String) = GoTestRerunPlan.quote(value)
    }
}
