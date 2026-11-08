package dev.openidea.goide.lsp

import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SemanticTokens
import org.eclipse.lsp4j.SemanticTokensDelta
import org.eclipse.lsp4j.SemanticTokensDeltaParams
import org.eclipse.lsp4j.SemanticTokensParams
import org.eclipse.lsp4j.SemanticTokensRangeParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.eclipse.lsp4j.services.LanguageServer
import org.eclipse.lsp4j.services.TextDocumentService
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicLong
import java.util.UUID

/**
 * gopls bounds each semantic-token request by UTF-8 bytes, including range requests.
 * Preserve its tokens and legend while replacing an oversized request with bounded
 * ranges. No syntax/semantic feature is disabled and the upstream process is untouched.
 */
internal class GoSemanticTokensServer(private val delegate: LanguageServer) : AutoCloseable {
    private data class Document(val text: String, val version: Int, val generation: Long)
    private data class Snapshot(val text: String, val generation: Long, val disk: Path? = null)
    private val documents = mutableMapOf<String, Document>()
    private val invalidDocuments = mutableSetOf<String>()
    private val generations = mutableMapOf<String, Long>()
    private val requests = mutableSetOf<Request>()
    private var closed = false
    private var nextGeneration = 0L
    private val resultIds = AtomicLong()
    private val sessionId = UUID.randomUUID().toString()
    private val remote = delegate.textDocumentService

    private val textService = Proxy.newProxyInstance(TextDocumentService::class.java.classLoader,
        arrayOf(TextDocumentService::class.java)) { proxy, method, supplied ->
        val args = supplied.orEmpty()
        when (method.name) {
            "didOpen" -> changed((args[0] as DidOpenTextDocumentParams).textDocument.uri, method, args) {
                val item = (args[0] as DidOpenTextDocumentParams).textDocument
                Document(item.text, item.version, nextGeneration)
            }
            "didChange" -> changed((args[0] as DidChangeTextDocumentParams).textDocument.uri, method, args) { previous ->
                val params = args[0] as DidChangeTextDocumentParams
                if (previous == null || params.textDocument.version <= previous.version) null
                else runCatching { Document(GoDocumentSync.applyChanges(previous.text, params.contentChanges),
                    params.textDocument.version, nextGeneration) }.getOrNull()
            }
            "didClose" -> changed((args[0] as DidCloseTextDocumentParams).textDocument.uri, method, args) { null }
            "semanticTokensFull" -> {
                val params = args[0] as SemanticTokensParams
                tokens(params.textDocument.uri, null) { remote.semanticTokensFull(params) }
            }
            "semanticTokensRange" -> {
                val params = args[0] as SemanticTokensRangeParams
                tokens(params.textDocument.uri, params.range) { remote.semanticTokensRange(params) }
            }
            "semanticTokensFullDelta" -> {
                val params = args[0] as SemanticTokensDeltaParams
                // A full response is explicitly legal in the delta response union. An
                // aggregate resultId must never be sent to an unrelated range request.
                delta(params)
            }
            "toString" -> "open Goland semantic-token service"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args[0]
            else -> invoke(method, remote, args)
        }
    } as TextDocumentService

    val server: LanguageServer = Proxy.newProxyInstance(LanguageServer::class.java.classLoader,
        arrayOf(LanguageServer::class.java)) { proxy, method, supplied ->
        val args = supplied.orEmpty()
        when (method.name) {
            "getTextDocumentService" -> textService
            "shutdown", "exit" -> { close(); invoke(method, delegate, args) }
            "toString" -> "open Goland gopls service"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args[0]
            else -> invoke(method, delegate, args)
        }
    } as LanguageServer

    private fun changed(uri: String, method: Method, args: Array<out Any?>, update: (Document?) -> Document?): Any? {
        val key = GoDocumentSync.canonicalUri(uri)
        val stale: List<Request>
        val response: Any?
        synchronized(this) {
            if (closed) return null
            ++nextGeneration
            generations[key] = nextGeneration
            val document = update(documents[key])
            if (document == null) documents.remove(key) else documents[key] = document
            if (method.name == "didChange" && document == null) invalidDocuments += key else invalidDocuments -= key
            stale = requests.filter { it.uri == key }.onEach { it.stopped = true }
            // These void RPC calls only enqueue transport messages. Keeping their send
            // under the same gate as range requests preserves notification ordering.
            response = invoke(method, remote, args)
        }
        stale.forEach { it.fail(contentModified()) }
        return response
    }

    private inner class Request(val uri: String, val generation: Long) {
        var stopped = false
        var rpc: CompletableFuture<SemanticTokens>? = null
        val result = object : CompletableFuture<SemanticTokens>() {
            override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
                val child = synchronized(this@GoSemanticTokensServer) { stopped = true; rpc }
                val changed = super.cancel(mayInterruptIfRunning)
                child?.cancel(mayInterruptIfRunning)
                return changed
            }
        }
        fun fail(failure: Throwable) {
            val child = synchronized(this@GoSemanticTokensServer) { stopped = true; rpc }
            result.completeExceptionally(failure)
            child?.cancel(true)
        }
        fun active(): Boolean = !closed && !stopped && !result.isDone && generations.getOrDefault(uri, 0) == generation
    }

    private fun tokens(uri: String, requested: Range?, original: () -> CompletableFuture<SemanticTokens>): CompletableFuture<SemanticTokens> {
        val key = GoDocumentSync.canonicalUri(uri)
        val request: Request
        val opened: Snapshot?
        synchronized(this) {
            if (closed) return CompletableFuture.failedFuture(CancellationException("Go language server closed"))
            if (key in invalidDocuments) return CompletableFuture.failedFuture(contentModified())
            request = Request(key, generations.getOrDefault(key, 0))
            opened = documents[key]?.let { Snapshot(it.text, it.generation) }
            requests += request
        }
        request.result.whenComplete { _, _ -> synchronized(this) { requests.remove(request); request.rpc = null } }
        // Encoding, partitioning and closed-file reads must never occur on the EDT.
        CompletableFuture.runAsync {
            try {
                if (!synchronized(this) { request.active() }) return@runAsync
                val snapshot = opened ?: diskSnapshot(key, request.generation)
                if (snapshot == null) { sendOriginal(request, original); return@runAsync }
                val chunks = GoSemanticTokenChunks.partition(snapshot.text, requested)
                if (chunks.size <= 1) { sendOriginal(request, original); return@runAsync }
                collect(request, snapshot, chunks, 0, mutableListOf())
            } catch (failure: Throwable) { request.fail(failure) }
        }
        return request.result
    }

    private fun delta(params: SemanticTokensDeltaParams): CompletableFuture<Either<SemanticTokens, SemanticTokensDelta>> {
        val full = tokens(params.textDocument.uri, null) { remote.semanticTokensFull(SemanticTokensParams(params.textDocument)) }
        val result = CompletableFuture<Either<SemanticTokens, SemanticTokensDelta>>()
        full.whenComplete { response, failure ->
            if (failure == null) result.complete(Either.forLeft(response)) else result.completeExceptionally(failure)
        }
        result.whenComplete { _, _ -> if (result.isCancelled) full.cancel(true) }
        return result
    }

    private fun diskSnapshot(uri: String, generation: Long): Snapshot? {
        val parsed = URI(uri)
        if (parsed.scheme != "file") return null
        val path = Path.of(parsed)
        return Snapshot(Files.readString(path), generation, path)
    }

    private fun start(request: Request, action: () -> CompletableFuture<SemanticTokens>): CompletableFuture<SemanticTokens>? = synchronized(this) {
        if (!request.active()) return null
        action().also { request.rpc = it }
    }

    private fun sendOriginal(request: Request, original: () -> CompletableFuture<SemanticTokens>) {
        val child = start(request, original) ?: return
        child.whenComplete { response, failure -> finish(request, response, failure) }
    }

    private fun collect(request: Request, snapshot: Snapshot, chunks: List<Range>, index: Int,
                        collected: MutableList<List<Int>>) {
        val child = start(request) { remote.semanticTokensRange(SemanticTokensRangeParams(TextDocumentIdentifier(request.uri), chunks[index])) }
            ?: return
        child.whenCompleteAsync { response, failure ->
            if (failure != null) { request.fail(failure); return@whenCompleteAsync }
            try {
                if (!synchronized(this) { request.active() }) return@whenCompleteAsync
                collected += response?.data ?: error("gopls returned no semantic-token range data")
                if (index + 1 < chunks.size) collect(request, snapshot, chunks, index + 1, collected)
                else {
                    // A closed file has no didChange stream. Refuse a mixed result if
                    // its exact disk contents changed while its ranges were evaluated.
                    if (snapshot.disk != null && Files.readString(snapshot.disk) != snapshot.text) throw contentModified()
                    val merged = SemanticTokens(GoSemanticTokenChunks.merge(collected)).apply {
                        resultId = "opengoland-$sessionId-${request.generation}-${resultIds.incrementAndGet()}"
                    }
                    finish(request, merged, null)
                }
            } catch (error: Throwable) { request.fail(error) }
        }
    }

    private fun finish(request: Request, response: SemanticTokens?, failure: Throwable?) {
        val active = synchronized(this) { request.active() }
        if (!active) { if (!request.result.isDone) request.fail(contentModified()); return }
        if (failure != null) request.fail(failure)
        else if (response == null) request.fail(IllegalStateException("gopls returned no semantic tokens"))
        else request.result.complete(response)
    }

    override fun close() {
        val closing = synchronized(this) {
            closed = true; documents.clear(); invalidDocuments.clear(); generations.clear()
            requests.toList().onEach { it.stopped = true }
        }
        closing.forEach { it.fail(CancellationException("Go language server closed")) }
    }

    companion object {
        private fun invoke(method: Method, target: Any, args: Array<out Any?>): Any? =
            try { method.invoke(target, *args) } catch (failure: InvocationTargetException) { throw failure.targetException }
        private fun contentModified() = ResponseErrorException(ResponseError(ResponseErrorCode.ContentModified,
            "Go document changed while semantic tokens were being computed", null))
    }
}

internal object GoSemanticTokenChunks {
    // Leave margin below gopls's 100000-byte range limit, including Unicode files.
    const val MAX_BYTES = 80_000

    fun partition(text: String, requested: Range? = null, maximumBytes: Int = MAX_BYTES): List<Range> {
        require(maximumBytes >= 4)
        val start = requested?.start ?: Position(0, 0)
        val end = requested?.end ?: position(text, text.length)
        var offset = offset(text, start)
        val finish = offset(text, end)
        require(finish >= offset) { "Reversed semantic-token range" }
        val chunks = mutableListOf<Range>()
        var first = Position(start.line, start.character)
        var current = Position(start.line, start.character)
        var bytes = 0
        while (offset < finish) {
            val crlf = text[offset] == '\r' && offset + 1 < finish && text[offset + 1] == '\n'
            val codePoint = Character.codePointAt(text, offset)
            val width = if (crlf) 2 else Character.charCount(codePoint)
            val encoded = if (crlf) 2 else when { codePoint <= 0x7f -> 1; codePoint <= 0x7ff -> 2; codePoint <= 0xffff -> 3; else -> 4 }
            if (bytes + encoded > maximumBytes) {
                chunks += Range(first, current)
                first = Position(current.line, current.character)
                bytes = 0
            }
            current = if (crlf || codePoint == '\n'.code) Position(current.line + 1, 0)
                else Position(current.line, current.character + width)
            offset += width
            bytes += encoded
        }
        chunks += Range(first, current)
        return chunks
    }

    private data class Token(val line: Int, val column: Int, val length: Int, val type: Int, val modifiers: Int)
    fun merge(parts: List<List<Int>>): List<Int> {
        val tokens = linkedSetOf<Token>()
        for (data in parts) {
            require(data.size % 5 == 0) { "Invalid semantic-token encoding" }
            var line = 0
            var column = 0
            for (index in data.indices step 5) {
                val delta = data[index]
                require(delta >= 0 && data[index + 1] >= 0 && data[index + 2] > 0 && data[index + 3] >= 0 && data[index + 4] >= 0)
                line = Math.addExact(line, delta)
                column = if (delta == 0) Math.addExact(column, data[index + 1]) else data[index + 1]
                tokens += Token(line, column, data[index + 2], data[index + 3], data[index + 4])
            }
        }
        val result = ArrayList<Int>(tokens.size * 5)
        var line = 0
        var column = 0
        for (token in tokens.sortedWith(compareBy(Token::line, Token::column, Token::length, Token::type, Token::modifiers))) {
            result += token.line - line
            result += if (line == token.line) token.column - column else token.column
            result += token.length; result += token.type; result += token.modifiers
            line = token.line; column = token.column
        }
        return result
    }

    private fun offset(text: String, position: Position): Int {
        require(position.line >= 0 && position.character >= 0)
        var start = 0
        repeat(position.line) { start = text.indexOf('\n', start).also { require(it >= 0) } + 1 }
        var end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
        if (end > start && text[end - 1] == '\r') end--
        require(position.character <= end - start) { "Semantic-token column is outside its line" }
        val offset = start + position.character
        require(offset == 0 || offset == text.length || !text[offset - 1].isHighSurrogate() || !text[offset].isLowSurrogate())
        return offset
    }

    private fun position(text: String, offset: Int): Position {
        var line = 0
        var beginning = 0
        for (index in 0 until offset) if (text[index] == '\n') { line++; beginning = index + 1 }
        return Position(line, offset - beginning)
    }
}
