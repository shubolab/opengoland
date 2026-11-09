package dev.openidea.goide.engine

import com.google.gson.JsonObject
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import dev.openidea.goide.settings.GoToolchain
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

abstract class GoTaskAction(private val command: String, private val title: String) : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) { e.presentation.isEnabledAndVisible = e.project != null }
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        // CLI tasks consume disk files. Use the platform save action on its
        // required thread before discovering the actual module/workspace.
        FileDocumentManager.getInstance().saveAllDocuments()
        val selected = e.getData(CommonDataKeys.VIRTUAL_FILE)
        project.getService(GoTaskTargetsService::class.java).invoke(command, title, selected)
    }
}
class GoTidyAction : GoTaskAction("tidy", "Go Mod Tidy")
class GoDownloadAction : GoTaskAction("download", "Download Go Modules")
class GoVendorAction : GoTaskAction("vendor", "Vendor Go Dependencies")
class GoWorkSyncAction : GoTaskAction("workSync", "Sync Go Workspace")
class GoVetAction : GoTaskAction("vet", "Go Vet")
class GoGenerateAction : GoTaskAction("generate", "Go Generate")
class GoBenchmarkAction : GoTaskAction("benchmark", "Go Benchmark")

internal class GoTaskProcessHandler(
    private val project: Project,
    private val command: String,
    private val cwd: String,
    private val settings: GoToolchain.Settings,
    environment: Map<String, String>,
    targetSelection: JsonObject,
    refreshRoots: List<String>,
    private val documentGuard: GoTaskDocumentGuard,
    warnings: List<String> = emptyList(),
) : ProcessHandler() {
    private val engine = project.getService(EngineClient::class.java)
    private val targetService = project.getService(GoTaskTargetsService::class.java)
    private val taskId = UUID.randomUUID().toString()
    private val operationId = UUID.randomUUID().toString()
    private val environment = environment.toMap()
    private val targetSelection = targetSelection.deepCopy()
    private val refreshRoots = (refreshRoots + cwd).distinct()
    private val warnings = warnings.toList()
    private val started = AtomicBoolean()
    private val cancelled = AtomicBoolean()
    private val finished = AtomicBoolean()
    private val executed = AtomicBoolean()
    private var subscription: AutoCloseable? = null

    override fun startNotify() {
        if (!started.compareAndSet(false, true)) return
        super.startNotify()
        warnings.forEach { notifyTextAvailable("$it\n", ProcessOutputTypes.SYSTEM) }
        subscription = engine.listen { method, params ->
            if (method == "engine/stopped" && params.getAsJsonArray("taskIds")?.any { it.asString == taskId } == true) finish(-1)
            else if (params.get("id")?.asString == taskId) when (method) {
                "task/output" -> {
                    val stderr = params.get("stream").asString == "stderr"
                    if (command != "benchmark" || stderr) notifyTextAvailable(params.get("text").asString, if (stderr) ProcessOutputTypes.STDERR else ProcessOutputTypes.STDOUT)
                }
                "test/event" -> if (command == "benchmark") params.getAsJsonObject("event")?.get("Output")?.let { notifyTextAvailable(it.asString, ProcessOutputTypes.STDOUT) }
                "task/exit" -> finish(params.get("code").asInt)
                "task/step" -> {
                    executed.set(true)
                    val directory = params.get("cwd")?.asString.orEmpty()
                    val executable = params.get("executable")?.asString.orEmpty()
                    val arguments = params.getAsJsonArray("args")?.map { it.asString }.orEmpty()
                    notifyTextAvailable("Working directory: $directory\n$executable ${com.intellij.util.execution.ParametersListUtil.join(arguments)}\n", ProcessOutputTypes.SYSTEM)
                }
            }
        }
        if (finished.get()) { subscription?.close(); subscription = null; return }
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                check(!project.isDisposed && GoToolchain.configurationKey(project) == settings.key) {
                    "The Go SDK or build settings changed. Run the action again."
                }
                if (cancelled.get()) { finish(130); return@executeOnPooledThread }
                val args = mutableListOf<String>()
                if (command in setOf("vet", "generate", "benchmark") && settings.buildTags.isNotBlank()) {
                    args.add("-tags=" + settings.buildTags.split(Regex("[,\\s]+")).filter(String::isNotBlank).joinToString(","))
                    args.add("./...")
                }
                val params = JsonObject().apply {
                    addProperty("id", taskId); addProperty("command", command); addProperty("cwd", cwd)
                    addProperty("operationId", operationId)
                    add("targetSelection", targetSelection.deepCopy())
                    add("args", com.google.gson.JsonArray().also { a -> args.forEach(a::add) })
                    add("env", JsonObject().also { env -> environment.forEach { (k, v) -> env.addProperty(k, v) } })
                }
                engine.request("task/prepare", params, settings, timeoutSeconds = 90).whenComplete { _, error ->
                    if (error != null) {
                        fail(error.cause?.message ?: error.message)
                    } else if (cancelled.get()) cancelTask()
                    else ApplicationManager.getApplication().invokeLater {
                        if (finished.get() || cancelled.get()) { cancelTask(); return@invokeLater }
                        try {
                            WriteIntentReadAction.run {
                                check(!project.isDisposed && GoToolchain.configurationKey(project) == settings.key) {
                                    "The Go SDK or build settings changed. Run the action again."
                                }
                                documentGuard.validate()
                            }
                            ApplicationManager.getApplication().executeOnPooledThread resumeTask@{
                                if (finished.get() || cancelled.get()) { cancelTask(); return@resumeTask }
                                if (project.isDisposed || GoToolchain.configurationKey(project) != settings.key) {
                                    fail("The Go SDK or build settings changed. Run the action again."); return@resumeTask
                                }
                                engine.request("task/resume", JsonObject().apply { addProperty("id", taskId) }, settings).whenComplete { _, resumeError ->
                                    if (resumeError != null) {
                                        fail(resumeError.cause?.message ?: resumeError.message)
                                    } else if (cancelled.get()) cancelTask()
                                }
                            }
                        } catch (failure: Exception) {
                            fail(failure.message)
                        }
                    }
                }
            } catch (error: Exception) {
                fail(error.message)
            }
        }
    }
    private fun fail(message: String?) {
        notifyTextAvailable("${message ?: "The Go task could not start"}\n", ProcessOutputTypes.STDERR)
        cancelled.set(true)
        cancelTask()
        finish(-1)
    }
    private fun cancelTask() {
        engine.cancelOperation(operationId)
        engine.cancelTask(taskId)
    }
    private fun finish(code: Int) {
        if (!finished.compareAndSet(false, true)) return
        subscription?.close(); subscription = null
        if (!isProcessTerminated) notifyProcessTerminated(code)
        if (executed.get()) targetService.refreshAfterTask(refreshRoots)
    }
    override fun destroyProcessImpl() { cancelled.set(true); cancelTask() }
    override fun detachProcessImpl() { destroyProcessImpl() }
    override fun detachIsDefault() = false
    override fun getProcessInput(): OutputStream? = null
}
