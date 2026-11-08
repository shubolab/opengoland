package dev.openidea.goide.debug

import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.configurations.RunConfigurationOptions
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.process.NopProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.ui.ConsoleView
import com.intellij.testFramework.LightPlatformTestCase
import com.intellij.util.ui.UIUtil
import com.intellij.xdebugger.XDebugSession
import com.redhat.devtools.lsp4ij.dap.DAPDebugProcess
import com.redhat.devtools.lsp4ij.dap.DAPDebugRunner
import com.redhat.devtools.lsp4ij.dap.DAPServerReadyTracker
import com.redhat.devtools.lsp4ij.dap.DebugMode
import com.redhat.devtools.lsp4ij.dap.client.DAPClient
import com.redhat.devtools.lsp4ij.dap.configurations.DAPCommandLineState
import com.redhat.devtools.lsp4ij.dap.descriptors.DebugAdapterDescriptor
import com.redhat.devtools.lsp4ij.dap.descriptors.ServerReadyConfig
import com.redhat.devtools.lsp4ij.settings.ServerTrace
import dev.openidea.goide.lang.GoFileType
import org.eclipse.lsp4j.debug.*
import org.eclipse.lsp4j.debug.launch.DSPLauncher
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient
import org.eclipse.lsp4j.debug.services.IDebugProtocolServer
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import java.lang.Thread as JavaThread
import java.lang.reflect.Proxy
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import javax.swing.Icon
import javax.swing.JPanel

/** Actual SDK client/process and LSP4J transport; only the session/console boundary and DAP peer are test doubles. */
class GoDapClientRoutingTest : LightPlatformTestCase() {
    fun testPublicClientMethodsReachTheBridgeAndKeepAllArguments() {
        Fixture().use { fixture ->
            val client = fixture.client()
            await(client.continue_(7))
            client.next(8, SteppingGranularity.INSTRUCTION)
            client.stepOut(9, SteppingGranularity.STATEMENT)
            client.stepIn(10, SteppingGranularity.LINE)
            client.stepIn(11, 99, SteppingGranularity.INSTRUCTION)
            val value = await(client.evaluate("cart.Value", 42, "watch"))
            assertEquals("37", value.result)
            val changed = await(client.setVariable("cart", 88, "updated"))
            assertEquals("updated", changed.value)
            waitFor { fixture.peer.steps.size == 4 }
            assertEquals(7, fixture.peer.continues.single().threadId)
            assertEquals(listOf("next", "out", "in", "in"), fixture.peer.steps)
            val next = fixture.peer.nexts.single()
            assertEquals(8, next.threadId); assertEquals(SteppingGranularity.INSTRUCTION, next.granularity)
            val out = fixture.peer.stepOuts.single()
            assertEquals(9, out.threadId); assertEquals(SteppingGranularity.STATEMENT, out.granularity)
            assertEquals(listOf(10, 11), fixture.peer.stepIns.map { it.threadId })
            assertEquals(listOf(null, 99), fixture.peer.stepIns.map { it.targetId })
            assertEquals(listOf(SteppingGranularity.LINE, SteppingGranularity.INSTRUCTION), fixture.peer.stepIns.map { it.granularity })
            val evaluated = fixture.peer.evaluations.single()
            assertEquals("cart.Value", evaluated.expression); assertEquals(42, evaluated.frameId); assertEquals("watch", evaluated.context)
            val assignment = fixture.peer.assignments.single()
            assertEquals("cart", assignment.name); assertEquals(88, assignment.variablesReference); assertEquals("updated", assignment.value)
            assertNotNull("Retain LSP4IJ's value-format object", assignment.format)

            val rejected = client.setVariable("Unavailable", -7, "must not reach the wire")
            waitFor { rejected.isDone }
            assertTrue(rejected.isCompletedExceptionally)
            assertEquals("A frontend error handle must never reach the DAP peer", 1, fixture.peer.assignments.size)
        }
    }

    fun testPublicEvaluateAndContinueWaitForTheRealScopesRecoveryQueue() {
        Fixture().use { fixture ->
            val client = fixture.client()
            fixture.peer.recoverGlobals = true
            val scopes = client.debugProtocolServer!!.scopes(ScopesArguments().apply { frameId = 1002 })
            waitFor { fixture.peer.scopeRequests == 2 }
            val update = client.evaluate("dlv config showGlobalVariables false", null, "repl")
            val resume = client.continue_(17)
            assertFalse(update.isDone)
            assertFalse(resume.isDone)
            assertEquals(listOf("dlv config showGlobalVariables false"), fixture.peer.evaluations.map { it.expression })

            fixture.peer.retry.complete(ScopesResponse().apply { this.scopes = arrayOf(Scope().apply { name = "Locals"; variablesReference = 42 }) })
            val result = await(scopes)
            await(update); await(resume)
            assertEquals(listOf("dlv config showGlobalVariables false", "dlv config showGlobalVariables true", "dlv config showGlobalVariables false"), fixture.peer.evaluations.map { it.expression })
            assertEquals(listOf("config:false", "config:true", "config:false", "continue:17"), fixture.peer.configAndResume)
            val error = result.scopes.single { it.variablesReference < 0 }
            val privateValue = await(client.debugProtocolServer!!.variables(VariablesArguments().apply { variablesReference = error.variablesReference }))
            assertEquals("error", privateValue.variables.single().type)
            assertTrue(fixture.peer.variableRequests.isEmpty())
        }
    }

    fun testVoidControlFailureCompletesWithAConsoleError() {
        Fixture().use { fixture ->
            val client = fixture.client()
            fixture.peer.failNext = true
            client.next(3, SteppingGranularity.LINE)
            waitFor { fixture.console.any { it.contains("Step Over failed:") && it.contains("controlled step failure") } }
        }
    }

    fun testCancellingQueuedContinueDoesNotResumeOrPrintAnError() {
        Fixture().use { fixture ->
            val client = fixture.client()
            fixture.peer.recoverGlobals = true
            val scopes = client.debugProtocolServer!!.scopes(ScopesArguments().apply { frameId = 1002 })
            waitFor { fixture.peer.scopeRequests == 2 }
            val resume = client.continue_(17)
            assertTrue(resume.cancel(true))
            fixture.peer.retry.complete(ScopesResponse().apply { this.scopes = emptyArray() })
            await(scopes)
            assertTrue(resume.isCancelled)
            assertTrue("Cancellation must not dispatch a stale Continue", fixture.peer.continues.isEmpty())
            assertFalse("A normal cancellation is not a control failure", fixture.console.any { it.contains("Continue failed:") })
        }
    }

    private inner class Fixture : AutoCloseable {
        val peer = Peer()
        val console = CopyOnWriteArrayList<String>()
        private val workers = Executors.newCachedThreadPool { task -> JavaThread(task, "Go-client-routing-test").apply { isDaemon = true } }
        private val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        private val accepted = CompletableFuture<Socket>()
        private var listening: Future<Void>? = null
        private val captured = CompletableFuture<GoDapClient>()
        private val handler = NopProcessHandler()
        private val debugProcess: DAPDebugProcess

        init {
            workers.submit {
                try {
                    val socket = listener.accept(); accepted.complete(socket)
                    val launcher = DSPLauncher.createServerLauncher(peer, socket.getInputStream(), socket.getOutputStream(), workers) { it }
                    peer.client = launcher.remoteProxy
                    listening = launcher.startListening()
                } catch (failure: Exception) { captured.completeExceptionally(failure) }
            }
            val executor = DefaultDebugExecutor.getDebugExecutorInstance()
            val profile = object : RunProfile {
                override fun getName() = "Go DAP routing test"
                override fun getIcon(): Icon? = null
                override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState? = null
            }
            val environment = ExecutionEnvironmentBuilder(project, executor).runProfile(profile).runner(DAPDebugRunner()).build()
            val options = object : RunConfigurationOptions() {}
            val descriptor = object : DebugAdapterDescriptor(options, environment, null) {
                override fun getId() = "opengoland-routing-test"
                override fun getFileType() = GoFileType.INSTANCE
                override fun getDebugMode() = DebugMode.ATTACH
                override fun getDapParameters() = mapOf<String, Any>("mode" to "local")
                override fun getServerReadyConfig(debugMode: DebugMode) = ServerReadyConfig("127.0.0.1", listener.localPort)
                override fun startServer(): ProcessHandler = error("The external loopback DAP peer must not spawn a process")
                override fun createClient(process: DAPDebugProcess, parameters: MutableMap<String, Any>, isDebug: Boolean,
                    mode: DebugMode, trace: ServerTrace, parent: DAPClient?): DAPClient =
                    GoDapClient(process, parameters, isDebug, mode, trace, parent).also { captured.complete(it) }
            }
            val state = DAPCommandLineState(descriptor, options, environment)
            val consoleView = Proxy.newProxyInstance(ConsoleView::class.java.classLoader, arrayOf(ConsoleView::class.java)) { _, method, args ->
                when (method.name) { "print" -> { console += args!![0] as String; null }; "getComponent", "getPreferredFocusableComponent" -> JPanel(); else -> defaultValue(method.returnType) }
            } as ConsoleView
            val session = Proxy.newProxyInstance(XDebugSession::class.java.classLoader, arrayOf(XDebugSession::class.java)) { _, method, _ ->
                when (method.name) { "getProject" -> project; "getConsoleView" -> consoleView; "getSessionName" -> "Go routing test"; else -> defaultValue(method.returnType) }
            } as XDebugSession
            handler.startNotify()
            DAPServerReadyTracker(descriptor.getServerReadyConfig(DebugMode.ATTACH), DebugMode.ATTACH, handler)
            debugProcess = DAPDebugProcess(state, session, DefaultExecutionResult(consoleView, handler), true)
        }

        fun client(): GoDapClient {
            val client = await(captured)
            await(peer.ready)
            assertNotNull("The real LSP4J initialization must install the wrapped endpoint", client.debugProtocolServer)
            return client
        }

        override fun close() {
            if (captured.isDone && !captured.isCompletedExceptionally) captured.getNow(null)?.dispose()
            debugProcess.dispose()
            handler.destroyProcess()
            accepted.getNow(null)?.close(); listener.close(); listening?.cancel(true)
            workers.shutdownNow()
            UIUtil.dispatchAllInvocationEvents()
        }
    }

    private class Peer : IDebugProtocolServer {
        lateinit var client: IDebugProtocolClient
        val ready = CompletableFuture<Unit>()
        val continues = CopyOnWriteArrayList<ContinueArguments>()
        val steps = CopyOnWriteArrayList<String>()
        val nexts = CopyOnWriteArrayList<NextArguments>()
        val stepOuts = CopyOnWriteArrayList<StepOutArguments>()
        val stepIns = CopyOnWriteArrayList<StepInArguments>()
        val evaluations = CopyOnWriteArrayList<EvaluateArguments>()
        val assignments = CopyOnWriteArrayList<SetVariableArguments>()
        val variableRequests = CopyOnWriteArrayList<Int>()
        val configAndResume = CopyOnWriteArrayList<String>()
        val retry = CompletableFuture<ScopesResponse>()
        @Volatile var recoverGlobals = false
        @Volatile var failNext = false
        @Volatile var scopeRequests = 0
        override fun initialize(args: InitializeRequestArguments) = CompletableFuture.completedFuture(Capabilities().apply { supportsConfigurationDoneRequest = true })
        override fun attach(args: MutableMap<String, Any>): CompletableFuture<Void> { client.initialized(); return done() }
        override fun configurationDone(args: ConfigurationDoneArguments) = done()
        override fun threads() = CompletableFuture.completedFuture(ThreadsResponse().apply { threads = emptyArray(); ready.complete(Unit) })
        override fun setBreakpoints(args: SetBreakpointsArguments) = CompletableFuture.completedFuture(SetBreakpointsResponse().apply { breakpoints = emptyArray() })
        override fun continue_(args: ContinueArguments): CompletableFuture<ContinueResponse> {
            continues += args; configAndResume += "continue:${args.threadId}"
            return CompletableFuture.completedFuture(ContinueResponse().apply { allThreadsContinued = true })
        }
        override fun next(args: NextArguments): CompletableFuture<Void> {
            steps += "next"; nexts += args
            return if (failNext) CompletableFuture.failedFuture(ResponseErrorException(ResponseError(-32001, "controlled step failure", null))) else done()
        }
        override fun stepOut(args: StepOutArguments): CompletableFuture<Void> { steps += "out"; stepOuts += args; return done() }
        override fun stepIn(args: StepInArguments): CompletableFuture<Void> { steps += "in"; stepIns += args; return done() }
        override fun evaluate(args: EvaluateArguments): CompletableFuture<EvaluateResponse> {
            evaluations += args
            if (args.expression.startsWith("dlv config")) configAndResume += "config:${args.expression.substringAfterLast(' ')}"
            return CompletableFuture.completedFuture(EvaluateResponse().apply { result = if (args.expression.startsWith("dlv config")) "Updated" else "37" })
        }
        override fun setVariable(args: SetVariableArguments): CompletableFuture<SetVariableResponse> { assignments += args; return CompletableFuture.completedFuture(SetVariableResponse().apply { value = args.value }) }
        override fun scopes(args: ScopesArguments): CompletableFuture<ScopesResponse> {
            scopeRequests++
            if (!recoverGlobals) return CompletableFuture.completedFuture(ScopesResponse().apply { scopes = emptyArray() })
            return if (scopeRequests == 1) CompletableFuture.failedFuture(ResponseErrorException(ResponseError(-32001, "Unable to list globals", "controlled globals failure"))) else retry
        }
        override fun variables(args: VariablesArguments): CompletableFuture<VariablesResponse> { variableRequests += args.variablesReference; return CompletableFuture.completedFuture(VariablesResponse().apply { variables = emptyArray() }) }
        override fun disconnect(args: DisconnectArguments) = done()
        private fun done(): CompletableFuture<Void> = CompletableFuture.completedFuture(null)
    }

    private fun <T> await(future: CompletableFuture<T>): T { waitFor { future.isDone }; return future.get(1, TimeUnit.SECONDS) }
    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12)
        while (!condition() && System.nanoTime() < deadline) { UIUtil.dispatchAllInvocationEvents(); JavaThread.sleep(5) }
        assertTrue("The actual DAP client did not finish its public operation", condition())
    }
    private fun defaultValue(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false; java.lang.Integer.TYPE -> 0; java.lang.Long.TYPE -> 0L; java.lang.Float.TYPE -> 0f; java.lang.Double.TYPE -> 0.0
        else -> if (type.isArray) java.lang.reflect.Array.newInstance(type.componentType, 0) else null
    }
}
