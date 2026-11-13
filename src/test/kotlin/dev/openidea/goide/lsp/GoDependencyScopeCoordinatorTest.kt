package dev.openidea.goide.lsp

import dev.openidea.goide.run.GoLocalDependencyScopeOperation
import dev.openidea.goide.run.GoLocalDependencyScopeSnapshot
import dev.openidea.goide.run.GoRunBuildContextRequest
import dev.openidea.goide.run.GoRunParameters
import dev.openidea.goide.settings.GoToolchain
import junit.framework.TestCase
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/** Controlled held futures exercise generation and lease ordering, not filesystem or gopls claims. */
class GoDependencyScopeCoordinatorTest : TestCase() {
    fun testOpenPackagesUnionSharedRootsAndCloseReleasesTheirContributions() = Fixture().use { f ->
        f.update("one", "two")
        f.complete("one", "shared", "first")
        f.complete("two", "shared", "second")
        assertEquals(setOf(root("shared"), root("first"), root("second")), f.active())
        f.update("two")
        assertEquals(setOf(root("shared"), root("second")), f.active())
        f.update()
        assertEquals(emptySet<Path>(), f.active())
        assertTrue(f.registrations.isEmpty())
    }

    fun testIncompleteAndFailedQueriesRetainTheLastVerifiedRoots() = Fixture().use { f ->
        f.update("one"); f.complete("one", "verified")
        f.update("one")
        f.queries.getValue("one").future.complete(snapshot(setOf(root("partial")), complete = false))
        assertEquals(setOf(root("verified")), f.active())
        assertTrue(f.scopes.failures().containsKey("one"))
        f.update("one")
        f.queries.getValue("one").future.completeExceptionally(IllegalStateException("owned query failed"))
        assertEquals(setOf(root("verified")), f.active())
        assertTrue(f.scopes.failures().getValue("one").contains("owned query failed"))
        f.update("one"); f.complete("one")
        assertEquals("Only a successful complete empty graph releases the old root", emptySet<Path>(), f.active())
    }

    fun testLateQueryAndChangedDocumentCannotInstallObsoleteRoots() = Fixture().use { f ->
        f.update("one")
        val old = f.queries.getValue("one")
        f.update("one")
        old.future.complete(snapshot(setOf(root("old"))))
        f.complete("one", "new")
        assertEquals(setOf(root("new")), f.active())
        var current = true
        f.scopes.update(mapOf("one" to input("one") { current }))
        current = false
        f.complete("one", "invalid-document")
        assertEquals(setOf(root("new")), f.active())
        assertTrue(old.disposed)
    }

    fun testNewRegistrationCompletesThenReconcilesBeforeOldScopeIsReleased() = Fixture().use { f ->
        f.update("one"); f.complete("one", "old")
        val oldId = f.registrations.keys.single()
        val held = CompletableFuture<Void>()
        f.install = { held }
        f.update("one"); f.queries.getValue("one").future.complete(snapshot(setOf(root("new"))))
        assertEquals("No reconcile before the matcher and root lease are installed", listOf(setOf(root("old"))), f.reconciled)
        assertTrue(oldId in f.registrations)
        held.complete(null)
        f.idle()
        assertEquals(listOf(setOf(root("old")), setOf(root("new"))), f.reconciled)
        assertFalse(oldId in f.registrations)
        assertEquals(setOf(root("new")), f.active())
        val count = f.reconciled.size
        f.update("one"); f.complete("one", "new")
        assertEquals("An unchanged scope does not repeatedly reload dependency snapshots", count, f.reconciled.size)
    }

    fun testFailedRootInstallationCleansPartialNewRegistrationAndPreservesOldScope() = Fixture().use { f ->
        f.update("one"); f.complete("one", "old")
        f.install = { CompletableFuture.failedFuture(IllegalStateException("bounded root refused")) }
        f.update("one"); f.complete("one", "new")
        assertEquals(setOf(root("old")), f.active())
        assertEquals(1, f.registrations.size)
        assertEquals(listOf(setOf(root("old"))), f.reconciled)
        assertTrue(f.scopes.failures().containsKey("<watch>"))
    }

    fun testDisposalWhileRegistrationIsHeldReleasesLateScopeWithoutReconciling() = Fixture().use { f ->
        val held = CompletableFuture<Void>()
        f.install = { held }
        f.update("one"); f.queries.getValue("one").future.complete(snapshot(setOf(root("late"))))
        f.scopes.dispose()
        held.complete(null)
        f.idle()
        assertTrue(f.registrations.isEmpty())
        assertTrue(f.reconciled.isEmpty())
        f.update("one")
        assertEquals(1, f.queries.size)
    }

    fun testOnlyKnownBuildInputsAndPackageMetadataTriggerAnotherQuery() = Fixture().use { f ->
        f.update("one")
        f.queries.getValue("one").future.complete(snapshot(setOf(root("dependency"))).copy(
            watchedDirectoryUris = setOf("file:///deps"), watchedFileUris = setOf("file:///custom/env")))
        assertTrue(f.scopes.affected("file:///deps/go.mod", "file:///deps", false))
        assertTrue(f.scopes.affected("file:///custom/env", "file:///custom", false))
        assertFalse("An unrelated directory is not a local replacement contribution", f.scopes.affected("file:///deps/source.go", "file:///deps", false))
        assertFalse(f.scopes.affected("file:///unrelated/go.mod", "file:///unrelated", false))
    }

    fun testPartialGraphRetainsOldMetadataAndDependencyImportsCanDiscoverAnotherColdRoot() = Fixture().use { f ->
        f.update("one")
        val dependency = root("dependency")
        val mod = GoDocumentSync.fileUri(dependency.resolve("go.mod"))
        f.queries.getValue("one").future.complete(snapshot(setOf(dependency)).copy(watchedFileUris = setOf(mod)))
        f.update("one")
        f.queries.getValue("one").future.complete(snapshot(emptySet(), complete = false))
        assertTrue("Repairing a previous dependency manifest must retry a partial graph", f.scopes.affected(mod, mod.substringBeforeLast('/'), false))
        val added = GoDocumentSync.fileUri(dependency.resolve("nested/new.go"))
        assertTrue("A dependency's new import can introduce another replacement", f.scopes.affected(added, added.substringBeforeLast('/'), false))
        f.update("one"); f.complete("one", "dependency", "new-transitive")
        assertEquals(setOf(dependency, root("new-transitive")), f.active())
    }

    fun testDebounceInvalidationCancelsImmediatelyAndIgnoresLateQueryWithoutDroppingRoots() = Fixture().use { f ->
        f.update("one"); f.complete("one", "verified")
        f.update("one")
        val pending = f.queries.getValue("one")
        f.scopes.invalidateQueries()
        assertTrue("Cancellation must not wait behind the old process on the query executor", pending.disposed)
        pending.future.complete(snapshot(setOf(root("late"))))
        assertEquals(setOf(root("verified")), f.active())
        f.update("one"); f.complete("one", "current")
        assertEquals(setOf(root("current")), f.active())
    }

    fun testResumedScopeOwnerCannotReuseThePreviousOwnersRegistrationId() = Fixture().use { first ->
        Fixture().use { second ->
            first.update("one"); first.complete("one", "shared")
            second.update("one"); second.complete("one", "shared")
            assertFalse("A paused owner's late cleanup must not unregister a resumed owner's same-numbered scope",
                first.registrations.keys.single() == second.registrations.keys.single())
        }
    }

    private class Query : GoLocalDependencyScopeOperation {
        override val future = CompletableFuture<GoLocalDependencyScopeSnapshot>()
        var disposed = false
        override fun start() {}
        // Deliberately allow a late completion, as a real process can win a cancellation race.
        override fun dispose() { disposed = true }
    }

    private class Fixture : AutoCloseable {
        val queries = linkedMapOf<String, Query>()
        val registrations = linkedMapOf<String, Set<Path>>()
        val reconciled = mutableListOf<Set<Path>>()
        var install: () -> CompletableFuture<Void> = { CompletableFuture.completedFuture(null) }
        val scopes = GoDependencyScopeCoordinator({ request -> Query().also { queries[request.workingDirectory] = it } },
            { id, roots -> registrations[id] = roots; install() },
            { roots -> reconciled += roots; CompletableFuture.completedFuture(null) },
            { id -> registrations.remove(id); CompletableFuture.completedFuture(null) }, Executor { it.run() })
        fun update(vararg packages: String) = scopes.update(packages.associateWith { input(it) })
        fun complete(key: String, vararg roots: String) { queries.getValue(key).future.complete(snapshot(roots.mapTo(linkedSetOf(), ::root))); idle() }
        fun active(): Set<Path> { idle(); return registrations.values.flatten().toSet() }
        fun idle() { scopes.idle().get(5, TimeUnit.SECONDS) }
        override fun close() { scopes.dispose(); idle() }
    }

    companion object {
        private fun root(name: String) = Path.of(System.getProperty("java.io.tmpdir"), "owned-scope-$name").toAbsolutePath().normalize()
        private fun input(key: String, current: () -> Boolean = { true }) = GoDependencyScopeInput(
            GoRunBuildContextRequest("file:///unused.go", key, GoRunParameters(), GoToolchain.Settings("", "", "", "", "", "", "", ""), emptyList()),
            { current() })
        private fun snapshot(roots: Set<Path>, complete: Boolean = true) = GoLocalDependencyScopeSnapshot(
            roots, emptySet(), emptySet(), {}, complete, if (complete) emptyList() else listOf("owned incomplete graph"))
    }
}
