package com.redhat.devtools.lsp4ij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.DocumentEx
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.EdtTestUtil
import com.intellij.testFramework.LightPlatformTestCase
import com.redhat.devtools.lsp4ij.client.features.LSPClientFeatures
import com.redhat.devtools.lsp4ij.features.highlight.LSPDocumentHighlightParams
import com.redhat.devtools.lsp4ij.features.highlight.LSPHighlightSupport
import com.redhat.devtools.lsp4ij.server.definition.LanguageServerDefinition
import dev.openidea.goide.lsp.GoDocumentSync
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.services.LanguageServer
import org.eclipse.lsp4j.services.TextDocumentService
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Real native documents/synchronizer/FIFO/support. Only the protocol peer is controlled. */
class GoLspHighlightOrderingTest : LightPlatformTestCase() {
    override fun runInDispatchThread() = false

    fun testUnicodePasteCannotOvertakeTheRealInflightDidChangeBarrier() = fixture { f ->
        f.hold().use { held ->
            f.paste()
            val pending = f.query()
            val overtaken = f.wrapper.requests.poll(300, TimeUnit.MILLISECONDS)
            assertNull("Highlight overtook held didChange: $overtaken; peer=${f.wrapper.events}", overtaken)
            assertFalse(pending.isDone)
            held.release()
            val highlights = pending.get(10, TimeUnit.SECONDS)
            assertEquals(1, highlights.size)
            val request = requireNotNull(f.wrapper.requests.poll(5, TimeUnit.SECONDS))
            assertEquals(f.position(), request.position)
            assertEquals(23, request.position.line)
            val line = read { f.document.text.lines()[23] }
            assertTrue("Supplementary text before the identifier must use UTF-16 columns", request.position.character > line.substringBefore("Dirty").codePointCount(0, line.substringBefore("Dirty").length))
            assertTrue(request.valid)
            assertEquals(read { f.document.text }, f.wrapper.text)
            assertEquals(listOf("open:1", "change:2", "highlight:2"), f.wrapper.events.toList())
        }
    }

    fun testChangedWhileWaitingCannotDispatchTheOldPosition() = fixture { f ->
        f.hold().use { held ->
            f.paste()
            val pending = f.query()
            f.edit("// shifted 😀\n" + read { f.document.text })
            held.release()
            rejected(pending)
            assertTrue("An expired generation must not reach the peer", f.wrapper.requests.isEmpty())
            assertEquals(1, f.query().get(10, TimeUnit.SECONDS).size)
            assertEquals(f.position(), f.wrapper.requests.single().position)
        }
    }

    fun testNativeUndoDoesNotReviveAnOldWaitingGeneration() = fixture { f ->
        f.hold().use { held ->
            f.paste()
            val before = read { f.document.text }
            val stamp = f.document.modificationStamp
            val sequence = (f.document as DocumentEx).modificationSequence
            val pending = f.query()
            f.edit("// temporary prefix 😀\n" + before)
            f.undo()
            assertEquals(before, read { f.document.text })
            assertEquals("The real native Undo restores the old arbitrary stamp", stamp, f.document.modificationStamp)
            assertTrue((f.document as DocumentEx).modificationSequence > sequence)
            held.release()
            rejected(pending)
            assertTrue(f.wrapper.requests.isEmpty())
            assertEquals(1, f.query().get(10, TimeUnit.SECONDS).size)
        }
    }

    fun testCancellationDoesNotCancelSharedOpenOrChangeFuturesAndDoesNotSend() = fixture { f ->
        f.hold().use { held ->
            f.paste()
            val shared = f.synchronizer.flushPendingChanges()
            val pending = f.query()
            f.support.cancel()
            held.release()
            rejected(pending)
            shared.get(10, TimeUnit.SECONDS)
            assertFalse(shared.isCancelled)
            assertFalse(f.synchronizer.didOpenFuture.isCancelled)
            assertTrue(f.wrapper.requests.isEmpty())
            assertEquals(1, f.query().get(10, TimeUnit.SECONDS).size)
        }
    }

    fun testCancellationDuringPendingDidOpenLeavesTheSharedConnectionAlive() = fixture(holdInitialOpen = true) { f ->
        val sharedOpen = f.synchronizer.didOpenFuture
        assertFalse(sharedOpen.isDone)
        val pending = f.query(0)
        f.support.cancel()
        f.wrapper.releaseOpen.countDown()
        rejected(pending)
        sharedOpen.get(10, TimeUnit.SECONDS)
        assertFalse(sharedOpen.isCancelled)
        assertTrue(f.wrapper.requests.isEmpty())
        assertTrue(f.query(0).get(10, TimeUnit.SECONDS).isEmpty())
        assertEquals(1, f.wrapper.requests.size)
    }

    fun testDisposingTheNativeFileSupportWhileWaitingDoesNotSend() = fixture { f ->
        f.hold().use { held ->
            f.paste()
            val shared = f.synchronizer.flushPendingChanges()
            val pending = f.query()
            Disposer.dispose(LSPFileSupport.getSupport(f.file))
            held.release()
            rejected(pending)
            shared.get(10, TimeUnit.SECONDS)
            assertFalse(shared.isCancelled)
            assertTrue(f.wrapper.requests.isEmpty())
        }
    }

    fun testClosingTheActualSynchronizerRejectsItsWaitingGeneration() = fixture { f ->
        f.hold().use { held ->
            f.paste()
            val pending = f.query()
            f.wrapper.disconnect(f.file.virtualFile, false)
            held.release()
            rejected(pending)
            f.wrapper.flush()
            assertTrue(f.wrapper.requests.isEmpty())
        }
    }

    fun testAReplacedServerConnectionCannotReceiveTheOldWaitingRequest() = fixture { f ->
        f.hold().use { held ->
            f.paste()
            val pending = f.query()
            f.wrapper.replaceRemote()
            held.release()
            rejected(pending)
            assertTrue(f.wrapper.requests.isEmpty())
        }
    }

    fun testAReplyAfterEditAndUndoCannotPublishStaleHighlights() = fixture { f ->
        f.paste(); f.wrapper.flush()
        val remote = UncancellableReply()
        f.wrapper.pendingReply = remote
        val pending = f.query()
        assertNotNull(f.wrapper.requests.poll(10, TimeUnit.SECONDS))
        val delivered = CopyOnWriteArrayList<List<DocumentHighlight>>()
        pending.thenAccept(delivered::add)
        val before = read { f.document.text }
        f.edit("// changed reply generation\n" + before); f.undo()
        assertEquals(before, read { f.document.text })
        remote.complete(listOf(DocumentHighlight(Range(f.position(), f.position().let { Position(it.line, it.character + 5) }), DocumentHighlightKind.Read)))
        rejected(pending)
        assertTrue("Late wire replies must not become native highlight targets", delivered.isEmpty())
        f.wrapper.pendingReply = null
        assertEquals(1, f.query().get(10, TimeUnit.SECONDS).size)
    }

    fun testAReplyAfterTheActualSynchronizerClosedCannotPublishHighlights() = fixture { f ->
        f.paste(); f.wrapper.flush()
        val remote = UncancellableReply(); f.wrapper.pendingReply = remote
        val pending = f.query()
        assertNotNull(f.wrapper.requests.poll(10, TimeUnit.SECONDS))
        f.wrapper.disconnect(f.file.virtualFile, false)
        remote.complete(f.highlight())
        rejected(pending)
    }

    fun testAReplyFromThePreviousServerConnectionCannotPublishHighlights() = fixture { f ->
        f.paste(); f.wrapper.flush()
        val remote = UncancellableReply(); f.wrapper.pendingReply = remote
        val pending = f.query()
        assertNotNull(f.wrapper.requests.poll(10, TimeUnit.SECONDS))
        f.wrapper.replaceRemote()
        remote.complete(f.highlight())
        rejected(pending)
    }

    fun testCloseAndReopenOfTheSameDocumentCannotReuseThePreviousConnectionCache() = fixture { f ->
        f.paste(); f.commit(); f.wrapper.flush()
        val first = f.query()
        assertEquals(1, first.get(10, TimeUnit.SECONDS).size)
        val sourceStamp = read { f.file.modificationStamp }
        val sequence = (f.document as DocumentEx).modificationSequence
        f.wrapper.disconnect(f.file.virtualFile, false)
        f.wrapper.replaceRemote()
        val connection = read { f.wrapper.createFileConnectionInfo(f.file.virtualFile, f.document, true) }
        f.wrapper.connect(f.file.virtualFile, connection).get(10, TimeUnit.SECONDS)
        assertEquals("The PSI source was not edited during reconnect", sourceStamp, read { f.file.modificationStamp })
        assertEquals(sequence, (f.document as DocumentEx).modificationSequence)
        val fresh = f.query()
        assertNotSame("A completed highlight cache belongs to its old open connection", first, fresh)
        assertEquals(1, fresh.get(10, TimeUnit.SECONDS).size)
        assertEquals(2, f.wrapper.requests.size)
    }

    fun testStableGenerationReusesItsFutureAndOffsetChangeGetsItsOwnRequest() = fixture { f ->
        f.paste(); f.commit(); f.wrapper.flush()
        val (psiStamp, sequence) = read {
            assertTrue("Stable-cache control requires committed native source", PsiDocumentManager.getInstance(project).isCommitted(f.document))
            f.file.modificationStamp to (f.document as DocumentEx).modificationSequence
        }
        val first = f.query()
        assertEquals(1, first.get(10, TimeUnit.SECONDS).size)
        val again = read {
            assertTrue(PsiDocumentManager.getInstance(project).isCommitted(f.document))
            assertEquals("PSI must remain stable before asserting cache reuse", psiStamp, f.file.modificationStamp)
            assertEquals("The native document must remain stable before asserting cache reuse", sequence, (f.document as DocumentEx).modificationSequence)
            f.query()
        }
        assertSame(first, again)
        assertEquals(1, f.wrapper.requests.size)
        val different = f.query(read { f.document.text.indexOf("package") })
        different.get(10, TimeUnit.SECONDS)
        assertNotSame(first, different)
        assertEquals(2, f.wrapper.requests.size)
    }

    fun testStartedServerRegistryReturnsAnImmutableSnapshotRatherThanALiveView() = fixture { f ->
        val accessor = LanguageServiceAccessor.getInstance(project)
        val snapshot = accessor.startedServers
        val expected = snapshot.toSet()
        @Suppress("UNCHECKED_CAST")
        val registry = LanguageServiceAccessor::class.java.getDeclaredField("startedServers").apply { isAccessible = true }
            .get(accessor) as MutableSet<LanguageServerWrapper>
        // Exercise the actual public server-creation API, without starting the
        // new wrapper or fabricating a server collection implementation.
        val added = accessor.forceCreateServer(f.wrapper.serverDefinition)
        try {
            synchronized(registry) { registry.remove(f.wrapper) }
            val current = accessor.startedServers
            assertTrue(current.contains(added)); assertFalse(current.contains(f.wrapper))
            assertEquals("A published registry snapshot must not change when owners are added or removed", expected, snapshot)
            val failure = runCatching { (snapshot as MutableSet<LanguageServerWrapper>).clear() }.exceptionOrNull()
            assertTrue("The returned snapshot remains read-only", failure is UnsupportedOperationException)
        } finally {
            synchronized(registry) { registry.remove(added); registry.add(f.wrapper) }
            Disposer.dispose(added)
        }
    }

    private inner class Fixture(val file: PsiFile, val document: Document, val editor: Editor, val wrapper: OwnedWrapper) {
        val support: LSPHighlightSupport = LSPFileSupport.getSupport(file).highlightSupport
        val synchronizer get() = requireNotNull(wrapper.getOpenedDocuments().single().synchronizer)
        fun query(offset: Int = read { document.text.indexOf("Dirty") }): CompletableFuture<List<DocumentHighlight>> = read {
            support.getHighlights(LSPDocumentHighlightParams(TextDocumentIdentifier(), LSPIJUtils.toPosition(offset, document), offset))
        }
        fun position() = read { LSPIJUtils.toPosition(document.text.indexOf("Dirty"), document) }
        fun highlight() = listOf(DocumentHighlight(Range(position(), position().let { Position(it.line, it.character + 5) }), DocumentHighlightKind.Read))
        fun paste() = edt { WriteCommandAction.runWriteCommandAction(project) { document.insertString(document.textLength, APPENDED) } }
        fun commit() = edt { PsiDocumentManager.getInstance(project).commitDocument(document) }
        fun edit(text: String) = edt { WriteCommandAction.runWriteCommandAction(project) { document.setText(text) }; PsiDocumentManager.getInstance(project).commitDocument(document) }
        fun undo() = edt { UndoManager.getInstance(project).undo(TextEditorProvider.getInstance().getTextEditor(editor)); PsiDocumentManager.getInstance(project).commitDocument(document) }
        fun hold() = HeldDispatcher(wrapper)
    }

    private class HeldDispatcher(wrapper: OwnedWrapper) : AutoCloseable {
        private val entered = CountDownLatch(1)
        private val released = CountDownLatch(1)
        private val task = wrapper.sendNotification { server ->
            check(!ApplicationManager.getApplication().isDispatchThread)
            entered.countDown()
            check(released.await(10, TimeUnit.SECONDS)) { "Owned notification dispatcher was not released" }
            server
        }
        init { check(entered.await(10, TimeUnit.SECONDS)) { "Owned notification dispatcher did not start" } }
        fun release() = released.countDown()
        override fun close() { release(); task.get(10, TimeUnit.SECONDS) }
    }

    private fun fixture(holdInitialOpen: Boolean = false, action: (Fixture) -> Unit) {
        val root = Files.createTempDirectory("open-goland-highlight-ordering-😀").toRealPath()
        val disposable = Disposer.newDisposable("owned highlight FIFO")
        VfsRootAccess.allowRootAccess(disposable, root.toString())
        val accessor = LanguageServiceAccessor.getInstance(project)
        @Suppress("UNCHECKED_CAST")
        val started = LanguageServiceAccessor::class.java.getDeclaredField("startedServers").apply { isAccessible = true }.get(accessor) as MutableSet<LanguageServerWrapper>
        val previous = synchronized(started) { started.toList() }
        val definition = requireNotNull(LanguageServersRegistry.getInstance().getServerDefinition("openidea.gopls"))
        val wrapper = OwnedWrapper(project, definition)
        wrapper.holdInitialOpen = holdInitialOpen
        var editor: Editor? = null
        var document: Document? = null
        var psi: PsiFile? = null
        try {
            val path = root.resolve("source.go"); Files.writeString(path, ORIGINAL)
            val virtual = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path))
            val file = read { requireNotNull(PsiManager.getInstance(project).findFile(virtual)) }
            psi = file
            val native = read { requireNotNull(FileDocumentManager.getInstance().getDocument(virtual)) }; document = native
            edt { editor = EditorFactory.getInstance().createEditor(native, project) }
            synchronized(started) { started.clear(); started.add(wrapper) }
            val connection = read { wrapper.createFileConnectionInfo(virtual, native, true) }
            val connected = wrapper.connect(virtual, connection)
            if (holdInitialOpen) assertTrue(wrapper.openStarted.await(10, TimeUnit.SECONDS))
            else connected.get(10, TimeUnit.SECONDS)
            action(Fixture(file, native, requireNotNull(editor), wrapper))
        } finally {
            wrapper.releaseOpen.countDown()
            synchronized(started) { started.clear(); started.addAll(previous) }
            psi?.let { file -> if (LSPFileSupport.hasSupport(file)) Disposer.dispose(LSPFileSupport.getSupport(file)) }
            wrapper.getOpenedDocuments().mapNotNull { it.synchronizer }.forEach { Disposer.dispose(it) }
            Disposer.dispose(wrapper)
            edt {
                editor?.let { if (!it.isDisposed) EditorFactory.getInstance().releaseEditor(it) }
                WriteCommandAction.runWriteCommandAction(project) { document?.let { it.setText(ORIGINAL); FileDocumentManager.getInstance().saveDocument(it) } }
            }
            Disposer.dispose(disposable)
            root.toFile().deleteRecursively(); LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root)
        }
    }

    private data class Request(val position: Position, val version: Int, val valid: Boolean)
    private class UncancellableReply : CompletableFuture<List<DocumentHighlight>>() { override fun cancel(mayInterruptIfRunning: Boolean) = false }
    private class OwnedWrapper(project: Project, definition: LanguageServerDefinition) : LanguageServerWrapper(project, definition) {
        val requests = LinkedBlockingQueue<Request>()
        val events = CopyOnWriteArrayList<String>()
        val openStarted = CountDownLatch(1)
        val releaseOpen = CountDownLatch(1)
        var holdInitialOpen = false
        @Volatile var text = ""
        @Volatile var version = 0
        @Volatile var pendingReply: CompletableFuture<List<DocumentHighlight>>? = null
        private val features = LSPClientFeatures().also { it.setServerWrapper(this) }
        private val capabilities = ServerCapabilities().apply { setDocumentHighlightProvider(true) }
        private val options = TextDocumentSyncOptions().apply { openClose = true; change = TextDocumentSyncKind.Incremental }
        private val service = Proxy.newProxyInstance(TextDocumentService::class.java.classLoader, arrayOf(TextDocumentService::class.java)) { _, method, args ->
            check(!ApplicationManager.getApplication().isDispatchThread) { "Protocol operations must not block the EDT" }
            when (method.name) {
                "didOpen" -> {
                    if (holdInitialOpen) { openStarted.countDown(); check(releaseOpen.await(10, TimeUnit.SECONDS)) }
                    val value = (args[0] as DidOpenTextDocumentParams).textDocument; text = value.text; version = value.version; events += "open:$version"; null
                }
                "didChange" -> { val value = args[0] as DidChangeTextDocumentParams; text = GoDocumentSync.applyChanges(text, value.contentChanges); check(value.textDocument.version > version); version = value.textDocument.version; events += "change:$version"; null }
                "didClose" -> { events += "close:$version"; null }
                "documentHighlight" -> {
                    val value = args[0] as DocumentHighlightParams
                    val pos = value.position; val lines = text.split('\n')
                    val valid = pos.line in lines.indices && pos.character in 0..lines.getOrElse(pos.line) { "" }.length
                    val copy = Position(pos.line, pos.character)
                    requests += Request(copy, version, valid); events += "highlight:$version"
                    pendingReply ?: CompletableFuture.completedFuture(if (valid && lines[pos.line].substring(pos.character).startsWith("Dirty"))
                        listOf(DocumentHighlight(Range(copy, Position(pos.line, pos.character + 5)), DocumentHighlightKind.Read)) else emptyList())
                }
                else -> null
            }
        } as TextDocumentService
        @Volatile private var remote: LanguageServer = newRemote()
        init { features.setServerCapabilities(capabilities); setField("serverCapabilities", capabilities); setField("initializeFuture", CompletableFuture.completedFuture<Void>(null)); setField("languageServer", remote) }
        private fun newRemote() = Proxy.newProxyInstance(LanguageServer::class.java.classLoader, arrayOf(LanguageServer::class.java)) { _, method, _ -> if (method.name == "getTextDocumentService") service else null } as LanguageServer
        fun replaceRemote() { remote = newRemote(); setField("languageServer", remote); incrementModificationCount() }
        fun flush() { sendNotification { it }.get(10, TimeUnit.SECONDS) }
        override fun start() {}
        override fun getInitializedServer() = CompletableFuture.completedFuture(remote)
        override fun getClientFeatures() = features
        override fun getServerStatus() = ServerStatus.started
        override fun isEnabled() = true
        override fun canOperate(file: VirtualFile) = true
        override fun getSyncOptions() = options
        override fun dispose() { openedDocuments.clear(); stopDispatcher(); features.dispose() }
        private fun setField(name: String, value: Any) { LanguageServerWrapper::class.java.getDeclaredField(name).apply { isAccessible = true }.set(this, value) }
    }
    private fun rejected(future: CompletableFuture<*>) {
        val failure = runCatching { future.get(10, TimeUnit.SECONDS) }.exceptionOrNull()
        assertNotNull("The expired/cancelled request must not complete successfully", failure)
        assertFalse("Cancellation must finish rather than time out", failure is java.util.concurrent.TimeoutException)
        assertTrue(generateSequence(requireNotNull(failure) as Throwable?) { it.cause }.any { it is CancellationException || it is com.intellij.openapi.progress.ProcessCanceledException })
    }
    private fun <T> read(action: () -> T): T = ReadAction.compute<T, RuntimeException> { action() }
    private fun edt(action: () -> Unit) = EdtTestUtil.runInEdtAndWait<RuntimeException> { WriteIntentReadAction.run(Runnable(action)) }
    companion object {
        private val ORIGINAL = "package fixture\n" + "// before paste\n".repeat(20)
        private const val APPENDED = "\n// 未保存 😀\nvar _ = \"前😀\"; var Dirty = 1\n"
    }
}
