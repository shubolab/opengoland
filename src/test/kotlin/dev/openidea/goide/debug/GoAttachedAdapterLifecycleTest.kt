package dev.openidea.goide.debug

import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.RunConfigurationOptions
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.ui.ConsoleView
import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.LightPlatformTestCase
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
import dev.openidea.goide.process.GoOwnedTestExecutable
import org.eclipse.lsp4j.debug.*
import org.eclipse.lsp4j.debug.launch.DSPLauncher
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient
import org.eclipse.lsp4j.debug.services.IDebugProtocolServer
import java.lang.Thread as JavaThread
import java.lang.reflect.Proxy
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.Icon
import javax.swing.JPanel

/** Real platform Stop and DAP transport, with an owned adapter that records premature signals. */
class GoAttachedAdapterLifecycleTest : LightPlatformTestCase() {
    override fun runInDispatchThread() = false

    fun testNativeStopWaitsForOneSharedDetachAndTheAdaptersNaturalExit() {
        Fixture().use { fixture ->
            val client = fixture.client()
            ApplicationManager.getApplication().invokeAndWait { fixture.stopNative() }
            waitFor { fixture.sessionStops.get() > 0 && fixture.peer.disconnects.size == 1 }
            assertTrue(fixture.handler.isProcessTerminating)
            val shared = client.disconnectAttached()
            client.terminate()
            assertSame(shared, client.disconnectAttached())
            val stableUntil = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(250)
            while (System.nanoTime() < stableUntil) {
                assertTrue("The real platform Stop must not kill the adapter while detach is pending", fixture.handler.process.isAlive)
                assertFalse("No SIGTERM before the DAP acknowledgement", Files.exists(fixture.root.resolve("term")))
                JavaThread.sleep(5)
            }
            assertFalse(shared.isDone)
            assertEquals(1, fixture.peer.disconnects.size)
            assertEquals(false, fixture.peer.disconnects.single().terminateDebuggee)
            assertTrue("Stop must not resume a target to work around detach", fixture.peer.continues.isEmpty())

            fixture.peer.ack.complete(null)
            shared.get(2, TimeUnit.SECONDS)
            Files.writeString(fixture.root.resolve("exit"), "owned adapter exits after detach")
            fixture.handler.process.onExit().get(2, TimeUnit.SECONDS)
            assertEquals("The owned adapter must exit naturally after acknowledgement", 0, fixture.handler.process.exitValue())
            assertFalse("A completed detach should permit natural exit without a concurrent signal", Files.exists(fixture.root.resolve("term")))
        }
    }

    fun testDetachDeadlineReapsOnlyTheOwnedAdapterWithoutBlockingTheEdt() {
        Fixture(ignoreTerm = true, graceSeconds = 1).use { fixture ->
            fixture.client()
            val descendant = fixture.child()
            val returned = CompletableFuture<Long>()
            ApplicationManager.getApplication().invokeLater {
                val start = System.nanoTime()
                fixture.handler.destroyProcess()
                returned.complete(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start))
            }
            assertTrue("Adapter Stop must not wait for network or process exit on the EDT", returned.get(1, TimeUnit.SECONDS) < 500)
            waitFor { fixture.peer.disconnects.size == 1 }
            fixture.handler.process.onExit().get(5, TimeUnit.SECONDS)
            if (!System.getProperty("os.name").startsWith("Windows"))
                assertTrue("The owned test adapter ignored the graceful termination signal", Files.exists(fixture.root.resolve("term")))
            assertTrue("Emergency cleanup must never recursively kill adapter descendants", descendant.isAlive)
            assertTrue(fixture.peer.continues.isEmpty())
            fixture.peer.ack.complete(null)
        }
    }

    fun testBlockedDisconnectDispatchCannotPreventTheIndependentCleanupDeadline() {
        Fixture(graceSeconds = 1).use { fixture ->
            fixture.client()
            val entered = CompletableFuture<Unit>()
            val blocked = CountDownLatch(1)
            fixture.handler.disconnect = {
                entered.complete(Unit)
                blocked.await()
                CompletableFuture.completedFuture(null)
            }
            try {
                ApplicationManager.getApplication().invokeAndWait { fixture.handler.destroyProcess() }
                entered.get(1, TimeUnit.SECONDS)
                fixture.handler.process.onExit().get(4, TimeUnit.SECONDS)
                if (!System.getProperty("os.name").startsWith("Windows")) assertTrue(Files.exists(fixture.root.resolve("term")))
            } finally { blocked.countDown() }
        }
    }

    fun testOwnedFileCleanupRetriesAClosingLeaseOnlyAfterTheOwnersHaveExited() {
        val root = Files.createTempDirectory("opengoland-adapter-cleanup-lease-")
        val executable = Files.writeString(root.resolve("owned-adapter.exe"), "owned fixture")
        var deleteAttempts = 0
        try {
            try {
                deleteOwnedFixtureTree(root, { false }, { deleteAttempts++; Files.deleteIfExists(it) })
                fail("Cleanup must not delete an image while an owned process is alive")
            } catch (_: IllegalStateException) { }
            assertEquals(0, deleteAttempts)
            assertTrue(Files.exists(executable))
            deleteOwnedFixtureTree(root, { true }, {
                deleteAttempts++
                if (deleteAttempts == 1) throw AccessDeniedException(it.toString(), null, "controlled closing image lease")
                Files.deleteIfExists(it)
            })
            assertTrue(deleteAttempts >= 3) // denied image, actual image delete, actual directory delete
            assertTrue("A retried cleanup must actually remove the entire owned tree", Files.notExists(root))
        } finally {
            Files.deleteIfExists(executable); Files.deleteIfExists(root)
        }
    }

    fun testOwnedFileCleanupPropagatesPersistentAccessDenialAtItsBound() {
        val root = Files.createTempDirectory("opengoland-adapter-cleanup-denied-")
        val executable = Files.writeString(root.resolve("owned-adapter.exe"), "owned fixture")
        val denied = AccessDeniedException(executable.toString(), null, "controlled persistent image lease")
        try {
            val start = System.nanoTime()
            try {
                deleteOwnedFixtureTree(root, { true }, { throw denied }, TimeUnit.MILLISECONDS.toNanos(100))
                fail("A persistent deletion failure must not be hidden")
            } catch (failure: AccessDeniedException) { assertSame(denied, failure) }
            assertTrue("A denial must be retried until the bounded deadline", System.nanoTime() - start >= TimeUnit.MILLISECONDS.toNanos(100))
            assertTrue("A timed-out cleanup must not pretend the executable was deleted", Files.exists(executable))
        } finally {
            Files.deleteIfExists(executable); Files.deleteIfExists(root)
        }
    }

    private inner class Fixture(private val ignoreTerm: Boolean = false, graceSeconds: Long = 3) : AutoCloseable {
        val root: Path = Files.createTempDirectory("opengoland-adapter-lifecycle-").toRealPath()
        private val token = UUID.randomUUID().toString()
        private val program = GoOwnedTestExecutable.compile(root.resolve(GoOwnedTestExecutable.name("owned-adapter")), """
            package main
            import ("os"; "os/exec"; "os/signal"; "path/filepath"; "strconv"; "syscall"; "time")
            func main() {
                if len(os.Args) > 1 && os.Args[1] == "child" { for { time.Sleep(time.Hour) } }
                executable, err := os.Executable(); if err != nil { panic(err) }
                root := filepath.Dir(executable)
                ignore := ${if (ignoreTerm) "true" else "false"}
                signals := make(chan os.Signal, 1)
                signal.Notify(signals, os.Interrupt, syscall.SIGTERM)
                if ignore {
                    child := exec.Command(executable, "child", "$token")
                    if err := child.Start(); err != nil { panic(err) }
                    if err := os.WriteFile(filepath.Join(root, "child"), []byte(strconv.Itoa(child.Process.Pid)), 0600); err != nil { panic(err) }
                }
                if err := os.WriteFile(filepath.Join(root, "ready"), []byte(strconv.Itoa(os.Getpid())), 0600); err != nil { panic(err) }
                for {
                    select {
                    case <-signals:
                        if err := os.WriteFile(filepath.Join(root, "term"), []byte(strconv.Itoa(os.Getpid())), 0600); err != nil { panic(err) }
                        if !ignore { return }
                    case <-time.After(10 * time.Millisecond):
                        if !ignore { if _, err := os.Stat(filepath.Join(root, "exit")); err == nil { return } }
                    }
                }
            }
        """.trimIndent())
        val peer = Peer()
        val sessionStops = AtomicInteger()
        private val console = CopyOnWriteArrayList<String>()
        private val workers = Executors.newCachedThreadPool { task -> JavaThread(task, "Go-attach-stop-test").apply { isDaemon = true } }
        private val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        private val accepted = CompletableFuture<Socket>()
        private var listening: Future<Void>? = null
        private val captured = CompletableFuture<GoAttachedDAPClient>()
        val handler: GoAttachedAdapterProcessHandler
        private val debugProcess: DAPDebugProcess
        private var ownedChild: ProcessHandle? = null
        private var childStarted: Instant? = null

        init {
            handler = GoAttachedAdapterProcessHandler(GeneralCommandLine(program.toString()).withWorkDirectory(root.toFile()), graceSeconds)
            waitFor { Files.exists(root.resolve("ready")) }
            workers.submit {
                try {
                    val socket = listener.accept(); accepted.complete(socket)
                    val launcher = DSPLauncher.createServerLauncher(peer, socket.getInputStream(), socket.getOutputStream(), workers) { it }
                    peer.client = launcher.remoteProxy
                    listening = launcher.startListening()
                } catch (failure: Exception) { captured.completeExceptionally(failure) }
            }
            var built: DAPDebugProcess? = null
            ApplicationManager.getApplication().invokeAndWait {
            val executor = DefaultDebugExecutor.getDebugExecutorInstance()
            val profile = object : RunProfile {
                override fun getName() = "Go attached adapter lifecycle"
                override fun getIcon(): Icon? = null
                override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState? = null
            }
            val environment = ExecutionEnvironmentBuilder(project, executor).runProfile(profile).runner(DAPDebugRunner()).build()
            val options = object : RunConfigurationOptions() {}
            val descriptor = object : DebugAdapterDescriptor(options, environment, null) {
                override fun getId() = "opengoland-attached-stop-test"
                override fun getFileType() = GoFileType.INSTANCE
                override fun getDebugMode() = DebugMode.ATTACH
                override fun getDapParameters() = mapOf<String, Any>("mode" to "local")
                override fun getServerReadyConfig(debugMode: DebugMode) = ServerReadyConfig("127.0.0.1", listener.localPort)
                override fun startServer(): ProcessHandler = error("This fixture already owns exactly one adapter process")
                override fun createClient(process: DAPDebugProcess, parameters: MutableMap<String, Any>, isDebug: Boolean,
                    mode: DebugMode, trace: ServerTrace, parent: DAPClient?): DAPClient =
                    GoAttachedDAPClient(process, parameters, isDebug, DebugMode.ATTACH, trace, parent).also {
                        handler.disconnect = it::disconnectAttached
                        captured.complete(it)
                    }
            }
            val state = DAPCommandLineState(descriptor, options, environment)
            val consoleView = Proxy.newProxyInstance(ConsoleView::class.java.classLoader, arrayOf(ConsoleView::class.java)) { _, method, args ->
                when (method.name) { "print" -> { console += args!![0] as String; null }; "getComponent", "getPreferredFocusableComponent" -> JPanel(); else -> defaultValue(method.returnType) }
            } as ConsoleView
            val session = Proxy.newProxyInstance(XDebugSession::class.java.classLoader, arrayOf(XDebugSession::class.java)) { _, method, _ ->
                when (method.name) {
                    "getProject" -> project
                    "getConsoleView" -> consoleView
                    "getSessionName" -> "Go attached adapter lifecycle"
                    // The actual pinned XDebugSessionImpl.stop calls destroyProcess.
                    // Retain that boundary rather than the no-op used by routing tests.
                    "stop" -> { sessionStops.incrementAndGet(); handler.destroyProcess(); null }
                    else -> defaultValue(method.returnType)
                }
            } as XDebugSession
            handler.startNotify()
            DAPServerReadyTracker(descriptor.getServerReadyConfig(DebugMode.ATTACH), DebugMode.ATTACH, handler)
            built = DAPDebugProcess(state, session, DefaultExecutionResult(consoleView, handler), true)
            }
            debugProcess = requireNotNull(built)
        }

        fun client(): GoAttachedDAPClient {
            val value = captured.get(5, TimeUnit.SECONDS)
            peer.ready.get(5, TimeUnit.SECONDS)
            assertNotNull(value.debugProtocolServer)
            return value
        }
        fun stopNative() = debugProcess.stop()

        fun child(): ProcessHandle {
            waitFor { Files.exists(root.resolve("child")) }
            val value = ProcessHandle.of(Files.readString(root.resolve("child")).toLong()).orElseThrow()
            assertOwnedChild(value)
            ownedChild = value
            childStarted = value.info().startInstant().orElse(null)
            return value
        }

        override fun close() {
            peer.ack.complete(null)
            if (Files.exists(root.resolve("child")) && ownedChild == null) runCatching { child() }
            ownedChild?.takeIf { it.isAlive }?.let { child ->
                assertOwnedChild(child)
                check(childStarted == null || child.info().startInstant().orElse(null) == childStarted) { "The owned child PID was reused" }
                child.destroyForcibly(); child.onExit().get(2, TimeUnit.SECONDS)
            }
            Files.writeString(root.resolve("exit"), "fixture cleanup")
            handler.process.destroyForcibly(); handler.process.onExit().get(2, TimeUnit.SECONDS)
            if (captured.isDone && !captured.isCompletedExceptionally) captured.getNow(null)?.dispose()
            debugProcess.dispose()
            accepted.getNow(null)?.close(); listener.close(); listening?.cancel(true); workers.shutdownNow()
            deleteOwnedFixtureTree(root, { !handler.process.isAlive && ownedChild?.isAlive != true })
        }

        private fun assertOwnedChild(child: ProcessHandle) {
            val info = child.info()
            check(info.arguments().map { it.any { argument -> argument == token } }.orElse(false) ||
                info.command().map { Path.of(it).toAbsolutePath().normalize() == program }.orElse(false)) {
                "The descendant must be this fixture's unique native executable or carry its ownership token"
            }
        }
    }

    /** Process exit does not guarantee that Windows has already released every image handle. */
    private fun deleteOwnedFixtureTree(root: Path, ownersStopped: () -> Boolean,
        delete: (Path) -> Unit = { Files.deleteIfExists(it) }, retryNanos: Long = TimeUnit.SECONDS.toNanos(3)) {
        check(!ApplicationManager.getApplication().isDispatchThread)
        check(ownersStopped()) { "Only stopped, owned process images may be removed" }
        val deadline = System.nanoTime() + retryNanos
        // Close traversal handles before removing files/directories on Windows.
        val paths = Files.walk(root).use { it.sorted(Comparator.reverseOrder()).toList() }
        for (path in paths) {
            while (true) {
                check(ownersStopped()) { "An owned process is still alive during fixture cleanup" }
                try {
                    delete(path)
                    check(Files.notExists(path)) { "The owned fixture path was not actually removed: $path" }
                    break
                } catch (denied: AccessDeniedException) {
                    val remaining = deadline - System.nanoTime()
                    if (remaining <= 0) throw denied
                    TimeUnit.NANOSECONDS.sleep(minOf(remaining, TimeUnit.MILLISECONDS.toNanos(10)))
                }
            }
        }
        check(Files.notExists(root)) { "The owned fixture tree remains after cleanup: $root" }
    }

    private class Peer : IDebugProtocolServer {
        lateinit var client: IDebugProtocolClient
        val ready = CompletableFuture<Unit>()
        val ack = CompletableFuture<Void>()
        val disconnects = CopyOnWriteArrayList<DisconnectArguments>()
        val continues = CopyOnWriteArrayList<ContinueArguments>()
        override fun initialize(args: InitializeRequestArguments) = CompletableFuture.completedFuture(Capabilities().apply { supportsConfigurationDoneRequest = true })
        override fun attach(args: MutableMap<String, Any>): CompletableFuture<Void> { client.initialized(); return done() }
        override fun configurationDone(args: ConfigurationDoneArguments) = done()
        override fun threads() = CompletableFuture.completedFuture(ThreadsResponse().apply { threads = emptyArray(); ready.complete(Unit) })
        override fun setBreakpoints(args: SetBreakpointsArguments) = CompletableFuture.completedFuture(SetBreakpointsResponse().apply { breakpoints = emptyArray() })
        override fun continue_(args: ContinueArguments): CompletableFuture<ContinueResponse> { continues += args; return CompletableFuture.completedFuture(ContinueResponse()) }
        override fun disconnect(args: DisconnectArguments): CompletableFuture<Void> { disconnects += args; return ack }
        private fun done(): CompletableFuture<Void> = CompletableFuture.completedFuture(null)
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6)
        while (!condition() && System.nanoTime() < deadline) JavaThread.sleep(5)
        assertTrue("The owned adapter lifecycle fixture did not reach its bounded state", condition())
    }
    private fun defaultValue(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false; java.lang.Integer.TYPE -> 0; java.lang.Long.TYPE -> 0L; java.lang.Float.TYPE -> 0f; java.lang.Double.TYPE -> 0.0
        else -> if (type.isArray) java.lang.reflect.Array.newInstance(type.componentType, 0) else null
    }
}
