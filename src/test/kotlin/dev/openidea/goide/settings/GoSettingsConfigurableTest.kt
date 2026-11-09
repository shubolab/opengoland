package dev.openidea.goide.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ConfigurationException
import com.intellij.testFramework.EdtTestUtil
import com.intellij.testFramework.LightPlatformTestCase
import java.awt.Component
import java.awt.Container
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import javax.swing.JLabel
import javax.swing.JCheckBox
import javax.swing.JTextArea
import javax.swing.JTextField

class GoSettingsConfigurableTest : LightPlatformTestCase() {
    override fun runInDispatchThread() = false

    fun testApplyOnEdtAcceptsUninstalledPathsAndBackgroundLaunchReportsTheMissingTool() = withSettings {
        val root = Path.of(System.getProperty("java.io.tmpdir"), "open-goland-uninstalled-${UUID.randomUUID()}").toAbsolutePath()
        val missing = listOf("go", "gopls", "dlv").associateWith { root.resolve("$it tool").toString() }
        missing.values.forEach { assertFalse(Files.exists(Path.of(it))) }
        onEdt {
            val settings = GoProjectSettings.get(project)
            settings.goExecutable = missing.getValue("go")
            settings.goplsExecutable = missing.getValue("gopls")
            // Keep language-server settings unchanged so Apply need not start a server.
            val configurable = GoSettingsConfigurable(project)
            val panel = configurable.createComponent() as Container
            configurable.reset()
            field(panel, "Delve executable:").text = "  ${missing.getValue("dlv")}  "
            assertTrue(configurable.isModified())
            assertTrue(ApplicationManager.getApplication().isDispatchThread)
            configurable.apply()
            assertEquals(missing.getValue("go"), settings.goExecutable)
            assertEquals(missing.getValue("gopls"), settings.goplsExecutable)
            assertEquals(missing.getValue("dlv"), settings.delveExecutable)
            configurable.reset()
            assertFalse(configurable.isModified())
        }
        assertFalse(ApplicationManager.getApplication().isDispatchThread)
        for (tool in missing.keys) {
            val failure = runCatching { GoToolchain.command(project, tool, emptyList()) }.exceptionOrNull()
            assertTrue("The background launch must explain the missing $tool: $failure", failure is IllegalStateException)
            assertEquals("$tool was not found. Configure it in Settings > Go.", failure!!.message)
        }
    }

    fun testApplyRetainsPureValidationAndLeavesSettingsUnchangedOnErrors() = withSettings {
        onEdt {
            val configurable = GoSettingsConfigurable(project)
            val panel = configurable.createComponent() as Container
            configurable.reset()
            val before = GoToolchain.capture(project)
            fun rejects(message: String, change: () -> Unit) {
                configurable.reset()
                change()
                val failure = runCatching { configurable.apply() }.exceptionOrNull()
                assertTrue("Expected a settings error, got $failure", failure is ConfigurationException)
                assertTrue(failure!!.message.orEmpty(), failure.message.orEmpty().contains(message))
                assertEquals(before, GoToolchain.capture(project))
            }
            rejects("Invalid go executable path") { field(panel, "Go executable:").text = "bad\u0000path" }
            rejects("CGO_ENABLED") { field(panel, "CGO_ENABLED (empty, 0 or 1):").text = "2" }
            rejects("NAME=value") { components(panel).filterIsInstance<JTextArea>().single().text = "MISSING_EQUALS" }
            configurable.reset()
            configurable.apply()
            val settings = GoProjectSettings.get(project)
            assertEquals("", settings.goExecutable)
            assertEquals("", settings.goplsExecutable)
            assertEquals("", settings.delveExecutable)
        }
    }

    fun testRawStringIndentResetAndApplyKeepEditorPreferencesSeparateFromToolchainSettings() = withSettings {
        onEdt {
            val settings = GoProjectSettings.get(project)
            val toolchain = GoToolchain.capture(project)
            val configurable = GoSettingsConfigurable(project)
            val panel = configurable.createComponent() as Container
            configurable.reset()
            val option = components(panel).filterIsInstance<JCheckBox>().single { it.text == "Indent on Enter in raw strings" }
            assertFalse(option.isSelected)
            option.isSelected = true
            assertTrue(configurable.isModified())
            // Reset models canceling an unapplied edit.
            configurable.reset()
            assertFalse(option.isSelected)
            assertFalse(settings.indentRawStringsOnEnter)
            assertFalse(configurable.isModified())
            option.isSelected = true
            configurable.apply()
            assertTrue(settings.indentRawStringsOnEnter)
            assertEquals(toolchain, GoToolchain.capture(project))
            assertFalse(configurable.isModified())
            configurable.reset()
            assertTrue(option.isSelected)
            option.isSelected = false
            configurable.apply()
            assertFalse(settings.indentRawStringsOnEnter)
            assertEquals(toolchain, GoToolchain.capture(project))
        }
    }

    private fun withSettings(action: () -> Unit) {
        val original = GoToolchain.capture(project)
        val originalRawIndent = GoProjectSettings.get(project).indentRawStringsOnEnter
        try {
            onEdt {
                restore(GoToolchain.Settings("", "", "", "", "", "", "", ""))
                GoProjectSettings.get(project).indentRawStringsOnEnter = false
            }
            action()
        } finally { onEdt { restore(original); GoProjectSettings.get(project).indentRawStringsOnEnter = originalRawIndent } }
    }

    private fun restore(value: GoToolchain.Settings) = GoProjectSettings.get(project).apply {
        goExecutable = value.goExecutable; goplsExecutable = value.goplsExecutable; delveExecutable = value.delveExecutable
        buildTags = value.buildTags; goos = value.goos; goarch = value.goarch; cgoEnabled = value.cgoEnabled; environment = value.environment
    }
    private fun field(panel: Container, label: String) = components(panel).filterIsInstance<JLabel>().single { it.text == label }.labelFor as JTextField
    private fun components(container: Container): List<Component> = container.components.flatMap { listOf(it) + if (it is Container) components(it) else emptyList() }
    private fun onEdt(action: () -> Unit) = EdtTestUtil.runInEdtAndWait<RuntimeException> { action() }
}
