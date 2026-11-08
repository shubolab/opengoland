package dev.openidea.goide.run

import com.intellij.execution.configuration.EnvironmentVariablesComponent
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.options.SettingsEditor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import java.awt.BorderLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.JComponent
import javax.swing.JPanel

class GoRunSettingsEditor(private val kind: GoRunKind) : SettingsEditor<GoRunConfiguration>() {
    private val target = JBTextField()
    private val workingDirectory = JBTextField()
    private val buildFlags = JBTextField()
    private val arguments = JBTextField()
    private val outputPath = JBTextField()
    private val testPattern = JBTextField()
    private val runAfterBuild = JBCheckBox("Run after build", true)
    private val environment = EnvironmentVariablesComponent()

    init { runAfterBuild.addActionListener { updateBuildFields() } }

    override fun resetEditorFrom(configuration: GoRunConfiguration) {
        with(configuration.parameters) {
            this@GoRunSettingsEditor.target.text = target
            this@GoRunSettingsEditor.workingDirectory.text = workingDirectory
            this@GoRunSettingsEditor.buildFlags.text = buildFlags
            this@GoRunSettingsEditor.arguments.text = arguments
            this@GoRunSettingsEditor.outputPath.text = outputPath
            this@GoRunSettingsEditor.testPattern.text = testPattern
            this@GoRunSettingsEditor.runAfterBuild.isSelected = runAfterBuild
            this@GoRunSettingsEditor.environment.envs = environment
            this@GoRunSettingsEditor.environment.isPassParentEnvs = passParentEnvironment
        }
        updateBuildFields()
    }

    override fun applyEditorTo(configuration: GoRunConfiguration) {
        val values = GoRunParameters(
            target = target.text.trim(), workingDirectory = workingDirectory.text.trim(),
            buildFlags = buildFlags.text, arguments = arguments.text,
            outputPath = outputPath.text.trim(), testPattern = testPattern.text,
            runAfterBuild = runAfterBuild.isSelected,
            environment = LinkedHashMap(environment.envs), passParentEnvironment = environment.isPassParentEnvs,
        )
        try { values.validate() } catch (error: IllegalArgumentException) { throw ConfigurationException(error.message.orEmpty()) }
        configuration.parameters = values
    }

    override fun createEditor(): JComponent {
        val form = JPanel(GridBagLayout())
        var row = 0
        fun field(label: String, component: JComponent) {
            form.add(JBLabel(label).also { it.labelFor = component }, GridBagConstraints().apply {
                gridx = 0; gridy = row; anchor = GridBagConstraints.LINE_END; insets = Insets(4, 0, 4, 10)
            })
            form.add(component, GridBagConstraints().apply {
                gridx = 1; gridy = row++; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL; insets = Insets(4, 0, 4, 0)
            })
        }
        field("Package / files:", target)
        target.toolTipText = "A package such as . or ./cmd/server; quote paths containing spaces. Go Test also accepts ./..."
        field("Working directory:", workingDirectory)
        field("Go tool arguments:", buildFlags)
        buildFlags.toolTipText = "Build or test flags, for example -tags=integration or -race. Project build tags apply unless overridden."
        if (kind == GoRunKind.TEST) {
            field("Test pattern:", testPattern)
            testPattern.toolTipText = "Go -run expression; leave empty to run every test."
            field("Test arguments:", arguments)
        } else {
            field("Program arguments:", arguments)
            field("Output directory / file:", outputPath)
            outputPath.toolTipText = "Used when Run after build is disabled; empty uses the Go tool default."
            field("", runAfterBuild)
        }
        field("Environment variables:", environment)
        updateBuildFields()
        return JPanel(BorderLayout()).apply { add(form, BorderLayout.NORTH) }
    }

    private fun updateBuildFields() {
        if (kind == GoRunKind.BUILD) {
            arguments.isEnabled = runAfterBuild.isSelected
            outputPath.isEnabled = !runAfterBuild.isSelected
        }
    }
}
