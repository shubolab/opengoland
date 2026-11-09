package dev.openidea.goide.debug

import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.LightPlatformTestCase
import com.redhat.devtools.lsp4ij.dap.DAPDebugRunner
import com.redhat.devtools.lsp4ij.dap.configurations.DAPCommandLineState
import dev.openidea.goide.run.GoBuildConfigurationType
import dev.openidea.goide.run.GoRunConfiguration
import dev.openidea.goide.run.GoRunKind
import dev.openidea.goide.run.GoTestConfigurationType

/** A real configuration/state/native console boundary, without starting an adapter. */
class GoDebugTestConsoleTest : LightPlatformTestCase() {
    fun testDebugTestUsesTheNativeTestConsole() {
        val console = state(GoRunKind.TEST).consoleBuilder.console
        try {
            assertTrue("Debug Go Test must expose native test nodes, not only a DAP text console: ${console.javaClass.name}",
                console is SMTRunnerConsoleView)
        } finally { Disposer.dispose(console) }
    }

    fun testDebugApplicationRetainsItsOrdinaryDebugConsole() {
        val console = state(GoRunKind.BUILD).consoleBuilder.console
        try { assertFalse(console is SMTRunnerConsoleView) } finally { Disposer.dispose(console) }
    }

    fun testManualTestProfileUsesTheSameNativeConsoleAndOtherModesKeepTheirRoute() {
        val executor = DefaultDebugExecutor.getDebugExecutorInstance()
        for (mode in listOf("test", "debug", "exec")) {
            val config = GoDebugConfiguration(project, GoDebugConfigurationType().configurationFactories.single(), "Manual $mode")
            config.goOptions.apply { this.mode = mode; program = "."; workingDirectory = System.getProperty("java.io.tmpdir") }
            val environment = ExecutionEnvironmentBuilder(project, executor).runProfile(config).runner(DAPDebugRunner()).build()
            val state = config.getState(executor, environment) as DAPCommandLineState
            val console = state.consoleBuilder.console
            try {
                assertEquals(mode == "test", console is SMTRunnerConsoleView)
                assertEquals(mode == "test", GoDebugTestRunner().canRun(executor.id, config))
                assertEquals(mode != "test", DAPDebugRunner().canRun(executor.id, config))
            } finally { Disposer.dispose(console) }
        }
    }

    private fun state(kind: GoRunKind): DAPCommandLineState {
        val type = if (kind == GoRunKind.TEST) GoTestConfigurationType() else GoBuildConfigurationType()
        val config = GoRunConfiguration(project, type.configurationFactories.single(), "Debug console regression", kind)
        config.parameters.workingDirectory = System.getProperty("java.io.tmpdir")
        val executor = DefaultDebugExecutor.getDebugExecutorInstance()
        val environment = ExecutionEnvironmentBuilder(project, executor).runProfile(config).runner(DAPDebugRunner()).build()
        return config.getState(executor, environment) as DAPCommandLineState
    }
}
