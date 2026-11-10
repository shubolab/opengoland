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
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.testFramework.LightPlatformTestCase
import com.intellij.util.ui.UIUtil
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugProcessStarter
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebugSessionListener
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator
import com.intellij.xdebugger.frame.XExecutionStack.XStackFrameContainer
import com.intellij.xdebugger.frame.XStackFrame
import com.intellij.xdebugger.frame.XValue
import com.intellij.xdebugger.impl.XDebugSessionImpl
import com.intellij.xdebugger.impl.actions.XDebuggerActions
import com.intellij.xdebugger.impl.ui.DebuggerUIUtil
import com.redhat.devtools.lsp4ij.dap.DAPDebugProcess
import com.redhat.devtools.lsp4ij.dap.DAPDebugRunner
import com.redhat.devtools.lsp4ij.dap.DAPServerReadyTracker
import com.redhat.devtools.lsp4ij.dap.DebugMode
import com.redhat.devtools.lsp4ij.dap.TransportStreams
import com.redhat.devtools.lsp4ij.dap.client.DAPClient
import com.redhat.devtools.lsp4ij.dap.client.DAPExecutionStack
import com.redhat.devtools.lsp4ij.dap.client.DAPSuspendContext
import com.redhat.devtools.lsp4ij.dap.configurations.DAPCommandLineState
import com.redhat.devtools.lsp4ij.dap.descriptors.DebugAdapterDescriptor
import com.redhat.devtools.lsp4ij.dap.descriptors.ServerReadyConfig
import com.redhat.devtools.lsp4ij.settings.ServerTrace
import dev.openidea.goide.lang.GoFileType
import org.eclipse.lsp4j.debug.Capabilities
import org.eclipse.lsp4j.debug.ConfigurationDoneArguments
import org.eclipse.lsp4j.debug.ContinueArguments
import org.eclipse.lsp4j.debug.ContinueResponse
import org.eclipse.lsp4j.debug.DisconnectArguments
import org.eclipse.lsp4j.debug.EvaluateArguments
import org.eclipse.lsp4j.debug.EvaluateResponse
import org.eclipse.lsp4j.debug.InitializeRequestArguments
import org.eclipse.lsp4j.debug.NextArguments
import org.eclipse.lsp4j.debug.PauseArguments
import org.eclipse.lsp4j.debug.ScopesArguments
import org.eclipse.lsp4j.debug.ScopesResponse
import org.eclipse.lsp4j.debug.SetBreakpointsArguments
import org.eclipse.lsp4j.debug.SetBreakpointsResponse
import org.eclipse.lsp4j.debug.StackFrame
import org.eclipse.lsp4j.debug.StackTraceArguments
import org.eclipse.lsp4j.debug.StackTraceResponse
import org.eclipse.lsp4j.debug.StoppedEventArguments
import org.eclipse.lsp4j.debug.ThreadsResponse
import org.eclipse.lsp4j.debug.launch.DSPLauncher
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient
import org.eclipse.lsp4j.debug.services.IDebugProtocolServer
import java.lang.reflect.Proxy
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
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
import java.lang.Thread as JavaThread
import org.eclipse.lsp4j.debug.Thread as DapThread

/** Real XDebugSessionImpl, DAP process/client/stacks and LSP4J wire; only the peer and console are controlled. */
class GoDapSessionControlTest : LightPlatformTestCase() {
    override fun runInDispatchThread() = false

    fun testRegisteredPauseActionEnablesWhileRunningAndReachesTheRealPeer() = Fixture().use { f ->
        f.ready()
        val action = requireNotNull(ActionManager.getInstance().getAction(XDebuggerActions.PAUSE))
        val event = pauseEvent(f)
        action.update(event)
        assertTrue("The registered native Pause action must be visible for Go Debug", event.presentation.isVisible)
        assertTrue("The registered native Pause action must be enabled while Go runs", event.presentation.isEnabled)
        f.peer.stopWithoutThreadOnPause = true
        edt { action.actionPerformed(event) }
        waitFor { f.session.isPaused }
        assertEquals("The actual registered action must send one Pause request", 1, f.peer.pauses.size)
        assertEquals(A, (f.session.currentExecutionStack as DAPExecutionStack).threadId)
        assertNotNull(f.session.currentStackFrame)
    }

    fun testRegisteredPauseActionDisablesForPausedAndStoppedSessions() = Fixture().use { f ->
        f.stopAtA()
        val action = requireNotNull(ActionManager.getInstance().getAction(XDebuggerActions.PAUSE))
        val paused = pauseEvent(f)
        action.update(paused)
        assertTrue("Pause stays available in the native Go toolbar", paused.presentation.isVisible)
        assertFalse("A paused Go session cannot be paused again", paused.presentation.isEnabled)
        val stopped = pauseEvent(f)
        edt { f.session.stop() }
        waitFor { f.session.isStopped }
        action.update(stopped)
        assertFalse("A stopped Go session cannot be paused", stopped.presentation.isEnabled)
        assertEmpty(f.peer.pauses)
    }

    fun testRegisteredPauseActionRemainsHiddenForANonDebugClient() = Fixture(debug = false).use { f ->
        f.ready()
        val action = requireNotNull(ActionManager.getInstance().getAction(XDebuggerActions.PAUSE))
        val event = pauseEvent(f)
        action.update(event)
        assertFalse(event.presentation.isVisible)
        assertFalse(event.presentation.isEnabled)
        assertEmpty(f.peer.pauses)
    }

    private fun pauseEvent(f: Fixture): AnActionEvent {
        // Match selecting the real debug session's tab before invoking a global
        // action. Resolve its native session proxy through the public UI helper.
        edt { f.session.activateSession(false) }
        val context = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .build()
        return AnActionEvent.createFromDataContext(ActionPlaces.DEBUGGER_TOOLBAR, Presentation(), context).also {
            // Split debugger session data can be mirrored across the frontend;
            // the platform session ID, rather than DTO object identity, binds it.
            assertEquals(f.session.id, requireNotNull(DebuggerUIUtil.getSessionProxy(it)).id)
        }
    }

    fun testConnectedRealSessionCanStepItsInitiallyStoppedThread() = Fixture().use { f ->
        val context = f.stopAtA()
        assertEquals(A, context.threadId)
        assertEquals(A, (f.session.currentExecutionStack as DAPExecutionStack).threadId)
        edt { f.session.stepOver(false) }
        waitFor { f.peer.nexts.isNotEmpty() }
        assertEquals(A, f.peer.nexts.single().threadId)
    }

    fun testStandardSessionPauseDispatchesOneDapPauseWhileRunning() = Fixture().use { f ->
        f.ready()
        assertFalse(f.session.isPaused)
        assertFalse(f.session.isStopped)
        assertTrue(f.process.checkCanPerformCommands())
        val previous = f.threadLookupEdt.size
        edt { f.session.pause() }
        awaitCondition(2_000) { f.peer.pauses.isNotEmpty() }
        assertEquals("The actual standard session Pause must reach the real DAP peer", 1, f.peer.pauses.size)
        assertTrue(f.threadLookupEdt.size > previous)
        assertFalse("Pause RPC lookup must not write to the socket on the EDT", f.threadLookupEdt.drop(previous).any { it })
    }

    fun testFirstColdBSelectionStepsTheSelectedThreadAndEvaluatesItsFrame() = Fixture().use { f ->
        val context = f.stopAtA()
        val b = f.stack(context, B)
        assertNull("This must exercise B's first uncached stack", b.topFrame)
        val frames = f.load(b)
        f.select(b, frames.first())
        f.assertWatchFrame(B_FRAME)
        assertEquals(B, (f.session.currentExecutionStack as DAPExecutionStack).threadId)
        println("COLD_B session=${(f.session.currentExecutionStack as DAPExecutionStack).threadId} context=${context.threadId}")
        edt { f.session.stepOver(false) }
        waitFor { f.peer.nexts.isNotEmpty() }
        assertEquals("Step must use the selected cold goroutine", B, f.peer.nexts.single().threadId)
    }

    fun testFirstColdBSelectionContinuesTheSelectedThread() = Fixture().use { f ->
        val context = f.stopAtA()
        val b = f.stack(context, B)
        assertNull(b.topFrame)
        f.select(b, f.load(b).first())
        edt { f.session.resume() }
        waitFor { f.peer.continues.isNotEmpty() }
        assertEquals("Continue must use the selected cold goroutine", B, f.peer.continues.single().threadId)
    }

    fun testWarmBSelectionControlStepsB() = Fixture().use { f ->
        val context = f.stopAtA()
        val b = f.stack(context, B)
        f.load(b)
        println("WARM_AFTER_FIRST context=${context.threadId} selected=${(f.session.currentExecutionStack as? DAPExecutionStack)?.threadId}")
        val frames = f.load(b)
        println("WARM_AFTER_SECOND context=${context.threadId} selected=${(f.session.currentExecutionStack as? DAPExecutionStack)?.threadId}")
        f.select(b, frames.first())
        println("WARM_AFTER_SELECT context=${context.threadId} selected=${(f.session.currentExecutionStack as? DAPExecutionStack)?.threadId}")
        assertEquals(B, (f.session.currentExecutionStack as DAPExecutionStack).threadId)
        edt { f.session.stepOver(false) }
        waitFor { f.peer.nexts.isNotEmpty() }
        assertEquals(B, f.peer.nexts.single().threadId)
    }

    fun testHeldColdBReplyCannotHijackASelection() = Fixture().use { f ->
        val context = f.stopAtA()
        val a = f.stack(context, A)
        val b = f.stack(context, B)
        f.peer.holdB = true
        val result = f.loadAsync(b)
        waitFor { f.peer.stackRequests.count { it.threadId == B } == 1 }
        f.select(a, requireNotNull(a.topFrame))
        f.peer.heldB.complete(f.peer.response(B))
        await(result)
        assertEquals(A, (f.session.currentExecutionStack as DAPExecutionStack).threadId)
        edt { f.session.stepOver(false) }
        waitFor { f.peer.nexts.isNotEmpty() }
        assertEquals("An unselected delayed frame reply must not change execution control", A, f.peer.nexts.single().threadId)
    }

    fun testUnselectedColdAndWarmCacheLoadsNeverChangeSelection() = Fixture().use { f ->
        val context = f.stopAtA()
        val b = f.stack(context, B)
        assertEquals(1, f.load(b).size)
        assertEquals(1, f.load(b).size)
        assertEquals("Loading a stack alone must not select it", A, context.threadId)
        assertEquals(A, (f.session.currentExecutionStack as DAPExecutionStack).threadId)
        edt { f.session.stepOver(false) }
        waitFor { f.peer.nexts.isNotEmpty() }
        assertEquals(A, f.peer.nexts.single().threadId)
    }

    fun testPauseAckAloneDoesNotInventAPausedSession() = Fixture().use { f ->
        f.ready()
        edt { f.session.pause() }
        waitFor { f.peer.pauses.isNotEmpty() }
        assertFalse(f.session.isPaused)
        assertNull(f.session.suspendContext)
        assertTrue(setOf(A, B).contains(f.peer.pauses.single().threadId))
    }

    fun testAllThreadsStoppedWithoutThreadIdBuildsRealFrames() = Fixture().use { f ->
        f.ready()
        f.peer.stopWithoutThreadOnPause = true
        edt { f.session.pause() }
        waitFor { f.session.isPaused && f.session.suspendContext is DAPSuspendContext }
        assertEquals(1, f.peer.pauses.size)
        val context = f.session.suspendContext as DAPSuspendContext
        assertEquals(A, context.threadId)
        assertEquals(A, f.peer.stackRequests.last().threadId)
        assertNotNull(context.activeExecutionStack?.topFrame)
    }

    fun testFailedPauseCannotInventAPausedSession() = Fixture().use { f ->
        f.ready()
        f.peer.failPause = true
        edt { f.session.pause() }
        waitFor { f.peer.pauses.isNotEmpty() }
        assertFalse(awaitCondition(250) { f.session.isPaused })
        assertNull(f.session.suspendContext)
    }

    fun testStopCancelsPendingPauseThreadLookupBeforeItCanDispatch() = Fixture().use { f ->
        f.ready()
        f.peer.holdThreads = true
        val previous = f.peer.threadRequests
        edt { f.session.pause() }
        waitFor { f.peer.threadRequests > previous }
        edt { f.session.stop() }
        f.peer.heldThreads.complete(f.peer.threadResponse())
        assertFalse(awaitCondition(300) { f.peer.pauses.isNotEmpty() })
        assertTrue(f.session.isStopped)
    }

    fun testNewStoppedEventCancelsAnOlderPendingPauseLookup() = Fixture().use { f ->
        f.ready()
        f.peer.holdNextThreads = true
        val previous = f.peer.threadRequests
        edt { f.session.pause() }
        waitFor { f.peer.threadRequests > previous }
        f.stopAtA()
        f.peer.heldThreads.complete(f.peer.threadResponse())
        assertFalse(awaitCondition(300) { f.peer.pauses.isNotEmpty() })
        assertTrue(f.session.isPaused)
        assertEquals(A, (f.session.currentExecutionStack as DAPExecutionStack).threadId)
    }

    fun testDirectProcessStopSynchronouslyCancelsAPendingPauseLookup() = Fixture().use { f ->
        f.ready()
        f.peer.holdNextThreads = true
        val previous = f.peer.threadRequests
        edt { f.session.pause() }
        waitFor { f.peer.threadRequests > previous }
        f.process.stop()
        f.peer.heldThreads.complete(f.peer.threadResponse())
        assertFalse(awaitCondition(300) { f.peer.pauses.isNotEmpty() })
        waitFor { f.session.isStopped }
    }

    fun testNewWireStopBetweenResumeCaptureAndCommandRejectsTheOldSelection() = Fixture().use { f ->
        val old = f.stopAtA()
        val b = f.stack(old, B)
        f.select(b, f.load(b).first())
        f.installWireStopAfterResumeCapture()
        edt { f.session.stepOver(false) }
        waitFor { f.session.isPaused && f.session.suspendContext !== old }
        assertFalse("An asynchronously superseded capture must not issue Step on the old thread",
            awaitCondition(750) { f.peer.nexts.isNotEmpty() })
        assertEquals(A, (f.session.currentExecutionStack as DAPExecutionStack).threadId)
    }

    fun testPauseAckKeepsItsGateUntilRealPausedFramesArePublished() = Fixture().use { f ->
        f.ready()
        f.peer.stopWithoutThreadOnPause = true
        f.peer.holdA = true
        edt { f.session.pause() }
        waitFor { f.peer.pauses.size == 1 && f.peer.stackRequests.any { it.threadId == A } }
        f.pauseAckBarrier()
        assertFalse(f.session.isPaused)
        try {
            edt { f.session.pause() }
            assertFalse(awaitCondition(250) { f.peer.pauses.size > 1 })
        } finally {
            // Preserve complete raw response pairs even on the expected old-code failure.
            f.peer.heldA.complete(f.peer.response(A))
        }
        waitFor { f.session.isPaused }
        assertEquals(1, f.peer.pauses.size)
    }

    fun testColdStackReplyAfterResumeCannotPopulateItsOldContext() = Fixture().use { f ->
        val context = f.stopAtA()
        val b = f.stack(context, B)
        f.peer.holdB = true
        val result = f.loadAsync(b)
        waitFor { f.peer.stackRequests.any { it.threadId == B } }
        edt { f.session.resume() }
        waitFor { f.peer.continues.isNotEmpty() }
        f.peer.heldB.complete(f.peer.response(B))
        assertFalse(awaitCondition(300) { result.isDone || b.topFrame != null })
        assertNull(f.session.suspendContext)
    }

    fun testColdStackReplyCannotMutateANewerSuspension() = Fixture().use { f ->
        val old = f.stopAtA()
        val b = f.stack(old, B)
        f.peer.holdB = true
        val result = f.loadAsync(b)
        waitFor { f.peer.stackRequests.any { it.threadId == B } }
        f.peer.client.stopped(StoppedEventArguments().apply { reason = "pause"; threadId = A; allThreadsStopped = true })
        waitFor { f.session.suspendContext !== old && f.session.isPaused }
        val current = f.session.suspendContext
        f.peer.heldB.complete(f.peer.response(B))
        assertFalse(awaitCondition(300) { result.isDone || b.topFrame != null })
        assertSame(current, f.session.suspendContext)
        assertEquals(A, (f.session.currentExecutionStack as DAPExecutionStack).threadId)
    }

    fun testClientDisposalRejectsItsLateColdStackReply() = Fixture().use { f ->
        val context = f.stopAtA()
        val b = f.stack(context, B)
        f.peer.holdB = true
        val result = f.loadAsync(b)
        waitFor { f.peer.stackRequests.any { it.threadId == B } }
        f.process.dispose()
        f.peer.heldB.complete(f.peer.response(B))
        assertFalse(awaitCondition(300) { result.isDone || b.topFrame != null })
    }

    fun testPauseDeadlineReportsAClearErrorWithoutInventingSuspension() = Fixture().use { f ->
        f.ready()
        f.peer.holdPause = true
        try {
            edt { f.session.pause() }
            waitFor { f.peer.pauses.size == 1 }
            waitFor { f.consoleMessages.any { it.startsWith("Pause failed:") } }
            val message = f.consoleMessages.single { it.startsWith("Pause failed:") }
            assertTrue("Timeout must explain its bounded failure, actual: $message", message.contains("within 5 seconds"))
            assertFalse(f.session.isPaused)
            assertNull(f.session.suspendContext)
        } finally {
            f.peer.heldPause.complete(null)
        }
    }

    fun testHeldPauseAckStandardStopCancelsProtocolOnlyOffTheEdt() = assertHeldPauseCancellation { it.session.stop() }

    fun testHeldPauseAckDirectStopCancelsProtocolOnlyOffTheEdt() = assertHeldPauseCancellation { it.process.stop() }

    fun testHeldPauseAckDisposalCancelsProtocolOnlyOffTheEdt() = assertHeldPauseCancellation { it.process.dispose() }

    private fun assertHeldPauseCancellation(stop: (Fixture) -> Unit) = Fixture().use { f ->
        f.ready()
        f.peer.holdPause = true
        f.peer.holdDisconnect = true
        try {
            edt { f.session.pause() }
            waitFor { f.peer.pauses.size == 1 }
            f.awaitPendingPauseRegistration()
            val before = f.clientWrites.size
            edt { stop(f) }
            waitFor { f.clientWrites.drop(before).any { it.second.contains("cancel", ignoreCase = true) } }
            val cancellation = f.clientWrites.drop(before).filter { it.second.contains("cancel", ignoreCase = true) }
            assertFalse("Raw LSP4J cancellation must not write or flush on the EDT", cancellation.any { it.first })
        } finally {
            f.peer.heldPause.complete(null)
            f.peer.heldDisconnect.complete(null)
        }
    }

    fun testStandardStepOverSocketWriteRunsOffTheEdt() = assertControlWriter("next") { it.session.stepOver(false) }

    fun testStandardContinueSocketWriteRunsOffTheEdt() = assertControlWriter("continue") { it.session.resume() }

    fun testHeldActualStepWriterDoesNotBlockStandardStepReturnOrEdtStop() = Fixture().use { f ->
        f.stopAtA()
        f.holdNextWrite = true
        val stepReturned = posted { f.session.stepOver(false) }
        var stopReturned: CompletableFuture<Unit>? = null
        var stepWasResponsive = false
        var stopWasResponsive = false
        try {
            assertTrue("The actual owned socket writer must reach the held next payload", f.writerEntered.await(3, TimeUnit.SECONDS))
            stopReturned = posted { f.session.stop() }
            // These waits are on the test worker and deliberately do not pump the EDT.
            stepWasResponsive = completedWithin(stepReturned, 1_000)
            stopWasResponsive = completedWithin(stopReturned, 1_000)
            println("HELD_ACTUAL_WRITER stepReturned=$stepWasResponsive edtStopReturned=$stopWasResponsive")
        } finally {
            // Release even when an assertion fails; never leave an SDK thread held.
            f.writerRelease.countDown()
            stepReturned.get(5, TimeUnit.SECONDS)
            stopReturned?.get(5, TimeUnit.SECONDS)
        }
        assertTrue("Standard Step must return while its actual socket writer is held", stepWasResponsive)
        assertTrue("EDT Stop must not wait for the protocol monitor while the writer is held", stopWasResponsive)
    }

    fun testDirectProcessDisposeDisconnectSocketWriteRunsOffTheEdt() = Fixture().use { f ->
        f.ready()
        val before = f.clientWrites.size
        edt { f.process.dispose() }
        waitFor { f.clientWrites.drop(before).any { it.second.contains("\"command\":\"disconnect\"") } }
        val writes = f.clientWrites.drop(before).filter { it.second.contains("\"command\":\"disconnect\"") }
        assertEquals("One real disconnect must be sent for the owned attached process", 1, writes.size)
        println("ACTUAL_DISPOSE_WRITE edt=${writes.single().first} payload=${writes.single().second}")
        assertFalse("Direct process.dispose must not send terminal RPC on the EDT", writes.any { it.first })
    }


    private fun completedWithin(future: CompletableFuture<*>, millis: Long): Boolean = try {
        future.get(millis, TimeUnit.MILLISECONDS); true
    } catch (_: java.util.concurrent.TimeoutException) { false }

    private fun posted(action: () -> Unit): CompletableFuture<Unit> {
        val future = CompletableFuture<Unit>()
        ApplicationManager.getApplication().invokeLater {
            try { WriteIntentReadAction.run { action() }; future.complete(Unit) }
            catch (failure: Throwable) { future.completeExceptionally(failure) }
        }
        return future
    }

    private fun assertControlWriter(command: String, action: (Fixture) -> Unit) = Fixture().use { f ->
        val context = f.stopAtA()
        assertEquals(A, context.threadId)
        val before = f.clientWrites.size
        edt { action(f) }
        waitFor { if (command == "next") f.peer.nexts.isNotEmpty() else f.peer.continues.isNotEmpty() }
        val writes = f.clientWrites.drop(before).filter { it.second.contains("\"command\":\"$command\"") }
        assertEquals("One actual LSP4J socket payload must carry the standard command", 1, writes.size)
        println("ACTUAL_CONTROL_WRITE command=$command edt=${writes.single().first} payload=${writes.single().second}")
        assertFalse("Standard $command must not write to the socket on the EDT", writes.any { it.first })
    }

    private inner class Fixture(private val debug: Boolean = true) : AutoCloseable {
        val peer = Peer()
        val clientWrites = CopyOnWriteArrayList<Pair<Boolean, String>>()
        @Volatile var holdNextWrite = false
        val writerEntered = java.util.concurrent.CountDownLatch(1)
        val writerRelease = java.util.concurrent.CountDownLatch(1)
        val consoleMessages = CopyOnWriteArrayList<String>()
        val threadLookupEdt = CopyOnWriteArrayList<Boolean>()
        private val workers = Executors.newCachedThreadPool { JavaThread(it, "Go-session-control-peer").apply { isDaemon = true } }
        private val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        private val accepted = CompletableFuture<Socket>()
        private val received = ByteArrayOutputStream()
        private val sent = ByteArrayOutputStream()
        private var listening: Future<Void>? = null
        private val client = CompletableFuture<GoDapClient>()
        private val handler = NopProcessHandler()
        private val console = Proxy.newProxyInstance(ConsoleView::class.java.classLoader, arrayOf(ConsoleView::class.java)) { _, method, arguments ->
            when (method.name) {
                "getComponent", "getPreferredFocusableComponent" -> JPanel()
                "print" -> { consoleMessages += arguments[0] as String; null }
                else -> defaultValue(method.returnType)
            }
        } as ConsoleView
        val session: XDebugSessionImpl
        lateinit var process: DAPDebugProcess
            private set

        init {
            recordRuntimeOrigins()
            workers.submit {
                try {
                    val socket = listener.accept(); accepted.complete(socket)
                    val input = socket.getInputStream()
                    val output = socket.getOutputStream()
                    val tracedInput = object : InputStream() {
                        override fun read(): Int = input.read().also { if (it >= 0) synchronized(received) { received.write(it) } }
                        override fun read(bytes: ByteArray, offset: Int, length: Int): Int = input.read(bytes, offset, length).also {
                            if (it > 0) synchronized(received) { received.write(bytes, offset, it) }
                        }
                    }
                    val tracedOutput = object : OutputStream() {
                        override fun write(value: Int) { output.write(value); synchronized(sent) { sent.write(value) } }
                        override fun write(bytes: ByteArray, offset: Int, length: Int) {
                            output.write(bytes, offset, length); synchronized(sent) { sent.write(bytes, offset, length) }
                        }
                        override fun flush() = output.flush()
                    }
                    val launcher = DSPLauncher.createServerLauncher(peer, tracedInput, tracedOutput, workers) { it }
                    peer.client = launcher.remoteProxy
                    listening = launcher.startListening()
                } catch (failure: Exception) { client.completeExceptionally(failure) }
            }
            val executor = DefaultDebugExecutor.getDebugExecutorInstance()
            val profile = object : RunProfile {
                override fun getName() = "Go session control owned peer"
                override fun getIcon(): Icon? = null
                override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState? = null
            }
            val environment = ExecutionEnvironmentBuilder(project, executor).runProfile(profile).runner(DAPDebugRunner()).build()
            val options = object : RunConfigurationOptions() {}
            val descriptor = object : DebugAdapterDescriptor(options, environment, null) {
                override fun getId() = "opengoland-session-control-test"
                override fun getFileType() = GoFileType.INSTANCE
                override fun getDebugMode() = DebugMode.ATTACH
                override fun getDapParameters() = mapOf<String, Any>("mode" to "local")
                override fun getServerReadyConfig(debugMode: DebugMode) = ServerReadyConfig("127.0.0.1", listener.localPort)
                override fun startServer(): ProcessHandler = error("The controlled peer must not launch any adapter process")
                override fun createClient(process: DAPDebugProcess, parameters: MutableMap<String, Any>, isDebug: Boolean,
                    mode: DebugMode, trace: ServerTrace, parent: DAPClient?): DAPClient {
                    traceClientTransport(process)
                    return object : GoDapClient(process, parameters, isDebug, mode, trace, parent) {
                        override fun getThreads(refreshThreads: Boolean): CompletableFuture<Array<DapThread>> {
                            threadLookupEdt += ApplicationManager.getApplication().isDispatchThread
                            return super.getThreads(refreshThreads)
                        }
                    }.also { client.complete(it) }
                }
            }
            val state = DAPCommandLineState(descriptor, options, environment)
            handler.startNotify()
            DAPServerReadyTracker(descriptor.getServerReadyConfig(DebugMode.ATTACH), DebugMode.ATTACH, handler)
            session = edt {
                XDebuggerManager.getInstance(project).newSessionBuilder(object : XDebugProcessStarter() {
                    override fun start(session: XDebugSession): XDebugProcess =
                        DAPDebugProcess(state, session, DefaultExecutionResult(console, handler), debug).also { process = it }
                }).environment(environment).showTab(false).startSession().session as XDebugSessionImpl
            }
        }

        private fun traceClientTransport(process: DAPDebugProcess) {
            // Decorate only the owned real transport before DAPClient constructs
            // its actual LSP4J launcher. No session/control implementation is replaced.
            val original = process.streamsSupplier
            val field = DAPDebugProcess::class.java.getDeclaredField("streamsSupplier").apply { isAccessible = true }
            field.set(process, java.util.function.Supplier {
                val actual = original.get()
                val output = object : OutputStream() {
                    override fun write(value: Int) {
                        clientWrites += ApplicationManager.getApplication().isDispatchThread to value.toChar().toString()
                        actual.out.write(value)
                    }
                    override fun write(bytes: ByteArray, offset: Int, length: Int) {
                        val text = String(bytes, offset, length, Charsets.UTF_8)
                        clientWrites += ApplicationManager.getApplication().isDispatchThread to text
                        if (holdNextWrite && text.contains("\"command\":\"next\"")) {
                            writerEntered.countDown()
                            check(writerRelease.await(10, TimeUnit.SECONDS)) { "Owned writer release deadline expired" }
                        }
                        actual.out.write(bytes, offset, length)
                    }
                    override fun flush() = actual.out.flush()
                }
                object : TransportStreams.DefaultTransportStreams(actual.`in`, output) {
                    override fun close() = actual.close()
                }
            })
        }

        fun awaitPendingPauseRegistration() {
            val field = DAPDebugProcess::class.java.getDeclaredField("pauseOperation").apply { isAccessible = true }
            waitFor {
                val operation = (field.get(process) as java.util.concurrent.atomic.AtomicReference<*>).get()
                if (operation == null) false else {
                    val requests = operation.javaClass.getDeclaredField("requests").apply { isAccessible = true }.get(operation) as List<*>
                    requests.size >= 2 && requests.filterIsInstance<CompletableFuture<*>>().any { !it.isDone }
                }
            }
        }

        fun pauseAckBarrier() {
            waitFor {
                synchronized(sent) { sent.toString(java.nio.charset.StandardCharsets.UTF_8).contains("\"command\":\"pause\"") }
            }
            // This response follows the already-written Pause ACK on the same
            // real connection, so the client reader has processed that ACK.
            await(actualClient().getThreads(true))
        }

        fun installWireStopAfterResumeCapture() = edt {
            session.addSessionListener(object : XDebugSessionListener {
                override fun beforeSessionResume() {
                    val before = actualClient().suspensionGeneration
                    // The real process listener already captured B. Hold this EDT
                    // callback briefly while the actual peer delivers a stopped
                    // event on LSP4J's independent reader, without pumping UI.
                    workers.submit {
                        peer.client.stopped(StoppedEventArguments().apply { reason = "pause"; threadId = A; allThreadsStopped = true })
                    }
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                    while (actualClient().suspensionGeneration == before && System.nanoTime() < deadline) JavaThread.sleep(1)
                    assertTrue("The actual transport event must supersede the captured generation",
                        actualClient().suspensionGeneration > before)
                }
            }, testRootDisposable)
        }

        fun actualClient(): GoDapClient = await(client)
        fun ready() {
            await(client); await(peer.ready)
            assertNotNull(await(client).debugProtocolServer)
        }

        fun stopAtA(): DAPSuspendContext {
            ready()
            peer.client.stopped(StoppedEventArguments().apply { reason = "breakpoint"; threadId = A; allThreadsStopped = true })
            waitFor { session.isPaused && session.suspendContext is DAPSuspendContext }
            return session.suspendContext as DAPSuspendContext
        }

        fun stack(context: DAPSuspendContext, id: Int) = context.executionStacks.filterIsInstance<DAPExecutionStack>().single { it.threadId == id }
        fun loadAsync(stack: DAPExecutionStack): CompletableFuture<List<XStackFrame>> {
            val result = CompletableFuture<List<XStackFrame>>()
            edt {
                stack.computeStackFrames(0, object : XStackFrameContainer {
                    override fun addStackFrames(frames: List<XStackFrame>, last: Boolean) { if (last) result.complete(frames) }
                    override fun errorOccurred(errorMessage: String) { result.completeExceptionally(AssertionError(errorMessage)) }
                    override fun isObsolete() = false
                })
            }
            return result
        }
        fun load(stack: DAPExecutionStack) = await(loadAsync(stack))
        fun select(stack: DAPExecutionStack, frame: XStackFrame) = edt {
            val context = session.suspendContext as? DAPSuspendContext
            println("SELECT_BEFORE target=${stack.threadId} context=${context?.threadId}")
            session.setCurrentStackFrame(stack, frame, true)
            println("SELECT_AFTER target=${stack.threadId} context=${context?.threadId}")
            assertSame(stack, session.currentExecutionStack)
            assertSame(frame, session.currentStackFrame)
        }
        fun assertWatchFrame(id: Int) {
            val result = CompletableFuture<XValue>()
            edt {
                requireNotNull(session.currentStackFrame?.evaluator).evaluate("marker", object : XDebuggerEvaluator.XEvaluationCallback {
                    override fun evaluated(value: XValue) { result.complete(value) }
                    override fun errorOccurred(errorMessage: String) { result.completeExceptionally(AssertionError(errorMessage)) }
                }, null)
            }
            await(result)
            assertEquals(id, peer.evaluations.last().frameId)
        }

        override fun close() {
            try {
                edt { if (!session.isStopped) session.stop() }
                // The real disconnect/client disposal is the response barrier;
                // do not truncate our controlled peer's protocol evidence.
                if (client.isDone && !client.isCompletedExceptionally) {
                    waitFor { client.getNow(null)?.debugProtocolServer == null }
                }
            } finally {
                handler.destroyProcess()
                waitFor { handler.isProcessTerminated }
                if (client.isDone && !client.isCompletedExceptionally) client.getNow(null)?.dispose()
                process.dispose()
                accepted.getNow(null)?.close(); listener.close(); listening?.cancel(true)
                workers.shutdownNow()
                assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS))
                System.getProperty("opengoland.debugControlEvidence")?.let { destination ->
                    val directory = Path.of(destination)
                    Files.createDirectories(directory)
                    val name = this@GoDapSessionControlTest.name
                    Files.write(directory.resolve("$name-client-to-peer.dap"), synchronized(received) { received.toByteArray() })
                    Files.write(directory.resolve("$name-peer-to-client.dap"), synchronized(sent) { sent.toByteArray() })
                    val writes = clientWrites.map { mapOf("edt" to it.first, "text" to it.second) }
                    Files.writeString(directory.resolve("$name-client-write-threads.json"),
                        com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(writes) + "\n")
                }
                edt { UIUtil.dispatchAllInvocationEvents() }
            }
        }
    }

    private class Peer : IDebugProtocolServer {
        lateinit var client: IDebugProtocolClient
        val ready = CompletableFuture<Unit>()
        val pauses = CopyOnWriteArrayList<PauseArguments>()
        val nexts = CopyOnWriteArrayList<NextArguments>()
        val continues = CopyOnWriteArrayList<ContinueArguments>()
        val evaluations = CopyOnWriteArrayList<EvaluateArguments>()
        val stackRequests = CopyOnWriteArrayList<StackTraceArguments>()
        val heldB = CompletableFuture<StackTraceResponse>()
        val heldA = CompletableFuture<StackTraceResponse>()
        val heldPause = CompletableFuture<Void>()
        val heldDisconnect = CompletableFuture<Void>()
        @Volatile var holdPause = false
        @Volatile var holdDisconnect = false
        @Volatile var holdA = false
        @Volatile var holdB = false
        @Volatile var stopWithoutThreadOnPause = false
        @Volatile var failPause = false
        @Volatile var holdThreads = false
        @Volatile var holdNextThreads = false
        @Volatile var threadRequests = 0
        val heldThreads = CompletableFuture<ThreadsResponse>()
        override fun initialize(args: InitializeRequestArguments) = CompletableFuture.completedFuture(Capabilities().apply { supportsConfigurationDoneRequest = true })
        override fun attach(args: MutableMap<String, Any>): CompletableFuture<Void> { client.initialized(); return done() }
        override fun configurationDone(args: ConfigurationDoneArguments) = done()
        fun threadResponse() = ThreadsResponse().apply {
            threads = arrayOf(DapThread().apply { id = A; name = "goroutine A" }, DapThread().apply { id = B; name = "goroutine B" })
        }
        override fun threads(): CompletableFuture<ThreadsResponse> {
            threadRequests++
            ready.complete(Unit)
            val hold = holdThreads || holdNextThreads
            holdNextThreads = false
            return if (hold) heldThreads else CompletableFuture.completedFuture(threadResponse())
        }
        override fun setBreakpoints(args: SetBreakpointsArguments) = CompletableFuture.completedFuture(SetBreakpointsResponse().apply { breakpoints = emptyArray() })
        override fun stackTrace(args: StackTraceArguments): CompletableFuture<StackTraceResponse> {
            stackRequests += args
            return when {
                args.threadId == B && holdB -> heldB
                args.threadId == A && holdA -> heldA
                else -> CompletableFuture.completedFuture(response(args.threadId))
            }
        }
        fun response(id: Int) = StackTraceResponse().apply {
            stackFrames = arrayOf(StackFrame().apply { this.id = if (id == B) B_FRAME else A_FRAME; name = "goroutine $id"; line = 1; column = 1 })
            totalFrames = 1
        }
        override fun pause(args: PauseArguments): CompletableFuture<Void> {
            pauses += args
            if (stopWithoutThreadOnPause) client.stopped(StoppedEventArguments().apply { reason = "pause"; allThreadsStopped = true })
            return when {
                failPause -> CompletableFuture.failedFuture(IllegalStateException("owned peer rejected Pause"))
                holdPause -> heldPause
                else -> done()
            }
        }
        override fun next(args: NextArguments): CompletableFuture<Void> { nexts += args; return done() }
        override fun continue_(args: ContinueArguments): CompletableFuture<ContinueResponse> {
            continues += args
            return CompletableFuture.completedFuture(ContinueResponse().apply { allThreadsContinued = true })
        }
        override fun evaluate(args: EvaluateArguments): CompletableFuture<EvaluateResponse> {
            evaluations += args
            return CompletableFuture.completedFuture(EvaluateResponse().apply { result = if (args.frameId == B_FRAME) "202" else "101"; type = "int"; variablesReference = 0 })
        }
        override fun scopes(args: ScopesArguments) = CompletableFuture.completedFuture(ScopesResponse().apply { scopes = emptyArray() })
        override fun disconnect(args: DisconnectArguments) = if (holdDisconnect) heldDisconnect else done()
        private fun done(): CompletableFuture<Void> = CompletableFuture.completedFuture(null)
    }

    private fun recordRuntimeOrigins() {
        val path = System.getProperty("opengoland.debugControlOriginEvidence") ?: return
        assertFalse(ApplicationManager.getApplication().isDispatchThread)
        val records = listOf(DAPDebugProcess::class.java, DAPClient::class.java,
            DAPSuspendContext::class.java, DAPExecutionStack::class.java, GoDapClient::class.java, GoDapProtocol::class.java).associate { type ->
            val resource = requireNotNull(type.getResource("/" + type.name.replace('.', '/') + ".class"))
            val bytes = resource.openStream().use { it.readBytes() }
            type.name to mapOf("resource" to resource.toExternalForm(),
                "sha256" to java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)),
                "loader" to type.classLoader.javaClass.name)
        }
        val destination = Path.of(path)
        Files.createDirectories(destination.parent)
        Files.writeString(destination, com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(records) + "\n")
    }

    private fun <T> await(future: CompletableFuture<T>): T { waitFor { future.isDone }; return future.get(1, TimeUnit.SECONDS) }
    private fun waitFor(condition: () -> Boolean) {
        assertTrue("Actual SDK/DAP operation did not complete", awaitCondition(10_000, condition))
    }
    private fun awaitCondition(millis: Long, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis)
        while (!condition() && System.nanoTime() < deadline) { edt { UIUtil.dispatchAllInvocationEvents() }; JavaThread.sleep(5) }
        return condition()
    }
    private fun <T> edt(action: () -> T): T {
        var value: T? = null
        ApplicationManager.getApplication().invokeAndWait { WriteIntentReadAction.run { value = action() } }
        @Suppress("UNCHECKED_CAST") return value as T
    }
    private fun defaultValue(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false; java.lang.Integer.TYPE -> 0; java.lang.Long.TYPE -> 0L; java.lang.Float.TYPE -> 0f; java.lang.Double.TYPE -> 0.0
        else -> if (type.isArray) java.lang.reflect.Array.newInstance(type.componentType, 0) else null
    }
    companion object { private const val A = 101; private const val B = 202; private const val A_FRAME = 1011; private const val B_FRAME = 2021 }
}
