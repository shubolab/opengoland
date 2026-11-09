package dev.openidea.goide.engine

import com.google.gson.JsonObject
import com.intellij.execution.RunContentExecutor
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.messages.Topic
import dev.openidea.goide.semantic.GoSemanticOperations
import dev.openidea.goide.settings.GoToolchain
import java.awt.BorderLayout
import java.awt.Dimension
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.ListSelectionModel

internal fun interface GoTaskRefreshListener { fun finished() }

internal data class GoTaskTarget(
    val id: String,
    val kind: String,
    val label: String,
    val description: String,
    val cwd: String,
    val moduleRoots: List<String>,
)

internal data class GoTaskTargetInspection(
    val id: String,
    val command: String,
    val workspace: String?,
    val targets: List<GoTaskTarget>,
    val defaultTargetId: String?,
    val warnings: List<String>,
) {
    fun selection(target: GoTaskTarget): JsonObject {
        require(targets.contains(target)) { "The Go task target is not from this inspection" }
        return JsonObject().apply { addProperty("inspectionId", id); addProperty("targetId", target.id) }
    }
}

internal object GoTaskTargetsCodec {
    fun decode(value: JsonObject, expectedCommand: String): GoTaskTargetInspection {
        fun JsonObject.string(key: String) = get(key)?.takeUnless { it.isJsonNull }?.asString.orEmpty()
        fun strings(owner: JsonObject, key: String): List<String> = owner.getAsJsonArray(key)?.map { it.asString }.orEmpty()
        val id = value.string("inspectionId")
        require(id.isNotBlank()) { "The Go engine returned no task-target inspection" }
        val command = value.string("command")
        require(command == expectedCommand) { "The Go task changed during target discovery. Run the action again." }
        val targets = value.getAsJsonArray("targets")?.map { element ->
            val target = element.asJsonObject
            GoTaskTarget(target.string("id"), target.string("kind"), target.string("label"),
                target.string("description"), target.string("cwd"), strings(target, "moduleRoots")).also {
                require(it.id.isNotBlank() && it.label.isNotBlank()) { "The Go engine returned an unnamed task target" }
                require(it.kind in setOf("module", "workspace", "workspaceModules")) { "Unsupported Go task target: ${it.kind}" }
                require(it.cwd.isNotBlank() && Path.of(it.cwd).isAbsolute) { "The Go engine returned a relative task directory" }
            }
        }.orEmpty()
        require(targets.map { it.id }.distinct().size == targets.size) { "The Go engine returned duplicate task targets" }
        val default = value.string("defaultTargetId").takeIf(String::isNotEmpty)
        require(default == null || targets.any { it.id == default }) { "The default Go task target is unavailable" }
        return GoTaskTargetInspection(id, command, value.string("workspace").takeIf(String::isNotEmpty), targets,
            default, strings(value, "warnings"))
    }
}

/** Ambiguous workspace roots require a deliberate native selection, not an arbitrary module. */
internal class GoTaskTargetDialog(project: Project, private val inspection: GoTaskTargetInspection, taskTitle: String) : DialogWrapper(project) {
    private val choices = JBList(inspection.targets).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        visibleRowCount = 7
        cellRenderer = object : ColoredListCellRenderer<GoTaskTarget>() {
            override fun customizeCellRenderer(list: JList<out GoTaskTarget>, value: GoTaskTarget?, index: Int, selected: Boolean, hasFocus: Boolean) {
                value ?: return
                append(value.label, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                append("  ${value.cwd}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                toolTipText = value.description
            }
        }
    }
    private val detail = JTextArea(3, 70).apply { isEditable = false; isOpaque = false; lineWrap = true; wrapStyleWord = true }
    private val taskTitle = taskTitle

    init {
        title = "$taskTitle Target"
        setOKButtonText("Run")
        init()
        choices.addListSelectionListener {
            detail.text = choices.selectedValue?.description.orEmpty()
            isOKActionEnabled = choices.selectedValue != null
        }
        isOKActionEnabled = false
    }
    override fun getPreferredFocusedComponent(): JComponent = choices
    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, 10)).apply {
        preferredSize = Dimension(820, 320)
        add(JBLabel("Choose where to run $taskTitle."), BorderLayout.NORTH)
        add(JBScrollPane(choices), BorderLayout.CENTER)
        add(JPanel(BorderLayout(0, 8)).apply {
            add(detail, BorderLayout.NORTH)
            if (inspection.warnings.isNotEmpty()) add(JTextArea(inspection.warnings.joinToString("\n")).apply {
                isEditable = false; isOpaque = false; lineWrap = true; wrapStyleWord = true
            }, BorderLayout.CENTER)
        }, BorderLayout.SOUTH)
    }
    override fun doValidate(): ValidationInfo? = if (choices.selectedValue == null) ValidationInfo("Select a module or workspace target.", choices) else null
    fun selection(): GoTaskTarget = requireNotNull(choices.selectedValue) { "Select a Go task target" }
}

@Service(Service.Level.PROJECT)
class GoTaskTargetsService(private val project: Project) : Disposable {
    private val active = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var disposed = false

    internal fun invoke(command: String, title: String, contextFile: VirtualFile?) {
        invokeAtPath(command, title, contextFile?.path, contextFile)
    }

    internal fun invokeAtPath(command: String, title: String, path: String?, contextFile: VirtualFile? = null) {
        val root = project.basePath ?: return
        val contextPath = path ?: root
        val contextDirectory = if (contextFile?.isDirectory == false) contextFile.parent?.path else contextPath
        val documentGuard = try { GoTaskDocumentGuard.capture(project, listOfNotNull(root, contextDirectory)) }
        catch (failure: RuntimeException) { Messages.showErrorDialog(project, failure.message ?: "Save the Go files and try again", title); return }
        val settings = GoToolchain.capture(project)
        val engine = project.getService(EngineClient::class.java)
        val work = GoSemanticOperations.get(project)
        work.background("Prepare $title") { indicator ->
            val operationId = UUID.randomUUID().toString()
            active += operationId
            var completed = false
            try {
                checkCurrent(root, settings, contextFile, contextPath)
                val environment = GoToolchain.command(project, "go", emptyList(), settings).environment.toMap()
                val request = JsonObject().apply {
                    addProperty("operationId", operationId); addProperty("command", command); addProperty("contextPath", contextPath)
                    add("env", JsonObject().also { target -> environment.forEach { (key, value) -> target.addProperty(key, value) } })
                }
                val response = work.await(engine.request("project/taskTargets", request, settings, timeoutSeconds = 90), indicator)
                val inspection = GoTaskTargetsCodec.decode(response.asJsonObject, command)
                require(inspection.targets.isNotEmpty()) {
                    inspection.warnings.joinToString("\n").ifBlank { "No Go module is available here. Open a module or configure the active go.work file." }
                }
                indicator.checkCanceled()
                completed = true
                work.later(indicator) {
                    try {
                        checkCurrent(root, settings, contextFile, contextPath)
                        val scopedDocuments = documentGuard.including(inspection)
                        val default = inspection.targets.singleOrNull { it.id == inspection.defaultTargetId }
                        val selected = default ?: GoTaskTargetDialog(project, inspection, title).let { dialog ->
                            if (dialog.showAndGet()) dialog.selection() else null
                        } ?: return@later
                        checkCurrent(root, settings, contextFile, contextPath)
                        scopedDocuments.validate()
                        val handler = GoTaskProcessHandler(project, command, selected.cwd, settings, environment,
                            inspection.selection(selected), selected.moduleRoots, scopedDocuments, inspection.warnings)
                        ProcessTerminatedListener.attach(handler)
                        RunContentExecutor(project, handler).withTitle("$title — ${selected.label}").withActivateToolWindow(true)
                            .withStop({ handler.destroyProcess() }, { !handler.isProcessTerminating && !handler.isProcessTerminated }).run()
                    } catch (cancelled: ProcessCanceledException) { throw cancelled }
                    catch (error: RuntimeException) { Messages.showErrorDialog(project, error.message ?: "The Go task could not start", title) }
                }
            } finally {
                active -= operationId
                if (!completed) engine.cancelOperation(operationId)
            }
        }
    }

    private fun checkCurrent(root: String, settings: GoToolchain.Settings, file: VirtualFile?, path: String) {
        check(!disposed && !project.isDisposed && project.basePath == root) { "The Go project was closed or changed" }
        check(GoToolchain.configurationKey(project) == settings.key) { "The Go SDK or build settings changed. Run the action again." }
        check(file == null || file.isValid && file.path == path) { "The selected file moved or closed. Run the action again." }
    }
    internal fun refreshAfterTask(roots: List<String>) {
        if (disposed || project.isDisposed) return
        ApplicationManager.getApplication().executeOnPooledThread {
            if (disposed || project.isDisposed) return@executeOnPooledThread
            roots.distinct().forEach { path ->
                LocalFileSystem.getInstance().refreshAndFindFileByPath(path)?.refresh(false, true)
            }
            ApplicationManager.getApplication().invokeLater({
                if (!disposed && !project.isDisposed) project.messageBus.syncPublisher(REFRESH_TOPIC).finished()
            }, project.disposed)
        }
    }
    override fun dispose() {
        disposed = true
        val engine = project.getService(EngineClient::class.java)
        active.toList().forEach(engine::cancelOperation)
        active.clear()
    }
    companion object {
        internal val REFRESH_TOPIC = Topic.create("Go module task completed", GoTaskRefreshListener::class.java)
    }
}
