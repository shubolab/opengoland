package dev.openidea.goide.run

import com.intellij.execution.Executor
import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.executors.DefaultDebugExecutor
import com.redhat.devtools.lsp4ij.dap.DebugAdapterManager
import com.redhat.devtools.lsp4ij.dap.configurations.DAPCommandLineState
import com.redhat.devtools.lsp4ij.dap.configurations.DAPRunConfigurationBase
import dev.openidea.goide.debug.GoDebugDescriptor
import dev.openidea.goide.debug.GoDebugLaunch
import dev.openidea.goide.debug.GoDebugSessionOptions
import com.intellij.icons.AllIcons
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import org.jdom.Element

class GoBuildConfigurationType : ConfigurationTypeBase(
    "OpenIDEAGoBuild", "Go Build", "Build and run a Go package or source file", AllIcons.RunConfigurations.Application,
) {
    init { addFactory(GoConfigurationFactory(this, GoRunKind.BUILD)) }
}

class GoTestConfigurationType : ConfigurationTypeBase(
    "OpenIDEAGoTest", "Go Test", "Run Go tests with a structured results tree", AllIcons.RunConfigurations.TestState.Run,
) {
    init { addFactory(GoConfigurationFactory(this, GoRunKind.TEST)) }
}

class GoConfigurationFactory(type: ConfigurationTypeBase, val kind: GoRunKind) : ConfigurationFactory(type) {
    override fun getId() = if (kind == GoRunKind.TEST) "OpenIDEAGoTest" else "OpenIDEAGoBuild"
    override fun createTemplateConfiguration(project: Project): RunConfiguration =
        GoRunConfiguration(project, this, if (kind == GoRunKind.TEST) "Go Test" else "Go Build", kind).apply {
            parameters.workingDirectory = project.basePath.orEmpty()
        }
    override fun isEditableInDumbMode() = true
}

class GoRunConfiguration(
    project: Project,
    factory: ConfigurationFactory,
    name: String,
    val kind: GoRunKind,
) : DAPRunConfigurationBase<Element>(project, factory, name) {
    var parameters = GoRunParameters()

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> = GoRunSettingsEditor(kind)
    // LSP4IJ's generic runner must not claim Run/Coverage, which use native consoles.
    override fun canRun(executorId: String) = executorId == DefaultDebugExecutor.EXECUTOR_ID
    override fun getDebugAdapterServer() = DebugAdapterManager.getInstance().getDebugAdapterServerById("openidea.delve")
    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState {
        if (executor.id != DefaultDebugExecutor.EXECUTOR_ID) return GoCommandLineState(environment, this)
        val launch = try { GoDebugLaunch.fromRun(kind, parameters.independentCopy(), project.basePath.orEmpty()) }
            catch (error: IllegalArgumentException) { throw ExecutionException(error.message.orEmpty(), error) }
        val sessionOptions = GoDebugSessionOptions(launch)
        return DAPCommandLineState(GoDebugDescriptor(sessionOptions, environment, debugAdapterServer), sessionOptions, environment)
    }
    override fun checkConfiguration() {
        try { parameters.validate() } catch (error: IllegalArgumentException) { throw RuntimeConfigurationError(error.message.orEmpty()) }
    }
    override fun excludeCompileBeforeLaunchOption() = true

    override fun readExternal(element: Element) {
        super.readExternal(element)
        val saved = element.getChild("openidea-go") ?: return
        parameters = GoRunParameters(
            target = saved.getAttributeValue("target") ?: ".",
            workingDirectory = saved.getAttributeValue("workingDirectory") ?: project.basePath.orEmpty(),
            buildFlags = saved.getAttributeValue("buildFlags").orEmpty(),
            arguments = saved.getAttributeValue("arguments").orEmpty(),
            runAfterBuild = saved.getAttributeValue("runAfterBuild") != "false",
            passParentEnvironment = saved.getAttributeValue("passParentEnvironment") != "false",
            outputPath = saved.getAttributeValue("outputPath").orEmpty(),
            testPattern = saved.getAttributeValue("testPattern").orEmpty(),
            environment = saved.getChildren("env").associate { it.getAttributeValue("name").orEmpty() to it.getAttributeValue("value").orEmpty() }.toMutableMap(),
        )
    }

    override fun writeExternal(element: Element) {
        super.writeExternal(element)
        element.removeChildren("openidea-go")
        val saved = Element("openidea-go")
            .setAttribute("target", parameters.target)
            .setAttribute("workingDirectory", parameters.workingDirectory)
            .setAttribute("buildFlags", parameters.buildFlags)
            .setAttribute("arguments", parameters.arguments)
            .setAttribute("runAfterBuild", parameters.runAfterBuild.toString())
            .setAttribute("passParentEnvironment", parameters.passParentEnvironment.toString())
            .setAttribute("outputPath", parameters.outputPath)
            .setAttribute("testPattern", parameters.testPattern)
        parameters.environment.forEach { (name, value) -> saved.addContent(Element("env").setAttribute("name", name).setAttribute("value", value)) }
        element.addContent(saved)
    }

    override fun clone(): GoRunConfiguration = (super.clone() as GoRunConfiguration).also { it.parameters = parameters.independentCopy() }
}
