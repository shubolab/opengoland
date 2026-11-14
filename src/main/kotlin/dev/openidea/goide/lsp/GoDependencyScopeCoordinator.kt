package dev.openidea.goide.lsp

import com.intellij.openapi.Disposable
import dev.openidea.goide.run.GoDependencyRootChanged
import dev.openidea.goide.run.GoDependencyRootState
import dev.openidea.goide.run.GoLocalDependencyScopeOperation
import dev.openidea.goide.run.GoLocalDependencyScopeSnapshot
import dev.openidea.goide.run.GoRunBuildContextRequest
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor

internal data class GoDependencyScopeInput(val request: GoRunBuildContextRequest, val current: (GoLocalDependencyScopeSnapshot?) -> Boolean)

/** Verified package scopes and selected recovery interests have separate ownership. */
internal class GoDependencyScopeCoordinator(
    private val query: (GoRunBuildContextRequest) -> GoLocalDependencyScopeOperation,
    private val register: (String, Set<Path>) -> CompletableFuture<Void>,
    private val reconcile: (Set<Path>) -> CompletableFuture<Void>,
    private val unregister: (String) -> CompletableFuture<Void>,
    private val executor: Executor,
    private val queryExecutor: Executor = executor,
    private val recoveryChanged: (Map<Path, GoDependencyRootState>, Set<Path>) -> Unit = { _, _ -> },
    private val retryRequired: () -> Unit = {},
) : Disposable {
    private val lock = Any()
    private val emissionLock = Any()
    private var closed = false
    private var generation = 0L
    private val operations = linkedMapOf<String, GoLocalDependencyScopeOperation>()
    // Committed only after actual registration/reconciliation. A failed new
    // graph cannot erase the previous package's working ownership.
    private val contributions = linkedMapOf<String, Set<Path>>()
    private val installedRecovery = linkedMapOf<String, Set<Path>>()
    private val completeUpdates = linkedMapOf<String, Set<Path>>()
    private val recoveryUpdates = linkedMapOf<String, Set<Path>>()
    private val recoveryObservations = linkedMapOf<String, Map<Path, GoDependencyRootState>>()
    private val observations = linkedMapOf<String, GoLocalDependencyScopeSnapshot>()
    private val errors = linkedMapOf<String, String>()
    private var packages = emptySet<String>()
    private var publishedRoots = emptySet<Path>()
    private val ownedPaths = linkedSetOf<Path>()
    private val publicationLock = Any()
    private var publication = CompletableFuture.completedFuture<Void>(null)
    // Publication-queue only. A literal root keeps its acquired maintainer even
    // while missing, so adding C never reacquires an unrelated absent A.
    private var nextId = 0L
    private val ownerId = UUID.randomUUID().toString()
    private val active = linkedMapOf<Path, String>()
    private val ownedIds = linkedMapOf<String, Path>()

    fun update(inputs: Map<String, GoDependencyScopeInput>) {
        val launches = mutableListOf<Triple<String, GoDependencyScopeInput, GoLocalDependencyScopeOperation>>()
        val previous: List<GoLocalDependencyScopeOperation>
        val epoch: Long
        synchronized(lock) {
            if (closed) return
            epoch = ++generation
            previous = operations.values.toList(); operations.clear()
            packages = inputs.keys.toSet()
            contributions.keys.retainAll(packages); installedRecovery.keys.retainAll(packages)
            completeUpdates.clear(); recoveryUpdates.clear()
            recoveryObservations.keys.retainAll(packages)
            observations.keys.retainAll(packages); errors.keys.retainAll(packages + "<watch>" + "<recovery>")
            for ((key, input) in inputs) {
                try {
                    val operation = query(input.request)
                    operations[key] = operation; launches += Triple(key, input, operation)
                } catch (failure: Exception) { errors[key] = failure.message ?: "Dependency discovery failed" }
            }
        }
        previous.forEach { it.dispose() }
        emitRecovery() // Removing the last interest never waits behind a held installation.
        syncScope()
        for ((key, input, operation) in launches) {
            operation.future.whenComplete { snapshot, failure -> executor.execute {
                if (!owns(epoch, key, operation)) return@execute
                var error = unwrapped(failure)
                if (error == null && snapshot != null) try { snapshot.validateDisk() } catch (invalid: Exception) { error = unwrapped(invalid) }
                var inputCurrent = false
                if (error == null || error is GoDependencyRootChanged) {
                    try { inputCurrent = input.current(snapshot) } catch (invalid: Exception) { if (error == null) error = invalid }
                }
                if (error == null && !inputCurrent) error = IllegalStateException("The Go input generation changed")
                var retry = false
                synchronized(lock) {
                    if (closed || generation != epoch || operations[key] !== operation) return@execute
                    operations.remove(key)
                    if (error == null && snapshot != null) {
                        val ready = snapshot.recoveryCandidates.filterValues { it.isDirectory }.keys.toSet()
                        val wanted = desiredByPackageLocked().filterKeys { it != key }.values.flatten().toSet() +
                            (if (snapshot.complete) snapshot.roots else contributions[key].orEmpty() + ready)
                        val interests = recoveryObservations.filterKeys { it != key }.values.flatMap { it.keys }.toSet() + snapshot.recoveryCandidates.keys
                        if ((wanted + interests + publishedRoots + ownedPaths).size > MAX_ROOTS) {
                            errors[key] = "The combined Go dependency scope exceeds 128 roots"
                        } else {
                            recoveryObservations[key] = snapshot.recoveryCandidates.toMap()
                            if (snapshot.complete) {
                                completeUpdates[key] = snapshot.roots.toSet(); recoveryUpdates.remove(key)
                                observations[key] = snapshot; errors.remove(key)
                            } else {
                                completeUpdates.remove(key); recoveryUpdates[key] = ready
                                val old = observations[key]
                                observations[key] = snapshot.copy(watchedFileUris = snapshot.watchedFileUris + old?.watchedFileUris.orEmpty(),
                                    watchedDirectoryUris = snapshot.watchedDirectoryUris + old?.watchedDirectoryUris.orEmpty(),
                                    watchedAllDirectoryUris = snapshot.watchedAllDirectoryUris + old?.watchedAllDirectoryUris.orEmpty())
                                errors[key] = snapshot.diagnostics.joinToString("; ").ifBlank { "Dependency discovery is incomplete" }
                            }
                        }
                    } else {
                        errors[key] = error?.message ?: "Dependency discovery failed"
                        retry = error is GoDependencyRootChanged && inputCurrent
                    }
                }
                emitRecovery(); syncScope()
                if (retry) synchronized(lock) {
                    // The callback only schedules a bounded, cancellable retry;
                    // it performs no IO or future wait while the owner is locked.
                    if (!closed && generation == epoch && key in packages) retryRequired()
                }
            } }
            queryExecutor.execute {
                if (owns(epoch, key, operation) && input.current(null)) operation.start() else operation.dispose()
            }
        }
    }

    private fun owns(epoch: Long, key: String, operation: GoLocalDependencyScopeOperation) = synchronized(lock) {
        !closed && generation == epoch && operations[key] === operation
    }

    fun invalidateQueries(retainRecovery: Boolean = false) {
        val previous = synchronized(lock) {
            if (closed) return
            ++generation
            // Ordinary input/settings invalidation withdraws old probes. A
            // same-owner pending-root handoff keeps its original baseline across
            // a transient query failure, until a fresh query replaces it.
            if (!retainRecovery) recoveryObservations.clear()
            completeUpdates.clear(); recoveryUpdates.clear()
            operations.values.toList().also { operations.clear() }
        }
        previous.forEach { it.dispose() }; emitRecovery(); syncScope()
    }

    fun affected(uri: String, parentUri: String, directory: Boolean): Boolean = synchronized(lock) {
        val selected = contributions.values.flatten() + completeUpdates.values.flatten() + installedRecovery.values.flatten() + recoveryUpdates.values.flatten()
        val localSource = (uri.endsWith(".go") || directory) && selected.any {
            val base = GoDocumentSync.fileUri(it).trimEnd('/')
            uri == base || uri.startsWith(base + "/")
        }
        localSource || observations.values.any { snapshot ->
            uri in snapshot.watchedFileUris || uri.substringAfterLast('/') in METADATA && parentUri in snapshot.watchedDirectoryUris ||
                uri in snapshot.watchedAllDirectoryUris || parentUri in snapshot.watchedAllDirectoryUris ||
                directory && snapshot.watchedFileUris.any { it.startsWith(uri.trimEnd('/') + "/") }
        }
    }

    internal fun failures(): Map<String, String> = synchronized(lock) { errors.toMap() }
    internal fun idle(): CompletableFuture<Void> = synchronized(publicationLock) { publication.thenApply { it } }
    private fun desiredByPackageLocked(): Map<String, Set<Path>> = if (closed) emptyMap() else packages.associateWith { key ->
        completeUpdates[key] ?: (contributions[key].orEmpty() + (recoveryUpdates[key] ?: installedRecovery[key].orEmpty()))
    }
    private fun current(epoch: Long, wanted: Map<String, Set<Path>>) = synchronized(lock) {
        !closed && generation == epoch && desiredByPackageLocked() == wanted
    }
    private fun emitRecovery() = synchronized(emissionLock) {
        val value = synchronized(lock) {
            (if (closed) emptyMap() else recoveryObservations.values.fold(linkedMapOf<Path, GoDependencyRootState>()) { all, part -> all.apply { putAll(part) } }) to publishedRoots
        }
        try { recoveryChanged(value.first, value.second) }
        catch (failure: Exception) { synchronized(lock) { if (!closed) errors["<recovery>"] = failure.message ?: "Unable to observe dependency recovery" } }
    }
    private fun syncScope() = synchronized(publicationLock) {
        publication = publication.handle { _, _ -> null }.thenComposeAsync({ publish() }, executor)
    }

    private fun publish(): CompletableFuture<Void> {
        val (epoch, wantedByPackage) = synchronized(lock) {
            val wanted = desiredByPackageLocked()
            // Existing actual maintainers can be shared immediately. Their new
            // package owner must not depend on an unrelated new root succeeding.
            wanted.forEach { (key, roots) ->
                val acquired = roots.intersect(active.keys)
                if (acquired.isNotEmpty()) installedRecovery[key] = installedRecovery[key].orEmpty() + acquired
            }
            generation to wanted
        }
        val wanted = wantedByPackage.values.flatten().toSet()
        var work = CompletableFuture.completedFuture<Void>(null)
        for (root in wanted - active.keys) work = work.thenCompose {
            if (!current(epoch, wantedByPackage)) return@thenCompose CompletableFuture.failedFuture(CancellationException())
            val id = "openidea.local-replacements/$ownerId/${++nextId}"
            ownedIds[id] = root
            synchronized(lock) { ownedPaths.add(root) }
            val installed = try { register(id, setOf(root)) } catch (failure: Exception) { CompletableFuture.failedFuture(failure) }
            installed.thenCompose {
                if (current(epoch, wantedByPackage)) reconcile(setOf(root)) else CompletableFuture.failedFuture(CancellationException())
            }.handle<Void> { _, failed ->
                val failure = unwrapped(failed)
                synchronized(lock) {
                    if (closed || generation != epoch || desiredByPackageLocked() != wantedByPackage)
                        throw CompletionException(CancellationException())
                    if (failure is CancellationException || failure is GoDependencyRootChanged)
                        throw CompletionException(failure)
                    if (failure == null) {
                        // Remember actual ownership immediately. Another independent
                        // root may be refused, but this acquired maintainer survives
                        // refresh until a fresh complete graph or its last owner drops it.
                        active[root] = id
                        wantedByPackage.filterValues { root in it }.keys.forEach { key ->
                            installedRecovery[key] = installedRecovery[key].orEmpty() + setOf(root)
                        }
                        publishedRoots = active.keys.toSet()
                    } else errors["<watch>"] = failure.message ?: "Unable to install dependency watches"
                }
                emitRecovery()
                null // A normal refusal of B cannot prevent independent C.
            }
        }
        return work.handle { _, failure -> unwrapped(failure) }.thenCompose { failure ->
            val accepted = synchronized(lock) {
                if (failure == null && wanted.all { it in active } &&
                    ((!closed && generation == epoch && desiredByPackageLocked() == wantedByPackage) || closed && wanted.isEmpty())) {
                    active.keys.retainAll(wanted)
                    completeUpdates.forEach { (key, roots) -> contributions[key] = roots; installedRecovery.remove(key) }
                    recoveryUpdates.forEach { (key, roots) -> installedRecovery[key] = roots }
                    completeUpdates.clear(); recoveryUpdates.clear()
                    publishedRoots = active.keys.toSet(); errors.remove("<watch>")
                    true
                } else false
            }
            if (!accepted && failure != null && failure !is CancellationException) {
                synchronized(lock) { if (!closed) errors["<watch>"] = failure.message ?: "Unable to install dependency watches" }
            }
            synchronized(lock) {
                // Failed replacement preserves a current owner's old working
                // root, but closing its last owner releases it even if B failed.
                val stillOwned = contributions.values.flatten().toSet() + installedRecovery.values.flatten()
                active.keys.retainAll(stillOwned)
                publishedRoots = active.keys.toSet()
            }
            emitRecovery()
            cleanup().thenRun { emitRecovery() }
        }
    }

    private fun cleanup(): CompletableFuture<Void> {
        var result = CompletableFuture.completedFuture<Void>(null)
        for (id in ownedIds.keys.toList().filter { it !in active.values }) result = result.thenCompose {
            val removed = try { unregister(id) } catch (failure: Exception) { CompletableFuture.failedFuture(failure) }
            removed.handle { _, failure ->
                if (failure == null) {
                    ownedIds.remove(id)
                    synchronized(lock) { ownedPaths.clear(); ownedPaths.addAll(ownedIds.values) }
                } else synchronized(lock) { if (!closed) errors["<watch>"] = failure.message ?: "Unable to release dependency watches" }
                null
            }
        }
        return result
    }

    override fun dispose() {
        val previous = synchronized(lock) {
            if (closed) return
            closed = true; ++generation
            packages = emptySet(); contributions.clear(); installedRecovery.clear(); completeUpdates.clear(); recoveryUpdates.clear()
            recoveryObservations.clear(); observations.clear(); errors.clear()
            operations.values.toList().also { operations.clear() }
        }
        previous.forEach { it.dispose() }; emitRecovery(); syncScope()
    }

    companion object {
        private const val MAX_ROOTS = 128
        private val METADATA = setOf("go.mod", "go.sum", "go.work", "go.work.sum")
        private fun unwrapped(value: Throwable?): Throwable? {
            var result = value
            while ((result is CompletionException || result is ExecutionException) && result.cause != null) result = result.cause
            return result
        }
    }
}
