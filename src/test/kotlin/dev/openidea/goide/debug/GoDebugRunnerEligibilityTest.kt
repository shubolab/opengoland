package dev.openidea.goide.debug

import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.openapi.project.Project
import com.redhat.devtools.lsp4ij.dap.DAPDebugRunner
import dev.openidea.goide.run.GoBuildConfigurationType
import dev.openidea.goide.run.GoProgramRunner
import dev.openidea.goide.run.GoRunConfiguration
import dev.openidea.goide.run.GoRunKind
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GoDebugRunnerEligibilityTest {
    @Test fun genericDAPRunnerOnlyClaimsDebugForNativeGoConfigurations() {
        val project = Proxy.newProxyInstance(Project::class.java.classLoader, arrayOf(Project::class.java)) { proxy, method, args ->
            when (method.name) {
                "getBasePath" -> System.getProperty("java.io.tmpdir")
                "toString" -> "GoDebugRunnerTestProject"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> null
            }
        } as Project
        val factory = GoBuildConfigurationType().configurationFactories.single()
        val dap = DAPDebugRunner()
        for (kind in GoRunKind.entries) {
            val configuration = GoRunConfiguration(project, factory, "Go ${kind.name}", kind)
            assertTrue(dap.canRun(DefaultDebugExecutor.EXECUTOR_ID, configuration))
            assertFalse(dap.canRun(DefaultRunExecutor.EXECUTOR_ID, configuration))
            assertFalse(dap.canRun("Coverage", configuration))
            assertTrue(GoProgramRunner().canRun(DefaultRunExecutor.EXECUTOR_ID, configuration))
        }
    }
}
