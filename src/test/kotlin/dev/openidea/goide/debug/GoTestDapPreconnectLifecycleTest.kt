package dev.openidea.goide.debug

import com.google.gson.JsonObject
import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.util.ProgressIndicatorBase
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.LightPlatformTestCase
import com.intellij.util.ui.UIUtil
import com.intellij.util.xmlb.XmlSerializerUtil
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugProcessStarter
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.impl.XDebugSessionImpl
import com.redhat.devtools.lsp4ij.dap.DAPDebugProcess
import com.redhat.devtools.lsp4ij.dap.DebugMode
import com.redhat.devtools.lsp4ij.dap.TransportStreams
import com.redhat.devtools.lsp4ij.dap.client.DAPClient
import com.redhat.devtools.lsp4ij.settings.ServerTrace
import dev.openidea.goide.process.GoOwnedTestExecutable
import dev.openidea.goide.run.GoRunConfiguration
import dev.openidea.goide.run.GoRunKind
import dev.openidea.goide.run.GoTestConfigurationType
import dev.openidea.goide.settings.GoProjectSettings
import dev.openidea.goide.settings.GoToolchain
import org.eclipse.lsp4j.debug.Capabilities
import org.eclipse.lsp4j.debug.ConfigurationDoneArguments
import org.eclipse.lsp4j.debug.DisconnectArguments
import org.eclipse.lsp4j.debug.InitializeRequestArguments
import org.eclipse.lsp4j.debug.OutputEventArguments
import org.eclipse.lsp4j.debug.ExitedEventArguments
import org.eclipse.lsp4j.debug.TerminatedEventArguments
import org.eclipse.lsp4j.debug.SetBreakpointsArguments
import org.eclipse.lsp4j.debug.SetBreakpointsResponse
import org.eclipse.lsp4j.debug.ThreadsResponse
import org.eclipse.lsp4j.debug.launch.DSPLauncher
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient
import org.eclipse.lsp4j.debug.services.IDebugProtocolServer
import java.io.InputStream
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Supplier
import kotlin.jvm.internal.CallableReference

/** Real Go Test state/events/SDK Stop; only an owned transport's constructor timing is held. */
class GoTestDapPreconnectLifecycleTest : LightPlatformTestCase() {
    override fun runInDispatchThread() = false

    fun testNativeGoTestStopDuringClientConstructionCannotInventDisconnectAcknowledgement() = preconnectStop(false)
    fun testManualTestStopDuringClientConstructionCannotInventDisconnectAcknowledgement() = preconnectStop(true)

    private fun preconnectStop(manualProfile: Boolean) {
        val peer = Fixture(holdConstructor = true, manualProfile = manualProfile).use { f ->
            f.constructorEntered.get(5, TimeUnit.SECONDS)
            edt { assertSame(f.tests.events, f.session.debugProcess.processHandler) }
            val returned = CompletableFuture<Long>()
            ApplicationManager.getApplication().invokeLater {
                val started = System.nanoTime()
                f.session.stop()
                returned.complete(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
            }
            assertTrue("Native Go Test Stop must not wait for client construction on EDT", returned.get(1, TimeUnit.SECONDS) < 500)
            waitFor { f.adapter.isProcessTerminating }
            f.constructorRelease.countDown()
            // This close is caused by the real Stop path, not by calling the
            // shutdown callback below. The descriptor's late-constructor guard
            // has to terminate its actual test client before we inspect it.
            f.clientTransportClosed.get(5, TimeUnit.SECONDS)
            val callback = requireNotNull(f.tests.disconnect)
            val actualClient = (callback as CallableReference).boundReceiver as GoTestDapClient
            val rawServer = DAPClient::class.java.getDeclaredField("debugProtocolServer").apply { isAccessible = true }.get(actualClient)
            assertNull("The actual public superclass never published a protocol endpoint", rawServer)
            assertEquals(0, f.peer.initializes.size)
            assertTrue(f.peer.launches.isEmpty())
            assertTrue(f.peer.disconnects.isEmpty())
            val shutdown = callback()
            assertSame(shutdown, callback())
            assertTrue("No endpoint and no DAP ACK must not complete shared shutdown successfully",
                shutdown.isCancelled || shutdown.isCompletedExceptionally)
            assertEquals("Cancellation0 is not a natural test success", 0, f.tests.completed.get(3, TimeUnit.SECONDS))
            edt {
                assertFalse(f.console.resultsViewer.root.isPassed)
                assertTrue(f.console.resultsViewer.root.allTests.any { it.isIgnored })
            }
            assertTrue(f.tests.output.failures().isEmpty())
            f.peer
        }
        assertEquals("No initialize after owned readers/tasks quiesce", 0, peer.initializes.size)
        assertTrue("A stopped late constructor must never launch a target", peer.launches.isEmpty())
        assertTrue(peer.disconnects.isEmpty())
    }

    fun testConnectedNativeStopKeepsOneHeldAckAndIgnoredResultForBothProfiles() {
        for (manual in listOf(false, true)) Fixture(manualProfile = manual).use { f ->
            f.ready()
            f.send("\u0016=== RUN   TestWaiting\n")
            waitFor { edt { f.console.resultsViewer.root.allTests.any { it.name == "TestWaiting" } } }
            edt { f.session.stop() }
            waitFor { f.peer.disconnects.size == 1 }
            val shutdown = f.shutdown()
            f.client().terminate()
            assertSame(shutdown, f.shutdown())
            assertFalse(shutdown.isDone)
            assertEquals(true, f.peer.disconnects.single().terminateDebuggee)
            f.send("\u0016--- PASS: TestWaiting (0.01s)\n\u0016PASS\n")
            val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(250)
            while (System.nanoTime() < until) {
                assertTrue("Real Go Test Stop must not signal its adapter before ACK", f.adapter.process.isAlive)
                assertFalse(Files.exists(f.directory.resolve("term")))
                Thread.sleep(5)
            }
            assertEquals(1, f.peer.disconnects.size)
            assertEquals(0, f.tests.completed.get(3, TimeUnit.SECONDS))
            assertNull("Stop sentinel0 must not fabricate a Go debuggee exit", f.targetExit())
            edt {
                assertFalse(f.console.resultsViewer.root.isPassed)
                assertTrue(f.console.resultsViewer.root.allTests.single { it.name == "TestWaiting" }.isIgnored)
            }
            assertTrue(f.tests.output.failures().isEmpty())
            f.peer.ack.complete(null)
            shutdown.get(3, TimeUnit.SECONDS)
            f.finishAdapterNaturally()
            assertFalse(Files.exists(f.directory.resolve("term")))
        }
    }

    fun testNaturalTerminationWithoutExitedRemainsFailedAfterAcknowledgement() = Fixture().use { f ->
        f.ready()
        f.send("\u0016=== RUN   TestUnknown\n\u0016--- PASS: TestUnknown (0.01s)\n\u0016PASS\n")
        f.peer.client.terminated(TerminatedEventArguments())
        waitFor { f.peer.disconnects.size == 1 }
        assertFalse(f.tests.completed.isDone)
        assertFalse(f.shutdown().isDone)
        assertTrue(f.adapter.process.isAlive)
        f.peer.ack.complete(null)
        assertEquals("A natural termination with no trusted exit stays failed, not user-cancelled0", 1, f.tests.completed.get(3, TimeUnit.SECONDS))
        assertNull(f.targetExit())
        edt { assertFalse(f.console.resultsViewer.root.isPassed); assertFalse(f.console.resultsViewer.root.allTests.any { it.isIgnored }) }
        assertTrue(f.tests.output.failures().isNotEmpty())
        f.finishAdapterNaturally()
    }

    fun testActualExitedFailureSurvivesRealNativeStop() = Fixture().use { f ->
        f.ready()
        f.send("\u0016=== RUN   TestFailed\n\u0016--- FAIL: TestFailed (0.01s)\n\u0016FAIL\n")
        f.peer.client.exited(ExitedEventArguments().apply { exitCode = 7 })
        waitFor { f.targetExit() == 7 }
        edt { f.session.stop() }
        waitFor { f.peer.disconnects.size == 1 }
        f.peer.ack.complete(null)
        assertEquals(7, f.tests.completed.get(3, TimeUnit.SECONDS))
        assertEquals(7, f.targetExit())
        edt { assertFalse(f.console.resultsViewer.root.isPassed); assertFalse(f.console.resultsViewer.root.allTests.single { it.name == "TestFailed" }.isIgnored) }
        assertEquals(listOf("TestFailed"), f.tests.output.failures().values.single())
        f.finishAdapterNaturally()
    }

    fun testStateProbeStopBeforePublicServerPublicationSendsOneRealDisconnectWithoutInitialize() {
        Fixture(holdConstructor = true).use { f ->
            f.constructorEntered.get(5, TimeUnit.SECONDS)
            f.StateProbe().use { probe ->
                probe.start()
                probe.entered.get(3, TimeUnit.SECONDS)
                assertNull(probe.rawServer())
                assertFalse(probe.clientIsDisposed())
                // Lower-layer state control only. C2/C4 separately exercise
                // actual normal SDK Go Test Stop rather than this direct call.
                edt { probe.client.terminate() }
                val shutdown = probe.shutdown()
                try {
                    shutdown.get(250, TimeUnit.MILLISECONDS)
                    fail("A naturally absent public-super endpoint cannot count as an ACK")
                } catch (_: TimeoutException) { }
                assertFalse(shutdown.isDone)
                probe.release.countDown()
                waitFor { probe.peer.disconnects.size == 1 }
                assertEquals(true, probe.peer.disconnects.single().terminateDebuggee)
                assertTrue(probe.peer.initializes.isEmpty())
                assertTrue(probe.peer.launches.isEmpty())
                assertFalse(shutdown.isDone)
                assertSame(shutdown, probe.shutdown())
                probe.peer.ack.complete(null)
                shutdown.get(3, TimeUnit.SECONDS)
                val connection = probe.connection.get(3, TimeUnit.SECONDS)
                waitFor { connection.isDone }
                assertTrue(connection.isCancelled)
            }
        }
    }

    fun testStateProbeMissingEndpointTimesOutAndCannotLaterInitialize() {
        Fixture(holdConstructor = true).use { f ->
            f.constructorEntered.get(5, TimeUnit.SECONDS)
            val peer = f.StateProbe().use { probe ->
                probe.start()
                probe.entered.get(3, TimeUnit.SECONDS)
                assertNull(probe.rawServer())
                edt { probe.client.terminate() }
                val shutdown = probe.shutdown()
                try { shutdown.get(4, TimeUnit.SECONDS); fail("Missing endpoint is not an acknowledged disconnect") }
                catch (failure: java.util.concurrent.ExecutionException) { assertTrue(failure.cause is TimeoutException) }
                assertTrue(shutdown.isCompletedExceptionally)
                assertNull(probe.rawServer())
                probe.release.countDown()
                val connection = probe.connection.get(3, TimeUnit.SECONDS)
                waitFor { connection.isDone }
                assertTrue(connection.isCancelled)
                assertTrue(probe.peer.initializes.isEmpty())
                assertTrue(probe.peer.launches.isEmpty())
                assertTrue(probe.peer.disconnects.isEmpty())
                probe.peer
            }
            assertTrue("No requests after probe reader/connection quiescence", peer.initializes.isEmpty() && peer.launches.isEmpty())
        }
    }

    fun testStateProbeAlreadyDisposedEndpointCannotBeReportedAsAcknowledged() {
        Fixture(holdConstructor = true).use { f ->
            f.constructorEntered.get(5, TimeUnit.SECONDS)
            f.StateProbe().use { probe ->
                assertNull(probe.rawServer())
                assertFalse(probe.clientIsDisposed())
                probe.client.dispose()
                assertTrue(probe.clientIsDisposed())
                val shutdown = probe.shutdown()
                assertTrue(shutdown.isCancelled || shutdown.isCompletedExceptionally)
                assertTrue(probe.peer.initializes.isEmpty() && probe.peer.launches.isEmpty() && probe.peer.disconnects.isEmpty())
            }
        }
    }

    private inner class Fixture(private val holdConstructor: Boolean = false, private val manualProfile: Boolean = false) : AutoCloseable {
        val root = Files.createTempDirectory("go-test-stop-").toRealPath()
        val directory = Files.createDirectories(root.resolve("project"))
        private val executable = GoOwnedTestExecutable.compile(root.resolve(GoOwnedTestExecutable.name("owned-adapter")), ADAPTER)
        private val token = UUID.randomUUID().toString()
        private val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        private val workers = Executors.newCachedThreadPool { action -> Thread(action, "Go-test-stop-owned").apply { isDaemon = true } }
        private val sockets = CopyOnWriteArrayList<Socket>()
        private val listening = CopyOnWriteArrayList<Future<Void>>()
        private val acceptedCount = AtomicInteger()
        private val suppliedCount = AtomicInteger()
        private val probePeer = CompletableFuture<Peer>()
        private val probeClients = CopyOnWriteArrayList<GoTestDapClient>()
        private val probeSessions = CopyOnWriteArrayList<GoDebugTestSession>()
        private lateinit var configuration: RunConfiguration
        private val previous = GoProjectSettings.Data().also { XmlSerializerUtil.copyBean(GoProjectSettings.get(project), it) }
        val constructorEntered = CompletableFuture<Unit>()
        val constructorRelease = CountDownLatch(1)
        val clientTransportClosed = CompletableFuture<Unit>()
        val peer = Peer()
        lateinit var state: GoDebugTestState
            private set
        lateinit var tests: GoDebugTestSession
            private set
        lateinit var adapter: OSProcessHandler
            private set
        lateinit var session: XDebugSessionImpl
            private set
        lateinit var console: SMTRunnerConsoleView
            private set
        private lateinit var process: DAPDebugProcess

        init {
            try {
            Files.writeString(directory.resolve("go.mod"), "module example.test/preconnect\n\ngo 1.26\n")
            Files.writeString(directory.resolve("probe_test.go"), "package preconnect\nimport \"testing\"\nfunc TestControlled(t *testing.T) {}\n")
            GoProjectSettings.get(project).apply {
                goExecutable = requireNotNull(GoToolchain.resolve("go", System.getProperty("opengoland.testGo")
                    ?: System.getenv("OPENGOLAND_GO_TEST").orEmpty()))
                delveExecutable = executable.toString()
                buildTags = ""; goos = ""; goarch = ""; cgoEnabled = "0"
                environment = "OWNED_DAP_PORT=" + listener.localPort + "\nOWNED_TOKEN=" + token +
                    "\nGOENV=off\nGOWORK=off\nGOTOOLCHAIN=local\nGOPROXY=off\nGOSUMDB=off\nGOTELEMETRY=off"
            }
            configuration = if (manualProfile) {
                GoDebugConfiguration(project, GoDebugConfigurationType().configurationFactories.single(), "Owned manual Debug Test").apply {
                    goOptions.mode = "test"; goOptions.program = directory.toString(); goOptions.workingDirectory = directory.toString()
                }
            } else GoRunConfiguration(project, GoTestConfigurationType().configurationFactories.single(),
                "Owned Go Test pre-connect Stop", GoRunKind.TEST).apply { parameters.workingDirectory = directory.toString() }
            val executor = DefaultDebugExecutor.getDebugExecutorInstance()
            val runner = GoDebugTestRunner()
            val environment = ExecutionEnvironmentBuilder(project, executor).runProfile(configuration).runner(runner).build()
            state = GoDebugTestState(environment, configuration)
            tests = state.testSession
            state.prepare()
            adapter = GoDebugTestSession::class.java.getDeclaredField("adapter").apply { isAccessible = true }.get(tests) as OSProcessHandler
            workers.submit {
                try {
                    while (!listener.isClosed) {
                        val socket = listener.accept(); sockets += socket
                        val endpoint = if (acceptedCount.getAndIncrement() == 0) peer else Peer().also { probePeer.complete(it) }
                        val launcher = DSPLauncher.createServerLauncher(endpoint, socket.getInputStream(), socket.getOutputStream(), workers) { it }
                        endpoint.client = launcher.remoteProxy; listening += launcher.startListening()
                    }
                } catch (failure: Exception) { if (!listener.isClosed) peer.ready.completeExceptionally(failure) }
            }
            val property = "intellij.progress.task.ignoreHeadless"
            val before = System.getProperty(property)
            try {
                System.setProperty(property, "true")
                session = edt {
                    XDebuggerManager.getInstance(project).newSessionBuilder(object : XDebugProcessStarter() {
                        override fun start(session: XDebugSession): XDebugProcess {
                            val execution = state.execute(executor, runner) as DefaultExecutionResult
                            state.installSessionActions(session, execution)
                            return object : DAPDebugProcess(state, session, execution, true) {
                                override fun getStreamsSupplier(): Supplier<TransportStreams> {
                                    val original = super.getStreamsSupplier()
                                    return Supplier {
                                        val actual = original.get()
                                        val first = suppliedCount.getAndIncrement() == 0
                                        val input = TransportStreams::class.java.getField("in").get(actual) as InputStream
                                        val traced = object : TransportStreams.DefaultTransportStreams(input, actual.out) {
                                            override fun close() {
                                                try { actual.close() } finally { if (first) clientTransportClosed.complete(Unit) }
                                            }
                                        }
                                        if (first) constructorEntered.complete(Unit)
                                        if (first && holdConstructor) check(constructorRelease.await(10, TimeUnit.SECONDS)) {
                                            "Owned constructor release deadline expired"
                                        }
                                        traced
                                    }
                                }
                            }.also { process = it }
                        }
                    }).environment(environment).showTab(false).startSession().session as XDebugSessionImpl
                }
            } finally {
                if (before == null) System.clearProperty(property) else System.setProperty(property, before)
            }
            console = session.consoleView as SMTRunnerConsoleView
            Files.writeString(directory.resolve("announce"), "owned native SDK session constructed")
            } catch (failure: Throwable) {
                try { close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                throw failure
            }
        }

        fun client() = ((requireNotNull(tests.disconnect)) as CallableReference).boundReceiver as GoTestDapClient
        fun shutdown(): CompletableFuture<Void> = requireNotNull(tests.disconnect).invoke()
        fun ready() { peer.ready.get(5, TimeUnit.SECONDS); assertNotNull(client().debugProtocolServer) }
        fun send(text: String) = peer.client.output(OutputEventArguments().apply { category = "stdout"; output = text })
        fun targetExit(): Int? {
            val lock = GoDebugTestSession::class.java.getDeclaredField("lock").apply { isAccessible = true }.get(tests)
            val value = GoDebugTestSession::class.java.getDeclaredField("targetExit").apply { isAccessible = true }
            return synchronized(lock) { value.get(tests) as? Int }
        }
        fun finishAdapterNaturally() {
            Files.writeString(directory.resolve("exit"), "owned adapter natural exit after protocol ACK")
            adapter.process.onExit().get(3, TimeUnit.SECONDS)
            assertEquals(0, adapter.process.exitValue())
            clientTransportClosed.get(3, TimeUnit.SECONDS)
        }

        /** Explicit lower-layer real-super control, not a replacement normal SDK Stop fixture. */
        inner class StateProbe : AutoCloseable {
            private val probeTests = GoDebugTestSession(configuration).also { probeSessions += it }
            val client = GoTestDapClient(process, tests.dapParameters(), true, DebugMode.LAUNCH, ServerTrace.off, null, probeTests)
                .also { probeClients += it }
            val peer = probePeer.get(3, TimeUnit.SECONDS)
            val entered = CompletableFuture<Unit>()
            val release = CountDownLatch(1)
            val connection = CompletableFuture<CompletableFuture<Void>>()
            private val gateClaimed = AtomicBoolean()
            fun start() {
                val actual = ProgressIndicatorBase()
                val indicator = Proxy.newProxyInstance(ProgressIndicator::class.java.classLoader, arrayOf(ProgressIndicator::class.java)) { _, method, args ->
                    if (method.name == "setIndeterminate" && args?.get(0) == false && gateClaimed.compareAndSet(false, true)) {
                        check(!ApplicationManager.getApplication().isDispatchThread)
                        entered.complete(Unit)
                        check(release.await(10, TimeUnit.SECONDS)) { "Owned public-super indicator gate expired" }
                    }
                    try { method.invoke(actual, *(args ?: emptyArray())) }
                    catch (failure: InvocationTargetException) { throw failure.targetException }
                } as ProgressIndicator
                workers.submit {
                    try { connection.complete(client.connectToServer(indicator)) }
                    catch (failure: Throwable) { connection.completeExceptionally(failure) }
                }
            }
            fun rawServer(): Any? = DAPClient::class.java.getDeclaredField("debugProtocolServer").apply { isAccessible = true }.get(client)
            fun clientIsDisposed() = GoDapClient::class.java.getDeclaredField("disposed").apply { isAccessible = true }.getBoolean(client)
            fun shutdown() = requireNotNull(probeTests.disconnect).invoke()
            override fun close() {
                client.terminate()
                release.countDown(); peer.ack.complete(null)
                if (connection.isDone) runCatching { connection.get(3, TimeUnit.SECONDS) }
                client.dispose()
                val pool = DAPClient::class.java.getDeclaredField("threadPool").apply { isAccessible = true }.get(client) as ExecutorService
                assertTrue(pool.awaitTermination(3, TimeUnit.SECONDS))
                Disposer.dispose(probeTests)
            }
        }

        override fun close() {
            if (this::session.isInitialized) edt { session.stop() }
            if (constructorEntered.isDone && this::adapter.isInitialized)
                waitFor { adapter.isProcessTerminating || adapter.isProcessTerminated }
            constructorRelease.countDown()
            peer.ack.complete(null)
            Files.writeString(directory.resolve("exit"), "owned fixture cleanup")
            if (this::tests.isInitialized) {
                Disposer.dispose(tests)
                tests.completed.get(5, TimeUnit.SECONDS)
            }
            if (this::process.isInitialized) process.dispose()
            if (constructorEntered.isDone && this::tests.isInitialized) {
                waitFor { tests.disconnect != null }
                clientTransportClosed.get(5, TimeUnit.SECONDS)
            }
            val callback = if (this::tests.isInitialized) tests.disconnect else null
            if (callback is CallableReference) {
                val client = callback.boundReceiver as GoTestDapClient
                client.dispose()
                val pool = DAPClient::class.java.getDeclaredField("threadPool").apply { isAccessible = true }.get(client) as ExecutorService
                assertTrue("Owned DAP client workers must finish before fixture release", pool.awaitTermination(3, TimeUnit.SECONDS))
            }
            if (this::adapter.isInitialized && adapter.process.isAlive) {
                // This exact Process was returned by this fixture's start,
                // never looked up by PID. OS command metadata can disappear
                // between isAlive and its natural exit.
                adapter.process.destroyForcibly()
                adapter.process.onExit().get(3, TimeUnit.SECONDS)
            }
            if (this::adapter.isInitialized) waitFor { adapter.isProcessTerminated }
            if (this::tests.isInitialized) {
                val converter = GoDebugTestSession::class.java.getDeclaredField("converter").apply { isAccessible = true }.get(tests) as? com.intellij.execution.process.ProcessHandler
                if (converter != null) waitFor { converter.isProcessTerminated }
            }
            probeClients.forEach { it.dispose() }
            probeSessions.forEach { Disposer.dispose(it) }
            sockets.forEach { it.close() }; listener.close(); listening.forEach { it.cancel(true) }
            workers.shutdownNow(); assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS))
            edt { if (this::console.isInitialized && !Disposer.isDisposed(console)) Disposer.dispose(console); UIUtil.dispatchAllInvocationEvents() }
            XmlSerializerUtil.copyBean(previous, GoProjectSettings.get(project))
            System.getProperty("opengoland.launchCleanupEvidence")?.let { destination ->
                val converter = if (this::tests.isInitialized)
                    GoDebugTestSession::class.java.getDeclaredField("converter").apply { isAccessible = true }.get(tests) as? OSProcessHandler else null
                val bytes = requireNotNull(GoTestDapClient::class.java.getResourceAsStream("GoTestDapClient.class")).use { it.readAllBytes() }
                val report = JsonObject().apply {
                    addProperty("test", getName())
                    addProperty("manualProfile", manualProfile)
                    addProperty("constructorWasHeld", holdConstructor)
                    addProperty("normalSessionProcessHandlerIsTestEvents", this@Fixture::session.isInitialized &&
                        session.debugProcess.processHandler === tests.events)
                    addProperty("adapterPid", adapter.process.pid())
                    addProperty("adapterAliveAfterCleanup", adapter.process.isAlive)
                    converter?.let { addProperty("converterPid", it.process.pid()); addProperty("converterAliveAfterCleanup", it.process.isAlive) }
                    addProperty("normalPeerInitializeRequests", peer.initializes.size)
                    addProperty("normalPeerLaunchRequests", peer.launches.size)
                    addProperty("normalPeerDisconnectRequests", peer.disconnects.size)
                    addProperty("lowerLayerProbeUsed", probeClients.isNotEmpty())
                    if (probePeer.isDone) probePeer.getNow(null)?.let {
                        addProperty("probeInitializeRequests", it.initializes.size)
                        addProperty("probeLaunchRequests", it.launches.size)
                        addProperty("probeDisconnectRequests", it.disconnects.size)
                    }
                    addProperty("goTestClientClassResource", GoTestDapClient::class.java.getResource("GoTestDapClient.class").toString())
                    addProperty("goTestClientClassSha256", MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
                    addProperty("actualGoDebuggeeLaunched", false)
                }
                val output = Files.createDirectories(Path.of(destination))
                Files.writeString(output.resolve(token + ".json"), report.toString())
            }
            val paths = Files.walk(root).use { it.sorted(Comparator.reverseOrder()).toList() }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            for (path in paths) {
                while (true) {
                    try { Files.deleteIfExists(path); break }
                    catch (failure: AccessDeniedException) {
                        if (System.nanoTime() >= deadline) throw failure
                        Thread.sleep(10)
                    }
                }
            }
            assertTrue(Files.notExists(root))
        }
    }

    private class Peer : IDebugProtocolServer {
        lateinit var client: IDebugProtocolClient
        val initializes = CopyOnWriteArrayList<InitializeRequestArguments>()
        val launches = CopyOnWriteArrayList<MutableMap<String, Any>>()
        val disconnects = CopyOnWriteArrayList<DisconnectArguments>()
        val ready = CompletableFuture<Unit>()
        val ack = CompletableFuture<Void>()
        override fun initialize(args: InitializeRequestArguments): CompletableFuture<Capabilities> {
            initializes += args
            return CompletableFuture.completedFuture(Capabilities().apply { supportsConfigurationDoneRequest = true })
        }
        override fun launch(args: MutableMap<String, Any>): CompletableFuture<Void> {
            launches += args; client.initialized(); return CompletableFuture.completedFuture(null)
        }
        override fun configurationDone(args: ConfigurationDoneArguments) = CompletableFuture.completedFuture<Void>(null)
        override fun threads() = CompletableFuture.completedFuture(ThreadsResponse().apply { threads = emptyArray(); ready.complete(Unit) })
        override fun setBreakpoints(args: SetBreakpointsArguments) = CompletableFuture.completedFuture(SetBreakpointsResponse().apply { breakpoints = emptyArray() })
        override fun disconnect(args: DisconnectArguments): CompletableFuture<Void> { disconnects += args; return ack }
    }

    private fun waitFor(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6)
        while (!predicate() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue("Owned Go Test lifecycle did not reach its bounded state", predicate())
    }
    private fun <T> edt(action: () -> T): T {
        var result: T? = null
        ApplicationManager.getApplication().invokeAndWait { WriteIntentReadAction.run { result = action() } }
        @Suppress("UNCHECKED_CAST") return result as T
    }
    private companion object {
        val ADAPTER = """
            package main
            import ("fmt"; "os"; "os/signal"; "path/filepath"; "syscall"; "time")
            func main() {
                if len(os.Args)>1 && os.Args[1]=="version" {
                    fmt.Println("Delve Debugger\nVersion: 1.27.2\nBuild: owned lifecycle fixture"); return
                }
                directory,err:=os.Getwd(); if err!=nil { panic(err) }
                signals:=make(chan os.Signal,1);signal.Notify(signals,os.Interrupt,syscall.SIGTERM)
                announced:=false
                for {
                    if !announced { if _,err:=os.Stat(filepath.Join(directory,"announce"));err==nil {
                        fmt.Printf("DAP server listening at: 127.0.0.1:%s\n",os.Getenv("OWNED_DAP_PORT"));announced=true
                    } }
                    select {
                    case <-signals:
                        if err:=os.WriteFile(filepath.Join(directory,"term"),[]byte("owned termination signal"),0600);err!=nil{panic(err)}
                        return
                    case <-time.After(10*time.Millisecond):
                        if _,err:=os.Stat(filepath.Join(directory,"exit"));err==nil{return}
                    }
                }
            }
        """.trimIndent()
    }
}
