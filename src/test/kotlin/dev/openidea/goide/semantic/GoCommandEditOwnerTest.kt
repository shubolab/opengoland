package dev.openidea.goide.semantic

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class GoCommandEditOwnerTest {
    @Test fun cancellingCommandCancelsItsOutstandingServerPreviews() {
        val owner = GoCommandEditOwner()
        val first = owner.track(CompletableFuture<Boolean>())
        val second = owner.track(CompletableFuture<Boolean>())
        owner.close()
        assertTrue(first.isCancelled)
        assertTrue(second.isCancelled)
        assertFalse(first.complete(true))
    }

    @Test fun lateServerEditCannotEscapeClosedCommand() {
        val owner = GoCommandEditOwner()
        owner.close()
        assertTrue(owner.track(CompletableFuture<Boolean>()).isCancelled)
        owner.close()
    }

    @Test fun completedAtomicEditRemainsCompleted() {
        val owner = GoCommandEditOwner()
        val completed = owner.track(CompletableFuture.completedFuture(true))
        owner.close()
        assertTrue(completed.join())
        assertFalse(completed.isCancelled)
    }

    @Test fun cancellationAndRegistrationRaceHasNoUnownedFuture() {
        repeat(100) {
            val owner = GoCommandEditOwner()
            val future = CompletableFuture<Boolean>()
            val start = CountDownLatch(1)
            val worker = thread { start.await(); owner.track(future) }
            start.countDown(); owner.close(); worker.join(3000)
            assertFalse(worker.isAlive)
            assertTrue(future.isCancelled)
        }
    }

    @Test fun serverEditsAreSerializedAndObserveTheCommittedBaseline() {
        val owner = GoCommandEditOwner()
        val first = CompletableFuture<Int>()
        var baseline = 0
        val result1 = owner.enqueue { first.thenApply { baseline = it; it } }
        val result2 = owner.enqueue { CompletableFuture.completedFuture(baseline + 1) }
        assertFalse(result2.isDone)
        first.complete(7)
        assertEquals(7, result1.join())
        assertEquals(8, result2.join())
        owner.close()
    }

    @Test fun cancelledQueueNeverStartsItsNextServerEdit() {
        val owner = GoCommandEditOwner()
        val first = CompletableFuture<Int>()
        owner.enqueue { first }
        var invoked = false
        val next = owner.enqueue { invoked = true; CompletableFuture.completedFuture(2) }
        owner.close()
        assertTrue(next.isCancelled)
        assertFalse(invoked)
    }

    @Test fun closingDuringQueuedFutureHandoffCannotMissTheNewPreview() {
        val owner = GoCommandEditOwner()
        val first = CompletableFuture<Int>()
        val child = CompletableFuture<Int>()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closing = CountDownLatch(1)
        val closed = CountDownLatch(1)
        owner.enqueue { first }
        val next = owner.enqueue { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)); child }
        val completing = thread { first.complete(1) }
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        val cancelling = thread { closing.countDown(); owner.close(); closed.countDown() }
        assertTrue(closing.await(3, TimeUnit.SECONDS))
        try { assertFalse(closed.await(50, TimeUnit.MILLISECONDS)) }
        finally { release.countDown() }
        completing.join(3000); cancelling.join(3000)
        assertFalse(completing.isAlive)
        assertFalse(cancelling.isAlive)
        assertTrue(child.isCancelled)
        assertTrue(next.isCancelled)
    }
}
