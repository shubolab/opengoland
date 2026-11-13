package dev.openidea.goide.http

import com.intellij.execution.RunContentExecutor
import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import dev.openidea.goide.lang.HttpRequestFileType
import dev.openidea.goide.lang.HttpRequestTokens
import java.io.OutputStream
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class RunHttpRequestAction : DumbAwareAction("Run HTTP Request", "Send the HTTP request at the caret", AllIcons.Actions.Execute) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = event.project != null && event.getData(CommonDataKeys.EDITOR) != null &&
            event.getData(CommonDataKeys.VIRTUAL_FILE)?.fileType == HttpRequestFileType.INSTANCE
    }
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val editor = event.getData(CommonDataKeys.EDITOR) ?: return
        project.getService(HttpRequestRuns::class.java).run(editor.document.text, editor.caretModel.offset)
    }
}

class HttpRequestRunLineMarkerContributor : RunLineMarkerContributor(), DumbAware {
    override fun getInfo(element: PsiElement): Info? {
        if (element.node.elementType != HttpRequestTokens.METHOD) return null
        var previous = element.prevSibling
        while (previous != null && previous.node.elementType != HttpRequestTokens.SEPARATOR) {
            if (previous.node.elementType == HttpRequestTokens.METHOD) return null
            previous = previous.prevSibling
        }
        val action = object : DumbAwareAction("Run HTTP Request", "Send this HTTP request", AllIcons.Actions.Execute) {
            override fun actionPerformed(event: AnActionEvent) {
                val project = element.project
                if (!element.isValid || project.isDisposed) return
                val document = PsiDocumentManager.getInstance(project).getDocument(element.containingFile) ?: return
                project.getService(HttpRequestRuns::class.java).run(document.text, element.textOffset)
            }
        }
        return Info(AllIcons.Actions.Execute, arrayOf(action)) { "Run HTTP Request" }
    }
}

/** Owns running exchanges even if their console is hidden; project close cancels all of them. */
@Service(Service.Level.PROJECT)
internal class HttpRequestRuns(private val project: Project) : Disposable {
    private val runs = ConcurrentHashMap.newKeySet<HttpRequestProcessHandler>()
    @Volatile private var disposed = false

    fun run(text: String, offset: Int) {
        if (disposed || project.isDisposed) return
        val handler = HttpRequestProcessHandler(text, offset) { runs.remove(it) }
        runs.add(handler)
        try {
            RunContentExecutor(project, handler).withTitle("HTTP Request").withActivateToolWindow(true)
                .withStop({ handler.destroyProcess() }, { !handler.isProcessTerminated && !handler.isProcessTerminating }).run()
        } catch (failure: Throwable) { handler.destroyProcess(); throw failure }
    }

    override fun dispose() { disposed = true; runs.toList().forEach { it.destroyProcess() }; runs.clear() }
}

internal class HttpRequestProcessHandler(
    text: String,
    private val offset: Int,
    private val finishedCallback: (HttpRequestProcessHandler) -> Unit,
) : ProcessHandler() {
    private val snapshot = AtomicReference<String?>(text)
    private val call = AtomicReference<HttpRequestCall?>()
    private val started = AtomicBoolean()
    private val cancelled = AtomicBoolean()
    private val finished = AtomicBoolean()

    override fun startNotify() {
        if (!started.compareAndSet(false, true)) return
        super.startNotify()
        ApplicationManager.getApplication().executeOnPooledThread {
            var exchange: HttpRequestCall? = null
            try {
                val source = snapshot.getAndSet(null) ?: return@executeOnPooledThread
                val spec = HttpRequestDocument.requestAt(source, offset)
                if (cancelled.get()) return@executeOnPooledThread
                notifyTextAvailable("${spec.method} ${spec.uri}\n", ProcessOutputTypes.SYSTEM)
                exchange = HttpRequestCall(spec)
                call.set(exchange)
                if (cancelled.get()) { exchange.close(); return@executeOnPooledThread }
                val response = exchange.await()
                if (cancelled.get()) return@executeOnPooledThread
                notifyTextAvailable("HTTP ${response.status} — ${response.elapsedMillis} ms, ${response.body.size} bytes\n", ProcessOutputTypes.SYSTEM)
                response.headers.forEach { (key, values) -> values.forEach { notifyTextAvailable("$key: $it\n", ProcessOutputTypes.STDOUT) } }
                notifyTextAvailable("\n${response.displayBody()}\n", ProcessOutputTypes.STDOUT)
                finish(0)
            } catch (failure: Exception) {
                var cause: Throwable = failure
                while (cause is ExecutionException && cause.cause != null) cause = cause.cause!!
                if (!cancelled.get()) {
                    val message = when (cause) {
                        is TimeoutException -> "HTTP request timed out after 30 seconds (including response body)."
                        is CancellationException -> "HTTP request cancelled."
                        else -> cause.message ?: cause.javaClass.simpleName
                    }
                    notifyTextAvailable("$message\n", ProcessOutputTypes.STDERR)
                    finish(1)
                }
            } finally {
                exchange?.close(); call.set(null)
                if (cancelled.get()) finish(130)
            }
        }
    }

    override fun destroyProcessImpl() {
        cancelled.set(true); snapshot.set(null); call.get()?.close()
        notifyTextAvailable("HTTP request cancelled.\n", ProcessOutputTypes.SYSTEM)
        finish(130)
    }
    override fun detachProcessImpl() = destroyProcessImpl()
    override fun detachIsDefault() = false
    override fun getProcessInput(): OutputStream? = null
    private fun finish(code: Int) {
        if (!finished.compareAndSet(false, true)) return
        snapshot.set(null); notifyProcessTerminated(code); finishedCallback(this)
    }
}
