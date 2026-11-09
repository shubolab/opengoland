package dev.openidea.goide.run

import com.intellij.execution.BeforeRunTask
import com.intellij.execution.BeforeRunTaskProvider
import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.impl.ExecutionManagerImpl
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.RunContentManagerImpl
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.ExecutionDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.testFramework.EdtTestUtil
import com.intellij.testFramework.LightPlatformTestCase
import com.intellij.util.xmlb.XmlSerializerUtil
import dev.openidea.goide.settings.GoProjectSettings
import dev.openidea.goide.settings.GoToolchain
import java.awt.BorderLayout
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JPanel

/** The platform global action launches actual separate Go test processes, including Before Launch tasks. */
class GoGlobalFailedTestsRunTest : LightPlatformTestCase() {
    override fun runInDispatchThread() = false
    @Volatile private var selectedEnvironment: ExecutionEnvironment? = null
    override fun getData(dataId: String): Any? =
        if (ExecutionDataKeys.EXECUTION_ENVIRONMENT.`is`(dataId)) selectedEnvironment else super.getData(dataId)

    fun testGlobalRunRetriesEveryFailedLeafAndRunsOriginalBeforeLaunchExactlyOncePerChild() {
        val root = Files.createTempDirectory("open-goland-global-rerun-空 格😀-").toRealPath()
        val previous = GoProjectSettings.Data().also { XmlSerializerUtil.copyBean(GoProjectSettings.get(project), it) }
        val manager = ExecutionManager.getInstance(project) as ExecutionManagerImpl
        val forceBefore = manager.forceCompilationInTests
        val registrations = Disposer.newDisposable("owned global rerun execution subscription")
        val children = CopyOnWriteArrayList<Pair<ExecutionEnvironment, GoTestProcessHandler>>()
        val bothStarted = CompletableFuture<Unit>()
        val beforeLaunch = AtomicInteger()
        val taskProfiles = CopyOnWriteArrayList<RunConfiguration>()
        var original: GoTestProcessHandler? = null
        var originalDescriptor: RunContentDescriptor? = null
        var originalConsole: com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView? = null
        var nativeContent: com.intellij.ui.content.Content? = null
        var contentManager: com.intellij.ui.content.ContentManager? = null
        try {
            Files.writeString(root.resolve("go.mod"), "module example.test/globalretry\n\ngo 1.24.0\n")
            Files.writeString(root.resolve("matrix_test.go"), """
                package globalretry
                import "testing"
                func TestMatrix(t *testing.T) {
                    for _, outer := range []string{"left", "right"} {
                        t.Run(outer, func(t *testing.T) {
                            for _, inner := range []string{"one", "two"} {
                                t.Run(inner, func(t *testing.T) {
                                    if outer == "left" && inner == "one" || outer == "right" && inner == "two" { t.Fatal("intended fixture failure") }
                                })
                            }
                        })
                    }
                }
            """.trimIndent()+"\n")
            GoProjectSettings.get(project).apply {
                goExecutable = requireNotNull(GoToolchain.resolve("go", System.getenv("OPENGOLAND_GO_TEST").orEmpty()))
                cgoEnabled = "0"; goos = ""; goarch = ""; buildTags = ""
                environment = "GOENV=off\nGOPROXY=off\nGOSUMDB=off\nGOTOOLCHAIN=local\nGOTELEMETRY=off\nGOWORK=off"
            }
            val executor = DefaultRunExecutor.getRunExecutorInstance()
            val runner = GoProgramRunner()
            val config = edt {
                (GoTestConfigurationType().configurationFactories.single().createTemplateConfiguration(project) as GoRunConfiguration).apply {
                    name = "Original real Go run"; parameters.workingDirectory = root.toString(); parameters.target = "."; parameters.buildFlags = "-count=1"
                    beforeRunTasks = listOf(CountingTask().apply { isEnabled = true })
                }
            }
            val environment = ExecutionEnvironmentBuilder(project, executor).runProfile(config).runner(runner).build()
            selectedEnvironment = environment
            val state = config.getState(executor, environment) as GoCommandLineState
            original = state.createProcess() as GoTestProcessHandler
            println("GLOBAL_FAILED_GO_PID=${original.process.pid()}")
            val result = edt { state.createResult(original, executor) }
            originalConsole = result.executionConsole as com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView
            original.startNotify()
            assertTrue(original.waitFor(20_000))
            assertEquals(1, original.exitCode)
            assertEquals(setOf("TestMatrix/left/one", "TestMatrix/right/two"), original.output.failures().values.single().toSet())
            edt {
                val panel = JPanel(BorderLayout()).apply { add(originalConsole!!.component, BorderLayout.CENTER) }
                originalDescriptor = RunContentDescriptor(originalConsole, original, panel, config.name, null, null, result.restartActions)
                contentManager = (com.intellij.execution.ui.RunContentManager.getInstance(project) as RunContentManagerImpl).registerToolWindow(executor)
                nativeContent = com.intellij.ui.content.ContentFactory.getInstance().createContent(panel, config.name, false).apply {
                    putUserData(RunContentDescriptor.DESCRIPTOR_KEY, originalDescriptor)
                }
                contentManager!!.addContent(nativeContent!!); contentManager!!.setSelectedContent(nativeContent!!)
                @Suppress("UNCHECKED_CAST")
                val point = BeforeRunTaskProvider.EP_NAME.getPoint(project) as com.intellij.openapi.extensions.ExtensionPoint<BeforeRunTaskProvider<*>>
                point.registerExtension(CountingProvider(beforeLaunch, taskProfiles), registrations)
            }
            project.messageBus.connect(registrations).subscribe(ExecutionManager.EXECUTION_TOPIC, object : ExecutionListener {
                override fun processStarted(executorId: String, execution: ExecutionEnvironment, handler: ProcessHandler) {
                    if (execution.runProfile is GoRunConfiguration && handler is GoTestProcessHandler) {
                        children += execution to handler
                        println("GLOBAL_FAILED_GO_PID=${handler.process.pid()}")
                        if (children.size == 2) bothStarted.complete(Unit)
                    }
                }
            })
            manager.forceCompilationInTests = true // The normal unit-test fast path skips Before Launch altogether.
            edt {
                val action = requireNotNull(ActionManager.getInstance().getAction("RerunFailedTests"))
                val event = AnActionEvent.createFromAnAction(action, null, ActionPlaces.UNKNOWN, SimpleDataContext.getProjectContext(project))
                action.update(event); assertTrue(event.presentation.isEnabled); action.actionPerformed(event)
            }
            bothStarted.get(30, TimeUnit.SECONDS)
            assertEquals("The outer platform wrapper must not execute the peer's Before Launch task again", 2, beforeLaunch.get())
            assertTrue("Only concrete retries execute Before Launch", taskProfiles.all { it is GoRunConfiguration })
            assertEquals(setOf("^TestMatrix$/^left$/^one$", "^TestMatrix$/^right$/^two$"),
                taskProfiles.map { (it as GoRunConfiguration).parameters.testPattern }.toSet())
            val selectors = children.map { (it.first.runProfile as GoRunConfiguration).parameters.testPattern }.toSet()
            assertEquals(setOf("^TestMatrix$/^left$/^one$", "^TestMatrix$/^right$/^two$"), selectors)
            for ((childEnvironment, handler) in children) {
                assertTrue(handler.waitFor(20_000)); assertEquals(1, handler.exitCode)
                assertEquals("Run", childEnvironment.executor.id)
                assertEquals(1, handler.output.failures().values.single().size)
                assertNotNull(childEnvironment.contentToReuse)
            }
            assertEquals(2, children.map { it.second.process.pid() }.toSet().size)
        } finally {
            manager.forceCompilationInTests = forceBefore
            selectedEnvironment = null
            original?.let { if (!it.isProcessTerminated) it.destroyProcess(); it.waitFor(5_000) }
            children.forEach { (_, handler) -> if (!handler.isProcessTerminated) handler.destroyProcess(); handler.waitFor(5_000) }
            edt {
                nativeContent?.let { contentManager?.removeContent(it, true) }
                children.mapNotNull { it.first.contentToReuse }.distinct().forEach { if (!Disposer.isDisposed(it)) Disposer.dispose(it) }
                originalDescriptor?.let { if (!Disposer.isDisposed(it)) Disposer.dispose(it) }
                originalConsole?.let { if (!Disposer.isDisposed(it)) Disposer.dispose(it) }
                Disposer.dispose(registrations)
            }
            XmlSerializerUtil.copyBean(previous, GoProjectSettings.get(project))
            root.toFile().deleteRecursively()
        }
    }

    private class CountingTask : BeforeRunTask<CountingTask>(TASK_KEY)
    private class CountingProvider(private val count: AtomicInteger, private val profiles: MutableList<RunConfiguration>) : BeforeRunTaskProvider<CountingTask>() {
        override fun getId() = TASK_KEY
        override fun getName() = "Owned global rerun Before Launch counter"
        override fun createTask(configuration: RunConfiguration) = CountingTask()
        override fun executeTask(context: DataContext, configuration: RunConfiguration, environment: ExecutionEnvironment, task: CountingTask): Boolean {
            profiles += configuration
            println("GLOBAL_FAILED_BEFORE_LAUNCH=${configuration.javaClass.name}:${(configuration as? GoRunConfiguration)?.parameters?.testPattern.orEmpty()}")
            count.incrementAndGet(); return true
        }
    }
    private fun <T> edt(block: () -> T): T {
        var value: T? = null
        EdtTestUtil.runInEdtAndWait<RuntimeException> { value = block() }
        @Suppress("UNCHECKED_CAST") return value as T
    }
    companion object { private val TASK_KEY = Key.create<CountingTask>("OwnedGlobalGoRetryBeforeLaunch") }
}
