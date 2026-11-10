package com.redhat.devtools.lsp4ij

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
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
import com.redhat.devtools.lsp4ij.server.definition.LanguageServerDefinition
import com.redhat.devtools.lsp4ij.client.features.LSPClientFeatures
import com.redhat.devtools.lsp4ij.features.completion.LSPCompletionParams
import com.redhat.devtools.lsp4ij.features.completion.LSPCompletionSupport
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.LanguageServer
import org.eclipse.lsp4j.services.TextDocumentService
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Auxiliary protocol peers only; the separate Basic Lookup test uses actual gopls. */
class GoCompletionBarrierLifecycleTest : LightPlatformTestCase() {
    override fun runInDispatchThread() = false

    fun testCancellingAQueuedDocumentDoesNotCancelSharedChangesOrAnotherDocument() = Fixture().use { f ->
        val a = f.rows[0]; val b = f.rows[1]
        f.hold().use { held ->
            f.edit(b, "// 中文😀\n" + b.original)
            val shared = f.wrapper.getOpenedDocument(LSPIJUtils.toUri(b.file.virtualFile))!!.synchronizer!!.flushPendingChanges()
            val pa = f.query(a); val pb = f.query(b)
            edt { b.support.cancel() }
            held.release()
            assertEquals(1, pa.get(10, TimeUnit.SECONDS).size)
            rejected(pb)
            shared.get(10, TimeUnit.SECONDS)
            assertFalse("Completion owns no shared notification", shared.isCancelled)
            assertFalse(f.wrapper.getOpenedDocument(LSPIJUtils.toUri(b.file.virtualFile))!!.synchronizer!!.didOpenFuture.isCancelled)
            assertEquals(listOf(a.uri), f.wrapper.calls.map { it.textDocument.uri })
            assertEquals(1, f.query(b).get(10, TimeUnit.SECONDS).size)
            assertEquals(listOf(a.uri, b.uri), f.wrapper.calls.map { it.textDocument.uri })
            val call = f.wrapper.calls.last()
            assertEquals(read { LSPIJUtils.toPosition(b.document.text.indexOf("Beta"), b.document) }, call.position)
        }
    }

    fun testEdtCancellationOfAnIssuedRequestWritesNoRawCancelOnTheEdt() = Fixture().use { f ->
        val a = f.rows[0]
        val raw = TrackingReply()
        f.wrapper.pending = raw
        val pending = f.query(a)
        assertEquals(a.uri, f.wrapper.arrived.poll(10, TimeUnit.SECONDS)?.textDocument?.uri)
        edt { a.support.cancel() }
        rejected(pending)
        assertTrue("The request's raw cancellation must actually run", raw.cancelled.await(10, TimeUnit.SECONDS))
        assertEquals(1, raw.cancelCalls.get())
        assertFalse("Raw cancel may emit LSP4J transport bytes", raw.onEdt.get())
        assertFalse(f.wrapper.getOpenedDocument(LSPIJUtils.toUri(a.file.virtualFile))!!.synchronizer!!.didOpenFuture.isCancelled)
        f.wrapper.pending = null
        assertEquals(1, f.query(a).get(10, TimeUnit.SECONDS).size)
    }

    fun testLateReplyAfterRealEditUndoAndReopenedConnectionCannotReviveOrReuseTheOldGeneration() = Fixture().use { f ->
        val a = f.rows[0]
        val late = object : CompletableFuture<Either<List<CompletionItem>, CompletionList>>() {
            override fun cancel(mayInterruptIfRunning: Boolean) = false
        }
        f.wrapper.pending = late
        val old = f.query(a)
        assertNotNull(f.wrapper.arrived.poll(10, TimeUnit.SECONDS))
        f.edit(a, "// 中文😀\n" + a.original)
        edt {
            UndoManager.getInstance(project).undo(TextEditorProvider.getInstance().getTextEditor(a.editor))
            PsiDocumentManager.getInstance(project).commitDocument(a.document)
        }
        assertEquals(a.original, read { a.document.text })
        assertTrue("The peer really completes its uncancellable old response", late.complete(reply("old")))
        rejected(old)
        f.wrapper.pending = null
        val fresh = f.query(a)
        assertEquals(1, fresh.get(10, TimeUnit.SECONDS).size)
        assertSame("Stable generation reuses its cached future", fresh, f.query(a))
        val oldOpened = f.wrapper.getOpenedDocument(LSPIJUtils.toUri(a.file.virtualFile))
        f.wrapper.disconnect(a.file.virtualFile, false)
        f.wrapper.connect(a.file.virtualFile, read { f.wrapper.createFileConnectionInfo(a.file.virtualFile, a.document, true) }).get(10, TimeUnit.SECONDS)
        assertNotSame(oldOpened, f.wrapper.getOpenedDocument(LSPIJUtils.toUri(a.file.virtualFile)))
        val reopened = f.query(a)
        assertNotSame("A replaced opened-document owner invalidates completed caches", fresh, reopened)
        assertEquals(1, reopened.get(10, TimeUnit.SECONDS).size)
        assertEquals(3, f.wrapper.calls.count { it.textDocument.uri == a.uri })
    }

    private data class Row(val uri: String, val original: String, val file: PsiFile, val document: Document, val editor: Editor, val support: LSPCompletionSupport)
    private inner class Fixture : AutoCloseable {
        private val root = Files.createTempDirectory("owned completion lifecycle 中文😀 ").toRealPath()
        private val lifetime = Disposer.newDisposable("owned completion boundary")
        private val accessor = LanguageServiceAccessor.getInstance(project)
        @Suppress("UNCHECKED_CAST")
        private val registry = LanguageServiceAccessor::class.java.getDeclaredField("startedServers").apply { isAccessible = true }.get(accessor) as MutableSet<LanguageServerWrapper>
        private val previous = synchronized(registry) { registry.toList() }
        val wrapper = Wrapper(project, requireNotNull(LanguageServersRegistry.getInstance().getServerDefinition("openidea.gopls")))
        val rows = mutableListOf<Row>()
        init {
            VfsRootAccess.allowRootAccess(lifetime, root.toString())
            synchronized(registry) { registry.clear(); registry.add(wrapper) }
            for (name in listOf("Alpha", "Beta")) {
                val text = "package fixture\n\nvar $name = 1\n"
                val path = root.resolve(name.lowercase() + ".go"); Files.writeString(path, text)
                val virtual = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path))
                val file = read { requireNotNull(PsiManager.getInstance(project).findFile(virtual)).also { it.node } }
                val document = read { requireNotNull(FileDocumentManager.getInstance().getDocument(virtual)) }
                lateinit var editor: Editor
                edt { editor = EditorFactory.getInstance().createEditor(document, project); PsiDocumentManager.getInstance(project).commitDocument(document) }
                wrapper.connect(virtual, read { wrapper.createFileConnectionInfo(virtual, document, true) }).get(10, TimeUnit.SECONDS)
                rows += Row(LSPIJUtils.toUriAsString(virtual), text, file, document, editor, LSPFileSupport.getSupport(file).completionSupport)
            }
            wrapper.flush()
        }
        fun query(row: Row) = read {
            val name = if (row.original.contains("Alpha")) "Alpha" else "Beta"
            val offset = row.document.text.indexOf(name)
            row.support.getCompletions(LSPCompletionParams(TextDocumentIdentifier(), LSPIJUtils.toPosition(offset, row.document), offset, null, false))
        }
        fun edit(row: Row, text: String) = edt { WriteCommandAction.runWriteCommandAction(project) { row.document.setText(text) }; PsiDocumentManager.getInstance(project).commitDocument(row.document) }
        fun hold() = Held(wrapper)
        override fun close() {
            synchronized(registry) { registry.clear(); registry.addAll(previous) }
            rows.forEach { if (LSPFileSupport.hasSupport(it.file)) Disposer.dispose(LSPFileSupport.getSupport(it.file)) }
            Disposer.dispose(wrapper)
            edt {
                rows.forEach { row ->
                    WriteCommandAction.runWriteCommandAction(project) { row.document.setText(row.original); FileDocumentManager.getInstance().saveDocument(row.document) }
                    EditorFactory.getInstance().releaseEditor(row.editor)
                }
            }
            Disposer.dispose(lifetime)
            root.toFile().deleteRecursively()
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root)
        }
    }

    private class Held(wrapper: Wrapper) : AutoCloseable {
        private val entered = CountDownLatch(1)
        private val release = CountDownLatch(1)
        private val task = wrapper.sendNotification { server -> entered.countDown(); check(release.await(10, TimeUnit.SECONDS)); server }
        init { check(entered.await(10, TimeUnit.SECONDS)) }
        fun release() = release.countDown()
        override fun close() { release(); task.get(10, TimeUnit.SECONDS) }
    }
    private class TrackingReply : CompletableFuture<Either<List<CompletionItem>, CompletionList>>() {
        val cancelled = CountDownLatch(1)
        val cancelCalls = AtomicInteger()
        val onEdt = AtomicBoolean()
        override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
            cancelCalls.incrementAndGet(); onEdt.set(ApplicationManager.getApplication().isDispatchThread)
            val result = super.cancel(mayInterruptIfRunning); cancelled.countDown(); return result
        }
    }
    private class Wrapper(project: Project, definition: LanguageServerDefinition) : LanguageServerWrapper(project, definition) {
        val calls = CopyOnWriteArrayList<CompletionParams>()
        val arrived = LinkedBlockingQueue<CompletionParams>()
        @Volatile var pending: CompletableFuture<Either<List<CompletionItem>, CompletionList>>? = null
        private val features = LSPClientFeatures().also { it.setServerWrapper(this) }
        private val capabilities = ServerCapabilities().apply { completionProvider = CompletionOptions().apply { resolveProvider = false; triggerCharacters = listOf(".") } }
        private val sync = TextDocumentSyncOptions().apply { openClose = true; change = TextDocumentSyncKind.Incremental }
        private val service = Proxy.newProxyInstance(TextDocumentService::class.java.classLoader, arrayOf(TextDocumentService::class.java)) { _, method, args ->
            check(!ApplicationManager.getApplication().isDispatchThread) { "Protocol methods must run on a worker" }
            when (method.name) {
                "completion" -> {
                    check(ApplicationManager.getApplication().isReadAccessAllowed)
                    val p = args[0] as CompletionParams
                    calls += p; arrived.offer(p)
                    pending ?: CompletableFuture.completedFuture(reply(p.textDocument.uri))
                }
                else -> null
            }
        } as TextDocumentService
        private val remote = Proxy.newProxyInstance(LanguageServer::class.java.classLoader, arrayOf(LanguageServer::class.java)) { _, method, _ -> if (method.name == "getTextDocumentService") service else null } as LanguageServer
        init { features.setServerCapabilities(capabilities); setField("serverCapabilities", capabilities); setField("initializeFuture", CompletableFuture.completedFuture<Void>(null)); setField("languageServer", remote) }
        fun flush() { sendNotification { it }.get(10, TimeUnit.SECONDS) }
        override fun start() {}
        override fun getInitializedServer() = CompletableFuture.completedFuture(remote)
        override fun getClientFeatures() = features
        override fun getServerStatus() = ServerStatus.started
        override fun isEnabled() = true
        override fun canOperate(file: VirtualFile) = true
        override fun getSyncOptions() = sync
        override fun dispose() { openedDocuments.values.forEach { it.synchronizer?.let(Disposer::dispose) }; openedDocuments.clear(); stopDispatcher(); features.dispose() }
        private fun setField(name: String, value: Any) { LanguageServerWrapper::class.java.getDeclaredField(name).apply { isAccessible = true }.set(this, value) }
    }
    private fun rejected(future: CompletableFuture<*>) {
        val error = runCatching { future.get(10, TimeUnit.SECONDS) }.exceptionOrNull()
        assertNotNull("An obsolete/canceled completion cannot publish", error)
        assertFalse("Cancellation is terminal, not a timeout", error is java.util.concurrent.TimeoutException)
    }
    private fun edt(action: () -> Unit) = EdtTestUtil.runInEdtAndWait<RuntimeException> { WriteIntentReadAction.run(Runnable(action)) }
    private fun <T> read(action: () -> T): T = ReadAction.compute<T, RuntimeException>(action)
    companion object {
        private fun reply(label: String): Either<List<CompletionItem>, CompletionList> = Either.forLeft(listOf(CompletionItem(label)))
    }
}
