package dev.openidea.goide.debug

import com.google.gson.JsonParser
import com.google.gson.JsonObject
import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.ui.ConsoleView
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.project.Project
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
import com.redhat.devtools.lsp4ij.dap.DebugAdapterManager
import com.redhat.devtools.lsp4ij.dap.DAPDebugRunner
import com.redhat.devtools.lsp4ij.dap.DAPServerReadyTracker
import com.redhat.devtools.lsp4ij.dap.DebugMode
import com.redhat.devtools.lsp4ij.dap.TransportStreams
import com.redhat.devtools.lsp4ij.dap.client.DAPClient
import com.redhat.devtools.lsp4ij.dap.configurations.DAPCommandLineState
import dev.openidea.goide.process.GoOwnedTestExecutable
import dev.openidea.goide.settings.GoProjectSettings
import dev.openidea.goide.settings.GoToolchain
import org.eclipse.lsp4j.debug.*
import org.eclipse.lsp4j.debug.launch.DSPLauncher
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient
import org.eclipse.lsp4j.debug.services.IDebugProtocolServer
import java.lang.reflect.Proxy
import java.lang.reflect.InvocationTargetException
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.lang.Thread
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import javax.swing.Icon
import javax.swing.JPanel

/** Actual Go descriptor, native SDK session Stop, real LSP4J wire and owned portable adapter process. */
class GoLaunchedAdapterLifecycleTest : LightPlatformTestCase() {
    override fun runInDispatchThread() = false

    fun testNativeSessionStopWaitsForOneLaunchDisconnectAndNaturalAdapterExit() {
        Fixture().use { f ->
            val client = f.ready()
            edt { f.session().stop() }
            val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(250)
            while (System.nanoTime() < until) {
                assertTrue("Native SDK Stop killed the adapter before its DAP acknowledgement", f.handler.process.isAlive)
                assertFalse("No adapter SIGTERM before the DAP acknowledgement", Files.exists(f.root.resolve("term")))
                Thread.sleep(5)
            }
            waitFor { f.peer.disconnects.size == 1 && f.handler.isProcessTerminating }
            client.terminate()
            client.terminate()
            assertEquals(1, f.peer.disconnects.size)
            assertEquals(true, f.peer.disconnects.single().terminateDebuggee)
            assertTrue(f.peer.terminates.isEmpty())
            assertTrue(f.peer.continues.isEmpty())
            val shared = (client as GoLaunchedDapClient).disconnectLaunched()
            assertSame(shared, client.disconnectLaunched())
            assertFalse(shared.isDone)
            f.peer.ack.complete(null)
            shared.get(2, TimeUnit.SECONDS)
            Files.writeString(f.root.resolve("exit"), "owned adapter natural exit after ACK")
            f.handler.process.onExit().get(3, TimeUnit.SECONDS)
            assertEquals(0, f.handler.process.exitValue())
            assertFalse("An ACK must allow natural exit rather than race another signal", Files.exists(f.root.resolve("term")))
        }
    }

    fun testNativeStopDeadlineIsOffEdtAndNeverKillsAnOwnedSentinelChild() {
        Fixture(ignoreTerm = true).use { f ->
            val client = f.ready()
            val child = f.child()
            val returned = CompletableFuture<Long>()
            ApplicationManager.getApplication().invokeLater {
                val started = System.nanoTime()
                f.session().stop()
                returned.complete(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
            }
            assertTrue("The real session Stop must return while its ACK is held", returned.get(1, TimeUnit.SECONDS) < 500)
            val posted = CompletableFuture<Unit>()
            ApplicationManager.getApplication().invokeLater { posted.complete(Unit) }
            posted.get(1, TimeUnit.SECONDS)
            waitFor { f.peer.disconnects.size == 1 }
            f.handler.process.onExit().get(10, TimeUnit.SECONDS)
            assertTrue("Fallback may terminate only its exact adapter, never a process tree", child.isAlive)
            assertEquals(true, f.peer.disconnects.single().terminateDebuggee)
            assertTrue((client as GoLaunchedDapClient).disconnectLaunched().isCompletedExceptionally)
            if (!System.getProperty("os.name").startsWith("Windows"))
                assertTrue(Files.exists(f.root.resolve("term")))
        }
    }

    fun testFailedDisconnectFallsBackWithoutDuplicatingRequestsOrTouchingChildren() {
        Fixture(ignoreTerm = true).use { f ->
            val client = f.ready()
            val child = f.child()
            edt { f.session().stop() }
            waitFor { f.peer.disconnects.size == 1 }
            f.peer.ack.completeExceptionally(IllegalStateException("owned controlled disconnect failure"))
            f.handler.process.onExit().get(8, TimeUnit.SECONDS)
            client.terminate()
            assertEquals(1, f.peer.disconnects.size)
            assertTrue(child.isAlive)
            assertTrue((client as GoLaunchedDapClient).disconnectLaunched().isCompletedExceptionally)
        }
    }

    fun testStopWhileInitializeIsHeldCannotSendALateLaunch() {
        val peer = Fixture(holdInitialize = true).use { f ->
            f.peer.initializeEntered.get(5, TimeUnit.SECONDS)
            // This is the real owner's process Stop while SDK initialization is
            // pending. The separate test above proves the standard EDT action.
            f.handler.destroyProcess()
            waitFor { f.peer.disconnects.size == 1 }
            assertEquals(true, f.peer.disconnects.single().terminateDebuggee)
            // Deliver a genuine late initialize response while disconnect is
            // still pending, rather than first closing the client reader.
            assertTrue(f.peer.initializeAck.complete(f.peer.capabilities()))
            f.initializeReplyConsumed.get(3, TimeUnit.SECONDS)
            assertFalse(f.peer.ack.isDone)
            assertTrue(f.handler.process.isAlive)
            assertTrue(f.clientReaderAlive())
            assertEquals("A late initialize reply cannot revive a launch while disconnect is pending", 0, f.peer.launches.size)
            f.peer.ack.complete(null)
            Files.writeString(f.root.resolve("exit"), "finish the owned early-stop adapter")
            f.handler.process.onExit().get(4, TimeUnit.SECONDS)
            f.session()
            assertEquals("A released initialize reply must not revive a stopped launch", 0, f.peer.launches.size)
            f.peer
        }
        assertEquals("No late launch after the client reader and owned fixture have quiesced", 0, peer.launches.size)
    }

    fun testStopBeforeClientBindingRejectsTheAdaptersLateReadyAnnouncement() {
        Fixture(holdReady = true).use { f ->
            waitFor { Files.exists(f.root.resolve("ready")) }
            f.handler.destroyProcess()
            Files.writeString(f.root.resolve("announce"), "late native-ready line")
            f.handler.process.onExit().get(7, TimeUnit.SECONDS)
            f.session()
            assertEquals(0, f.peer.initializeCalls.get())
            assertTrue(f.peer.launches.isEmpty())
            assertTrue("No endpoint existed, so no successful disconnect is claimed", f.peer.disconnects.isEmpty())
        }
    }

    fun testRejectedProjectRegistrationCleansTheUnnotifiedOwnedAdapter() {
        Fixture(rejectRegistration = true).use { f ->
            assertNotNull(f.registrationFailure)
            assertTrue(f.registrationFailure!!.message.orEmpty().contains("disposed", ignoreCase = true))
            assertTrue("No runner can start notifications after startServer throws", f.handler.isStartNotified)
            f.handler.process.onExit().get(8, TimeUnit.SECONDS)
            assertFalse(f.handler.process.isAlive)
            assertTrue(f.peer.disconnects.isEmpty())
            assertTrue(f.peer.launches.isEmpty())
        }
    }

    private inner class Fixture(
        ignoreTerm: Boolean = false, holdInitialize: Boolean = false, holdReady: Boolean = false,
        rejectRegistration: Boolean = false,
    ) : AutoCloseable {
        val root = Files.createTempDirectory("go-launch-stop-").toRealPath()
        private val token = UUID.randomUUID().toString()
        private val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        private val workers = Executors.newCachedThreadPool { task -> Thread(task, "Go-launch-stop-owned").apply { isDaemon = true } }
        private val accepted = CompletableFuture<Socket>()
        private var listening: Future<Void>? = null
        val peer = Peer(holdInitialize)
        val initializeReplyConsumed = CompletableFuture<Unit>()
        private val sessionFuture = CompletableFuture<XDebugSessionImpl>()
        private var process: DAPDebugProcess? = null
        private val printed = CopyOnWriteArrayList<String>()
        private val settings = GoProjectSettings.get(project)
        private val previous = GoProjectSettings.Data().also { XmlSerializerUtil.copyBean(settings, it) }
        private val program = GoOwnedTestExecutable.compile(root.resolve(GoOwnedTestExecutable.name("owned-adapter")), ADAPTER)
        private var ownedChild: ProcessHandle? = null
        private var childStarted: Instant? = null
        var registrationFailure: Throwable? = null
            private set
        val handler: OSProcessHandler

        init {
            settings.goExecutable = requireNotNull(GoToolchain.resolve("go",
                System.getProperty("opengoland.testGo") ?: System.getenv("OPENGOLAND_GO_TEST").orEmpty()))
            settings.goplsExecutable = program.toString()
            settings.delveExecutable = program.toString()
            settings.goos = ""; settings.goarch = ""; settings.cgoEnabled = ""; settings.buildTags = ""
            settings.environment = "OWNED_DAP_PORT=" + listener.localPort + "\nOWNED_TOKEN=" + token +
                "\nOWNED_IGNORE_TERM=" + ignoreTerm + "\nOWNED_HOLD_READY=true" +
                "\nGOENV=off\nGOTOOLCHAIN=local\nGOWORK=off\nGOPROXY=off\nGOSUMDB=off\nGOTELEMETRY=off"
            // Initialize the upstream memory-only owner while its project is alive.
            com.redhat.devtools.lsp4ij.dap.runInTerminal.RunInTerminalManager.getInstance(project)
            val executor = DefaultDebugExecutor.getDebugExecutorInstance()
            val profile = object : RunProfile {
                override fun getName() = "Owned launched adapter lifecycle"
                override fun getIcon(): Icon? = null
                override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState? = null
            }
            // Only Disposer's ownership record is rejected. All SDK/services
            // remain the real live project; disposing this identity proxy must
            // never dispose the shared LightPlatform project.
            val owner = if (rejectRegistration) rejectedOwner() else project
            val environment = ExecutionEnvironmentBuilder(owner, executor).runProfile(profile).runner(DAPDebugRunner()).build()
            val options = GoDebugOptions().apply { mode = "debug"; program = root.toString(); workingDirectory = root.toString() }
            val descriptor = GoDebugDescriptor(options, environment,
                requireNotNull(DebugAdapterManager.getInstance().getDebugAdapterServerById("openidea.delve")))
            handler = if (rejectRegistration) {
                registrationFailure = runCatching { descriptor.startServer() }.exceptionOrNull()
                GoDebugDescriptor::class.java.getDeclaredField("adapter").apply { isAccessible = true }.get(descriptor) as OSProcessHandler
            } else descriptor.startServer() as OSProcessHandler
            val state = DAPCommandLineState(descriptor, options, environment)
            val console = Proxy.newProxyInstance(ConsoleView::class.java.classLoader, arrayOf(ConsoleView::class.java)) { _, method, args ->
                when (method.name) {
                    "print" -> { printed += args!![0] as String; null }
                    "getComponent", "getPreferredFocusableComponent" -> JPanel()
                    else -> defaultValue(method.returnType)
                }
            } as ConsoleView
            if (!rejectRegistration) {
                DAPServerReadyTracker(descriptor.getServerReadyConfig(DebugMode.LAUNCH), DebugMode.LAUNCH, handler)
                handler.startNotify()
                workers.submit {
                    try {
                        val socket = listener.accept(); accepted.complete(socket)
                        val launcher = DSPLauncher.createServerLauncher(peer, socket.getInputStream(), socket.getOutputStream(), workers) { it }
                        peer.client = launcher.remoteProxy; listening = launcher.startListening()
                    } catch (failure: Exception) { if (!listener.isClosed) peer.ready.completeExceptionally(failure) }
                }
                workers.submit {
                    val asyncProperty = "intellij.progress.task.ignoreHeadless"
                    val previousAsync = System.getProperty(asyncProperty)
                    try {
                        // The pinned SDK exposes this headless-test switch to
                        // keep its real Backgroundable handshake asynchronous.
                        System.setProperty(asyncProperty, "true")
                        sessionFuture.complete(edt {
                            XDebuggerManager.getInstance(project).newSessionBuilder(object : XDebugProcessStarter() {
                                override fun start(session: XDebugSession): XDebugProcess =
                                    object : DAPDebugProcess(state, session, DefaultExecutionResult(console, handler), true) {
                                        override fun getStreamsSupplier(): java.util.function.Supplier<TransportStreams> {
                                            val original = super.getStreamsSupplier()
                                            return if (!holdInitialize) original else java.util.function.Supplier {
                                                initializationReceipt(original.get())
                                            }
                                        }
                                    }.also { process = it }
                            }).environment(environment).showTab(false).startSession().session as XDebugSessionImpl
                        })
                        // Do not race the tracker's initial publication with a
                        // helper much faster than real Delve startup.
                        if (!holdReady) Files.writeString(root.resolve("announce"), "owned session constructed")
                    } catch (failure: Throwable) { sessionFuture.completeExceptionally(failure) }
                    finally {
                        if (previousAsync == null) System.clearProperty(asyncProperty) else System.setProperty(asyncProperty, previousAsync)
                    }
                }
            }
        }

        private fun rejectedOwner(): Project {
            val delegate = project
            val owner = Proxy.newProxyInstance(Project::class.java.classLoader, arrayOf(Project::class.java)) { proxy, method, args ->
                when (method.name) {
                    "equals" -> proxy === args?.get(0)
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "Owned rejected launch registration"
                    "dispose" -> null
                    else -> try { method.invoke(delegate, *(args ?: emptyArray())) }
                        catch (failure: InvocationTargetException) { throw failure.targetException }
                }
            } as Project
            Disposer.dispose(owner)
            check(Disposer.isDisposed(owner))
            return owner
        }

        fun session(): XDebugSessionImpl = sessionFuture.get(10, TimeUnit.SECONDS)
        fun client(): GoDapClient? {
            val process = process ?: return null
            return DAPDebugProcess::class.java.getDeclaredField("parentClient").apply { isAccessible = true }.get(process) as? GoDapClient
        }
        fun ready(): GoDapClient {
            session(); peer.ready.get(5, TimeUnit.SECONDS)
            return requireNotNull(client()).also { assertNotNull(it.debugProtocolServer) }
        }
        fun clientReaderAlive(): Boolean = !clientPool(requireNotNull(client())).isShutdown

        private fun clientPool(client: DAPClient): ExecutorService =
            DAPClient::class.java.getDeclaredField("threadPool").apply { isAccessible = true }.get(client) as ExecutorService

        private fun initializationReceipt(actual: TransportStreams): TransportStreams {
            val bytes = ByteArrayOutputStream()
            var completeInitialize = false
            // StreamMessageProducer consumes a response before reading the next
            // frame. Complete only at that next read, after a whole response
            // (not a partial JSON token) passed through the real client parser.
            val input = object : InputStream() {
                private fun beforeRead() { if (completeInitialize) initializeReplyConsumed.complete(Unit) }
                private fun captured(value: ByteArray, offset: Int, size: Int) {
                    bytes.write(value, offset, size)
                    while (true) {
                        val data = bytes.toByteArray()
                        val text = String(data, Charsets.ISO_8859_1)
                        val headerEnd = text.indexOf("\r\n\r\n")
                        if (headerEnd < 0) return
                        val length = Regex("(?im)^Content-Length: ([0-9]+)\\r?$").find(text.substring(0, headerEnd))?.groupValues?.get(1)?.toInt()
                            ?: error("Owned DAP peer emitted an invalid frame header")
                        val end = headerEnd + 4 + length
                        if (data.size < end) return
                        val message = JsonParser.parseString(String(data, headerEnd + 4, length, Charsets.UTF_8)).asJsonObject
                        if (message["type"]?.asString == "response" && message["command"]?.asString == "initialize") completeInitialize = true
                        bytes.reset(); bytes.write(data, end, data.size - end)
                        if (data.size == end) return
                    }
                }
                override fun read(): Int {
                    beforeRead()
                    return actual.`in`.read().also { if (it >= 0) captured(byteArrayOf(it.toByte()), 0, 1) }
                }
                override fun read(value: ByteArray, offset: Int, length: Int): Int {
                    beforeRead()
                    return actual.`in`.read(value, offset, length).also { if (it > 0) captured(value, offset, it) }
                }
            }
            return object : TransportStreams.DefaultTransportStreams(input, actual.out) {
                override fun close() = actual.close()
            }
        }
        fun child(): ProcessHandle {
            waitFor { Files.exists(root.resolve("child")) }
            return ProcessHandle.of(Files.readString(root.resolve("child")).toLong()).orElseThrow().also {
                assertOwnedChild(it); ownedChild = it; childStarted = it.info().startInstant().orElse(null)
            }
        }

        override fun close() {
            peer.ack.complete(null); peer.initializeAck.complete(peer.capabilities())
            Files.writeString(root.resolve("announce"), "owned fixture cleanup")
            if (Files.exists(root.resolve("child")) && ownedChild == null) child()
            ownedChild?.takeIf { it.isAlive }?.let {
                assertOwnedChild(it)
                check(childStarted == null || childStarted == it.info().startInstant().orElse(null))
                it.destroyForcibly(); it.onExit().get(3, TimeUnit.SECONDS)
            }
            Files.writeString(root.resolve("exit"), "owned fixture cleanup")
            if (handler.process.isAlive) { handler.process.destroyForcibly(); handler.process.onExit().get(3, TimeUnit.SECONDS) }
            if (sessionFuture.isDone && !sessionFuture.isCompletedExceptionally) edt { session().stop() }
            val dapClient = client()
            dapClient?.dispose(); process?.dispose()
            dapClient?.let {
                val pool = clientPool(it)
                assertTrue("The owned DAP client reader must finish before its project fixture is released", pool.awaitTermination(3, TimeUnit.SECONDS))
            }
            accepted.getNow(null)?.close(); listener.close(); listening?.cancel(true)
            workers.shutdownNow(); assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS))
            XmlSerializerUtil.copyBean(previous, settings)
            // Windows can retain an image lease briefly even after owned Process exit.
            check(!handler.process.isAlive && ownedChild?.isAlive != true)
            System.getProperty("opengoland.launchCleanupEvidence")?.let { destination ->
                val report = JsonObject().apply {
                    addProperty("test", getName())
                    addProperty("adapterPid", handler.process.pid())
                    addProperty("adapterAliveAfterCleanup", handler.process.isAlive)
                    addProperty("adapterExitCode", handler.process.exitValue())
                    ownedChild?.let { addProperty("sentinelChildPid", it.pid()); addProperty("sentinelChildAliveAfterCleanup", it.isAlive) }
                    addProperty("disconnectRequests", peer.disconnects.size)
                    addProperty("allDisconnectsTerminateLaunchedTarget", peer.disconnects.all { it.terminateDebuggee == true })
                    addProperty("initializeReplyConsumedBeforeAck", initializeReplyConsumed.isDone)
                    addProperty("launchRequests", peer.launches.size)
                    addProperty("adapterTermMarker", Files.exists(root.resolve("term")))
                }
                Files.createDirectories(Path.of(destination))
                Files.writeString(Path.of(destination).resolve("$token.json"), report.toString())
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            val paths = Files.walk(root).use { it.sorted(Comparator.reverseOrder()).toList() }
            for (path in paths) {
                while (true) {
                    try { Files.deleteIfExists(path); check(Files.notExists(path)); break }
                    catch (denied: AccessDeniedException) {
                        if (System.nanoTime() >= deadline) throw denied
                        Thread.sleep(10)
                    }
                }
            }
            check(Files.notExists(root))
            edt { UIUtil.dispatchAllInvocationEvents() }
        }

        private fun assertOwnedChild(child: ProcessHandle) {
            val info = child.info()
            check(info.arguments().map { it.contains(token) }.orElse(false) ||
                info.command().map { Path.of(it).toAbsolutePath().normalize() == program }.orElse(false))
        }
    }

    private class Peer(holdInitialize: Boolean) : IDebugProtocolServer {
        lateinit var client: IDebugProtocolClient
        val ack = CompletableFuture<Void>()
        val initializeAck = object : CompletableFuture<Capabilities>() {
            override fun cancel(mayInterruptIfRunning: Boolean) = false
        }
        val initializeEntered = CompletableFuture<Unit>()
        val initializeCalls = java.util.concurrent.atomic.AtomicInteger()
        val ready = CompletableFuture<Unit>()
        val launches = CopyOnWriteArrayList<MutableMap<String, Any>>()
        val disconnects = CopyOnWriteArrayList<DisconnectArguments>()
        val continues = CopyOnWriteArrayList<ContinueArguments>()
        val terminates = CopyOnWriteArrayList<TerminateArguments>()
        init { if (!holdInitialize) initializeAck.complete(capabilities()) }
        fun capabilities() = Capabilities().apply { supportsConfigurationDoneRequest = true; supportsTerminateRequest = false }
        override fun initialize(args: InitializeRequestArguments): CompletableFuture<Capabilities> {
            initializeCalls.incrementAndGet(); initializeEntered.complete(Unit); return initializeAck
        }
        override fun launch(args: MutableMap<String, Any>): CompletableFuture<Void> { launches += args; client.initialized(); return done() }
        override fun configurationDone(args: ConfigurationDoneArguments) = done()
        override fun threads() = CompletableFuture.completedFuture(ThreadsResponse().apply { threads = emptyArray(); ready.complete(Unit) })
        override fun setBreakpoints(args: SetBreakpointsArguments) = CompletableFuture.completedFuture(SetBreakpointsResponse().apply { breakpoints = emptyArray() })
        override fun continue_(args: ContinueArguments): CompletableFuture<ContinueResponse> { continues += args; return CompletableFuture.completedFuture(ContinueResponse()) }
        override fun terminate(args: TerminateArguments): CompletableFuture<Void> { terminates += args; return done() }
        override fun disconnect(args: DisconnectArguments): CompletableFuture<Void> { disconnects += args; return ack }
        private fun done(): CompletableFuture<Void> = CompletableFuture.completedFuture(null)
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue("The owned launched-adapter fixture did not reach its bounded state", condition())
    }
    private fun <T> edt(action: () -> T): T {
        var value: T? = null
        ApplicationManager.getApplication().invokeAndWait { WriteIntentReadAction.run { value = action() } }
        @Suppress("UNCHECKED_CAST") return value as T
    }
    private fun defaultValue(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false; java.lang.Integer.TYPE -> 0; java.lang.Long.TYPE -> 0L
        else -> if (type.isArray) java.lang.reflect.Array.newInstance(type.componentType, 0) else null
    }
    private companion object {
        val ADAPTER = """
            package main
            import ("fmt"; "os"; "os/exec"; "os/signal"; "path/filepath"; "strconv"; "syscall"; "time")
            func main() {
                if len(os.Args) > 1 && os.Args[1] == "child" { for { time.Sleep(time.Hour) } }
                executable, err := os.Executable(); if err != nil { panic(err) }
                root := filepath.Dir(executable)
                ignore := os.Getenv("OWNED_IGNORE_TERM") == "true"
                signals := make(chan os.Signal, 1)
                signal.Notify(signals, os.Interrupt, syscall.SIGTERM)
                if ignore {
                    child := exec.Command(executable, "child", os.Getenv("OWNED_TOKEN"))
                    if err := child.Start(); err != nil { panic(err) }
                    if err := os.WriteFile(filepath.Join(root, "child"), []byte(strconv.Itoa(child.Process.Pid)), 0600); err != nil { panic(err) }
                }
                if err := os.WriteFile(filepath.Join(root, "ready"), []byte(strconv.Itoa(os.Getpid())), 0600); err != nil { panic(err) }
                announced := false
                for {
                    if !announced {
                        _, announceErr := os.Stat(filepath.Join(root, "announce"))
                        if os.Getenv("OWNED_HOLD_READY") != "true" || announceErr == nil {
                            fmt.Printf("DAP server listening at: 127.0.0.1:%s\n", os.Getenv("OWNED_DAP_PORT")); announced = true
                        }
                    }
                    select {
                    case <-signals:
                        if err := os.WriteFile(filepath.Join(root, "term"), []byte(strconv.Itoa(os.Getpid())), 0600); err != nil { panic(err) }
                        if !ignore { return }
                    case <-time.After(10 * time.Millisecond):
                        if !ignore { if _, err := os.Stat(filepath.Join(root, "exit")); err == nil { return } }
                    }
                }
            }
        """.trimIndent()
    }
}
