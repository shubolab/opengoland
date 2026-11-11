package com.redhat.devtools.lsp4ij

import com.google.gson.Gson
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.EdtTestUtil
import com.intellij.testFramework.LightPlatformTestCase
import com.redhat.devtools.lsp4ij.client.features.LSPClientFeatures
import com.redhat.devtools.lsp4ij.internal.capabilities.ClientCapabilitiesFactory
import com.redhat.devtools.lsp4ij.server.OSProcessStreamConnectionProvider
import com.redhat.devtools.lsp4ij.server.definition.LanguageServerDefinition
import dev.openidea.goide.lsp.GoLanguageServerFactory
import dev.openidea.goide.lsp.GoLspBuildContextEpoch
import dev.openidea.goide.settings.GoProjectSettings
import dev.openidea.goide.settings.GoToolchain
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageServer
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Real factory process + initialize exits and real cross-module gopls responses. No simulated parent claims. */
class GoGoworkGoplsTest : LightPlatformTestCase() {
    override fun runInDispatchThread() = false

    fun testExplicitAutoResolvesWorkspaceModuleThroughFactory() { fixture(Mode.AUTO) }
    fun testExplicitEmptyResolvesWorkspaceModuleThroughFactory() { fixture(Mode.EMPTY) }
    fun testExplicitOffKeepsModuleIsolationThroughFactory() { fixture(Mode.OFF) }
    fun testExplicitAbsoluteWorkspaceResolvesModuleThroughFactory() { fixture(Mode.ABSOLUTE) }

    private fun fixture(mode: Mode) {
        // Reuse the same required selected tools and worker caches as the existing
        // native integration tests; never copy SDKs/apps or create per-mode caches.
        val go = Path.of(requireNotNull(System.getProperty("opengoland.testGo")
            ?: System.getenv("OPENGOLAND_GO_TEST")) { "Set OPENGOLAND_GO_TEST to the selected real SDK" }).toRealPath()
        val gopls = Path.of(requireNotNull(System.getProperty("opengoland.completionGopls")
            ?: System.getenv("OPENGOLAND_GOPLS_TEST")) { "Set OPENGOLAND_GOPLS_TEST to the selected real gopls" }).toRealPath()
        val root = Files.createTempDirectory("owned gowork 中文😀 ").toRealPath()
        val consumer = Files.createDirectories(root.resolve("consumer"))
        val providerRoot = Files.createDirectories(root.resolve("provider"))
        val workspace = root.resolve("go.work")
        val module = "example.test/gowork/" + UUID.randomUUID().toString().replace("-", "")
        val target = providerRoot.resolve("target.go")
        val source = consumer.resolve("source.go")
        val targetText = "package provider\n\nfunc Exported() string { return \"workspace 中文😀\" }\n"
        val sourceText = "package consumer\n\nimport provider \"$module/provider\"\n\n" +
            "func Local() string { return \"local\" }\n" +
            "var FromLocal = Local()\n" +
            "var FromWorkspace = /* 中文😀 */ provider.Exported()\n"
        val rawGoWork = when (mode) {
            Mode.AUTO -> "auto"
            Mode.EMPTY -> ""
            Mode.OFF -> "off"
            Mode.ABSOLUTE -> workspace.toString()
        }
        val rawEnvironment = listOf("GOENV=off", "GOFLAGS=", "GOTOOLCHAIN=local", "GOPROXY=off", "GOSUMDB=off",
            "GOPRIVATE=", "GONOPROXY=", "GONOSUMDB=", "GOTELEMETRY=off", "GOOS=", "GOARCH=", "CGO_ENABLED=0",
            "GOMAXPROCS=2", "GOWORK=$rawGoWork").joinToString("\n")
        val previous = GoToolchain.capture(project)
        val factory = GoLanguageServerFactory()
        val definition = requireNotNull(LanguageServersRegistry.getInstance().getServerDefinition("openidea.gopls"))
        val wrapper = OwnedWrapper(project, definition, factory)
        val messages = CopyOnWriteArrayList<Map<String, Any?>>()
        val protocolWire = StringWriter()
        val ownedStderr = CopyOnWriteArrayList<String>()
        val pool = Executors.newCachedThreadPool()
        val processSampler = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "owned-gowork-process-sampler").apply { isDaemon = true }
        }
        val children = ConcurrentHashMap<Long, ProcessHandle>()
        var sampling: ScheduledFuture<*>? = null
        var connection: OSProcessStreamConnectionProvider? = null
        var owned: ProcessHandle? = null
        var remote: LanguageServer? = null
        var transport: Future<Void>? = null
        var primary: Throwable? = null
        val evidence = linkedMapOf<String, Any?>("mode" to mode.name, "root" to root.toString(),
            "selectedGo" to go.toString(), "selectedGoSha256" to sha(go),
            "selectedGopls" to gopls.toString(), "selectedGoplsSha256" to sha(gopls),
            "rawGowork" to rawGoWork, "noReplace" to true, "simulatedParentEnvironment" to false)
        try {
            Files.writeString(workspace, "go 1.24.0\n\nuse (\n ./consumer\n ./provider\n)\n")
            Files.writeString(consumer.resolve("go.mod"), "module $module/consumer\n\ngo 1.24.0\n\nrequire $module/provider v0.0.0\n")
            Files.writeString(providerRoot.resolve("go.mod"), "module $module/provider\n\ngo 1.24.0\n")
            Files.writeString(source, sourceText); Files.writeString(target, targetText)
            edt {
                GoProjectSettings.get(project).apply {
                    goExecutable = go.toString(); goplsExecutable = gopls.toString()
                    buildTags = ""; goos = ""; goarch = ""; cgoEnabled = "0"; environment = rawEnvironment
                }
            }
            val provider = factory.createConnectionProvider(project) as OSProcessStreamConnectionProvider
            connection = provider
            // The light project's basePath is not this owned fixture. This is
            // the only command adjustment: keep the actual factory environment.
            provider.commandLine.withWorkDirectory(consumer.toFile())
            evidence["childGoworkKeyPresent"] = provider.commandLine.environment.containsKey("GOWORK")
            evidence["childGowork"] = provider.commandLine.environment["GOWORK"]
            provider.addLogErrorHandler {
                ownedStderr += it
                messages += mapOf("source" to "stderr", "message" to it)
            }
            provider.start()
            val handle = ProcessHandle.of(requireNotNull(provider.pid) { "Owned provider PID missing after start" }).orElseThrow()
            owned = handle
            // Keep exact owned handles even if a child is later reparented.
            // A single snapshot just before shutdown misses children spawned
            // while the real initial workspace diagnosis is still running.
            sampling = processSampler.scheduleWithFixedDelay({
                handle.descendants().use { found -> found.forEach { children[it.pid()] = it } }
            }, 0, 20, TimeUnit.MILLISECONDS)
            evidence["ownedGoplsPid"] = handle.pid()
            val client = Client(messages)
            val launcher = wrapper.features.createLauncherBuilder<LanguageServer>()
                .setRemoteInterface(LanguageServer::class.java).setLocalService(client)
                .traceMessages(PrintWriter(protocolWire, true))
                .setInput(provider.inputStream).setOutput(provider.outputStream).setExecutorService(pool).create()
            transport = launcher.startListening()
            val server = launcher.remoteProxy; remote = server
            val params = InitializeParams().apply {
                capabilities = ClientCapabilitiesFactory.create(null)
                rootUri = consumer.toUri().toASCIIString()
                workspaceFolders = listOf(WorkspaceFolder(consumer.toUri().toASCIIString(), "owned-gowork"))
            }
            wrapper.features.initializeParams(params)
            val initializeEnvironment = (params.initializationOptions as Map<*, *>)["env"] as Map<*, *>
            evidence["initializeGoworkKeyPresent"] = initializeEnvironment.containsKey("GOWORK")
            evidence["initializeGowork"] = initializeEnvironment["GOWORK"]
            // Preserve every actual factory option; add only test progress verbosity.
            val factoryOptions = params.initializationOptions as Map<*, *>
            params.initializationOptions = buildMap<String, Any> {
                factoryOptions.forEach { (key, value) -> put(key as String, requireNotNull(value)) }
                put("verboseWorkDoneProgress", true)
            }
            evidence["testVerboseWorkDoneProgress"] = true
            evidence["clientWorkDoneProgress"] = params.capabilities.window?.workDoneProgress
            assertTrue("Actual client capabilities must advertise work-done progress for the protocol barrier",
                params.capabilities.window?.workDoneProgress == true)
            server.initialize(params).get(45, TimeUnit.SECONDS)
            server.initialized(InitializedParams())
            server.textDocumentService.didOpen(DidOpenTextDocumentParams(TextDocumentItem(source.toUri().toASCIIString(), "go", 1, sourceText)))

            // Definition can succeed through cmd/go despite incorrect gopls workspace
            // metadata. Diagnose that real workspace error before adapter assertions.
            val local = query(server, source, position(sourceText, sourceText.lastIndexOf("Local()")))
            val cross = query(server, source, position(sourceText, sourceText.indexOf("Exported()")))
            evidence["localDefinition"] = local; evidence["crossModuleDefinition"] = cross
            assertEquals("The local symbol must resolve even in the off control: $local", null, local.failureKind)
            assertEquals(listOf(Target(source.toUri().toASCIIString(), range(sourceText, sourceText.indexOf("Local()"), "Local".length))), local.targets)
            assertEquals("A real response is required; timeout/transport errors are not an off control: $cross", null, cross.failureKind)
            if (mode == Mode.OFF) {
                assertTrue("GOWORK=off must not find the sibling module without replace/network: $cross", cross.targets.isEmpty())
            } else {
                assertEquals("The workspace must resolve the sibling module's exact declaration",
                    listOf(Target(target.toUri().toASCIIString(), range(targetText, targetText.indexOf("Exported"), "Exported".length))), cross.targets)
            }
            // Setting-up progress alone does not wait for initial diagnosis in
            // gopls0.24. This matching-token end covers that initial diagnosis group,
            // not arbitrary later didOpen diagnostics or global server idleness.
            val diagnosis = try {
                client.initialDiagnosisFinished.get(45, TimeUnit.SECONDS)
            } catch (timeout: TimeoutException) {
                evidence["initialDiagnosisBarrier"] = mapOf("status" to "deadline-not-genuine-red")
                throw AssertionError("Initial diagnosis protocol barrier timed out; fixture failure, not workspace RED", timeout)
            }
            evidence["initialDiagnosisBarrier"] = diagnosis
            val workspaceErrors = messages.filter {
                it["type"] in setOf("Error", "Warning") || it["workspaceDiagnostic"] == true
            }
            evidence["workspaceErrorNotifications"] = workspaceErrors
            assertTrue("Initial workspace setup/diagnosis reported errors or warnings: $workspaceErrors", workspaceErrors.isEmpty())
            val expected = if (rawGoWork == "auto") "" else rawGoWork
            assertEquals(true, evidence["childGoworkKeyPresent"])
            assertEquals(expected, evidence["childGowork"])
            assertEquals(true, evidence["initializeGoworkKeyPresent"])
            assertEquals(expected, evidence["initializeGowork"])
            assertEquals("User settings must retain the raw value", rawEnvironment, GoProjectSettings.get(project).environment)
            if (mode == Mode.AUTO) {
                val epoch = wrapper.features as GoLspBuildContextEpoch
                assertTrue("The initialized raw settings snapshot must be current", epoch.isBuildContextCurrent())
                edt { GoProjectSettings.get(project).environment = rawEnvironment.replace("GOWORK=auto", "GOWORK=") }
                assertFalse("Normalization must not collapse the raw settings epoch", epoch.isBuildContextCurrent())
                edt { GoProjectSettings.get(project).environment = rawEnvironment }
                assertTrue(epoch.isBuildContextCurrent())
            }
            evidence["status"] = "pass-real-cross-module-and-environment-checks"
        } catch (error: Throwable) {
            primary = error
            evidence["status"] = "failed"
            evidence["failureClass"] = error.javaClass.name; evidence["failureMessage"] = error.message
            throw error
        } finally {
            // Observe listening state before our own stop/cancel changes its outcome.
            val listening = transport
            evidence["transportAtFinallyEntry"] = buildMap<String, Any?> {
                put("present", listening != null)
                if (listening != null) {
                    val done = listening.isDone
                    put("isDone", done); put("isCancelled", listening.isCancelled)
                    if (done) try {
                        listening.get()
                        put("completion", "normal")
                    } catch (error: Throwable) {
                        var cause = error
                        while ((cause is ExecutionException || cause is CompletionException) && cause.cause != null) cause = cause.cause!!
                        put("completion", "exceptional")
                        put("exceptionClass", cause.javaClass.name); put("exceptionMessage", cause.message)
                    }
                }
            }
            evidence["protocolWireCharactersAtFinallyEntry"] = protocolWire.buffer.length
            val cleanup = mutableListOf<Throwable>()
            val explicitSignals = mutableListOf<Map<String, Any?>>()
            fun signal(handle: ProcessHandle, action: String, role: String, reason: String, send: () -> Boolean) {
                val event = linkedMapOf<String, Any?>("pid" to handle.pid(), "action" to action,
                    "role" to role, "reason" to reason, "attemptedAtUtc" to java.time.Instant.now().toString())
                explicitSignals += event
                try {
                    event["returnValue"] = send()
                    event["outcome"] = "returned"
                } catch (error: Throwable) {
                    event["outcome"] = "threw"
                    event["error"] = error.javaClass.name + ": " + error.message
                    throw error
                } finally { event["completedAtUtc"] = java.time.Instant.now().toString() }
            }
            fun attempt(action: () -> Unit) { try { action() } catch (error: Throwable) { cleanup += error } }
            // Capture only handles below the process launched by this fixture.
            attempt { owned?.descendants()?.use { found -> found.forEach { children[it.pid()] = it } } }
            // Give the protocol shutdown/exit a chance to finish naturally.
            // stop() before EOF can race a still-running Windows compiler child.
            attempt { remote?.shutdown()?.get(3, TimeUnit.SECONDS) }
            attempt { remote?.exit() }
            owned?.let { handle -> attempt {
                if (handle.isAlive) try { handle.onExit().get(8, TimeUnit.SECONDS) }
                catch (timeout: TimeoutException) {
                    signal(handle, "destroyForcibly", "owned-gopls", "protocol-exit-wait-timeout") { handle.destroyForcibly() }
                    handle.onExit().get(3, TimeUnit.SECONDS); throw timeout
                }
                check(!handle.isAlive) { "Owned gopls ${handle.pid()} survived stop" }
            } }
            sampling?.cancel(false)
            processSampler.shutdown()
            attempt { check(processSampler.awaitTermination(3, TimeUnit.SECONDS)) { "Owned process sampler survived" } }
            for (child in children.values) attempt {
                if (child.isAlive) {
                    signal(child, "destroy", "owned-descendant", "still-alive-after-provider-stop") { child.destroy() }
                    try { child.onExit().get(2, TimeUnit.SECONDS) }
                    catch (_: TimeoutException) {
                        signal(child, "destroyForcibly", "owned-descendant", "descendant-destroy-wait-timeout") { child.destroyForcibly() }
                        child.onExit().get(3, TimeUnit.SECONDS)
                    }
                }
                check(!child.isAlive) { "Owned descendant ${child.pid()} survived cleanup" }
            }
            attempt { transport?.get(3, TimeUnit.SECONDS) }
            attempt { connection?.stop() } // Also closes GoDocumentSync's owned session.
            if (transport?.isDone == false) transport?.cancel(true)
            attempt { Disposer.dispose(wrapper) }
            pool.shutdownNow(); attempt { check(pool.awaitTermination(5, TimeUnit.SECONDS)) { "Owned LSP stdio pool survived" } }
            attempt { edt { restore(previous) } }
            val remaining = (listOfNotNull(owned) + children.values).filter(ProcessHandle::isAlive).map(ProcessHandle::pid)
            if (remaining.isEmpty()) attempt {
                // Close Windows directory enumeration handles before deleting
                // those directories, including the consumer working directory.
                val paths = Files.walk(root).use { it.sorted(Comparator.reverseOrder()).toList() }
                paths.forEach(Files::deleteIfExists)
            }
            // Trace and stderr belong only to this fixture's real provider/launcher.
            val wireText = protocolWire.toString()
            evidence["protocolWire"] = mapOf("totalCharacters" to wireText.length, "tailLimitCharacters" to 100000,
                "truncated" to (wireText.length > 100000), "tail" to wireText.takeLast(100000),
                "characterUnits" to "JVM UTF-16 code units", "source" to "LSP4J launcher traceMessages; not raw byte capture")
            val stderrText = ownedStderr.joinToString("")
            evidence["ownedProviderStderr"] = mapOf("totalCharacters" to stderrText.length, "tailLimitCharacters" to 400000,
                "truncated" to (stderrText.length > 400000), "tail" to stderrText.takeLast(400000),
                "characterUnits" to "JVM UTF-16 code units", "chunks" to ownedStderr.size,
                "source" to "addLogErrorHandler of only this fixture's launched OSProcessStreamConnectionProvider")
            evidence["protocolProgressAndAcks"] = messages.filter { it["source"] in setOf("progress", "client-ack") }
            evidence["messages"] = messages.takeLast(32)
            evidence["ownedDescendantPids"] = children.values.map(ProcessHandle::pid)
            evidence["ownedRemaining"] = remaining
            evidence["fixtureExplicitProcessSignalAttempts"] = explicitSignals
            evidence["providerStopSignalObservation"] = mapOf("invoked" to (connection != null),
                "internalSignals" to "unknown-not-instrumented; upstream provider stop delegates to ExecutionManagerImpl.stopProcess")
            evidence["signalRecordingScope"] = "Explicit ProcessHandle actions by this fixture only; runner noSignals is separate and does not establish signal-free fixture/provider cleanup."
            evidence["fixturePreservedForLiveOwnedProcess"] = remaining.isNotEmpty()
            evidence["cleanupErrors"] = cleanup.map { it.javaClass.name + ": " + it.message }
            println("GOWORK_GOPLS_EVIDENCE " + Gson().toJson(evidence))
            val failure = primary
            if (failure != null) cleanup.forEach { failure.addSuppressed(it) }
            else if (cleanup.isNotEmpty()) throw AssertionError("Owned fixture cleanup failed", cleanup.first())
        }
    }

    private fun restore(value: GoToolchain.Settings) = GoProjectSettings.get(project).apply {
        goExecutable = value.goExecutable; goplsExecutable = value.goplsExecutable; delveExecutable = value.delveExecutable
        buildTags = value.buildTags; goos = value.goos; goarch = value.goarch; cgoEnabled = value.cgoEnabled; environment = value.environment
    }.let { }

    private data class Target(val uri: String, val range: Range)
    private data class Reply(val targets: List<Target>, val failureKind: String? = null, val code: Int? = null, val message: String? = null)

    private fun query(server: LanguageServer, file: Path, position: Position): Reply = try {
        val response = server.textDocumentService.definition(DefinitionParams(TextDocumentIdentifier(file.toUri().toASCIIString()), position)).get(45, TimeUnit.SECONDS)
        Reply(when {
            response == null -> emptyList()
            response.isLeft -> response.left.orEmpty().map { Target(it.uri, it.range) }
            else -> response.right.orEmpty().map { Target(it.targetUri, it.targetSelectionRange) }
        })
    } catch (error: Throwable) {
        var cause = error
        while ((cause is ExecutionException || cause is CompletionException) && cause.cause != null) cause = cause.cause!!
        when (cause) {
            is TimeoutException -> Reply(emptyList(), "deadline-not-genuine-red", message = cause.message)
            is ResponseErrorException -> Reply(emptyList(), "actual-rpc-error", cause.responseError.code, cause.responseError.message)
            else -> Reply(emptyList(), "transport-or-fixture-error-not-genuine-red", message = cause.javaClass.name + ": " + cause.message)
        }
    }

    // Kotlin/JVM string offsets and LSP character positions are both UTF-16.
    private fun position(text: String, offset: Int): Position {
        val line = text.substring(0, offset).count { it == '\n' }
        val start = text.lastIndexOf('\n', offset - 1) + 1
        return Position(line, offset - start)
    }
    private fun range(text: String, offset: Int, length: Int) = Range(position(text, offset), position(text, offset + length))
    private fun sha(path: Path) = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }
    private fun edt(action: () -> Unit) = EdtTestUtil.runInEdtAndWait<RuntimeException> { action() }
    private enum class Mode { AUTO, EMPTY, OFF, ABSOLUTE }

    private class OwnedWrapper(project: Project, definition: LanguageServerDefinition, factory: GoLanguageServerFactory) : LanguageServerWrapper(project, definition) {
        val features: LSPClientFeatures = factory.createClientFeatures().also { it.setServerWrapper(this) }
        override fun start() {}
        override fun getClientFeatures() = features
        override fun dispose() { stopDispatcher(); features.dispose() }
    }

    private class Client(private val messages: MutableList<Map<String, Any?>>) : LanguageClient {
        val initialDiagnosisFinished = CompletableFuture<Map<String, Any?>>()
        private data class ProgressState(val title: String? = null, val end: Map<String, Any?>? = null)
        private val progress = ConcurrentHashMap<String, ProgressState>()

        override fun createProgress(params: WorkDoneProgressCreateParams): CompletableFuture<Void> {
            messages += mapOf("source" to "client-ack", "method" to "window/workDoneProgress/create", "token" to params.token.toString())
            return CompletableFuture.completedFuture(null)
        }
        override fun registerCapability(params: RegistrationParams): CompletableFuture<Void> {
            messages += mapOf("source" to "client-ack", "method" to "client/registerCapability", "ids" to params.registrations.map { it.id })
            return CompletableFuture.completedFuture(null)
        }
        override fun unregisterCapability(params: UnregistrationParams): CompletableFuture<Void> {
            messages += mapOf("source" to "client-ack", "method" to "client/unregisterCapability", "ids" to params.unregisterations.map { it.id })
            return CompletableFuture.completedFuture(null)
        }
        override fun notifyProgress(params: ProgressParams) {
            val token = params.token
            val key = if (token.isLeft) "string:${token.left}" else "integer:${token.right}"
            val value = params.value.let { if (it.isLeft) it.left else it.right }
            val json = Gson().toJsonTree(value)
            if (!json.isJsonObject) return
            val objectValue = json.asJsonObject
            val kind = when (value) {
                is WorkDoneProgressBegin -> "begin"
                is WorkDoneProgressEnd -> "end"
                is WorkDoneProgressReport -> "report"
                else -> objectValue.get("kind")?.asString
            }
            val title = (value as? WorkDoneProgressBegin)?.title ?: objectValue.get("title")?.asString
            val record = mapOf("source" to "progress", "token" to key, "kind" to kind,
                "title" to title, "atUtc" to java.time.Instant.now().toString())
            val state = requireNotNull(progress.compute(key) { _, previous ->
                val old = previous ?: ProgressState()
                ProgressState(if (kind == "begin") title else old.title, if (kind == "end") record else old.end)
            })
            messages += record + ("title" to state.title)
            val actualEnd = state.end
            if (actualEnd != null && state.title == "diagnosing initial workspace load") {
                initialDiagnosisFinished.complete(mapOf("status" to "completed-initial-diagnosis", "token" to key,
                    "title" to state.title, "end" to (actualEnd + ("title" to state.title))))
            }
        }
        override fun telemetryEvent(value: Any?) {}
        override fun publishDiagnostics(params: PublishDiagnosticsParams) {
            for (diagnostic in params.diagnostics) {
                val message = diagnostic.message.let { if (it.isLeft) it.left else it.right.value }
                messages += mapOf("source" to "diagnostic", "uri" to params.uri,
                    "severity" to diagnostic.severity?.name, "message" to message, "range" to diagnostic.range,
                    "workspaceDiagnostic" to (diagnostic.severity in setOf(DiagnosticSeverity.Error, DiagnosticSeverity.Warning) &&
                        message.contains("loading workspace", ignoreCase = true)))
            }
        }
        override fun showMessage(params: MessageParams) { messages += mapOf("source" to "show", "type" to params.type.name, "message" to params.message) }
        override fun logMessage(params: MessageParams) { messages += mapOf("source" to "log", "type" to params.type.name, "message" to params.message) }
        override fun showMessageRequest(params: ShowMessageRequestParams): CompletableFuture<MessageActionItem> = CompletableFuture.completedFuture(null)
        override fun configuration(params: ConfigurationParams): CompletableFuture<List<Any>> = CompletableFuture.completedFuture(params.items.map { emptyMap<String, Any>() })
    }
}
