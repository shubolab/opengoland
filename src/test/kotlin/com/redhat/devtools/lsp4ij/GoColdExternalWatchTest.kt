package com.redhat.devtools.lsp4ij

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.NewVirtualFile
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.LightPlatformTestCase
import com.redhat.devtools.lsp4ij.client.features.LSPClientFeatures
import com.redhat.devtools.lsp4ij.server.definition.LanguageServerDefinition
import dev.openidea.goide.lsp.GoLanguageClient
import org.eclipse.lsp4j.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** Real client registration and cold local VFS; no editor, daemon or native-fsnotifier claim. */
class GoColdExternalWatchTest : LightPlatformTestCase() {
    override fun runInDispatchThread() = false

    fun testAbsoluteExternalWatcherDiscoversUnopenedSourceAndVfsSeesItsDiskChange() = fixture { f ->
        val local = LocalFileSystem.getInstance()
        assertNull("The dependency must begin outside the VFS snapshot", local.findFileByPathIfCached(f.source.toString()))
        f.register(FileSystemWatcher().apply { setGlobPattern(f.directory.toString().replace('\\', '/') + "/**/*.{go,mod,sum,work}"); kind = 7 })
        val cached = local.findFileByPathIfCached(f.source.toString())
        assertNotNull("A registered external root must load its unopened Go sources into the VFS snapshot", cached)
        assertNull(FileDocumentManager.getInstance().getCachedDocument(requireNotNull(cached)))
        val vfsEvents = CopyOnWriteArrayList<VFileEvent>()
        val connection = project.messageBus.connect()
        try {
            connection.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
                override fun after(events: List<VFileEvent>) { vfsEvents.addAll(events) }
            })
            Files.writeString(f.source, "package dependency\nfunc Greeting() string { return \"changed-and-longer\" }\n")
            Files.setLastModifiedTime(f.source, FileTime.fromMillis(cached.timeStamp + 2000))
            // The test app disables fsnotifier. Supply its dirty marker, then use
            // the production asynchronous refresh API, not a fabricated VFS event.
            (cached as NewVirtualFile).markDirty()
            val refreshed = CompletableFuture<Void>()
            local.refreshFiles(listOf(cached.parent), true, true, Runnable { refreshed.complete(null) })
            refreshed.get(10, TimeUnit.SECONDS)
            assertTrue("Refresh must publish the real external content event: $vfsEvents", vfsEvents.any {
                it is VFileContentChangeEvent && Files.isSameFile(Path.of(it.path), f.source)
            })
            assertNull("The dependency is still unopened", FileDocumentManager.getInstance().getCachedDocument(cached))
        } finally { connection.disconnect() }
    }

    fun testRelativeExternalWatcherAlsoDiscoversAnUnopenedNestedSource() = fixture(dependencyName = "dependency[old]") { f ->
        val local = LocalFileSystem.getInstance()
        assertNull(local.findFileByPathIfCached(f.source.toString()))
        f.register(FileSystemWatcher().apply {
            setGlobPattern(RelativePattern().apply { setBaseUri(f.directory.toUri().toString()); pattern = "**/*.go" })
            kind = WatchKind.Change
        })
        val cached = local.findFileByPathIfCached(f.source.toString())
        assertNotNull("RelativePattern's file base URI must establish the same cold VFS view", cached)
        assertNull(FileDocumentManager.getInstance().getCachedDocument(requireNotNull(cached)))
    }

    fun testUpstreamRegistrationFailureDoesNotInstallAnIndependentExternalWatch() = fixture(failRegistration = true) { f ->
        val failure = runCatching { f.register(FileSystemWatcher().apply { setGlobPattern(f.directory.toString().replace('\\', '/') + "/**/*.go") }) }.exceptionOrNull()
        assertNotNull("The original registration error must reach the request caller", failure)
        assertTrue(generateSequence(failure) { it.cause }.any { it is IllegalStateException && it.message == "owned upstream registration failure" })
        assertNull("A failed upstream registration must not populate an independently watched VFS tree", LocalFileSystem.getInstance().findFileByPathIfCached(f.source.toString()))
    }

    fun testCancellableIdleStoppingDoesNotPermanentlyCloseTheSameClientsRegistrations() = fixture { f ->
        // These are controlled lifecycle callbacks: the fake wrapper is not a started server.
        // LSP4IJ's idle timer may emit stopping then started without disposing its client.
        f.client.handleServerStatusChanged(ServerStatus.started)
        f.client.handleServerStatusChanged(ServerStatus.stopping)
        f.client.handleServerStatusChanged(ServerStatus.started)
        f.register(FileSystemWatcher().apply { setGlobPattern(f.directory.toString().replace('\\', '/') + "/**/*.go"); kind = 7 })
        assertNotNull("A resumed client must still install actual matcher/VFS registrations",
            LocalFileSystem.getInstance().findFileByPathIfCached(f.source.toString()))
    }

    private class Fixture(val directory: Path, val source: Path, val client: GoLanguageClient) {
        fun register(watcher: FileSystemWatcher) {
            val options = DidChangeWatchedFilesRegistrationOptions(listOf(watcher))
            client.registerCapability(RegistrationParams(listOf(Registration("external", "workspace/didChangeWatchedFiles",
                JSONUtils.getLsp4jGson().toJsonTree(options))))).get(20, TimeUnit.SECONDS)
        }
    }

    private fun fixture(failRegistration: Boolean = false, dependencyName: String = "dependency", block: (Fixture) -> Unit) {
        val root = Files.createTempDirectory("cold external 中文😀 ").toRealPath()
        val lifetime = Disposer.newDisposable("owned cold external files")
        VfsRootAccess.allowRootAccess(lifetime, root.toString())
        val directory = Files.createDirectories(root.resolve("$dependencyName/nested"))
        val source = directory.resolve("external.go")
        Files.writeString(source, "package dependency\nfunc Message() string { return \"original\" }\n")
        val definition = requireNotNull(LanguageServersRegistry.getInstance().getServerDefinition("openidea.gopls"))
        val wrapper = RegistrationWrapper(project, definition, failRegistration)
        val client = GoLanguageClient(project).also { it.setServerWrapper(wrapper) }
        wrapper.bindOwner(client)
        try { block(Fixture(root.resolve(dependencyName), source, client)) }
        finally {
            client.dispose(); Disposer.dispose(wrapper); Disposer.dispose(lifetime)
            root.toFile().deleteRecursively()
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root)
        }
    }

    /** Enables the actual registration path without claiming a connected/started server. */
    private class RegistrationWrapper(project: Project, definition: LanguageServerDefinition, private val failRegistration: Boolean) : LanguageServerWrapper(project, definition) {
        private val features = LSPClientFeatures().also { it.setServerWrapper(this) }
        init {
            LanguageServerWrapper::class.java.getDeclaredField("initializeFuture").apply { isAccessible = true }
                .set(this, CompletableFuture.completedFuture<Void>(null))
        }
        fun bindOwner(client: GoLanguageClient) {
            val type = LanguageServerWrapper::class.java.declaredClasses.single { it.simpleName == "InitializingContext" }
            val context = type.declaredConstructors.single().apply { isAccessible = true }.newInstance(this)
            type.getDeclaredField("languageClient").apply { isAccessible = true }.set(context, client)
            @Suppress("UNCHECKED_CAST")
            val ready = type.getDeclaredField("capabilityRegistrationReady").apply { isAccessible = true }.get(context) as CompletableFuture<Void>
            ready.complete(null)
            LanguageServerWrapper::class.java.getDeclaredField("currentInitializingContext").apply { isAccessible = true }.set(this, context)
        }
        override fun registerCapability(params: RegistrationParams) {
            if (failRegistration) throw IllegalStateException("owned upstream registration failure")
            super.registerCapability(params)
        }
        override fun getClientFeatures() = features
        override fun dispose() { stopDispatcher(); features.dispose() }
    }
}
