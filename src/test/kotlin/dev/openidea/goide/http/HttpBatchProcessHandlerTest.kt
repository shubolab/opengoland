package dev.openidea.goide.http

import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.testFramework.LightPlatformTestCase
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.openapi.util.Key
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class HttpBatchProcessHandlerTest : LightPlatformTestCase() {
    private class Probe : HttpBatchObserver {
        val started = CopyOnWriteArrayList<String>()
        val responses = CopyOnWriteArrayList<Pair<String, HttpRequestResponse>>()
        val failures = CopyOnWriteArrayList<Pair<String, String>>()
        val cancelled = CopyOnWriteArrayList<String>()
        val finished = AtomicInteger()
        val terminated = AtomicInteger()
        val cancelledAtTermination = AtomicInteger(-1)
        val events = CopyOnWriteArrayList<String>()
        val output = StringBuffer()
        var onStarted: ((HttpRequestEntry) -> Unit)? = null
        var onResponded: ((HttpRequestEntry) -> Unit)? = null
        var onCancelled: ((HttpRequestEntry) -> Unit)? = null

        override fun started(entry: HttpRequestEntry, method: String, uri: String) {
            started += entry.displayName
            onStarted?.invoke(entry)
        }
        override fun responded(entry: HttpRequestEntry, response: HttpRequestResponse) {
            responses += entry.displayName to response
            onResponded?.invoke(entry)
        }
        override fun failed(entry: HttpRequestEntry, message: String) { failures += entry.displayName to message }
        override fun cancelled(entry: HttpRequestEntry) {
            cancelled += entry.displayName
            onCancelled?.invoke(entry)
        }

        fun handler(plan: HttpRequestPlan, entries: List<HttpRequestEntry> = plan.entries,
                    includeResponseDetailInConsole: Boolean = true): HttpBatchProcessHandler {
            val handler = HttpBatchProcessHandler(plan, entries, this, includeResponseDetailInConsole) {
                events += "finished"
                finished.incrementAndGet()
            }
            handler.addProcessListener(object : ProcessAdapter() {
                override fun startNotified(event: ProcessEvent) { events += "start" }
                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) { output.append(event.text) }
                override fun processTerminated(event: ProcessEvent) {
                    events += "terminated"
                    cancelledAtTermination.set(cancelled.size)
                    terminated.incrementAndGet()
                }
            })
            return handler
        }
    }

    private fun awaitFinished(handler: HttpBatchProcessHandler, probe: Probe) {
        PlatformTestUtil.waitWithEventsDispatching("Batch must drain and terminate", {
            handler.isProcessTerminated && probe.finished.get() == 1
        }, 10)
        assertEquals(1, probe.terminated.get())
    }

    private fun dispatchFor(milliseconds: Long) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(milliseconds)
        PlatformTestUtil.waitWithEventsDispatching("Dispatch pending work", { System.nanoTime() >= deadline }, 3)
    }

    fun testRequestsAreStrictlySequentialAndUseSelectedSnapshotOrder() = LocalHttpFixture().use { fixture ->
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val lastHits = AtomicInteger()
        fixture.route("/first") { exchange ->
            firstEntered.countDown()
            releaseFirst.await()
            val body = "first 你好😀".toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, body.size.toLong()); exchange.responseBody.write(body)
        }
        fixture.route("/last") { exchange -> lastHits.incrementAndGet(); exchange.sendResponseHeaders(204, -1) }
        val plan = HttpRequestDocument.plan("### first\nGET ${fixture.base}/first\n### last\nGET ${fixture.base}/last")
        val probe = Probe(); val handler = probe.handler(plan)
        try {
            handler.startNotify()
            PlatformTestUtil.waitWithEventsDispatching("First request must reach the real server", { firstEntered.count == 0L }, 5)
            dispatchFor(200)
            assertEquals(0, lastHits.get())
            assertEquals(listOf("first"), probe.started.toList())
            releaseFirst.countDown()
            awaitFinished(handler, probe)
            assertEquals(0, handler.exitCode)
            assertEquals(1, lastHits.get())
            assertEquals(listOf("first", "last"), probe.responses.map { it.first })
            assertEquals("first 你好😀", String(probe.responses.first().second.body, Charsets.UTF_8))
            assertTrue(probe.output.toString().contains("first 你好😀"))
        } finally { releaseFirst.countDown(); handler.destroyProcess() }
    }

    fun testHttpErrorStatusAndNetworkAndParseFailuresDoNotSkipLaterRequests() = LocalHttpFixture().use { fixture ->
        val lastHits = AtomicInteger()
        fixture.route("/unavailable") { exchange -> exchange.sendResponseHeaders(503, -1) }
        fixture.route("/disconnect") { exchange -> exchange.close() }
        fixture.route("/last") { exchange -> lastHits.incrementAndGet(); exchange.sendResponseHeaders(204, -1) }
        val plan = HttpRequestDocument.plan("""
            ### unavailable
            GET ${fixture.base}/unavailable
            ### network
            GET ${fixture.base}/disconnect
            ### missing
            GET {{undefined}}/never
            ### last
            GET ${fixture.base}/last
        """.trimIndent())
        val probe = Probe(); val handler = probe.handler(plan)
        try {
            handler.startNotify(); awaitFinished(handler, probe)
            assertEquals(1, handler.exitCode)
            assertEquals(listOf("unavailable" to 503, "last" to 204), probe.responses.map { it.first to it.second.status })
            assertEquals(listOf("network", "missing"), probe.failures.map { it.first })
            assertTrue(probe.failures.last().second.contains("Undefined HTTP variable"))
            assertEquals(1, lastHits.get())
            assertTrue(probe.cancelled.isEmpty())
        } finally { handler.destroyProcess() }
    }

    fun testStopAllClosesActiveStreamAndNeverSendsTheQueuedRequest() = LocalHttpFixture().use { fixture ->
        val streaming = CountDownLatch(1); val disconnected = CountDownLatch(1)
        val lastHits = AtomicInteger()
        fixture.route("/stream") { exchange ->
            exchange.sendResponseHeaders(200, 0)
            try {
                repeat(2500) {
                    exchange.responseBody.write(ByteArray(1024)); exchange.responseBody.flush()
                    streaming.countDown(); Thread.sleep(10)
                }
            } catch (_: IOException) { disconnected.countDown() }
        }
        fixture.route("/last") { exchange -> lastHits.incrementAndGet(); exchange.sendResponseHeaders(204, -1) }
        val plan = HttpRequestDocument.plan("### stream\nGET ${fixture.base}/stream\n### last\nGET ${fixture.base}/last")
        val probe = Probe(); val handler = probe.handler(plan)
        try {
            handler.startNotify()
            PlatformTestUtil.waitWithEventsDispatching("First response must be streaming", { streaming.count == 0L }, 5)
            handler.destroyProcess(); awaitFinished(handler, probe)
            PlatformTestUtil.waitWithEventsDispatching("Stop All must close the real socket", { disconnected.count == 0L }, 5)
            dispatchFor(200)
            assertEquals(130, handler.exitCode)
            assertEquals(0, lastHits.get())
            assertEquals(listOf("stream", "last"), probe.cancelled.toList())
            assertEquals(2, probe.cancelledAtTermination.get())
            assertTrue(probe.responses.isEmpty()); assertTrue(probe.failures.isEmpty())
        } finally { handler.destroyProcess() }
    }

    fun testCancelBeforeStartDrainsExactlyOnceWithoutSendingAnything() = LocalHttpFixture().use { fixture ->
        val hits = AtomicInteger()
        fixture.route("/never") { exchange -> hits.incrementAndGet(); exchange.sendResponseHeaders(204, -1) }
        val plan = HttpRequestDocument.plan("### first\nGET ${fixture.base}/never\n### last\nGET ${fixture.base}/never")
        val probe = Probe(); val handler = probe.handler(plan)
        handler.destroyProcess()
        awaitFinished(handler, probe)
        handler.startNotify(); handler.destroyProcess(); handler.detachProcess()
        dispatchFor(200)
        assertEquals(130, handler.exitCode)
        assertEquals(0, hits.get())
        assertTrue(probe.started.isEmpty())
        assertEquals(listOf("first", "last"), probe.cancelled.toList())
        assertEquals(1, probe.finished.get()); assertEquals(1, probe.terminated.get())
        assertEquals(listOf("start", "terminated", "finished"), probe.events.toList())
    }

    fun testCancelFromStartedObserverPreventsTheFirstNetworkExchange() = LocalHttpFixture().use { fixture ->
        val hits = AtomicInteger()
        fixture.route("/never") { exchange -> hits.incrementAndGet(); exchange.sendResponseHeaders(204, -1) }
        val plan = HttpRequestDocument.plan("### first\nGET ${fixture.base}/never\n### last\nGET ${fixture.base}/never")
        val probe = Probe(); val handler = probe.handler(plan)
        probe.onStarted = { handler.destroyProcess() }
        handler.startNotify(); awaitFinished(handler, probe)
        assertEquals(130, handler.exitCode)
        assertEquals(0, hits.get())
        assertEquals(listOf("first"), probe.started.toList())
        assertEquals(listOf("first", "last"), probe.cancelled.toList())
    }

    fun testCancelBetweenRequestsPreservesTheCompletedResultAndDoesNotStartNext() = LocalHttpFixture().use { fixture ->
        val firstHits = AtomicInteger(); val lastHits = AtomicInteger()
        fixture.route("/first") { exchange -> firstHits.incrementAndGet(); exchange.sendResponseHeaders(204, -1) }
        fixture.route("/last") { exchange -> lastHits.incrementAndGet(); exchange.sendResponseHeaders(204, -1) }
        val plan = HttpRequestDocument.plan("### first\nGET ${fixture.base}/first\n### last\nGET ${fixture.base}/last")
        val probe = Probe(); val handler = probe.handler(plan)
        probe.onResponded = { handler.destroyProcess() }
        handler.startNotify(); awaitFinished(handler, probe)
        assertEquals(130, handler.exitCode)
        assertEquals(1, firstHits.get()); assertEquals(0, lastHits.get())
        assertEquals(listOf("first"), probe.responses.map { it.first })
        assertEquals(listOf("last"), probe.cancelled.toList())
    }

    fun testRerunUsesANewHandlerAndEachHandlerFinishesOnlyOnce() = LocalHttpFixture().use { fixture ->
        val hits = AtomicInteger()
        fixture.route("/run") { exchange ->
            val body = "run-${hits.incrementAndGet()}".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong()); exchange.responseBody.write(body)
        }
        val plan = HttpRequestDocument.plan("GET ${fixture.base}/run")
        val first = Probe(); val firstHandler = first.handler(plan)
        firstHandler.startNotify(); awaitFinished(firstHandler, first)
        firstHandler.startNotify()
        val second = Probe(); val secondHandler = second.handler(plan)
        secondHandler.startNotify(); awaitFinished(secondHandler, second)
        firstHandler.destroyProcess(); secondHandler.destroyProcess(); dispatchFor(100)
        assertEquals(2, hits.get())
        assertEquals("run-1", String(first.responses.single().second.body))
        assertEquals("run-2", String(second.responses.single().second.body))
        assertEquals(0, firstHandler.exitCode); assertEquals(0, secondHandler.exitCode)
        assertEquals(1, first.finished.get()); assertEquals(1, second.finished.get())
        assertEquals(1, first.terminated.get()); assertEquals(1, second.terminated.get())
    }

    fun testSelectedEntriesDoNotSendOtherRequestsInThePlan() = LocalHttpFixture().use { fixture ->
        val firstHits = AtomicInteger(); val lastHits = AtomicInteger()
        fixture.route("/first") { exchange -> firstHits.incrementAndGet(); exchange.sendResponseHeaders(204, -1) }
        fixture.route("/last") { exchange -> lastHits.incrementAndGet(); exchange.sendResponseHeaders(204, -1) }
        val plan = HttpRequestDocument.plan("### first\nGET ${fixture.base}/first\n### last\nGET ${fixture.base}/last")
        val probe = Probe(); val handler = probe.handler(plan, listOf(plan.entries.last()))
        handler.startNotify(); awaitFinished(handler, probe)
        assertEquals(0, handler.exitCode)
        assertEquals(0, firstHits.get()); assertEquals(1, lastHits.get())
        assertEquals(listOf("last"), probe.responses.map { it.first })
    }

    fun testBodyLimitFailureStillRunsTheFollowingRequest() = LocalHttpFixture().use { fixture ->
        val lastHits = AtomicInteger()
        fixture.route("/large") { exchange ->
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.write(ByteArray(1024 * 1024 + 1))
        }
        fixture.route("/last") { exchange -> lastHits.incrementAndGet(); exchange.sendResponseHeaders(204, -1) }
        val plan = HttpRequestDocument.plan("### large\nGET ${fixture.base}/large\n### last\nGET ${fixture.base}/last")
        val probe = Probe(); val handler = probe.handler(plan)
        handler.startNotify(); awaitFinished(handler, probe)
        assertEquals(1, handler.exitCode)
        assertEquals(listOf("large"), probe.failures.map { it.first })
        assertTrue(probe.failures.single().second.contains("byte limit"))
        assertEquals(1, lastHits.get())
        assertEquals(listOf("last"), probe.responses.map { it.first })
    }

    fun testThrowingCancellationObserverCannotPreventRemainingDrainOrFinish() = LocalHttpFixture().use { fixture ->
        val hits = AtomicInteger()
        fixture.route("/never") { exchange -> hits.incrementAndGet(); exchange.sendResponseHeaders(204, -1) }
        val plan = HttpRequestDocument.plan("### first\nGET ${fixture.base}/never\n### last\nGET ${fixture.base}/never")
        val probe = Probe(); val handler = probe.handler(plan)
        probe.onCancelled = { throw IllegalStateException("owned observer failure") }
        handler.destroyProcess(); awaitFinished(handler, probe)
        assertEquals(130, handler.exitCode)
        assertEquals(0, hits.get())
        assertEquals(listOf("first", "last"), probe.cancelled.toList())
        assertEquals(2, probe.cancelledAtTermination.get())
        assertEquals(listOf("start", "terminated", "finished"), probe.events.toList())
    }

    fun testConsoleLimitKeepsFullUnicodeResponseAndStillRunsLaterRequests() = LocalHttpFixture().use { fixture ->
        val body = "😀".repeat(256 * 1024).toByteArray(Charsets.UTF_8) // Exactly the existing 1 MiB wire-body limit.
        val lastHits = AtomicInteger()
        fixture.route("/large-unicode") { exchange ->
            exchange.responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
            exchange.sendResponseHeaders(200, body.size.toLong()); exchange.responseBody.write(body)
        }
        fixture.route("/last") { exchange -> lastHits.incrementAndGet(); exchange.sendResponseHeaders(204, -1) }
        val plan = HttpRequestDocument.plan("### large\nGET ${fixture.base}/large-unicode\n### last\nGET ${fixture.base}/last")
        val probe = Probe(); val handler = probe.handler(plan)
        handler.startNotify(); awaitFinished(handler, probe)
        assertEquals(0, handler.exitCode)
        assertEquals(1, lastHits.get())
        assertTrue(probe.responses.first().second.body.contentEquals(body))
        assertEquals(listOf("large" to 200, "last" to 204), probe.responses.map { it.first to it.second.status })
        val output = probe.output.toString()
        assertTrue(output.length <= 512 * 1024)
        assertTrue(output.length > 500 * 1024)
        assertEquals(1, output.split("HTTP console output limit reached").size - 1)
        for (index in output.indices) {
            if (output[index].isHighSurrogate()) assertTrue(index + 1 < output.length && output[index + 1].isLowSurrogate())
            if (output[index].isLowSurrogate()) assertTrue(index > 0 && output[index - 1].isHighSurrogate())
        }
    }

    fun testSummaryOnlyConsoleOmitsPrivateResponseDetailButKeepsStatus() = LocalHttpFixture().use { fixture ->
        val body = "{\"secret\":\"private response 你好😀\"}".toByteArray(Charsets.UTF_8)
        fixture.route("/private") { exchange ->
            exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
            exchange.responseHeaders.add("X-Private", "secret response header")
            exchange.sendResponseHeaders(200, body.size.toLong()); exchange.responseBody.write(body)
        }
        val plan = HttpRequestDocument.plan("### private\nGET ${fixture.base}/private")
        val probe = Probe(); val handler = probe.handler(plan, includeResponseDetailInConsole = false)
        handler.startNotify(); awaitFinished(handler, probe)
        assertEquals(0, handler.exitCode)
        assertTrue(probe.responses.single().second.body.contentEquals(body))
        assertTrue(probe.responses.single().second.headers.entries.any {
            it.key.equals("X-Private", true) && it.value == listOf("secret response header")
        })
        val output = probe.output.toString()
        assertTrue(output.contains("GET ${fixture.base}/private")); assertTrue(output.contains("HTTP 200"))
        assertFalse(output.contains("private response 你好😀")); assertFalse(output.contains("secret response header"))
    }
}
