package dev.openidea.goide.run

import com.intellij.execution.configuration.EnvironmentVariablesComponent
import com.intellij.openapi.ui.ComboBox
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
    private val testMode = ComboBox(GoTestMode.entries.toTypedArray())
    private val benchmarkPattern = JBTextField()
    private val benchmarkTime = JBTextField()
    private val benchmarkMemory = JBCheckBox("Report memory allocations", true)
    private val fuzzPattern = JBTextField()
    private val fuzzTime = JBTextField()
    private val fuzzMinimizeTime = JBTextField()
    private val fuzzParallelism = JBTextField()
    private val benchmarkFields = mutableListOf<JComponent>()
    private val fuzzFields = mutableListOf<JComponent>()
    private val patternLabel = JBLabel("Test pattern:")
    private val runAfterBuild = JBCheckBox("Run after build", true)
    private val environment = EnvironmentVariablesComponent()

    init {
        runAfterBuild.addActionListener { updateBuildFields() }
        testMode.addActionListener { updateTestFields() }
    }

    override fun resetEditorFrom(configuration: GoRunConfiguration) {
        with(configuration.parameters) {
            this@GoRunSettingsEditor.target.text = target
            this@GoRunSettingsEditor.workingDirectory.text = workingDirectory
            this@GoRunSettingsEditor.buildFlags.text = buildFlags
            this@GoRunSettingsEditor.arguments.text = arguments
            this@GoRunSettingsEditor.outputPath.text = outputPath
            this@GoRunSettingsEditor.testPattern.text = testPattern
            this@GoRunSettingsEditor.testMode.selectedItem = testMode
            this@GoRunSettingsEditor.benchmarkPattern.text = benchmarkPattern
            this@GoRunSettingsEditor.benchmarkTime.text = benchmarkTime
            this@GoRunSettingsEditor.benchmarkMemory.isSelected = benchmarkMemory
            this@GoRunSettingsEditor.fuzzPattern.text = fuzzPattern
            this@GoRunSettingsEditor.fuzzTime.text = fuzzTime
            this@GoRunSettingsEditor.fuzzMinimizeTime.text = fuzzMinimizeTime
            this@GoRunSettingsEditor.fuzzParallelism.text = fuzzParallelism
            this@GoRunSettingsEditor.runAfterBuild.isSelected = runAfterBuild
            this@GoRunSettingsEditor.environment.envs = environment
            this@GoRunSettingsEditor.environment.isPassParentEnvs = passParentEnvironment
        }
        updateBuildFields()
        updateTestFields()
    }

    override fun applyEditorTo(configuration: GoRunConfiguration) {
        val values = GoRunParameters(
            target = target.text.trim(), workingDirectory = workingDirectory.text.trim(),
            buildFlags = buildFlags.text, arguments = arguments.text,
            outputPath = outputPath.text.trim(), testPattern = testPattern.text,
            testMode = testMode.selectedItem as GoTestMode,
            benchmarkPattern = benchmarkPattern.text,
            benchmarkTime = benchmarkTime.text.trim(), benchmarkMemory = benchmarkMemory.isSelected,
            fuzzPattern = fuzzPattern.text, fuzzTime = fuzzTime.text.trim(),
            fuzzMinimizeTime = fuzzMinimizeTime.text.trim(), fuzzParallelism = fuzzParallelism.text.trim(),
            runAfterBuild = runAfterBuild.isSelected,
            environment = LinkedHashMap(environment.envs), passParentEnvironment = environment.isPassParentEnvs,
        )
        try {
            values.validate()
            if (kind == GoRunKind.TEST) values.commandArguments(kind, "")
        } catch (error: IllegalArgumentException) { throw ConfigurationException(error.message.orEmpty()) }
        configuration.parameters = values
    }

    override fun createEditor(): JComponent {
        val form = JPanel(GridBagLayout())
        var row = 0
        fun field(label: String, component: JComponent, group: MutableList<JComponent>? = null, actualLabel: JBLabel = JBLabel(label)) {
            val text = actualLabel.also { it.labelFor = component }
            form.add(text, GridBagConstraints().apply {
                gridx = 0; gridy = row; anchor = GridBagConstraints.LINE_END; insets = Insets(4, 0, 4, 10)
            })
            form.add(component, GridBagConstraints().apply {
                gridx = 1; gridy = row++; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL; insets = Insets(4, 0, 4, 0)
            })
            group?.addAll(listOf(text, component))
        }
        field("Package / files:", target)
        target.toolTipText = "A package such as . or ./cmd/server; quote paths containing spaces. Go Test also accepts ./..."
        field("Working directory:", workingDirectory)
        field("Go tool arguments:", buildFlags)
        buildFlags.toolTipText = "Build or test flags, for example -tags=integration or -race. Project build tags apply unless overridden."
        if (kind == GoRunKind.TEST) {
            field("Test framework:", testMode)
            field("Test pattern:", testPattern, actualLabel = patternLabel)
            testPattern.toolTipText = "Go -run expression. Empty runs all tests for gotest, or skips unrelated tests for benchmarks/fuzzing."
            field("Benchmark pattern:", benchmarkPattern, benchmarkFields)
            benchmarkPattern.toolTipText = "Go -bench expression; . runs every benchmark."
            field("Benchmark time / iterations:", benchmarkTime, benchmarkFields)
            benchmarkTime.toolTipText = "Go -benchtime, for example 1s or 100x."
            field("", benchmarkMemory, benchmarkFields)
            field("Fuzz pattern:", fuzzPattern, fuzzFields)
            fuzzPattern.toolTipText = "Match exactly one fuzz function in one package, for example ^FuzzDecode$."
            field("Fuzz time / iterations:", fuzzTime, fuzzFields)
            fuzzTime.toolTipText = "Go -fuzztime, for example 30s or 1000x; empty runs until Stop."
            field("Minimization time / iterations:", fuzzMinimizeTime, fuzzFields)
            fuzzMinimizeTime.toolTipText = "Go -fuzzminimizetime, for example 60s or 100x; 0 disables minimization."
            field("Fuzz workers:", fuzzParallelism, fuzzFields)
            fuzzParallelism.toolTipText = "A positive Go -parallel worker count; empty uses the Go default."
            field("", JBLabel("Debug runs the matching seed corpus without fuzzing workers."), fuzzFields)
            field("Test arguments:", arguments)
        } else {
            field("Program arguments:", arguments)
            field("Output directory / file:", outputPath)
            outputPath.toolTipText = "Used when Run after build is disabled; empty uses the Go tool default."
            field("", runAfterBuild)
        }
        field("Environment variables:", environment)
        updateBuildFields()
        updateTestFields()
        return JPanel(BorderLayout()).apply { add(form, BorderLayout.NORTH) }
    }

    private fun updateBuildFields() {
        if (kind == GoRunKind.BUILD) {
            arguments.isEnabled = runAfterBuild.isSelected
            outputPath.isEnabled = !runAfterBuild.isSelected
        }
    }
    private fun updateTestFields() {
        val selected = testMode.selectedItem as? GoTestMode ?: GoTestMode.TEST
        benchmarkFields.forEach { it.isVisible = selected == GoTestMode.BENCHMARK }
        fuzzFields.forEach { it.isVisible = selected == GoTestMode.FUZZ }
        patternLabel.text = if (selected == GoTestMode.TEST) "Test pattern:" else "Also run tests (pattern):"
        patternLabel.parent?.revalidate()
        patternLabel.parent?.repaint()
    }
}
