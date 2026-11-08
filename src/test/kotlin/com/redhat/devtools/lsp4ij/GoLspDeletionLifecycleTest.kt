package com.redhat.devtools.lsp4ij

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
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
import com.redhat.devtools.lsp4ij.features.codeAction.CodeActionData
import com.redhat.devtools.lsp4ij.features.codeAction.intention.LSPIntentionCodeActionSupport
import com.redhat.devtools.lsp4ij.internal.CancellationSupport
import com.redhat.devtools.lsp4ij.server.StreamConnectionProvider
import com.redhat.devtools.lsp4ij.server.definition.LanguageServerDefinition
import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.CodeActionContext
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentSyncKind
import org.eclipse.lsp4j.TextDocumentSyncOptions
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.services.LanguageServer
import org.eclipse.lsp4j.services.TextDocumentService
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Real VFS deletion, native PSI feature support and patched upstream connection lifecycle. */
class GoLspDeletionLifecycleTest : LightPlatformTestCase() {
    override fun runInDispatchThread() = false

    fun testDeletedNativeFileCannotFinishItsQueuedInitializationConnection() = fixture { wrapper, files ->
        val source = files.first()
        val info = ReadAction.compute<LanguageServerWrapper.LSPFileConnectionInfo, RuntimeException> {
            wrapper.createFileConnectionInfo(source.file, source.document, true)
        }
        val connected = wrapper.connect(source.file, info)
        delete(source.file)
        assertFalse(source.file.isValid)
        wrapper.release()
        assertNull(connected.get(5, TimeUnit.SECONDS))
        assertTrue("A deleted native VirtualFile must never send a delayed didOpen", wrapper.opens.isEmpty())
    }

    fun testVfsBeforeDeleteCancelsOnlyTheDeletedCachedNativeFeatureOwner() = fixture { wrapper, files ->
        wrapper.release()
        val native = files.map { source ->
            val info = ReadAction.compute<LanguageServerWrapper.LSPFileConnectionInfo, RuntimeException> {
                wrapper.createFileConnectionInfo(source.file, source.document, true)
            }
            wrapper.connect(source.file, info).get(5, TimeUnit.SECONDS)
            ReadAction.compute<Pair<LSPFileSupport, CompletableFuture<List<CodeActionData>>>, RuntimeException> {
                val owner = LSPFileSupport.getSupport(source.psi)
                val actions = object : LSPIntentionCodeActionSupport(source.psi) {
                    override fun doLoad(params: CodeActionParams, cancellationSupport: CancellationSupport): CompletableFuture<List<CodeActionData>> =
                        cancellationSupport.execute(wrapper.remote.textDocumentService.codeAction(params)).thenApply { emptyList() }
                }
                LSPFileSupport::class.java.getDeclaredField("intentionCodeActionSupport").apply { isAccessible = true }.set(owner, actions)
                val params = CodeActionParams(TextDocumentIdentifier(source.file.toNioPath().toUri().toASCIIString()),
                    Range(Position(0, 0), Position(0, 0)), CodeActionContext(emptyList()))
                owner to actions.getCodeActions(params)
            }
        }
        val deletedUri = files[0].file.toNioPath().toUri().toASCIIString()
        val otherUri = files[1].file.toNioPath().toUri().toASCIIString()
        val deletedRequest = requireNotNull(wrapper.replies[deletedUri])
        val otherRequest = requireNotNull(wrapper.replies[otherUri])
        assertFalse(deletedRequest.isDone)
        assertFalse(otherRequest.isDone)
        delete(files[0].file)

        assertTrue("The actual native support cancellation must reach its remote future", deletedRequest.isCancelled)
        assertTrue(native[0].second.isCompletedExceptionally)
        assertFalse("A different live file keeps its pending request", otherRequest.isDone)
        assertFalse(ReadAction.compute<Boolean, RuntimeException> { LSPFileSupport.hasSupport(files[0].psi) })
        assertTrue(ReadAction.compute<Boolean, RuntimeException> { LSPFileSupport.hasSupport(files[1].psi) })

        // The fix owns cancellation, not an error-message filter: live request failures remain failures.
        val error = ResponseErrorException(ResponseError(0, "stat live.go: no such file or directory", null))
        otherRequest.completeExceptionally(error)
        val propagated = runCatching { native[1].second.get(5, TimeUnit.SECONDS) }.exceptionOrNull()
        assertSame(error, propagated?.cause)
        assertTrue("Only the deleted support was disposed", !Disposer.isDisposed(native[1].first))
    }

    private data class Source(val file: VirtualFile, val psi: PsiFile, val document: Document)

    private fun fixture(block: (HeldWrapper, List<Source>) -> Unit) {
        val root = Files.createTempDirectory("open-goland-lsp-delete-").toRealPath()
        VfsRootAccess.allowRootAccess(testRootDisposable, root.toString())
        val wrapper = HeldWrapper(project)
        try {
            val files = listOf("deleted_test.go", "live_test.go").map { name ->
                val path = root.resolve(name)
                Files.writeString(path, "package lifecycle\n")
                val file = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path))
                ReadAction.compute<Source, RuntimeException> {
                    val psi = requireNotNull(PsiManager.getInstance(project).findFile(file))
                    Source(file, psi, requireNotNull(PsiDocumentManager.getInstance(project).getDocument(psi)))
                }
            }
            block(wrapper, files)
        } finally {
            Disposer.dispose(wrapper)
            root.toFile().deleteRecursively()
        }
    }

    private fun delete(file: VirtualFile) = EdtTestUtil.runInEdtAndWait<RuntimeException> {
        WriteCommandAction.runWriteCommandAction(project, Runnable { file.delete(this) })
    }

    private class HeldWrapper(project: Project) : LanguageServerWrapper(project, definition()) {
        private val initialized = CompletableFuture<Void>()
        @Volatile private var active = false
        val opens = CopyOnWriteArrayList<DidOpenTextDocumentParams>()
        val replies = ConcurrentHashMap<String, CompletableFuture<Any?>>()
        private val features = LSPClientFeatures().also { it.setServerWrapper(this) }
        private val text = Proxy.newProxyInstance(TextDocumentService::class.java.classLoader, arrayOf(TextDocumentService::class.java)) { _, method, args ->
            when (method.name) {
                "didOpen" -> { opens += args[0] as DidOpenTextDocumentParams; null }
                "codeAction" -> CompletableFuture<Any?>().also { replies[(args[0] as CodeActionParams).textDocument.uri] = it }
                else -> null
            }
        } as TextDocumentService
        val remote = Proxy.newProxyInstance(LanguageServer::class.java.classLoader, arrayOf(LanguageServer::class.java)) { _, method, _ ->
            if (method.name == "getTextDocumentService") text else null
        } as LanguageServer
        private val options = TextDocumentSyncOptions().apply { openClose = true; change = TextDocumentSyncKind.Incremental }

        init { setField("initializeFuture", initialized); setField("languageServer", remote) }
        fun release() { active = true; initialized.complete(null) }
        override fun start() {}
        override fun getInitializedServer(): CompletableFuture<LanguageServer> = initialized.thenApply { remote }
        override fun getSyncOptions() = options
        override fun getClientFeatures() = features
        override fun isActive() = active
        override fun canDeleteFilesSupported() = true
        override fun dispose() { active = false; stopDispatcher(); features.dispose() }
        private fun setField(name: String, value: Any) {
            LanguageServerWrapper::class.java.getDeclaredField(name).apply { isAccessible = true }.set(this, value)
        }
        companion object {
            private fun definition() = object : LanguageServerDefinition("open-goland-delete-test", "Owned deletion lifecycle regression", "", false, null, false, false) {
                override fun createConnectionProvider(project: Project): StreamConnectionProvider = error("This test owns no process")
            }
        }
    }
}
