package dev.openidea.goide.lint

import com.intellij.execution.process.KillableProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import dev.openidea.goide.engine.EngineClient
import dev.openidea.goide.settings.GoLintSettings
import dev.openidea.goide.settings.GoProjectSettings
import dev.openidea.goide.settings.GoToolchain
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class GoInspectionLog(val sequence: Long, val text: String, val error: Boolean)
data class GoInspectionState(
    val modules: List<GoInspectionModule> = emptyList(), val selectedDirectory: String? = null,
    val tool: GoInspectionTool = GoInspectionTool.VET, val refreshing: Boolean = false, val busy: Boolean = false,
    val issues: List<GoInspectionIssue> = emptyList(), val log: List<GoInspectionLog> = emptyList(),
    val message: String = "Run Go Vet or golangci-lint for a module or the entire Go workspace.",
)

@Service(Service.Level.PROJECT)
class GoLintService(private val project: Project) : Disposable {
    private val listeners = CopyOnWriteArrayList<(GoInspectionState) -> Unit>()
    private val cancelled = AtomicBoolean()
    @Volatile private var disposed = false
    @Volatile private var active: KillableProcessHandler? = null
    private var operation = 0L
    private var sequence = 0L
    private var pendingRun: GoInspectionTool? = null
    @Volatile var state = GoInspectionState()
        private set

    fun listen(listener: (GoInspectionState) -> Unit): AutoCloseable {
        listeners += listener
        onEdt { listener(state) }
        return AutoCloseable { listeners -= listener }
    }

    fun refreshModules(after: (() -> Unit)? = null) = onEdt {
        if (state.busy || state.refreshing) return@onEdt
        state = state.copy(refreshing = true); publish()
        pooled {
            project.getService(EngineClient::class.java).request("project/info").whenComplete { result, error ->
                // JSON and filesystem/subprocess work never run on the EDT.
                val modules = if (error != null) emptyList() else runCatching {
                    result.asJsonObject.getAsJsonArray("modules")?.mapNotNull { entry ->
                        val item = entry.asJsonObject
                        val path = item.get("Path")?.takeUnless { it.isJsonNull }?.asString
                        val directory = item.get("Dir")?.takeUnless { it.isJsonNull }?.asString
                        if (path.isNullOrBlank() || directory.isNullOrBlank()) null else GoInspectionModule(path, directory)
                    }.orEmpty().distinctBy { it.directory }.sortedBy { it.path }
                }.getOrDefault(emptyList())
                onEdt {
                    state = state.copy(modules = modules, refreshing = false,
                        selectedDirectory = state.selectedDirectory?.takeIf { selected -> modules.any { it.directory == selected } },
                        message = when { error != null -> failure(error); modules.isEmpty() -> "No Go modules were found. Open go.mod or go.work as a project."; else -> state.message })
                    publish()
                    val requested = pendingRun
                    pendingRun = null
                    if (error == null && modules.isNotEmpty()) {
                        after?.invoke()
                        if (requested != null) run(requested)
                    }
                }
            }
        }
    }

    fun selectTool(tool: GoInspectionTool) = onEdt { if (!state.busy) { state = state.copy(tool = tool); publish() } }
    fun selectModule(directory: String?) = onEdt {
        if (!state.busy && (directory == null || state.modules.any { it.directory == directory })) { state = state.copy(selectedDirectory = directory); publish() }
    }

    fun run(tool: GoInspectionTool? = null): Unit = onEdt {
        if (state.busy) return@onEdt
        if (tool != null) state = state.copy(tool = tool)
        // Creating the tool window starts module discovery before an activating action runs.
        if (state.refreshing) { pendingRun = state.tool; return@onEdt }
        if (state.modules.isEmpty()) { refreshModules { run(tool) }; return@onEdt }
        FileDocumentManager.getInstance().saveAllDocuments()
        val modules = state.modules.filter { state.selectedDirectory == null || it.directory == state.selectedDirectory }
        val selectedTool = state.tool
        val current = ++operation
        val settings = GoLintSettings.get(project)
        val executable = settings.golangciExecutable
        val flags = if (selectedTool == GoInspectionTool.VET) settings.vetFlags else settings.golangciFlags
        cancelled.set(false)
        state = state.copy(busy = true, issues = emptyList(), log = emptyList(), message = "Preparing ${selectedTool.title}…")
        publish()
        pooled { runModules(current, selectedTool, modules, executable, flags) }
    }

    fun cancel() = onEdt {
        if (!state.busy) return@onEdt
        cancelled.set(true)
        state = state.copy(message = "Cancelling inspections…"); publish()
        pooled { active?.let { if (!it.isProcessTerminated) it.destroyProcess() } }
    }

    private fun runModules(current: Long, tool: GoInspectionTool, modules: List<GoInspectionModule>, configured: String, flags: String) {
        var failures = 0
        try {
            val executable = if (tool == GoInspectionTool.VET) null else GoToolchain.resolve("golangci-lint", configured)
                ?: error("golangci-lint was not found. Set its executable in Settings > Go > Go Inspections.")
            val major = if (executable == null) null else {
                val output = execute(current, modules.first().directory, executable, listOf("version"), timeoutSeconds = 10)
                require(output.code == 0) { "Unable to query golangci-lint version: ${output.stderr.ifBlank { output.stdout }}" }
                GoLintCommands.versionMajor(output.stdout + output.stderr) ?: error("Unable to identify golangci-lint version.")
            }
            require(major == null || major in 1..2) { "Unsupported golangci-lint major version $major. Use version 1 or 2." }
            for (module in modules) {
                if (cancelled.get() || disposed) break
                onEdt { if (current == operation) { state = state.copy(message = "Running ${tool.title}: ${module.path}"); publish() } }
                val report = if (tool == GoInspectionTool.GOLANGCI) Files.createTempFile("openidea-go-inspections-", ".json") else null
                try {
                    val args = if (tool == GoInspectionTool.VET) GoLintCommands.vet(flags) else
                        GoLintCommands.golangci(major!!, flags, report!!, GoToolchain.buildTags(GoProjectSettings.get(project).buildTags))
                    val output = execute(current, module.directory, executable, args)
                    if (cancelled.get() || disposed) break
                    require(!output.truncated) { "Inspection output exceeded 32 MiB; results are incomplete. Narrow the module scope or linter selection." }
                    val issues = if (tool == GoInspectionTool.VET) GoLintParser.vet(output.stdout + "\n" + output.stderr, module.directory) else {
                        require(Files.size(report!!) in 1..(32L * 1024 * 1024)) { "golangci-lint did not produce a valid JSON report (exit ${output.code}). See the console for configuration or compatibility errors." }
                        GoLintParser.golangci(Files.readString(report), module.directory, relativeRoots(module.directory, flags))
                    }
                    // Findings and command failures are different states; vet -json can exit 0 with findings.
                    if (output.code != 0 && issues.isEmpty()) failures++
                    onEdt { if (current == operation) { state = state.copy(issues = state.issues + issues); publish() } }
                    log(current, "${module.path}: ${issues.size} issue(s), exit ${output.code}.\n", output.code != 0 && issues.isEmpty())
                } catch (error: Exception) {
                    if (!cancelled.get()) { failures++; log(current, "${module.path}: ${failure(error)}\n", true) }
                } finally { if (report != null) Files.deleteIfExists(report) }
            }
            onEdt {
                if (current == operation) {
                    val status = if (cancelled.get()) "Inspections cancelled; ${state.issues.size} collected issue(s)."
                    else "${tool.title}: ${state.issues.size} issue(s) in ${modules.size} module(s)${if (failures > 0) "; $failures failed module(s), see console" else ""}."
                    state = state.copy(busy = false, message = status); publish()
                }
            }
        } catch (error: Exception) { onEdt { if (current == operation) { state = state.copy(busy = false, message = if (cancelled.get()) "Inspections cancelled." else failure(error)); log(current, state.message + "\n", true) } } }
    }

    private data class Output(val code: Int, val stdout: String, val stderr: String, val truncated: Boolean)
    private fun execute(current: Long, directory: String, executable: String?, args: List<String>, timeoutSeconds: Long? = null): Output {
        check(!cancelled.get() && !disposed) { "Inspections cancelled." }
        val command = GoToolchain.command(project, "go", args).withWorkDirectory(directory).withCharset(StandardCharsets.UTF_8)
        if (executable != null) command.withExePath(executable)
        log(current, "[$directory] ${command.commandLineString}\n")
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val truncated = AtomicBoolean()
        val handler = GoInspectionProcesses.create(command)
        active = handler
        handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                val error = outputType === ProcessOutputTypes.STDERR
                val buffer = if (error) stderr else stdout
                synchronized(buffer) {
                    val remaining = (32 * 1024 * 1024 - buffer.length).coerceAtLeast(0)
                    if (event.text.length > remaining) truncated.set(true)
                    buffer.append(event.text.take(remaining))
                }
                log(current, event.text, error)
            }
        })
        handler.startNotify()
        if (cancelled.get() || disposed) handler.destroyProcess()
        try {
            if (timeoutSeconds == null) handler.waitFor() else if (!handler.waitFor(TimeUnit.SECONDS.toMillis(timeoutSeconds))) {
                handler.destroyProcess(); handler.waitFor(3000)
                error("Tool version query timed out.")
            }
            return Output(handler.exitCode ?: -1, synchronized(stdout) { stdout.toString() }, synchronized(stderr) { stderr.toString() }, truncated.get())
        } finally {
            if (!handler.isProcessTerminated) handler.destroyProcess()
            if (active === handler) active = null
        }
    }

    private fun relativeRoots(directory: String, flags: String): List<String> = buildList {
        val args = GoLintCommands.flags(flags)
        val configured = args.firstOrNull { it.startsWith("--config=") }?.substringAfter('=')
            ?: args.indexOfFirst { it in setOf("--config", "-c") }.takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
        if (!configured.isNullOrBlank()) runCatching { Path.of(directory).resolve(configured).toAbsolutePath().normalize().parent.toString() }.getOrNull()?.let(::add)
        var current: Path? = Path.of(directory).parent
        while (current != null) {
            val candidate = current
            if (listOf(".golangci.yml", ".golangci.yaml", ".golangci.toml", ".golangci.json").any { Files.isRegularFile(candidate.resolve(it)) }) add(candidate.toString())
            current = current.parent
        }
        project.basePath?.let(::add)
    }
    private fun log(current: Long, text: String, error: Boolean = false) = onEdt {
        if (current != operation) return@onEdt
        val entries = (state.log + GoInspectionLog(++sequence, text.takeLast(64 * 1024), error)).takeLast(1000).toMutableList()
        var size = entries.sumOf { it.text.length }
        while (size > 2 * 1024 * 1024 && entries.size > 1) size -= entries.removeAt(0).text.length
        state = state.copy(log = entries); publish()
    }
    private fun publish() { if (!disposed) listeners.forEach { it(state) } }
    private fun onEdt(action: () -> Unit) {
        if (disposed || project.isDisposed) return
        val app = ApplicationManager.getApplication()
        val guarded = Runnable { if (!disposed && !project.isDisposed) WriteIntentReadAction.run(Runnable(action)) }
        if (app.isDispatchThread) guarded.run() else app.invokeLater(guarded)
    }
    private fun pooled(action: () -> Unit) = ApplicationManager.getApplication().executeOnPooledThread { if (!disposed && !project.isDisposed) action() }
    override fun dispose() {
        disposed = true; cancelled.set(true); listeners.clear()
        val handler = active
        if (handler != null) ApplicationManager.getApplication().executeOnPooledThread { if (!handler.isProcessTerminated) handler.destroyProcess() }
    }
    companion object {
        fun get(project: Project): GoLintService = project.getService(GoLintService::class.java)
        private fun failure(error: Throwable): String = error.cause?.message ?: error.message ?: error.javaClass.simpleName
    }
}
