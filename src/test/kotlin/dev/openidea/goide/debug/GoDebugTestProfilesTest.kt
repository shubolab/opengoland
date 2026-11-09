package dev.openidea.goide.debug

import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.testFramework.LightPlatformTestCase
import com.intellij.util.execution.ParametersListUtil
import dev.openidea.goide.run.GoTestRerunPlan

class GoDebugTestProfilesTest : LightPlatformTestCase() {
    fun testManualRetryKeepsConcreteProfileAndAllSettingsAndExactSubtest() {
        val factory = GoDebugConfigurationType().configurationFactories.single()
        val original = GoDebugConfiguration(project, factory, "Original manual test")
        original.goOptions.apply {
            mode = "test"; program = "/tmp/包 空格 😀"; workingDirectory = "/tmp/work dir"
            buildFlags = "-tags=integration -race"
            arguments = "-run ^TestOld$ -args -custom '中文 😀'"
            environmentVariables = "FIRST= A\nSECOND=中文😀"
        }
        val captured = original.clone()
        val values = GoDebugTestProfiles.parameters(captured)
        assertEquals(listOf("/tmp/包 空格 😀"), ParametersListUtil.parse(values.target, false, true))
        original.goOptions.apply { program = "changed"; arguments = "changed"; environmentVariables = "CHANGED=B" }
        val retry = GoTestRerunPlan.create(mapOf("example.test/debug" to listOf("TestParent/bad.*😀")), values).single()
        val cloned = captured.clone().apply { name = retry.name }
        GoDebugTestProfiles.applyRetry(cloned, retry.parameters)
        val settings = RunManager.getInstance(project).createConfiguration(cloned, factory)
        assertTrue(settings.configuration is GoDebugConfiguration)
        val actual = settings.configuration as GoDebugConfiguration
        assertSame(factory, actual.factory)
        assertEquals("test", actual.goOptions.mode)
        assertEquals("example.test/debug", actual.goOptions.program)
        assertEquals("/tmp/work dir", actual.goOptions.workingDirectory)
        assertEquals("-tags=integration -race", actual.goOptions.buildFlags)
        assertEquals(mapOf("FIRST" to " A", "SECOND" to "中文😀"), GoDebugTestProfiles.parameters(actual).environment)
        assertEquals(listOf("-run", "^TestParent$/^bad\\.\\*😀$", "-args", "-custom", "中文 😀"),
            ParametersListUtil.parse(actual.goOptions.arguments, false, true))
        assertEquals("changed", original.goOptions.arguments)
    }

    fun testDebugRetryActionRetainsTheDebugExecutorAndCapturedProfile() {
        val config = GoDebugConfiguration(project, GoDebugConfigurationType().configurationFactories.single(), "Manual test")
        config.goOptions.apply { mode = "test"; program = "."; workingDirectory = System.getProperty("java.io.tmpdir") }
        val executor = DefaultDebugExecutor.getDebugExecutorInstance()
        val env = ExecutionEnvironmentBuilder(project, executor).runProfile(config).runner(GoDebugTestRunner()).build()
        val state = config.getState(executor, env) as GoDebugTestState
        try {
            val action = state.rerunFailedAction
            val capturedExecutor = action.javaClass.getDeclaredField("executor").apply { isAccessible = true }.get(action)
            val capturedProfile = action.javaClass.getDeclaredField("originalProfile").apply { isAccessible = true }.get(action)
            assertSame(executor, capturedExecutor)
            assertTrue(capturedProfile is GoDebugConfiguration)
            config.goOptions.program = "changed"
            assertEquals(".", (capturedProfile as GoDebugConfiguration).goOptions.program)
        } finally { com.intellij.openapi.util.Disposer.dispose(state.testSession) }
    }
}
