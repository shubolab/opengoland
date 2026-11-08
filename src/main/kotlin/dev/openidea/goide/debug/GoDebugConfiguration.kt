package dev.openidea.goide.debug

import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunConfigurationOptions
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.redhat.devtools.lsp4ij.dap.DebugAdapterManager
import com.redhat.devtools.lsp4ij.dap.configurations.DAPRunConfigurationBase
import com.redhat.devtools.lsp4ij.dap.configurations.DAPRunConfigurationOptionsBase
import dev.openidea.goide.lang.GoFileType
import dev.openidea.goide.settings.GoToolchain
import org.jdom.Element
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane

class GoDebugOptions : DAPRunConfigurationOptionsBase() {
    private val storedProgram = string("").provideDelegate(this, "program")
    private val storedWorkingDirectory = string("").provideDelegate(this, "workingDirectory")
    private val storedMode = string("debug").provideDelegate(this, "mode")
    private val storedArguments = string("").provideDelegate(this, "arguments")
    private val storedBuildFlags = string("").provideDelegate(this, "buildFlags")
    private val storedEnvironment = string("").provideDelegate(this, "environmentVariables")
    var program: String
        get() = storedProgram.getValue(this).orEmpty()
        set(value) = storedProgram.setValue(this, value)
    var workingDirectory: String
        get() = storedWorkingDirectory.getValue(this).orEmpty()
        set(value) = storedWorkingDirectory.setValue(this, value)
    var mode: String
        get() = storedMode.getValue(this) ?: "debug"
        set(value) = storedMode.setValue(this, value)
    var arguments: String
        get() = storedArguments.getValue(this).orEmpty()
        set(value) = storedArguments.setValue(this, value)
    var buildFlags: String
        get() = storedBuildFlags.getValue(this).orEmpty()
        set(value) = storedBuildFlags.setValue(this, value)
    var environmentVariables: String
        get() = storedEnvironment.getValue(this).orEmpty()
        set(value) = storedEnvironment.setValue(this, value)
}

class GoDebugConfigurationType : ConfigurationType {
    private val factory = object : ConfigurationFactory(this) {
        override fun getId() = "OpenIDEA Go Debug"
        override fun createTemplateConfiguration(project: Project): RunConfiguration = GoDebugConfiguration(project, this, "Go Debug")
    }
    override fun getDisplayName() = "Go Debug"
    override fun getConfigurationTypeDescription() = "Debug a Go application, test package, or executable with Delve"
    override fun getIcon() = GoFileType.INSTANCE.icon
    override fun getId() = "OpenIDEAGoDebug"
    override fun getConfigurationFactories() = arrayOf(factory)
}

class GoDebugConfiguration(project: Project, factory: ConfigurationFactory, name: String) : DAPRunConfigurationBase<GoDebugOptions>(project, factory, name) {
    val goOptions: GoDebugOptions get() = options as GoDebugOptions
    override fun getOptionsClass(): Class<out RunConfigurationOptions> = GoDebugOptions::class.java
    override fun getDebugAdapterServer() = DebugAdapterManager.getInstance().getDebugAdapterServerById("openidea.delve")
    override fun canRun(executorId: String) = executorId == DefaultDebugExecutor.EXECUTOR_ID
    override fun getConfigurationEditor(): SettingsEditor<GoDebugConfiguration> = GoDebugSettingsEditor()
    override fun checkConfiguration() {
        try { GoDebugLaunch.fromOptions(goOptions, project.basePath.orEmpty()) }
        catch (error: IllegalArgumentException) { throw RuntimeConfigurationError(error.message.orEmpty()) }
    }
    override fun readExternal(element: Element) {
        super.readExternal(element)
        val s = goOptions
        s.program = element.getAttributeValue("goProgram", "")
        s.workingDirectory = element.getAttributeValue("goWorkingDirectory", "")
        s.mode = element.getAttributeValue("goMode", "debug")
        s.arguments = element.getAttributeValue("goArguments", "")
        s.buildFlags = element.getAttributeValue("goBuildFlags", "")
        s.environmentVariables = element.getAttributeValue("goEnvironment", "")
    }
    override fun writeExternal(element: Element) {
        super.writeExternal(element)
        val s = goOptions
        mapOf("goProgram" to s.program, "goWorkingDirectory" to s.workingDirectory, "goMode" to s.mode, "goArguments" to s.arguments, "goBuildFlags" to s.buildFlags, "goEnvironment" to s.environmentVariables).forEach(element::setAttribute)
    }

    override fun clone(): GoDebugConfiguration = (super.clone() as GoDebugConfiguration).also { it.goOptions.copyFrom(goOptions) }
}

class GoDebugSettingsEditor : SettingsEditor<GoDebugConfiguration>() {
    private val program = JBTextField()
    private val cwd = JBTextField()
    private val mode = JComboBox(arrayOf("debug", "test", "exec"))
    private val arguments = JBTextField()
    private val flags = JBTextField()
    private val environment = JBTextArea(4, 40)
    override fun createEditor(): JComponent = FormBuilder.createFormBuilder()
        .addLabeledComponent("Mode:", mode)
        .addLabeledComponent("Package directory / executable:", program)
        .addLabeledComponent("Working directory:", cwd)
        .addLabeledComponent("Program arguments:", arguments)
        .addLabeledComponent("Go build flags:", flags)
        .addLabeledComponent("Environment (NAME=value per line):", JScrollPane(environment))
        .addComponentFillVertically(JPanel(), 0).panel
    override fun resetEditorFrom(s: GoDebugConfiguration) {
        val v = s.goOptions
        program.text = v.program; cwd.text = v.workingDirectory.ifBlank { s.project.basePath ?: "" }
        mode.selectedItem = v.mode; arguments.text = v.arguments; flags.text = v.buildFlags; environment.text = v.environmentVariables
    }
    override fun applyEditorTo(s: GoDebugConfiguration) {
        val v = s.goOptions
        v.program = program.text.trim(); v.workingDirectory = cwd.text.trim(); v.mode = mode.selectedItem as String
        v.arguments = arguments.text; v.buildFlags = flags.text; v.environmentVariables = environment.text
    }
}
