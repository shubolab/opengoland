package dev.openidea.goide.refactor

import org.eclipse.lsp4j.ApplyWorkspaceEditResponse
import java.util.concurrent.CompletableFuture

/** Cancellation can win before the write command; an atomic native command cannot be cancelled halfway. */
internal class GoWorkspaceEditFuture : CompletableFuture<ApplyWorkspaceEditResponse>() {
    private var committing = false
    @Synchronized fun beginCommit(): Boolean {
        if (isDone) return false
        committing = true
        return true
    }
    @Synchronized override fun cancel(mayInterruptIfRunning: Boolean): Boolean = if (committing) false else super.cancel(mayInterruptIfRunning)
}

/** Only completed/begun native operations are journalled, so future untouched resources stay intact. */
internal class GoWorkspaceEditJournal {
    private val inverse = ArrayDeque<() -> Unit>()
    fun record(rollback: () -> Unit) { inverse.addLast(rollback) }
    fun rollback() {
        var failure: Throwable? = null
        while (inverse.isNotEmpty()) {
            try { inverse.removeLast().invoke() }
            catch (error: Throwable) { if (failure == null) failure = error else failure.addSuppressed(error) }
        }
        failure?.let { throw it }
    }
}
