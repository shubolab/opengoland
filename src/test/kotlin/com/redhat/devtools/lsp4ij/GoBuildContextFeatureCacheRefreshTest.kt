package com.redhat.devtools.lsp4ij

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.application.WriteIntentReadAction
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
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.util.xmlb.XmlSerializerUtil
import com.redhat.devtools.lsp4ij.client.features.LSPClientFeatures
import com.redhat.devtools.lsp4ij.server.definition.LanguageServerDefinition
import dev.openidea.goide.lsp.GoLanguageServerFactory
import dev.openidea.goide.run.GoRunBuildContextService
import dev.openidea.goide.run.GoRunBuildStatus
import dev.openidea.goide.settings.GoProjectSettings
import dev.openidea.goide.settings.GoToolchain
import org.eclipse.lsp4j.ClientCapabilities
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionContext
import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.Command
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SemanticTokens
import org.eclipse.lsp4j.SemanticTokensLegend
import org.eclipse.lsp4j.SemanticTokensParams
import org.eclipse.lsp4j.SemanticTokensWithRegistrationOptions
import org.eclipse.lsp4j.ServerCapabilities
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentSyncKind
import org.eclipse.lsp4j.TextDocumentSyncOptions
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.LanguageServer
import org.eclipse.lsp4j.services.TextDocumentService
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Actual LSPFileSupport semantic/intention caches and actual selected-SDK metadata.
 * No test calls cancel(), replaces a support, or invokes the new refresh helper.
 * Only the protocol peer is controlled. Re-querying the same support proves its
 * cache was invalidated; this is not a native UI/daemon automatically issuing RPC proof.
 */
class GoBuildContextFeatureCacheRefreshTest : LightPlatformTestCase() {
    override fun runInDispatchThread() = false

    fun testFirstCheckingNullAndEmptyCachesWakeOnActualMetadataCompletionWithoutReplacingSupports() = fixture { f ->
        lateinit var cache: CachedFeatures
        lateinit var first: Replies
        val stamp = read { f.host.modificationStamp }
        write {
            assertEquals(GoRunBuildStatus.CHECKING, GoRunBuildContextService.get(project).lookup(f.host).status)
            cache = CachedFeatures(f.host)
            first = cache.query()
            assertEmpty(first)
            val again = cache.query()
            assertSame("Semantic null is a real cached SDK future", first.semantic, again.semantic)
            assertSame("Intention empty list is a real cached SDK future", first.actions, again.actions)
            assertEquals(0, f.wrapper.rpcCount.get())
        }
        f.await(f.host, GoRunBuildStatus.ACTIVE)
        val ready = awaitResult(cache, positive = true)
        assertNotSame(first.semantic, ready.semantic)
        assertNotSame(first.actions, ready.actions)
        assertEquals("No edit may accidentally invalidate the cache for this test", stamp, read { f.host.modificationStamp })
        assertTrue(f.wrapper.semanticCount.get() > 0)
        assertTrue(f.wrapper.actionCount.get() > 0)
        cache.assertSameSupports()
    }

    fun testTagsMembershipSwapRefreshesTheSameCachedSupportsWithoutAnEditorEdit() = fixture { f ->
        f.tags("native_extra")
        f.wrapper.initializeFeatures()
        f.await(f.extra, GoRunBuildStatus.ACTIVE)
        val cache = CachedFeatures(f.extra)
        val initial = awaitResult(cache, positive = true)
        val stamp = read { f.extra.modificationStamp }
        f.tags("")
        f.wrapper.initializeFeatures()
        f.await(f.extra, GoRunBuildStatus.IGNORED)
        val excluded = awaitResult(cache, positive = false)
        assertNotSame(initial.semantic, excluded.semantic)
        assertNotSame(initial.actions, excluded.actions)
        val issued = f.wrapper.rpcCount.get()
        assertEmpty(cache.query())
        assertEquals("An excluded cached support cannot send another RPC", issued, f.wrapper.rpcCount.get())

        f.tags("native_extra")
        f.wrapper.initializeFeatures()
        f.await(f.extra, GoRunBuildStatus.ACTIVE)
        val restored = awaitResult(cache, positive = true)
        assertNotSame(excluded.semantic, restored.semantic)
        assertNotSame(excluded.actions, restored.actions)
        assertEquals(stamp, read { f.extra.modificationStamp })
        cache.assertSameSupports()
    }

    fun testCurrentServerStartedEventWakesEmptyCachesLeftByAnOldInitializeEpoch() = fixture { f ->
        // Establish the first metadata generation's completed refresh before
        // changing settings, so it cannot later clear the epoch-control cache.
        val host = f.host
        lateinit var cache: CachedFeatures
        lateinit var firstChecking: Replies
        write {
            assertEquals(GoRunBuildStatus.CHECKING, GoRunBuildContextService.get(project).lookup(host).status)
            cache = CachedFeatures(host)
            firstChecking = cache.query()
            assertEmpty(firstChecking)
        }
        f.await(f.host, GoRunBuildStatus.ACTIVE)
        awaitReleased(cache, firstChecking)
        val initial = awaitResult(cache, positive = true)
        edt {
            GoProjectSettings.get(project).environment += "\nOWNED_CACHE_EPOCH=changed"
            GoRunBuildContextService.settingsChanged(project)
        }
        // Observe the actual invalidation refresh before starting a new load;
        // its delayed cancellation must not be mistaken for metadata completion.
        awaitReleased(cache, initial)
        lateinit var checking: Replies
        write {
            assertEquals(GoRunBuildStatus.CHECKING, GoRunBuildContextService.get(project).lookup(f.host).status)
            checking = cache.query()
            assertEmpty(checking)
        }
        f.await(f.host, GoRunBuildStatus.ACTIVE)
        // ACTIVE is published before finish() queues refresh. Wait for the real
        // completion refresh to clear these exact CHECKING futures, without
        // manually cancelling or querying a replacement support.
        awaitReleased(cache, checking)
        val empty = awaitResult(cache, positive = false)
        val issued = f.wrapper.rpcCount.get()
        f.wrapper.initializeFeatures()
        // Merely changing the initialize snapshot must not be mistaken for a
        // support-cache invalidation. The actual started lifecycle callback is next.
        val beforeStarted = cache.query()
        assertSame(empty.semantic, beforeStarted.semantic)
        assertSame(empty.actions, beforeStarted.actions)
        assertEmpty(beforeStarted)
        assertEquals(issued, f.wrapper.rpcCount.get())
        f.wrapper.features.handleServerStatusChanged(ServerStatus.started)
        val ready = awaitResult(cache, positive = true)
        assertNotSame(empty.semantic, ready.semantic)
        assertNotSame(empty.actions, ready.actions)
        cache.assertSameSupports()
    }

    fun testPhysicalGoModAndGoWorkKeepTheirOwnSdkFeaturesAcrossGoSettingsEpochChanges() = fixture { f ->
        val mod = f.file("go.mod")
        val work = f.file("go.work")
        assertEquals("OpenIDEAGoMod", read { mod.language.id })
        assertEquals("OpenIDEAGoWork", read { work.language.id })
        val beforeService = project.getServiceIfCreated(GoRunBuildContextService::class.java)
        val modCache = CachedFeatures(mod)
        val workCache = CachedFeatures(work)
        read {
            assertTrue(f.wrapper.features.semanticTokensFeature.isSemanticTokensSupported(mod))
            assertTrue(f.wrapper.features.codeActionFeature.isCodeActionSupported(work))
        }
        edt {
            GoProjectSettings.get(project).environment += "\nOWNED_MANIFEST_EPOCH=changed"
            GoProjectSettings.get(project).buildTags = "native_extra"
        }
        // The server deliberately keeps its original initialization epoch here.
        // Go source eligibility must not disable the distinct manifest languages.
        read {
            for (file in listOf(mod, work)) {
                assertTrue(f.wrapper.features.semanticTokensFeature.isSemanticTokensSupported(file))
                assertTrue(f.wrapper.features.codeActionFeature.isCodeActionSupported(file))
            }
        }
        awaitResult(modCache, positive = true)
        awaitResult(workCache, positive = true)
        assertSame("Manifest feature checks must not create a build-context service", beforeService,
            project.getServiceIfCreated(GoRunBuildContextService::class.java))
    }

    private data class Replies(val semantic: CompletableFuture<*>, val actions: CompletableFuture<*>)

    private inner class CachedFeatures(val file: PsiFile) {
        private val support = read { LSPFileSupport.getSupport(file) }
        private val semantic = read { support.semanticTokensSupport }
        private val actions = read { support.intentionCodeActionSupport }
        private val tokens = SemanticTokensParams(TextDocumentIdentifier(file.virtualFile.url))
        private val codes = CodeActionParams(TextDocumentIdentifier(file.virtualFile.url),
            Range(Position(0, 0), Position(0, 0)), CodeActionContext(emptyList()))

        fun query(): Replies = read {
            assertSame(semantic, support.semanticTokensSupport)
            assertSame(actions, support.intentionCodeActionSupport)
            Replies(semantic.getSemanticTokens(tokens), actions.getCodeActions(codes))
        }
        fun assertSameSupports() = read {
            assertSame(support, LSPFileSupport.getSupport(file))
            assertSame(semantic, support.semanticTokensSupport)
            assertSame(actions, support.intentionCodeActionSupport)
        }
        fun hasReleased(replies: Replies): Boolean = read {
            semantic.future !== replies.semantic && actions.future !== replies.actions
        }
    }

    private fun awaitReleased(cache: CachedFeatures, replies: Replies) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
        while (!cache.hasReleased(replies)) {
            flushUi()
            check(System.nanoTime() < deadline) { "Actual refresh did not release both existing support futures" }
            Thread.sleep(10)
        }
    }

    private fun assertEmpty(replies: Replies) {
        assertNull(replies.semantic.get(10, TimeUnit.SECONDS))
        assertTrue((replies.actions.get(10, TimeUnit.SECONDS) as List<*>).isEmpty())
    }

    private fun awaitResult(cache: CachedFeatures, positive: Boolean): Replies {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
        while (true) {
            flushUi()
            val result = cache.query()
            try {
                val semantic = result.semantic.get(10, TimeUnit.SECONDS)
                val actions = result.actions.get(10, TimeUnit.SECONDS) as List<*>
                if ((semantic != null && actions.isNotEmpty()) == positive &&
                    (positive || semantic == null && actions.isEmpty())) return result
            } catch (failure: Throwable) {
                var cause = failure
                while ((cause is ExecutionException || cause is CompletionException) && cause.cause != null) cause = cause.cause!!
                if (cause !is CancellationException && cause !is com.intellij.openapi.progress.ProcessCanceledException) throw failure
            }
            check(System.nanoTime() < deadline) { "Same SDK feature caches did not reach positive=$positive" }
            Thread.sleep(10)
        }
    }

    private inner class Fixture(val root: Path, val wrapper: OwnedWrapper) {
        val host get() = file("host.go")
        val extra get() = file("extra_test.go")
        private val files = mutableMapOf<String, PsiFile>()
        fun file(name: String): PsiFile = files.getOrPut(name) {
            val virtual = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root.resolve(name)))
            read { requireNotNull(PsiManager.getInstance(project).findFile(virtual)).also {
                requireNotNull(PsiDocumentManager.getInstance(project).getDocument(it))
            } }
        }
        fun tags(value: String) = edt {
            GoProjectSettings.get(project).buildTags = value
            GoRunBuildContextService.settingsChanged(project)
        }
        fun await(file: PsiFile, expected: GoRunBuildStatus) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
            while (true) {
                flushUi()
                val view = read { GoRunBuildContextService.get(project).lookup(file) }
                if (view.status != GoRunBuildStatus.CHECKING) {
                    assertEquals("${file.name}: ${view.message}", expected, view.status)
                    return
                }
                check(System.nanoTime() < deadline) { "Actual selected SDK metadata did not complete" }
                Thread.sleep(10)
            }
        }
        fun disposeSupports() {
            val existing = read {
                files.values.filter { it.isValid && LSPFileSupport.hasSupport(it) }
                    .map { LSPFileSupport.getSupport(it) }
            }
            existing.forEach(Disposer::dispose)
        }
    }

    private fun fixture(action: (Fixture) -> Unit) {
        val root = Files.createTempDirectory("Go cached features 中文😀 ").toRealPath()
        val lifetime = Disposer.newDisposable("owned Go feature cache test")
        VfsRootAccess.allowRootAccess(lifetime, root.toString())
        val settings = GoProjectSettings.get(project)
        val previousSettings = GoProjectSettings.Data().also { XmlSerializerUtil.copyBean(settings, it) }
        val accessor = LanguageServiceAccessor.getInstance(project)
        @Suppress("UNCHECKED_CAST")
        val started = LanguageServiceAccessor::class.java.getDeclaredField("startedServers").apply { isAccessible = true }
            .get(accessor) as MutableSet<LanguageServerWrapper>
        val previous = synchronized(started) { started.toList() }
        val definition = requireNotNull(LanguageServersRegistry.getInstance().getServerDefinition("openidea.gopls"))
        val wrapper = OwnedWrapper(project, definition)
        var fixture: Fixture? = null
        try {
            val selected = requireNotNull(System.getProperty("opengoland.testGo")?.takeIf(String::isNotBlank)
                ?: System.getenv("OPENGOLAND_GO_TEST")?.takeIf(String::isNotBlank)) { "Set OPENGOLAND_GO_TEST to the actual runtime SDK" }
            settings.apply {
                goExecutable = Path.of(requireNotNull(GoToolchain.resolve("go", selected))).toRealPath().toString()
                buildTags = ""; goos = ""; goarch = ""; cgoEnabled = "0"
                environment = "GOENV=off\nGOFLAGS=\nGOTOOLCHAIN=local\nGOWORK=off\nGOPROXY=off\nGOSUMDB=off\nGOTELEMETRY=off\nGOMAXPROCS=2"
            }
            GoRunBuildContextService.settingsChanged(project)
            Files.writeString(root.resolve("go.mod"), "module example.org/cachedfeatures\n\ngo 1.24.0\n")
            Files.writeString(root.resolve("go.work"), "go 1.24.0\n\nuse .\n")
            Files.writeString(root.resolve("host.go"), "package fixture\nconst Live = 1\n")
            Files.writeString(root.resolve("extra_test.go"), "//go:build native_extra\n\npackage fixture\nimport \"testing\"\nfunc TestExtra(t *testing.T) {}\n")
            wrapper.initializeFeatures()
            synchronized(started) { started.clear(); started.add(wrapper) }
            val value = Fixture(root, wrapper)
            fixture = value
            action(value)
        } finally {
            XmlSerializerUtil.copyBean(previousSettings, settings)
            GoRunBuildContextService.settingsChanged(project)
            fixture?.disposeSupports()
            synchronized(started) { started.clear(); started.addAll(previous) }
            Disposer.dispose(wrapper)
            Disposer.dispose(lifetime)
            root.toFile().deleteRecursively()
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root)
        }
    }

    private class OwnedWrapper(project: Project, definition: LanguageServerDefinition) : LanguageServerWrapper(project, definition) {
        val features: LSPClientFeatures = GoLanguageServerFactory().createClientFeatures()
        val rpcCount = AtomicInteger()
        val semanticCount = AtomicInteger()
        val actionCount = AtomicInteger()
        private val capabilities = ServerCapabilities().apply {
            codeActionProvider = Either.forLeft(true)
            semanticTokensProvider = SemanticTokensWithRegistrationOptions().apply {
                legend = SemanticTokensLegend(listOf("variable"), emptyList()); full = Either.forLeft(true)
            }
        }
        private val text = Proxy.newProxyInstance(TextDocumentService::class.java.classLoader, arrayOf(TextDocumentService::class.java)) { _, method, _ ->
            when (method.name) {
                "semanticTokensFull" -> {
                    rpcCount.incrementAndGet(); semanticCount.incrementAndGet()
                    CompletableFuture.completedFuture(SemanticTokens(listOf(0, 0, 1, 0, 0)))
                }
                "codeAction" -> {
                    rpcCount.incrementAndGet(); actionCount.incrementAndGet()
                    CompletableFuture.completedFuture(listOf(Either.forRight<Command, CodeAction>(CodeAction("owned cache action"))))
                }
                else -> null
            }
        } as TextDocumentService
        private val remote = Proxy.newProxyInstance(LanguageServer::class.java.classLoader, arrayOf(LanguageServer::class.java)) { _, method, _ ->
            if (method.name == "getTextDocumentService") text else null
        } as LanguageServer
        init {
            features.setServerWrapper(this); features.setServerCapabilities(capabilities)
            setField("serverCapabilities", capabilities); setField("languageServer", remote)
            setField("initializeFuture", CompletableFuture.completedFuture<Void>(null))
        }
        fun initializeFeatures() = features.initializeParams(InitializeParams().apply { capabilities = ClientCapabilities() })
        override fun start() {}
        override fun getInitializedServer(): CompletableFuture<LanguageServer> = CompletableFuture.completedFuture(remote)
        override fun getClientFeatures() = features
        override fun getServerStatus() = ServerStatus.started
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
    private fun write(action: () -> Unit) = edt { WriteAction.run<RuntimeException> { action() } }
    private fun flushUi() = EdtTestUtil.runInEdtAndWait<RuntimeException> { PlatformTestUtil.dispatchAllEventsInIdeEventQueue() }
}
