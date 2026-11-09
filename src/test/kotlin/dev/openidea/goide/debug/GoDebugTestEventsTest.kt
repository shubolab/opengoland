package dev.openidea.goide.debug

import com.intellij.ide.impl.OpenProjectTask
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ex.ProjectManagerEx
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.RunConfigurationOptions
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.process.NopProcessHandler
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.EdtTestUtil
import com.intellij.testFramework.LightPlatformTestCase
import com.intellij.util.xmlb.XmlSerializerUtil
import com.redhat.devtools.lsp4ij.dap.DAPDebugProcess
import com.redhat.devtools.lsp4ij.dap.DAPServerReadyTracker
import com.redhat.devtools.lsp4ij.dap.DAPDebugRunner
import com.redhat.devtools.lsp4ij.dap.DebugMode
import com.redhat.devtools.lsp4ij.dap.client.DAPClient
import com.redhat.devtools.lsp4ij.dap.configurations.DAPCommandLineState
import com.redhat.devtools.lsp4ij.dap.descriptors.DebugAdapterDescriptor
import com.redhat.devtools.lsp4ij.dap.descriptors.ServerReadyConfig
import com.redhat.devtools.lsp4ij.settings.ServerTrace
import com.intellij.xdebugger.XDebugSession
import dev.openidea.goide.lang.GoFileType
import dev.openidea.goide.run.GoRunConfiguration
import dev.openidea.goide.run.GoRunKind
import dev.openidea.goide.run.GoTestConfigurationType
import dev.openidea.goide.run.GoTestRerunPlan
import dev.openidea.goide.settings.GoProjectSettings
import dev.openidea.goide.settings.GoToolchain
import org.eclipse.lsp4j.debug.*
import org.eclipse.lsp4j.debug.launch.DSPLauncher
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient
import org.eclipse.lsp4j.debug.services.IDebugProtocolServer
import java.lang.reflect.Proxy
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Real SDK SM tree + real official converter + LSP4J DAP events; the peer controls event ordering. */
class GoDebugTestEventsTest : LightPlatformTestCase() {
    override fun runInDispatchThread() = false

    fun testSubtestsUnicodeChunksLocationsAndFailedDebugRetryShareTheirNativeIdentity() = Fixture().use { f ->
        f.send("\u0016=== RUN   TestParent\r\n\u0016=== RUN   TestParent/good😀\r\n")
        f.send("\u0016    probe_test.go:5: 中文😀\r\n", splitEveryCharacter = true)
        f.send("\u0016--- PASS: TestParent/good😀 (0.01s)\r\n\u0016=== RUN   TestParent/bad\r\n")
        f.send("\u0016    probe_test.go:6: mismatch\r\n\u0016--- FAIL: TestParent/bad (0.02s)\r\n\u0016--- FAIL: TestParent (0.03s)\r\n\u0016FAIL\r\n")
        f.exit(1)
        assertEquals(1, await(f.tests.completed))
        edt {
            val nodes = f.console.resultsViewer.root.allTests
            assertTrue(nodes.any { it.name == "good😀" && it.isPassed })
            val bad = nodes.single { it.name == "bad" }
            assertTrue(bad.isDefect)
            assertTrue(bad.locationUrl.orEmpty().startsWith("go-test://"))
            assertFalse(f.console.resultsViewer.root.isPassed)
        }
        assertEquals(mapOf("example.test/debug" to listOf("TestParent/bad")), f.tests.output.failures())
        val failedNode = edt { f.console.resultsViewer.root.allTests.single { it.name == "bad" } }
        val sourceFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(f.directory.resolve("probe_test.go"))!!
        ReadAction.run<RuntimeException> { PsiManager.getInstance(project).findFile(sourceFile) }
        val location = ReadAction.compute<com.intellij.execution.Location<*>?, RuntimeException> {
            failedNode.getLocation(project, GlobalSearchScope.allScope(project))
        }
        assertNotNull("Native failed-node locator must resolve to the real Go source", location)
        val sourcePosition = ReadAction.compute<Triple<String, Int, Int>, RuntimeException> {
            val path = location!!.virtualFile!!.path
            val offset = location.openFileDescriptor!!.offset
            val document = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(sourceFile)!!
            Triple(path, offset, if (offset >= 0) document.getLineNumber(offset) else -1)
        }
        assertEquals(f.directory.resolve("probe_test.go").toRealPath(), Path.of(sourcePosition.first).toRealPath())
        assertTrue(sourcePosition.second >= 0)
        assertEquals(5, sourcePosition.third)
        val retry = GoTestRerunPlan.create(f.tests.output.failures(), f.tests.values).single()
        assertEquals("^TestParent$/^bad$", retry.parameters.testPattern)
        assertEquals("example.test/debug", retry.parameters.target)
        assertTrue(f.tests.events.isProcessTerminated)
    }

    fun testAdapterAndConsoleLogsCannotForgeTestsAndTargetExitWinsOverConverterPass() = Fixture().use { f ->
        f.tests.toolOutput("\u0016=== RUN   ForgedAdapter\n\u0016PASS\n")
        f.peerOutput("console", "\u0016=== RUN   ForgedConsole\n\u0016PASS\n")
        f.send("\u0016=== RUN   TestReal\n\u0016--- PASS: TestReal (0.00s)\n\u0016PASS\n")
        f.exit(7)
        assertEquals(7, await(f.tests.completed))
        edt {
            val nodes = f.console.resultsViewer.root.allTests
            assertFalse(nodes.any { "Forged" in it.name })
            assertTrue(nodes.any { it.name == "TestReal" && it.isPassed })
            assertFalse("Target exit 7 must keep the native root failed even if the converter read PASS", f.console.resultsViewer.root.isPassed)
        }
        assertEquals(7, f.tests.events.exitCode)
        assertTrue(f.tests.output.failures().values.single().contains(""))
    }

    fun testStopWhileTestPausedMarksIgnoredDespiteLatePassAndExitZero() = Fixture().use { f ->
        f.send("\u0016=== RUN   TestPaused\n")
        waitFor { edt { f.console.resultsViewer.root.allTests.any { it.name == "TestPaused" } } }
        f.tests.requestCancellation()
        f.send("\u0016--- PASS: TestPaused (0.01s)\n\u0016PASS\n")
        f.exit(0)
        await(f.tests.completed)
        edt { assertTrue(f.console.resultsViewer.root.allTests.single { it.name == "TestPaused" }.isIgnored) }
        assertTrue(f.tests.output.failures().isEmpty())
    }

    fun testStopAfterExitedKeepsItsRealFailureAndBeforeTestsDoesNotPass() {
        Fixture().use { f ->
            f.send("\u0016=== RUN   TestFailed\n\u0016--- FAIL: TestFailed (0.01s)\n\u0016FAIL\n")
            f.sendExited(1)
            f.tests.requestCancellation()
            f.terminate()
            assertEquals(1, await(f.tests.completed))
            assertEquals(listOf("TestFailed"), f.tests.output.failures().values.single())
        }
        Fixture().use { f ->
            f.tests.requestCancellation(); f.terminate()
            await(f.tests.completed)
            edt { assertFalse("Stopping at a breakpoint before tests is not a successful test run", f.console.resultsViewer.root.isPassed) }
            assertTrue(f.tests.output.failures().isEmpty())
        }
    }

    fun testMissingExitedNeverUsesAdapterZeroAsTestSuccess() = Fixture().use { f ->
        f.send("\u0016=== RUN   TestLost\n")
        f.adapter.finishZero()
        assertEquals(1, await(f.tests.completed))
        edt { assertFalse(f.console.resultsViewer.root.isPassed) }
        assertTrue(f.tests.output.failures().values.flatten().contains("TestLost"))
    }

    fun testPinnedDelveStatusRequiresMatchingProcessAndDisconnectPhaseAndPreservesExitZero() = Fixture().use { f ->
        f.sendProcess(4321)
        f.peerOutput("console", "Process 4321 has exited with status 9\n") // too early
        f.send("\u0016=== RUN   TestPassed\n\u0016--- PASS: TestPassed (0.00s)\n\u0016PASS\n")
        f.peer.disconnectOutput = "Process 4321 has exited with status 0\n"
        f.terminate()
        assertEquals(0, await(f.tests.completed))
        edt { assertTrue(f.console.resultsViewer.root.isPassed) }
        assertTrue(f.tests.output.failures().isEmpty())
    }

    fun testUntrustedExitDiagnosticsNeverOverrideTheTypedExitedEventOrMakeUnknownPass() {
        Fixture().use { f ->
            f.sendProcess(4321)
            f.send("\u0016=== RUN   TestPassed\n\u0016--- PASS: TestPassed (0.00s)\n\u0016PASS\n")
            f.peer.disconnectOutput = "Process 4321 has exited with status 9\n"
            f.exit(0)
            assertEquals(0, await(f.tests.completed))
        }
        for ((category, text) in listOf("console" to "Process 9999 has exited with status 0\n",
            "stdout" to "Process 4321 has exited with status 0\n", "stderr" to "Process 4321 has exited with status 0\n",
            "console" to "user prefix Process 4321 has exited with status 0\n")) {
            Fixture().use { f ->
                f.sendProcess(4321)
                f.send("\u0016=== RUN   TestPassed\n\u0016--- PASS: TestPassed (0.00s)\n\u0016PASS\n")
                f.peer.disconnectCategory = category; f.peer.disconnectOutput = text
                f.terminate()
                assertEquals("Untrusted exit diagnostic: $category $text", 1, await(f.tests.completed))
                edt { assertFalse(f.console.resultsViewer.root.isPassed) }
            }
        }
    }

    fun testCapturedToolchainAndRunValuesSurviveLiveSettingsChanges() = Fixture(mutateAfterCapture = true).use { f ->
        val parameters = f.tests.dapParameters()
        val environment = parameters["env"] as Map<*, *>
        assertEquals("A", environment["FROZEN"])
        assertFalse(environment.containsKey("LIVE_ONLY"))
        assertFalse(f.tests.settings.goExecutable.endsWith("missing-live-go-B"))
        f.send("\u0016=== RUN   TestPassed\n\u0016--- PASS: TestPassed (0.00s)\n\u0016PASS\n")
        f.exit(0)
        assertEquals(0, await(f.tests.completed))
    }

    fun testUnknownDelveVersionDoesNotEnableTheConsoleCompatibilityBridge() = Fixture(versionString = "1.25.0").use { f ->
        f.sendProcess(4321)
        f.send("\u0016=== RUN   TestPassed\n\u0016--- PASS: TestPassed (0.00s)\n\u0016PASS\n")
        f.peer.disconnectOutput = "Process 4321 has exited with status 0\n"
        f.terminate()
        assertEquals(1, await(f.tests.completed))
    }

    fun testDisposingAnOwnedProjectStopsItsConverterAndDisconnectsItsSession() {
        val location = Files.createTempDirectory("open-goland-debug-project-")
        val owner = requireNotNull(ProjectManagerEx.getInstanceEx().newProject(location, OpenProjectTask {
            isNewProject = true; useDefaultProjectAsTemplate = false; projectName = "owned debug lifecycle"
            preloadServices = false; createModule = false
        }))
        try {
            Fixture(ownerProject = owner).use { f ->
                f.send("\u0016=== RUN   TestPaused\n")
                edt { WriteAction.run<RuntimeException> { Disposer.dispose(owner) } }
                await(f.tests.completed)
                waitFor { f.adapter.isProcessTerminated }
                assertTrue(owner.isDisposed)
                assertTrue(f.tests.output.failures().isEmpty())
                val field = f.tests.javaClass.getDeclaredField("converter").apply { isAccessible = true }
                assertTrue((field.get(f.tests) as ProcessHandler).isProcessTerminated)
            }
        } finally {
            if (!owner.isDisposed) edt { WriteAction.run<RuntimeException> { Disposer.dispose(owner) } }
            location.toFile().deleteRecursively()
        }
    }

    fun testConverterFailureCannotTurnARealZeroExitIntoASuccessfulIdeRun() = Fixture().use { f ->
        f.send("\u0016=== RUN   TestPassed\n\u0016--- PASS: TestPassed (0.00s)\n\u0016PASS\n")
        f.sendExited(0)
        val field = f.tests.javaClass.getDeclaredField("converter").apply { isAccessible = true }
        val converter = field.get(f.tests) as ProcessHandler
        converter.destroyProcess()
        assertEquals(1, await(f.tests.completed))
        assertEquals("Keep the real DAP exit distinct from the failed IDE conversion", 0, synchronizedExit(f.tests))
        waitFor { f.adapter.isProcessTerminated }
        edt { assertFalse(f.console.resultsViewer.root.isPassed) }
        assertTrue(f.tests.output.failures().isNotEmpty())
    }

    fun testManualTestProfileUsesTheRealPackageConverterAndTargetEventPath() = Fixture(manualProfile = true).use { f ->
        assertTrue(f.tests.profileSnapshot is GoDebugConfiguration)
        assertEquals(listOf(f.directory.toString()), com.intellij.util.execution.ParametersListUtil.parse(f.tests.values.target, false, true))
        val parameters = f.tests.dapParameters()
        assertEquals(listOf("-tags=integration"), parameters["buildFlags"])
        assertEquals(listOf("-test.v=test2json", "-test.run", "^TestParent$", "-custom", "中文 😀"), parameters["args"])
        f.send("\u0016=== RUN   TestParent\n\u0016=== RUN   TestParent/bad\n\u0016--- FAIL: TestParent/bad (0.01s)\n\u0016--- FAIL: TestParent (0.01s)\n\u0016FAIL\n")
        f.exit(1)
        assertEquals(1, await(f.tests.completed))
        assertEquals(listOf("TestParent/bad"), f.tests.output.failures().values.single())
    }

    private inner class Fixture(private val ownerProject: Project = project, private val mutateAfterCapture: Boolean = false, private val versionString: String = "1.24.2", private val manualProfile: Boolean = false) : AutoCloseable {
        val directory = Files.createTempDirectory("open-goland-debug-test-空 格😀-")
        private val executable = GoToolchain.resolve("go", System.getenv("OPENGOLAND_GO_TEST").orEmpty())
            ?: error("Go is required for the actual test2json integration")
        private val version = controlledDelveVersion(directory, executable, versionString)
        private val vfs = Disposer.newDisposable("debug test source access")
        private val previous = GoProjectSettings.Data().also { XmlSerializerUtil.copyBean(GoProjectSettings.get(ownerProject), it) }
        private val workers = Executors.newCachedThreadPool()
        private val listener = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        private var socket: Socket? = null
        private var rpc: Future<Void>? = null
        val peer = Peer()
        val adapter = Adapter()
        val tests: GoDebugTestSession
        val console: SMTRunnerConsoleView
        private val process: DAPDebugProcess
        private val client = CompletableFuture<GoTestDapClient>()

        init {
            VfsRootAccess.allowRootAccess(vfs, directory.toString())
            Files.writeString(directory.resolve("go.mod"), "module example.test/debug\n\ngo 1.24\n")
            Files.writeString(directory.resolve("probe_test.go"), "package probe\nimport \"testing\"\nfunc TestReal(t *testing.T) {}\n\n// 中文😀\n// location\n")
            GoProjectSettings.get(ownerProject).apply {
                goExecutable = executable; delveExecutable = version.toString(); goos = ""; goarch = ""; cgoEnabled = "0"; buildTags = ""
                environment = "GOPROXY=off\nGOTELEMETRY=off\nGOTOOLCHAIN=local"
            }
            val configuration: RunConfiguration = if (manualProfile) {
                GoDebugConfiguration(ownerProject, GoDebugConfigurationType().configurationFactories.single(), "Controlled manual test").apply {
                    goOptions.apply {
                        mode = "test"; program = directory.toString(); workingDirectory = directory.toString()
                        buildFlags = "-v=false -tags=integration"
                        arguments = "-run ^TestParent$ -args -custom '中文 😀'"
                        environmentVariables = "FROZEN=A"
                    }
                }
            } else GoRunConfiguration(ownerProject, GoTestConfigurationType().configurationFactories.single(), "Controlled debug test", GoRunKind.TEST).apply {
                parameters.workingDirectory = directory.toString(); parameters.environment["FROZEN"] = "A"
            }
            tests = GoDebugTestSession(configuration)
            if (mutateAfterCapture) {
                GoProjectSettings.get(ownerProject).goExecutable = directory.resolve("missing-live-go-B").toString()
                GoProjectSettings.get(ownerProject).delveExecutable = directory.resolve("missing-live-dlv-B").toString()
                GoProjectSettings.get(ownerProject).environment = "LIVE_ONLY=B"
                (configuration as GoRunConfiguration).parameters.environment["FROZEN"] = "B"
            }
            tests.prepare()
            console = edt { tests.createConsole(DefaultDebugExecutor.getDebugExecutorInstance()) as SMTRunnerConsoleView }
            if (ownerProject !== project) Disposer.register(ownerProject, console)
            tests.bindAdapter(adapter)
            adapter.startNotify()
            workers.submit {
                val accepted = listener.accept(); socket = accepted
                val launcher = DSPLauncher.createServerLauncher(peer, accepted.getInputStream(), accepted.getOutputStream(), workers) { it }
                peer.client = launcher.remoteProxy; rpc = launcher.startListening()
            }
            val executor = DefaultDebugExecutor.getDebugExecutorInstance()
            val environment = ExecutionEnvironmentBuilder(ownerProject, executor).runProfile(configuration).runner(DAPDebugRunner()).build()
            val options = object : RunConfigurationOptions() {}
            val descriptor = object : DebugAdapterDescriptor(options, environment, null) {
                override fun getId() = "controlled-go-test"
                override fun getDebugMode() = DebugMode.ATTACH
                override fun getFileType() = GoFileType.INSTANCE
                override fun getDapParameters() = emptyMap<String, Any>()
                override fun getServerReadyConfig(mode: DebugMode) = ServerReadyConfig("127.0.0.1", listener.localPort)
                override fun startServer(): ProcessHandler = error("Controlled DAP peer is already listening")
                override fun createClient(process: DAPDebugProcess, parameters: MutableMap<String, Any>, debug: Boolean,
                    mode: DebugMode, trace: ServerTrace, parent: DAPClient?): DAPClient =
                    GoTestDapClient(process, parameters, debug, mode, trace, parent, tests).also { client.complete(it) }
            }
            DAPServerReadyTracker(descriptor.getServerReadyConfig(DebugMode.ATTACH), DebugMode.ATTACH, tests.events)
            val state = DAPCommandLineState(descriptor, options, environment)
            val session = Proxy.newProxyInstance(XDebugSession::class.java.classLoader, arrayOf(XDebugSession::class.java)) { _, method, _ ->
                when (method.name) {
                    "getProject" -> ownerProject; "getConsoleView" -> console; "getSessionName" -> "Controlled Go test"
                    else -> defaultValue(method.returnType)
                }
            } as XDebugSession
            process = edt { DAPDebugProcess(state, session, DefaultExecutionResult(console, tests.events), true) }
            await(peer.ready)
        }

        fun send(text: String, splitEveryCharacter: Boolean = false) = peerOutput("stdout", text, splitEveryCharacter)
        fun peerOutput(category: String, text: String, split: Boolean = false) {
            await(CompletableFuture.runAsync({
                val chunks = if (split) text.map(Char::toString) else listOf(text)
                chunks.forEach { peer.client.output(OutputEventArguments().apply { this.category = category; output = it }) }
            }, workers))
        }
        fun sendExited(code: Int) {
            await(CompletableFuture.runAsync({ peer.client.exited(ExitedEventArguments().apply { exitCode = code }) }, workers))
            waitFor { synchronizedExit(tests) == code }
        }
        fun sendProcess(pid: Int) {
            await(CompletableFuture.runAsync({ peer.client.process(ProcessEventArguments().apply { name = "controlled owned test"; systemProcessId = pid }) }, workers))
        }
        fun terminate() { await(CompletableFuture.runAsync({ peer.client.terminated(TerminatedEventArguments()) }, workers)) }
        fun exit(code: Int) { sendExited(code); terminate() }
        override fun close() {
            tests.dispose()
            await(tests.completed)
            edt { process.dispose(); if (!Disposer.isDisposed(console)) Disposer.dispose(console) }
            socket?.close(); listener.close(); rpc?.cancel(true); workers.shutdownNow()
            if (!ownerProject.isDisposed) XmlSerializerUtil.copyBean(previous, GoProjectSettings.get(ownerProject))
            Disposer.dispose(vfs)
            directory.toFile().deleteRecursively()
        }
    }

    /** A real host executable: Windows cannot execute a POSIX shebang fixture. */
    private fun controlledDelveVersion(directory: Path, go: String, version: String): Path {
        check(!ApplicationManager.getApplication().isDispatchThread)
        require(version.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+")))
        val tools = Files.createDirectories(directory.resolve(".controlled-tools"))
        val source = tools.resolve("version.go")
        val binary = tools.resolve("controlled-dlv-version" + if (System.getProperty("os.name").startsWith("Windows")) ".exe" else "")
        Files.writeString(source, """
            package main
            import "os"
            func main() {
                if len(os.Args) != 2 || os.Args[1] != "version" { os.Exit(2) }
                _, err := os.Stdout.WriteString("Delve Debugger\nVersion: $version\n")
                if err != nil { os.Exit(3) }
            }
        """.trimIndent())
        val sdk = Path.of(go).toRealPath().parent.parent
        val command = GeneralCommandLine(go, "build", "-trimpath", "-buildvcs=false", "-o", binary.toString(), source.toString())
            .withWorkDirectory(directory.toFile()).withEnvironment("GOROOT", sdk.toString())
            .withEnvironment("GOTOOLCHAIN", "local").withEnvironment("GOWORK", "off")
            .withEnvironment("GOENV", "off").withEnvironment("GOPROXY", "off").withEnvironment("GOSUMDB", "off")
            .withEnvironment("CGO_ENABLED", "0").withEnvironment("GOFLAGS", "").withEnvironment("GOEXPERIMENT", "")
            .withEnvironment("GOOS", "").withEnvironment("GOARCH", "")
        val compiler = CapturingProcessHandler(command).apply { setShouldDestroyProcessRecursively(true) }
        try {
            val output = compiler.runProcess(60_000, true)
            check(!output.isTimeout && !output.isCancelled && output.exitCode == 0) {
                "The owned native Delve-version fixture did not compile: ${output.stdout}${output.stderr}"
            }
            check(Files.isRegularFile(binary) && Files.isExecutable(binary))
            return binary
        } catch (failure: Throwable) {
            if (!compiler.isProcessTerminated) {
                compiler.destroyProcess()
                check(compiler.waitFor(5_000)) { "The owned fixture compiler did not terminate." }
            }
            directory.toFile().deleteRecursively()
            throw failure
        } finally {
            if (!compiler.isProcessTerminated) compiler.destroyProcess()
        }
    }

    private class Adapter : NopProcessHandler() { fun finishZero() = notifyProcessTerminated(0) }
    private class Peer : IDebugProtocolServer {
        lateinit var client: IDebugProtocolClient
        val ready = CompletableFuture<Unit>()
        var disconnectOutput: String? = null
        var disconnectCategory = "console"
        override fun initialize(args: InitializeRequestArguments) = CompletableFuture.completedFuture(Capabilities().apply { supportsConfigurationDoneRequest = true })
        override fun attach(args: MutableMap<String, Any>): CompletableFuture<Void> { client.initialized(); return CompletableFuture.completedFuture(null) }
        override fun configurationDone(args: ConfigurationDoneArguments) = CompletableFuture.completedFuture<Void>(null)
        override fun threads() = CompletableFuture.completedFuture(ThreadsResponse().apply { threads = emptyArray(); ready.complete(Unit) })
        override fun setBreakpoints(args: SetBreakpointsArguments) = CompletableFuture.completedFuture(SetBreakpointsResponse().apply { breakpoints = emptyArray() })
        override fun disconnect(args: DisconnectArguments): CompletableFuture<Void> {
            disconnectOutput?.let { client.output(OutputEventArguments().apply { category = disconnectCategory; output = it }) }
            return CompletableFuture.completedFuture(null)
        }
    }
    private fun synchronizedExit(session: GoDebugTestSession): Int? {
        val field = session.javaClass.getDeclaredField("targetExit").apply { isAccessible = true }
        val lock = session.javaClass.getDeclaredField("lock").apply { isAccessible = true }.get(session)
        return synchronized(lock) { field.get(session) as? Int }
    }
    private fun <T> edt(block: () -> T): T {
        var value: T? = null
        EdtTestUtil.runInEdtAndWait<RuntimeException> { value = block() }
        @Suppress("UNCHECKED_CAST") return value as T
    }
    private fun <T> await(future: CompletableFuture<T>): T = future.get(20, TimeUnit.SECONDS)
    private fun waitFor(test: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (!test() && System.nanoTime() < deadline) java.lang.Thread.sleep(5)
        assertTrue("Native debug test event did not settle", test())
    }
    private fun defaultValue(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false; java.lang.Integer.TYPE -> 0; java.lang.Long.TYPE -> 0L
        else -> if (type.isArray) java.lang.reflect.Array.newInstance(type.componentType, 0) else null
    }
}
