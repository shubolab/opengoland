package dev.openidea.goide.http

import com.intellij.openapi.diagnostic.Logger

internal data class CachedHttpResponse(val response: HttpRequestResponse, val presentation: HttpResponsePresentation)

/** Project-owned response storage. Metadata lives in sessions; only these bounded entries retain bodies. */
internal class HttpResponseCache(val maxBytes: Long = 16L * 1024 * 1024) {
    private data class Key(val session: String, val ordinal: Int)
    private data class Value(val content: CachedHttpResponse, val bytes: Long)
    private val lock = Any()
    private val values = LinkedHashMap<Key, Value>()
    private val owners = HashMap<String, () -> Unit>()
    private var retained = 0L

    init { require(maxBytes > 0) }

    val retainedBytes: Long get() = synchronized(lock) { retained }
    val entryCount: Int get() = synchronized(lock) { values.size }

    fun registerOwner(sessionId: String, onEvicted: () -> Unit) = synchronized(lock) {
        check(sessionId !in owners) { "HTTP response owner is already registered." }
        owners[sessionId] = onEvicted
    }

    fun put(sessionId: String, ordinal: Int, response: HttpRequestResponse, presentation: HttpResponsePresentation): Boolean {
        val bytes = weight(response, presentation)
        val notifications = linkedSetOf<String>()
        val callbacks: List<() -> Unit>
        val accepted: Boolean
        synchronized(lock) {
            if (sessionId !in owners) return false
            val key = Key(sessionId, ordinal)
            values.remove(key)?.let { retained -= it.bytes; notifications += sessionId }
            accepted = bytes <= maxBytes
            if (accepted) {
                while (retained > maxBytes - bytes && values.isNotEmpty()) {
                    val first = values.entries.iterator().next()
                    retained -= first.value.bytes
                    notifications += first.key.session
                    values.remove(first.key)
                }
                // Only the cache owns these raw bytes; late caller mutation cannot change a saved response.
                val owned = response.copy(headers = response.headers.mapValues { it.value.toList() }, body = response.body.copyOf())
                values[key] = Value(CachedHttpResponse(owned, presentation), bytes)
                retained += bytes
            }
            callbacks = notifications.mapNotNull { owners[it] }
        }
        callbacks.forEach { callback ->
            try { callback() } catch (failure: Exception) { LOG.warn("HTTP response eviction refresh failed", failure) }
        }
        return accepted
    }

    fun get(sessionId: String, ordinal: Int): CachedHttpResponse? = synchronized(lock) {
        val key = Key(sessionId, ordinal)
        val value = values.remove(key) ?: return@synchronized null
        values[key] = value
        value.content
    }

    fun peek(sessionId: String, ordinal: Int): CachedHttpResponse? = synchronized(lock) { values[Key(sessionId, ordinal)]?.content }

    fun removeSession(sessionId: String) = synchronized(lock) {
        owners.remove(sessionId)
        val iterator = values.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key.session == sessionId) { retained -= entry.value.bytes; iterator.remove() }
        }
    }

    fun clear() {
        val callbacks = synchronized(lock) {
            val affected = values.keys.map { it.session }.toSet()
            values.clear(); retained = 0
            affected.mapNotNull { owners[it] }
        }
        callbacks.forEach { callback ->
            try { callback() } catch (failure: Exception) { LOG.warn("HTTP response clear refresh failed", failure) }
        }
    }

    private fun weight(response: HttpRequestResponse, presentation: HttpResponsePresentation): Long {
        var bytes = response.body.size.toLong() +
            2L * (presentation.headersText.length.toLong() + presentation.bodyText.length)
        for ((name, values) in response.headers) {
            bytes += name.length * 2L
            if (bytes > maxBytes) return bytes
            for (value in values) { bytes += value.length * 2L; if (bytes > maxBytes) return bytes }
        }
        return bytes
    }

    private companion object { val LOG = Logger.getInstance(HttpResponseCache::class.java) }
}
