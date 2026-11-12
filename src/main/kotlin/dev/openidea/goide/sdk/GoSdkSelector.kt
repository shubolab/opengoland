package dev.openidea.goide.sdk

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBLabel
import com.intellij.util.concurrency.AppExecutorUtil
import dev.openidea.goide.project.GoModuleSdk
import dev.openidea.goide.project.GoSdkProbe
import dev.openidea.goide.settings.GoToolchain
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicLong
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities

/** Native reusable SDK selection. Every UI getter is cached; all discovery/probing is background work. */
class GoSdkSelector internal constructor(
    private val project: Project?, parentDisposable: Disposable, initialExecutable: String,
    private val registry: GoSdkRegistry, private val verifier: Verifier,
    private val executor: ExecutorService,
    private val download: (Disposable, (GoModuleSdk) -> Unit) -> Unit,
) {
    constructor(project: Project?, parentDisposable: Disposable, initialExecutable: String = "") : this(
        project, parentDisposable, initialExecutable, GoSdkRegistry.getInstance(), Verifier { configured, cancelled ->
            val executable = GoToolchain.resolve("go", configured)
                ?: throw IllegalArgumentException("Go SDK was not found. Add a local SDK or download one.")
            GoSdkProbe.inspect(executable, cancelled)
        }, AppExecutorUtil.getAppExecutorService(),
        { owner, installed -> GoSdkDownloadDialog(project, owner, installed).show() },
    )

    internal fun interface Verifier { fun inspect(configured: String, cancelled: () -> Boolean): GoModuleSdk }
    private data class Choice(val executable: String, val label: String) { override fun toString() = label }
    private val lifetime = Disposer.newDisposable("open Goland SDK selector")
    private val generation = AtomicLong()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val combo = JComboBox<Choice>()
    private val details = JBLabel()
    private val remove = JButton("Remove registration")
    private var updating = false
    private var pending: Future<*>? = null
    private var verified: GoModuleSdk? = null
    private var defaultSdk: GoModuleSdk? = null
    @Volatile private var disposed = false
    @Volatile private var executable = initialExecutable.trim()
    @Volatile var isReady = false
        private set
    @Volatile var validationMessage: String? = "Checking Go SDK…"
        private set

    var configuredExecutable: String
        get() = executable
        set(value) { select(value.trim()) }

    val component: JComponent = JPanel(BorderLayout(0, 5)).apply {
        add(combo, BorderLayout.NORTH)
        add(details, BorderLayout.CENTER)
        add(JPanel(FlowLayout(FlowLayout.LEADING, 5, 0)).apply {
            add(JButton("Add local…").apply { addActionListener {
                FileChooser.chooseFile(FileChooserDescriptorFactory.singleDir().withTitle("Select Go SDK Home (GOROOT)"), project, null)?.let {
                    addLocalSdk(it.toNioPath())
                }
            } })
            add(JButton("Download…").apply { addActionListener {
                val ticket = generation.get()
                download(lifetime) { sdk ->
                    if (alive() && generation.get() == ticket) registerInstalledSdk(sdk)
                }
            } })
            add(remove)
        }, BorderLayout.SOUTH)
    }

    init {
        check(SwingUtilities.isEventDispatchThread()) { "Construct the SDK selector on the EDT" }
        Disposer.register(parentDisposable, lifetime)
        val subscription = registry.addListener { onEdt {
            if (alive()) rebuildChoices()
        } }
        Disposer.register(lifetime, Disposable {
            disposed = true; generation.incrementAndGet(); pending?.cancel(true)
            subscription.close(); listeners.clear()
        })
        combo.addActionListener { if (!updating) (combo.selectedItem as? Choice)?.let { select(it.executable) } }
        remove.addActionListener { registry.remove(executable) }
        remove.toolTipText = "Forget this SDK registration. SDK files and the current selection are retained."
        select(executable)
    }

    fun addStateListener(listener: () -> Unit): AutoCloseable {
        if (disposed) return AutoCloseable { }
        listeners.add(listener)
        if (disposed) listeners.remove(listener)
        return AutoCloseable { listeners.remove(listener) }
    }

    internal fun addLocalSdk(root: Path) {
        val filename = if (System.getProperty("os.name").startsWith("Windows")) "go.exe" else "go"
        select(root.resolve("bin").resolve(filename).toString(), register = true)
    }

    internal fun registerInstalledSdk(sdk: GoModuleSdk) {
        check(SwingUtilities.isEventDispatchThread())
        if (!alive()) return
        registry.register(sdk)
        select(sdk.executable.toString())
    }

    private fun select(value: String, register: Boolean = false) {
        check(SwingUtilities.isEventDispatchThread()) { "Change SDK selection on the EDT" }
        if (!alive()) return
        val ticket = generation.incrementAndGet()
        pending?.cancel(true)
        executable = value; verified = null; isReady = false; validationMessage = "Checking Go SDK…"
        rebuildChoices(); render(); notifyState()
        if (!alive() || generation.get() != ticket) return
        pending = executor.submit {
            val cancelled = { !alive() || generation.get() != ticket || Thread.currentThread().isInterrupted }
            val result = runCatching { verifier.inspect(value, cancelled) }
            onEdt {
                if (!alive() || generation.get() != ticket) return@onEdt
                pending = null
                result.fold({ sdk ->
                    verified = sdk
                    if (value.isEmpty()) defaultSdk = sdk
                    // Verification must not turn a persisted shim/symlink path
                    // into an unapplied Settings edit. Explicit Add installs the
                    // verified SDK binary; ordinary selection keeps its intent.
                    else if (register) executable = sdk.executable.toString()
                    isReady = true; validationMessage = null
                    if (register) registry.register(sdk)
                }, { error ->
                    isReady = false
                    validationMessage = if (error is CancellationException) "Go SDK verification was cancelled. Select an SDK to retry."
                    else error.message ?: "Unable to verify the selected Go SDK."
                })
                if (alive() && generation.get() == ticket) { rebuildChoices(); render(); notifyState() }
            }
        }
    }

    private fun rebuildChoices() {
        val entries = registry.list()
        val choices = mutableListOf(Choice("", defaultSdk?.let { "Bundled / automatic — ${it.version} — ${it.root}" } ?: "Bundled / automatic"))
        entries.forEach { choices.add(Choice(it.executable, "${it.version} — ${it.root}")) }
        if (executable.isNotEmpty() && choices.none { it.executable == executable }) {
            choices.add(Choice(executable, verified?.let { "${it.version} — ${it.root}" } ?: "Configured SDK — $executable"))
        }
        updating = true
        try {
            combo.model = DefaultComboBoxModel(choices.toTypedArray())
            combo.selectedItem = choices.first { it.executable == executable }
            remove.isEnabled = entries.any { it.executable == executable }
        } finally { updating = false }
    }

    private fun render() {
        details.text = verified?.let { "${it.version} — GOROOT: ${it.root}" } ?: validationMessage
        details.toolTipText = details.text
        // SDK paths are commonly longer than the Settings viewport. Keep the
        // action row and the combo arrow reachable; the untruncated value stays
        // available through the tooltip and the selected model entry.
        capPreferredWidth(combo)
        capPreferredWidth(details)
    }
    private fun capPreferredWidth(component: JComponent) {
        val preferred = component.preferredSize
        component.minimumSize = Dimension(0, preferred.height)
        component.preferredSize = Dimension(minOf(preferred.width, MAX_SELECTOR_WIDTH), preferred.height)
    }
    private fun alive() = !disposed && project?.isDisposed != true
    private fun notifyState() { listeners.forEach { it() } }
    private fun onEdt(action: () -> Unit) {
        if (!alive()) return
        val application = ApplicationManager.getApplication()
        if (application.isDisposed) return
        if (SwingUtilities.isEventDispatchThread()) action()
        else application.invokeLater({ if (alive()) action() }, ModalityState.any())
    }

    private companion object { const val MAX_SELECTOR_WIDTH = 520 }
}
