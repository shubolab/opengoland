package dev.openidea.goide.http

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HttpResponseCacheTest {
    private fun response(text: String) = HttpRequestResponse(200,
        mapOf("Content-Type" to listOf("text/plain; charset=UTF-8"), "X-Value" to listOf("€")), text.toByteArray(), 1)

    private fun weight(response: HttpRequestResponse, presentation: HttpResponsePresentation): Long =
        response.body.size + 2L * (presentation.headersText.length + presentation.bodyText.length +
            response.headers.entries.sumOf { it.key.length + it.value.sumOf(String::length) })

    @Test fun budgetCountsRawHeadersAndUtf16PresentationAndOwnsTheSavedBytes() {
        val cache = HttpResponseCache()
        cache.registerOwner("first") {}
        val response = response("你好😀")
        val original = response.body.copyOf()
        val presentation = HttpResponsePresentation.prepare(response)
        assertTrue(cache.put("first", 0, response, presentation))
        assertEquals(weight(response, presentation), cache.retainedBytes)
        response.body.fill(0)
        assertContentEquals(original, assertNotNull(cache.get("first", 0)).response.body)
        cache.removeSession("first")
        assertEquals(0, cache.entryCount)
        assertEquals(0L, cache.retainedBytes)
    }

    @Test fun leastRecentlyUsedResultIsEvictedAndOnlyItsOwnerIsNotified() {
        val response = response("same bytes")
        val presentation = HttpResponsePresentation.prepare(response)
        val cache = HttpResponseCache(weight(response, presentation) * 2)
        var firstEvictions = 0
        var secondEvictions = 0
        cache.registerOwner("first") { firstEvictions++ }
        cache.registerOwner("second") { secondEvictions++ }
        assertTrue(cache.put("first", 0, response, presentation))
        assertTrue(cache.put("first", 1, response, presentation))
        assertNotNull(cache.get("first", 0))
        assertTrue(cache.put("second", 0, response, presentation))
        assertNull(cache.peek("first", 1))
        assertNotNull(cache.peek("first", 0))
        assertNotNull(cache.peek("second", 0))
        assertEquals(1, firstEvictions)
        assertEquals(0, secondEvictions)
        assertEquals(cache.maxBytes, cache.retainedBytes)
    }

    @Test fun oversizedPresentationAndRawHeadersCannotBypassTheBudget() {
        val cache = HttpResponseCache(1024)
        cache.registerOwner("owner") {}
        val response = response("one")
        val largePresentation = HttpResponsePresentation.prepare(response).copy(bodyText = "x".repeat(1024))
        assertFalse(cache.put("owner", 0, response, largePresentation))
        val largeHeaders = response.copy(headers = mapOf("X-Huge" to listOf("x".repeat(4096))))
        assertFalse(cache.put("owner", 1, largeHeaders, HttpResponsePresentation.prepare(largeHeaders)))
        assertEquals(0, cache.entryCount)
        assertEquals(0L, cache.retainedBytes)
    }

    @Test fun aLateWorkerCannotRepopulateADisposedSession() {
        val cache = HttpResponseCache()
        cache.registerOwner("owner") {}
        val response = response("late response")
        val presentation = HttpResponsePresentation.prepare(response)
        val ready = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(1)
        var accepted = true
        val worker = Thread {
            ready.countDown()
            release.await()
            accepted = cache.put("owner", 0, response, presentation)
            completed.countDown()
        }
        worker.start()
        try {
            assertTrue(ready.await(2, TimeUnit.SECONDS))
            cache.removeSession("owner")
            release.countDown()
            assertTrue(completed.await(2, TimeUnit.SECONDS))
            assertFalse(accepted)
            assertEquals(0, cache.entryCount)
            assertEquals(0L, cache.retainedBytes)
        } finally { release.countDown(); worker.join(2_000) }
    }

    @Test fun clearDropsAllResponsesNotifiesOncePerOwnerAndKeepsLiveRegistration() {
        val cache = HttpResponseCache()
        var evictions = 0
        cache.registerOwner("live") { evictions++ }
        val response = response("body")
        val presentation = HttpResponsePresentation.prepare(response)
        assertTrue(cache.put("live", 0, response, presentation))
        assertTrue(cache.put("live", 1, response, presentation))
        cache.clear()
        assertEquals(0L, cache.retainedBytes)
        assertEquals(0, cache.entryCount)
        assertEquals(1, evictions)
        assertTrue(cache.put("live", 2, response, presentation))
    }
}
