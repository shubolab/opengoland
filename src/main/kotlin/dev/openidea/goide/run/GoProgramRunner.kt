package dev.openidea.goide.run

import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionException
import com.intellij.execution.Executor
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.KillableProcessHandler
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.AsyncProgramRunner
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.RunContentBuilder
import com.intellij.execution.testframework.sm.SMTestRunnerConnectionUtil
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.util.concurrency.AppExecutorUtil
import dev.openidea.goide.settings.GoProjectSettings
import dev.openidea.goide.settings.GoToolchain
import dev.openidea.goide.coverage.GoCoverageService
import dev.openidea.goide.coverage.GoNativeCoverageResult
import org.jetbrains.concurrency.AsyncPromise
import org.jetbrains.concurrency.Promise
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/** Process creation runs on a pool; console creation and window updates run on the EDT. */
class GoProgramRunner : AsyncProgramRunner<RunnerSettings>() {
    override fun getRunnerId() = "OpenIDEAGoRun"
    override fun canRun(executorId: String, profile: RunProfile) = profile is GoRunConfiguration &&
        (executorId == DefaultRunExecutor.EXECUTOR_ID || executorId == GO_COVERAGE_EXECUTOR &&
            profile.kind == GoRunKind.TEST && profile.parameters.testMode == GoTestMode.TEST)

    override fun execute(environment: ExecutionEnvironment, state: RunProfileState): Promise<RunContentDescriptor?> {
        val promise = AsyncPromise<RunContentDescriptor?>()
        val goState = state as? GoCommandLineState ?: return promise.apply { setError("Unsupported Go run state") }
        WriteIntentReadAction.run {
            FileDocumentManager.getInstance().saveAllDocuments()
            goState.prepareCoverage()
        }
        AppExecutorUtil.getAppExecutorService().execute {
            try {
                if (environment.project.isDisposed) throw ExecutionException("Project was closed before Go could start.")
                val handler = goState.createProcess()
                ApplicationManager.getApplication().invokeLater {
                    if (environment.project.isDisposed) {
                        handler.startNotify()
                        handler.destroyProcess()
                        promise.setError("Project was closed before the Go console opened.")
                    } else try {
                        val result = goState.createResult(handler, environment.executor)
                        val descriptor = RunContentBuilder(result, environment).showRunContent(environment.contentToReuse)
                        handler.startNotify()
                        promise.setResult(descriptor)
                    } catch (error: Throwable) {
                        handler.startNotify()
                        handler.destroyProcess()
                        promise.setError(error)
                    }
                }
            } catch (error: Throwable) { promise.setError(error) }
        }
        return promise
    }

    companion object { const val GO_COVERAGE_EXECUTOR = "Coverage" }
}

class GoCommandLineState(environment: ExecutionEnvironment, private val configuration: GoRunConfiguration) : CommandLineState(environment) {
    private val values = configuration.parameters.independentCopy()
    private var coverageService: GoCoverageService? = null
    private var coverageRevision = 0L

    fun prepareCoverage() {
        if (environment.executor.id == GoProgramRunner.GO_COVERAGE_EXECUTOR && configuration.kind == GoRunKind.TEST && values.testMode == GoTestMode.TEST) {
            coverageService = GoCoverageService.get(configuration.project)
            coverageRevision = coverageService!!.captureSourceRevision()
        }
    }

    fun createProcess(): ProcessHandler {
        val cwd = Path.of(values.workingDirectory.ifBlank { configuration.project.basePath.orEmpty() })
        if (!Files.isDirectory(cwd)) throw ExecutionException("Working directory does not exist: $cwd")
        val profile = if (coverageService != null) {
            val directory = PathManager.getSystemDir().resolve("openidea-go/coverage/native")
            Files.createDirectories(directory)
            directory.resolve("go-test-${java.util.UUID.randomUUID()}.cover")
        } else null
        val args = try { values.commandArguments(configuration.kind, GoProjectSettings.get(configuration.project).buildTags, profile?.toString()) }
            catch (error: IllegalArgumentException) { throw ExecutionException(error.message.orEmpty(), error) }
        val command = GoToolchain.command(configuration.project, "go", args)
            .withWorkingDirectory(cwd).withCharset(StandardCharsets.UTF_8).withEnvironment(values.environment)
        if (!values.passParentEnvironment) command.withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.NONE)
        val metadata = if (configuration.kind == GoRunKind.TEST) GoPackageDirectories.loadMetadata(configuration.project, values, cwd) else GoPackageMetadata(emptyMap(), emptyList())
        if (configuration.project.isDisposed) throw ExecutionException("Project was closed before Go could start.")
        val handler = if (configuration.kind == GoRunKind.TEST) GoTestProcessHandler(command, cwd.toString(), metadata.directories) else KillableProcessHandler(command)
        if (profile != null && handler is GoTestProcessHandler) handler.addProcessListener(object : ProcessAdapter() {
            override fun processTerminated(event: ProcessEvent) {
                val cancelled = handler.cancellationRequested
                ApplicationManager.getApplication().executeOnPooledThread {
                    if (cancelled || !Files.isRegularFile(profile) || Files.size(profile) == 0L) {
                        Files.deleteIfExists(profile)
                    } else coverageService?.acceptNativeProfile(GoNativeCoverageResult(
                        profile, cwd.toString(), event.exitCode, metadata.modules, coverageRevision, configuration.name,
                    ))
                }
            }
        })
        handler.setShouldDestroyProcessRecursively(true)
        ProcessTerminatedListener.attach(handler)
        val cleanup = Disposable { if (!handler.isProcessTerminated) handler.destroyProcess() }
        Disposer.register(configuration.project, cleanup)
        handler.addProcessListener(object : ProcessAdapter() {
            override fun processTerminated(event: ProcessEvent) { Disposer.dispose(cleanup) }
        })
        return handler
    }

    override fun startProcess() = createProcess()

    fun createResult(handler: ProcessHandler, executor: Executor): DefaultExecutionResult {
        if (handler is GoTestProcessHandler) {
            val properties = GoTestConsoleProperties(configuration, executor, handler.output)
            val console = SMTestRunnerConnectionUtil.createAndAttachConsole("OpenIDEAGo", handler, properties)
            console.addMessageFilter(properties.fuzzCorpusFilter)
            val rerun = GoRerunFailedTestsAction(configuration, handler.output, values, handler, executor)
            return DefaultExecutionResult(console, handler).apply { setRestartActions(rerun) }
        }
        val console = consoleBuilder.console
        console.attachToProcess(handler)
        return DefaultExecutionResult(console, handler)
    }
}

class GoTestProcessHandler(commandLine: GeneralCommandLine, workingDirectory: String, packageDirectories: Map<String, String> = emptyMap()) : KillableProcessHandler(commandLine) {
    val output = GoTestOutput(workingDirectory, packageDirectories)
    @Volatile var cancellationRequested = false
        private set
    override fun destroyProcessImpl() { cancellationRequested = true; output.requestCancellation(); super.destroyProcessImpl() }
    override fun killProcess() { cancellationRequested = true; output.requestCancellation(); super.killProcess() }
    override fun notifyTextAvailable(text: String, outputType: Key<*>) {
        if (outputType === ProcessOutputTypes.STDOUT) output.consume(text) { super.notifyTextAvailable(it, outputType) }
        else super.notifyTextAvailable(text, outputType)
    }
    override fun notifyProcessTerminated(exitCode: Int) {
        try { output.finish(exitCode, cancellationRequested) { super.notifyTextAvailable(it, ProcessOutputTypes.STDOUT) } }
        finally { output.close(); super.notifyProcessTerminated(exitCode) }
    }
}
