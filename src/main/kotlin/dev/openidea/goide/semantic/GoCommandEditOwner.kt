package dev.openidea.goide.semantic

import java.util.concurrent.CompletableFuture

/** An executeCommand owns every applyEdit request it triggered, including an open preview. */
internal class GoCommandEditOwner {
    private var closed = false
    private val pending = mutableSetOf<CompletableFuture<*>>()
    private var tail = CompletableFuture.completedFuture(Unit)

    fun <T> enqueue(operation: () -> CompletableFuture<T>): CompletableFuture<T> {
        val result = track(CompletableFuture<T>())
        synchronized(this) {
            if (closed) return result
            tail = tail.handle { _, _ -> Unit }.thenCompose {
                // The factory only starts asynchronous work; it must never wait for that work.
                // Close cannot slip between the final check and registration of the child.
                val child = synchronized(this) {
                    if (result.isDone || closed) null
                    else try { track(operation()) } catch (error: Throwable) { CompletableFuture.failedFuture(error) }
                }
                if (child == null) CompletableFuture.completedFuture(Unit)
                else {
                    child.whenComplete { value, error ->
                        if (error == null) result.complete(value) else result.completeExceptionally(error)
                    }
                    result.whenComplete { _, _ -> if (result.isCancelled) child.cancel(true) }
                    child.handle { _, _ -> Unit }
                }
            }
        }
        return result
    }

    fun <T> track(future: CompletableFuture<T>): CompletableFuture<T> {
        val reject = synchronized(this) {
            if (closed) true else { pending += future; false }
        }
        if (reject) future.cancel(true)
        else future.whenComplete { _, _ -> synchronized(this) { pending -= future } }
        return future
    }

    fun close() {
        val values = synchronized(this) { closed = true; pending.toList().also { pending.clear() } }
        values.forEach { it.cancel(true) }
    }
}
