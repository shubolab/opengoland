@file:Suppress("PackageDirectoryMismatch", "DEPRECATION")

package com.redhat.devtools.lsp4ij

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.EdtTestUtil
import com.intellij.testFramework.LightPlatformTestCase
import com.redhat.devtools.lsp4ij.client.LanguageClientImpl
import com.redhat.devtools.lsp4ij.client.features.LSPClientFeatures
import com.redhat.devtools.lsp4ij.server.OSProcessStreamConnectionProvider
import com.redhat.devtools.lsp4ij.server.StreamConnectionProvider
import com.redhat.devtools.lsp4ij.server.definition.LanguageServerDefinition
import dev.openidea.goide.lsp.GoDocumentSync
import dev.openidea.goide.lsp.GoLanguageClient
import dev.openidea.goide.lsp.GoLanguageServerFactory
import dev.openidea.goide.settings.GoProjectSettings
import dev.openidea.goide.settings.GoToolchain
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.Launcher
import org.eclipse.lsp4j.services.LanguageServer
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport

/**
 * Real wrapper start + production client + actual gopls wire.
 * Explicit owned-register/reconcile isolates the root-Changed hypothesis; this
 * deliberately does NOT claim auto FileEditorManager/capture/discovery coverage.
 * The positive control modifies the actual external file, then explicitly sends
 * that exact file Changed through the same real wrapper FIFO. It does not claim
 * automatic VFS forwarding or native fsnotifier coverage.
 */
class GoInitialMissingGoplsWireTest : LightPlatformTestCase() {
    override fun runInDispatchThread() = false

    fun testInitialMissingAtomicGreetingThroughProductionOwnedReconcile() {
        val repo = Path.of(System.getProperty("opengoland.recoveryRepo") ?: System.getenv("OPENGOLAND_RECOVERY_REPO") ?: System.getProperty("user.dir"))
        val evidence = Files.createDirectories(Path.of(System.getProperty("opengoland.recoveryWireEvidence") ?: System.getenv("OPENGOLAND_RECOVERY_WIRE_EVIDENCE") ?: repo.resolve("build/test-wire-evidence/initial-missing").toString())
            .resolve("wire-" + UUID.randomUUID()))
        val root = Files.createDirectory(evidence.resolve("fixture 中文😀"))
        val app = Files.createDirectory(root.resolve("app"))
        val dep = root.resolve("dep")
        val source = dep.resolve("dep.go")
        val public = repo.resolve("docs/reference/initial-absent-dependency-20261114-evidence/fixture")
        val callerNames = listOf("go.mod", "main.go", "main_test.go")
        callerNames.forEach { Files.copy(public.resolve("app/$it"), app.resolve(it)) }
        val caller = app.resolve("main.go")
        val expectedHashes = callerNames.associateWith { sha(app.resolve(it)) }
        val callerUri = GoDocumentSync.fileUri(caller)
        val selectedGo = Path.of(requireNotNull(System.getProperty("opengoland.testGo") ?: System.getenv("OPENGOLAND_GO_TEST"))).toRealPath()
        val selectedGopls = Path.of(requireNotNull(System.getProperty("opengoland.completionGopls") ?: System.getenv("OPENGOLAND_GOPLS_TEST"))).toRealPath()
        val oldSettings = GoToolchain.capture(project)
        val lifetime = Disposer.newDisposable("owned initial-missing actual-gopls wire")
        VfsRootAccess.allowRootAccess(lifetime, root.toString())
        val incoming = Wire()
        val outgoing = Wire()
        val factory = GoLanguageServerFactory()
        lateinit var actualClient: GoLanguageClient
        lateinit var provider: OSProcessStreamConnectionProvider
        val definition = object : LanguageServerDefinition("owned-initial-missing-wire", "Owned actual gopls wire", "", false, null, false, false) {
            override fun createConnectionProvider(project: Project): StreamConnectionProvider {
                provider = (factory.createConnectionProvider(project) as OSProcessStreamConnectionProvider).also {
                    // The light project's basePath differs from this owned fixture.
                    it.commandLine.withWorkDirectory(app.toFile())
                    it.addLogErrorHandler { line -> synchronized(evidence) {
                        Files.writeString(evidence.resolve("gopls.stderr"), line + "\n", java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)
                    } }
                }
                return TeeProvider(provider, incoming, outgoing)
            }
            override fun createLanguageClient(project: Project): LanguageClientImpl = GoLanguageClient(project).also { actualClient = it }
            override fun getLanguageId(file: com.intellij.openapi.vfs.VirtualFile?, project: Project) = "go"
        }
        val wrapper = OwnedWrapper(project, definition, factory, app)
        val result = linkedMapOf<String, Any?>("authority" to "real gopls + headless SDK/VFS + actual client registration and FIFO; not native desktop",
            "automaticScopeCaptureExercised" to false, "fixtureRoot" to root.toString(), "go" to selectedGo.toString(),
            "gopls" to selectedGopls.toString(), "goSha256" to sha(selectedGo), "goplsSha256" to sha(selectedGopls),
            "headlessWorkDoneProgressDisabled" to true,
            "automaticVfsForwardingExercised" to false,
            "positiveControl" to "test-supplied exact external-file Changed through actual live wrapper FIFO after real NIO source modification",
            "wireCaptureBoundary" to "actual factory provider streams consumed by LSP4J; input headers may be normalized to LF")
        var remote: LanguageServer? = null
        var owned: ProcessHandle? = null
        var primary: Throwable? = null
        try {
            edt {
                GoProjectSettings.get(project).apply {
                    goExecutable = selectedGo.toString(); goplsExecutable = selectedGopls.toString(); delveExecutable = ""
                    buildTags = ""; goos = ""; goarch = ""; cgoEnabled = "0"
                    environment = listOf("GOROOT=${selectedGo.parent.parent}", "GOENV=off", "GOTOOLCHAIN=local", "GOFLAGS=",
                        "GOPROXY=off", "GOSUMDB=off", "GOVCS=*:off", "GOTELEMETRY=off", "CGO_ENABLED=0",
                        "GOCACHE=${requireNotNull(System.getenv("GOCACHE"))}", "GOMODCACHE=${requireNotNull(System.getenv("GOMODCACHE"))}").joinToString("\n")
                    // GOWORK deliberately absent, matching native C10 scoped env.
                }
            }
            assertFalse(Files.exists(dep))
            val local = LocalFileSystem.getInstance()
            val virtual = requireNotNull(local.refreshAndFindFileByNioFile(caller))
            val document = ReadAction.compute<com.intellij.openapi.editor.Document, RuntimeException> {
                requireNotNull(FileDocumentManager.getInstance().getDocument(virtual))
            }
            wrapper.start() // Real process/init/client/started; no fabricated status callback.
            val server = wrapper.initializedServer.get(45, TimeUnit.SECONDS)
            remote = server
            await("real wrapper started") { wrapper.serverStatus == ServerStatus.started }
            val processHandle = ProcessHandle.of(requireNotNull(provider.pid)).orElseThrow()
            owned = processHandle
            val originalPid = processHandle.pid()
            result["goplsPid"] = originalPid
            val info = ReadAction.compute<LanguageServerWrapper.LSPFileConnectionInfo, RuntimeException> {
                wrapper.createFileConnectionInfo(virtual, document, true)
            }
            wrapper.connect(virtual, info).get(20, TimeUnit.SECONDS)
            assertTrue("Require a real BrokenImport before mutating the filesystem", awaitDiagnostic(incoming, callerUri, 0) {
                it.contains("BrokenImport") || it.contains("could not import")
            })
            val beforeGreeting = incoming.messages().size
            val prepared = Files.createDirectory(root.resolve("prepared-greeting"))
            Files.copy(public.resolve("retained-created-dep/go.mod"), prepared.resolve("go.mod"))
            val originalDependency = Files.readString(public.resolve("retained-created-dep/dep.go"))
            Files.writeString(prepared.resolve("dep.go"), originalDependency.replace("func Message()", "func Greeting()"))
            Files.move(prepared, dep)
            val identity = requireNotNull(Files.readAttributes(dep, java.nio.file.attribute.BasicFileAttributes::class.java).fileKey())
            val registrationId = "owned.actual-gopls.initial-missing"
            invokeClient(actualClient, "registerDependencyScope", registrationId, setOf(dep)).get(25, TimeUnit.SECONDS)
            invokeClient(actualClient, "reconcileDependencyScope", setOf(dep)).get(20, TimeUnit.SECONDS)
            assertNotNull("Production registration must really materialize the cold external source", local.findFileByPathIfCached(source.toString()))
            await("actual stable root URI Changed on the real output stream") {
                outgoing.messages().any { watched(it, GoDocumentSync.fileUri(dep), 2) }
            }
            // Capture failure, then still run the content-change positive control.
            val greetingUpdated = awaitDiagnostic(incoming, callerUri, beforeGreeting) {
                it.contains("Message") && (it.contains("UndeclaredImportedName") || it.contains("undefined")) && !it.contains("BrokenImport")
            }
            result["greetingUpdatedToActualUndefined"] = greetingUpdated
            result["greetingDiagnostics"] = diagnostics(incoming, callerUri)
            result["greetingActualWatchedNotifications"] = outgoing.messages().filter { it.get("method")?.asString == "workspace/didChangeWatchedFiles" }
            incoming.save(evidence.resolve("after-greeting.server-to-client.raw"))
            outgoing.save(evidence.resolve("after-greeting.client-to-server.raw"))

            val beforeMessage = incoming.messages().size
            val beforeMessageOutgoing = outgoing.messages().size
            val cached = requireNotNull(local.findFileByPathIfCached(source.toString()))
            assertNull(FileDocumentManager.getInstance().getCachedDocument(cached))
            Files.writeString(source, originalDependency)
            Files.setLastModifiedTime(source, java.nio.file.attribute.FileTime.fromMillis(cached.timeStamp + 2_000))
            // Deliberately isolate the server invalidation control from the
            // independently unverified automatic VFS forwarding fixture path.
            // This sends an actual packet, never a fabricated diagnostic/ACK.
            wrapper.sendNotification { dispatched ->
                check(dispatched === server && wrapper.serverStatus == ServerStatus.started && wrapper.languageServer === server)
                dispatched.workspaceService.didChangeWatchedFiles(DidChangeWatchedFilesParams(listOf(
                    FileEvent(GoDocumentSync.fileUri(source), FileChangeType.Changed))))
                dispatched
            }.get(20, TimeUnit.SECONDS)
            await("test-supplied exact external source Changed on the actual wrapper output stream") {
                outgoing.messages().drop(beforeMessageOutgoing).any { watched(it, GoDocumentSync.fileUri(source), 2) }
            }
            result["exactFileChangedControlPacketObserved"] = true
            val messageCleared = awaitDiagnostic(incoming, callerUri, beforeMessage) { it == "[]" }
            result["exactFileChangedControlClearedDiagnostics"] = messageCleared
            result["messageDiagnostics"] = diagnostics(incoming, callerUri)
            result["classification"] = when {
                !messageCleared -> "exact-file-control-failed-not-genuine-root-reconcile-RED"
                !greetingUpdated -> "real-wire-root-reconcile-failed-explicit-exact-file-control-cleared"
                else -> "actual-production-reconcile-updated-diagnostics-inspect-recorded-notifications"
            }
            if (messageCleared) {
                val text = Files.readString(caller)
                val offset = text.indexOf("Message()")
                val prefix = text.substring(0, offset)
                val hover = server.textDocumentService.hover(HoverParams(TextDocumentIdentifier(callerUri),
                    Position(prefix.count { it == '\n' }, offset - (prefix.lastIndexOf('\n') + 1)))).get(20, TimeUnit.SECONDS)
                result["messageHover"] = Gson().toJsonTree(hover)
                assertNotNull("The real server must resolve the restored external Message", hover)
                assertTrue(Gson().toJson(hover).contains("Message"))
            }
            assertEquals(identity, Files.readAttributes(dep, java.nio.file.attribute.BasicFileAttributes::class.java).fileKey())
            assertEquals(expectedHashes, callerNames.associateWith { sha(app.resolve(it)) })
            assertTrue(processHandle.isAlive); assertEquals(originalPid, provider.pid)
            val frames = outgoing.messages()
            assertEquals(1, frames.count { it.get("method")?.asString == "textDocument/didOpen" })
            assertEquals(0, frames.count { it.get("method")?.asString == "textDocument/didChange" })
            result["callerHashesUnchanged"] = true
            result["sameGoplsPid"] = true
            assertTrue("Exact-file FIFO control failed: do not misclassify transport/fixture failure as root-Changed RED", messageCleared)
            assertTrue("Actual production root reconcile did not replace BrokenImport with undefined Message; see saved original wire", greetingUpdated)
        } catch (error: Throwable) {
            primary = error; result["failureClass"] = error.javaClass.name; result["failureMessage"] = error.message
            throw error
        } finally {
            val cleanup = mutableListOf<Throwable>()
            val cleanupEvents = mutableListOf<Map<String, Any?>>()
            var shutdownAcknowledged = false
            var exitSent = false
            var exitedBeforeDispose = false
            fun attempt(step: String, target: ProcessHandle? = owned, action: () -> Unit) {
                val event = linkedMapOf<String, Any?>("step" to step, "pid" to target?.pid(),
                    "startedAtUtc" to java.time.Instant.now().toString(), "aliveBefore" to target?.isAlive)
                try { action(); event["outcome"] = "returned" }
                catch (error: Throwable) {
                    cleanup += error; event["outcome"] = "threw"; event["error"] = error.toString()
                } finally {
                    event["aliveAfter"] = target?.isAlive
                    event["endedAtUtc"] = java.time.Instant.now().toString()
                    cleanupEvents += event
                }
            }
            val children = owned?.descendants()?.use { it.toList() }.orEmpty()
            result["cleanupObservedChildPids"] = children.map { it.pid() }
            attempt("protocol-shutdown") { remote?.let { it.shutdown().get(5, TimeUnit.SECONDS); shutdownAcknowledged = true } }
            attempt("protocol-exit") { remote?.let { it.exit(); exitSent = true } }
            attempt("wait-owned-exit-before-wrapper-dispose") { owned?.let { it.onExit().get(8, TimeUnit.SECONDS); exitedBeforeDispose = !it.isAlive } }
            children.forEach { child -> attempt("wait-observed-child-exit-before-wrapper-dispose", child) {
                child.onExit().get(5, TimeUnit.SECONDS)
            } }
            if (owned != null) attempt("wait-provider-process-handler-and-stream-readers") {
                // ProcessHandle.onExit alone does not join IntelliJ's stdout
                // reader. Wait for the actual provider handler's termination
                // barrier after the protocol process and observed children exit.
                val handler = OSProcessStreamConnectionProvider::class.java.getDeclaredMethod("getProcessHandler")
                    .apply { isAccessible = true }.invoke(provider) as com.intellij.execution.process.OSProcessHandler
                check(handler.waitFor(5_000)) { "Owned provider process handler/readers did not finish" }
            }
            result["wrapperDisposedWithLiveOwnedProcess"] = owned?.isAlive
            attempt("wrapper-dispose-may-terminate-live-owned-process") { Disposer.dispose(wrapper) }
            owned?.takeIf { it.isAlive }?.let { handle -> attempt("explicit-destroyForcibly-owned-fallback", handle) {
                handle.destroyForcibly(); handle.onExit().get(3, TimeUnit.SECONDS)
            } }
            children.filter { it.isAlive }.forEach { handle -> attempt("explicit-destroyForcibly-child-fallback", handle) {
                handle.destroyForcibly(); handle.onExit().get(3, TimeUnit.SECONDS)
            } }
            attempt("restore-project-test-settings") { edt { restoreSettings(oldSettings) } }
            attempt("dispose-vfs-test-lifetime") { Disposer.dispose(lifetime) }
            incoming.save(evidence.resolve("server-to-client.raw")); outgoing.save(evidence.resolve("client-to-server.raw"))
            Files.writeString(evidence.resolve("parsed-server-to-client.json"), Gson().toJson(incoming.messages()))
            Files.writeString(evidence.resolve("parsed-client-to-server.json"), Gson().toJson(outgoing.messages()))
            result["cleanupFailures"] = cleanup.map { it.toString() }
            result["cleanupEvents"] = cleanupEvents
            result["protocolShutdownAcknowledged"] = shutdownAcknowledged
            result["protocolExitSent"] = exitSent
            result["ownedExitedBeforeWrapperDispose"] = exitedBeforeDispose
            result["ownedProcessAliveAfterCleanup"] = owned?.isAlive
            result["observedChildPidsStillAliveAfterCleanup"] = children.filter { it.isAlive }.map { it.pid() }
            result["fixtureRetained"] = true
            Files.writeString(evidence.resolve("result.json"), Gson().toJson(result))
            if (primary != null) cleanup.forEach(primary::addSuppressed)
            else check(cleanup.isEmpty()) { "Owned cleanup failed: $cleanup" }
        }
    }

    private class OwnedWrapper(project: Project, definition: LanguageServerDefinition, factory: GoLanguageServerFactory, app: Path) : LanguageServerWrapper(project, definition) {
        private val actual = factory.createClientFeatures().also { it.setServerWrapper(this) }
        private val observed = object : LSPClientFeatures() {
            override fun <S : LanguageServer> createLauncherBuilder(): Launcher.Builder<S> = actual.createLauncherBuilder()
            override fun initializeParams(params: InitializeParams) {
                actual.initializeParams(params)
                // Only the fixture location differs from the light project root.
                params.rootUri = GoDocumentSync.fileUri(app)
                params.rootPath = app.toString()
                params.workspaceFolders = listOf(WorkspaceFolder(GoDocumentSync.fileUri(app), "owned-initial-missing"))
                // The real client's progress UI enters invokeAndWait, while a
                // headless light test has no native event loop to service it.
                // Do not let unrelated workspace progress block its serial
                // message writer before didOpen/configuration/shutdown. All
                // diagnostic, registration and watched-file capabilities stay.
                params.capabilities.window?.workDoneProgress = false
            }
            override fun keepServerAlive() = true
            override fun handleServerStatusChanged(status: ServerStatus) { actual.handleServerStatusChanged(status); super.handleServerStatusChanged(status) }
        }.also { it.setServerWrapper(this) }
        override fun getClientFeatures() = observed
        override fun dispose() { try { super.dispose() } finally { stopDispatcher(); actual.dispose(); observed.dispose() } }
    }

    private class TeeProvider(private val actual: StreamConnectionProvider, private val incoming: Wire, private val outgoing: Wire) : StreamConnectionProvider by actual {
        private val input: InputStream by lazy { object : FilterInputStream(requireNotNull(actual.inputStream)) {
            override fun read(): Int = `in`.read().also { if (it >= 0) incoming.append(byteArrayOf(it.toByte()), 0, 1) }
            override fun read(bytes: ByteArray, off: Int, len: Int): Int = `in`.read(bytes, off, len).also { if (it > 0) incoming.append(bytes, off, it) }
        } }
        private val output: OutputStream by lazy { object : FilterOutputStream(requireNotNull(actual.outputStream)) {
            override fun write(value: Int) { out.write(value); outgoing.append(byteArrayOf(value.toByte()), 0, 1) }
            override fun write(bytes: ByteArray, off: Int, len: Int) { out.write(bytes, off, len); outgoing.append(bytes, off, len) }
        } }
        override fun getInputStream() = input
        override fun getOutputStream() = output
    }

    private class Wire {
        private val raw = ByteArrayOutputStream()
        private val frames = mutableListOf<JsonObject>()
        private var offset = 0
        @Synchronized fun append(bytes: ByteArray, off: Int, len: Int) {
            check(raw.size() + len <= 20 * 1024 * 1024)
            raw.write(bytes, off, len)
            val all = raw.toByteArray()
            while (offset < all.size) {
                // OSProcessStreamConnectionProvider can normalize process
                // stdout's CRLF headers to LF before exposing this stream.
                // Retain the received bytes; only framing accepts both forms.
                val end = (offset..all.size - 2).firstOrNull { index ->
                    all[index] == 10.toByte() && all[index + 1] == 10.toByte() ||
                        index + 3 < all.size && all[index] == 13.toByte() && all[index + 1] == 10.toByte() &&
                        all[index + 2] == 13.toByte() && all[index + 3] == 10.toByte()
                } ?: return
                val body = end + if (all[end] == 13.toByte()) 4 else 2
                val header = String(all, offset, end - offset, Charsets.US_ASCII)
                val count = requireNotNull(Regex("Content-Length: *([0-9]+)", RegexOption.IGNORE_CASE).find(header)).groupValues[1].toInt()
                if (all.size < body + count) return
                frames += JsonParser.parseString(String(all, body, count, Charsets.UTF_8)).asJsonObject
                offset = body + count
            }
        }
        @Synchronized fun messages(): List<JsonObject> = frames.toList()
        @Synchronized fun save(path: Path) { Files.write(path, raw.toByteArray()) }
    }

    private fun watched(frame: JsonObject, uri: String, type: Int): Boolean = frame.get("method")?.asString == "workspace/didChangeWatchedFiles" &&
        frame.getAsJsonObject("params").getAsJsonArray("changes").any { val event = it.asJsonObject; event.get("uri").asString == uri && event.get("type").asInt == type }
    private fun diagnostics(wire: Wire, uri: String): List<String> = wire.messages().filter { it.get("method")?.asString == "textDocument/publishDiagnostics" }
        .map { it.getAsJsonObject("params") }.filter { it.get("uri")?.asString == uri }.map { it.get("diagnostics").toString() }
    private fun awaitDiagnostic(wire: Wire, uri: String, after: Int, predicate: (String) -> Boolean): Boolean = waitFor {
        wire.messages().drop(after).filter { it.get("method")?.asString == "textDocument/publishDiagnostics" }.any {
            val params = it.getAsJsonObject("params"); params.get("uri")?.asString == uri && predicate(params.get("diagnostics").toString())
        }
    }
    private fun waitFor(condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (System.nanoTime() < deadline) { if (condition()) return true; LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20)) }
        return condition()
    }
    private fun await(label: String, condition: () -> Boolean) { check(waitFor(condition)) { "Timed out awaiting $label" } }
    @Suppress("UNCHECKED_CAST") private fun invokeClient(client: GoLanguageClient, name: String, vararg args: Any): CompletableFuture<Void> =
        GoLanguageClient::class.java.declaredMethods.single { it.name == name && it.parameterCount == args.size }.apply { isAccessible = true }
            .invoke(client, *args) as CompletableFuture<Void>
    private fun sha(path: Path) = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)))
    private fun edt(action: () -> Unit) = EdtTestUtil.runInEdtAndWait<RuntimeException> { action() }
    private fun restoreSettings(previous: GoToolchain.Settings) { GoProjectSettings.get(project).apply {
        goExecutable = previous.goExecutable; goplsExecutable = previous.goplsExecutable; delveExecutable = previous.delveExecutable
        buildTags = previous.buildTags; goos = previous.goos; goarch = previous.goarch; cgoEnabled = previous.cgoEnabled; environment = previous.environment
    } }
}
