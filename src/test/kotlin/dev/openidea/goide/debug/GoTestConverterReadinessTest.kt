package dev.openidea.goide.debug

import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.openapi.util.Key
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.configurations.RunProfileState
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManagerListener
import com.intellij.openapi.progress.Task
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.util.ProgressIndicatorBase
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ex.ProjectManagerEx
import com.intellij.ide.impl.OpenProjectTask
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.LightPlatformTestCase
import com.intellij.util.xmlb.XmlSerializerUtil
import com.intellij.util.ui.UIUtil
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerManagerListener
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugSession
import com.redhat.devtools.lsp4ij.dap.DAPDebugProcess
import dev.openidea.goide.process.GoOwnedTestExecutable
import dev.openidea.goide.project.GoSdkProbe
import dev.openidea.goide.run.GoRunConfiguration
import dev.openidea.goide.run.GoRunKind
import dev.openidea.goide.run.GoTestConfigurationType
import dev.openidea.goide.settings.GoProjectSettings
import dev.openidea.goide.settings.GoToolchain
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.AccessDeniedException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.CancellationException
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.UUID
import org.jetbrains.concurrency.CancellablePromise

/** Real SDK tool compilation and state.prepare; the adapter never launches a debuggee. */
class GoTestConverterReadinessTest : LightPlatformTestCase() {
    override fun runInDispatchThread() = false

    fun testColdExternalSdkConverterIsReadyBeforeTheAdapterCanStart() = Fixture().use { f ->
        f.start()
        f.awaitCompilerGate()
        assertFalse("Compiler is held before official test2json can emit start", Files.exists(f.adapterStarted))
        try {
            f.prepared.get(1, TimeUnit.SECONDS)
            fail("GoDebugTestState.prepare returned while the selected SDK was still compiling test2json")
        } catch (_: TimeoutException) { }
        assertFalse("No owned adapter may start before the real converter start event", Files.exists(f.adapterStarted))
        f.releaseCompiler()
        f.prepared.get(60, TimeUnit.SECONDS)
        f.awaitFile(f.adapterStarted)
        assertEquals(f.selectedGo.toString(), f.tests.settings.goExecutable)
        f.finishPassingStream()
    }

    fun testUninstrumentedColdExternalSdkUsesItsOwnOfficialConverterAndUnicodeStream() = Fixture(instrumentCompiler = false).use { f ->
        f.start()
        f.prepared.get(90, TimeUnit.SECONDS)
        f.awaitFile(f.adapterStarted)
        assertFalse("This lane has no compiler gate or synthetic converter", Files.exists(f.directory.resolve("compiler-entered")))
        f.assertConverterUsesSelectedGo()
        f.finishPassingStream()
    }

    fun testSessionDisposalDuringCompilationCancelsPreparationAndItsOwnedCompiler() = Fixture().use { f ->
        f.start(); f.awaitCompilerGate()
        Disposer.dispose(f.tests)
        assertCancelledPreparation(f)
    }

    fun testProgressCancellationDuringCompilationNeverStartsTheAdapter() = Fixture().use { f ->
        val indicator = ProgressIndicatorBase()
        f.start(indicator); f.awaitCompilerGate()
        indicator.cancel()
        assertCancelledPreparation(f)
    }

    fun testRunnerPromiseCancellationDuringCompilationStopsItsOwnedTools() = withAsynchronousTasks {
        Fixture().use { f ->
            val promise = f.startThroughRunner()
            f.awaitCompilerGate()
            val start = System.nanoTime()
            ApplicationManager.getApplication().invokeAndWait { promise.cancel() }
            assertTrue("Cancelling preparation must not wait for the compiler on EDT", System.nanoTime() - start < TimeUnit.MILLISECONDS.toNanos(500))
            assertCancelledPreparation(f)
        }
    }

    fun testOwnedProjectDisposalDuringCompilationCancelsPreparation() {
        val location = Files.createTempDirectory("converter-project-")
        val owner = requireNotNull(ProjectManagerEx.getInstanceEx().newProject(location, OpenProjectTask {
            isNewProject = true; useDefaultProjectAsTemplate = false; projectName = "owned converter startup"
            preloadServices = false; createModule = false
        }))
        try {
            Fixture(ownerProject = owner).use { f ->
                f.start(); f.awaitCompilerGate()
                ApplicationManager.getApplication().invokeAndWait { WriteAction.run<RuntimeException> { Disposer.dispose(owner) } }
                assertTrue(owner.isDisposed)
                assertCancelledPreparation(f)
            }
        } finally {
            if (!owner.isDisposed) ApplicationManager.getApplication().invokeAndWait { WriteAction.run<RuntimeException> { Disposer.dispose(owner) } }
            Files.walk(location).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    fun testWrongPackageStartAndEarlyConverterExitCannotStartAnAdapter() {
        for (mode in listOf("wrong-package", "early-exit")) Fixture(converterMode = mode).use { f ->
            f.start()
            try { f.prepared.get(15, TimeUnit.SECONDS); fail("Unready converter must reject preparation: $mode") }
            catch (failure: java.util.concurrent.ExecutionException) {
                assertTrue(failure.cause!!.message.orEmpty(), failure.cause!!.message.orEmpty().contains("test2json"))
            }
            assertFalse(Files.exists(f.adapterStarted))
            assertEquals(1, f.tests.completed.get(5, TimeUnit.SECONDS))
            f.awaitConverterExit()
        }
    }

    fun testRegisteredPreparationProgressCanCancelTheActualRunnerBeforeAnAdapterStarts() = withAsynchronousTasks {
        Fixture().use { f ->
            RegisteredPreparationTask(project, getName()).use { registration ->
                val promise = f.startThroughRunner()
                f.awaitCompilerGate()
                assertTrue("The actual Runner must register a cancellable preparation Task with the platform",
                    registration.started.isDone)
                val entry = registration.started.get(1, TimeUnit.SECONDS)
                assertEquals("Preparing Go test debugging", entry.first.title)
                assertTrue(entry.first.isCancellable)
                assertFalse(entry.first.isModal)
                assertSame(project, entry.first.project)
                assertTrue(registration.startedInBackground)
                assertTrue(registration.indicatorWasCurrent)
                val start = System.nanoTime()
                ApplicationManager.getApplication().invokeAndWait { entry.second.cancel() }
                assertTrue("Cancelling the platform progress must not wait for a compiler on EDT",
                    System.nanoTime() - start < TimeUnit.MILLISECONDS.toNanos(500))
                assertCancelledPreparation(f)
                assertTrue("Task.onCancel must cancel the Runner promise", promise.isCancelled)
                registration.finished.get(5, TimeUnit.SECONDS)
            }
        }
    }

    fun testCancelledFinishedProgressCannotHandAPreparedAdapterToTheUi() = withAsynchronousTasks {
        Fixture().use { f ->
            RegisteredPreparationTask(project, getName(), cancelBeforeFinishedCallback = true).use { registration ->
                val uiStarts = CopyOnWriteArrayList<XDebugProcess>()
                val connection = project.messageBus.connect()
                connection.subscribe(XDebuggerManager.TOPIC, object : XDebuggerManagerListener {
                    override fun processStarted(debugProcess: XDebugProcess) { uiStarts += debugProcess }
                })
                try {
                    val promise = f.startThroughRunner()
                    f.awaitCompilerGate()
                    assertTrue(registration.started.isDone)
                    f.releaseCompiler()
                    registration.ran.get(60, TimeUnit.SECONDS)
                    assertCancelledPreparation(f, adapterMayHaveStarted = true)
                    registration.finished.get(5, TimeUnit.SECONDS)
                    assertTrue(registration.cancelledAtEdtCallback)
                    assertTrue(promise.isCancelled)
                    assertTrue("The queued successful Task callback must recheck cancellation before UI ownership", uiStarts.isEmpty())
                } finally { connection.disconnect() }
            }
        }
    }

    fun testLateProgressCancellationCannotDestroyTheUiClaimedSession() = withAsynchronousTasks {
        Fixture(consoleActivatedByRunner = true).use { f ->
            RegisteredPreparationTask(project, getName()).use { registration ->
                val sessionStarted = CompletableFuture<XDebugSession>()
                val connection = project.messageBus.connect()
                connection.subscribe(XDebuggerManager.TOPIC, object : XDebuggerManagerListener {
                    override fun processStarted(debugProcess: XDebugProcess) {
                        // XDebuggerManager publishes this after the real starter
                        // returned, within Task.onSuccess and before its promise resolves.
                        registration.cancelledAfterUiClaim = true
                        registration.started.getNow(null)?.second?.cancel()
                        sessionStarted.complete(debugProcess.session)
                    }
                })
                var session: XDebugSession? = null
                try {
                    val promise = f.startThroughRunner()
                    f.awaitCompilerGate(); assertTrue(registration.started.isDone)
                    f.releaseCompiler()
                    session = sessionStarted.get(60, TimeUnit.SECONDS)
                    f.prepared.get(10, TimeUnit.SECONDS)
                    registration.finished.get(5, TimeUnit.SECONDS)
                    assertTrue(registration.started.getNow(null).second.isCanceled)
                    assertFalse("The native UI already claimed startup; a late indicator cancel must not reject its promise", promise.isCancelled)
                    assertFalse("Preparation cancellation must not end a session handed to XDebuggerManager", f.tests.completed.isDone)
                    ApplicationManager.getApplication().invokeAndWait {
                        assertSame(f.tests.events, session.debugProcess.processHandler)
                        assertFalse(session.isStopped)
                    }
                    f.assertConverterUsesSelectedGo()
                } finally {
                    connection.disconnect()
                    session?.let { live ->
                        ApplicationManager.getApplication().invokeAndWait { live.stop() }
                        f.tests.completed.get(5, TimeUnit.SECONDS)
                        f.awaitConverterExit()
                        (live.debugProcess as? DAPDebugProcess)?.dispose()
                        ApplicationManager.getApplication().invokeAndWait {
                            val console = live.consoleView
                            val content = live.runContentDescriptor
                            if (!Disposer.isDisposed(content)) Disposer.dispose(content)
                            if (console != null && !Disposer.isDisposed(console)) Disposer.dispose(console)
                            UIUtil.dispatchAllInvocationEvents()
                        }
                    }
                }
            }
        }
    }

    private fun withAsynchronousTasks(action: () -> Unit) {
        // Pinned CoreProgressManager explicitly supports exercising native async
        // Task machinery in headless tests with this property. No custom indicator.
        val key = "intellij.progress.task.ignoreHeadless"
        val before = System.getProperty(key)
        System.setProperty(key, "true")
        try { action() } finally {
            if (before == null) System.clearProperty(key) else System.setProperty(key, before)
        }
    }

    private class RegisteredPreparationTask(owner: Project, private val testName: String, private val cancelBeforeFinishedCallback: Boolean = false) : AutoCloseable {
        val started = CompletableFuture<Pair<Task, ProgressIndicator>>()
        val ran = CompletableFuture<Unit>()
        val finished = CompletableFuture<Unit>()
        @Volatile var startedInBackground = false
        @Volatile var indicatorWasCurrent = false
        @Volatile var cancelledAtEdtCallback = false
        @Volatile var cancelledAfterUiClaim = false
        private val connection = ApplicationManager.getApplication().messageBus.connect()
        init {
            connection.subscribe(ProgressManagerListener.TOPIC, object : ProgressManagerListener {
                override fun beforeTaskStart(task: Task, indicator: ProgressIndicator) {
                    if (task.project === owner && task.title == "Preparing Go test debugging") {
                        startedInBackground = !ApplicationManager.getApplication().isDispatchThread
                        indicatorWasCurrent = ProgressManager.getInstance().progressIndicator === indicator
                        started.complete(task to indicator)
                    }
                }
                override fun afterTaskStart(task: Task, indicator: ProgressIndicator) {
                    if (started.getNow(null)?.first === task) ran.complete(Unit)
                }
                override fun beforeTaskFinished(task: Task) {
                    if (cancelBeforeFinishedCallback && started.getNow(null)?.first === task) {
                        check(ApplicationManager.getApplication().isDispatchThread)
                        check(ran.isDone)
                        started.getNow(null).second.cancel()
                        cancelledAtEdtCallback = true
                    }
                }
                override fun afterTaskFinished(task: Task) {
                    if (started.getNow(null)?.first === task) finished.complete(Unit)
                }
            })
        }
        override fun close() {
            connection.disconnect()
            System.getProperty("opengoland.preparationProgressEvidence")?.let { destination ->
                check(!ApplicationManager.getApplication().isDispatchThread)
                val captured = started.getNow(null)
                val report = JsonObject().apply {
                    addProperty("test", testName)
                    addProperty("platformTaskRegistered", captured != null)
                    captured?.let { (task, indicator) ->
                        addProperty("taskClass", task.javaClass.name); addProperty("title", task.title)
                        addProperty("cancellable", task.isCancellable); addProperty("modal", task.isModal)
                        addProperty("indicatorClass", indicator.javaClass.name); addProperty("indicatorCancelled", indicator.isCanceled)
                    }
                    addProperty("startedOnBackground", startedInBackground); addProperty("indicatorWasCurrent", indicatorWasCurrent)
                    addProperty("taskRunFinished", ran.isDone); addProperty("taskCallbacksFinished", finished.isDone)
                    addProperty("cancelledAtEdtCallback", cancelledAtEdtCallback); addProperty("cancelledAfterUiClaim", cancelledAfterUiClaim)
                    addProperty("headlessAsyncTaskMode", "intellij.progress.task.ignoreHeadless=true, restored in finally")
                    addProperty("actualGoDebuggeeLaunched", false)
                }
                val target = Files.createDirectories(Path.of(destination))
                Files.writeString(target.resolve(UUID.randomUUID().toString() + ".json"), report.toString())
            }
        }
    }

    private fun assertCancelledPreparation(f: Fixture, adapterMayHaveStarted: Boolean = false) {
        try { f.prepared.get(5, TimeUnit.SECONDS); fail("Cancelled preparation must not start an adapter") }
        catch (failure: java.util.concurrent.ExecutionException) {
            assertTrue(failure.cause.toString(), failure.cause is CancellationException || failure.cause is ProcessCanceledException)
        } catch (_: CancellationException) { }
        f.awaitConverterExit()
        if (!adapterMayHaveStarted) assertFalse(Files.exists(f.adapterStarted))
        assertEquals("Cancelled startup is ignored, never a failed or successful test", 0, f.tests.completed.get(5, TimeUnit.SECONDS))
        assertTrue(f.tests.output.failures().isEmpty())
    }

    private inner class Fixture(private val instrumentCompiler: Boolean = true, private val converterMode: String = "official",
        private val ownerProject: Project = project, private val consoleActivatedByRunner: Boolean = false) : AutoCloseable {
        private val ownedRoot = Files.createTempDirectory("go-converter-").toRealPath()
        val directory = Files.createDirectory(ownedRoot.resolve("外部 SDK 中文😀"))
        private val helper = GoOwnedTestExecutable.compile(directory.resolve(GoOwnedTestExecutable.name("owned-tool")), TOOL)
        private val before = GoProjectSettings.Data().also { XmlSerializerUtil.copyBean(GoProjectSettings.get(ownerProject), it) }
        private val sourceGo = Path.of(System.getProperty("opengoland.externalGo")
            ?: requireNotNull(GoToolchain.resolve("go", System.getProperty("opengoland.testGo")
                ?: System.getenv("OPENGOLAND_GO_TEST").orEmpty()))).toRealPath()
        private val sourceSdk = sourceGo.parent.parent
        private val originalGoSha = digest(sourceGo)
        private val originalSdkSha = treeDigest(sourceSdk)
        private val suffix = if (System.getProperty("os.name").startsWith("Windows")) ".exe" else ""
        private val host = Files.list(sourceSdk.resolve("pkg/tool")).use { dirs ->
            dirs.filter { Files.isDirectory(it) && Files.isRegularFile(it.resolve("compile$suffix")) }.toList().single().fileName.toString()
        }
        private val originalCompiler = sourceSdk.resolve("pkg/tool/$host/compile$suffix")
        private val originalCompilerSha = digest(originalCompiler)
        private val originalTool = sourceSdk.resolve("pkg/tool/$host/test2json$suffix")
        private val originalToolSha = originalTool.takeIf(Files::isRegularFile)?.let(::digest)
        val selectedGo = if (instrumentCompiler || converterMode != "official" || Files.isRegularFile(originalTool)) copySdkWithoutDerivedConverter() else sourceGo
        val adapterStarted = directory.resolve("adapter-started")
        private val gateEntered = directory.resolve("compiler-entered")
        private val gateRelease = directory.resolve("compiler-release")
        private val executor = Executors.newSingleThreadExecutor()
        val prepared = CompletableFuture<Unit>()
        private val eventText = CopyOnWriteArrayList<String>()
        private val ownedHandles = linkedMapOf<Long, ProcessHandle>()
        private var started = false
        private var gateRecord: String? = null
        private var selectedVersion = ""
        private var officialStart: JsonObject? = null
        private var officialConverter: Path? = null
        private var officialConverterSha: String? = null
        private var officialConverterBuildInfo: String? = null
        val state: GoDebugTestState
        val tests: GoDebugTestSession
        private val runner = GoDebugTestRunner()
        private val environment: ExecutionEnvironment
        private var adapter: OSProcessHandler? = null

        init {
            val sdk = GoSdkProbe.inspect(selectedGo.toString())
            check(sdk.executable == selectedGo.toRealPath())
            selectedVersion = sdk.version
            if (instrumentCompiler && converterMode == "official")
                Files.copy(helper, sdk.root.resolve("pkg/tool/$host/compile$suffix"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
            if (converterMode != "official")
                Files.copy(helper, sdk.root.resolve("pkg/tool/$host/test2json$suffix"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
            Files.writeString(directory.resolve("go.mod"), "module example.test/converter\n\ngo 1.26\n")
            Files.writeString(directory.resolve("ready_test.go"), "package converter\nimport \"testing\"\nfunc TestReady(t *testing.T) {}\n")
            GoProjectSettings.get(ownerProject).apply {
                goExecutable = selectedGo.toString(); delveExecutable = helper.toString()
                buildTags = ""; goos = ""; goarch = ""; cgoEnabled = "0"
                environment = "GOCACHE=${directory.resolve("cold-cache")}\nGOMAXPROCS=1\nGOFLAGS=\n" +
                    "GOENV=off\nGOWORK=off\nGOTOOLCHAIN=local\nGOPROXY=off\nGOSUMDB=off\nGOTELEMETRY=off\n" +
                    "OWNED_GATE=${directory}\nOWNED_CONVERTER_MODE=$converterMode\nOWNED_REAL_COMPILE=$originalCompiler\nGOROOT=${sdk.root}"
            }
            val configuration = GoRunConfiguration(ownerProject, GoTestConfigurationType().configurationFactories.single(),
                "Owned converter readiness", GoRunKind.TEST).apply { parameters.workingDirectory = directory.toString() }
            environment = ExecutionEnvironmentBuilder(ownerProject, DefaultDebugExecutor.getDebugExecutorInstance())
                .runProfile(configuration).runner(runner).build()
            state = GoDebugTestState(environment, configuration)
            tests = state.testSession
            tests.events.addProcessListener(object : ProcessAdapter() {
                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) { eventText += event.text }
            })
            if (!consoleActivatedByRunner) tests.events.activate()
        }

        fun start(indicator: ProgressIndicatorBase? = null) { started = true; executor.submit {
            try {
                if (indicator == null) state.prepare()
                else ProgressManager.getInstance().runProcess({ state.prepare() }, indicator)
                prepared.complete(Unit)
            }
            catch (failure: Throwable) { prepared.completeExceptionally(failure) }
        } }

        fun startThroughRunner(): CancellablePromise<RunContentDescriptor?> {
            var result: CancellablePromise<RunContentDescriptor?>? = null
            ApplicationManager.getApplication().invokeAndWait {
                @Suppress("UNCHECKED_CAST")
                // The SDK framework calls this protected AsyncProgramRunner delegate.
                // Invoke that real method; do not copy its callback or inject a promise.
                val method = GoDebugTestRunner::class.java.getDeclaredMethod("execute", ExecutionEnvironment::class.java, RunProfileState::class.java)
                    .apply { isAccessible = true }
                val actual = method.invoke(runner, environment, state) as CancellablePromise<RunContentDescriptor?>
                actual.onSuccess { prepared.complete(Unit) }.onError { prepared.completeExceptionally(it) }
                result = actual
            }
            started = true
            return requireNotNull(result)
        }

        fun awaitCompilerGate() {
            awaitFile(gateEntered, 90)
            assertFalse(Files.exists(gateRelease))
            gateRecord = Files.readString(gateEntered)
            captureOwnedProcesses()
        }
        fun releaseCompiler() { Files.writeString(gateRelease, "release only our compiler invocation") }
        private fun converter(): OSProcessHandler? = GoDebugTestSession::class.java.getDeclaredField("converter")
            .apply { isAccessible = true }.get(tests) as? OSProcessHandler
        private fun captureOwnedProcesses() {
            converter()?.process?.toHandle()?.let { parent ->
                ownedHandles[parent.pid()] = parent
                parent.descendants().use { children -> children.forEach { ownedHandles[it.pid()] = it } }
            }
        }
        fun assertConverterUsesSelectedGo() {
            val actual = requireNotNull(converter())
            assertEquals(selectedGo.toRealPath(), Path.of(actual.process.info().command().orElseThrow()).toRealPath())
            @Suppress("UNCHECKED_CAST")
            val ready = GoDebugTestSession::class.java.getDeclaredField("converterReady").apply { isAccessible = true }
                .get(tests) as CompletableFuture<JsonObject>
            officialStart = ready.get(1, TimeUnit.SECONDS).deepCopy()
            assertEquals("start", officialStart!!.get("Action").asString)
            assertEquals("example.test/converter", officialStart!!.get("Package").asString)
            captureOwnedProcesses()
            officialConverter = actual.process.descendants().use { children -> children.map { it.info().command().orElse("") }
                .filter { it.isNotBlank() && Path.of(it).fileName.toString() == "test2json$suffix" }
                .map(Path::of).toList().single() }
            officialConverterSha = digest(officialConverter!!)
            val command = GoToolchain.command(ownerProject, "go", listOf("version", "-m", officialConverter.toString()), tests.settings)
                .withWorkDirectory(directory.toFile()).withCharset(StandardCharsets.UTF_8)
            val metadata = CapturingProcessHandler(command).apply { setShouldDestroyProcessRecursively(true) }
            ownedHandles[metadata.process.pid()] = metadata.process.toHandle()
            try {
                val output = metadata.runProcess(10_000, true)
                assertEquals(output.stderr, 0, output.exitCode)
                assertFalse(output.isTimeout || output.isCancelled)
                officialConverterBuildInfo = output.stdout
                assertTrue(output.stdout, output.stdout.lineSequence().first().contains(": $selectedVersion"))
                assertTrue(output.stdout, Regex("(?m)^\\s*path\\s+cmd/test2json\\s*$").containsMatchIn(output.stdout))
            } finally { if (!metadata.isProcessTerminated) metadata.destroyProcess() }
        }
        fun awaitConverterExit() {
            val process = converter() ?: return
            assertTrue("Owned converter/compiler subtree must exit after cancelled or failed startup", process.waitFor(8_000))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (ownedHandles.values.any { it.isAlive } && System.nanoTime() < deadline) Thread.sleep(5)
            assertTrue("Owned compiler/gate processes remain", ownedHandles.values.none { it.isAlive })
        }
        fun finishPassingStream() {
            assertConverterUsesSelectedGo()
            tests.targetOutput("\u0016=== RUN   TestReady\n\u0016=== RUN   TestReady/中文😀\n" +
                "\u0016--- PASS: TestReady/中文😀 (0.00s)\n\u0016--- PASS: TestReady (0.00s)\n\u0016PASS\n")
            tests.exitedEvent(0); tests.end()
            assertEquals(0, tests.completed.get(3, TimeUnit.SECONDS))
            assertTrue(tests.output.failures().isEmpty())
            assertTrue(eventText.joinToString("").contains("中文😀"))
            Files.writeString(directory.resolve("adapter-exit"), "owned adapter finished; no debuggee")
        }
        fun awaitFile(path: Path, seconds: Long = 5) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
            while (!Files.exists(path) && System.nanoTime() < deadline) {
                if (prepared.isCompletedExceptionally) prepared.get(1, TimeUnit.SECONDS)
                if (tests.completed.isDone) fail("Converter finished before the compiler gate: ${eventText.joinToString("")}")
                Thread.sleep(5)
            }
            assertTrue("Owned process did not publish $path; ${eventText.joinToString("")}", Files.exists(path))
        }

        private fun copySdkWithoutDerivedConverter(): Path {
            // Independent copies, never hardlinks: the original SDK is immutable.
            // The instrumented lane separately replaces only this copy's compiler.
            val target = directory.resolve("external-sdk")
            Files.walk(sourceSdk).use { paths -> paths.forEach { source ->
                val relative = sourceSdk.relativize(source)
                if (source != originalTool) {
                    val destination = target.resolve(relative)
                    if (Files.isDirectory(source)) Files.createDirectories(destination)
                    else Files.copy(source, destination, StandardCopyOption.COPY_ATTRIBUTES)
                }
            } }
            return target.resolve("bin/go$suffix")
        }

        override fun close() {
            captureOwnedProcesses()
            adapter = GoDebugTestSession::class.java.getDeclaredField("adapter").apply { isAccessible = true }.get(tests) as? OSProcessHandler
            adapter?.let { if (!it.isStartNotified) it.startNotify() }
            Disposer.dispose(tests)
            if (started) runCatching { prepared.get(90, TimeUnit.SECONDS) }
            awaitConverterExit()
            releaseCompiler()
            adapter?.let { if (!it.isProcessTerminated) it.destroyProcess(); assertTrue(it.waitFor(12_000)) }
            executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            if (!ownerProject.isDisposed) XmlSerializerUtil.copyBean(before, GoProjectSettings.get(ownerProject))
            assertEquals(originalGoSha, digest(sourceGo))
            assertEquals(originalCompilerSha, digest(originalCompiler))
            assertEquals(originalToolSha, originalTool.takeIf(Files::isRegularFile)?.let(::digest))
            assertEquals("No byte in the original SDK may change", originalSdkSha, treeDigest(sourceSdk))
            System.getProperty("opengoland.converterReadinessEvidence")?.let { output ->
                val report = JsonObject().apply {
                    addProperty("test", getName()); addProperty("selectedGo", selectedGo.toString())
                    addProperty("selectedVersion", selectedVersion); addProperty("originalGoSha256", originalGoSha)
                    addProperty("originalCompiler", originalCompiler.toString()); addProperty("originalCompilerSha256", originalCompilerSha)
                    addProperty("originalSdkTreeSha256", originalSdkSha); addProperty("originalSdkUnchanged", true)
                    addProperty("instrumentedCompiler", instrumentCompiler && converterMode == "official")
                    addProperty("converterMode", converterMode); addProperty("actualGoDebuggeeLaunched", false)
                    officialStart?.let { add("officialStartEvent", it) }
                    officialConverter?.let { addProperty("officialConverterExecutable", it.toString()) }
                    officialConverterSha?.let { addProperty("officialConverterSha256", it) }
                    officialConverterBuildInfo?.let { addProperty("officialConverterBuildInfo", it) }
                    gateRecord?.let { add("compilerGate", JsonParser.parseString(it)) }
                    add("ownedPids", JsonParser.parseString(ownedHandles.keys.toString()))
                    addProperty("allOwnedConverterDescendantsAbsent", ownedHandles.values.none { it.isAlive })
                    addProperty("adapterPid", adapter?.process?.pid()); addProperty("adapterAliveAfterCleanup", adapter?.process?.isAlive ?: false)
                    addProperty("output", eventText.joinToString(""))
                }
                val target = Files.createDirectories(Path.of(output))
                Files.writeString(target.resolve(UUID.randomUUID().toString() + ".json"), report.toString())
            }
            val paths = Files.walk(ownedRoot).use { it.sorted(Comparator.reverseOrder()).toList() }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            for (path in paths) while (true) {
                try { Files.deleteIfExists(path); break }
                catch (failure: AccessDeniedException) { if (System.nanoTime() >= deadline) throw failure; Thread.sleep(10) }
            }
            assertTrue(Files.notExists(ownedRoot))
        }
    }

    private fun digest(path: Path) = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }
    private fun treeDigest(root: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.walk(root).use { paths -> paths.filter(Files::isRegularFile).sorted().forEach { path ->
            digest.update(root.relativize(path).toString().toByteArray(Charsets.UTF_8)); digest.update(0)
            digest.update(digest(path).toByteArray(Charsets.UTF_8)); digest.update(0)
        } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        val TOOL = """
            package main
            import("encoding/json";"fmt";"os";"os/exec";"path/filepath";"strings";"time")
            func main(){
                directory:=os.Getenv("OWNED_GATE")
                if strings.TrimSuffix(filepath.Base(os.Args[0]),".exe")=="test2json"{
                    if os.Getenv("OWNED_CONVERTER_MODE")=="early-exit"{fmt.Fprintln(os.Stderr,"owned converter early failure");os.Exit(23)}
                    fmt.Println(`{"Action":"start","Package":"wrong.package"}`)
                    for{time.Sleep(time.Second)}
                }
                if strings.TrimSuffix(filepath.Base(os.Args[0]),".exe")=="compile"{
                    version:=false;selected:=false
                    for _,arg:=range os.Args[1:]{
                        if strings.HasPrefix(arg,"-V"){version=true}
                        dir:=filepath.Dir(arg)
                        if strings.HasSuffix(arg,".go")&&filepath.Base(dir)=="test2json"&&filepath.Base(filepath.Dir(dir))=="cmd"{selected=true}
                    }
                    if selected&&!version{
                        data,_:=json.Marshal(map[string]any{"pid":os.Getpid(),"actualCompiler":os.Getenv("OWNED_REAL_COMPILE"),"goroot":os.Getenv("GOROOT"),"args":os.Args[1:]})
                        if err:=os.WriteFile(filepath.Join(directory,"compiler-entered"),data,0600);err!=nil{panic(err)}
                        deadline:=time.Now().Add(90*time.Second)
                        for{if _,err:=os.Stat(filepath.Join(directory,"compiler-release"));err==nil{break};if time.Now().After(deadline){panic("owned compiler gate deadline")};time.Sleep(10*time.Millisecond)}
                    }
                    command:=exec.Command(os.Getenv("OWNED_REAL_COMPILE"),os.Args[1:]...);command.Stdin=os.Stdin;command.Stdout=os.Stdout;command.Stderr=os.Stderr
                    if err:=command.Run();err!=nil{if e,ok:=err.(*exec.ExitError);ok{os.Exit(e.ExitCode())};panic(err)}
                    return
                }
                if len(os.Args)>1&&os.Args[1]=="version"{fmt.Println("Delve Debugger\nVersion: 1.27.2\nBuild: owned converter fixture");return}
                if len(os.Args)>1&&os.Args[1]=="dap"{
                    os.WriteFile(filepath.Join(directory,"adapter-started"),[]byte("owned adapter; no target"),0600)
                    for{if _,err:=os.Stat(filepath.Join(directory,"adapter-exit"));err==nil{return};time.Sleep(10*time.Millisecond)}
                }
                panic("unknown owned fixture invocation")
            }
        """.trimIndent()
    }
}
