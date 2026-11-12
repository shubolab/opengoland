package dev.openidea.goide.terminal

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.registry.Registry
import com.intellij.testFramework.EdtTestUtil
import com.intellij.testFramework.LightPlatformTestCase
import com.intellij.util.xmlb.XmlSerializerUtil
import dev.openidea.goide.settings.GoProjectSettings
import java.io.File
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class GoTerminalCustomizerTest : LightPlatformTestCase() {
    override fun runInDispatchThread() = false

    fun testProjectGoSdkPrecedesShellPathAndAppliesProjectEnvironment() {
        val settings = GoProjectSettings.get(project)
        val previous = GoProjectSettings.Data().also { XmlSerializerUtil.copyBean(settings, it) }
        val go = Path.of(requireNotNull(
            System.getProperty("opengoland.testGo")?.takeIf(String::isNotBlank)
                ?: System.getenv("OPENGOLAND_GO_TEST")?.takeIf(String::isNotBlank)
        ) { "A real selected Go SDK is required" }).toRealPath()
        val shellBin = Path.of(project.basePath!!).resolve("shell-bin").toString()
        try {
            EdtTestUtil.runInEdtAndWait<RuntimeException> {
                settings.goExecutable = go.toString()
                settings.goplsExecutable = ""
                settings.delveExecutable = ""
                settings.buildTags = "terminal_native terminal_extra"
                settings.goos = "darwin"
                settings.goarch = "arm64"
                settings.cgoEnabled = "0"
                settings.environment = "GOPROXY=off\nCUSTOM_TERMINAL=中文😀"
            }
            val result = CompletableFuture<Pair<Array<String>, MutableMap<String, String>>>()
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    val command = arrayOf("zsh", "-l")
                    val environment = mutableMapOf("PATH" to shellBin, "KEEP_SHELL" to "yes")
                    val returned = GoTerminalCustomizer().customizeCommandAndEnvironment(project, "zsh", command, environment)
                    result.complete(returned to environment)
                } catch (failure: Throwable) { result.completeExceptionally(failure) }
            }
            val (command, environment) = result.get(10, TimeUnit.SECONDS)
            assertEquals(listOf("zsh", "-l"), command.toList())
            val path = environment.getValue("PATH").split(File.pathSeparatorChar)
            assertEquals("The project-selected Go bin must win over the shell PATH", go.parent.toString(), path.first())
            assertTrue("The original shell PATH must remain available", shellBin in path)
            assertEquals(go.parent.parent.toString(), environment["GOROOT"])
            assertEquals(go.parent.parent.toString(), environment["_INTELLIJ_FORCE_SET_GOROOT"])
            assertEquals("off", environment["GOPROXY"])
            assertEquals("off", environment["_INTELLIJ_FORCE_SET_GOPROXY"])
            assertEquals("中文😀", environment["CUSTOM_TERMINAL"])
            assertEquals("中文😀", environment["_INTELLIJ_FORCE_SET_CUSTOM_TERMINAL"])
            assertEquals("yes", environment["KEEP_SHELL"])
            assertTrue(environment.getValue("GOFLAGS").contains("-tags=terminal_native,terminal_extra"))
            assertTrue(environment.getValue("_INTELLIJ_FORCE_SET_GOFLAGS").contains("-tags=terminal_native,terminal_extra"))
            assertEquals(go.parent.toString(), environment.getValue("_INTELLIJ_FORCE_PREPEND_PATH").split(File.pathSeparatorChar).first())
            assertEquals("darwin", environment["GOOS"])
            assertEquals("arm64", environment["GOARCH"])
            assertEquals("0", environment["CGO_ENABLED"])
            assertTrue(Registry.get(GoTerminalCustomizer.TERMINAL_COMPLETION_POPUP_REGISTRY_KEY).asBoolean())
        } finally {
            EdtTestUtil.runInEdtAndWait<RuntimeException> { XmlSerializerUtil.copyBean(previous, settings) }
        }
    }
}
