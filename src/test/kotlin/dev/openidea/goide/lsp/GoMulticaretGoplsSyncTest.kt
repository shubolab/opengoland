@file:Suppress("PackageDirectoryMismatch")

package com.redhat.devtools.lsp4ij

import com.google.gson.Gson
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.EditorTestUtil
import com.intellij.testFramework.EdtTestUtil
import com.intellij.testFramework.LightPlatformTestCase
import com.redhat.devtools.lsp4ij.client.features.LSPClientFeatures
import com.redhat.devtools.lsp4ij.server.StreamConnectionProvider
import com.redhat.devtools.lsp4ij.server.definition.LanguageServerDefinition
import dev.openidea.goide.generate.tags.GoStructTagValuesIntention
import dev.openidea.goide.lsp.GoDocumentSync
import dev.openidea.goide.lsp.GoLanguageServerFactory
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.MessageConsumer
import org.eclipse.lsp4j.jsonrpc.messages.NotificationMessage
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageServer
import org.junit.Assume
import java.awt.datatransfer.StringSelection
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Actual pinned Wrapper -> native DocumentContentSynchronizer -> stdio gopls.
 * Only process initialization is supplied by this fixture. Native Paste and
 * Undo/Redo create every change; no didChange payload is manufactured here.
 *
 * The mirror and notification futures are transport evidence, never server ACKs.
 * Actual gopls hover/symbol responses prove each checked overlay; versioned
 * diagnostics prove two new errors appear and disappear after Undo/Redo.
 *
 * SDK unit-test mode sends changes immediately. This does not exercise the
 * production performForCommittedDocument scheduling or native UI icon counts.
 */
class GoMulticaretGoplsSyncTest : LightPlatformTestCase() {
    override fun runInDispatchThread() = false

    fun testUnicodeNativePasteUndoRedoReachesRealGoplsWithOrderedIncrementalVersions() {
        val executable = System.getenv("OPENGOLAND_GOPLS_TEST")?.let(Path::of)
            ?: System.getenv("PATH").orEmpty().split(java.io.File.pathSeparator)
                .map { Path.of(it, "gopls") }.firstOrNull { Files.isExecutable(it) }
        Assume.assumeTrue("Install gopls or set OPENGOLAND_GOPLS_TEST", executable != null)
        val root = Files.createTempDirectory("open-goland-multicaret-gopls-").toRealPath()
        val disposable = Disposer.newDisposable("owned multicaret gopls native fixture")
        VfsRootAccess.allowRootAccess(disposable, root.toString())
        val pool = Executors.newCachedThreadPool()
        val wrapper = GoplsWrapper(project)
        val client = Client()
        val mirror = GoDocumentSync()
        val session = mirror.startSession()
        val records = CopyOnWriteArrayList<Any>()
        val deltas = CopyOnWriteArrayList<TextDocumentContentChangeEvent>()
        val gson = Gson()
        var process: Process? = null
        var listening: Future<Void>? = null
        var server: LanguageServer? = null
        var editor: Editor? = null
        try {
            Files.writeString(root.resolve("go.mod"), "module example.com/multicaret\n\ngo 1.24.0\n")
            val path = root.resolve("profile.go")
            Files.writeString(path, ORIGINAL)
            val uri = path.toUri().toString()
            val virtual = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path))
            val file = ReadAction.compute<PsiFile, RuntimeException> {
                requireNotNull(PsiManager.getInstance(project).findFile(virtual))
            }
            val document = ReadAction.compute<Document, RuntimeException> {
                requireNotNull(PsiDocumentManager.getInstance(project).getDocument(file))
            }
            document.addDocumentListener(object : DocumentListener {
                override fun beforeDocumentChange(event: DocumentEvent) {
                    val old = event.document.immutableCharSequence
                    deltas += TextDocumentContentChangeEvent().apply {
                        range = Range(position(old, event.offset), position(old, event.offset + event.oldLength))
                        @Suppress("DEPRECATION")
                        rangeLength = event.oldLength
                        text = event.newFragment.toString()
                    }
                }
            }, disposable)
            process = ProcessBuilder(executable.toString(), "serve").directory(root.toFile())
                .redirectError(root.resolve("gopls.stderr").toFile()).apply {
                    environment()["GOTOOLCHAIN"] = "local"
                    environment()["GOTELEMETRY"] = "off"
                    environment()["GOPROXY"] = "off"
                    environment()["GOWORK"] = "off"
                    environment()["CGO_ENABLED"] = "0"
                }.start()
            val launcher = wrapper.features.createLauncherBuilder<LanguageServer>()
                .setRemoteInterface(LanguageServer::class.java).setLocalService(client)
                .setInput(process.inputStream).setOutput(process.outputStream).setExecutorService(pool)
                .wrapMessages { consumer -> MessageConsumer { message ->
                    if (message is NotificationMessage) {
                        // Observe the actual LSP4J transport with immutable copies.
                        when (message.method) {
                            "textDocument/didOpen" -> records += gson.fromJson(gson.toJson(message.params), DidOpenTextDocumentParams::class.java)
                            "textDocument/didChange" -> records += gson.fromJson(gson.toJson(message.params), DidChangeTextDocumentParams::class.java)
                            "textDocument/didClose" -> records += gson.fromJson(gson.toJson(message.params), DidCloseTextDocumentParams::class.java)
                        }
                        mirror.observe(session, message)
                    }
                    consumer.consume(message)
                } }.create()
            val transport = launcher.startListening()
            listening = transport
            val remote = launcher.remoteProxy
            server = remote
            val capabilities = ClientCapabilities().apply {
                general = GeneralClientCapabilities().apply { positionEncodings = listOf("utf-16") }
                textDocument = TextDocumentClientCapabilities().apply {
                    hover = HoverCapabilities().apply { contentFormat = listOf("markdown") }
                    documentSymbol = DocumentSymbolCapabilities().apply { hierarchicalDocumentSymbolSupport = true }
                    publishDiagnostics = PublishDiagnosticsCapabilities().apply { versionSupport = true }
                }
            }
            val initialized = remote.initialize(InitializeParams().apply {
                this.capabilities = capabilities
                rootUri = root.toUri().toString()
                workspaceFolders = listOf(WorkspaceFolder(root.toUri().toString(), "multicaret"))
                initializationOptions = mapOf("diagnosticsDelay" to "10ms", "staticcheck" to false)
            }).get(30, TimeUnit.SECONDS)
            assertTrue(initialized.capabilities.positionEncoding in listOf(null, "utf-16"))
            remote.initialized(InitializedParams())
            wrapper.attach(remote, initialized.capabilities, transport)
            assertTrue("The wrapper must use the real live transport state", wrapper.isActive)
            val info = ReadAction.compute<LanguageServerWrapper.LSPFileConnectionInfo, RuntimeException> {
                wrapper.createFileConnectionInfo(virtual, document, true)
            }
            wrapper.connect(virtual, info).get(15, TimeUnit.SECONDS)
            lateinit var native: Editor
            onEdt { native = EditorFactory.getInstance().createEditor(document, project); editor = native }

            fun checkpoint(expected: String, prefix: String): Int {
                // A FIFO notification barrier orders this request after native
                // events. It is not treated as acknowledgement by gopls.
                wrapper.flush()
                val version = assertIncrementalTrace(records, deltas, expected)
                assertEquals(expected, ReadAction.compute<String, RuntimeException> { document.text })
                val snapshot = requireNotNull(mirror.snapshot(uri))
                assertEquals(version, snapshot.version); assertEquals(expected, snapshot.text)
                val hover = remote.textDocumentService.hover(HoverParams(TextDocumentIdentifier(uri), Position(2, 6))).get(30, TimeUnit.SECONDS)
                assertNotNull("The real server must resolve the latest Profile", hover)
                assertTrue("gopls must return its actual struct declaration", hover.contents.isRight)
                val declaration = hover.contents.right.value
                assertTrue(declaration, declaration.contains("json:\"${prefix}name,omitempty\""))
                assertTrue(declaration, declaration.contains("json:\"${prefix}nick,omitempty\""))
                if (prefix.isEmpty()) assertFalse(declaration, declaration.contains(UNICODE + "name"))
                val symbols = remote.textDocumentService.documentSymbol(DocumentSymbolParams(TextDocumentIdentifier(uri))).get(30, TimeUnit.SECONDS)
                val profile = symbols.single { (if (it.isRight) it.right.name else it.left.name) == "Profile" }
                val range = if (profile.isRight) profile.right.range else profile.left.location.range
                assertEquals("Server symbol extent must use UTF-16 after both insertions", Position(2, expected.lineSequence().elementAt(2).length), range.end)
                return version
            }
            val initialVersion = checkpoint(ORIGINAL, "")
            client.awaitDiagnostics(uri, initialVersion) { it.diagnostics.isEmpty() }
            onEdt {
                native.caretModel.moveToOffset(ORIGINAL.indexOf("json:"))
                val action: IntentionAction = GoStructTagValuesIntention()
                assertTrue(action.isAvailable(project, native, file))
                action.invoke(project, native, file)
                assertEquals(2, native.caretModel.caretCount)
                paste(native, UNICODE)
            }
            val unicode = ORIGINAL.replace("json:\"name", "json:\"${UNICODE}name").replace("json:\"nick", "json:\"${UNICODE}nick")
            checkpoint(unicode, UNICODE)
            onEdt { undo(native) }
            checkpoint(ORIGINAL, "")
            onEdt { redo(native) }
            checkpoint(unicode, UNICODE)

            // Deliberately do not wait for transport/server work between these
            // native commands, matching rapid paste/Undo/Redo interaction.
            onEdt { repeat(3) { undo(native); redo(native) } }
            checkpoint(unicode, UNICODE)

            onEdt {
                val offsets = listOf(unicode.indexOf("var First = ") + "var First = ".length,
                    unicode.indexOf("var Second = ") + "var Second = ".length)
                native.caretModel.setCaretsAndSelections(offsets.map {
                    val p = native.offsetToLogicalPosition(it)
                    com.intellij.openapi.editor.CaretState(p, p, p)
                })
                paste(native, MISSING)
            }
            val errors = unicode.replace("var First = 已知", "var First = ${MISSING}已知")
                .replace("var Second = 已知", "var Second = ${MISSING}已知")
            val errorVersion = checkpoint(errors, UNICODE)
            client.awaitDiagnostics(uri, errorVersion) { params ->
                params.diagnostics.count { it.severity == DiagnosticSeverity.Error && diagnosticText(it).contains(MISSING + "已知") } == 2
            }
            onEdt { undo(native) }
            val cleared = checkpoint(unicode, UNICODE)
            client.awaitDiagnostics(uri, cleared) { it.diagnostics.isEmpty() }
            onEdt { redo(native) }
            val repeated = checkpoint(errors, UNICODE)
            client.awaitDiagnostics(uri, repeated) { params ->
                params.diagnostics.count { it.severity == DiagnosticSeverity.Error && diagnosticText(it).contains(MISSING + "已知") } == 2
            }
            onEdt { undo(native) }
            val finalVersion = checkpoint(unicode, UNICODE)
            client.awaitDiagnostics(uri, finalVersion) { it.diagnostics.isEmpty() }
            assertEquals("The entire regression must use unsaved native overlays", ORIGINAL, Files.readString(path))
            assertTrue("Undo and Redo must each deliver real incremental document events", deltas.size >= 24)
            wrapper.disconnect(virtual, false)
            wrapper.flush()
            assertFalse(wrapper.isConnectedTo(path.toUri()))
            assertTrue("The native synchronizer must actually emit didClose", records.last() is DidCloseTextDocumentParams)
            assertEquals((records.first() as DidOpenTextDocumentParams).textDocument.uri,
                (records.last() as DidCloseTextDocumentParams).textDocument.uri)
            assertNull(mirror.snapshot(uri))
            val closedHover = remote.textDocumentService.hover(HoverParams(TextDocumentIdentifier(uri), Position(2, 6))).get(30, TimeUnit.SECONDS)
            assertNotNull("After didClose, the server must resolve the unchanged on-disk file", closedHover)
            assertTrue(closedHover.contents.isRight)
            val onDisk = closedHover.contents.right.value
            assertTrue(onDisk, onDisk.contains("json:\"name,omitempty\""))
            assertTrue(onDisk, onDisk.contains("json:\"nick,omitempty\""))
            assertFalse("Server overlay must be removed rather than only clearing the client mirror", onDisk.contains(UNICODE + "name"))
        } finally {
            onEdt { editor?.takeUnless { it.isDisposed }?.let(EditorFactory.getInstance()::releaseEditor) }
            runCatching { Disposer.dispose(wrapper) }
            runCatching { server?.shutdown()?.get(3, TimeUnit.SECONDS); server?.exit() }
            runCatching { process?.outputStream?.close() }
            process?.let {
                if (!it.waitFor(3, TimeUnit.SECONDS)) {
                    it.destroy()
                    if (!it.waitFor(3, TimeUnit.SECONDS)) { it.destroyForcibly(); check(it.waitFor(3, TimeUnit.SECONDS)) }
                }
            }
            listening?.cancel(true)
            pool.shutdownNow()
            mirror.dispose()
            Disposer.dispose(disposable)
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root)
        }
    }

    private fun assertIncrementalTrace(records: List<Any>, deltas: List<TextDocumentContentChangeEvent>, expected: String): Int {
        val opened = records.first() as DidOpenTextDocumentParams
        assertEquals(ORIGINAL, opened.textDocument.text)
        assertEquals(1, opened.textDocument.version)
        var version = opened.textDocument.version
        var text = opened.textDocument.text
        val changes = ArrayList<TextDocumentContentChangeEvent>()
        for (record in records.drop(1)) {
            val changed = record as DidChangeTextDocumentParams
            assertEquals("Every native notification version must be serial", ++version, changed.textDocument.version)
            for (change in changed.contentChanges) {
                assertNotNull("Incremental synchronization must retain native ranges", change.range)
                changes += change
            }
            text = GoDocumentSync.applyChanges(text, changed.contentChanges)
        }
        assertEquals("Every native document event must be delivered exactly once and in order", deltas, changes)
        assertEquals(expected, text)
        return version
    }

    private fun paste(editor: Editor, text: String) {
        val clipboard = CopyPasteManager.getInstance()
        val previous = clipboard.contents
        try {
            clipboard.setContents(StringSelection(text))
            EditorTestUtil.executeAction(editor, IdeActions.ACTION_EDITOR_PASTE, true)
        } finally { if (previous != null) clipboard.setContents(previous) }
    }
    private fun undo(editor: Editor) {
        val manager = UndoManager.getInstance(project)
        val fileEditor = TextEditorProvider.getInstance().getTextEditor(editor)
        assertTrue(manager.isUndoAvailable(fileEditor)); manager.undo(fileEditor)
    }
    private fun redo(editor: Editor) {
        val manager = UndoManager.getInstance(project)
        val fileEditor = TextEditorProvider.getInstance().getTextEditor(editor)
        assertTrue(manager.isRedoAvailable(fileEditor)); manager.redo(fileEditor)
    }
    private fun onEdt(action: () -> Unit) = EdtTestUtil.runInEdtAndWait<RuntimeException> { WriteIntentReadAction.run(Runnable(action)) }

    private class GoplsWrapper(project: Project) : LanguageServerWrapper(project, definition()) {
        val features: LSPClientFeatures = GoLanguageServerFactory().createClientFeatures().also { it.setServerWrapper(this) }
        private lateinit var remote: LanguageServer
        override fun start() {}
        override fun getClientFeatures() = features
        override fun getInitializedServer(): CompletableFuture<LanguageServer> = CompletableFuture.completedFuture(remote)
        fun attach(server: LanguageServer, capabilities: ServerCapabilities, transport: Future<Void>) {
            remote = server
            setField("languageServer", server)
            setField("initializeFuture", CompletableFuture.completedFuture<Void>(null))
            setField("serverCapabilities", capabilities)
            // documentClosed intentionally skips didClose for inactive servers.
            // Preserve that production condition using the actual listening
            // future, not an overridden isActive or a fabricated pending future.
            setField("launcherFuture", transport)
            assertEquals(TextDocumentSyncKind.Incremental, syncOptions.change)
        }
        fun flush() { sendNotification { it }.get(10, TimeUnit.SECONDS) }
        override fun dispose() {
            openedDocuments.values.forEach { it.synchronizer?.let(Disposer::dispose) }
            openedDocuments.clear()
            stopDispatcher()
            features.dispose()
        }
        private fun setField(name: String, value: Any) {
            LanguageServerWrapper::class.java.getDeclaredField(name).apply { isAccessible = true }.set(this, value)
        }
        companion object {
            private fun definition() = object : LanguageServerDefinition("open-goland-real-gopls-sync-test", "Owned native gopls synchronization", "", false, null, false, false) {
                override fun createConnectionProvider(project: Project): StreamConnectionProvider = error("The test owns the exact gopls process")
                override fun getLanguageId(file: com.intellij.openapi.vfs.VirtualFile?, project: Project) = "go"
            }
        }
    }

    private class Client : LanguageClient {
        private val history = CopyOnWriteArrayList<PublishDiagnosticsParams>()
        private val updates = LinkedBlockingQueue<PublishDiagnosticsParams>()
        override fun publishDiagnostics(params: PublishDiagnosticsParams) { history += params; updates.offer(params) }
        fun awaitDiagnostics(uri: String, version: Int, predicate: (PublishDiagnosticsParams) -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (true) {
                if (history.any { it.uri == uri && it.version == version && predicate(it) }) return
                val remaining = deadline - System.nanoTime()
                check(remaining > 0) { "gopls did not publish expected diagnostics for version $version: $history" }
                updates.poll(remaining, TimeUnit.NANOSECONDS)
            }
        }
        override fun telemetryEvent(value: Any?) {}
        override fun showMessage(params: MessageParams) {}
        override fun showMessageRequest(params: ShowMessageRequestParams): CompletableFuture<MessageActionItem> = CompletableFuture.completedFuture(null)
        override fun logMessage(params: MessageParams) {}
        override fun configuration(params: ConfigurationParams): CompletableFuture<List<Any>> = CompletableFuture.completedFuture(params.items.map { emptyMap<String, Any>() })
    }

    companion object {
        private const val UNICODE = "中文😀"
        private const val MISSING = "不存在_"
        private const val ORIGINAL = "package fixture\n\ntype Profile struct { Name string `json:\"name,omitempty\" xml:\"name\"`; Nick string `json:\"nick,omitempty\" xml:\"nick\"` }\nconst 已知 = 1\nvar First = 已知\nvar Second = 已知\n"
        // SDK LSP4J exposes String getMessage(), while the runtime plugin's
        // LSP4J 1.0 exposes Either<String, MarkupContent>. Read the actual DTO's
        // JSON field without linking to either getter return descriptor.
        private fun diagnosticText(diagnostic: Diagnostic): String =
            Gson().toJsonTree(diagnostic).asJsonObject.get("message")?.toString().orEmpty()
        private fun position(text: CharSequence, offset: Int): Position {
            val before = text.subSequence(0, offset)
            return Position(before.count { it == '\n' }, offset - (before.lastIndexOf('\n') + 1))
        }
    }
}
