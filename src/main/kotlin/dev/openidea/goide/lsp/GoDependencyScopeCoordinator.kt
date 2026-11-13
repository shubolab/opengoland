package dev.openidea.goide.lsp

import com.intellij.openapi.Disposable
import dev.openidea.goide.run.GoLocalDependencyScopeOperation
import dev.openidea.goide.run.GoLocalDependencyScopeSnapshot
import dev.openidea.goide.run.GoRunBuildContextRequest
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.UUID

/** A document/settings generation belongs to one package and one language client. */
internal data class GoDependencyScopeInput(val request: GoRunBuildContextRequest, val current: (GoLocalDependencyScopeSnapshot?) -> Boolean)

/**
 * Keeps the last verified contribution of each open package. Discovery and publication have
 * separate ordering: a slow obsolete query cannot install roots, and a new registration must
 * be installed and reconciled before its predecessor is released.
 */
internal class GoDependencyScopeCoordinator(
    private val query: (GoRunBuildContextRequest) -> GoLocalDependencyScopeOperation,
    private val register: (String, Set<Path>) -> CompletableFuture<Void>,
    private val reconcile: (Set<Path>) -> CompletableFuture<Void>,
    private val unregister: (String) -> CompletableFuture<Void>,
    private val executor: Executor,
    private val queryExecutor: Executor = executor,
) : Disposable {
    private val lock = Any()
    private var closed = false
    private var generation = 0L
    private val operations = linkedMapOf<String, GoLocalDependencyScopeOperation>()
    private val contributions = linkedMapOf<String, Set<Path>>()
    private val observations = linkedMapOf<String, GoLocalDependencyScopeSnapshot>()
    private val errors = linkedMapOf<String, String>()
    private val publicationLock = Any()
    private var publication = CompletableFuture.completedFuture<Void>(null)
    // These values are accessed only by the publication queue.
    private var nextId = 0L
    private val ownerId = UUID.randomUUID().toString()
    private var activeId: String? = null
    private var activeRoots = emptySet<Path>()
    private val ownedIds = linkedSetOf<String>()

    fun update(inputs: Map<String, GoDependencyScopeInput>) {
        val launches = mutableListOf<Triple<String, GoDependencyScopeInput, GoLocalDependencyScopeOperation>>()
        val previous: List<GoLocalDependencyScopeOperation>
        val epoch: Long
        synchronized(lock) {
            if (closed) return
            epoch = ++generation
            previous = operations.values.toList()
            operations.clear()
            contributions.keys.retainAll(inputs.keys)
            observations.keys.retainAll(inputs.keys)
            errors.keys.retainAll(inputs.keys)
            for ((key, input) in inputs) {
                try {
                    val operation = query(input.request)
                    operations[key] = operation
                    launches += Triple(key, input, operation)
                } catch (failure: Exception) { errors[key] = failure.message ?: "Dependency discovery failed" }
            }
        }
        previous.forEach { it.dispose() }
        syncScope()
        for ((key, input, operation) in launches) {
            operation.future.whenComplete { snapshot, failure ->
                executor.execute {
                    var error = failure
                    if (error == null && snapshot != null) {
                        try { snapshot.validateDisk(); check(input.current(snapshot)) { "The Go input generation changed" } }
                        catch (invalid: Exception) { error = invalid }
                    }
                    synchronized(lock) {
                        if (closed || generation != epoch || operations[key] !== operation) return@execute
                        operations.remove(key)
                        if (error == null && snapshot != null) {
                            if (snapshot.complete) {
                                observations[key] = snapshot
                                contributions[key] = snapshot.roots.toSet()
                                errors.remove(key)
                            } else {
                                val previousObservation = observations[key]
                                observations[key] = snapshot.copy(
                                    watchedFileUris = snapshot.watchedFileUris + previousObservation?.watchedFileUris.orEmpty(),
                                    watchedDirectoryUris = snapshot.watchedDirectoryUris + previousObservation?.watchedDirectoryUris.orEmpty(),
                                    watchedAllDirectoryUris = snapshot.watchedAllDirectoryUris + previousObservation?.watchedAllDirectoryUris.orEmpty(),
                                )
                                errors[key] = snapshot.diagnostics.joinToString("; ").ifBlank { "Dependency discovery is incomplete" }
                            }
                        } else errors[key] = error?.message ?: "Dependency discovery failed"
                    }
                    syncScope()
                }
            }
            queryExecutor.execute {
                val current = synchronized(lock) { !closed && generation == epoch && operations[key] === operation }
                if (current && input.current(null)) operation.start() else operation.dispose()
            }
        }
    }

    /** Immediate memory-only cancellation; a debounce timer must not queue behind an old process. */
    fun invalidateQueries() {
        val previous = synchronized(lock) {
            if (closed) return
            ++generation
            operations.values.toList().also { operations.clear() }
        }
        previous.forEach { it.dispose() }
    }

    /** Memory-only relevance check used by native document/VFS listeners. */
    fun affected(uri: String, parentUri: String, directory: Boolean): Boolean = synchronized(lock) {
        val localSource = uri.endsWith(".go") && contributions.values.any { paths -> paths.any { root ->
            uri.startsWith(GoDocumentSync.fileUri(root).trimEnd('/') + "/")
        } }
        localSource || observations.values.any { snapshot ->
            uri in snapshot.watchedFileUris || uri.substringAfterLast('/') in METADATA && parentUri in snapshot.watchedDirectoryUris ||
                uri in snapshot.watchedAllDirectoryUris || parentUri in snapshot.watchedAllDirectoryUris ||
                directory && snapshot.watchedFileUris.any { it.startsWith(uri.trimEnd('/') + "/") }
        }
    }

    internal fun failures(): Map<String, String> = synchronized(lock) { errors.toMap() }
    internal fun idle(): CompletableFuture<Void> = synchronized(publicationLock) { publication.thenApply { it } }

    private fun desired(): Set<Path> = synchronized(lock) {
        if (closed) emptySet() else contributions.values.flatten().toSet()
    }

    private fun syncScope() = synchronized(publicationLock) {
        publication = publication.handle { _, _ -> null }.thenComposeAsync({ publish() }, executor)
    }

    private fun publish(): CompletableFuture<Void> {
        val wanted = desired()
        if (wanted == activeRoots) return cleanup()
        if (wanted.isEmpty()) {
            activeId = null
            activeRoots = emptySet()
            return cleanup()
        }
        val id = "openidea.local-replacements/$ownerId/${++nextId}"
        ownedIds += id
        val installed = try { register(id, wanted) } catch (failure: Exception) { CompletableFuture.failedFuture(failure) }
        return installed.thenCompose {
            // Do not reconcile an obsolete scope, or publish it over a newer package generation.
            if (desired() == wanted) reconcile(wanted) else CompletableFuture.failedFuture(java.util.concurrent.CancellationException())
        }.handle { _, failure -> failure }.thenCompose { failure ->
            if (failure == null && desired() == wanted) {
                activeId = id
                activeRoots = wanted
                synchronized(lock) { errors.remove("<watch>") }
            } else if (failure != null && failure !is java.util.concurrent.CancellationException) {
                synchronized(lock) { if (!closed) errors["<watch>"] = failure.message ?: "Unable to install dependency watches" }
            }
            cleanup()
        }
    }

    private fun cleanup(): CompletableFuture<Void> {
        var result = CompletableFuture.completedFuture<Void>(null)
        for (id in ownedIds.toList().filter { it != activeId }) {
            result = result.thenCompose {
                val removed = try { unregister(id) } catch (failure: Exception) { CompletableFuture.failedFuture(failure) }
                removed.handle { _, failure ->
                    if (failure == null) ownedIds.remove(id)
                    else synchronized(lock) { if (!closed) errors["<watch>"] = failure.message ?: "Unable to release dependency watches" }
                    null
                }
            }
        }
        return result
    }

    override fun dispose() {
        val previous = synchronized(lock) {
            if (closed) return
            closed = true; ++generation
            contributions.clear(); observations.clear(); errors.clear()
            operations.values.toList().also { operations.clear() }
        }
        previous.forEach { it.dispose() }
        syncScope()
    }

    companion object { private val METADATA = setOf("go.mod", "go.sum", "go.work", "go.work.sum") }
}
