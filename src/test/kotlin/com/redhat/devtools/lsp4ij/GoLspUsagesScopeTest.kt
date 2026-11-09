package com.redhat.devtools.lsp4ij

import com.intellij.find.findUsages.FindUsagesOptions
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.SearchScope
import com.intellij.testFramework.LightPlatformTestCase
import com.intellij.usages.UsageInfo2UsageAdapter
import com.intellij.util.Processor
import com.redhat.devtools.lsp4ij.client.features.LSPClientFeatures
import com.redhat.devtools.lsp4ij.server.definition.LanguageServerDefinition
import com.redhat.devtools.lsp4ij.usages.LocationData
import com.redhat.devtools.lsp4ij.usages.LSPFindUsagesHandlerFactory
import com.redhat.devtools.lsp4ij.usages.LSPUsageSearcher
import com.redhat.devtools.lsp4ij.usages.LSPUsageTriggeredPsiElement
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.ServerCapabilities
import org.eclipse.lsp4j.TextDocumentSyncKind
import org.eclipse.lsp4j.TextDocumentSyncOptions
import org.eclipse.lsp4j.services.LanguageServer
import org.eclipse.lsp4j.services.TextDocumentService
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger

/** Actual upstream searcher/accessor/PSI/VFS/scopes; only remote locations are deterministic. */
class GoLspUsagesScopeTest : LightPlatformTestCase() {
    override fun runInDispatchThread() = false

    fun testFreshGlobalScopeExcludesAnotherPhysicalFile() = fixture { f ->
        f.locations = listOf(f.location(0, 20, 24), f.location(1, 40, 44))
        assertEquals(listOf(Hit("first.go", 20, 24)), f.search(read { GlobalSearchScope.fileScope(f.files[0]) }, cached = false))
        assertEquals("Fresh search must really ask the remote reference service", 1, f.wrapper.referenceRequests.get())
    }

    fun testCachedGlobalScopeExcludesAnotherPhysicalFileWithoutAnotherRequest() = fixture { f ->
        f.locations = listOf(f.location(0, 20, 24), f.location(1, 40, 44))
        assertEquals(listOf(Hit("first.go", 20, 24)), f.search(read { GlobalSearchScope.fileScope(f.files[0]) }, cached = true))
        assertEquals(0, f.wrapper.referenceRequests.get())
    }

    fun testFreshDeduplicationNeverComparesRangesFromDifferentFiles() = fixture { f ->
        f.locations = listOf(f.location(0, 20, 45), f.location(1, 24, 28))
        assertEquals(listOf(Hit("first.go", 20, 45), Hit("second.go", 24, 28)), f.search(f.allFilesScope(), cached = false))
    }

    fun testLocalScopeUsesTheSelectedPsiRangeRatherThanTheWholeFile() = fixture { f ->
        val name = SOURCE.indexOf("AlphaOne")
        val selected = read { requireNotNull(f.files[0].findElementAt(name)) }
        val scope = read { LocalSearchScope(selected) }
        f.locations = listOf(f.location(0, name, name + 8), f.location(0, SOURCE.indexOf("BetaTwo"), SOURCE.indexOf("BetaTwo") + 7),
            f.location(1, name, name + 8))
        for (cached in listOf(false, true)) assertEquals("Local scope must retain only its complete contained range (cached=$cached)",
            listOf(Hit("first.go", name, name + 8)), f.search(scope, cached))
    }

    fun testSameFileContainmentAndPartialOverlapsAreIndependentOfInputOrder() = fixture { f ->
        val locations = listOf(f.location(0, 20, 44), f.location(0, 25, 29), f.location(0, 35, 50), f.location(1, 20, 44))
        val expected = listOf(Hit("first.go", 25, 29), Hit("first.go", 35, 50), Hit("second.go", 20, 44))
        for (order in listOf(locations, locations.reversed(), locations.drop(1) + locations.first())) {
            f.locations = order
            for (cached in listOf(false, true)) assertEquals("Only a genuine same-file containing range is redundant (cached=$cached)",
                expected, f.search(f.allFilesScope(), cached))
        }
    }

    fun testEmptyGlobalAndLocalScopesNeverEmitUsagesInEitherPath() = fixture { f ->
        f.locations = listOf(f.location(0, 20, 24), f.location(1, 40, 44))
        for (scope in listOf(GlobalSearchScope.EMPTY_SCOPE, LocalSearchScope.EMPTY)) {
            for (cached in listOf(false, true)) assertEquals(emptyList<Hit>(), f.search(scope, cached))
        }
    }

    private data class Hit(val file: String, val start: Int, val end: Int)
    private inner class Fixture(val files: List<PsiFile>, val wrapper: OwnedWrapper) {
        var locations: List<Location>
            get() = wrapper.locations
            set(value) { wrapper.locations = value }
        fun location(file: Int, start: Int, end: Int) = Location(files[file].virtualFile.url,
            Range(position(start), position(end)))
        fun allFilesScope(): GlobalSearchScope = read {
            GlobalSearchScope.filesScope(project, files.map { it.virtualFile }).also { scope ->
                check(files.all { scope.contains(it.virtualFile) })
            }
        }
        fun search(scope: SearchScope, cached: Boolean): List<Hit> {
            val target = read { LSPUsageTriggeredPsiElement(files[0], TextRange(SOURCE.indexOf("AlphaOne"), SOURCE.indexOf("AlphaOne") + 8)) }
            if (cached) target.lspReferences = locations.map { LocationData(it, LanguageServerItem(wrapper.remote, wrapper)) }
            val hits = mutableListOf<Hit>()
            LSPUsageSearcher().processElementUsages(target, Processor { usage ->
                val element = requireNotNull((usage as UsageInfo2UsageAdapter).usageInfo.element)
                val range = element.textRange
                hits += Hit(element.containingFile.name, range.startOffset, range.endOffset)
                true
            }, FindUsagesOptions(scope))
            return hits.sortedWith(compareBy(Hit::file, Hit::start, Hit::end))
        }
    }

    private fun fixture(block: (Fixture) -> Unit) {
        val root = Files.createTempDirectory("open-goland-usage-scope-").toRealPath()
        val disposable = Disposer.newDisposable("owned usage scope roots")
        VfsRootAccess.allowRootAccess(disposable, root.toString())
        val accessor = LanguageServiceAccessor.getInstance(project)
        @Suppress("UNCHECKED_CAST")
        val started = LanguageServiceAccessor::class.java.getDeclaredField("startedServers").apply { isAccessible = true }
            .get(accessor) as MutableSet<LanguageServerWrapper>
        val previous = synchronized(started) { started.toList() }
        val definition = requireNotNull(LanguageServersRegistry.getInstance().getServerDefinition("openidea.gopls"))
        val wrapper = OwnedWrapper(project, definition)
        try {
            val files = listOf("first.go", "second.go").map { name ->
                val path = root.resolve(name)
                Files.writeString(path, SOURCE)
                val virtual = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path))
                read {
                    requireNotNull(PsiManager.getInstance(project).findFile(virtual)).also {
                        requireNotNull(PsiDocumentManager.getInstance(project).getDocument(it))
                    }
                }
            }
            synchronized(started) { started.clear(); started.add(wrapper) }
            assertTrue("The actual accessor must recognize this started reference-capable server",
                read { LSPFindUsagesHandlerFactory.isUsageSupportedByLanguageServer(files[0]) })
            block(Fixture(files, wrapper))
        } finally {
            // Do not stop any pre-existing service or process: restore its exact set.
            synchronized(started) { started.clear(); started.addAll(previous) }
            Disposer.dispose(wrapper)
            root.toFile().deleteRecursively()
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root)
            Disposer.dispose(disposable)
        }
    }

    private class OwnedWrapper(project: Project, definition: LanguageServerDefinition) : LanguageServerWrapper(project, definition) {
        var locations = emptyList<Location>()
        val referenceRequests = AtomicInteger()
        private val features = LSPClientFeatures().also { it.setServerWrapper(this) }
        private val capabilities = ServerCapabilities().apply { setReferencesProvider(true) }
        private val text = Proxy.newProxyInstance(TextDocumentService::class.java.classLoader, arrayOf(TextDocumentService::class.java)) { _, method, _ ->
            when (method.name) {
                "references" -> { referenceRequests.incrementAndGet(); CompletableFuture.completedFuture(locations) }
                "didOpen", "didClose", "didChange" -> null
                else -> null
            }
        } as TextDocumentService
        val remote = Proxy.newProxyInstance(LanguageServer::class.java.classLoader, arrayOf(LanguageServer::class.java)) { _, method, _ ->
            if (method.name == "getTextDocumentService") text else null
        } as LanguageServer
        init {
            features.setServerCapabilities(capabilities)
            setField("serverCapabilities", capabilities)
            setField("initializeFuture", CompletableFuture.completedFuture<Void>(null))
            setField("languageServer", remote)
        }
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
    companion object {
        private const val SOURCE = "package fixture\nvar AlphaOne, BetaTwo, GammaThree, DeltaFour, EpsilonFive int\n"
        private fun position(offset: Int): Position {
            val prefix = SOURCE.substring(0, offset)
            return Position(prefix.count { it == '\n' }, prefix.substringAfterLast('\n').length)
        }
    }
}
