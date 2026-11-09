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
import org.eclipse.lsp4j.jsonrpc.MessageConsumer
import org.eclipse.lsp4j.jsonrpc.messages.RequestMessage
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageServer
import org.junit.Assume
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/** Actual public supports/accessor -> owned stdio gopls, without manufactured replies. */
class GoLspFindUsagesGoplsTest : LightPlatformTestCase() {
    override fun runInDispatchThread() = false

    fun testOrdinaryFunctionAndInterfaceReferencesDoNotConsumeExplicitNavigationRequests() {
        val executable = System.getenv("OPENGOLAND_GOPLS_TEST")?.let(Path::of)
            ?: System.getenv("PATH").orEmpty().split(java.io.File.pathSeparator)
                .map { Path.of(it, "gopls") }.firstOrNull(Files::isExecutable)
        Assume.assumeTrue("Install gopls or set OPENGOLAND_GOPLS_TEST", executable != null)
        val root = Files.createTempDirectory("open-goland-find-usages-gopls-").toRealPath()
        val disposable = Disposer.newDisposable("owned gopls usage policy")
        VfsRootAccess.allowRootAccess(disposable, root.toString())
        val pool = Executors.newCachedThreadPool()
        val notifications = CopyOnWriteArrayList<Notification>()
        project.messageBus.connect(disposable).subscribe(Notifications.TOPIC, object : Notifications {
            override fun notify(notification: Notification) { notifications += notification }
        })
        val requests = CopyOnWriteArrayList<String>()
        val accessor = LanguageServiceAccessor.getInstance(project)
        @Suppress("UNCHECKED_CAST")
        val started = LanguageServiceAccessor::class.java.getDeclaredField("startedServers").apply { isAccessible = true }
            .get(accessor) as MutableSet<LanguageServerWrapper>
        val previous = synchronized(started) { started.toList() }
        val definition = requireNotNull(LanguageServersRegistry.getInstance().getServerDefinition("openidea.gopls"))
        val wrapper = OwnedWrapper(project, definition)
        var process: Process? = null
        var transport: Future<Void>? = null
        var remote: LanguageServer? = null
        var file: VirtualFile? = null
        try {
            Files.writeString(root.resolve("go.mod"), "module example.com/usages\n\ngo 1.24.0\n")
            val path = root.resolve("source.go")
            Files.writeString(path, SOURCE)
            val virtual = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path))
            file = virtual
            val psi = read { requireNotNull(PsiManager.getInstance(project).findFile(virtual)) }
            val document = read { requireNotNull(PsiDocumentManager.getInstance(project).getDocument(psi)) }
            process = ProcessBuilder(executable.toString(), "serve").directory(root.toFile())
                .redirectError(root.resolve("gopls.stderr").toFile()).apply {
                    environment()["GOTOOLCHAIN"] = "local"; environment()["GOTELEMETRY"] = "off"
                    environment()["GOPROXY"] = "off"; environment()["GOWORK"] = "off"
                    environment()["CGO_ENABLED"] = "0"
                }.start()
            val launcher = wrapper.features.createLauncherBuilder<LanguageServer>()
                .setRemoteInterface(LanguageServer::class.java).setLocalService(Client())
                .setInput(process.inputStream).setOutput(process.outputStream).setExecutorService(pool)
                .wrapMessages { consumer -> MessageConsumer { message ->
                    if (message is RequestMessage && message.method.startsWith("textDocument/")) requests += message.method
                    consumer.consume(message)
                } }.create()
            transport = launcher.startListening()
            val server = launcher.remoteProxy
            remote = server
            val initialized = server.initialize(InitializeParams().apply {
                capabilities = ClientCapabilities().apply {
                    general = GeneralClientCapabilities().apply { positionEncodings = listOf("utf-16") }
                }
                rootUri = root.toUri().toString()
                workspaceFolders = listOf(WorkspaceFolder(root.toUri().toString(), "usages"))
                initializationOptions = mapOf("staticcheck" to false)
            }).get(30, TimeUnit.SECONDS)
            server.initialized(InitializedParams())
            wrapper.attach(server, initialized.capabilities, transport)
            val info = read { wrapper.createFileConnectionInfo(virtual, document, true) }
            wrapper.connect(virtual, info).get(15, TimeUnit.SECONDS)
            synchronized(started) { started.clear(); started.add(wrapper) }

            // Both queries run through the same actual path used by LSPUsageSearcher.
            val ordinary = usages(psi, "Ordinary")
            val ordinaryReferences = ordinary.filter { it.kind == LSPUsagePsiElement.UsageKind.references }
            assertEquals("includeDeclaration=true must retain one declaration and one call", 2, ordinaryReferences.size)
            assertTrue(read { ordinaryReferences.all { it.textRange.substring(SOURCE) == "Ordinary" } })
            val interfaces = usages(psi, "Reader")
            val interfaceReferences = interfaces.filter { it.kind == LSPUsagePsiElement.UsageKind.references }
            assertEquals("The interface declaration, constraint and variable type remain searchable", 3, interfaceReferences.size)
            assertTrue(read { interfaceReferences.all { it.textRange.substring(SOURCE) == "Reader" } })
            assertEquals(2, requests.count { it == "textDocument/references" })
            assertFalse(requests.contains("textDocument/typeDefinition"))
            assertFalse(requests.contains("textDocument/implementation"))
            flushUi()
            assertEquals("No invalid related-navigation query should produce an error notification", emptyList<String>(),
                notifications.map { it.subtitle.orEmpty() + ": " + it.content })

            // These distinct public feature supports must still use real gopls.
            val identifier = TextDocumentIdentifier(virtual.url)
            val reader = SOURCE.indexOf("Reader")
            val implementations = LSPImplementationSupport(psi).getImplementations(
                LSPImplementationParams(identifier, position(reader), reader)).get(15, TimeUnit.SECONDS)
            assertTrue("Go to Implementation on Reader must still find Value", implementations.any {
                it.location().range.start == Position(2, 5)
            })
            val current = SOURCE.indexOf("Current")
            val types = LSPTypeDefinitionSupport(psi).getTypeDefinitions(
                LSPTypeDefinitionParams(identifier, position(current), current)).get(15, TimeUnit.SECONDS)
            assertTrue("Go to Type Definition on Current must still find Reader", types.any {
                it.location().range.start == Position(1, 5)
            })
            val hierarchy: List<*> = LSPPrepareTypeHierarchySupport(psi).getPrepareTypeHierarchies(
                LSPTypeHierarchyPrepareParams(identifier, position(reader), reader)).get(15, TimeUnit.SECONDS)
            assertFalse("Preparing the Reader type hierarchy must still return its actual item", hierarchy.isEmpty())
            assertEquals(listOf("Reader"), hierarchy.map { data ->
                // The upstream record is package-private, unlike its LSP DTO.
                val item = requireNotNull(data).javaClass.getDeclaredMethod("typeHierarchyItem").apply { isAccessible = true }
                    .invoke(data) as TypeHierarchyItem
                item.name
            })
            assertEquals(1, requests.count { it == "textDocument/implementation" })
            assertEquals(1, requests.count { it == "textDocument/typeDefinition" })
            assertEquals(1, requests.count { it == "textDocument/prepareTypeHierarchy" })
        } finally {
            synchronized(started) { started.clear(); started.addAll(previous) }
            runCatching { file?.let { wrapper.disconnect(it, false); wrapper.flush() } }
            runCatching { remote?.shutdown()?.get(5, TimeUnit.SECONDS) }
            runCatching { remote?.exit() }
            process?.let { owned ->
                if (!owned.waitFor(5, TimeUnit.SECONDS)) {
                    owned.destroy()
                    if (!owned.waitFor(2, TimeUnit.SECONDS)) {
                        owned.destroyForcibly()
                        check(owned.waitFor(3, TimeUnit.SECONDS)) { "Owned gopls did not terminate" }
                    }
                }
            }
            transport?.cancel(true)
            Disposer.dispose(wrapper)
            pool.shutdownNow(); pool.awaitTermination(5, TimeUnit.SECONDS)
            notifications.forEach(Notification::expire)
            Disposer.dispose(disposable)
            root.toFile().deleteRecursively()
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root)
        }
    }

    private fun usages(file: PsiFile, name: String): List<LSPUsagePsiElement> = requireNotNull(LSPUsageSupport(file)
        .getFeatureData(LSPUsageSupport.LSPUsageSupportParams(position(SOURCE.indexOf(name))))).get(30, TimeUnit.SECONDS)
    private fun <T> read(action: () -> T): T = ReadAction.compute<T, RuntimeException> { action() }
    private fun flushUi() = EdtTestUtil.runInEdtAndWait<RuntimeException> { PlatformTestUtil.dispatchAllEventsInIdeEventQueue() }

    private class OwnedWrapper(project: Project, definition: LanguageServerDefinition) : LanguageServerWrapper(project, definition) {
        val features: LSPClientFeatures = GoLanguageServerFactory().createClientFeatures().also { it.setServerWrapper(this) }
        private lateinit var remote: LanguageServer
        fun attach(server: LanguageServer, capabilities: ServerCapabilities, transport: Future<Void>) {
            remote = server
            setField("languageServer", server); setField("serverCapabilities", capabilities)
            setField("initializeFuture", CompletableFuture.completedFuture<Void>(null)); setField("launcherFuture", transport)
        }
        override fun start() {}
        override fun getInitializedServer(): CompletableFuture<LanguageServer> = CompletableFuture.completedFuture(remote)
        override fun getClientFeatures() = features
        override fun getServerStatus() = ServerStatus.started
        override fun isEnabled() = true
        override fun canOperate(file: VirtualFile) = true
        fun flush() { sendNotification { it }.get(5, TimeUnit.SECONDS) }
        override fun dispose() {
            openedDocuments.values.forEach { it.synchronizer?.let(Disposer::dispose) }
            openedDocuments.clear(); stopDispatcher(); features.dispose()
        }
        private fun setField(name: String, value: Any) {
            LanguageServerWrapper::class.java.getDeclaredField(name).apply { isAccessible = true }.set(this, value)
        }
    }

    private class Client : LanguageClient {
        override fun telemetryEvent(value: Any?) {}
        override fun publishDiagnostics(params: PublishDiagnosticsParams) {}
        override fun showMessage(params: MessageParams) {}
        override fun logMessage(params: MessageParams) {}
        override fun showMessageRequest(params: ShowMessageRequestParams): CompletableFuture<MessageActionItem> = CompletableFuture.completedFuture(null)
        override fun configuration(params: ConfigurationParams): CompletableFuture<List<Any>> = CompletableFuture.completedFuture(params.items.map { emptyMap<String, Any>() })
    }

    companion object {
        private const val SOURCE = "package fixture\ntype Reader interface { Read() int }\ntype Value struct{}\nfunc (Value) Read() int { return 7 }\nvar _ Reader = Value{}\nfunc Ordinary(value int) int { return value + 1 }\nfunc Caller() int { return Ordinary(1) }\nvar Current Reader = Value{}\n"
        private fun position(offset: Int): Position {
            val before = SOURCE.substring(0, offset)
            return Position(before.count { it == '\n' }, before.substringAfterLast('\n').length)
        }
    }
}
