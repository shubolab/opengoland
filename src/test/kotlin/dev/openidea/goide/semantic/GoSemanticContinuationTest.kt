package dev.openidea.goide.semantic

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.testFramework.LightPlatformTestCase
import com.intellij.util.ui.UIUtil
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class GoSemanticContinuationTest : LightPlatformTestCase() {
    override fun runInDispatchThread() = true

    fun testCancellingAfterBackgroundAnalysisQueuesItsUiDropsTheLateContinuation() {
        val indicator = EmptyProgressIndicator()
        var delivered = false
        val work = GoSemanticOperations.get(project)
        // The EDT is occupied by this test while the real application callback is queued.
        CompletableFuture.runAsync { work.later(indicator) { delivered = true } }.get(5, TimeUnit.SECONDS)
        indicator.cancel()
        UIUtil.dispatchAllInvocationEvents()
        assertFalse("Cancelled analysis must not open its queued dialog or conflict UI", delivered)
    }

    fun testLiveContinuationStillRunsOnEdtAndAlreadyCancelledWorkDoesNotRun() {
        val work = GoSemanticOperations.get(project)
        var delivered = 0
        val active = EmptyProgressIndicator()
        val cancelled = EmptyProgressIndicator().apply { cancel() }
        CompletableFuture.runAsync {
            work.later(cancelled) { fail("Already cancelled work cannot show UI") }
            work.later(active) { assertTrue(ApplicationManager.getApplication().isDispatchThread); delivered++ }
        }.get(5, TimeUnit.SECONDS)
        UIUtil.dispatchAllInvocationEvents()
        assertEquals(1, delivered)
    }
}
