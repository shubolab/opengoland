package com.redhat.devtools.lsp4ij

import com.google.gson.JsonParser
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.EdtTestUtil
import com.intellij.testFramework.LightPlatformTestCase
import com.intellij.testFramework.LightVirtualFile
import com.redhat.devtools.lsp4ij.client.features.LSPClientFeatures
import com.redhat.devtools.lsp4ij.server.StreamConnectionProvider
import com.redhat.devtools.lsp4ij.server.definition.LanguageServerDefinition
import dev.openidea.goide.lsp.GoDocumentSync
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.TextDocumentSyncKind
import org.eclipse.lsp4j.TextDocumentSyncOptions
import org.eclipse.lsp4j.services.LanguageServer
import org.eclipse.lsp4j.services.TextDocumentService
import java.lang.reflect.Proxy
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Actual patched upstream wrapper/synchronizer and native document; only the remote service is in memory. */
class GoLspInitializationSyncTest : LightPlatformTestCase() {
    override fun runInDispatchThread() = false

    fun testRuntimeLoadsTheCompletePinnedPrivatePatch() {
        val loader = LanguageServerWrapper::class.java.classLoader
        val marker = requireNotNull(loader.getResourceAsStream("META-INF/open-goland-document-sync.json")) {
            "Tests must load the patched private sandbox plugin rather than the downloaded upstream dependency"
        }.bufferedReader().use { JsonParser.parseReader(it).asJsonObject }
        assertEquals("967ebb91e97f3f9f60e863bd8f10663717110956a1ea4fc45cf479fa27a85604", marker.get("patchSha256").asString)
        val classes = marker.getAsJsonObject("classSha256").entrySet()
        assertEquals("The complete six-source private patch must be present", 6, marker.getAsJsonArray("sources").size())
        assertTrue("Find Usages outer class must be rebuilt", classes.any { it.key == "com/redhat/devtools/lsp4ij/usages/LSPUsageSearcher.class" })
        assertTrue("Find Usages local record must be rebuilt", classes.any { it.key == "com/redhat/devtools/lsp4ij/usages/LSPUsageSearcher\$1ElementData.class" })
        assertTrue("Find Usages policy API must be rebuilt", classes.any { it.key == "com/redhat/devtools/lsp4ij/client/features/LSPUsageFeature.class" })
        assertTrue("Find Usages policy consumer must be rebuilt", classes.any { it.key == "com/redhat/devtools/lsp4ij/usages/LSPUsageSupport.class" })
        assertTrue("Find Usages parameters record must be rebuilt", classes.any { it.key == "com/redhat/devtools/lsp4ij/usages/LSPUsageSupport\$LSPUsageSupportParams.class" })
        assertEquals("Every outer/nested class from the six public sources must be included", 13, classes.size)
        for ((path, digest) in classes) {
            val bytes = requireNotNull(loader.getResourceAsStream(path)).use { it.readAllBytes() }
            val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            assertEquals("The classloader must resolve the complete source patch: $path", digest.asString, actual)
        }
    }

    fun testEditDuringInitializationIsIncludedInTheFirstNativeDocumentSnapshot() = fixture { wrapper, native, document, file ->
        val info = ReadAction.compute<LanguageServerWrapper.LSPFileConnectionInfo, RuntimeException> {
            wrapper.createFileConnectionInfo(file, document, true)
        }
        val connected = wrapper.connect(file, info)
        write { native.insertString(native.textLength, APPENDED) }
        assertFalse("Initialization must still be held", connected.isDone)
        wrapper.initialized.complete(null)
        assertSame(wrapper.remote, connected.get(5, TimeUnit.SECONDS))

        val opened = wrapper.records.single() as DidOpenTextDocumentParams
        assertEquals(1, opened.textDocument.version)
        assertEquals(ORIGINAL + APPENDED, opened.textDocument.text)
        assertTrue("Current text must be captured in a read action", wrapper.snapshotReadAccess.all { it })
        assertEquals(listOf(true), wrapper.listenerReadAccess)

        // After the initial snapshot, real native edits must be delivered exactly once.
        write { native.insertString(native.textLength, "\n// 中文 😀\n") }
        wrapper.flush()
        assertMirrorEquals(wrapper.records, native.text)
    }

    fun testDidOpenAndIncrementalChangesUseTheSameFifoDispatcher() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        fixture(onOpen = {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS)) { "Test didOpen release was not signalled" }
        }) { wrapper, native, document, file ->
            try {
                val info = ReadAction.compute<LanguageServerWrapper.LSPFileConnectionInfo, RuntimeException> {
                    wrapper.createFileConnectionInfo(file, document, true)
                }
                val connected = wrapper.connect(file, info)
                wrapper.initialized.complete(null)
                assertTrue("didOpen dispatch did not start", entered.await(5, TimeUnit.SECONDS))
                write { native.insertString(native.textLength, "// 😀\n") }
                write { native.insertString(native.textLength, APPENDED) }
                assertTrue("didChange overtook the blocked didOpen", wrapper.records.isEmpty())
                release.countDown()
                connected.get(5, TimeUnit.SECONDS)
                wrapper.flush()
                assertEquals(3, wrapper.records.size)
                assertTrue(wrapper.records.first() is DidOpenTextDocumentParams)
                assertMirrorEquals(wrapper.records, native.text)
            } finally { release.countDown() }
        }
    }

    fun testNoWaitConnectionStillMaterializesAnAtomicDidOpenWithoutWaitingForDispatch() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        fixture(onOpen = {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS)) { "Test didOpen release was not signalled" }
        }) { wrapper, native, document, file ->
            try {
                val info = ReadAction.compute<LanguageServerWrapper.LSPFileConnectionInfo, RuntimeException> {
                    wrapper.createFileConnectionInfo(file, document, false)
                }
                val connected = wrapper.connect(file, info)
                write { native.insertString(native.textLength, APPENDED) }
                wrapper.initialized.complete(null)
                assertSame(wrapper.remote, connected.get(5, TimeUnit.SECONDS))
                assertTrue("A no-wait caller must still send didOpen", entered.await(5, TimeUnit.SECONDS))
                assertTrue(wrapper.records.isEmpty())
                release.countDown()
                wrapper.flush()
                assertMirrorEquals(wrapper.records, native.text)
                assertEquals(listOf(true), wrapper.listenerReadAccess)
            } finally { release.countDown() }
        }
    }

    fun testFailedDidOpenDispatchCompletesItsNotificationFuture() = fixture(onOpen = {
        throw IllegalStateException("owned remote failure")
    }) { wrapper, _, document, file ->
        val info = ReadAction.compute<LanguageServerWrapper.LSPFileConnectionInfo, RuntimeException> {
            wrapper.createFileConnectionInfo(file, document, true)
        }
        val connected = wrapper.connect(file, info)
        wrapper.initialized.complete(null)
        runCatching { connected.get(5, TimeUnit.SECONDS) }
            .onFailure { assertFalse("Connection future must not hang", it is java.util.concurrent.TimeoutException) }
        val open = wrapper.openFuture()
        val failure = runCatching { open.get(5, TimeUnit.SECONDS) }.exceptionOrNull()
        assertNotNull(failure)
        assertFalse("Notification future must not hang", failure is java.util.concurrent.TimeoutException)
        assertTrue(open.isCompletedExceptionally)
    }

    private fun assertMirrorEquals(records: List<Any>, expected: String) {
        val opened = records.first() as DidOpenTextDocumentParams
        var text = opened.textDocument.text
        var version = opened.textDocument.version
        records.drop(1).forEach {
            val changed = it as DidChangeTextDocumentParams
            assertEquals(++version, changed.textDocument.version)
            assertNotNull("Native incremental ranges must remain incremental", changed.contentChanges.single().range)
            text = GoDocumentSync.applyChanges(text, changed.contentChanges)
        }
        assertEquals(expected, text)
    }

    private fun fixture(onOpen: (DidOpenTextDocumentParams) -> Unit = {}, block: (HeldWrapper, Document, Document, LightVirtualFile) -> Unit) {
        val native = EditorFactory.getInstance().createDocument(ORIGINAL)
        val wrapper = HeldWrapper(project, onOpen)
        val document = object : Document by native {
            override fun getText(): String {
                wrapper.snapshotReadAccess += ApplicationManager.getApplication().isReadAccessAllowed
                return native.text
            }
            override fun addDocumentListener(listener: DocumentListener, parentDisposable: Disposable) {
                wrapper.listenerReadAccess += ApplicationManager.getApplication().isReadAccessAllowed
                native.addDocumentListener(listener, parentDisposable)
            }
        }
        val file = LightVirtualFile("initialization.go", ORIGINAL)
        try { block(wrapper, native, document, file) }
        finally { Disposer.dispose(wrapper) }
    }

    private fun write(action: () -> Unit) = EdtTestUtil.runInEdtAndWait<RuntimeException> {
        WriteCommandAction.runWriteCommandAction(project, Runnable(action))
    }

    private class HeldWrapper(project: Project, onOpen: (DidOpenTextDocumentParams) -> Unit) : LanguageServerWrapper(project, definition()) {
        val initialized = CompletableFuture<Void>()
        val records = CopyOnWriteArrayList<Any>()
        val snapshotReadAccess = CopyOnWriteArrayList<Boolean>()
        val listenerReadAccess = CopyOnWriteArrayList<Boolean>()
        private val features = LSPClientFeatures().also { it.setServerWrapper(this) }
        private val text = Proxy.newProxyInstance(TextDocumentService::class.java.classLoader, arrayOf(TextDocumentService::class.java)) { _, method, args ->
            when (method.name) {
                "didOpen" -> { val params = args[0] as DidOpenTextDocumentParams; onOpen(params); records += params; null }
                "didChange" -> { records += args[0] as DidChangeTextDocumentParams; null }
                "didClose" -> null
                else -> null
            }
        } as TextDocumentService
        val remote = Proxy.newProxyInstance(LanguageServer::class.java.classLoader, arrayOf(LanguageServer::class.java)) { _, method, _ ->
            if (method.name == "getTextDocumentService") text else null
        } as LanguageServer
        private val options = TextDocumentSyncOptions().apply { openClose = true; change = TextDocumentSyncKind.Incremental }

        init {
            setField("initializeFuture", initialized)
            setField("languageServer", remote)
        }

        // Exercise real connect, sendNotification, synchronizer and dispatcher without starting a process.
        override fun start() {}
        override fun getInitializedServer(): CompletableFuture<LanguageServer> = initialized.thenApply { remote }
        override fun getSyncOptions() = options
        override fun getClientFeatures() = features
        override fun dispose() { stopDispatcher(); features.dispose() }

        fun flush() { sendNotification { it }.get(5, TimeUnit.SECONDS) }
        fun openFuture(): CompletableFuture<LanguageServer> = requireNotNull(openedDocuments.values.single().synchronizer).didOpenFuture
        private fun setField(name: String, value: Any) {
            LanguageServerWrapper::class.java.getDeclaredField(name).apply { isAccessible = true }.set(this, value)
        }

        companion object {
            private fun definition() = object : LanguageServerDefinition("open-goland-sync-test", "Owned native sync regression", "", false, null, false, false) {
                override fun createConnectionProvider(project: Project): StreamConnectionProvider = error("This test owns no process")
            }
        }
    }

    companion object {
        private const val ORIGINAL = "package fixture\n"
        private const val APPENDED = "\nfunc TestDuringInit(t *testing.T) {}\n"
    }
}
