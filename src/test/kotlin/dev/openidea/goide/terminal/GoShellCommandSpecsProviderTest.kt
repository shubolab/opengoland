package dev.openidea.goide.terminal

import com.intellij.terminal.completion.spec.ShellCommandResult
import com.intellij.terminal.completion.spec.ShellCommandSpec
import com.intellij.testFramework.LightPlatformTestCase
import kotlinx.coroutines.runBlocking
import org.jetbrains.plugins.terminal.block.completion.spec.ShellDataGeneratorProcessExecutor
import org.jetbrains.plugins.terminal.block.completion.spec.ShellDataGeneratorProcessOptions
import org.jetbrains.plugins.terminal.testFramework.completion.ShellCompletionTestFixture
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

    fun testGoCommandFixtureReturnsSubcommandsAndOptions() = runBlocking {
        val info: ShellCommandSpecInfo = GoShellCommandSpecsProvider().getCommandSpecs().single()
        val fixture = ShellCompletionTestFixture.builder(project)
            .setIsReworkedTerminal(true)
            .mockCommandSpecs(info.spec)
            .mockProcessesExecutor(object : ShellDataGeneratorProcessExecutor {
                override suspend fun executeProcess(options: ShellDataGeneratorProcessOptions): ShellCommandResult =
                    ShellCommandResult.create("", exitCode = 1)
            })
            .mockShellCommandResults { ShellCommandResult.create("", exitCode = 1) }
            .build()

        val subcommands = fixture.getCompletions("go te").map { it.name }
        assertTrue("Expected the go test subcommand, got $subcommands", "test" in subcommands)

        val options = fixture.getCompletions("go test -").map { it.name }
        assertTrue("Expected common go test options, got $options", listOf("-run", "-bench", "-count", "-race", "-cover").all(options::contains))
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
