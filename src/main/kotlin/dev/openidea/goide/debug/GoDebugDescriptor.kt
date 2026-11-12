package dev.openidea.goide.debug

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.RunConfigurationOptions
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessTerminatedListener
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
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture

class GoDebugDescriptor(private val launchOptions: RunConfigurationOptions, environment: ExecutionEnvironment, definition: DebugAdapterServerDefinition?) : DebugAdapterDescriptor(launchOptions, environment, definition) {
    @Volatile private var adapter: GoOwnedDapAdapterProcessHandler? = null
    // Publish the Stop gate with the process, before a client/RPC exists.
    private val clientShutdown = CompletableFuture<() -> CompletableFuture<Void>>()

    override fun createClient(debugProcess: com.redhat.devtools.lsp4ij.dap.DAPDebugProcess, dapParameters: MutableMap<String, Any>, isDebug: Boolean,
        debugMode: DebugMode, serverTrace: ServerTrace, parentClient: DAPClient?): DAPClient {
        if (parentClient == null && adapter?.let { it.isProcessTerminating || it.isProcessTerminated } == true)
            throw CancellationException("Go debug launch was stopped before connecting")
        val client = when {
            parentClient != null -> GoDapClient(debugProcess, dapParameters, isDebug, debugMode, serverTrace, parentClient)
            launchOptions is GoDebugTestOptions -> GoTestDapClient(debugProcess, dapParameters, isDebug, debugMode, serverTrace, null, launchOptions.testSession)
            else -> GoLaunchedDapClient(debugProcess, dapParameters, isDebug, debugMode, serverTrace, null)
        }
        if (parentClient == null) {
            clientShutdown.complete(when (client) {
                is GoTestDapClient -> { { client.terminate(); client.disconnectTest() } }
                is GoLaunchedDapClient -> client::disconnectLaunched
                else -> error("Unsupported owned Go launch client")
            })
            // Stop may win while the constructor is creating its transport.
            // Do not return that late client to an initialization/launch chain.
            if (adapter?.let { it.isProcessTerminating || it.isProcessTerminated } == true) {
                client.terminate()
                throw CancellationException("Go debug launch was stopped while connecting")
            }
        }
        return client
    }
    private val launch: GoDebugLaunch get() = when (launchOptions) {
        is GoDebugTestOptions -> launchOptions.testSession.launch
        is GoDebugSessionOptions -> launchOptions.launch
        is GoDebugOptions -> GoDebugLaunch.fromOptions(launchOptions, environment.project.basePath.orEmpty())
        else -> error("Unsupported Go debug configuration")
    }
    override fun startServer(): ProcessHandler {
        GoDebugPrerequisites.requireMacDebuggerAccess()
        val command = GoToolchain.command(environment.project, "dlv", listOf("dap", "--listen=127.0.0.1:0"),
            (launchOptions as? GoDebugTestOptions)?.testSession?.settings ?: GoToolchain.capture(environment.project))
            .withWorkDirectory(launch.workingDirectory).withEnvironment(launch.environment)
        if (!launch.passParentEnvironment) command.withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.NONE)
        val handler = GoOwnedDapAdapterProcessHandler(command).also { adapter = it }
        handler.disconnect = { clientShutdown.thenCompose { shutdown -> shutdown() } }
        ProcessTerminatedListener.attach(handler)
        val cleanup = Disposable { if (!handler.isProcessTerminated) handler.destroyProcess() }
        try {
            Disposer.register(environment.project, cleanup)
            handler.addProcessListener(object : ProcessListener {
                override fun processTerminated(event: ProcessEvent) {
                    clientShutdown.completeExceptionally(CancellationException("Owned Go adapter exited before client binding"))
                    Disposer.dispose(cleanup)
                }
            })
        } catch (failure: Throwable) {
            clientShutdown.completeExceptionally(failure)
            // ProcessHandler defers destroyProcess until startNotify. No runner
            // receives this failed startServer result, so activate its reader
            // and exact-owned-process cleanup here before rethrowing.
            if (!handler.isStartNotified) handler.startNotify()
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
