package dev.openidea.goide.lsp

import dev.openidea.goide.run.GoDependencyRootChanged
import dev.openidea.goide.run.GoDependencyRootState
import dev.openidea.goide.run.GoLocalDependencyScopeOperation
import dev.openidea.goide.run.GoLocalDependencyScopeSnapshot
import dev.openidea.goide.run.GoRunBuildContextRequest
import dev.openidea.goide.run.GoRunParameters
import dev.openidea.goide.settings.GoToolchain
import junit.framework.TestCase
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/** Held production-coordinator futures; actual root observations, no simulated VFS coverage. */
class GoDependencyRecoveryCoordinatorTest : TestCase() {
    fun testInitiallyMissingPartialGraphKeepsAnInterestWithoutClaimingCoverage() = Fixture().use { f ->
        val missing = f.path.resolve("missing")
        f.update("one"); f.complete("one", partial(missing))
        assertEquals(setOf(missing), f.interests().keys)
        assertTrue(f.covered().isEmpty())
        assertTrue(f.registrations.isEmpty())
        assertTrue(f.scopes.failures().containsKey("one"))
    }

    fun testPresentPartialRecoveryDoesNotReplaceThePreviousVerifiedContribution() = Fixture().use { f ->
        val verified = Files.createDirectory(f.path.resolve("verified"))
        val recovery = Files.createDirectory(f.path.resolve("recovery"))
        f.update("one"); f.complete("one", snapshot(setOf(verified)))
        f.update("one"); f.complete("one", partial(recovery))
        assertEquals(setOf(verified, recovery), f.active())
        assertEquals(setOf(verified, recovery), f.covered())
        assertTrue(f.scopes.failures().containsKey("one"))
        val nested = GoDocumentSync.fileUri(recovery.resolve("deep/new.go"))
        assertTrue(f.scopes.affected(nested, nested.substringBeforeLast('/'), false))
        f.update("one"); f.complete("one", snapshot(emptySet()))
        assertTrue(f.active().isEmpty())
        assertTrue(f.interests().isEmpty())
    }

    fun testSharedMissingInterestLivesUntilTheLastPackageCloses() = Fixture().use { f ->
        val missing = f.path.resolve("shared")
        f.update("one", "two"); f.complete("one", partial(missing)); f.complete("two", partial(missing))
        f.update("two")
        assertEquals(setOf(missing), f.interests().keys)
        f.update()
        assertTrue(f.interests().isEmpty())
        assertTrue(f.registrations.isEmpty())
    }

    fun testPendingInterestIsNotCoveredUntilRegistrationAndReconciliationFinish() = Fixture().use { f ->
        val ready = Files.createDirectory(f.path.resolve("ready"))
        val held = CompletableFuture<Void>()
        val reconciliation = CompletableFuture<Void>()
        f.install = { held }
        f.reconcile = { reconciliation }
        f.update("one"); f.complete("one", partial(ready), wait = false)
        assertEquals(setOf(ready), f.interests().keys)
        assertTrue(f.covered().isEmpty())
        assertTrue(f.reconciled.isEmpty())
        held.complete(null)
        assertTrue("Register completion is not reconciliation completion", f.covered().isEmpty())
        reconciliation.complete(null); f.idle()
        assertEquals(setOf(ready), f.covered())
        assertEquals(listOf(setOf(ready)), f.reconciled)
    }

    fun testFailedInstallationRetainsTheUncoveredRecoveryAndOldWorkingScope() = Fixture().use { f ->
        val verified = Files.createDirectory(f.path.resolve("verified"))
        val ready = Files.createDirectory(f.path.resolve("ready"))
        f.update("one"); f.complete("one", snapshot(setOf(verified)))
        f.install = { CompletableFuture.failedFuture(IllegalStateException("owned bounded lease refused")) }
        f.update("one"); f.complete("one", partial(ready))
        assertEquals(setOf(verified), f.active())
        assertEquals(setOf(ready), f.interests().keys)
        assertEquals(setOf(verified), f.covered())
        assertTrue(f.scopes.failures().containsKey("<watch>"))
    }

    fun testTypedRootChangeBeforePublicationRequestsRetryWithoutPublishingStaleSelection() = Fixture().use { f ->
        val missing = f.path.resolve("appeared")
        val captured = partial(missing)
        Files.createDirectory(missing)
        f.update("one"); f.complete("one", captured)
        assertEquals(1, f.retries)
        assertTrue(f.interests().isEmpty())
        assertTrue(f.registrations.isEmpty())
        f.update("one")
        f.queries.getValue("one").future.completeExceptionally(GoDependencyRootChanged("changed during loader query"))
        f.idle(); assertEquals(2, f.retries)
    }

    fun testOldGenerationCannotPublishOrRequestRetry() = Fixture().use { f ->
        f.update("one"); val old = f.queries.getValue("one")
        f.update("one"); f.complete("one", snapshot(emptySet()))
        old.future.completeExceptionally(GoDependencyRootChanged("obsolete root"))
        f.idle(); assertEquals(0, f.retries); assertTrue(f.interests().isEmpty())
        f.update("one"); val oldSuccess = f.queries.getValue("one")
        f.update("one"); f.complete("one", snapshot(emptySet()))
        oldSuccess.future.complete(partial(f.path.resolve("obsolete-success")))
        f.idle(); assertTrue("A late successful partial graph must not revive an interest", f.interests().isEmpty())
        f.update("one"); val closing = f.queries.getValue("one"); f.scopes.dispose()
        closing.future.completeExceptionally(GoDependencyRootChanged("disposed root"))
        f.idle(); assertEquals(0, f.retries); assertTrue(f.interests().isEmpty())
    }

    fun testRecoveryHandoffRetainsAnUncoveredInterestAcrossAOneShotMetadataFailure() = Fixture().use { f ->
        val missing = f.path.resolve("missing")
        f.update("one"); f.complete("one", partial(missing))
        f.scopes.invalidateQueries(retainRecovery = true)
        f.update("one")
        f.queries.getValue("one").future.completeExceptionally(java.io.IOException("one-shot metadata failure"))
        f.idle()
        assertEquals("A same-owner recovery handoff must retain the exact original baseline", setOf(missing), f.interests().keys)
        assertFalse(f.interests().getValue(missing).isDirectory)
        assertTrue(f.covered().isEmpty())
        assertFalse(Files.exists(missing))
        Files.createDirectory(missing)
        assertFalse("A later probe must still be able to discover the checkout", f.interests().getValue(missing).isCurrent())
        f.scopes.invalidateQueries()
        f.idle()
        assertTrue("An ordinary SDK/input invalidation must still withdraw old interests", f.interests().isEmpty())
    }

    fun testClosingLastPackageWithdrawsInterestBeforeAHeldRegistrationReturns() = Fixture().use { f ->
        val ready = Files.createDirectory(f.path.resolve("ready"))
        val held = CompletableFuture<Void>()
        f.install = { held }
        f.update("one"); f.complete("one", partial(ready), wait = false)
        assertEquals(setOf(ready), f.interests().keys)
        f.update()
        assertTrue("Removing the owner cannot wait behind LSP registration", f.interests().isEmpty())
        held.complete(null); f.idle()
        assertTrue(f.registrations.isEmpty()); assertTrue(f.reconciled.isEmpty())
    }

    fun testCombinedVerifiedAndPendingBudgetCannotSilentlyTruncate() = Fixture().use { f ->
        val verified = Files.createDirectory(f.path.resolve("verified"))
        f.update("one"); f.complete("one", snapshot(setOf(verified)))
        val pending = (0 until 128).associate { index -> f.path.resolve("missing-$index").let { it to GoDependencyRootState.capture(it) } }
        f.update("one"); f.complete("one", snapshot(emptySet(), false, pending))
        assertEquals(setOf(verified), f.active())
        assertTrue("The excess scope must fail explicitly", f.scopes.failures().values.any { it.contains("128") })
        assertTrue(f.interests().isEmpty())
    }

    fun testAnAlreadyMaintainedMissingRootDoesNotBlockAnIndependentReadyRecovery() = Fixture().use { f ->
        val old = Files.createDirectory(f.path.resolve("old"))
        val ready = Files.createDirectory(f.path.resolve("ready"))
        f.update("one"); f.complete("one", snapshot(setOf(old)))
        Files.delete(old) // Its previously acquired NativeRoot remains an owned recovery maintainer.
        f.install = {
            if (f.registrations.values.last().any { !Files.isDirectory(it) })
                CompletableFuture.failedFuture(IllegalStateException("new coverage cannot be acquired for an absent root"))
            else CompletableFuture.completedFuture(null)
        }
        f.update("one"); f.complete("one", partial(ready))
        assertEquals("Only the new root needs new coverage; the old maintainer must survive", setOf(old, ready), f.active())
        assertEquals(setOf(old, ready), f.covered())
    }

    fun testOneUnwatchableRecoveryCannotBlockAnotherIndependentReadyRoot() = Fixture().use { f ->
        val verified = Files.createDirectory(f.path.resolve("verified"))
        val refused = Files.createDirectory(f.path.resolve("refused"))
        val ready = Files.createDirectory(f.path.resolve("ready"))
        f.update("one"); f.complete("one", snapshot(setOf(verified)))
        f.install = {
            if (refused in f.registrations.values.last())
                CompletableFuture.failedFuture(IllegalStateException("owned native depth/entry budget refusal"))
            else CompletableFuture.completedFuture(null)
        }
        val candidates = linkedMapOf(refused to GoDependencyRootState.capture(refused), ready to GoDependencyRootState.capture(ready))
        f.update("one"); f.complete("one", snapshot(emptySet(), false, candidates))
        assertEquals(setOf(verified, ready), f.active())
        assertEquals(setOf(verified, ready), f.covered())
        assertEquals(setOf(refused, ready), f.interests().keys)
        assertTrue(f.scopes.failures().containsKey("<watch>"))
        val readyId = f.registrations.entries.single { ready in it.value }.key
        f.scopes.invalidateQueries()
        f.update("one"); f.complete("one", snapshot(emptySet(), false, candidates))
        assertEquals("A successful independent lease must survive a fresh partial query", readyId,
            f.registrations.entries.single { ready in it.value }.key)
        assertEquals(setOf(verified, ready), f.active())
        assertEquals(setOf(verified, ready), f.covered())
    }

    fun testClosingTheLastOwnerReleasesItsRootEvenWhenAnotherRootIsRefused() = Fixture().use { f ->
        val old = Files.createDirectory(f.path.resolve("closed-owner-root"))
        val refused = Files.createDirectory(f.path.resolve("other-refused-root"))
        f.update("one"); f.complete("one", snapshot(setOf(old)))
        val held = CompletableFuture<Void>()
        f.install = { held }
        f.update("one", "two"); f.complete("two", partial(refused), wait = false)
        assertEquals(setOf(old, refused), f.registrations.values.flatten().toSet())
        f.update("two")
        f.install = { CompletableFuture.failedFuture(IllegalStateException("ordinary root refusal")) }
        f.complete("two", partial(refused), wait = false)
        held.completeExceptionally(IllegalStateException("previous install refused after its owner changed"))
        f.idle()
        assertEquals("A closed package cannot retain a native root because another package failed", emptySet<Path>(), f.active())
        assertTrue(f.covered().isEmpty())
        assertEquals(setOf(refused), f.interests().keys)
        assertTrue(f.scopes.failures().containsKey("<watch>"))
    }

    fun testAnAlreadyAcquiredSharedRootTransfersOwnerDespiteAnotherRootRefusal() = Fixture().use { f ->
        val shared = Files.createDirectory(f.path.resolve("already-acquired-shared"))
        val refused = Files.createDirectory(f.path.resolve("independent-refused"))
        f.update("one"); f.complete("one", snapshot(setOf(shared)))
        val sharedId = f.registrations.entries.single { shared in it.value }.key
        val held = CompletableFuture<Void>()
        f.install = { held }
        val candidates = linkedMapOf(shared to GoDependencyRootState.capture(shared), refused to GoDependencyRootState.capture(refused))
        f.update("one", "two"); f.complete("two", snapshot(emptySet(), false, candidates), wait = false)
        f.update("two") // Close the original owner while another root's registration is held.
        f.install = { CompletableFuture.failedFuture(IllegalStateException("ordinary independent-root refusal")) }
        f.complete("two", snapshot(emptySet(), false, candidates), wait = false)
        held.completeExceptionally(IllegalStateException("previous install refused after owner changed"))
        f.idle()
        assertEquals("The still-open owner must keep the acquired shared root", setOf(shared), f.active())
        assertEquals(setOf(shared), f.covered())
        assertEquals(sharedId, f.registrations.entries.single { shared in it.value }.key)
        assertEquals(setOf(shared, refused), f.interests().keys)
        assertTrue(f.scopes.failures().containsKey("<watch>"))
        f.scopes.invalidateQueries(retainRecovery = true)
        f.update("two"); f.complete("two", snapshot(emptySet(), false, candidates))
        assertEquals("Ownership transfer must survive a fresh partial query", sharedId,
            f.registrations.entries.single { shared in it.value }.key)
        assertEquals(setOf(shared), f.active())
        f.update()
        assertTrue(f.active().isEmpty()); assertTrue(f.covered().isEmpty()); assertTrue(f.interests().isEmpty())
    }

    private class Query : GoLocalDependencyScopeOperation {
        override val future = CompletableFuture<GoLocalDependencyScopeSnapshot>()
        override fun start() {}
        override fun dispose() {} // Permit a realistic completion after cancellation.
    }

    private class Fixture : AutoCloseable {
        val path = Files.createTempDirectory("go-recovery-coordinator-").toRealPath()
        val queries = linkedMapOf<String, Query>()
        val registrations = linkedMapOf<String, Set<Path>>()
        val reconciled = mutableListOf<Set<Path>>()
        val emitted = mutableListOf<Pair<Map<Path, GoDependencyRootState>, Set<Path>>>()
        val pendingInstalls = mutableListOf<CompletableFuture<Void>>()
        val pendingReconciliations = mutableListOf<CompletableFuture<Void>>()
        var retries = 0
        var install: () -> CompletableFuture<Void> = { CompletableFuture.completedFuture(null) }
        var reconcile: () -> CompletableFuture<Void> = { CompletableFuture.completedFuture(null) }
        val scopes = GoDependencyScopeCoordinator({ request -> Query().also { queries[request.workingDirectory] = it } },
            { id, roots -> registrations[id] = roots; install().also(pendingInstalls::add) },
            { roots -> reconciled += roots; reconcile().also(pendingReconciliations::add) },
            { id -> registrations.remove(id); CompletableFuture.completedFuture(null) }, Executor { it.run() },
            recoveryChanged = { candidates, installed -> emitted += candidates.toMap() to installed.toSet() },
            retryRequired = { retries++ })
        fun update(vararg keys: String) = scopes.update(keys.associateWith { input(it) })
        fun complete(key: String, snapshot: GoLocalDependencyScopeSnapshot, wait: Boolean = true) {
            queries.getValue(key).future.complete(snapshot); if (wait) idle()
        }
        fun idle() { scopes.idle().get(5, TimeUnit.SECONDS) }
        fun active(): Set<Path> { idle(); return registrations.values.flatten().toSet() }
        fun interests() = emitted.lastOrNull()?.first.orEmpty()
        fun covered() = emitted.lastOrNull()?.second.orEmpty()
        override fun close() {
            scopes.dispose(); pendingInstalls.forEach { it.complete(null) }; pendingReconciliations.forEach { it.complete(null) }; idle()
            Files.walk(path).use { files -> files.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    companion object {
        private fun input(key: String) = GoDependencyScopeInput(GoRunBuildContextRequest("file:///unused.go", key,
            GoRunParameters(), GoToolchain.Settings("", "", "", "", "", "", "", ""), emptyList()), { true })
        private fun partial(path: Path) = snapshot(emptySet(), false, mapOf(path to GoDependencyRootState.capture(path)))
        private fun snapshot(roots: Set<Path>, complete: Boolean = true, candidates: Map<Path, GoDependencyRootState> = emptyMap()) =
            GoLocalDependencyScopeSnapshot(roots, emptySet(), emptySet(), {
                if (candidates.values.any { !it.isCurrent() }) throw GoDependencyRootChanged("candidate changed before publication")
            }, complete, if (complete) emptyList() else listOf("owned partial graph"), recoveryCandidates = candidates)
    }
}
