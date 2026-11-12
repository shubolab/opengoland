package dev.openidea.goide.terminal

import com.intellij.terminal.completion.spec.ShellCommandSpec
import com.intellij.testFramework.LightPlatformTestCase
import org.jetbrains.plugins.terminal.block.completion.ShellCommandSpecsManagerImpl
import org.jetbrains.plugins.terminal.block.completion.spec.ShellCommandSpecInfo

class GoShellCommandSpecsProviderTest : LightPlatformTestCase() {
    fun testGoSpecIsVisibleToTheInstalledTerminalManager() {
        val selected = ShellCommandSpecsManagerImpl.getInstance().getLightCommandSpec("go")
        assertNotNull(selected)
        val go: ShellCommandSpec = selected!!
        assertEquals("go", go.name)
        assertTrue(go.description?.contains("Go tool") == true)
    }

    fun testGoSpecExposesSubcommandsAndCommonBuildNavigation() {
        val info: ShellCommandSpecInfo = GoShellCommandSpecsProvider().getCommandSpecs().single()
        val go: ShellCommandSpec = info.spec
        assertEquals("go", go.name)
        assertTrue(go.requiresSubcommand)
        assertTrue(go.options.map { it.name }.containsAll(listOf("-C", "-v", "-x", "-mod", "-tags")))
        assertTrue(go.description?.contains("Go tool") == true)
    }
}
