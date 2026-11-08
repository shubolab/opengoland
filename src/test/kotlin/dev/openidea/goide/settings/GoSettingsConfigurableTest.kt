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

    private fun withSettings(action: () -> Unit) {
        val original = GoToolchain.capture(project)
        try {
            onEdt { restore(GoToolchain.Settings("", "", "", "", "", "", "", "")) }
            action()
        } finally { onEdt { restore(original) } }
    }

    private fun restore(value: GoToolchain.Settings) = GoProjectSettings.get(project).apply {
        goExecutable = value.goExecutable; goplsExecutable = value.goplsExecutable; delveExecutable = value.delveExecutable
        buildTags = value.buildTags; goos = value.goos; goarch = value.goarch; cgoEnabled = value.cgoEnabled; environment = value.environment
    }
    private fun field(panel: Container, label: String) = components(panel).filterIsInstance<JLabel>().single { it.text == label }.labelFor as JTextField
    private fun components(container: Container): List<Component> = container.components.flatMap { listOf(it) + if (it is Container) components(it) else emptyList() }
    private fun onEdt(action: () -> Unit) = EdtTestUtil.runInEdtAndWait<RuntimeException> { action() }
}
