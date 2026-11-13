package dev.openidea.goide.http

import com.intellij.execution.services.ServiceViewContributor
import com.intellij.execution.services.ServiceViewDescriptor
import com.intellij.icons.AllIcons
import com.intellij.ide.projectView.PresentationData
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.pom.Navigatable
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicReference
import javax.swing.DefaultListModel
import javax.swing.JList
import javax.swing.JPanel

internal enum class HttpRequestPhase { QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED }

internal data class HttpRequestItemStatus(
    val phase: HttpRequestPhase = HttpRequestPhase.QUEUED,
    val method: String? = null,
    val uri: String? = null,
    val httpStatus: Int? = null,
    val elapsedMillis: Long? = null,
    val wireBytes: Int? = null,
    val message: String? = null,
) {
    val text: String get() = when (phase) {
        HttpRequestPhase.QUEUED -> "Waiting"
        HttpRequestPhase.RUNNING -> "Sending"
        HttpRequestPhase.COMPLETED -> "HTTP $httpStatus — $elapsedMillis ms, $wireBytes bytes"
        HttpRequestPhase.FAILED -> "Failed"
        HttpRequestPhase.CANCELLED -> "Cancelled"
    }
}

internal class HttpRequestItem(val entry: HttpRequestEntry) {
    private val current = AtomicReference(HttpRequestItemStatus())
    val status: HttpRequestItemStatus get() = current.get()
    internal fun update(status: HttpRequestItemStatus): HttpRequestItemStatus = current.getAndSet(status)
    override fun toString() = entry.displayName
}

internal object HttpServicesDataKeys {
    val SESSION: DataKey<HttpRequestSession> = DataKey.create("open.goland.http.session")
    val REQUEST: DataKey<HttpRequestItem> = DataKey.create("open.goland.http.request")
}

/** Stable Services identity; worker callbacks retain response bodies only in the project cache. */
internal class HttpRequestSession(
    private val project: Project,
    text: String,
    plan: HttpRequestPlan,
    entries: List<HttpRequestEntry>,
    private val sourceFile: VirtualFile?,
    val title: String,
    private val cache: HttpResponseCache,
    private val onChanged: (HttpRequestSession) -> Unit,
    private val onFinished: (HttpRequestSession) -> Unit,
    private val onRepeat: (HttpRequestEntry?) -> Unit,
    private val onRemove: () -> Unit,
) : ServiceViewContributor<HttpRequestItem>, Disposable {
    val snapshotText: String = text
    val id: String = UUID.randomUUID().toString()
    val items: List<HttpRequestItem> = entries.map(::HttpRequestItem)
    private val byOrdinal = items.associateBy { it.entry.blockOrdinal }
    private val disposed = AtomicBoolean()
    private val finished = AtomicBoolean()
    private val counts = AtomicIntegerArray(HttpRequestPhase.entries.size)
    private var batchView: BatchView? = null // All Swing references are accessed on the EDT.
    private val itemViews = HashMap<HttpRequestItem, ItemView>()
    private val batchToolbar by lazy { actions(null) }
    private val itemToolbars = ConcurrentHashMap<HttpRequestItem, DefaultActionGroup>()
    @Volatile private var selectedItem: HttpRequestItem? = items.firstOrNull()
    val isDisposed: Boolean get() = disposed.get()
    val isFinished: Boolean get() = finished.get()

    init {
        counts.set(HttpRequestPhase.QUEUED.ordinal, items.size)
        cache.registerOwner(id) { changed() }
    }

    val handler: HttpBatchProcessHandler = HttpBatchProcessHandler(plan, entries, object : HttpBatchObserver {
        override fun started(entry: HttpRequestEntry, method: String, uri: String) = update(entry) {
            it.copy(phase = HttpRequestPhase.RUNNING, method = method,
                uri = if (uri.length <= 512) uri else uri.take(511) + "…")
        }

        override fun responded(entry: HttpRequestEntry, response: HttpRequestResponse) {
            if (isDisposed) return
            val prepared = HttpResponsePresentation.prepare(response)
            if (isDisposed) return
            cache.put(id, entry.blockOrdinal, response, prepared)
            update(entry) { it.copy(phase = HttpRequestPhase.COMPLETED, httpStatus = response.status,
                elapsedMillis = response.elapsedMillis, wireBytes = response.body.size) }
        }

        override fun failed(entry: HttpRequestEntry, message: String) = update(entry) {
            it.copy(phase = HttpRequestPhase.FAILED, message = message.take(4096))
        }

        override fun cancelled(entry: HttpRequestEntry) = update(entry) { it.copy(phase = HttpRequestPhase.CANCELLED) }
    }, includeResponseDetailInConsole = false, finishedCallback = {
        finished.set(true)
        if (!isDisposed) { changed(); onFinished(this) }
    })

    private fun update(entry: HttpRequestEntry, transform: (HttpRequestItemStatus) -> HttpRequestItemStatus) {
        if (isDisposed) return
        val item = byOrdinal[entry.blockOrdinal] ?: return
        val next = transform(item.status)
        val previous = item.update(next)
        if (previous.phase != next.phase) {
            counts.decrementAndGet(previous.phase.ordinal); counts.incrementAndGet(next.phase.ordinal)
        }
        changed()
    }

    private fun changed() { if (!isDisposed) onChanged(this) }

    fun cachedResponse(entry: HttpRequestEntry): HttpRequestResponse? = cache.get(id, entry.blockOrdinal)?.response

    fun refreshViews() {
        ApplicationManager.getApplication().assertIsDispatchThread()
        if (isDisposed) return
        batchView?.refresh()
        itemViews.values.toList().forEach { it.refresh() }
    }

    fun stop() { if (!handler.isProcessTerminated) handler.destroyProcess() }

    val descriptor: ServiceViewDescriptor = descriptorFor(null)

    override fun getViewDescriptor(project: Project): ServiceViewDescriptor = descriptor
    override fun getServices(project: Project): List<HttpRequestItem> = if (isDisposed || items.size == 1) emptyList() else items
    override fun getServiceDescriptor(project: Project, service: HttpRequestItem): ServiceViewDescriptor = descriptorFor(service)

    private fun descriptorFor(item: HttpRequestItem?): ServiceViewDescriptor = object : ServiceViewDescriptor, UiDataProvider {
        override fun getId(): String = if (item == null) id else "$id:${item.entry.blockOrdinal}"
        override fun getPresentation() = if (item == null) {
            val result = HttpRequestPhase.entries.joinToString(", ") { "${counts.get(it.ordinal)} ${it.name.lowercase()}" }
            PresentationData(title.take(256), result, AllIcons.FileTypes.Http, null)
        } else {
            val status = item.status
            val icon = when {
                status.phase == HttpRequestPhase.FAILED -> AllIcons.General.Error
                status.phase == HttpRequestPhase.CANCELLED -> AllIcons.Actions.Cancel
                status.phase == HttpRequestPhase.RUNNING -> AllIcons.Actions.Refresh
                (status.httpStatus ?: 0) >= 400 -> AllIcons.General.Warning
                else -> AllIcons.FileTypes.Http
            }
            PresentationData(item.entry.displayName.take(256), status.text, icon, null)
        }

        override fun getContentComponent(): JPanel? {
            ApplicationManager.getApplication().assertIsDispatchThread()
            if (isDisposed) return null
            return if (item == null) {
                if (items.size == 1) itemViews.getOrPut(items.single()) { ItemView(items.single()) }.also { it.refresh() }
                else (batchView ?: BatchView().also { batchView = it }).also { it.refresh() }
            } else {
                itemViews.getOrPut(item) { ItemView(item) }.also { it.refresh() }
            }
        }

        override fun getNavigatable(): Navigatable? {
            val file = sourceFile ?: return null
            val line = ((item ?: selectedItem)?.entry?.line ?: 1) - 1
            // Services collects action data under Dispatchers.UI, where taking a
            // read lock is prohibited. OpenFileDescriptor creates a lazy range
            // marker in its constructor, so create it only when navigation runs.
            return object : Navigatable {
                override fun canNavigate() = !isDisposed && !project.isDisposed
                override fun canNavigateToSource() = canNavigate()
                override fun navigate(requestFocus: Boolean) {
                    if (canNavigate() && file.isValid) OpenFileDescriptor(project, file, line, 0).navigate(requestFocus)
                }
            }
        }

        override fun getToolbarActions() = if (item == null) batchToolbar else itemToolbars.computeIfAbsent(item) { actions(it) }
        override fun getRemover(): Runnable? = if (item == null) Runnable { if (!isDisposed) { stop(); onRemove() } } else null
        override fun onNodeUnselected() {
            ApplicationManager.getApplication().assertIsDispatchThread()
            if (item == null && items.size == 1) itemViews.remove(items.single())?.let { Disposer.dispose(it) }
            else if (item == null) batchView?.clearResponse()
            else itemViews.remove(item)?.let { Disposer.dispose(it) }
        }
        override fun uiDataSnapshot(sink: DataSink) {
            sink.set(HttpServicesDataKeys.SESSION, this@HttpRequestSession)
            sink.set(HttpServicesDataKeys.REQUEST, item ?: selectedItem)
        }
    }

    private fun actions(item: HttpRequestItem?): DefaultActionGroup = DefaultActionGroup().apply {
        add(object : DumbAwareAction("Run Again", "Run the captured request snapshot again", AllIcons.Actions.Rerun) {
            override fun getActionUpdateThread() = ActionUpdateThread.BGT
            override fun update(e: AnActionEvent) { e.presentation.isEnabled = !isDisposed && !project.isDisposed }
            override fun actionPerformed(e: AnActionEvent) { if (!isDisposed && !project.isDisposed) onRepeat(item?.entry) }
        })
        add(object : DumbAwareAction("Stop All", "Cancel this batch and all its unsent requests", AllIcons.Actions.Suspend) {
            override fun getActionUpdateThread() = ActionUpdateThread.BGT
            override fun update(e: AnActionEvent) { e.presentation.isEnabled = !isDisposed && !handler.isProcessTerminated && !handler.isProcessTerminating }
            override fun actionPerformed(e: AnActionEvent) { stop() }
        })
        ActionManager.getInstance().getAction("OpenGoland.Http.SaveResponse")?.let(::add)
        add(object : DumbAwareAction("Remove Batch", "Remove this batch and its retained responses", AllIcons.Actions.GC) {
            override fun getActionUpdateThread() = ActionUpdateThread.BGT
            override fun update(e: AnActionEvent) { e.presentation.isEnabled = !isDisposed }
            override fun actionPerformed(e: AnActionEvent) { if (!isDisposed) { stop(); onRemove() } }
        })
    }

    private inner class ItemView(private val item: HttpRequestItem) : JPanel(BorderLayout()), Disposable {
        private val summary = JBLabel().apply {
            putClientProperty("html.disable", true)
            border = JBUI.Borders.empty(6, 8)
        }
        private val responseView = HttpResponseView(project, this)
        private var lastPresentation: HttpResponsePresentation? = null
        private var lastMessage: String? = null
        init {
            Disposer.register(this@HttpRequestSession, this)
            add(summary, BorderLayout.NORTH)
            add(responseView, BorderLayout.CENTER)
        }

        fun refresh() {
            val status = item.status
            summary.text = "${item.entry.displayName.take(160)} — ${status.text}"
            val prepared = cache.peek(id, item.entry.blockOrdinal)?.presentation
            if (status.phase == HttpRequestPhase.COMPLETED && prepared != null) {
                if (lastPresentation !== prepared) { responseView.show(prepared); lastPresentation = prepared; lastMessage = null }
            } else {
                val message = when (status.phase) {
                    HttpRequestPhase.QUEUED -> "Waiting to send ${item.entry.displayName.take(256)}."
                    HttpRequestPhase.RUNNING -> "Sending ${status.method.orEmpty()} ${status.uri.orEmpty()}"
                    HttpRequestPhase.COMPLETED -> "${status.text}\nResponse body is no longer available. Run this request again to view or save it."
                    HttpRequestPhase.FAILED -> status.message ?: "HTTP request failed."
                    HttpRequestPhase.CANCELLED -> "HTTP request cancelled."
                }
                if (lastMessage != message) { responseView.showMessage(message); lastMessage = message; lastPresentation = null }
            }
        }
        override fun dispose() { lastPresentation = null; lastMessage = null }
    }

    private inner class BatchView : JPanel(BorderLayout()) {
        private val list = JBList(DefaultListModel<HttpRequestItem>().apply { items.forEach { addElement(it) } })
        private val response = JPanel(BorderLayout())
        private var current: HttpRequestItem? = null
        private var currentView: ItemView? = null

        init {
            list.cellRenderer = object : ColoredListCellRenderer<HttpRequestItem>() {
                override fun customizeCellRenderer(list: JList<out HttpRequestItem>, value: HttpRequestItem?, index: Int,
                                                   selected: Boolean, hasFocus: Boolean) {
                    if (value != null) {
                        append(value.entry.displayName.take(256), SimpleTextAttributes.REGULAR_ATTRIBUTES)
                        append("  ${value.status.text}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    }
                }
            }
            list.addListSelectionListener { if (!it.valueIsAdjusting) { selectedItem = list.selectedValue; refreshSelection() } }
            add(OnePixelSplitter(false, 0.32f).apply {
                firstComponent = JBScrollPane(list); secondComponent = response
            }, BorderLayout.CENTER)
            if (items.isNotEmpty()) list.selectedIndex = 0
        }

        private fun refreshSelection() {
            val item = list.selectedValue
            if (item !== current) {
                currentView?.let { Disposer.dispose(it) }
                current = item
                currentView = item?.let { ItemView(it) }
                response.removeAll()
                currentView?.let { response.add(it, BorderLayout.CENTER) }
                response.revalidate(); response.repaint()
            }
            currentView?.refresh()
        }

        fun refresh() { list.repaint(); refreshSelection() }
        fun clearResponse() {
            currentView?.let { Disposer.dispose(it) }
            currentView = null; current = null
            response.removeAll()
        }
    }

    override fun dispose() {
        if (!disposed.compareAndSet(false, true)) return
        stop(); cache.removeSession(id)
        val application = ApplicationManager.getApplication()
        val release = {
            Disposer.disposeChildren(this) { true }
            batchView = null; itemViews.clear()
        }
        if (application.isDispatchThread) release() else application.invokeLater(release, ModalityState.any())
    }
}
