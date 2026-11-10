package com.redhat.devtools.lsp4ij

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.EdtTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.redhat.devtools.lsp4ij.client.features.LSPClientFeatures
import com.redhat.devtools.lsp4ij.internal.capabilities.ClientCapabilitiesFactory
import com.redhat.devtools.lsp4ij.server.definition.LanguageServerDefinition
import dev.openidea.goide.lsp.GoDocumentSync
import dev.openidea.goide.lsp.GoLanguageServerFactory
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.MessageConsumer
import org.eclipse.lsp4j.jsonrpc.messages.NotificationMessage
import org.eclipse.lsp4j.jsonrpc.messages.RequestMessage
import org.eclipse.lsp4j.jsonrpc.messages.ResponseMessage
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageServer
import java.awt.datatransfer.StringSelection
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** One real Basic Lookup path; no canned completion payload and no Go debuggee. */
class GoNativeCompletionOrderingTest : BasePlatformTestCase() {
    override fun runInDispatchThread() = false

    fun testPendingNativeUnicodePasteCompletionAutoImportAndOneUndo() {
        val root = Files.createTempDirectory("owned completion 中文😀 ").toRealPath()
        val lifetime = Disposer.newDisposable("owned native completion")
        VfsRootAccess.allowRootAccess(lifetime, root.toString())
        val report = linkedMapOf<String, Any?>()
        val gson = Gson()
        val go = Path.of(requireNotNull(System.getProperty("opengoland.testGo") ?: System.getenv("OPENGOLAND_GO_TEST")) { "Set OPENGOLAND_GO_TEST to the selected real SDK executable" }).toRealPath()
        val gopls = Path.of(requireNotNull(System.getProperty("opengoland.completionGopls") ?: System.getenv("OPENGOLAND_GOPLS_TEST")) { "Set OPENGOLAND_GOPLS_TEST to the selected real gopls executable" }).toRealPath()
        report["selectedGo"] = go.toString(); report["selectedGoSha256"] = sha(go)
        report["gopls"] = gopls.toString(); report["goplsSha256"] = sha(gopls)
        val definition = requireNotNull(LanguageServersRegistry.getInstance().getServerDefinition("openidea.gopls"))
        val wrapper = OwnedWrapper(project, definition)
        val accessor = LanguageServiceAccessor.getInstance(project)
        @Suppress("UNCHECKED_CAST")
        val started = LanguageServiceAccessor::class.java.getDeclaredField("startedServers").apply { isAccessible = true }.get(accessor) as MutableSet<LanguageServerWrapper>
        val previous = synchronized(started) { started.toList() }
        val records = CopyOnWriteArrayList<String>()
        val trace = CopyOnWriteArrayList<Map<String, Any?>>()
        val wireIn = java.io.ByteArrayOutputStream()
        val wireOut = java.io.ByteArrayOutputStream()
        val completionSeen = CountDownLatch(1)
        val changeSeen = CountDownLatch(1)
        val ids = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val pool = Executors.newCachedThreadPool()
        val release = CountDownLatch(1)
        val gateEntered = CountDownLatch(1)
        val gateTask = arrayOfNulls<CompletableFuture<LanguageServer>>(1)
        var process: Process? = null
        var transport: Future<Void>? = null
        var remote: LanguageServer? = null
        var connected = false
        var beforePaste: String? = null
        var sourcePath: Path? = null
        var diskBefore: String? = null
        val settings = CodeInsightSettings.getInstance()
        val oldPopup = settings.AUTO_POPUP_COMPLETION_LOOKUP
        val oldAuto = settings.AUTOCOMPLETE_ON_CODE_COMPLETION
        val clipboard = CopyPasteManager.getInstance().contents
        report["initialClipboardAbsentRestoresEmpty"] = clipboard == null
        val pids = linkedSetOf<Long>()
        val handles = linkedMapOf<Long, ProcessHandle>()
        var primaryFailure: Throwable? = null
        try {
            Files.writeString(root.resolve("go.mod"), "module example.com/completiondemo\n\ngo 1.26.0\n")
            val path = root.resolve("source.go"); sourcePath = path
            Files.writeString(path, ORIGINAL); diskBefore = ORIGINAL
            val command = GeneralCommandLine(gopls.toString()).withParameters("serve").withWorkDirectory(root.toString())
                .withEnvironment("GOROOT", go.parent.parent.toString())
                .withEnvironment("PATH", go.parent.toString() + java.io.File.pathSeparator + System.getenv("PATH").orEmpty())
                .withEnvironment("GOTOOLCHAIN", "local").withEnvironment("GOTELEMETRY", "off")
                .withEnvironment("GOENV", "off").withEnvironment("GOFLAGS", "").withEnvironment("GOWORK", "off")
                .withEnvironment("GOPROXY", "off").withEnvironment("GOSUMDB", "off").withEnvironment("CGO_ENABLED", "0")
                .withEnvironment("GOCACHE", root.resolve("gocache").toString())
                .withEnvironment("GOMODCACHE", root.resolve("modcache").toString())
            process = ProcessBuilder(gopls.toString(), "serve").directory(root.toFile()).redirectError(root.resolve("gopls.stderr").toFile()).apply {
                // Use the platform's case-aware effective environment once:
                // inherited Windows Path must not coexist with a second PATH.
                environment().clear(); environment().putAll(command.effectiveEnvironment)
            }.start()
            pids += process.pid(); handles[process.pid()] = process.toHandle()
            val owned = process
            val tappedInput = object : java.io.InputStream() {
                override fun read(): Int = owned.inputStream.read().also { if (it >= 0) synchronized(wireIn) { wireIn.write(it) } }
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int = owned.inputStream.read(bytes, offset, length).also { count -> if (count > 0) synchronized(wireIn) { check(wireIn.size() + count <= 8 * 1024 * 1024); wireIn.write(bytes, offset, count) } }
            }
            val tappedOutput = object : java.io.OutputStream() {
                override fun write(value: Int) { synchronized(wireOut) { wireOut.write(value) }; owned.outputStream.write(value) }
                override fun write(bytes: ByteArray, offset: Int, length: Int) { synchronized(wireOut) { check(wireOut.size() + length <= 8 * 1024 * 1024); wireOut.write(bytes, offset, length) }; owned.outputStream.write(bytes, offset, length) }
                override fun flush() = owned.outputStream.flush()
            }
            val launcher = wrapper.features.createLauncherBuilder<LanguageServer>()
                .setRemoteInterface(LanguageServer::class.java).setLocalService(Client(wrapper))
                .setInput(tappedInput).setOutput(tappedOutput).setExecutorService(pool)
                .wrapMessages { consumer -> MessageConsumer { message ->
                    val json = JsonParser.parseString(gson.toJson(message)).asJsonObject
                    when (message) {
                        is NotificationMessage -> if (message.method in setOf("textDocument/didOpen", "textDocument/didChange", "textDocument/didClose")) {
                            records += message.method
                            trace += mapOf("kind" to "notification", "value" to json)
                            if (message.method == "textDocument/didChange") changeSeen.countDown()
                        }
                        is RequestMessage -> if (message.method == "textDocument/completion") {
                            ids += message.id.toString(); records += message.method
                            trace += mapOf("kind" to "completion-request", "value" to json)
                        }
                        is ResponseMessage -> if (message.id.toString() in ids) trace += mapOf("kind" to "completion-response", "value" to json)
                    }
                    consumer.consume(message)
                    // Release only after the real outgoing completion frame was consumed/written.
                    if (message is RequestMessage && message.method == "textDocument/completion") completionSeen.countDown()
                } }.create()
            transport = launcher.startListening(); remote = launcher.remoteProxy
            val params = InitializeParams().apply {
                capabilities = ClientCapabilitiesFactory.create(null)
                rootUri = root.toUri().toString()
                workspaceFolders = listOf(WorkspaceFolder(root.toUri().toString(), "owned-completion"))
            }
            wrapper.features.initializeParams(params)
            val init = remote.initialize(params).get(45, TimeUnit.SECONDS)
            report["serverCapabilities"] = init.capabilities
            assertTrue(init.capabilities.completionProvider.resolveProvider != true)
            remote.initialized(InitializedParams())
            wrapper.attach(remote, init.capabilities, transport)
            synchronized(started) { started.clear(); started.add(wrapper) }
            val virtual = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path))
            // Load only the physical editor document/PSI on BGT, never completion data.
            ReadAction.run<RuntimeException> {
                requireNotNull(FileDocumentManager.getInstance().getDocument(virtual))
                requireNotNull(com.intellij.psi.PsiManager.getInstance(project).findFile(virtual)).node
            }
            edt {
                settings.AUTO_POPUP_COMPLETION_LOOKUP = false; settings.AUTOCOMPLETE_ON_CODE_COMPLETION = false
                myFixture.configureFromExistingVirtualFile(virtual)
                PsiDocumentManager.getInstance(project).commitAllDocuments()
            }
            val document = myFixture.editor.document
            report["nativeSequenceBeforePaste"] = (document as com.intellij.openapi.editor.ex.DocumentEx).modificationSequence
            report["loadedClasses"] = listOf(GoLanguageServerFactory::class.java, com.redhat.devtools.lsp4ij.features.completion.LSPCompletionContributor::class.java, com.redhat.devtools.lsp4ij.features.completion.LSPCompletionSupport::class.java, com.redhat.devtools.lsp4ij.client.features.LSPCompletionProposal::class.java, DocumentContentSynchronizer::class.java).map { type ->
                val resource = requireNotNull(type.getResource("/" + type.name.replace('.', '/') + ".class"))
                mapOf("class" to type.name, "resource" to resource.toString(), "sha256" to MessageDigest.getInstance("SHA-256").digest(resource.openStream().use { it.readBytes() }).joinToString("") { "%02x".format(it) })
            }
            val info = ReadAction.compute<LanguageServerWrapper.LSPFileConnectionInfo, RuntimeException> { wrapper.createFileConnectionInfo(virtual, document, true) }
            wrapper.connect(virtual, info).get(15, TimeUnit.SECONDS); connected = true
            wrapper.flush()
            val initialNotifications = records.size
            gateTask[0] = wrapper.sendNotification { server ->
                check(!com.intellij.openapi.application.ApplicationManager.getApplication().isDispatchThread)
                gateEntered.countDown(); check(release.await(30, TimeUnit.SECONDS)); server
            }
            assertTrue(gateEntered.await(10, TimeUnit.SECONDS))
            edt {
                myFixture.editor.caretModel.moveToOffset(document.textLength)
                CopyPasteManager.getInstance().setContents(StringSelection(APPENDED))
                myFixture.performEditorAction(IdeActions.ACTION_EDITOR_PASTE)
                val offset = document.text.indexOf("bytes.Buf") + "bytes.Buf".length
                myFixture.editor.caretModel.moveToOffset(offset)
                PsiDocumentManager.getInstance(project).commitDocument(document)
            }
            beforePaste = ReadAction.compute<String, RuntimeException> { document.text }
            report["beforeAccept"] = beforePaste
            report["nativeUtf16Position"] = ReadAction.compute<Position, RuntimeException> { LSPIJUtils.toPosition(myFixture.editor.caretModel.offset, document) }
            report["dirtyAfterPaste"] = FileDocumentManager.getInstance().isDocumentUnsaved(document)
            assertEquals(1L, changeSeen.count)
            val overtook = AtomicBoolean()
            val coordinator = pool.submit<java.util.AbstractMap.SimpleImmutableEntry<Boolean, Boolean>> {
                // This is a bounded ordering observation, not an interactive latency assertion.
                overtook.set(completionSeen.await(15, TimeUnit.SECONDS))
                val observed = java.util.AbstractMap.SimpleImmutableEntry(overtook.get(), changeSeen.count == 0L)
                release.countDown()
                observed
            }
            val attempt = CompletableFuture<List<String>>()
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                try { myFixture.completeBasic(); attempt.complete(myFixture.lookupElements?.map { it.lookupString }.orEmpty()) }
                catch (failure: Throwable) { attempt.completeExceptionally(failure) }
            }
            report["firstLookup"] = attempt.get(60, TimeUnit.SECONDS)
            val observed = coordinator.get(20, TimeUnit.SECONDS)
            report["completionObservedBeforeFifoRelease"] = observed.key
            report["didChangeObservedBeforeFifoRelease"] = observed.value
            gateTask[0]!!.get(15, TimeUnit.SECONDS)
            wrapper.flush()
            report["recordedAfterPaste"] = records.drop(initialNotifications)
            report["nativeSequenceAfterPaste"] = (document as com.intellij.openapi.editor.ex.DocumentEx).modificationSequence
            val pasted = requireNotNull(beforePaste)
            edt { LookupManager.getActiveLookup(myFixture.editor)?.hideLookup(true) }
            // New completion at a changed offset makes the control independent of an earlier empty cached response.
            edt {
                myFixture.type("f")
                PsiDocumentManager.getInstance(project).commitDocument(document)
            }
            wrapper.flush()
            val beforePositive = ReadAction.compute<String, RuntimeException> { document.text }
            val positive = CompletableFuture<List<String>>()
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                try { myFixture.completeBasic(); positive.complete(myFixture.lookupElements?.map { it.lookupString }.orEmpty()) }
                catch (failure: Throwable) { positive.completeExceptionally(failure) }
            }
            val candidates = positive.get(60, TimeUnit.SECONDS); report["positiveLookup"] = candidates
            assertTrue("Actual current gopls must offer bytes.Buffer: $candidates", "Buffer" in candidates)
            edt {
                val item = requireNotNull(myFixture.lookupElements).first { it.lookupString == "Buffer" }
                val lookup = requireNotNull(LookupManager.getActiveLookup(myFixture.editor)) as com.intellij.codeInsight.lookup.impl.LookupImpl
                lookup.setCurrentItem(item)
                lookup.finishLookup(Lookup.NORMAL_SELECT_CHAR)
            }
            val accepted = ReadAction.compute<String, RuntimeException> { document.text }; report["accepted"] = accepted
            assertTrue(accepted.contains("bytes.Buffer")); assertTrue(accepted.contains("中文😀"))
            assertEquals(1, accepted.split("\"bytes\"").size - 1)
            edt {
                val editor = TextEditorProvider.getInstance().getTextEditor(myFixture.editor)
                assertTrue(UndoManager.getInstance(project).isUndoAvailable(editor))
                UndoManager.getInstance(project).undo(editor)
            }
            val undone = ReadAction.compute<String, RuntimeException> { document.text }; report["afterSingleUndo"] = undone
            assertEquals("One native Undo must restore both identifier and import", beforePositive, undone)
            assertEquals(ORIGINAL, Files.readString(path))
            report["originalDiskUnchanged"] = true
            report["completedPositiveControl"] = true
            val ordered = !overtook.get()
            report["orderingInvariantPass"] = ordered
            report["status"] = if (ordered) "native-completion-ordering-green" else "actual-completion-overtook-pending-didchange"
            assertTrue("Native completion request reached gopls before the real pending didChange: $records", ordered)
            assertTrue("The original completion must offer Buffer after its owned FIFO barrier", (report["firstLookup"] as List<*>).contains("Buffer"))
        } catch (failure: Throwable) {
            primaryFailure = failure
            report["failure"] = failure.stackTraceToString(); throw failure
        } finally {
            release.countDown()
            runCatching { gateTask[0]?.get(10, TimeUnit.SECONDS) }
            report["transport"] = trace.toList(); report["eventOrder"] = records.toList()
            process?.toHandle()?.descendants()?.use { stream -> stream.forEach { pids += it.pid(); handles[it.pid()] = it } }
            runCatching { if (connected) wrapper.disconnect(myFixture.file.virtualFile, false); wrapper.flush() }
            synchronized(started) { started.clear(); started.addAll(previous) }
            runCatching { if (LSPFileSupport.hasSupport(myFixture.file)) Disposer.dispose(LSPFileSupport.getSupport(myFixture.file)) }
            runCatching { Disposer.dispose(wrapper) }
            runCatching { remote?.shutdown()?.get(5, TimeUnit.SECONDS); remote?.exit() }
            runCatching { process?.outputStream?.close() }
            process?.let { owned ->
                if (!owned.waitFor(5, TimeUnit.SECONDS)) {
                    owned.toHandle().descendants().use { stream -> stream.forEach { child -> pids += child.pid(); child.destroyForcibly() } }
                    owned.destroyForcibly(); assertTrue(owned.waitFor(5, TimeUnit.SECONDS))
                }
            }
            // A normal gopls shutdown may finish before its owned go-list workers.
            // Wait on the exact captured handles; a numeric PID is never reattached.
            for (handle in handles.values) {
                if (handle.isAlive) runCatching { handle.onExit().get(5, TimeUnit.SECONDS) }
                if (handle.isAlive) { handle.destroyForcibly(); runCatching { handle.onExit().get(5, TimeUnit.SECONDS) } }
            }
            runCatching { transport?.get(5, TimeUnit.SECONDS) }
            transport?.cancel(true)
            pool.shutdownNow(); pool.awaitTermination(5, TimeUnit.SECONDS)
            report["recordedOwnedPids"] = pids.toList()
            report["recordedOwnedPidsAbsent"] = pids.all { ProcessHandle.of(it).map { handle -> !handle.isAlive }.orElse(true) }
            sourcePath?.let { report["diskBeforeCleanup"] = runCatching { Files.readString(it) }.getOrNull() }
            runCatching { edt {
                settings.AUTO_POPUP_COMPLETION_LOOKUP = oldPopup; settings.AUTOCOMPLETE_ON_CODE_COMPLETION = oldAuto
                CopyPasteManager.getInstance().setContents(clipboard ?: StringSelection(""))
                if (sourcePath != null && diskBefore != null) WriteCommandAction.runWriteCommandAction(project) {
                    myFixture.editor.document.setText(diskBefore); FileDocumentManager.getInstance().saveDocument(myFixture.editor.document)
                }
            } }
            report["goplsStderr"] = runCatching { Files.readString(root.resolve("gopls.stderr")) }.getOrNull()
            report["selectedGoAfterSha256"] = sha(go); report["goplsAfterSha256"] = sha(gopls)
            System.getProperty("opengoland.completionEvidence")?.let(Path::of)?.let { evidence ->
                Files.createDirectories(evidence)
                Files.write(evidence.resolve("gopls-input.frames"), synchronized(wireOut) { wireOut.toByteArray() })
                Files.write(evidence.resolve("gopls-output.frames"), synchronized(wireIn) { wireIn.toByteArray() })
                Files.writeString(evidence.resolve("result.json"), gson.toJson(report))
            }
            Disposer.dispose(lifetime)
            root.toFile().deleteRecursively()
            if (report["recordedOwnedPidsAbsent"] != true) {
                val cleanupFailure = AssertionError("All recorded owned gopls/helper processes must finish")
                if (primaryFailure != null) primaryFailure.addSuppressed(cleanupFailure) else throw cleanupFailure
            }
        }
    }

    private class OwnedWrapper(project: Project, definition: LanguageServerDefinition) : LanguageServerWrapper(project, definition) {
        val features: LSPClientFeatures = GoLanguageServerFactory().createClientFeatures().also { it.setServerWrapper(this) }
        private lateinit var remote: LanguageServer
        override fun start() {}
        override fun getInitializedServer() = CompletableFuture.completedFuture(remote)
        override fun getClientFeatures() = features
        override fun getServerStatus() = ServerStatus.started
        override fun isEnabled() = true
        override fun canOperate(file: VirtualFile) = true
        fun attach(server: LanguageServer, capabilities: ServerCapabilities, transport: Future<Void>) {
            remote = server; features.setServerCapabilities(capabilities)
            setField("languageServer", server); setField("initializeFuture", CompletableFuture.completedFuture<Void>(null))
            setField("serverCapabilities", capabilities); setField("launcherFuture", transport)
        }
        fun flush() { sendNotification { it }.get(15, TimeUnit.SECONDS) }
        override fun dispose() {
            openedDocuments.values.forEach { it.synchronizer?.let(Disposer::dispose) }
            openedDocuments.clear(); stopDispatcher(); features.dispose()
        }
        private fun setField(name: String, value: Any) { LanguageServerWrapper::class.java.getDeclaredField(name).apply { isAccessible = true }.set(this, value) }
    }
    private class Client(private val wrapper: OwnedWrapper) : LanguageClient {
        override fun createProgress(params: WorkDoneProgressCreateParams): CompletableFuture<Void> = CompletableFuture.completedFuture(null)
        override fun registerCapability(params: RegistrationParams): CompletableFuture<Void> { wrapper.registerCapability(params); return CompletableFuture.completedFuture(null) }
        override fun unregisterCapability(params: UnregistrationParams): CompletableFuture<Void> { wrapper.unregisterCapability(params); return CompletableFuture.completedFuture(null) }
        override fun notifyProgress(params: ProgressParams) {}
        override fun telemetryEvent(value: Any?) {}
        override fun showMessage(params: MessageParams) {}
        override fun showMessageRequest(params: ShowMessageRequestParams) = CompletableFuture.completedFuture<MessageActionItem>(null)
        override fun logMessage(params: MessageParams) {}
        override fun publishDiagnostics(params: PublishDiagnosticsParams) {}
        override fun configuration(params: ConfigurationParams): CompletableFuture<List<Any>> = CompletableFuture.completedFuture(params.items.map { emptyMap<String, Any>() })
    }
    private fun edt(action: () -> Unit) = EdtTestUtil.runInEdtAndWait<RuntimeException> { WriteIntentReadAction.run(Runnable(action)) }
    private fun sha(path: Path) = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }
    companion object {
        private const val ORIGINAL = "package completiondemo\n\n"
        private const val APPENDED = "func use() {\n    _ = \"中文😀\"; var b bytes.Buf\n    _ = b\n}\n"
    }
}
