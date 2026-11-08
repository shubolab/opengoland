package dev.openidea.goide.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.xmlb.XmlSerializerUtil
import dev.openidea.goide.lint.GoLintCommands
import java.nio.file.Path
import javax.swing.JComponent
import javax.swing.JPanel

@Service(Service.Level.PROJECT)
@State(name = "OpenIDEAGoLintSettings", storages = [Storage("openidea-go-lint.xml")])
class GoLintSettings : PersistentStateComponent<GoLintSettings.Data> {
    class Data {
        var golangciExecutable = ""
        var vetFlags = ""
        var golangciFlags = ""
    }
    private val data = Data()
    override fun getState() = data
    override fun loadState(state: Data) = XmlSerializerUtil.copyBean(state, data)
    companion object { fun get(project: Project): Data = project.getService(GoLintSettings::class.java).state }
}

class GoLintConfigurable(private val project: Project) : Configurable {
    private val executable = JBTextField()
    private val vet = JBTextField()
    private val golangci = JBTextField()
    override fun getDisplayName() = "Go Inspections"
    override fun createComponent(): JComponent = FormBuilder.createFormBuilder()
        .addLabeledComponent("golangci-lint executable:", executable)
        .addComponent(JBLabel("Leave empty to discover golangci-lint on PATH. Versions 1 and 2 are supported."))
        .addLabeledComponent("Go Vet flags:", vet)
        .addLabeledComponent("golangci-lint flags:", golangci)
        .addComponent(JBLabel("Flags support quotes. Each selected module is inspected with ./...."))
        .addComponent(JBLabel("The Go SDK, target, environment and build tags come from Settings > Go."))
        .addComponent(JBLabel("JSON output is managed by open Goland. Project .golangci configuration is respected."))
        .addComponentFillVertically(JPanel(), 0).panel
    override fun reset() { GoLintSettings.get(project).let { executable.text = it.golangciExecutable; vet.text = it.vetFlags; golangci.text = it.golangciFlags } }
    override fun isModified(): Boolean = GoLintSettings.get(project).let { listOf(executable.text, vet.text, golangci.text) != listOf(it.golangciExecutable, it.vetFlags, it.golangciFlags) }
    override fun apply() {
        try {
            GoLintCommands.vet(vet.text)
            GoLintCommands.golangci(2, golangci.text, Path.of("inspection-report.json"), emptyList())
        } catch (error: IllegalArgumentException) { throw ConfigurationException(error.message ?: "Invalid inspection flags") }
        GoLintSettings.get(project).let { it.golangciExecutable = executable.text.trim(); it.vetFlags = vet.text.trim(); it.golangciFlags = golangci.text.trim() }
    }
}
