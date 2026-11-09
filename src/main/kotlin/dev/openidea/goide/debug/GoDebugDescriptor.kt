package dev.openidea.goide.debug

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.RunConfigurationOptions
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.Disposable
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider
import com.redhat.devtools.lsp4ij.dap.DAPDebugProcess
import com.redhat.devtools.lsp4ij.dap.DebugMode
import com.redhat.devtools.lsp4ij.dap.client.DAPClient
import com.redhat.devtools.lsp4ij.settings.ServerTrace
import com.redhat.devtools.lsp4ij.dap.definitions.DebugAdapterServerDefinition
import com.redhat.devtools.lsp4ij.dap.descriptors.DebugAdapterDescriptor
import com.redhat.devtools.lsp4ij.dap.descriptors.ServerReadyConfig
import dev.openidea.goide.lang.GoFileType
import dev.openidea.goide.settings.GoProjectSettings
import dev.openidea.goide.settings.GoToolchain

class GoDebugDescriptor(private val launchOptions: RunConfigurationOptions, environment: ExecutionEnvironment, definition: DebugAdapterServerDefinition?) : DebugAdapterDescriptor(launchOptions, environment, definition) {
    override fun createClient(debugProcess: com.redhat.devtools.lsp4ij.dap.DAPDebugProcess, dapParameters: MutableMap<String, Any>, isDebug: Boolean,
        debugMode: DebugMode, serverTrace: ServerTrace, parentClient: DAPClient?): DAPClient =
        if (launchOptions is GoDebugTestOptions && parentClient == null)
            GoTestDapClient(debugProcess, dapParameters, isDebug, debugMode, serverTrace, parentClient, launchOptions.testSession)
        else GoDapClient(debugProcess, dapParameters, isDebug, debugMode, serverTrace, parentClient)
    private val launch: GoDebugLaunch get() = when (launchOptions) {
        is GoDebugTestOptions -> launchOptions.testSession.launch
        is GoDebugSessionOptions -> launchOptions.launch
        is GoDebugOptions -> GoDebugLaunch.fromOptions(launchOptions, environment.project.basePath.orEmpty())
        else -> error("Unsupported Go debug configuration")
    }
    override fun startServer(): ProcessHandler {
        val command = GoToolchain.command(environment.project, "dlv", listOf("dap", "--listen=127.0.0.1:0"),
            (launchOptions as? GoDebugTestOptions)?.testSession?.settings ?: GoToolchain.capture(environment.project))
            .withWorkDirectory(launch.workingDirectory).withEnvironment(launch.environment)
        if (!launch.passParentEnvironment) command.withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.NONE)
        val handler = startServer(command)
        handler.setShouldDestroyProcessRecursively(true)
        val cleanup = Disposable { if (!handler.isProcessTerminated) handler.destroyProcess() }
        try {
            Disposer.register(environment.project, cleanup)
            handler.addProcessListener(object : ProcessListener {
                override fun processTerminated(event: ProcessEvent) { Disposer.dispose(cleanup) }
            })
        } catch (failure: Throwable) {
            handler.destroyProcess()
            throw failure
        }
        return handler
    }
    override fun getDapParameters(): Map<String, Any> {
        val project = environment.project
        if (launchOptions is GoDebugTestOptions) return launchOptions.testSession.dapParameters()
        val settings = GoProjectSettings.get(project)
        return launch.dapParameters(environment.runProfile.name, GoToolchain.command(project, "go", emptyList()).environment, settings.buildTags)
    }
    override fun getFileType() = GoFileType.INSTANCE
    override fun createBreakpointHandler(session: XDebugSession, project: Project) = GoDapBreakpointHandler(session, this, project)
    override fun createDebuggerEditorsProvider(fileType: FileType?, debugProcess: DAPDebugProcess): XDebuggerEditorsProvider =
        GoDebuggerEditorsProvider(fileType, debugProcess)
    override fun isDebuggableFile(file: VirtualFile, project: Project) = file.extension == "go"
    override fun getServerReadyConfig(debugMode: DebugMode) = ServerReadyConfig("DAP server listening at: \${address}:\${port}")
}
