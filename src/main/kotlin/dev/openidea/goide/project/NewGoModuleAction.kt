package dev.openidea.goide.project

import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import dev.openidea.goide.settings.GoProjectSettings
import dev.openidea.goide.settings.GoToolchain
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.nio.file.Path
import java.util.concurrent.CancellationException
import javax.swing.JComponent
import javax.swing.JPanel

class NewGoModuleAction : DumbAwareAction() {
    override fun actionPerformed(event: AnActionEvent) {
        val owner = event.project
        val settingsProject = owner ?: ProjectManager.getInstance().defaultProject
        val dialog = NewGoModuleDialog(owner, GoProjectSettings.get(settingsProject).goExecutable.ifBlank { "go" })
        if (!dialog.showAndGet()) return
        val initial = dialog.request()
        object : Task.Backgroundable(owner, "Creating Go module ${initial.modulePath}", true) {
            private lateinit var created: Path
            private lateinit var executable: String
            private var mainFile: VirtualFile? = null

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                executable = GoToolchain.resolve("go", initial.goExecutable.takeUnless { it == "go" }.orEmpty())
                    ?: throw IllegalArgumentException("Go executable was not found. Choose the Go toolchain's bin/go executable.")
                try {
                    created = GoModuleGenerator.generate(initial.copy(goExecutable = executable), { indicator.isCanceled }) { indicator.text2 = it }
                    mainFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(created.resolve("main.go"))
                } catch (_: CancellationException) { throw ProcessCanceledException() }
            }

            override fun onSuccess() {
                val project = ProjectUtil.openOrImport(created, null, true)
                if (project == null) {
                    Messages.showInfoMessage(owner, "Go module was created at $created. Open this directory to continue.", "Go Module Created")
                    return
                }
                GoProjectSettings.get(project).goExecutable = executable
                mainFile?.let { FileEditorManager.getInstance(project).openFile(it, true) }
            }

            override fun onThrowable(error: Throwable) {
                Messages.showErrorDialog(owner, error.message ?: error.javaClass.simpleName, "Unable to Create Go Module")
            }
        }.queue()
    }
}

class NewGoModuleDialog(project: Project?, goExecutable: String) : DialogWrapper(project) {
    private val modulePath = JBTextField("example.com/hello")
    private val projectLocation = TextFieldWithBrowseButton().apply {
        text = Path.of(System.getProperty("user.home"), "GoProjects", "hello").toString()
        addBrowseFolderListener(project, FileChooserDescriptorFactory.singleDir())
    }
    private val executable = TextFieldWithBrowseButton().apply {
        text = goExecutable
        addBrowseFolderListener(project, FileChooserDescriptorFactory.singleFile())
    }
    private val example = JBCheckBox("Create a runnable example with a unit test", true)

    init { title = "New Go Module"; setOKButtonText("Create"); init() }
    override fun getPreferredFocusedComponent(): JComponent = modulePath
    fun request() = GoModuleRequest(modulePath.text.trim(), Path.of(projectLocation.text.trim()), executable.text.trim(), example.isSelected)
    override fun doValidate(): ValidationInfo? = try {
        request().validate(); null
    } catch (error: IllegalArgumentException) { ValidationInfo(error.message ?: "Invalid Go module settings.", modulePath) }

    override fun createCenterPanel(): JComponent = JPanel(GridBagLayout()).apply {
        var row = 0
        fun field(label: String, component: JComponent) {
            add(JBLabel(label).also { it.labelFor = component }, GridBagConstraints().apply {
                gridx = 0; gridy = row; anchor = GridBagConstraints.LINE_END; insets = Insets(6, 0, 6, 12)
            })
            add(component, GridBagConstraints().apply {
                gridx = 1; gridy = row++; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL; insets = Insets(6, 0, 6, 0)
            })
        }
        field("Module path:", modulePath)
        field("New project directory:", projectLocation)
        field("Go executable:", executable)
        field("", example)
    }
}
