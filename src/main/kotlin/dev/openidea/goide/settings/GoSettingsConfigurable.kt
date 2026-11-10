package dev.openidea.goide.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.Project
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.JBCheckBox
import com.intellij.util.ui.FormBuilder
import com.redhat.devtools.lsp4ij.LanguageServerManager
import com.redhat.devtools.lsp4ij.LanguageServersRegistry
import dev.openidea.goide.sdk.GoSdkSelector
import java.nio.file.InvalidPathException
import java.nio.file.Path
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane

class GoSettingsConfigurable(private val project: Project) : SearchableConfigurable {
    private var sdkSelector: GoSdkSelector? = null
    private var uiLifetime: Disposable? = null
    private val gopls = JBTextField()
    private val delve = JBTextField()
    private val tags = JBTextField()
    private val goos = JBTextField()
    private val goarch = JBTextField()
    private val cgo = JBTextField()
    private val env = JBTextArea(5, 40)
    private val rawStringIndent = JBCheckBox("Indent on Enter in raw strings")
    override fun getId() = ID
    override fun getDisplayName() = "Go"
    override fun createComponent(): JComponent {
        disposeUIResources()
        val owner = Disposer.newDisposable("open Goland Go settings")
        uiLifetime = owner
        Disposer.register(project, owner)
        val sdk = GoSdkSelector(project, owner, GoProjectSettings.get(project).goExecutable)
        sdkSelector = sdk
        return FormBuilder.createFormBuilder()
        .addLabeledComponent("Go SDK:", sdk.component)
        .addLabeledComponent("gopls executable:", gopls)
        .addLabeledComponent("Delve executable:", delve)
        .addComponent(com.intellij.ui.components.JBLabel("Leave gopls and Delve fields empty to use bundled tools or automatic discovery."))
        .addComponent(com.intellij.ui.components.JBLabel("Configured paths are checked when tools start."))
        .addSeparator().addLabeledComponent("Build tags:", tags)
        .addLabeledComponent("Target OS (GOOS):", goos)
        .addLabeledComponent("Target architecture (GOARCH):", goarch)
        .addLabeledComponent("CGO_ENABLED (empty, 0 or 1):", cgo)
        .addLabeledComponent("Environment (NAME=value per line):", JScrollPane(env))
        .addSeparator().addComponent(rawStringIndent)
        .addComponentFillVertically(JPanel(), 0).panel
    }

    override fun reset() {
        val s = GoProjectSettings.get(project)
        sdkSelector?.configuredExecutable = s.goExecutable
        gopls.text = s.goplsExecutable; delve.text = s.delveExecutable
        tags.text = s.buildTags; goos.text = s.goos; goarch.text = s.goarch; cgo.text = s.cgoEnabled; env.text = s.environment
        rawStringIndent.isSelected = s.indentRawStringsOnEnter
    }
    override fun isModified(): Boolean {
        val s = GoProjectSettings.get(project)
        return listOf(selectedExecutable(), gopls.text, delve.text, tags.text, goos.text, goarch.text, cgo.text, env.text) !=
            listOf(s.goExecutable, s.goplsExecutable, s.delveExecutable, s.buildTags, s.goos, s.goarch, s.cgoEnabled, s.environment) ||
            rawStringIndent.isSelected != s.indentRawStringsOnEnter
    }
    override fun apply() {
        try { GoToolchain.parseEnvironment(env.text) } catch (e: IllegalArgumentException) { throw ConfigurationException(e.message ?: "Invalid environment") }
        if (cgo.text !in listOf("", "0", "1")) throw ConfigurationException("CGO_ENABLED must be empty, 0 or 1")
        listOf("go" to selectedExecutable(), "gopls" to gopls.text, "dlv" to delve.text).forEach { (tool, path) ->
            // Configurable.apply runs on the EDT. Existence, permissions and tool
            // discovery are checked by the background tool launch, not this form.
            if (path.isNotBlank()) try { Path.of(path.trim()) }
            catch (error: InvalidPathException) { throw ConfigurationException("Invalid $tool executable path: ${error.reason}") }
        }
        val s = GoProjectSettings.get(project)
        // An existing invalid configuration remains visible and does not prevent
        // editing unrelated preferences. A newly chosen SDK must first verify.
        sdkSelector?.let { selected ->
            if (selected.configuredExecutable != s.goExecutable && !selected.isReady)
                throw ConfigurationException(selected.validationMessage ?: "Verify the selected Go SDK before applying.")
        }
        val previousLanguageServerSettings = languageServerSettings(s)
        s.goExecutable = selectedExecutable(); s.goplsExecutable = gopls.text.trim(); s.delveExecutable = delve.text.trim()
        s.buildTags = tags.text.trim(); s.goos = goos.text.trim(); s.goarch = goarch.text.trim(); s.cgoEnabled = cgo.text.trim(); s.environment = env.text
        s.indentRawStringsOnEnter = rawStringIndent.isSelected
        if (previousLanguageServerSettings != languageServerSettings(s)) {
            // Memory-only invalidation; package metadata work is scheduled by the native entry service.
            dev.openidea.goide.run.GoRunBuildContextService.settingsChanged(project)
            ApplicationManager.getApplication().executeOnPooledThread {
                if (!project.isDisposed) {
                    val definition = LanguageServersRegistry.getInstance().getServerDefinition("openidea.gopls")
                    // Applying project settings must respect an explicitly disabled server.
                    if (definition != null && definition.isEnabled(project)) {
                        try {
                            LanguageServerManager.getInstance(project).start("openidea.gopls",
                                LanguageServerManager.StartOptions().setForceRestart(true).setForceStart(false).setWillEnable(false))
                        } catch (error: Exception) {
                            Logger.getInstance(GoSettingsConfigurable::class.java).warn("Unable to restart gopls after changing Go settings", error)
                        }
                    }
                }
            }
        }
    }

    private fun selectedExecutable() = sdkSelector?.configuredExecutable ?: GoProjectSettings.get(project).goExecutable

    override fun disposeUIResources() {
        uiLifetime?.let { Disposer.dispose(it) }
        uiLifetime = null
        sdkSelector = null
    }

    private fun languageServerSettings(s: GoProjectSettings.Data) =
        listOf(s.goExecutable, s.goplsExecutable, s.buildTags, s.goos, s.goarch, s.cgoEnabled, s.environment)

    companion object { const val ID = "dev.openidea.go.settings" }
}
