package dev.openidea.goide.profile

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import dev.openidea.goide.engine.EngineClient
import dev.openidea.goide.settings.GoToolchain
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

data class GoProfileLog(val sequence: Long, val text: String, val error: Boolean = false)
data class GoProfileState(
    val modules: List<GoProfileModule> = emptyList(), val selectedDirectory: String? = null,
    val refreshing: Boolean = false, val busy: Boolean = false, val taskId: String? = null,
    val report: GoProfileReport? = null, val profilePath: String? = null, val options: GoProfileOptions = GoProfileOptions(),
    val message: String = "Generate a Go profile or import a pprof file.", val log: List<GoProfileLog> = emptyList(),
)

@Service(Service.Level.PROJECT)
class GoProfileService(private val project: Project) : Disposable {
    private val engine = project.getService(EngineClient::class.java)
    private val listeners = CopyOnWriteArrayList<(GoProfileState) -> Unit>()
    private val subscription = engine.listen(::onEngineEvent)
    private var operation = 0L
    private var sequence = 0L
    private var cancellationRequested = false
    private var cumulativeSort = false
    @Volatile private var disposed = false
    @Volatile var state = GoProfileState()
        private set

    fun listen(listener: (GoProfileState) -> Unit): AutoCloseable {
        listeners += listener; onEdt { listener(state) }; return AutoCloseable { listeners -= listener }
    }
    fun refreshModules() = onEdt {
        if (state.refreshing || state.busy) return@onEdt
        state = state.copy(refreshing = true); publish()
        pooled { engine.request("project/info").whenComplete { result, error -> onEdt {
            if (error != null) { state = state.copy(refreshing = false, message = failure(error)); log(failure(error) + "\n", true) }
            else {
                val modules = result.asJsonObject.getAsJsonArray("modules")?.mapNotNull { entry ->
                    val module = entry.asJsonObject
                    val path = module.get("Path")?.takeUnless { it.isJsonNull }?.asString
                    val directory = module.get("Dir")?.takeUnless { it.isJsonNull }?.asString
                    if (path.isNullOrBlank() || directory.isNullOrBlank()) null else GoProfileModule(path, directory)
                }.orEmpty().distinctBy { it.directory }.sortedBy { it.path }
                state = state.copy(refreshing = false, modules = modules,
                    selectedDirectory = state.selectedDirectory?.takeIf { selected -> modules.any { it.directory == selected } } ?: modules.firstOrNull()?.directory,
                    message = if (modules.isEmpty()) "No Go module was found. Open a go.mod or go.work project." else state.message)
                publish()
            }
        } } }
    }
    fun selectModule(directory: String) = onEdt {
        if (!state.busy && state.modules.any { it.directory == directory }) { state = state.copy(selectedDirectory = directory); publish() }
    }
    fun runSelected(options: GoProfileOptions = state.options): Unit = onEdt {
        if (state.busy || state.refreshing) return@onEdt
        val module = state.modules.firstOrNull { it.directory == state.selectedDirectory } ?: run { refreshModules(); return@onEdt }
        val id = UUID.randomUUID().toString()
        try { options.request(id, module.directory, emptyMap()) } catch (error: Exception) { fail(failure(error)); return@onEdt }
        FileDocumentManager.getInstance().saveAllDocuments()
        val current = ++operation
        cancellationRequested = false
        state = state.copy(busy = true, taskId = id, options = options, report = null, profilePath = null, log = emptyList(), message = "Generating ${options.kind} profile…")
        log("Profiling ${options.target} in ${module.directory}\n")
        pooled {
            try {
                val request = options.request(id, module.directory, GoToolchain.command(project, "go", emptyList()).environment)
                engine.request("task/start", request).whenComplete { _, error -> onEdt {
                    if (operation != current || state.taskId != id) return@onEdt
                    if (error != null) fail(failure(error)) else if (cancellationRequested) cancel()
                } }
            } catch (error: Exception) { onEdt { if (operation == current && state.taskId == id) fail(failure(error)) } }
        }
    }
    fun cancel() = onEdt {
        val id = state.taskId ?: return@onEdt
        cancellationRequested = true; state = state.copy(message = "Cancelling profile collection…"); publish()
        pooled { engine.request("task/cancel", JsonObject().apply { addProperty("id", id) }).whenComplete { _, error ->
            if (error != null) onEdt { if (state.taskId == id) log("Unable to cancel: ${failure(error)}\n", true) }
        } }
    }
    fun importProfile(path: Path) = onEdt {
        if (state.busy) return@onEdt
        state = state.copy(busy = true, taskId = null, report = null, profilePath = path.toString(), log = emptyList(), message = "Reading pprof profile…")
        publish(); load(path.toString(), ++operation)
    }
    fun selectMetric(index: Int) = onEdt {
        val report = state.report ?: return@onEdt
        if (state.busy || report.sampleIndex == index || index !in report.sampleTypes.indices) return@onEdt
        state = state.copy(busy = true, message = "Reading ${report.sampleTypes[index].type} hotspots…"); publish()
        load(report.path, ++operation, index)
    }
    fun sortByCumulative(cumulative: Boolean) = onEdt {
        val report = state.report ?: return@onEdt
        if (state.busy) return@onEdt
        cumulativeSort = cumulative
        state = state.copy(busy = true); publish(); load(report.path, ++operation, report.sampleIndex, cumulative)
    }
    fun showMessage(message: String) = onEdt { state = state.copy(message = message); publish() }
    private fun load(path: String, current: Long, metric: Int? = null, cumulative: Boolean = cumulativeSort, exitCode: Int = 0) = pooled {
        val params = JsonObject().apply { addProperty("path", path); metric?.let { addProperty("sampleIndex", it) }; addProperty("sort", if (cumulative) "cumulative" else "flat"); addProperty("limit", 5000) }
        engine.request("profile/inspect", params).whenComplete { value, error -> onEdt {
            if (operation != current) return@onEdt
            if (error != null) fail(failure(error)) else try {
                val report = Gson().fromJson(value, GoProfileReport::class.java)
                val metricValue = report.metric
                val summary = "${report.sampleCount} samples · ${report.functionCount} functions · total ${metricValue?.let { GoProfileUnits.format(it.total, it.unit) }.orEmpty()}"
                state = state.copy(busy = false, taskId = null, report = report, profilePath = path,
                    message = summary + if (exitCode == 0) "" else " · go test exited $exitCode")
                report.warnings.forEach { log(it + "\n", true) }; publish()
            } catch (error: Exception) { fail("Unable to read profile results: ${failure(error)}") }
        } }
    }
    private fun onEngineEvent(method: String, params: JsonObject) = onEdt {
        val id = state.taskId ?: return@onEdt
        if (method == "engine/stopped" && params.getAsJsonArray("taskIds")?.any { it.asString == id } == true) { fail("Go engine stopped during profile collection."); return@onEdt }
        if (params.get("id")?.asString != id) return@onEdt
        when (method) {
            "task/step" -> log("${params.get("executable")?.asString} ${params.getAsJsonArray("args")?.joinToString(" ") { it.asString }}\n")
            "task/output" -> if (params.get("stream")?.asString == "stderr") log(params.get("text")?.asString.orEmpty(), true)
            "test/event" -> params.getAsJsonObject("event")?.get("Output")?.takeUnless { it.isJsonNull }?.asString?.let { log(it) }
            "task/exit" -> {
                val code = params.get("code")?.asInt ?: -1
                val path = params.getAsJsonObject("profile")?.get("path")?.asString
                if (cancellationRequested || params.get("cancelled")?.asBoolean == true) {
                    state = state.copy(busy = false, taskId = null, message = "Profile collection cancelled."); log("Profile collection cancelled.\n")
                } else if (path != null) { state = state.copy(taskId = null, profilePath = path, message = "Reading profile hotspots…"); publish(); load(path, operation, exitCode = code) }
                else fail("Profile collection exited $code without a profile. Check the tool output.")
            }
        }
    }
    private fun log(text: String, error: Boolean = false) {
        val bounded = text.takeLast(64 * 1024)
        var retained = (state.log + GoProfileLog(++sequence, bounded, error)).takeLast(1000)
        var size = retained.sumOf { it.text.length }
        while (size > 1024 * 1024 && retained.size > 1) { size -= retained.first().text.length; retained = retained.drop(1) }
        state = state.copy(log = retained); publish()
    }
    private fun fail(message: String) { state = state.copy(busy = false, taskId = null, message = message); log(message + "\n", true) }
    private fun publish() { listeners.forEach { listener -> runCatching { listener(state) } } }
    private fun onEdt(action: () -> Unit) {
        val application = ApplicationManager.getApplication()
        val guarded = Runnable { if (!disposed && !project.isDisposed) WriteIntentReadAction.run(action) }
        if (application.isDispatchThread) guarded.run() else application.invokeLater(guarded)
    }
    private fun pooled(action: () -> Unit) = ApplicationManager.getApplication().executeOnPooledThread { if (!disposed && !project.isDisposed) action() }
    override fun dispose() {
        disposed = true; operation++; subscription.close(); listeners.clear()
        state.taskId?.let { id -> ApplicationManager.getApplication().executeOnPooledThread { runCatching { engine.request("task/cancel", JsonObject().apply { addProperty("id", id) }) } } }
    }
    companion object {
        fun get(project: Project): GoProfileService = project.getService(GoProfileService::class.java)
        private fun failure(error: Throwable) = error.cause?.message ?: error.message ?: error.javaClass.simpleName
    }
}
