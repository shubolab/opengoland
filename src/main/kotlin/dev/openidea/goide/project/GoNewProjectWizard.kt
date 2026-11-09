package dev.openidea.goide.project

import com.intellij.CommonBundle
import com.intellij.facet.ui.ValidationResult
import com.intellij.ide.util.projectWizard.AbstractNewProjectDialog
import com.intellij.ide.util.projectWizard.AbstractNewProjectStep
import com.intellij.ide.util.projectWizard.ProjectSettingsStepBase
import com.intellij.ide.util.projectWizard.SettingsStep
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectBundle
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.DirectoryProjectGenerator
import com.intellij.platform.ProjectGeneratorPeer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import dev.openidea.goide.lang.GoFileType
import dev.openidea.goide.sdk.GoSdkSelector
import java.awt.BorderLayout
import java.nio.file.Path
import java.util.concurrent.CancellationException
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.event.DocumentEvent

internal class GoNewProjectDialog : AbstractNewProjectDialog() {
    // AbstractNewProjectDialog builds its panel inside its constructor. Read this
    // provider only when Create is pressed, after the caller has assigned it.
    var ownerProject: Project? = null
    private lateinit var projectStep: GoNewProjectStep
    init { title = ProjectBundle.message("dialog.title.new.project") }
    override fun createNewProjectStep(): AbstractNewProjectStep<*> =
        GoNewProjectStep({ ownerProject }, disposable) { close(DialogWrapper.CANCEL_EXIT_CODE) }.also { projectStep = it }

    fun initialize(owner: Project?, executable: String, environment: String) {
        ownerProject = owner
        // This runs after the base dialog's eager constructor has built the panel.
        projectStep.settingsStep().apply {
            sdkSelector.configuredExecutable = executable
            environmentField.text = environment
            checkValid()
        }
    }
}

internal class GoNewProjectStep(owner: () -> Project?, lifetime: Disposable, cancel: () -> Unit) :
    AbstractNewProjectStep<GoNewProjectSettings>(GoNewProjectCustomization(owner, lifetime, cancel)) {
    internal fun settingsStep(): GoNewProjectSettingsStep = childActionsOrStubs.filterIsInstance<GoNewProjectSettingsStep>().single()

    private class GoNewProjectCustomization(private val owner: () -> Project?, private val lifetime: Disposable, private val cancel: () -> Unit) :
        Customization<GoNewProjectSettings>() {
        override fun createEmptyProjectGenerator(): DirectoryProjectGenerator<GoNewProjectSettings> = GoNewProjectGenerator()
        override fun createCallback(): AbstractNewProjectStep.AbstractCallback<GoNewProjectSettings> = GoNewProjectCallback(owner)
        override fun createProjectSpecificSettingsStep(generator: DirectoryProjectGenerator<GoNewProjectSettings>,
            callback: AbstractNewProjectStep.AbstractCallback<GoNewProjectSettings>): ProjectSettingsStepBase<GoNewProjectSettings> =
            GoNewProjectSettingsStep(generator, callback, cancel).also { Disposer.register(lifetime, it) }
        override fun getActions(generators: List<DirectoryProjectGenerator<*>>,
            callback: AbstractNewProjectStep.AbstractCallback<GoNewProjectSettings>): Array<out AnAction> = emptyArray()
    }
}

private class GoNewProjectGenerator : DirectoryProjectGenerator<GoNewProjectSettings> {
    override fun getName() = "Go"
    override fun getLogo(): Icon = GoFileType.INSTANCE.icon
    override fun validate(baseDirPath: String): ValidationResult = ValidationResult.OK
    override fun generateProject(project: Project, baseDir: VirtualFile, settings: GoNewProjectSettings, module: Module) =
        error("Go modules must be verified on a background worker before a project is opened")
}

internal class GoNewProjectSettingsStep(generator: DirectoryProjectGenerator<GoNewProjectSettings>,
    callback: AbstractNewProjectStep.AbstractCallback<GoNewProjectSettings>, private val cancel: () -> Unit) :
    ProjectSettingsStepBase<GoNewProjectSettings>(generator, callback, "hello") {
    internal val nameField = JBTextField("hello")
    internal val locationField = TextFieldWithBrowseButton().apply {
        text = Path.of(System.getProperty("user.home"), "GoProjects").toString()
        addBrowseFolderListener(null, FileChooserDescriptorFactory.singleDir())
    }
    internal val moduleField = JBTextField("example.com/hello")
    internal val sampleField = JBCheckBox("Create a runnable example with a unit test", true)
    internal val environmentField = JBTextArea(3, 38).apply {
        toolTipText = "Enter environment variables as NAME=value, one per line."
    }
    internal val sdkSelector = GoSdkSelector(null, this)
    private val destinationLabel = JBLabel()
    private var panelCreated = false
    private var automaticModule = "example.com/hello"
    private val peer = object : ProjectGeneratorPeer<GoNewProjectSettings> {
        override fun getComponent(location: TextFieldWithBrowseButton, validate: Runnable): JComponent = sdkSelector.component
        override fun buildUI(step: SettingsStep) = Unit
        override fun getSettings() = snapshot()
        override fun validate(): ValidationInfo? = validationMessage()?.let { ValidationInfo(it) }
        override fun isBackgroundJobRunning() = false
    }

    init {
        Disposer.register(this, locationField)
        val listener = sdkSelector.addStateListener { if (panelCreated) checkValid() }
        Disposer.register(this) { listener.close() }
        fun changed() { if (panelCreated) checkValid() }
        nameField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(event: DocumentEvent) {
                if (moduleField.text == automaticModule) {
                    automaticModule = "example.com/${nameField.text.trim()}"
                    moduleField.text = automaticModule
                }
                changed()
            }
        })
        for (document in listOf(locationField.textField.document, moduleField.document, environmentField.document))
            document.addDocumentListener(object : DocumentAdapter() { override fun textChanged(event: DocumentEvent) = changed() })
    }

    override fun getPeer(): ProjectGeneratorPeer<GoNewProjectSettings> = peer
    internal fun snapshot() = GoNewProjectSettings(nameField.text.trim(), locationField.text.trim(), moduleField.text.trim(),
        sdkSelector.configuredExecutable, sampleField.isSelected, environmentField.text)

    private fun validationMessage(): String? = try {
        snapshot().validate()
        if (!sdkSelector.isReady) sdkSelector.validationMessage ?: "Checking the selected Go SDK…" else null
    } catch (error: IllegalArgumentException) { error.message ?: "Enter valid Go project settings." }

    override fun checkValid(): Boolean {
        if (!panelCreated) return false
        val error = validationMessage()
        setErrorText(error)
        destinationLabel.text = runCatching { "Project directory: ${snapshot().directory()}" }.getOrDefault(" ")
        return error == null
    }

    override fun getProjectLocation(): String = snapshot().directory().toString()

    override fun createPanel(): JPanel {
        myLocationField = locationField
        val error = createErrorLabel()
        val create = createActionButton()
        val cancelButton = JButton(CommonBundle.getCancelButtonText()).apply { addActionListener { cancel() } }
        create.addActionListener {
            if (checkValid()) myCallback.consume(this, peer)
        }
        Disposer.register(this) {
            com.intellij.util.ui.UIUtil.dispose(create)
            com.intellij.util.ui.UIUtil.dispose(cancelButton)
        }
        val fields = FormBuilder.createFormBuilder()
            .addLabeledComponent("Name:", nameField)
            .addLabeledComponent("Location:", locationField)
            .addComponent(destinationLabel)
            .addLabeledComponent("Module path:", moduleField)
            .addLabeledComponent("Go SDK:", sdkSelector.component)
            .addLabeledComponent("Environment:", JScrollPane(environmentField))
            .addComponent(sampleField).panel
        val bottom = JPanel(BorderLayout()).apply {
            add(error, BorderLayout.NORTH)
            add(cancelButton, BorderLayout.WEST)
            add(create, BorderLayout.EAST)
        }
        panelCreated = true
        checkValid()
        return JPanel(BorderLayout()).apply { add(fields, BorderLayout.NORTH); add(bottom, BorderLayout.SOUTH) }
    }
}

private class GoNewProjectCallback(private val owner: () -> Project?) : AbstractNewProjectStep.AbstractCallback<GoNewProjectSettings>() {
    override fun consume(settings: ProjectSettingsStepBase<GoNewProjectSettings>?, peer: ProjectGeneratorPeer<GoNewProjectSettings>) {
        if (settings == null) return
        // Capture before the dialog disposes the SDK selector and its verification task.
        val selected = peer.settings
        val project = owner()
        DialogWrapper.findInstance(settings.actionButton)?.close(DialogWrapper.OK_EXIT_CODE)
        GoNewProjectCreation.start(project, selected)
    }
}

internal object GoNewProjectCreation {
    fun start(owner: Project?, selected: GoNewProjectSettings) {
        selected.validate()
        val creator = GoNewProjectCreator()
        object : Task.Backgroundable(owner, "Creating Go module ${selected.modulePath}", true) {
            private var created: GoNewProjectCreator.Created? = null
            private var entry: VirtualFile? = null
            private var indicator: ProgressIndicator? = null
            private fun cancelled() = indicator?.isCanceled == true || owner?.isDisposed == true
            override fun run(indicator: ProgressIndicator) {
                this.indicator = indicator
                indicator.isIndeterminate = true
                try {
                    // Keep the completed result even if cancellation happens during VFS refresh.
                    val result = creator.generate(selected, ::cancelled) { indicator.text2 = it }
                    created = result
                    if (!cancelled())
                        entry = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(result.directory.resolve(if (selected.createExample) "main.go" else "go.mod"))
                } catch (_: CancellationException) { throw ProcessCanceledException() }
            }
            override fun onSuccess() {
                val result = created ?: return
                if (cancelled()) { reportCreated(result); return }
                val opened = creator.open(result, owner, ::cancelled)
                if (opened == null) reportCreated(result)
                else entry?.let { FileEditorManager.getInstance(opened).openFile(it, true) }
            }
            override fun onCancel() { created?.let(::reportCreated) }
            override fun onThrowable(error: Throwable) {
                val result = created
                if (result != null) reportCreated(result)
                else notify("Unable to create Go module", error.message ?: error.javaClass.simpleName, NotificationType.ERROR)
            }
            private fun reportCreated(result: GoNewProjectCreator.Created) = notify("Go module created",
                "The module is available at ${result.directory}. Open this directory to continue.", NotificationType.INFORMATION)
            private fun notify(title: String, message: String, type: NotificationType) {
                if (!ApplicationManager.getApplication().isDisposed)
                    NotificationGroupManager.getInstance().getNotificationGroup("open Goland Project Creation")
                        .createNotification(title, message, type).notify(owner?.takeUnless { it.isDisposed })
            }
        }.queue()
    }
}
