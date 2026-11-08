package dev.openidea.goide.refactor

import org.eclipse.lsp4j.ApplyWorkspaceEditResponse
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GoWorkspaceEditJournalTest {
    @Test fun anInjectedFilesystemFailureRollsBackOnlyExecutedStepsInReverseOrder() {
        val root = Files.createTempDirectory("opengoland-edit-rollback-")
        try {
            val original = Files.writeString(root.resolve("original.go"), "original")
            val untouched = Files.writeString(root.resolve("later.go"), "must stay")
            val laterStamp = Files.getLastModifiedTime(untouched)
            val renamed = root.resolve("moved.go")
            val created = root.resolve("created.go")
            val journal = GoWorkspaceEditJournal()
            assertFailsWith<IOException> {
                try {
                    journal.record { Files.writeString(original, "original") }
                    Files.writeString(original, "edited")
                    journal.record { Files.move(renamed, original) }
                    Files.move(original, renamed)
                    Files.writeString(created, "new")
                    journal.record { Files.deleteIfExists(created) }
                    throw IOException("injected before touching later.go")
                } catch (error: IOException) { journal.rollback(); throw error }
            }
            assertEquals("original", Files.readString(original))
            assertFalse(Files.exists(renamed))
            assertFalse(Files.exists(created))
            assertEquals("must stay", Files.readString(untouched))
            assertEquals(laterStamp, Files.getLastModifiedTime(untouched))
        } finally { Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }

    @Test fun rollbackAttemptsEveryInverseAndReportsRestorationFailures() {
        val order = mutableListOf<Int>()
        val journal = GoWorkspaceEditJournal()
        journal.record { order += 1; throw IOException("first") }
        journal.record { order += 2 }
        journal.record { order += 3; throw IOException("third") }
        val error = assertFailsWith<IOException> { journal.rollback() }
        assertEquals(listOf(3, 2, 1), order)
        assertEquals("third", error.message)
        assertEquals("first", error.suppressed.single().message)
    }

    @Test fun cancellationAndAtomicCommitCannotBothWinTheirRace() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            repeat(30) {
                val future = GoWorkspaceEditFuture()
                val barrier = CyclicBarrier(2)
                val commit = executor.submit<Boolean> { barrier.await(2, TimeUnit.SECONDS); future.beginCommit() }
                val cancel = executor.submit<Boolean> { barrier.await(2, TimeUnit.SECONDS); future.cancel(true) }
                val committed = commit.get(3, TimeUnit.SECONDS)
                val cancelled = cancel.get(3, TimeUnit.SECONDS)
                assertTrue(committed.xor(cancelled))
                if (committed) { assertFalse(future.isCancelled); future.complete(ApplyWorkspaceEditResponse(true)); assertTrue(future.get().isApplied) }
                else assertTrue(future.isCancelled)
            }
        } finally { executor.shutdownNow() }
    }
}
