package dev.openidea.goide.http

import com.intellij.execution.RunContentExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.services.ServiceEventListener
import com.intellij.execution.services.ServiceViewManager
import com.intellij.execution.ui.RunContentManager
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.util.ui.update.MergingUpdateQueue
import com.intellij.util.ui.update.Update
import dev.openidea.goide.lang.HttpRequestFileType
import dev.openidea.goide.lang.HttpRequestTokens
import java.util.concurrent.atomic.AtomicBoolean

class RunHttpRequestAction : DumbAwareAction("Run HTTP Request", "Send the HTTP request at the caret", AllIcons.Actions.Execute) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(event: AnActionEvent) { event.presentation.isEnabledAndVisible = isHttpEditor(event) }
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val editor = event.getData(CommonDataKeys.EDITOR) ?: return
        project.getService(HttpRequestRuns::class.java).run(editor.document.text, editor.caretModel.offset,
            event.getData(CommonDataKeys.VIRTUAL_FILE))
    }
}

class RunAllHttpRequestsAction : DumbAwareAction("Run All Requests", "Run every request in this HTTP file in order", AllIcons.Actions.RunAll) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(event: AnActionEvent) { event.presentation.isEnabledAndVisible = isHttpEditor(event) }
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val editor = event.getData(CommonDataKeys.EDITOR) ?: return
        project.getService(HttpRequestRuns::class.java).runAll(editor.document.text, event.getData(CommonDataKeys.VIRTUAL_FILE))
    }
}

private fun isHttpEditor(event: AnActionEvent) = event.project != null &&
    event.getData(CommonDataKeys.EDITOR) != null && event.getData(CommonDataKeys.VIRTUAL_FILE)?.fileType == HttpRequestFileType.INSTANCE

/** Uses the platform's above-editor toolbar, with the editor's own data context. */
class HttpRequestEditorToolbar : FileEditorManagerListener {
    override fun fileOpened(manager: FileEditorManager, file: VirtualFile) {
        if (file.fileType != HttpRequestFileType.INSTANCE) return
        manager.getEditors(file).filterIsInstance<TextEditor>().forEach { editor ->
            val toolbar = ActionManager.getInstance().createActionToolbar("OpenIDEA.Http.Editor",
                DefaultActionGroup(RunAllHttpRequestsAction(), RunHttpRequestAction()), true)
            toolbar.targetComponent = editor.editor.contentComponent
            manager.addTopComponent(editor, toolbar.component)
            (toolbar as? Disposable)?.let { Disposer.register(editor, it) }
        }
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
        fun action(all: Boolean) = object : DumbAwareAction(if (all) "Run All Requests" else "Run HTTP Request",
            if (all) "Run every request in this file" else "Send this HTTP request", if (all) AllIcons.Actions.RunAll else AllIcons.Actions.Execute) {
            override fun actionPerformed(event: AnActionEvent) {
                if (!element.isValid || element.project.isDisposed) return
                val file = element.containingFile
                val document = PsiDocumentManager.getInstance(element.project).getDocument(file) ?: return
                val runs = element.project.getService(HttpRequestRuns::class.java)
                if (all) runs.runAll(document.text, file.virtualFile)
                else runs.run(document.text, element.textOffset, file.virtualFile)
            }
        }
        return Info(AllIcons.Actions.Execute, arrayOf(action(false), action(true))) { "Run HTTP Request" }
    }
}

/** Owns active calls, bounded response retention and the native Run/Services presentations. */
@Service(Service.Level.PROJECT)
internal class HttpRequestRuns(private val project: Project) : Disposable {
    private val ownership = Any()
    private val records = mutableListOf<HttpRequestSession>()
    private val cache = HttpResponseCache()
    private val updates = MergingUpdateQueue("HTTP request results", 75, true, null, this)
    @Volatile private var disposed = false

    fun sessions(): List<HttpRequestSession> = synchronized(ownership) { records.toList() }
    fun run(text: String, offset: Int, file: VirtualFile? = null) = prepare(text, offset, file)
    fun runAll(text: String, file: VirtualFile? = null) = prepare(text, null, file)

    private fun prepare(text: String, offset: Int?, file: VirtualFile?, repeatOrdinal: Int? = null) {
        if (disposed || project.isDisposed) return
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val plan = HttpRequestDocument.plan(text)
                val entries = when {
                    repeatOrdinal != null -> listOfNotNull(plan.entries.firstOrNull { it.blockOrdinal == repeatOrdinal })
                    offset != null -> listOfNotNull(plan.entryAt(offset))
                    else -> plan.entries
                }
                require(entries.isNotEmpty()) {
                    if (offset == null && repeatOrdinal == null) "This file does not contain an HTTP request."
                    else "Place the caret inside an HTTP request, below its ### separator."
                }
                val title = if (offset == null && repeatOrdinal == null) "${file?.name ?: "HTTP"} — All Requests"
                    else "${file?.name ?: "HTTP"} | ${entries.single().displayName}"
                ApplicationManager.getApplication().invokeLater {
                    if (disposed || project.isDisposed) return@invokeLater
                    lateinit var session: HttpRequestSession
                    session = HttpRequestSession(project, text, plan, entries, file, title, cache,
                        onChanged = { changed(it) }, onFinished = { finished(it) },
                        onRepeat = { entry -> prepare(text, null, file, entry?.blockOrdinal ?:
                            if (offset != null || repeatOrdinal != null) entries.single().blockOrdinal else null) },
                        onRemove = { remove(session) })
                    val accepted = synchronized(ownership) {
                        if (disposed) false else { records += session; true }
                    }
                    if (!accepted) { Disposer.dispose(session); return@invokeLater }
                    event(ServiceEventListener.ServiceEvent.createServiceAddedEvent(session, HttpServicesContributor::class.java, null))
                    try {
                        // Retain native global Stop and close-project process ownership.
                        // Services is selected separately and is the HTTP result surface.
                        RunContentExecutor(project, session.handler).withTitle(title).withActivateToolWindow(false)
                            .withStop({ session.handler.destroyProcess() },
                                { !session.handler.isProcessTerminated && !session.handler.isProcessTerminating }).run()
                        ServiceViewManager.getInstance(project).select(session, HttpServicesContributor::class.java, true, true)
                            .onError { LOG.debug("HTTP Services selection was unavailable", it) }
                    } catch (failure: Exception) {
                        remove(session)
                        LOG.warn("Could not open HTTP request results", failure)
                        Messages.showErrorDialog(project, failure.message ?: "Could not open HTTP results.", "HTTP Requests")
                    }
                }
            } catch (failure: IllegalArgumentException) {
                ApplicationManager.getApplication().invokeLater {
                    if (!disposed && !project.isDisposed) Messages.showErrorDialog(project,
                        failure.message ?: "Invalid HTTP request.", "HTTP Requests")
                }
            }
        }
    }

    private fun changed(session: HttpRequestSession) {
        if (disposed || project.isDisposed) return
        updates.queue(object : Update(session) {
            override fun run() {
                if (disposed || project.isDisposed || session.isDisposed) return
                session.refreshViews()
                event(ServiceEventListener.ServiceEvent.createEvent(ServiceEventListener.EventType.SERVICE_CHANGED,
                    session, HttpServicesContributor::class.java))
                event(ServiceEventListener.ServiceEvent.createEvent(ServiceEventListener.EventType.SERVICE_CHILDREN_CHANGED,
                    session, HttpServicesContributor::class.java))
            }
        })
    }

    private fun finished(session: HttpRequestSession) {
        changed(session)
        ApplicationManager.getApplication().invokeLater {
            if (disposed || project.isDisposed) return@invokeLater
            val old = synchronized(ownership) { records.filter { it.isFinished }.dropLast(20) }
            old.forEach { remove(it) }
        }
    }

    private fun remove(session: HttpRequestSession) {
        val existed = synchronized(ownership) { records.remove(session) }
        if (!existed) return
        val scheduled = AtomicBoolean()
        fun removeConsoleAfterTermination() {
            if (!scheduled.compareAndSet(false, true)) return
            ApplicationManager.getApplication().invokeLater {
                if (!disposed && !project.isDisposed) {
                    val manager = RunContentManager.getInstanceIfCreated(project) ?: return@invokeLater
                    val executor = DefaultRunExecutor.getRunExecutorInstance()
                    manager.findContentDescriptor(executor, session.handler)?.let { descriptor ->
                        if (!manager.removeRunContent(executor, descriptor)) LOG.debug("HTTP Run content was already unavailable for removal")
                    }
                }
            }
        }
        val listener = object : ProcessAdapter() {
            override fun processTerminated(event: ProcessEvent) {
                session.handler.removeProcessListener(this)
                removeConsoleAfterTermination()
            }
        }
        session.handler.addProcessListener(listener)
        Disposer.dispose(session)
        if (session.handler.isProcessTerminated) {
            session.handler.removeProcessListener(listener)
            removeConsoleAfterTermination()
        }
        if (!project.isDisposed) {
            event(ServiceEventListener.ServiceEvent.createEvent(ServiceEventListener.EventType.SERVICE_REMOVED,
                session, HttpServicesContributor::class.java))
        }
    }

    private fun event(event: ServiceEventListener.ServiceEvent) {
        if (!disposed && !project.isDisposed) project.messageBus.syncPublisher(ServiceEventListener.TOPIC).handle(event)
    }

    override fun dispose() {
        val owned = synchronized(ownership) { disposed = true; records.toList().also { records.clear() } }
        owned.forEach { Disposer.dispose(it) }
        cache.clear()
    }

    private companion object { val LOG = Logger.getInstance(HttpRequestRuns::class.java) }
}
