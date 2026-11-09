package com.redhat.devtools.lsp4ij

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.LightPlatformTestCase
import com.redhat.devtools.lsp4ij.client.features.LSPClientFeatures
import com.redhat.devtools.lsp4ij.features.AbstractLSPDocumentFeatureSupport
import com.redhat.devtools.lsp4ij.features.declaration.LSPDeclarationParams
import com.redhat.devtools.lsp4ij.features.implementation.LSPImplementationParams
import com.redhat.devtools.lsp4ij.features.navigation.LSPDefinitionParams
import com.redhat.devtools.lsp4ij.features.typeDefinition.LSPTypeDefinitionParams
import com.redhat.devtools.lsp4ij.server.definition.LanguageServerDefinition
import com.redhat.devtools.lsp4ij.usages.LocationData
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.LanguageServer
import org.eclipse.lsp4j.services.TextDocumentService
import java.lang.management.ManagementFactory
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

/** Actual uninstrumented public getters, SDK PSI and a controlled remote service. */
class GoNavigationParameterGateTest : LightPlatformTestCase() {
    override fun runInDispatchThread() = false

    fun testDeclarationParametersAndFutureAreOneAtomicGeneration() = scenario("declaration")
    fun testDefinitionParametersAndFutureAreOneAtomicGeneration() = scenario("definition")
    fun testTypeDefinitionParametersAndFutureAreOneAtomicGeneration() = scenario("typeDefinition")
    fun testImplementationParametersAndFutureAreOneAtomicGeneration() = scenario("implementation")

    private fun scenario(method: String) = fixture { f ->
        val support = f.support(method)
        assertNull("The scheduling test needs a genuinely unused parameter gate",
            support.javaClass.getDeclaredField("previousOffset").apply { isAccessible = true }.get(support))
        val entered = CountDownLatch(1)
        val firstFuture = AtomicReference<CompletableFuture<List<LocationData>>>()
        val firstError = AtomicReference<Throwable>()
        val worker = Thread({
            entered.countDown()
            try { firstFuture.set(f.query(method, FIRST)) } catch (e: Throwable) { firstError.set(e) }
        }, "owned navigation parameter A").apply { isDaemon = true }
        lateinit var second: CompletableFuture<List<LocationData>>
        try {
            // Acquire read access before the support monitor. B's read is then
            // reentrant even if an EDT writer queues while A waits on the
            // monitor; never wait for a new read lock while owning that lock.
            read { synchronized(support) {
                worker.start()
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                awaitBlockedOn(worker, support)
                // This thread already owns the same monitor, so B can call the
                // actual getter reentrantly. Before the fix A has written its
                // parameter key and is blocked inside the base getter. With
                // the fix A is blocked at the public getter's method entry.
                second = f.query(method, SECOND)
                assertEquals(f.location("Right"), second.get(10, TimeUnit.SECONDS).single().location())
            } }
            worker.join(10_000)
            assertFalse("The owned worker must leave the monitor within the bound", worker.isAlive)
            firstError.get()?.let { throw AssertionError("First navigation failed", it) }
            val first = requireNotNull(firstFuture.get())
            assertEquals("Parameter A must return Left, not B's cached Right location ($method)",
                f.location("Left"), first.get(10, TimeUnit.SECONDS).single().location())
            assertNotSame("Different parameter positions cannot share the same cached future", first, second)
            assertEquals("Both actual RPCs must retain their own UTF-16 query position",
                listOf(position(SOURCE, SECOND), position(SOURCE, FIRST)), f.wrapper.requests.map { it.second })
            assertEquals(listOf(method, method), f.wrapper.requests.map { it.first })
            assertSame("The final unchanged A generation should still be reusable", first, f.query(method, FIRST))
            assertEquals("Fixing the gate must not disable stable-context caching", 2, f.wrapper.requests.size)
        } finally {
            // synchronized() releases the monitor even on an assertion failure.
            if (worker.isAlive) { worker.interrupt(); worker.join(10_000) }
            assertFalse("No owned scheduling worker may survive the fixture", worker.isAlive)
        }
    }

    private fun awaitBlockedOn(worker: Thread, monitor: Any) {
        val bean = ManagementFactory.getThreadMXBean()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            val info = bean.getThreadInfo(worker.id)
            if (info?.threadState == Thread.State.BLOCKED &&
                info.lockInfo?.identityHashCode == System.identityHashCode(monitor) &&
                info.lockInfo?.className == monitor.javaClass.name) return
            check(worker.isAlive) { "The first call exited before reaching the owned support monitor" }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1))
        }
        error("The first call did not block on the exact support monitor: ${bean.getThreadInfo(worker.id)}")
    }

    private inner class Fixture(val file: PsiFile, val target: PsiFile, val wrapper: OwnedWrapper) {
        private val supports = LSPFileSupport.getSupport(file)
        fun support(method: String): AbstractLSPDocumentFeatureSupport<*, *> = when (method) {
            "declaration" -> supports.declarationSupport
            "definition" -> supports.definitionSupport
            "typeDefinition" -> supports.typeDefinitionSupport
            else -> supports.implementationSupport
        }
        fun query(method: String, offset: Int): CompletableFuture<List<LocationData>> = read {
            val id = TextDocumentIdentifier()
            val position = position(SOURCE, offset)
            requireNotNull(when (method) {
                "declaration" -> supports.declarationSupport.getDeclarations(LSPDeclarationParams(id, position, offset))
                "definition" -> supports.definitionSupport.getDefinitions(LSPDefinitionParams(id, position, offset))
                "typeDefinition" -> supports.typeDefinitionSupport.getTypeDefinitions(LSPTypeDefinitionParams(id, position, offset))
                else -> supports.implementationSupport.getImplementations(LSPImplementationParams(id, position, offset))
            })
        }
        fun location(name: String): Location = Location(target.virtualFile.url,
            Range(position(TARGET, TARGET.indexOf(name)), position(TARGET, TARGET.indexOf(name) + name.length)))
    }

    private fun fixture(block: (Fixture) -> Unit) {
        val root = Files.createTempDirectory("open-goland-navigation-parameters-").toRealPath()
        val disposable = Disposer.newDisposable("owned navigation parameter gates")
        VfsRootAccess.allowRootAccess(disposable, root.toString())
        val accessor = LanguageServiceAccessor.getInstance(project)
        @Suppress("UNCHECKED_CAST")
        val started = LanguageServiceAccessor::class.java.getDeclaredField("startedServers").apply { isAccessible = true }
            .get(accessor) as MutableSet<LanguageServerWrapper>
        val previous = synchronized(started) { started.toList() }
        val definition = requireNotNull(LanguageServersRegistry.getInstance().getServerDefinition("openidea.gopls"))
        val wrapper = OwnedWrapper(project, definition)
        try {
            val files = listOf("query.go" to SOURCE, "targets.go" to TARGET).map { (name, text) ->
                val path = root.resolve(name); Files.writeString(path, text)
                val virtual = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path))
                read { requireNotNull(PsiManager.getInstance(project).findFile(virtual)).also {
                    requireNotNull(PsiDocumentManager.getInstance(project).getDocument(it))
                } }
            }
            wrapper.targetURI = files[1].virtualFile.url
            synchronized(started) { started.clear(); started.add(wrapper) }
            block(Fixture(files[0], files[1], wrapper))
        } finally {
            synchronized(started) { started.clear(); started.addAll(previous) }
            Disposer.dispose(wrapper)
            Disposer.dispose(disposable)
            root.toFile().deleteRecursively()
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root)
        }
    }

    private class OwnedWrapper(project: Project, definition: LanguageServerDefinition) : LanguageServerWrapper(project, definition) {
        lateinit var targetURI: String
        val requests = CopyOnWriteArrayList<Pair<String, Position>>()
        private val features = LSPClientFeatures().also { it.setServerWrapper(this) }
        private val capabilities = ServerCapabilities().apply {
            setDeclarationProvider(true); setDefinitionProvider(true); setTypeDefinitionProvider(true); setImplementationProvider(true)
        }
        private val text = Proxy.newProxyInstance(TextDocumentService::class.java.classLoader, arrayOf(TextDocumentService::class.java)) { _, method, args ->
            when (method.name) {
                "declaration", "definition", "typeDefinition", "implementation" -> {
                    val at = (requireNotNull(args?.first()) as TextDocumentPositionParams).position
                    requests += method.name to Position(at.line, at.character)
                    val name = when (at) { position(SOURCE, FIRST) -> "Left"; position(SOURCE, SECOND) -> "Right"; else -> error("Unexpected query position $at") }
                    val offset = TARGET.indexOf(name)
                    CompletableFuture.completedFuture(Either.forLeft<List<Location>, List<LocationLink>>(listOf(
                        Location(targetURI, Range(position(TARGET, offset), position(TARGET, offset + name.length))))))
                }
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
        private const val SOURCE = "package fixture\nfunc Use() { _ = \"前😀\"; var First Left; var Second Right; _, _ = First, Second }\n"
        private const val TARGET = "package fixture\ntype Left struct{}\ntype Right struct{}\n"
        private val FIRST = SOURCE.indexOf("First") + 1
        private val SECOND = SOURCE.indexOf("Second") + 1
        private fun position(text: String, offset: Int): Position {
            val prefix = text.substring(0, offset)
            return Position(prefix.count { it == '\n' }, prefix.substringAfterLast('\n').length)
        }
    }
}
