package dev.openidea.goide.engine

import com.google.gson.JsonObject
import com.intellij.execution.RunContentExecutor
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import dev.openidea.goide.settings.GoProjectSettings
import dev.openidea.goide.settings.GoToolchain
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

abstract class GoTaskAction(private val command: String, private val title: String) : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) { e.presentation.isEnabledAndVisible = e.project != null }
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        FileDocumentManager.getInstance().saveAllDocuments()
        val selected = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val cwd = if (selected != null && selected.name in setOf("go.mod", "go.work")) selected.parent.path else project.basePath ?: return
        val handler = GoTaskProcessHandler(project, command, cwd)
        RunContentExecutor(project, handler).withTitle(title).withActivateToolWindow(true).run()
    }
}
class GoTidyAction : GoTaskAction("tidy", "Go Mod Tidy")
class GoDownloadAction : GoTaskAction("download", "Download Go Modules")
class GoVendorAction : GoTaskAction("vendor", "Vendor Go Dependencies")
class GoWorkSyncAction : GoTaskAction("workSync", "Sync Go Workspace")
class GoVetAction : GoTaskAction("vet", "Go Vet")
class GoGenerateAction : GoTaskAction("generate", "Go Generate")
class GoBenchmarkAction : GoTaskAction("benchmark", "Go Benchmark")

class GoTaskProcessHandler(private val project: Project, private val command: String, private val cwd: String) : ProcessHandler() {
    private val engine = project.getService(EngineClient::class.java)
    private val taskId = UUID.randomUUID().toString()
    private val started = AtomicBoolean()
    private val cancelled = AtomicBoolean()
    private val finished = AtomicBoolean()
    private var subscription: AutoCloseable? = null

    override fun startNotify() {
        if (!started.compareAndSet(false, true)) return
        super.startNotify()
        subscription = engine.listen { method, params ->
            if (method == "engine/stopped" && params.getAsJsonArray("taskIds")?.any { it.asString == taskId } == true) finish(-1)
            else if (params.get("id")?.asString == taskId) when (method) {
                "task/output" -> {
                    val stderr = params.get("stream").asString == "stderr"
                    if (command != "benchmark" || stderr) notifyTextAvailable(params.get("text").asString, if (stderr) ProcessOutputTypes.STDERR else ProcessOutputTypes.STDOUT)
                }
                "test/event" -> if (command == "benchmark") params.getAsJsonObject("event")?.get("Output")?.let { notifyTextAvailable(it.asString, ProcessOutputTypes.STDOUT) }
                "task/exit" -> finish(params.get("code").asInt)
            }
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val settings = GoProjectSettings.get(project)
                val args = mutableListOf<String>()
                if (command in setOf("vet", "generate", "benchmark") && settings.buildTags.isNotBlank()) {
                    args.add("-tags=" + settings.buildTags.split(Regex("[,\\s]+")).filter(String::isNotBlank).joinToString(","))
                    args.add("./...")
                }
                val params = JsonObject().apply {
                    addProperty("id", taskId); addProperty("command", command); addProperty("cwd", cwd)
                    add("args", com.google.gson.JsonArray().also { a -> args.forEach(a::add) })
                    add("env", JsonObject().also { env -> GoToolchain.command(project, "go", emptyList()).environment.forEach { (k, v) -> env.addProperty(k, v) } })
                }
                engine.request("task/start", params).whenComplete { _, error ->
                    if (error != null) {
                        notifyTextAvailable("${error.cause?.message ?: error.message}\n", ProcessOutputTypes.STDERR)
                        finish(-1)
                    } else if (cancelled.get()) cancelTask()
                }
            } catch (error: Exception) {
                notifyTextAvailable("${error.message}\n", ProcessOutputTypes.STDERR)
                finish(-1)
            }
        }
    }
    private fun cancelTask() {
        engine.request("task/cancel", JsonObject().apply { addProperty("id", taskId) })
    }
    private fun finish(code: Int) {
        if (!finished.compareAndSet(false, true)) return
        subscription?.close(); subscription = null
        if (!isProcessTerminated) notifyProcessTerminated(code)
    }
    override fun destroyProcessImpl() { cancelled.set(true); cancelTask() }
    override fun detachProcessImpl() { destroyProcessImpl() }
    override fun detachIsDefault() = false
    override fun getProcessInput(): OutputStream? = null
}
