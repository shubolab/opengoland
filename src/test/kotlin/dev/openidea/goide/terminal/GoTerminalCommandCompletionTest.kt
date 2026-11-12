package dev.openidea.goide.terminal

import com.intellij.terminal.completion.spec.ShellCommandResult
import com.intellij.testFramework.LightPlatformTestCase
import kotlinx.coroutines.runBlocking
import org.jetbrains.plugins.terminal.block.completion.ShellCommandSpecsManagerImpl
import org.jetbrains.plugins.terminal.block.completion.spec.ShellDataGeneratorProcessExecutor
import org.jetbrains.plugins.terminal.block.completion.spec.ShellDataGeneratorProcessOptions
import org.jetbrains.plugins.terminal.testFramework.completion.ShellCompletionTestFixture

class GoTerminalCommandCompletionTest : LightPlatformTestCase() {
    fun testInstalledTerminalManagerLoadsGoSpec() {
        val selected = ShellCommandSpecsManagerImpl.getInstance().getLightCommandSpec("go")
        assertNotNull(selected)
        assertEquals("go", selected!!.name)
        assertNotNull(selected.description)
    }

    fun testInstalledFullGoSpecLoadsSubcommands() = runBlocking {
        val manager = ShellCommandSpecsManagerImpl.getInstance()
        val light = manager.getLightCommandSpec("go")
        assertNotNull(light)
        val full = manager.getFullCommandSpec(light!!)
        assertNotNull(full)
        assertEquals("go", full!!.name)
        assertNotNull(full.subcommandsGenerator)
    }

    fun testInstalledFullGoSpecCompletesSubcommandsAndOptions() = runBlocking {
        val manager = ShellCommandSpecsManagerImpl.getInstance()
        val full = manager.getFullCommandSpec(manager.getLightCommandSpec("go")!!)!!
        val fixture = ShellCompletionTestFixture.builder(project)
            .setIsReworkedTerminal(true)
            .mockCommandSpecs(full)
            .mockProcessesExecutor(object : ShellDataGeneratorProcessExecutor {
                override suspend fun executeProcess(options: ShellDataGeneratorProcessOptions): ShellCommandResult =
                    ShellCommandResult.create("", exitCode = 1)
            })
            .mockShellCommandResults { ShellCommandResult.create("", exitCode = 1) }
            .build()

        val subcommands = fixture.getCompletions("go te").map { it.name }
        assertTrue("Expected the installed spec to suggest test, got $subcommands", "test" in subcommands)

        val options = fixture.getCompletions("go test -").map { it.name }
        assertTrue(
            "Expected installed go test options, got $options",
            listOf("-race", "-json", "-o").all(options::contains),
        )
    }
}
