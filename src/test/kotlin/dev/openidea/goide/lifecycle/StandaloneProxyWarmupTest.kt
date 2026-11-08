package dev.openidea.goide.lifecycle

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StandaloneProxyWarmupTest {
    @Test fun startupDoesNotContinueUntilProxyServiceInitializationCompletes() = runBlocking { withTimeout(10_000) {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val timeline = CopyOnWriteArrayList<String>()
        val context = RecordingContext("OpenGoland", timeline) {
            entered.complete(Unit)
            release.await()
            timeline += "proxy-initialized"
        }
        val startup = async(Dispatchers.Default) {
            StandaloneProxyWarmup(context).execute()
            timeline += "application-starter"
        }
        entered.await()
        assertFalse(startup.isCompleted, "Application startup must remain behind the service initialization barrier")
        assertEquals(listOf("starting"), timeline.toList())
        release.complete(Unit)
        startup.await()
        assertEquals(listOf("starting", "proxy-initialized", "completed", "application-starter"), timeline.toList())
    } }

    @Test fun anotherIdeOrDevelopmentSandboxDoesNotInitializeOrLogAnything() = runBlocking {
        for (vendor in listOf(null, "", "JetBrains", "OpenIDEA")) {
            val timeline = CopyOnWriteArrayList<String>()
            val context = RecordingContext(vendor, timeline) { error("Another IDE must not be changed") }
            StandaloneProxyWarmup(context).execute()
            assertTrue(timeline.isEmpty(), "Unexpected startup side effects for vendor $vendor")
        }
    }

    @Test fun initializationFailurePropagatesAndCannotBeReportedAsCompleted() {
        val timeline = CopyOnWriteArrayList<String>()
        val failure = IllegalStateException("fixture initialization failed")
        val context = RecordingContext("OpenGoland", timeline) { throw failure }
        val actual = runCatching { runBlocking { StandaloneProxyWarmup(context).execute() } }.exceptionOrNull()
        assertTrue(actual is IllegalStateException)
        assertEquals(failure.message, actual.message)
        assertEquals(listOf("starting"), timeline.toList())
    }

    @Test fun cancelledStartupCancelsTheAwaitedServiceWithoutACompletionMarker() = runBlocking { withTimeout(10_000) {
        val entered = CompletableDeferred<Unit>()
        val neverReady = CompletableDeferred<Unit>()
        val timeline = CopyOnWriteArrayList<String>()
        val context = RecordingContext("OpenGoland", timeline) { entered.complete(Unit); neverReady.await() }
        val startup = launch(Dispatchers.Default) { StandaloneProxyWarmup(context).execute() }
        entered.await()
        startup.cancelAndJoin()
        assertTrue(startup.isCancelled)
        assertEquals(listOf("starting"), timeline.toList())
    } }

    @Test fun evenAnEdtCallerPerformsTheProxyWorkOffTheEdt() {
        val timeline = CopyOnWriteArrayList<String>()
        val context = RecordingContext("OpenGoland", timeline) { assertFalse(SwingUtilities.isEventDispatchThread()) }
        SwingUtilities.invokeAndWait { runBlocking { StandaloneProxyWarmup(context).execute() } }
        assertEquals(listOf("starting", "completed"), timeline.toList())
    }

    private class RecordingContext(
        private val vendor: String?, private val timeline: MutableList<String>,
        private val initialize: suspend () -> Unit,
    ) : ProxyWarmupContext {
        override fun vendorName() = vendor
        override fun started() { timeline += "starting" }
        override suspend fun initializeProxy() { initialize() }
        override fun completed() { timeline += "completed" }
    }
}
