package dev.openidea.goide.debug

import com.google.gson.JsonObject
import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.testframework.actions.AbstractRerunFailedTestsAction
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.RunContentManager
import com.intellij.execution.ui.RunContentManagerImpl
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.ExecutionDataKeys
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.extensions.LoadingOrder
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.LightPlatformTestCase
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.util.execution.ParametersListUtil
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugProcessStarter
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerManager
import dev.openidea.goide.lang.GoFileType
import dev.openidea.goide.run.GoRunConfiguration
import dev.openidea.goide.run.GoTestConfigurationType
import java.awt.BorderLayout
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.JPanel

/** Registered platform action + actual selected native content, not a replacement alias action. */
class GoGlobalFailedTestsActionTest : LightPlatformTestCase() {
    private var selectedEnvironment: ExecutionEnvironment? = null
    override fun getData(dataId: String): Any? =
        if (ExecutionDataKeys.EXECUTION_ENVIRONMENT.`is`(dataId)) selectedEnvironment else super.getData(dataId)
    fun testStandardGlobalActionRecognizesTheSelectedFinishedGoDebugTestConsole() = Fixture().use { f ->
        f.finish(mapOf("example.test/a" to listOf("TestParent/bad")))
        f.select()
        assertTrue("The platform's registered RerunFailedTests must find the selected native Go console delegate", f.enabled())
        val action = f.result.restartActions.single() as AbstractRerunFailedTestsAction
        assertSame(f.console.resultsViewer, action.model)
        assertSame("The native action and execution must own one console", f.console, f.state.consoleBuilder.console)
        assertTrue(ActionUtil.getActions(f.console.component).contains(action))
        assertEquals(global().shortcutSet.shortcuts.toList(), action.shortcutSet.shortcuts.toList())
    }

    fun testGlobalInvocationDispatchesEveryExactPathAndPackageThroughTheRealGoRunner() = Fixture().use { f ->
        val capture = capture()
        f.finish(linkedMapOf("example.test/a" to listOf("TestParent/left/one", "TestParent/right/two"), "example.test/b" to listOf("")))
        f.select()
        (f.configuration as GoRunConfiguration).parameters.apply { target = "changed/live"; environment["FROZEN"] = "B" }
        val delegate = f.result.restartActions.single() as AbstractRerunFailedTestsAction
        assertNotNull(delegate.getRunProfileTestAccessor(f.environment))
        assertTrue("Preparing the native run profile must not launch a process", capture.values.isEmpty())
        assertSame(f.environment, ExecutionDataKeys.EXECUTION_ENVIRONMENT.getData(DataManager.getInstance().getDataContext(f.descriptor.component)))
        global().actionPerformed(f.event())
        awaitCaptured(capture, 3)
        assertEquals(setOf("example.test/a" to "^TestParent$/^left$/^one$", "example.test/a" to "^TestParent$/^right$/^two$", "example.test/b" to ""),
            capture.values.map { (it.profile as GoRunConfiguration).parameters.let { p -> p.target to p.testPattern } }.toSet())
        for (value in capture.values) {
            assertEquals("Debug", value.executor)
            val profile = value.profile as GoRunConfiguration
            assertSame(f.configuration.factory, profile.factory)
            assertEquals("A", profile.parameters.environment["FROZEN"])
            assertEquals("-count=1 -tags=integration", profile.parameters.buildFlags)
            assertEquals("-timeout=30s", profile.parameters.arguments)
        }
    }

    fun testManualDebugGlobalRetryPreservesItsConcreteFactoryAndQuotedNestedSelector() = Fixture(manual = true).use { f ->
        val capture = capture()
        f.finish(mapOf("example.test/manual" to listOf("TestParent/bad.*😀")))
        f.select()
        (f.configuration as GoDebugConfiguration).goOptions.apply { program = "changed/live"; environmentVariables = "FROZEN=B" }
        assertSame(f.environment, ExecutionDataKeys.EXECUTION_ENVIRONMENT.getData(DataManager.getInstance().getDataContext(f.descriptor.component)))
        global().actionPerformed(f.event())
        awaitCaptured(capture, 1)
        val observed = capture.values.single()
        assertEquals("Debug", observed.executor)
        val profile = observed.profile as GoDebugConfiguration
        assertSame(f.configuration.factory, profile.factory)
        assertEquals("test", profile.goOptions.mode)
        assertEquals("example.test/manual", profile.goOptions.program)
        assertEquals("FROZEN=A", profile.goOptions.environmentVariables)
        assertEquals(listOf("-run", "^TestParent$/^bad\\.\\*😀$", "-timeout=30s"), ParametersListUtil.parse(profile.goOptions.arguments, false, true))
    }

    fun testRunningPassedAndIgnoredOnlyConsolesDoNotEnableTheGlobalAction() {
        Fixture().use { f ->
            f.failed("example.test/a", "TestBad")
            f.select()
            assertFalse("A partial failure in an active debug target is not yet rerunnable", f.enabled())
        }
        Fixture().use { f -> f.finish(emptyMap()); f.select(); assertFalse(f.enabled()) }
        Fixture().use { f ->
            f.message("run", "example.test/a", "TestCancelled")
            f.message("skip", "example.test/a", "TestCancelled")
            f.state.testSession.requestCancellation(); f.state.testSession.end()
            f.select(); assertTrue(f.state.testSession.output.failures().isEmpty()); assertFalse(f.enabled())
        }
    }

    fun testGlobalActionFollowsTheSelectedDescriptorInsteadOfAnotherGoFailureTab() = Fixture().use { f ->
        f.finish(mapOf("example.test/a" to listOf("TestBad")))
        f.select(); assertTrue(f.enabled())
        val unrelated = RunContentDescriptor(null, null, JPanel(), "Unrelated selected execution")
        val content = com.intellij.ui.content.ContentFactory.getInstance().createContent(unrelated.component, unrelated.displayName, false)
        content.putUserData(RunContentDescriptor.DESCRIPTOR_KEY, unrelated)
        try {
            f.contentManager.addContent(content); f.contentManager.setSelectedContent(content)
            assertSame(unrelated, RunContentManager.getInstance(project).selectedContent)
            assertFalse(f.enabled())
            f.select(); assertTrue(f.enabled())
        } finally { f.contentManager.removeContent(content, true); Disposer.dispose(unrelated) }
    }

    fun testDisposedConsoleDisablesItsDelegateAndRemovesTheNativeShortcutBinding() = Fixture().use { f ->
        val capture = capture()
        f.finish(mapOf("example.test/a" to listOf("TestBad")))
        f.select(); assertTrue(f.enabled())
        val action = f.result.restartActions.single()
        val component = f.console.component
        Disposer.dispose(f.console)
        assertFalse(f.enabled())
        assertFalse(ActionUtil.getActions(component).contains(action))
        global().actionPerformed(f.event())
        assertTrue(capture.values.isEmpty())
    }

    fun testTheActualNativeDebuggerTabRetainsTheStandardFailedTestDelegateAfterStop() = Fixture().use { f ->
        val started = XDebuggerManager.getInstance(project).newSessionBuilder(object : XDebugProcessStarter() {
            override fun start(session: XDebugSession): XDebugProcess {
                // Same production binding before the SDK snapshots restart actions.
                f.state.installSessionActions(session, f.result)
                return object : XDebugProcess(session) {
                    override fun getEditorsProvider() = GoDebuggerEditorsProvider(GoFileType.INSTANCE, null)
                    override fun createConsole() = f.console
                    override fun doGetProcessHandler() = f.state.testSession.events
                    override fun stop() = f.state.testSession.stop()
                }
            }
        }).environment(f.environment).showTab(false).startSession()
        var nativeDescriptor: RunContentDescriptor? = null
        try {
            assertSame(f.result.restartActions.single(), (started.session as com.intellij.xdebugger.impl.XDebugSessionImpl).restartActions.single())
            // Split-debugger unit sessions expose a backend mock descriptor.
            // Construct the actual SDK tab model through its public session
            // adapter, as the frontend does, instead of asserting on that mock.
            val proxy = com.intellij.platform.debugger.impl.ui.XDebuggerEntityConverter.asProxy(started.session)
            val tab = com.intellij.xdebugger.impl.ui.XDebugSessionTab.create(proxy, null,
                com.intellij.execution.runners.BackendExecutionEnvironmentProxy(f.environment), null, true, false, null)
            nativeDescriptor = requireNotNull(tab.runContentDescriptor)
            assertSame(f.result.restartActions.single(), nativeDescriptor.restartActions.single())
            // Unit mode skips toolbar attachment. Invoke the SDK's actual New
            // UI builder, then inspect its actual More group, including a full
            // rebuild, rather than manually copying actions into a test group.
            val newUi = tab as com.intellij.xdebugger.impl.ui.XDebugSessionTabNewUI
            newUi.javaClass.getDeclaredMethod("initToolbars", com.intellij.platform.debugger.impl.shared.proxy.XDebugSessionProxy::class.java)
                .apply { isAccessible = true }.invoke(newUi, proxy)
            val toolbar = newUi.javaClass.getDeclaredField("toolbarGroup").apply { isAccessible = true }.get(newUi) as com.intellij.openapi.actionSystem.ActionGroup
            val more = toolbar.getChildren(null).single { it is com.intellij.openapi.actionSystem.impl.MoreActionGroup } as com.intellij.openapi.actionSystem.ActionGroup
            assertEquals(1, more.getChildren(null).count { it === f.result.restartActions.single() })
            assertFalse(toolbar.getChildren(null).any { it === f.result.restartActions.single() })
            newUi.updateToolbars()
            val rebuiltMore = toolbar.getChildren(null).single { it is com.intellij.openapi.actionSystem.impl.MoreActionGroup } as com.intellij.openapi.actionSystem.ActionGroup
            assertEquals(1, rebuiltMore.getChildren(null).count { it === f.result.restartActions.single() })
            f.finish(mapOf("example.test/a" to listOf("TestBad")))
            assertSame(f.result.restartActions.single(), nativeDescriptor.restartActions.single())
        } finally {
            started.session.stop()
            nativeDescriptor?.let { if (!Disposer.isDisposed(it)) Disposer.dispose(it) }
            started.runContentDescriptor?.let { if (!Disposer.isDisposed(it)) Disposer.dispose(it) }
        }
    }

    private inner class Fixture(manual: Boolean = false) : AutoCloseable {
        val executor = DefaultDebugExecutor.getDebugExecutorInstance()
        val configuration: RunConfiguration = if (manual) {
            GoDebugConfiguration(project, GoDebugConfigurationType().configurationFactories.single(), "Original manual").apply {
                goOptions.apply {
                    mode = "test"; program = "."; workingDirectory = project.basePath.orEmpty()
                    buildFlags = "-count=1 -tags=integration"; arguments = "-run ^Old$ -timeout=30s"; environmentVariables = "FROZEN=A"
                }
            }
        } else (GoTestConfigurationType().configurationFactories.single().createTemplateConfiguration(project) as GoRunConfiguration).apply {
            parameters.apply { buildFlags = "-count=1 -tags=integration"; arguments = "-timeout=30s"; environment["FROZEN"] = "A" }
        }
        val runner = GoDebugTestRunner()
        val environment = ExecutionEnvironmentBuilder(project, executor).runProfile(configuration).runner(runner).build()
        val state = configuration.getState(executor, environment) as GoDebugTestState
        val result: DefaultExecutionResult
        val console: SMTRunnerConsoleView
        val descriptor: RunContentDescriptor
        val contentManager = (RunContentManager.getInstance(project) as RunContentManagerImpl).registerToolWindow(executor)
        val content: com.intellij.ui.content.Content
        init {
            state.javaClass.getDeclaredField("preparedAdapter").apply { isAccessible = true }.set(state, object : com.intellij.execution.process.NopProcessHandler() {})
            result = state.execute(executor, runner) as DefaultExecutionResult
            console = result.executionConsole as SMTRunnerConsoleView
            val panel = JPanel(BorderLayout()).apply { add(console.component, BorderLayout.CENTER) }
            DataManager.registerDataProvider(panel) { key ->
                when {
                    ExecutionDataKeys.EXECUTION_ENVIRONMENT.`is`(key) -> environment
                    CommonDataKeys.PROJECT.`is`(key) -> project
                    else -> null
                }
            }
            descriptor = RunContentDescriptor(console, result.processHandler, panel, "Selected Go failures", null, null, result.restartActions)
            // The SDK intentionally skips showRunContent in unit-test mode; use
            // its actual public content model and selected-descriptor service.
            content = com.intellij.ui.content.ContentFactory.getInstance().createContent(panel, descriptor.displayName, false)
            content.putUserData(RunContentDescriptor.DESCRIPTOR_KEY, descriptor)
            contentManager.addContent(content)
        }
        fun select() {
            // HeadlessDataManager uses LightPlatform's DataProvider rather than
            // walking live component providers. Supply only this host context;
            // actual action lookup/selection/execution remain the platform's.
            selectedEnvironment = environment
            contentManager.setSelectedContent(content)
            assertSame(descriptor, RunContentManager.getInstance(project).selectedContent)
        }
        fun event() = AnActionEvent.createFromAnAction(global(), null, ActionPlaces.UNKNOWN, SimpleDataContext.getProjectContext(project))
        fun enabled(): Boolean { val event = event(); global().update(event); return event.presentation.isEnabled }
        fun message(action: String, pkg: String, name: String? = null) {
            val line = JsonObject().apply { addProperty("Action", action); addProperty("Package", pkg); name?.let { addProperty("Test", it) } }
            state.testSession.output.consume("$line\n", state.testSession.events::publish)
        }
        fun failed(pkg: String, name: String) { message("run", pkg, name); message("fail", pkg, name) }
        fun finish(failures: Map<String, List<String>>) {
            failures.forEach { (pkg, names) -> names.forEach { name -> if (name.isEmpty()) message("fail", pkg) else failed(pkg, name) } }
            state.testSession.exitedEvent(if (failures.isEmpty()) 0 else 1); state.testSession.end()
            assertTrue(state.testSession.events.isProcessTerminated)
        }
        override fun close() {
            if (selectedEnvironment === environment) selectedEnvironment = null
            contentManager.removeContent(content, true)
            Disposer.dispose(state.testSession)
            if (!Disposer.isDisposed(console)) Disposer.dispose(console)
            if (!Disposer.isDisposed(descriptor)) Disposer.dispose(descriptor)
        }
    }

    private data class Captured(val executor: String, val profile: RunConfiguration)
    private class CapturingRunner : ProgramRunner<RunnerSettings> {
        val values = CopyOnWriteArrayList<Captured>()
        override fun getRunnerId() = "OwnedGlobalFailedTestsCapture"
        override fun canRun(executorId: String, profile: RunProfile) = profile is GoRunConfiguration || profile is GoDebugConfiguration
        override fun execute(environment: ExecutionEnvironment) { values += Captured(environment.executor.id, (environment.runProfile as RunConfiguration).clone()) }
    }
    private fun capture() = CapturingRunner().also { ProgramRunner.PROGRAM_RUNNER_EP.point.registerExtension(it, LoadingOrder.FIRST, testRootDisposable) }
    private fun awaitCaptured(capture: CapturingRunner, count: Int) = PlatformTestUtil.waitWithEventsDispatching("All exact retries must reach normal runner lookup", { capture.values.size == count }, 10)
    private fun global() = requireNotNull(ActionManager.getInstance().getAction("RerunFailedTests"))
}
