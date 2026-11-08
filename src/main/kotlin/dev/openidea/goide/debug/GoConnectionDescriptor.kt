package dev.openidea.goide.debug

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.redhat.devtools.lsp4ij.dap.DAPDebugProcess
import com.redhat.devtools.lsp4ij.dap.DebugMode
import com.redhat.devtools.lsp4ij.dap.client.DAPClient
import com.redhat.devtools.lsp4ij.dap.definitions.DebugAdapterServerDefinition
import com.redhat.devtools.lsp4ij.dap.descriptors.DebugAdapterDescriptor
import com.redhat.devtools.lsp4ij.dap.descriptors.ServerReadyConfig
import com.redhat.devtools.lsp4ij.settings.ServerTrace
import dev.openidea.goide.lang.GoFileType
import dev.openidea.goide.settings.GoToolchain
import org.eclipse.lsp4j.debug.DisconnectArguments
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class GoConnectionDescriptor(options: GoConnectionOptions, environment: ExecutionEnvironment,
    definition: DebugAdapterServerDefinition?, localAdapter: Boolean) : DebugAdapterDescriptor(options, environment, definition) {
    val connection = GoDebugConnection.fromOptions(options, environment.project.basePath.orEmpty(), localAdapter)
    override fun getDebugMode() = connection.adapterMode
    override fun getDapParameters() = connection.dapParameters(environment.runProfile.name)
    override fun getFileType() = GoFileType.INSTANCE
    override fun isDebuggableFile(file: VirtualFile, project: Project) = file.extension == "go"
    override fun getServerReadyConfig(debugMode: DebugMode): ServerReadyConfig = if (connection.localAdapter)
        ServerReadyConfig("DAP server listening at: \${address}:\${port}") else ServerReadyConfig(connection.host, connection.port)

    override fun startServer(): ProcessHandler {
        check(connection.localAdapter) { "Remote DAP configurations never start a local debugger server." }
        val command: GeneralCommandLine = GoToolchain.command(environment.project, "dlv", listOf("dap", "--listen=127.0.0.1:0"))
            .withWorkDirectory(connection.workingDirectory).withEnvironment(connection.environment)
        val handler = startServer(command)
        // Attached targets are not ours. In particular, never recursively destroy the adapter's process tree.
        handler.setShouldDestroyProcessRecursively(false)
        val cleanup = Disposable { if (!handler.isProcessTerminated) handler.destroyProcess() }
        Disposer.register(environment.project, cleanup)
        handler.addProcessListener(object : ProcessListener { override fun processTerminated(event: ProcessEvent) { Disposer.dispose(cleanup) } })
        return handler
    }

    override fun createClient(debugProcess: DAPDebugProcess, dapParameters: MutableMap<String, Any>, isDebug: Boolean,
        debugMode: DebugMode, serverTrace: ServerTrace, parentClient: DAPClient?): DAPClient =
        GoAttachedDAPClient(debugProcess, dapParameters.filterKeys { it != "noDebug" }, isDebug,
            connection.requestMode, serverTrace, parentClient)
}

/** Stop is a DAP detach, irrespective of the outer mode used to spawn the adapter. */
class GoAttachedDAPClient(debugProcess: DAPDebugProcess, parameters: Map<String, Any>, isDebug: Boolean,
    requestMode: DebugMode, serverTrace: ServerTrace, parentClient: DAPClient?) :
    DAPClient(debugProcess, parameters, isDebug, requestMode, serverTrace, parentClient) {
    private val disconnecting = AtomicBoolean()
    override fun terminate() {
        if (!disconnecting.compareAndSet(false, true)) return
        val server = debugProtocolServer
        if (server == null) { dispose(); return }
        try {
            server.disconnect(DisconnectArguments().apply { terminateDebuggee = false })
                .orTimeout(3, TimeUnit.SECONDS).whenComplete { _, _ -> dispose() }
        } catch (_: Exception) { dispose() }
    }
}
