package dev.openidea.goide.lsp

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.redhat.devtools.lsp4ij.client.LanguageClientImpl
import com.redhat.devtools.lsp4ij.LanguageServerWrapper
import com.redhat.devtools.lsp4ij.ServerStatus
import com.redhat.devtools.lsp4ij.JSONUtils
import com.redhat.devtools.lsp4ij.features.AbstractLSPDocumentFeatureSupport
import com.intellij.util.concurrency.AppExecutorUtil
import dev.openidea.goide.semantic.GoSemanticOperations
import org.eclipse.lsp4j.ApplyWorkspaceEditParams
import org.eclipse.lsp4j.ApplyWorkspaceEditResponse
import org.eclipse.lsp4j.RegistrationParams
import org.eclipse.lsp4j.UnregistrationParams
import org.eclipse.lsp4j.Unregistration
import org.eclipse.lsp4j.Registration
import org.eclipse.lsp4j.RelativePattern
import org.eclipse.lsp4j.FileSystemWatcher
import org.eclipse.lsp4j.DidChangeWatchedFilesRegistrationOptions
import org.eclipse.lsp4j.DidChangeWatchedFilesParams
import org.eclipse.lsp4j.FileEvent
import org.eclipse.lsp4j.FileChangeType
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

/** Server requests use the same atomic edit path as native Go refactor actions. */
class GoLanguageClient(project: Project) : LanguageClientImpl(project) {
    private val externalFiles = GoExternalFileWatches(project)
    private val capabilitiesClosed = AtomicBoolean()
    private val capabilityLock = Any()
    private var capabilityQueue = CompletableFuture.completedFuture<Void>(null)
    @Volatile private var ownerWrapper: LanguageServerWrapper? = null
    private var dependencyScopes: GoExternalDependencyScopes? = null

    override fun setServerWrapper(wrapper: LanguageServerWrapper) {
        super.setServerWrapper(wrapper)
        ownerWrapper = wrapper
    }

    override fun handleServerStatusChanged(status: ServerStatus) {
        super.handleServerStatusChanged(status)
        if (status == ServerStatus.started) {
            val scopes = synchronized(capabilityLock) {
                if (capabilitiesClosed.get() || project.isDisposed) null else dependencyScopes ?: GoExternalDependencyScopes(
                    project, ::registerDependencyScope, ::reconcileDependencyScope, ::unregisterDependencyScope,
                ).also { dependencyScopes = it }
            }
            scopes?.refresh()
        } else if (status == ServerStatus.stopping || status == ServerStatus.stopped) {
            // LSP4IJ also uses stopping for a cancellable idle timer. That same
            // client may return to started; only dispose permanently closes it.
            val previous = synchronized(capabilityLock) { dependencyScopes.also { dependencyScopes = null } }
            previous?.let(Disposer::dispose)
        }
    }

    private fun registerDependencyScope(id: String, roots: Set<Path>): CompletableFuture<Void> = capabilityChange(rejectClosed = true) {
        val watchers = roots.map { path -> FileSystemWatcher().apply {
            setGlobPattern(RelativePattern().apply { setBaseUri(path.toUri().toASCIIString()); pattern = "**/*.{go,mod,sum,work}" })
            kind = 7
        } }
        // Refuse unsafe/oversized scopes before installing even a partial matcher.
        val projectRoot = project.basePath?.let { Path.of(it).normalize() }
        check(roots.size <= 128 && roots.all { it.toString().length <= 4096 && GoExternalFileWatches.isSafeDependencyRoot(it, projectRoot) }) {
            "The external Go dependency scope exceeds the supported watch limits"
        }
        // Unlike a server RPC, this local registration has not passed through
        // LSP4J's wire decoder. The pinned wrapper consumes a JsonObject here.
        val options = JSONUtils.getLsp4jGson().toJsonTree(DidChangeWatchedFilesRegistrationOptions(watchers)).asJsonObject
        val params = RegistrationParams(listOf(Registration(id, "workspace/didChangeWatchedFiles",
            options)))
        super.registerCapability(params).thenCompose { externalFiles.registerOwned(id, roots) }
            .thenCompose { externalFiles.requireCoverage(id, roots) }
    }

    private fun unregisterDependencyScope(id: String): CompletableFuture<Void> = unregisterCapability(
        UnregistrationParams(listOf(Unregistration(id, "workspace/didChangeWatchedFiles"))))

    private fun reconcileDependencyScope(roots: Set<Path>): CompletableFuture<Void> = capabilityChange {
        val server = languageServer ?: return@capabilityChange CompletableFuture.failedFuture(IllegalStateException("The Go language server stopped"))
        val wrapper = ownerWrapper ?: return@capabilityChange CompletableFuture.failedFuture(IllegalStateException("The Go language client is detached"))
        check(wrapper.serverStatus == ServerStatus.started && wrapper.languageServer === server)
        // Cold VFS materialization may establish a new baseline after gopls read
        // the dependency. One directory event reconciles its already observed
        // files; steady-state updates still come from real VFS events.
        project.getService(AbstractLSPDocumentFeatureSupport.NavigationModificationTracker::class.java).incrementWatchedDependency()
        wrapper.incrementModificationCount()
        server.workspaceService.didChangeWatchedFiles(DidChangeWatchedFilesParams(roots.map {
            FileEvent(it.toUri().toASCIIString(), FileChangeType.Changed)
        }))
        CompletableFuture.completedFuture(null)
    }

    override fun registerCapability(params: RegistrationParams): CompletableFuture<Void> =
        capabilityChange { super.registerCapability(params).thenCompose { externalFiles.register(params) } }

    override fun unregisterCapability(params: UnregistrationParams): CompletableFuture<Void> =
        capabilityChange { super.unregisterCapability(params).thenCompose { externalFiles.unregister(params) } }

    private fun capabilityChange(rejectClosed: Boolean = false, action: () -> CompletableFuture<Void>): CompletableFuture<Void> = synchronized(capabilityLock) {
        // Preserve protocol arrival order, including rapid register/unregister.
        // A failed upstream registration must not leave an independently owned watch.
        val next = capabilityQueue.handle { _, _ -> null }.thenComposeAsync({
            if (capabilitiesClosed.get() || project.isDisposed) {
                if (rejectClosed) CompletableFuture.failedFuture(IllegalStateException("The Go language client is closed"))
                else CompletableFuture.completedFuture(null)
            } else action()
        }, AppExecutorUtil.getAppExecutorService())
        capabilityQueue = next
        // A cancelled RPC owns only its reply view, not the internal ordering barrier.
        next.thenApply { it }
    }

    override fun dispose() {
        val previous = synchronized(capabilityLock) {
            capabilitiesClosed.set(true)
            dependencyScopes.also { dependencyScopes = null }
        }
        previous?.let(Disposer::dispose)
        Disposer.dispose(externalFiles)
        super.dispose()
    }

    override fun applyEdit(params: ApplyWorkspaceEditParams): CompletableFuture<ApplyWorkspaceEditResponse> {
        if (project.isDisposed) return CompletableFuture.completedFuture(ApplyWorkspaceEditResponse(false).apply { failureReason = "The project is closed" })
        return GoSemanticOperations.get(project).applyServerEdit(params)
    }
}
