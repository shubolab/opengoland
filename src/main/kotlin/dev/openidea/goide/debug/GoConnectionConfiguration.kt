package dev.openidea.goide.debug

import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunConfigurationOptions
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.redhat.devtools.lsp4ij.dap.DebugAdapterManager
import com.redhat.devtools.lsp4ij.dap.configurations.DAPRunConfigurationBase
import com.redhat.devtools.lsp4ij.dap.configurations.DAPRunConfigurationOptionsBase
import dev.openidea.goide.lang.GoFileType
import org.jdom.Element
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane

class GoConnectionOptions : DAPRunConfigurationOptionsBase() {
    private val storedPid = string("").provideDelegate(this, "processId")
    private val storedHost = string("127.0.0.1").provideDelegate(this, "host")
    private val storedPort = string("38697").provideDelegate(this, "port")
    private val storedRemoteKind = string(GoRemoteServerKind.DAP_PROCESS.name).provideDelegate(this, "remoteKind")
    private val storedDirectory = string("").provideDelegate(this, "workingDirectory")
    private val storedEnvironment = string("").provideDelegate(this, "environmentVariables")
    private val storedMappings = string("").provideDelegate(this, "sourceMappings")
    private val storedHide = property(false).provideDelegate(this, "hideSystemGoroutines")
    var processId: String
        get() = storedPid.getValue(this).orEmpty(); set(value) = storedPid.setValue(this, value)
    var host: String
        get() = storedHost.getValue(this) ?: "127.0.0.1"; set(value) = storedHost.setValue(this, value)
    var port: String
        get() = storedPort.getValue(this) ?: "38697"; set(value) = storedPort.setValue(this, value)
    var remoteKind: String
        get() = storedRemoteKind.getValue(this) ?: GoRemoteServerKind.DAP_PROCESS.name; set(value) = storedRemoteKind.setValue(this, value)
    var workingDirectory: String
        get() = storedDirectory.getValue(this).orEmpty(); set(value) = storedDirectory.setValue(this, value)
    var environmentVariables: String
        get() = storedEnvironment.getValue(this).orEmpty(); set(value) = storedEnvironment.setValue(this, value)
    var sourceMappings: String
        get() = storedMappings.getValue(this).orEmpty(); set(value) = storedMappings.setValue(this, value)
    var hideSystemGoroutines: Boolean
        get() = storedHide.getValue(this); set(value) = storedHide.setValue(this, value)
}

abstract class GoConnectionConfiguration(project: Project, factory: ConfigurationFactory, name: String,
    val localAdapter: Boolean) : DAPRunConfigurationBase<GoConnectionOptions>(project, factory, name) {
    val goOptions: GoConnectionOptions get() = options as GoConnectionOptions
    override fun getOptionsClass(): Class<out RunConfigurationOptions> = GoConnectionOptions::class.java
    override fun getDebugAdapterServer() = DebugAdapterManager.getInstance().getDebugAdapterServerById("openidea.delve")
    override fun canRun(executorId: String) = executorId == DefaultDebugExecutor.EXECUTOR_ID
    override fun getConfigurationEditor(): SettingsEditor<GoConnectionConfiguration> = GoConnectionSettingsEditor(localAdapter)
    override fun checkConfiguration() {
        try { GoDebugConnection.fromOptions(goOptions, project.basePath.orEmpty(), localAdapter) }
        catch (error: IllegalArgumentException) { throw RuntimeConfigurationError(error.message.orEmpty()) }
    }
    override fun readExternal(element: Element) {
        super.readExternal(element)
        goOptions.apply {
            processId = element.getAttributeValue("goAttachPid", processId)
            host = element.getAttributeValue("goDapHost", host); port = element.getAttributeValue("goDapPort", port)
            remoteKind = element.getAttributeValue("goRemoteKind", remoteKind)
            workingDirectory = element.getAttributeValue("goAdapterDirectory", workingDirectory)
            environmentVariables = element.getAttributeValue("goAdapterEnvironment", environmentVariables)
            sourceMappings = element.getAttributeValue("goSourceMappings", sourceMappings)
            hideSystemGoroutines = element.getAttributeValue("goHideSystemGoroutines", hideSystemGoroutines.toString()).toBoolean()
        }
    }
    override fun writeExternal(element: Element) {
        super.writeExternal(element)
        val v = goOptions
        mapOf("goAttachPid" to v.processId, "goDapHost" to v.host, "goDapPort" to v.port, "goRemoteKind" to v.remoteKind,
            "goAdapterDirectory" to v.workingDirectory, "goAdapterEnvironment" to v.environmentVariables,
            "goSourceMappings" to v.sourceMappings, "goHideSystemGoroutines" to v.hideSystemGoroutines.toString()).forEach(element::setAttribute)
    }
    override fun clone(): GoConnectionConfiguration = (super.clone() as GoConnectionConfiguration).also { it.goOptions.copyFrom(goOptions) }
}
class GoLocalAttachConfiguration(project: Project, factory: ConfigurationFactory, name: String) : GoConnectionConfiguration(project, factory, name, true)
class GoRemoteDebugConfiguration(project: Project, factory: ConfigurationFactory, name: String) : GoConnectionConfiguration(project, factory, name, false)

class GoLocalAttachConfigurationType : ConfigurationType {
    private val factory = object : ConfigurationFactory(this) {
        override fun getId() = "OpenIDEA Go Attach"
        override fun createTemplateConfiguration(project: Project): RunConfiguration = GoLocalAttachConfiguration(project, this, "Go Attach")
    }
    override fun getId() = "OpenIDEAGoAttach"
    override fun getDisplayName() = "Go Attach to Process"
    override fun getConfigurationTypeDescription() = "Attach Delve DAP to a local Go process; Stop detaches without terminating the target"
    override fun getIcon() = GoFileType.INSTANCE.icon
    override fun getConfigurationFactories() = arrayOf(factory)
}
class GoRemoteDebugConfigurationType : ConfigurationType {
    private val factory = object : ConfigurationFactory(this) {
        override fun getId() = "OpenIDEA Go Remote Debug"
        override fun createTemplateConfiguration(project: Project): RunConfiguration = GoRemoteDebugConfiguration(project, this, "Go Remote Debug")
    }
    override fun getId() = "OpenIDEAGoRemoteDebug"
    override fun getDisplayName() = "Go Remote Debug (DAP)"
    override fun getConfigurationTypeDescription() = "Connect to an external Delve DAP endpoint without starting a local adapter"
    override fun getIcon() = GoFileType.INSTANCE.icon
    override fun getConfigurationFactories() = arrayOf(factory)
}

private class GoConnectionSettingsEditor(private val localAdapter: Boolean) : SettingsEditor<GoConnectionConfiguration>() {
    private val pid = JBTextField()
    private val host = JBTextField()
    private val port = JBTextField()
    private val serverKind = JComboBox(GoRemoteServerKind.entries.toTypedArray())
    private val directory = JBTextField()
    private val environment = JBTextArea(3, 40)
    private val mappings = JBTextArea(3, 40)
    private val hideSystem = JBCheckBox("Hide system goroutines", false)
    init { serverKind.addActionListener { updatePid() } }
    private fun updatePid() { pid.isEnabled = localAdapter || serverKind.selectedItem == GoRemoteServerKind.DAP_PROCESS }
    override fun createEditor(): JComponent {
        val form = FormBuilder.createFormBuilder()
        if (!localAdapter) form.addLabeledComponent("DAP server type:", serverKind)
            .addLabeledComponent("DAP host:", host).addLabeledComponent("DAP TCP port:", port)
        form.addLabeledComponent(if (localAdapter) "Local process ID:" else "Process ID on server:", pid)
        if (localAdapter) form.addLabeledComponent("Adapter working directory:", directory)
            .addLabeledComponent("Adapter environment (target is unchanged):", JScrollPane(environment))
        form.addLabeledComponent("Source paths (local prefix => remote prefix):", JScrollPane(mappings)).addComponent(hideSystem)
        val help = if (localAdapter) "A local dlv dap adapter is started. Stop requests a non-terminating detach. Resume before Stop to keep the target running."
        else "<html>DAP protocol only; this does not use Delve JSON-RPC.<br>For dlv dap, specify the target PID on that server.<br>For an existing headless session, start Delve with --headless --accept-multiclient.<br>Stop disconnects; a multi-client server retains the target's running or paused state.<br>Single-client headless servers can terminate targets they launched on disconnect.</html>"
        return form.addComponent(JBLabel(help)).addComponentFillVertically(JPanel(), 0).panel
    }
    override fun resetEditorFrom(configuration: GoConnectionConfiguration) {
        val v = configuration.goOptions
        pid.text = v.processId; host.text = v.host; port.text = v.port
        serverKind.selectedItem = runCatching { GoRemoteServerKind.valueOf(v.remoteKind) }.getOrDefault(GoRemoteServerKind.DAP_PROCESS)
        directory.text = v.workingDirectory.ifBlank { configuration.project.basePath.orEmpty() }
        environment.text = v.environmentVariables; mappings.text = v.sourceMappings; hideSystem.isSelected = v.hideSystemGoroutines
        updatePid()
    }
    override fun applyEditorTo(configuration: GoConnectionConfiguration) {
        configuration.goOptions.apply {
            processId = pid.text.trim(); host = this@GoConnectionSettingsEditor.host.text.trim(); port = this@GoConnectionSettingsEditor.port.text.trim()
            remoteKind = (serverKind.selectedItem as GoRemoteServerKind).name
            workingDirectory = directory.text.trim(); environmentVariables = environment.text; sourceMappings = mappings.text; hideSystemGoroutines = hideSystem.isSelected
        }
    }
}
