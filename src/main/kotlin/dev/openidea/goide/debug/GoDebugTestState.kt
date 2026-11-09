package dev.openidea.goide.debug

import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionException
import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.filters.TextConsoleBuilderImpl
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.AsyncProgramRunner
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.Disposer
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugProcessStarter
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerManager
import com.redhat.devtools.lsp4ij.dap.DebugAdapterManager
import com.redhat.devtools.lsp4ij.dap.DAPDebugProcess
import com.redhat.devtools.lsp4ij.dap.DAPServerReadyTracker
import com.redhat.devtools.lsp4ij.dap.configurations.DAPCommandLineState
import dev.openidea.goide.run.GoRerunFailedTestsAction
import dev.openidea.goide.run.GoRunConfiguration
import dev.openidea.goide.run.GoRunKind
import org.jetbrains.concurrency.AsyncPromise
import org.jetbrains.concurrency.Promise
import java.util.concurrent.CompletableFuture

/** Keeps synchronous DAP state APIs free of filesystem/process work on the EDT. */
internal class GoDebugTestState(
    environment: ExecutionEnvironment,
    private val configuration: RunConfiguration,
    val testSession: GoDebugTestSession = GoDebugTestSession(configuration),
) : DAPCommandLineState(GoDebugDescriptor(GoDebugTestOptions(testSession), environment, DebugAdapterManager.getInstance().getDebugAdapterServerById("openidea.delve")),
    GoDebugTestOptions(testSession), environment) {
    private var preparedAdapter: ProcessHandler? = null
    val rerunFailedAction by lazy {
        GoRerunFailedTestsAction(testSession.profileSnapshot, testSession.output, testSession.values, testSession.events, environment.executor)
    }
    init {
        consoleBuilder = object : TextConsoleBuilderImpl(environment.project) {
            override fun createConsole() = testSession.createConsole(environment.executor)
        }
    }

    fun prepare() {
        try {
            testSession.prepare()
            if (environment.project.isDisposed || testSession.completed.isDone)
                throw ExecutionException("Project closed before the Go test adapter started")
            preparedAdapter = super.startProcess().also { adapter ->
                DAPServerReadyTracker.getServerReadyTracker(adapter).cancel(true)
                val ready = object : DAPServerReadyTracker(getServerReadyConfig(debugMode), debugMode, testSession.events) {
                    override fun track(): CompletableFuture<Void> {
                        // DAPDebugProcess already owns this tracker reference here.
                        // Start OS readers on its background task, even when the SDK
                        // runs that task synchronously in unit-test mode.
                        if (!adapter.isStartNotified) adapter.startNotify()
                        return super.track()
                    }
                }
                testSession.bindAdapter(adapter, ready)
                ProcessTerminatedListener.attach(testSession.events)
            }
        } catch (failure: Throwable) {
            testSession.end(1)
            Disposer.dispose(testSession)
            throw failure
        }
    }

    override fun startProcess(): ProcessHandler {
        if (preparedAdapter == null) throw ExecutionException("Go Debug Test was not prepared")
        return testSession.events
    }

    override fun execute(executor: Executor, runner: ProgramRunner<*>): ExecutionResult =
        DefaultExecutionResult(consoleBuilder.console, startProcess()).apply { setRestartActions(rerunFailedAction) }

}

/** Only test Debug uses this runner; application/attach debugging keeps the upstream runner. */
class GoDebugTestRunner : AsyncProgramRunner<RunnerSettings>() {
    override fun getRunnerId() = "OpenIDEAGoDebugTest"
    override fun canRun(executorId: String, profile: RunProfile) = executorId == DefaultDebugExecutor.EXECUTOR_ID &&
        (profile is GoRunConfiguration && profile.kind == GoRunKind.TEST || profile is GoDebugConfiguration && profile.goOptions.mode == "test")

    override fun execute(environment: ExecutionEnvironment, state: RunProfileState): Promise<RunContentDescriptor?> {
        val result = AsyncPromise<RunContentDescriptor?>()
        val testState = state as? GoDebugTestState ?: return result.apply { setError("Unsupported Go Debug Test state") }
        WriteIntentReadAction.run { FileDocumentManager.getInstance().saveAllDocuments() }
        AppExecutorUtil.getAppExecutorService().execute {
            try {
                if (environment.project.isDisposed) throw ExecutionException("Project closed before Go Debug Test could start")
                testState.prepare()
                ApplicationManager.getApplication().invokeLater {
                    try {
                        if (environment.project.isDisposed) throw ExecutionException("Project closed before the debug console opened")
                        val starter = object : XDebugProcessStarter() {
                            override fun start(session: XDebugSession): XDebugProcess {
                                val execution = testState.execute(environment.executor, this@GoDebugTestRunner)
                                return object : DAPDebugProcess(testState, session, execution, true) {
                                    override fun registerAdditionalActions(left: DefaultActionGroup, top: DefaultActionGroup, settings: DefaultActionGroup) {
                                        super.registerAdditionalActions(left, top, settings)
                                        left.add(testState.rerunFailedAction)
                                    }
                                }
                            }
                        }
                        val started = XDebuggerManager.getInstance(environment.project).newSessionBuilder(starter)
                            .environment(environment).startSession()
                        result.setResult(started.runContentDescriptor)
                    } catch (failure: Throwable) {
                        Disposer.dispose(testState.testSession)
                        result.setError(failure)
                    }
                }
            } catch (failure: Throwable) {
                Disposer.dispose(testState.testSession)
                result.setError(failure)
            }
        }
        return result
    }
}
