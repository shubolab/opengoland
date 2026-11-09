package dev.openidea.goide.sdk

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.util.Disposer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import dev.openidea.goide.project.GoModuleSdk
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.nio.file.Path
import java.util.concurrent.CancellationException
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.JTextArea
import javax.swing.event.DocumentEvent

/** Only the verified, fully installed SDK is delivered to the registry owner. */
internal class GoSdkDownloadDialog(
    private val project: Project?,
    parentDisposable: Disposable,
    private val onInstalled: (GoModuleSdk) -> Unit,
    private val downloads: GoSdkDownloads = GoSdkDownloads(),
    private val host: GoSdkHost = GoSdkHost.current(),
) : DialogWrapper(project) {
    private val versions = ComboBox<GoSdkArchive>()
    private val location = JBTextField()
    private val browse = JButton("Browse…")
    private val reload = JButton("Reload")
    private val status = JTextArea(3, 58).apply { isEditable = false; isOpaque = false; lineWrap = true; wrapStyleWord = true }
    private val progress = JProgressBar(0, 1000)
    private val panel = JPanel(GridBagLayout())
    @Volatile private var operation = GoSdkDownloadCancellation()
    private var generation = 0L
    private var busy = false
    private var downloading = false
    @Volatile private var parentDisposed = false
    private var suggestedLocation = ""
    private var lastProgressNanos = 0L

    init {
        title = "Download Go SDK"
        setOKButtonText("Download")
        init()
        versions.addActionListener {
            val archive = versions.selectedItem as? GoSdkArchive
            if (archive != null && (location.text.isBlank() || location.text == suggestedLocation)) {
                suggestedLocation = defaultLocation(archive.version)
                location.text = suggestedLocation
            }
            updateEnabled()
        }
        location.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(event: DocumentEvent) = updateEnabled()
        })
        browse.addActionListener {
            FileChooser.chooseFile(FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Go SDK Parent Directory"), project, null) { folder ->
                val version = (versions.selectedItem as? GoSdkArchive)?.version ?: "go"
                location.text = Path.of(folder.path).resolve(version).toString()
            }
        }
        reload.addActionListener { loadCatalogue() }
        Disposer.register(disposable, Disposable { operation.cancel() })
        val parentLifetime = Disposable {
            parentDisposed = true
            operation.cancel()
            ApplicationManager.getApplication().invokeLater({ if (!isDisposed) close(CANCEL_EXIT_CODE) }, ModalityState.any())
        }
        if (!Disposer.tryRegister(parentDisposable, parentLifetime)) {
            parentDisposed = true; operation.cancel()
            ApplicationManager.getApplication().invokeLater({ if (!isDisposed) close(CANCEL_EXIT_CODE) }, ModalityState.any())
        }
        else {
            Disposer.register(disposable, Disposable { Disposer.dispose(parentLifetime) })
            loadCatalogue()
        }
    }

    override fun createCenterPanel(): JComponent {
        fun row(index: Int, label: String, value: JComponent) {
            panel.add(JBLabel(label), GridBagConstraints().apply { gridx = 0; gridy = index; anchor = GridBagConstraints.WEST; insets = Insets(4, 0, 4, 10) })
            panel.add(value, GridBagConstraints().apply { gridx = 1; gridy = index; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL; insets = Insets(4, 0, 4, 0) })
        }
        row(0, "Version:", JPanel(BorderLayout(8, 0)).apply { add(versions, BorderLayout.CENTER); add(reload, BorderLayout.EAST) })
        row(1, "Location:", JPanel(BorderLayout(8, 0)).apply { add(this@GoSdkDownloadDialog.location, BorderLayout.CENTER); add(browse, BorderLayout.EAST) })
        row(2, "Platform:", JBLabel("${host.os} / ${host.arch}"))
        panel.add(JBLabel("Choose a new GOROOT directory. Existing installations are never overwritten."), GridBagConstraints().apply {
            gridx = 0; gridy = 3; gridwidth = 2; anchor = GridBagConstraints.WEST; insets = Insets(5, 0, 8, 0)
        })
        panel.add(progress, GridBagConstraints().apply { gridx = 0; gridy = 4; gridwidth = 2; fill = GridBagConstraints.HORIZONTAL; weightx = 1.0 })
        panel.add(status, GridBagConstraints().apply { gridx = 0; gridy = 5; gridwidth = 2; fill = GridBagConstraints.HORIZONTAL; weightx = 1.0; insets = Insets(8, 0, 0, 0) })
        panel.preferredSize = Dimension(610, 210)
        return panel
    }

    override fun doValidate(): ValidationInfo? {
        if (busy) return ValidationInfo("Wait for the current operation to finish.")
        if (versions.selectedItem !is GoSdkArchive) return ValidationInfo("Choose an available Go version.", versions)
        return try {
            val target = Path.of(location.text.trim())
            if (!target.isAbsolute || target.normalize().parent == null) ValidationInfo("Enter an absolute, new SDK directory.", location) else null
        } catch (_: RuntimeException) { ValidationInfo("Enter a valid SDK directory.", location) }
    }

    override fun doOKAction() {
        if (doValidate() != null || parentDisposed || project?.isDisposed == true) return
        val archive = versions.selectedItem as GoSdkArchive
        val target = Path.of(location.text.trim())
        val ticket = begin("Starting Go SDK download…", true)
        val cancellation = operation
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val sdk = downloads.install(archive, target, cancellation) { value ->
                    val now = System.nanoTime()
                    if (value.total == null || now - lastProgressNanos > 100_000_000 || value.completed == value.total) {
                        lastProgressNanos = now
                        deliver(ticket) { showProgress(value) }
                    }
                }
                deliver(ticket) {
                    busy = false; downloading = false
                    onInstalled(sdk)
                    super.doOKAction()
                }
            } catch (error: Exception) { failed(ticket, cancellation, error) }
        }
    }

    override fun doCancelAction() { operation.cancel(); super.doCancelAction() }

    private fun loadCatalogue() {
        if (parentDisposed || isDisposed || project?.isDisposed == true) return
        val ticket = begin("Loading official Go versions…", false)
        val cancellation = operation
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val values = downloads.catalogue(host, cancellation)
                deliver(ticket) {
                    versions.removeAllItems(); values.forEach(versions::addItem)
                    busy = false
                    progress.isIndeterminate = false; progress.value = 0
                    status.text = if (values.isEmpty()) "No official SDK archives are available for this platform." else "Downloads are verified against the official Go archive size and SHA-256 before installation."
                    updateEnabled()
                }
            } catch (error: Exception) { failed(ticket, cancellation, error) }
        }
    }

    private fun begin(message: String, install: Boolean): Long {
        operation.cancel(); operation = GoSdkDownloadCancellation()
        busy = true; downloading = install
        progress.isIndeterminate = true; status.text = message
        updateEnabled()
        return ++generation
    }

    private fun showProgress(value: GoSdkDownloadProgress) {
        status.text = value.message
        progress.isIndeterminate = value.total == null
        if (value.total != null && value.total > 0) progress.value = (value.completed * 1000 / value.total).toInt().coerceIn(0, 1000)
    }

    private fun failed(ticket: Long, cancellation: GoSdkDownloadCancellation, error: Exception) {
        if (cancellation.isCancelled() || error is CancellationException) return
        deliver(ticket) {
            busy = false; downloading = false
            progress.isIndeterminate = false; progress.value = 0
            status.text = error.message ?: "Go SDK download failed. Check the connection and try again."
            updateEnabled()
        }
    }

    private fun deliver(ticket: Long, action: () -> Unit) {
        ApplicationManager.getApplication().invokeLater({
            if (!isDisposed && !parentDisposed && project?.isDisposed != true && generation == ticket && !operation.isCancelled()) action()
        }, ModalityState.any())
    }

    private fun updateEnabled() {
        versions.isEnabled = !busy; location.isEnabled = !busy; browse.isEnabled = !busy
        reload.isEnabled = !downloading && !busy
        isOKActionEnabled = !busy && versions.selectedItem is GoSdkArchive && location.text.isNotBlank() && !parentDisposed
    }

    private fun defaultLocation(version: String) = Path.of(System.getProperty("user.home"), ".open-goland", "sdks", version).toString()
}
