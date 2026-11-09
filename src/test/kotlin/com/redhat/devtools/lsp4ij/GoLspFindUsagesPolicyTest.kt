package com.redhat.devtools.lsp4ij

import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.application.ReadAction
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
import com.redhat.devtools.lsp4ij.client.features.LSPClientFeatures
import com.redhat.devtools.lsp4ij.features.implementation.LSPImplementationParams
import com.redhat.devtools.lsp4ij.features.implementation.LSPImplementationSupport
import com.redhat.devtools.lsp4ij.features.typeDefinition.LSPTypeDefinitionParams
import com.redhat.devtools.lsp4ij.features.typeDefinition.LSPTypeDefinitionSupport
import com.redhat.devtools.lsp4ij.features.typeHierarchy.LSPPrepareTypeHierarchySupport
import com.redhat.devtools.lsp4ij.features.typeHierarchy.LSPTypeHierarchyPrepareParams
import com.redhat.devtools.lsp4ij.server.definition.LanguageServerDefinition
import com.redhat.devtools.lsp4ij.usages.LSPUsagePsiElement
import com.redhat.devtools.lsp4ij.usages.LSPUsageSupport
import dev.openidea.goide.lsp.GoLanguageServerFactory
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.eclipse.lsp4j.services.LanguageServer
import org.eclipse.lsp4j.services.TextDocumentService
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** Actual SDK/accessor/feature supports. Only the remote replies are controlled. */
class GoLspFindUsagesPolicyTest : LightPlatformTestCase() {
    override fun runInDispatchThread() = false

    fun testGoFindUsagesKeepsDeclarationsDefinitionsAndReferencesWithoutRelatedNavigation() = fixture(true) { f ->
        val usages = f.usages()
        assertEquals(setOf(LSPUsagePsiElement.UsageKind.declarations, LSPUsagePsiElement.UsageKind.definitions,
            LSPUsagePsiElement.UsageKind.references), usages.map { it.kind }.toSet())
        assertEquals(listOf("declaration", "definition", "references"), f.wrapper.requests.sorted())
        assertTrue("Find Usages must not disable explicit implementation navigation", f.wrapper.features.implementationFeature.isSupported(f.file))
        assertTrue(f.wrapper.features.typeDefinitionFeature.isSupported(f.file))
        assertTrue(f.wrapper.features.typeHierarchyFeature.isSupported(f.file))
    }

    fun testOtherClientsRetainTheUpstreamAggregatePolicy() = fixture(false) { f ->
        assertEquals(5, f.usages().size)
        assertEquals(listOf("declaration", "definition", "implementation", "references", "typeDefinition"), f.wrapper.requests.sorted())
    }

    fun testExplicitGoImplementationTypeDefinitionAndHierarchyStillReachTheServer() = fixture(true) { f ->
        val id = TextDocumentIdentifier(f.file.virtualFile.url)
        val pos = Position(1, 5)
        val implementations = LSPImplementationSupport(f.file).getImplementations(LSPImplementationParams(id, pos, 21)).get(10, TimeUnit.SECONDS)
        val types = LSPTypeDefinitionSupport(f.file).getTypeDefinitions(LSPTypeDefinitionParams(id, pos, 21)).get(10, TimeUnit.SECONDS)
        val hierarchy = LSPPrepareTypeHierarchySupport(f.file).getPrepareTypeHierarchies(LSPTypeHierarchyPrepareParams(id, pos, 21)).get(10, TimeUnit.SECONDS)
        assertEquals(listOf(f.wrapper.location), implementations.map { it.location() })
        assertEquals(listOf(f.wrapper.location), types.map { it.location() })
        assertEquals(1, hierarchy.size)
        assertEquals(listOf("implementation", "prepareTypeHierarchy", "typeDefinition"), f.wrapper.requests.sorted())
    }

    fun testRealReferencesFailureStillReportsItsOriginalFeatureAndMessage() = fixture(true) { f ->
        val notifications = CopyOnWriteArrayList<Notification>()
        val connection = project.messageBus.connect(f.disposable)
        connection.subscribe(Notifications.TOPIC, object : Notifications {
            override fun notify(notification: Notification) { notifications += notification }
        })
        f.wrapper.failReferences = true
        val usages = f.usages()
        flushUi()
        assertFalse("A failed references request must not manufacture reference hits", usages.any { it.kind == LSPUsagePsiElement.UsageKind.references })
        assertTrue("The actual CancellationSupport must still report a references error: $notifications",
            notifications.any { it.subtitle == "textDocument/references" && it.content.contains("owned references failure") })
        assertEquals(listOf("declaration", "definition", "references"), f.wrapper.requests.sorted())
        notifications.forEach(Notification::expire)
    }

    private inner class Fixture(val file: PsiFile, val wrapper: OwnedWrapper, val disposable: com.intellij.openapi.Disposable) {
        fun usages(): List<LSPUsagePsiElement> = requireNotNull(LSPUsageSupport(file)
            .getFeatureData(LSPUsageSupport.LSPUsageSupportParams(Position(1, 5)))).get(10, TimeUnit.SECONDS)
    }

    private fun fixture(go: Boolean, block: (Fixture) -> Unit) {
        val root = Files.createTempDirectory("open-goland-find-usages-policy-").toRealPath()
        val disposable = Disposer.newDisposable("owned Find Usages policy")
        VfsRootAccess.allowRootAccess(disposable, root.toString())
        val accessor = LanguageServiceAccessor.getInstance(project)
        @Suppress("UNCHECKED_CAST")
        val started = LanguageServiceAccessor::class.java.getDeclaredField("startedServers").apply { isAccessible = true }
            .get(accessor) as MutableSet<LanguageServerWrapper>
        val previous = synchronized(started) { started.toList() }
        val definition = requireNotNull(LanguageServersRegistry.getInstance().getServerDefinition("openidea.gopls"))
        val wrapper = OwnedWrapper(project, definition, if (go) GoLanguageServerFactory().createClientFeatures() else LSPClientFeatures())
        try {
            val path = root.resolve("source.go")
            Files.writeString(path, SOURCE)
            val virtual = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path))
            val file = read { requireNotNull(PsiManager.getInstance(project).findFile(virtual)).also {
                requireNotNull(PsiDocumentManager.getInstance(project).getDocument(it))
            } }
            wrapper.location = Location(virtual.url, Range(Position(1, 5), Position(1, 11)))
            synchronized(started) { started.clear(); started.add(wrapper) }
            block(Fixture(file, wrapper, disposable))
        } finally {
            synchronized(started) { started.clear(); started.addAll(previous) }
            Disposer.dispose(wrapper)
            Disposer.dispose(disposable)
            root.toFile().deleteRecursively()
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root)
        }
    }

    private class OwnedWrapper(project: Project, definition: LanguageServerDefinition, val features: LSPClientFeatures) : LanguageServerWrapper(project, definition) {
        val requests = CopyOnWriteArrayList<String>()
        var failReferences = false
        lateinit var location: Location
        private val capabilities = ServerCapabilities().apply {
            setDeclarationProvider(true); setDefinitionProvider(true); setReferencesProvider(true)
            setImplementationProvider(true); setTypeDefinitionProvider(true); setTypeHierarchyProvider(true)
        }
        private val text = Proxy.newProxyInstance(TextDocumentService::class.java.classLoader, arrayOf(TextDocumentService::class.java)) { _, method, _ ->
            when (method.name) {
                "declaration", "definition", "implementation", "typeDefinition" -> {
                    requests += method.name
                    CompletableFuture.completedFuture(Either.forLeft<List<Location>, List<LocationLink>>(listOf(location)))
                }
                "references" -> {
                    requests += method.name
                    if (failReferences) CompletableFuture.failedFuture<List<Location>>(ResponseErrorException(
                        ResponseError(ResponseErrorCode.InvalidParams, "owned references failure", null)))
                    else CompletableFuture.completedFuture(listOf(location))
                }
                "prepareTypeHierarchy" -> {
                    requests += method.name
                    CompletableFuture.completedFuture(listOf(TypeHierarchyItem("Shared", SymbolKind.Interface,
                        location.uri, location.range, location.range)))
                }
                "didOpen", "didClose", "didChange" -> null
                else -> null
            }
        } as TextDocumentService
        val remote = Proxy.newProxyInstance(LanguageServer::class.java.classLoader, arrayOf(LanguageServer::class.java)) { _, method, _ ->
            if (method.name == "getTextDocumentService") text else null
        } as LanguageServer
        init {
            features.setServerWrapper(this); features.setServerCapabilities(capabilities)
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
    private fun flushUi() = EdtTestUtil.runInEdtAndWait<RuntimeException> { PlatformTestUtil.dispatchAllEventsInIdeEventQueue() }
    companion object { private const val SOURCE = "package fixture\nfunc Shared() {}\nfunc Caller() { Shared() }\n" }
}
