package com.redhat.devtools.lsp4ij

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.progress.ProcessCanceledException
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
import com.redhat.devtools.lsp4ij.features.AbstractLSPDocumentFeatureSupport
import com.redhat.devtools.lsp4ij.features.declaration.LSPDeclarationParams
import com.redhat.devtools.lsp4ij.features.implementation.LSPImplementationParams
import com.redhat.devtools.lsp4ij.features.navigation.LSPDefinitionParams
import com.redhat.devtools.lsp4ij.features.typeDefinition.LSPTypeDefinitionParams
import com.redhat.devtools.lsp4ij.server.definition.LanguageServerDefinition
import com.redhat.devtools.lsp4ij.usages.LSPUsageSupport
import com.redhat.devtools.lsp4ij.usages.LocationData
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.LanguageServer
import org.eclipse.lsp4j.services.TextDocumentService
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Actual public navigation supports/cache and native documents; controlled remote replies only. */
class GoNavigationCacheTest : LightPlatformTestCase() {
    override fun runInDispatchThread() = false

    fun testUnchangedSemanticEpochReusesAllFourNavigationFutures() = fixture { f ->
        for (method in METHODS) {
            val first = f.query(method)
            assertEquals(1, first.get(10, TimeUnit.SECONDS).size)
            val again = f.query(method)
            assertSame("An unchanged workspace must retain caching for $method", first, again)
            assertEquals(1, f.wrapper.requests.count { it == method })
        }
    }

    fun testTargetOnlyUnsavedMovementInvalidatesAllFourNavigationCaches() = fixture { f ->
        METHODS.forEach { f.query(it).get(10, TimeUnit.SECONDS) }
        val sourceStamp = f.sourceDocument.modificationStamp
        f.write(f.targetDocument, "// moved target 中文😀\n" + TARGET)
        assertEquals(sourceStamp, f.sourceDocument.modificationStamp)
        for (method in METHODS) {
            val result = f.query(method).get(10, TimeUnit.SECONDS)
            assertEquals("The old location future must not survive target-only movement: $method", 2, f.wrapper.requests.count { it == method })
            assertEquals(Position(2, 5), result.single().location().range.start)
        }
    }

    fun testThirdFileSemanticsInvalidateWithoutChangingSourceOrPreviousTarget() = fixture { f ->
        METHODS.forEach { f.query(it).get(10, TimeUnit.SECONDS) }
        val sourceStamp = f.sourceDocument.modificationStamp
        val targetStamp = f.targetDocument.modificationStamp
        f.write(f.otherDocument, OTHER + "// choose Other\n")
        assertEquals(sourceStamp, f.sourceDocument.modificationStamp)
        assertEquals(targetStamp, f.targetDocument.modificationStamp)
        for (method in METHODS) {
            val result = f.query(method).get(10, TimeUnit.SECONDS)
            assertEquals("A semantic dependency change must requery $method", 2, f.wrapper.requests.count { it == method })
            assertEquals(Position(2, 5), result.single().location().range.start)
        }
    }

    fun testServerConfigurationEpochInvalidatesWithoutAnyDocumentEdit() = fixture { f ->
        val first = f.query("typeDefinition")
        first.get(10, TimeUnit.SECONDS)
        f.wrapper.incrementModificationCount()
        val changed = f.query("typeDefinition")
        changed.get(10, TimeUnit.SECONDS)
        assertNotSame(first, changed)
        assertEquals(2, f.wrapper.requests.count { it == "typeDefinition" })
        assertSame(changed, f.query("typeDefinition"))
    }

    fun testAChangedDependencyRejectsAnOldInflightReplyAndTheNextGenerationCanBeReused() = fixture { f ->
        val held = f.wrapper.holdReply()
        val old = f.query("typeDefinition")
        f.awaitRequests("typeDefinition", 1)
        assertEquals(1, f.wrapper.requests.count { it == "typeDefinition" })
        f.write(f.otherDocument, OTHER + "// choose Other\n")
        held.complete(Either.forLeft(listOf(f.wrapper.location())))
        val error = runCatching { old.get(10, TimeUnit.SECONDS) }.exceptionOrNull()
        assertNotNull("An epoch-mismatched response must not become a navigation result", error)
        assertTrue(generateSequence(requireNotNull(error) as Throwable?) { it.cause }.any { it is ProcessCanceledException })
        f.wrapper.pending = null
        val fresh = f.query("typeDefinition")
        assertEquals(Position(2, 5), fresh.get(10, TimeUnit.SECONDS).single().location().range.start)
        assertSame(fresh, f.query("typeDefinition"))
        assertEquals(2, f.wrapper.requests.count { it == "typeDefinition" })
    }

    fun testAReplacementGenerationCannotBeOverwrittenByTheOldPendingFuture() = fixture { f ->
        val firstReply = f.wrapper.holdReply()
        val first = f.query("typeDefinition")
        f.awaitRequests("typeDefinition", 1)
        f.write(f.otherDocument, OTHER + "// choose Other\n")
        val nextReply = f.wrapper.holdReply()
        val next = f.query("typeDefinition")
        assertNotSame(first, next)
        nextReply.complete(Either.forLeft(listOf(f.wrapper.location())))
        next.get(10, TimeUnit.SECONDS)
        firstReply.complete(Either.forLeft(emptyList()))
        assertSame(next, f.query("typeDefinition"))
        assertEquals(2, f.wrapper.requests.count { it == "typeDefinition" })
    }

    fun testColdServerStartupDoesNotDiscardItsFirstLegitimateNavigationReply() = fixture { f ->
        f.wrapper.status = ServerStatus.starting
        val first = f.query("typeDefinition")
        assertEquals(1, first.get(10, TimeUnit.SECONDS).size)
        assertEquals(ServerStatus.started, f.wrapper.status)
        // The post-readiness baseline preserves the first answer and its cache;
        // it is not a blanket exemption for later semantic/config changes.
        val stable = f.query("typeDefinition")
        stable.get(10, TimeUnit.SECONDS)
        assertSame("The readiness baseline is taken before RPC, after startup", first, stable)
        assertSame(stable, f.query("typeDefinition"))
    }

    fun testAColdRequestsWatchedDependencyChangeCannotUseAStartupExemption() = fixture { f ->
        val listener = LSPFileListener(f.wrapper)
        listener.registerFileSystemWatchers("owned cold watcher", listOf(FileSystemWatcher().apply {
            setGlobPattern(RelativePattern().apply { setBaseUri(f.targetFile.virtualFile.parent.url); pattern = "*.go" })
            kind = WatchKind.Change
        }))
        f.wrapper.status = ServerStatus.starting
        val held = f.wrapper.holdReply()
        val pending = f.query("typeDefinition")
        f.awaitRequests("typeDefinition", 1)
        listener.onFileContentChangedAfter(f.targetFile.virtualFile, mutableListOf())
        held.complete(Either.forLeft(listOf(f.wrapper.location())))
        val error = runCatching { pending.get(10, TimeUnit.SECONDS) }.exceptionOrNull()
        assertNotNull("A watched dependency change is semantic even in the first request", error)
        assertTrue(generateSequence(requireNotNull(error) as Throwable?) { it.cause }.any { it is ProcessCanceledException })
        f.wrapper.pending = null
        val fresh = f.query("typeDefinition")
        fresh.get(10, TimeUnit.SECONDS)
        assertSame(fresh, f.query("typeDefinition"))
        assertEquals(2, f.wrapper.requests.count { it == "typeDefinition" })
        listener.unregisterFileSystemWatchers("owned cold watcher")
    }

    fun testAColdRequestsConfigurationChangeAfterDispatchRejectsItsOldReply() = fixture { f ->
        f.wrapper.status = ServerStatus.starting
        val held = f.wrapper.holdReply()
        val pending = f.query("typeDefinition")
        f.awaitRequests("typeDefinition", 1)
        f.wrapper.incrementModificationCount()
        held.complete(Either.forLeft(listOf(f.wrapper.location())))
        val error = runCatching { pending.get(10, TimeUnit.SECONDS) }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(generateSequence(requireNotNull(error) as Throwable?) { it.cause }.any { it is ProcessCanceledException })
    }

    fun testMatchedWatchedDependencyInvalidatesButAnUnmatchedTraceFileDoesNot() = fixture { f ->
        val listener = LSPFileListener(f.wrapper)
        listener.registerFileSystemWatchers("owned semantic watcher", listOf(FileSystemWatcher().apply {
            setGlobPattern(RelativePattern().apply { setBaseUri(f.targetFile.virtualFile.parent.url); pattern = "*.go" })
            kind = WatchKind.Change
        }))
        val first = f.query("typeDefinition")
        first.get(10, TimeUnit.SECONDS)
        val ignored = mutableListOf<org.eclipse.lsp4j.FileEvent>()
        listener.onFileContentChangedAfter(f.traceFile, ignored)
        assertTrue(ignored.isEmpty())
        assertSame("Transport trace writes are not claimed semantic dependencies", first, f.query("typeDefinition"))
        val matched = mutableListOf<org.eclipse.lsp4j.FileEvent>()
        listener.onFileContentChangedAfter(f.targetFile.virtualFile, matched)
        assertEquals(1, matched.size)
        val fresh = f.query("typeDefinition")
        assertNotSame(first, fresh)
        fresh.get(10, TimeUnit.SECONDS)
        assertEquals(2, f.wrapper.requests.count { it == "typeDefinition" })
        listener.unregisterFileSystemWatchers("owned semantic watcher")
    }

    fun testNonNavigationFeatureKeepsItsOriginalCachePolicy() = fixture { f ->
        val support = LSPUsageSupport(f.source)
        val params = LSPUsageSupport.LSPUsageSupportParams(Position(1, 5))
        val original = requireNotNull(read { support.getFeatureData(params) })
        original.get(10, TimeUnit.SECONDS)
        val requests = f.wrapper.requests.toList()
        f.write(f.otherDocument, OTHER + "// semantic edit\n")
        assertSame("The patch is opt-in for semantic location navigation, not all LSP caches", original,
            read { support.getFeatureData(params) })
        assertEquals(requests, f.wrapper.requests.toList())
    }

    private inner class Fixture(val source: PsiFile, val targetFile: PsiFile, val traceFile: VirtualFile,
                                val sourceDocument: Document, val targetDocument: Document, val otherDocument: Document,
                                val wrapper: OwnedWrapper) {
        private val supports = LSPFileSupport.getSupport(source)
        fun query(method: String): CompletableFuture<List<LocationData>> = read {
            val id = TextDocumentIdentifier(); val position = Position(1, 5); val offset = SOURCE.indexOf("Current")
            requireNotNull(when (method) {
                "typeDefinition" -> supports.typeDefinitionSupport.getTypeDefinitions(LSPTypeDefinitionParams(id, position, offset))
                "implementation" -> supports.implementationSupport.getImplementations(LSPImplementationParams(id, position, offset))
                "definition" -> supports.definitionSupport.getDefinitions(LSPDefinitionParams(id, position, offset))
                else -> supports.declarationSupport.getDeclarations(LSPDeclarationParams(id, position, offset))
            })
        }
        fun write(document: Document, text: String) = edt {
            WriteCommandAction.runWriteCommandAction(project) { document.setText(text) }
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }
        fun awaitRequests(method: String, count: Int) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (wrapper.requests.count { it == method } < count) {
                val remaining = deadline - System.nanoTime()
                check(remaining > 0) { "The real support did not dispatch $method" }
                wrapper.requestSignals.poll(remaining, TimeUnit.NANOSECONDS)
            }
        }
    }

    private fun fixture(block: (Fixture) -> Unit) {
        val root = Files.createTempDirectory("open-goland-navigation-cache-").toRealPath()
        val disposable = Disposer.newDisposable("owned navigation epoch")
        VfsRootAccess.allowRootAccess(disposable, root.toString())
        val accessor = LanguageServiceAccessor.getInstance(project)
        @Suppress("UNCHECKED_CAST")
        val started = LanguageServiceAccessor::class.java.getDeclaredField("startedServers").apply { isAccessible = true }
            .get(accessor) as MutableSet<LanguageServerWrapper>
        val previous = synchronized(started) { started.toList() }
        val definition = requireNotNull(LanguageServersRegistry.getInstance().getServerDefinition("openidea.gopls"))
        val wrapper = OwnedWrapper(project, definition)
        try {
            val files = listOf("source.go" to SOURCE, "target.go" to TARGET, "other.go" to OTHER).map { (name, text) ->
                val path = root.resolve(name); Files.writeString(path, text)
                val virtual = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path))
                read { requireNotNull(PsiManager.getInstance(project).findFile(virtual)) }
            }
            val documents = read { files.map { requireNotNull(PsiDocumentManager.getInstance(project).getDocument(it)) } }
            val tracePath = root.resolve("trace.jsonl"); Files.writeString(tracePath, "")
            val trace = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(tracePath))
            wrapper.targetDocument = documents[1]; wrapper.otherDocument = documents[2]; wrapper.targetURI = files[1].virtualFile.url
            synchronized(started) { started.clear(); started.add(wrapper) }
            block(Fixture(files[0], files[1], trace, documents[0], documents[1], documents[2], wrapper))
        } finally {
            synchronized(started) { started.clear(); started.addAll(previous) }
            Disposer.dispose(wrapper)
            Disposer.dispose(disposable)
            root.toFile().deleteRecursively()
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root)
        }
    }

    private class OwnedWrapper(project: Project, definition: LanguageServerDefinition) : LanguageServerWrapper(project, definition) {
        lateinit var targetDocument: Document
        lateinit var otherDocument: Document
        lateinit var targetURI: String
        var status = ServerStatus.started
        var pending: CompletableFuture<Either<List<Location>, List<LocationLink>>>? = null
        val requests = CopyOnWriteArrayList<String>()
        val requestSignals = LinkedBlockingQueue<String>()
        private val features = LSPClientFeatures().also { it.setServerWrapper(this) }
        private val capabilities = ServerCapabilities().apply {
            setDeclarationProvider(true); setDefinitionProvider(true); setTypeDefinitionProvider(true)
            setImplementationProvider(true); setReferencesProvider(true)
        }
        private val text = Proxy.newProxyInstance(TextDocumentService::class.java.classLoader, arrayOf(TextDocumentService::class.java)) { _, method, _ ->
            when (method.name) {
                "declaration", "definition", "typeDefinition", "implementation" -> {
                    requests += method.name
                    requestSignals.offer(method.name)
                    if (method.name == "typeDefinition" && pending != null) pending
                    else CompletableFuture.completedFuture(Either.forLeft<List<Location>, List<LocationLink>>(listOf(location())))
                }
                "references" -> { requests += method.name; CompletableFuture.completedFuture(listOf(location())) }
                "didOpen", "didClose", "didChange" -> null
                else -> null
            }
        } as TextDocumentService
        private val remote = Proxy.newProxyInstance(LanguageServer::class.java.classLoader, arrayOf(LanguageServer::class.java)) { _, method, _ ->
            if (method.name == "getTextDocumentService") text else null
        } as LanguageServer
        init {
            features.setServerCapabilities(capabilities)
            setField("serverCapabilities", capabilities); setField("initializeFuture", CompletableFuture.completedFuture<Void>(null))
            setField("languageServer", remote)
        }
        fun location(): Location {
            val target = if (otherDocument.text.contains("choose Other")) "Other" else "Target"
            val text = targetDocument.text; val offset = text.indexOf(target)
            return Location(targetURI, Range(position(text, offset), position(text, offset + target.length)))
        }
        fun holdReply() = CompletableFuture<Either<List<Location>, List<LocationLink>>>().also { pending = it }
        override fun start() {}
        override fun getInitializedServer(): CompletableFuture<LanguageServer> {
            if (status == ServerStatus.starting) {
                status = ServerStatus.started; incrementModificationCount()
                LanguageServiceAccessor.getInstance(project).incrementModificationCount()
            }
            return CompletableFuture.completedFuture(remote)
        }
        override fun getClientFeatures() = features
        override fun getServerStatus() = status
        override fun isEnabled() = true
        override fun canOperate(file: VirtualFile) = true
        override fun getSyncOptions() = TextDocumentSyncOptions().apply { openClose = false; change = TextDocumentSyncKind.None }
        override fun dispose() {
            openedDocuments.values.forEach { it.synchronizer?.let(Disposer::dispose) }
            openedDocuments.clear(); stopDispatcher(); features.dispose()
        }
        private fun setField(name: String, value: Any) {
            LanguageServerWrapper::class.java.getDeclaredField(name).apply { isAccessible = true }.set(this, value)
        }
    }

    private fun <T> read(action: () -> T): T = ReadAction.compute<T, RuntimeException> { action() }
    private fun edt(action: () -> Unit) = EdtTestUtil.runInEdtAndWait<RuntimeException> { WriteIntentReadAction.run(Runnable(action)) }
    companion object {
        private val METHODS = listOf("declaration", "definition", "typeDefinition", "implementation")
        private const val SOURCE = "package fixture\nvar Current Target\n"
        private const val TARGET = "package fixture\ntype Target struct{}\ntype Other struct{}\n"
        private const val OTHER = "package fixture\nvar Semantics = 1\n"
        private fun position(text: String, offset: Int): Position {
            val prefix = text.substring(0, offset)
            return Position(prefix.count { it == '\n' }, prefix.substringAfterLast('\n').length)
        }
    }
}
