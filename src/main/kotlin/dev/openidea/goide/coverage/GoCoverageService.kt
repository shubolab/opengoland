package dev.openidea.goide.coverage

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.JBColor
import dev.openidea.goide.engine.EngineClient
import dev.openidea.goide.settings.GoProjectSettings
import dev.openidea.goide.settings.GoToolchain
import java.awt.Component
import java.awt.Graphics
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.Icon

data class GoCoverageLog(val sequence: Long, val text: String, val error: Boolean = false)
data class GoNativeCoverageResult(
    val profile: Path, val workingDirectory: String, val exitCode: Int,
    val modules: List<GoCoverageModule>, val sourceRevision: Long, val configurationName: String,
)
data class GoCoverageState(
    val modules: List<GoCoverageModule> = emptyList(), val selectedDirectory: String? = null,
    val refreshing: Boolean = false, val busy: Boolean = false, val taskId: String? = null,
    val report: GoCoverageReport? = null, val profilePath: String? = null,
    val showInEditor: Boolean = true, val staleFiles: Set<String> = emptySet(),
    val message: String = "Run tests with coverage or import a Go coverprofile.", val log: List<GoCoverageLog> = emptyList(),
)

@Service(Service.Level.PROJECT)
class GoCoverageService(private val project: Project) : Disposable {
    private val engine = project.getService(EngineClient::class.java)
    private val listeners = CopyOnWriteArrayList<(GoCoverageState) -> Unit>()
    private val highlighters = mutableMapOf<Editor, List<RangeHighlighter>>()
    private val changedDuringRun = mutableSetOf<String>()
    private var sourceRevision = 0L
    private val sourceChanges = mutableMapOf<String, Long>()
    private val pendingNative = java.util.ArrayDeque<GoNativeCoverageResult>()
    private var nativeDrainScheduled = false
    private var logSequence = 0L
    private var operation = 0L
    private var cancellationRequested = false
    @Volatile private var disposed = false
    @Volatile var state = GoCoverageState()
        private set
    private val subscription = engine.listen(::onEngineEvent)

    init {
        val factory = EditorFactory.getInstance()
        factory.addEditorFactoryListener(object : EditorFactoryListener {
            override fun editorCreated(event: EditorFactoryEvent) { onEdt { annotate(event.editor) } }
            override fun editorReleased(event: EditorFactoryEvent) { highlighters.remove(event.editor) }
        }, this)
        factory.eventMulticaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                val file = FileDocumentManager.getInstance().getFile(event.document) ?: return
                onEdt {
                    val path = pathKey(file.path)
                    sourceChanges[path] = ++sourceRevision
                    if (state.busy) changedDuringRun += path
                    if (state.report?.files?.any { it.localPath?.let(::pathKey) == path } == true) {
                        state = state.copy(staleFiles = state.staleFiles + path)
                        clearAnnotations(path)
                        publish()
                    }
                }
            }
        }, this)
        project.messageBus.connect(this).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun fileOpened(source: FileEditorManager, file: VirtualFile) { onEdt { annotateAll() } }
        })
    }

    fun listen(listener: (GoCoverageState) -> Unit): AutoCloseable {
        listeners += listener
        onEdt { listener(state) }
        return AutoCloseable { listeners -= listener }
    }

    /** Called on the EDT before the native run starts, so edits saved during execution also invalidate markers. */
    fun captureSourceRevision(): Long = sourceRevision

    fun acceptNativeProfile(result: GoNativeCoverageResult) = onEdt {
        pendingNative.addLast(result)
        drainNativeProfiles()
    }

    private fun drainNativeProfiles() {
        if (state.busy || pendingNative.isEmpty()) return
        val result = pendingNative.removeFirst()
        val modules = (result.modules + state.modules).distinctBy { it.path }
        val selected = modules.filter { Path.of(result.workingDirectory).normalize().startsWith(Path.of(it.directory).normalize()) }
            .maxByOrNull { it.directory.length }?.directory ?: state.selectedDirectory
        val current = ++operation
        changedDuringRun.clear()
        clearAnnotations()
        state = state.copy(modules = modules, selectedDirectory = selected, busy = true, taskId = null,
            report = null, profilePath = null, staleFiles = emptySet(),
            message = "Loading coverage from ${result.configurationName}…", log = emptyList())
        publish()
        loadProfile(result.profile, result.workingDirectory, current, result.exitCode, emptySet(), imported = false,
            modulesOverride = modules, revision = result.sourceRevision, showToolWindow = true)
    }

    fun refreshModules(): CompletableFuture<List<GoCoverageModule>> {
        val result = CompletableFuture<List<GoCoverageModule>>()
        onEdt { state = state.copy(refreshing = true); publish() }
        pooled {
            engine.request("project/info").whenComplete { value, error -> onEdt {
                if (error != null) {
                    val message = failureMessage(error)
                    state = state.copy(refreshing = false, message = message)
                    log(message + "\n", true)
                    result.completeExceptionally(error)
                } else {
                    val modules = value.asJsonObject.getAsJsonArray("modules")?.mapNotNull { entry ->
                        val module = entry.asJsonObject
                        val path = module.get("Path")?.takeUnless { it.isJsonNull }?.asString
                        val directory = module.get("Dir")?.takeUnless { it.isJsonNull }?.asString
                        if (path.isNullOrBlank() || directory.isNullOrBlank()) null else GoCoverageModule(path, directory)
                    }.orEmpty().distinctBy { it.directory }.sortedBy { it.path }
                    val selected = state.selectedDirectory?.takeIf { choice -> modules.any { it.directory == choice } } ?: modules.firstOrNull()?.directory
                    state = state.copy(modules = modules, selectedDirectory = selected, refreshing = false,
                        message = if (modules.isEmpty()) "No Go module was found. Open a directory with go.mod or go.work." else state.message)
                    publish()
                    result.complete(modules)
                }
            } }
        }
        return result
    }

    fun selectModule(directory: String) = onEdt {
        if (!state.busy && state.modules.any { it.directory == directory }) { state = state.copy(selectedDirectory = directory); publish() }
    }

    fun runSelected(): Unit = onEdt {
        if (state.busy) return@onEdt
        if (state.modules.isEmpty()) {
            refreshModules().whenComplete { modules, error -> if (error == null && modules.isNotEmpty()) onEdt { runSelected() } }
            return@onEdt
        }
        val module = state.modules.firstOrNull { it.directory == state.selectedDirectory } ?: return@onEdt
        FileDocumentManager.getInstance().saveAllDocuments()
        val id = UUID.randomUUID().toString()
        val current = ++operation
        cancellationRequested = false
        changedDuringRun.clear()
        clearAnnotations()
        state = state.copy(busy = true, taskId = id, report = null, profilePath = null, staleFiles = emptySet(),
            message = "Running coverage for ${module.path}…", log = emptyList())
        log("Running go test with coverage in ${module.directory}\n")
        pooled {
            try {
                val settings = GoProjectSettings.get(project)
                val args = JsonArray()
                if (settings.buildTags.isNotBlank()) {
                    args.add("-tags=" + settings.buildTags.split(Regex("[,\\s]+")).filter(String::isNotBlank).joinToString(","))
                    args.add("./...")
                }
                val params = JsonObject().apply {
                    addProperty("id", id); addProperty("command", "coverage"); addProperty("cwd", module.directory)
                    add("args", args)
                    add("env", JsonObject().also { env -> GoToolchain.command(project, "go", emptyList()).environment.forEach { (key, value) -> env.addProperty(key, value) } })
                }
                engine.request("task/start", params).whenComplete { _, error -> onEdt {
                    if (operation != current || state.taskId != id) return@onEdt
                    if (error != null) finishError(failureMessage(error))
                    else if (cancellationRequested) cancel()
                } }
            } catch (error: Exception) { onEdt { if (operation == current && state.taskId == id) finishError(failureMessage(error)) } }
        }
    }

    fun cancel() = onEdt {
        val id = state.taskId ?: return@onEdt
        cancellationRequested = true
        state = state.copy(message = "Cancelling coverage…")
        publish()
        pooled {
            engine.request("task/cancel", JsonObject().apply { addProperty("id", id) }).whenComplete { _, error ->
                if (error != null) onEdt { if (state.taskId == id) log("Unable to cancel coverage: ${failureMessage(error)}\n", true) }
            }
        }
    }

    fun importProfile(path: Path): Unit = onEdt {
        if (state.busy) return@onEdt
        if (state.modules.isEmpty()) {
            refreshModules().whenComplete { modules, error -> if (error == null && modules.isNotEmpty()) onEdt { importProfile(path) } }
            return@onEdt
        }
        val current = ++operation
        changedDuringRun.clear()
        clearAnnotations()
        state = state.copy(busy = true, taskId = null, message = "Importing coverage…", staleFiles = emptySet(), log = emptyList())
        publish()
        loadProfile(path, state.selectedDirectory, current, 0, emptySet(), imported = true)
    }

    fun showInEditor(show: Boolean) = onEdt {
        state = state.copy(showInEditor = show)
        if (show) annotateAll() else clearAnnotations()
        publish()
    }

    private fun onEngineEvent(method: String, params: JsonObject) = onEdt {
        val id = state.taskId ?: return@onEdt
        if (method == "engine/stopped" && params.getAsJsonArray("taskIds")?.any { it.asString == id } == true) {
            finishError(params.get("message")?.asString ?: "Go engine stopped during coverage.")
            return@onEdt
        }
        if (params.get("id")?.asString != id) return@onEdt
        when (method) {
            "task/output" -> if (params.get("stream")?.asString == "stderr") log(params.get("text")?.asString.orEmpty(), true)
            "test/event" -> params.getAsJsonObject("event")?.get("Output")?.takeUnless { it.isJsonNull }?.asString?.let { log(it) }
            "task/exit" -> {
                val code = params.get("code")?.asInt ?: -1
                val profile = params.get("coverageProfile")?.takeUnless { it.isJsonNull }?.asString
                if (cancellationRequested) {
                    state = state.copy(taskId = null, busy = false, message = "Coverage cancelled.")
                    log("Coverage cancelled.\n")
                } else if (profile != null) {
                    state = state.copy(taskId = null, message = "Reading coverage results…")
                    publish()
                    loadProfile(Path.of(profile), state.selectedDirectory, operation, code, changedDuringRun.toSet(), imported = false)
                } else finishError("Coverage exited with code $code and did not produce a profile.")
            }
        }
    }

    private fun loadProfile(
        path: Path, relativeRoot: String?, current: Long, exitCode: Int, changedFiles: Set<String>, imported: Boolean,
        modulesOverride: List<GoCoverageModule>? = null, revision: Long? = null, showToolWindow: Boolean = false,
    ) {
        val modules = modulesOverride ?: state.modules
        pooled {
            try {
                require(Files.size(path) <= 64L * 1024 * 1024) { "Coverage profile is larger than 64 MiB." }
                val report = GoCoverageProfile.parse(Files.readString(path, StandardCharsets.UTF_8), modules, relativeRoot)
                onEdt {
                    if (operation != current) return@onEdt
                    val unsaved = FileDocumentManager.getInstance().unsavedDocuments.mapNotNull { document ->
                        FileDocumentManager.getInstance().getFile(document)?.path?.let(::pathKey)
                    }.toSet()
                    val changed = revision?.let { baseline -> sourceChanges.filterValues { it > baseline }.keys }.orEmpty()
                    state = state.copy(report = report, profilePath = path.toString(), busy = false, staleFiles = changedFiles + changed + changedDuringRun + unsaved,
                        message = if (exitCode == 0) "${if (imported) "Imported" else "Collected"} coverage: ${formatCoverage(report.totals.percent)} of statements."
                        else "Partial coverage loaded; tests exited with code $exitCode.")
                    annotateAll()
                    if (showToolWindow) ToolWindowManager.getInstance(project).getToolWindow("Go Coverage")?.show()
                    log("${state.message}\nProfile: $path\n")
                    if (report.unmappedFiles.isNotEmpty()) log("${report.unmappedFiles.size} profile file(s) could not be mapped to this workspace; their editor markers are not shown.\n", true)
                }
            } catch (error: Exception) { onEdt { if (operation == current) finishError("Unable to load coverage: ${failureMessage(error)}") } }
        }
    }

    private fun finishError(message: String) {
        state = state.copy(busy = false, taskId = null, message = message)
        log(message + "\n", true)
    }

    private fun log(text: String, error: Boolean = false) {
        val entries = (state.log + GoCoverageLog(++logSequence, text.takeLast(128 * 1024), error)).takeLast(1000).toMutableList()
        var size = entries.sumOf { it.text.length }
        while (size > 2 * 1024 * 1024 && entries.size > 1) size -= entries.removeAt(0).text.length
        state = state.copy(log = entries)
        publish()
    }

    private fun publish() {
        if (disposed) return
        listeners.forEach { it(state) }
        if (!state.busy && pendingNative.isNotEmpty() && !nativeDrainScheduled) {
            nativeDrainScheduled = true
            ApplicationManager.getApplication().invokeLater { onEdt { nativeDrainScheduled = false; drainNativeProfiles() } }
        }
    }
    private fun annotateAll() { EditorFactory.getInstance().allEditors.filter { it.project == project }.forEach(::annotate) }

    private fun annotate(editor: Editor) {
        highlighters.remove(editor)?.forEach { it.dispose() }
        if (editor.isDisposed || editor.project != project || !state.showInEditor) return
        val source = FileDocumentManager.getInstance().getFile(editor.document) ?: return
        val path = pathKey(source.path)
        if (path in state.staleFiles) return
        val data = state.report?.files?.firstOrNull { it.localPath?.let(::pathKey) == path } ?: return
        highlighters[editor] = data.lines(editor.document.lineCount).filterKeys { it in 1..editor.document.lineCount }.map { (line, coverage) ->
            editor.markupModel.addLineHighlighter(line - 1, HighlighterLayer.ADDITIONAL_SYNTAX, null).apply {
                gutterIconRenderer = CoverageGutter(coverage)
                setErrorStripeMarkColor(coverageColor(coverage))
                errorStripeTooltip = "Go coverage: ${coverage.name.lowercase()}"
                isThinErrorStripeMark = true
            }
        }
    }

    private fun clearAnnotations(path: String? = null) {
        val editors = highlighters.keys.filter { editor -> path == null || FileDocumentManager.getInstance().getFile(editor.document)?.path?.let(::pathKey) == path }
        editors.forEach { editor -> highlighters.remove(editor)?.forEach { runCatching { it.dispose() } } }
    }

    private fun onEdt(action: () -> Unit) {
        if (disposed || project.isDisposed) return
        val application = ApplicationManager.getApplication()
        val guarded = Runnable {
            if (!disposed && !project.isDisposed) WriteIntentReadAction.run(Runnable(action))
        }
        if (application.isDispatchThread) guarded.run() else application.invokeLater(guarded)
    }
    private fun pooled(action: () -> Unit) = ApplicationManager.getApplication().executeOnPooledThread { if (!disposed && !project.isDisposed) action() }

    override fun dispose() {
        disposed = true
        val id = state.taskId
        subscription.close()
        listeners.clear()
        pendingNative.clear()
        if (id != null) ApplicationManager.getApplication().executeOnPooledThread {
            runCatching { engine.request("task/cancel", JsonObject().apply { addProperty("id", id) }) }
        }
        val clear = Runnable { WriteIntentReadAction.run { clearAnnotations() } }
        if (ApplicationManager.getApplication().isDispatchThread) clear.run()
        else ApplicationManager.getApplication().invokeLater(clear)
    }

    companion object {
        fun get(project: Project): GoCoverageService = project.getService(GoCoverageService::class.java)
        private fun pathKey(path: String) = Path.of(path).normalize().toString().replace('\\', '/')
        private fun failureMessage(error: Throwable) = error.cause?.message ?: error.message ?: error.javaClass.simpleName
        fun formatCoverage(percent: Double) = String.format(java.util.Locale.ROOT, "%.1f%%", percent)
    }
}

private fun coverageColor(coverage: GoLineCoverage): JBColor = when (coverage) {
    GoLineCoverage.COVERED -> JBColor.GREEN
    GoLineCoverage.UNCOVERED -> JBColor.RED
    GoLineCoverage.PARTIAL -> JBColor.ORANGE
}

private class CoverageGutter(private val coverage: GoLineCoverage) : GutterIconRenderer(), DumbAware {
    override fun getIcon(): Icon = object : Icon {
        override fun getIconWidth() = 6
        override fun getIconHeight() = 12
        override fun paintIcon(component: Component?, graphics: Graphics, x: Int, y: Int) {
            graphics.color = coverageColor(coverage)
            graphics.fillRoundRect(x + 1, y, 4, 12, 2, 2)
        }
    }
    override fun getTooltipText() = "Go coverage: ${coverage.name.lowercase()}"
    override fun getAccessibleName() = tooltipText
    override fun equals(other: Any?) = other is CoverageGutter && other.coverage == coverage
    override fun hashCode() = coverage.hashCode()
}
