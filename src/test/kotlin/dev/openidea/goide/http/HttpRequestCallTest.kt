package dev.openidea.goide.http

import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

internal class LocalHttpFixture : AutoCloseable {
    val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val executor = Executors.newCachedThreadPool()
    val base: String get() = "http://127.0.0.1:${server.address.port}"
    init { server.executor = executor; server.start() }
    fun route(path: String, handler: (HttpExchange) -> Unit) { server.createContext(path) { exchange ->
        try { handler(exchange) } catch (_: IOException) { /* Expected when the client cancels a streaming body. */ }
        finally { exchange.close() }
    } }
    override fun close() { server.stop(0); executor.shutdownNow(); check(executor.awaitTermination(5, TimeUnit.SECONDS)) }
}

class HttpRequestCallTest {
    @Test fun nativeGoLandBodyBoundaryVectorsMatchActualOutgoingBytes() = LocalHttpFixture().use { fixture ->
        val resource = javaClass.classLoader.getResourceAsStream("http-body-reference-20261113.json")!!
        val oracle = resource.bufferedReader(Charsets.UTF_8).use { JsonParser.parseString(it.readText()).asJsonObject }
        fixture.route("/echo") { exchange ->
            val bytes = exchange.requestBody.readAllBytes()
            exchange.responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
            exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.write(bytes)
        }
        val document = oracle.get("document").asString.replace("http://127.0.0.1:18081", fixture.base)
        for (item in oracle.getAsJsonArray("observations")) {
            val observed = item.asJsonObject
            val name = observed.get("probe").asString
            val marker = document.indexOf("X-Probe: $name\n")
            assertTrue(marker >= 0)
            val request = HttpRequestDocument.requestAt(document, marker)
            val response = HttpRequestCall(request).use { it.await() }
            assertContentEquals(observed.get("body").asString.toByteArray(Charsets.UTF_8), response.body, name)
            assertEquals(observed.get("utf8Bytes").asInt, response.body.size, name)
        }
    }

    @Test fun originalUnicodeExampleMatchesTheReference39WireBytes() = LocalHttpFixture().use { fixture ->
        val resource = javaClass.classLoader.getResourceAsStream("http-body-reference-20261113.json")!!
        val oracle = resource.bufferedReader(Charsets.UTF_8).use { JsonParser.parseString(it.readText()).asJsonObject.getAsJsonObject("unicode") }
        fixture.route("/echo") { exchange ->
            val bytes = exchange.requestBody.readAllBytes()
            exchange.responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
            exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.write(bytes)
        }
        val document = oracle.get("document").asString.replace("http://127.0.0.1:18081", fixture.base)
        val response = HttpRequestCall(HttpRequestDocument.requestAt(document, document.indexOf("POST"))).use { it.await() }
        assertContentEquals(oracle.get("expectedBody").asString.toByteArray(Charsets.UTF_8), response.body)
        assertEquals(39, response.body.size)
    }

    @Test fun sendsUtf8PostWithHeadersAndReturnsRealErrorStatus() = LocalHttpFixture().use { fixture ->
        val captured = AtomicReference<String>()
        fixture.route("/echo") { exchange ->
            captured.set("${exchange.requestMethod}|${exchange.requestHeaders.getFirst("X-Probe")}|${String(exchange.requestBody.readAllBytes(), Charsets.UTF_8)}")
            val bytes = "{\"message\":\"你好😀\"}".toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json; charset=UTF-8")
            exchange.sendResponseHeaders(422, bytes.size.toLong()); exchange.responseBody.write(bytes)
        }
        val request = HttpRequestDocument.requestAt("POST ${fixture.base}/echo\nX-Probe: native\nContent-Type: application/json\n\n你好😀", 0)
        val response = HttpRequestCall(request).use { it.await() }
        assertEquals("POST|native|你好😀", captured.get())
        assertEquals(422, response.status)
        assertEquals("{\"message\":\"你好😀\"}", response.displayBody())
    }

    @Test fun doesNotFollowRedirectsOrAcquireCookies() = LocalHttpFixture().use { fixture ->
        val redirected = AtomicInteger()
        fixture.route("/redirect") { exchange ->
            exchange.responseHeaders.add("Location", "${fixture.base}/target")
            exchange.responseHeaders.add("Set-Cookie", "session=secret")
            exchange.sendResponseHeaders(302, -1)
        }
        fixture.route("/target") { exchange -> redirected.incrementAndGet(); exchange.sendResponseHeaders(204, -1) }
        val cookie = AtomicReference<String?>()
        fixture.route("/cookie-probe") { exchange -> cookie.set(exchange.requestHeaders.getFirst("Cookie")); exchange.sendResponseHeaders(204, -1) }
        val response = HttpRequestCall(HttpRequestDocument.requestAt("GET ${fixture.base}/redirect", 0)).use { it.await() }
        assertEquals(302, response.status); assertEquals(0, redirected.get())
        HttpRequestCall(HttpRequestDocument.requestAt("GET ${fixture.base}/cookie-probe", 0)).use { it.await() }
        assertEquals(null, cookie.get())
    }

    @Test fun boundsChunkedResponsesWithoutContentLength() = LocalHttpFixture().use { fixture ->
        fixture.route("/large") { exchange -> exchange.sendResponseHeaders(200, 0); exchange.responseBody.write(ByteArray(8192)) }
        val call = HttpRequestCall(HttpRequestDocument.requestAt("GET ${fixture.base}/large", 0), maxResponseBytes = 128)
        val failure = call.use { assertFailsWith<ExecutionException> { it.await() } }
        assertTrue(failure.cause!!.message!!.contains("128-byte limit"))
    }

    @Test fun cancellationClosesAnActiveStreamingExchange() = LocalHttpFixture().use { fixture ->
        val received = CountDownLatch(1); val disconnected = CountDownLatch(1)
        fixture.route("/stream") { exchange ->
            exchange.sendResponseHeaders(200, 0)
            try {
                repeat(1000) { exchange.responseBody.write(ByteArray(1024)); exchange.responseBody.flush(); received.countDown(); Thread.sleep(10) }
            } catch (_: IOException) { disconnected.countDown() }
        }
        val call = HttpRequestCall(HttpRequestDocument.requestAt("GET ${fixture.base}/stream", 0))
        val pending = CompletableFuture.supplyAsync { call.await() }
        try {
            assertTrue(received.await(5, TimeUnit.SECONDS))
            call.close()
            assertFailsWith<ExecutionException> { pending.get(3, TimeUnit.SECONDS) }
            assertTrue(disconnected.await(5, TimeUnit.SECONDS), "Cancel must close the real socket, not only mark the UI finished")
        } finally { call.close() }
    }

    @Test fun deadlineIncludesBodyAfterResponseHeaders(): Unit = LocalHttpFixture().use { fixture ->
        val received = CountDownLatch(1); val release = CountDownLatch(1)
        fixture.route("/stall") { exchange ->
            exchange.sendResponseHeaders(200, 0); exchange.responseBody.write(1); exchange.responseBody.flush(); received.countDown()
            release.await(5, TimeUnit.SECONDS)
        }
        val call = HttpRequestCall(HttpRequestDocument.requestAt("GET ${fixture.base}/stall", 0), Duration.ofSeconds(1))
        try {
            assertTrue(received.await(5, TimeUnit.SECONDS))
            call.use { assertFailsWith<TimeoutException> { it.await() } }
        } finally { release.countDown(); call.close() }
    }

    @Test fun respectsResponseCharsetAndDoesNotPrintBinaryAsText() = LocalHttpFixture().use { fixture ->
        fixture.route("/latin") { exchange ->
            val bytes = "café".toByteArray(Charsets.ISO_8859_1)
            exchange.responseHeaders.add("Content-Type", "text/plain; charset=ISO-8859-1")
            exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.write(bytes)
        }
        val response = HttpRequestCall(HttpRequestDocument.requestAt("GET ${fixture.base}/latin", 0)).use { it.await() }
        assertEquals("café", response.displayBody())
        assertTrue(response.copy(headers = mapOf("content-type" to listOf("image/png"))).displayBody().startsWith("[Binary response:"))
    }
}
