package dev.openidea.goide.http

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit

internal data class HttpRequestResponse(
    val status: Int,
    val headers: Map<String, List<String>>,
    val body: ByteArray,
    val elapsedMillis: Long,
) {
    fun displayBody(): String {
        val contentType = headers.entries.firstOrNull { it.key.equals("content-type", true) }?.value?.firstOrNull().orEmpty()
        val mime = contentType.substringBefore(';').trim().lowercase()
        if (mime.isNotEmpty() && !mime.startsWith("text/") && !mime.contains("json") && !mime.contains("xml") && mime != "application/x-www-form-urlencoded")
            return "[Binary response: ${body.size} bytes.]"
        val name = Regex("charset\\s*=\\s*\"?([^;\\s\"]+)", RegexOption.IGNORE_CASE).find(contentType)?.groupValues?.get(1)
        val charset = name?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: UTF_8
        return String(body, charset)
    }
}

/** One owned HTTP exchange; cancelling covers headers and streaming bodies, without an EDT wait. */
internal class HttpRequestCall(
    spec: HttpRequestSpec,
    private val timeout: Duration = Duration.ofSeconds(30),
    maxResponseBytes: Int = 1024 * 1024,
) : AutoCloseable {
    private val started = System.nanoTime()
    private val client = HttpClient.newBuilder().connectTimeout(timeout)
        .followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1).build()
    private val future: CompletableFuture<HttpResponse<ByteArray>>

    init {
        try {
            require(maxResponseBytes > 0)
            val builder = HttpRequest.newBuilder(spec.uri).timeout(timeout)
            spec.headers.forEach { (name, value) -> builder.header(name, value) }
            val publisher = if (spec.body.isEmpty()) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofByteArray(spec.body)
            future = client.sendAsync(builder.method(spec.method, publisher).build()) { LimitedBodySubscriber(maxResponseBytes) }
        } catch (failure: Throwable) { client.shutdownNow(); throw failure }
    }

    fun await(): HttpRequestResponse {
        try {
            // Unlike a headers-only timeout, this deadline includes a stalled response body.
            val response = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS)
            return HttpRequestResponse(response.statusCode(), response.headers().map(), response.body(),
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
        } finally { close() }
    }

    override fun close() { future.cancel(true); client.shutdownNow() }

    private class LimitedBodySubscriber(private val limit: Int) : HttpResponse.BodySubscriber<ByteArray> {
        private val result = CompletableFuture<ByteArray>()
        private val bytes = ByteArrayOutputStream()
        private var subscription: Flow.Subscription? = null
        override fun getBody(): CompletionStage<ByteArray> = result
        override fun onSubscribe(value: Flow.Subscription) { subscription = value; value.request(1) }
        override fun onNext(items: List<ByteBuffer>) {
            for (item in items) {
                if (item.remaining() > limit - bytes.size()) {
                    result.completeExceptionally(IOException("HTTP response exceeds the $limit-byte limit."))
                    subscription?.cancel()
                    return
                }
                val chunk = ByteArray(item.remaining()); item.get(chunk); bytes.write(chunk)
            }
            subscription?.request(1)
        }
        override fun onError(error: Throwable) { result.completeExceptionally(error) }
        override fun onComplete() { result.complete(bytes.toByteArray()) }
    }
}
