package dev.openidea.goide.lsp

import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.eclipse.lsp4j.services.LanguageServer
import org.eclipse.lsp4j.services.TextDocumentService
import org.eclipse.lsp4j.services.WorkspaceService
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.test.*

class GoSemanticTokensServerTest {
    private val uri = "file:///opengoland/semantic-test.go"
    private val large = "package main\n" + "// 中文 😀 comment\r\n".repeat(12_000) + "var Last = 37\n"

    @Test fun utf8ChunksCoverCrLfUnicodeAndLongSingleLinesWithoutGaps() {
        for (text in listOf("中文😀abc\r\n".repeat(10), "α".repeat(101) + "😀", "\r\n".repeat(30))) {
            val ranges = GoSemanticTokenChunks.partition(text, maximumBytes = 17)
            val parts = ranges.map { text.substring(offset(text, it.start), offset(text, it.end)) }
            assertEquals(text, parts.joinToString(""))
            assertTrue(parts.all { it.toByteArray(Charsets.UTF_8).size <= 17 })
            assertTrue(parts.none { it.endsWith('\r') || it.firstOrNull()?.isLowSurrogate() == true || it.lastOrNull()?.isHighSurrogate() == true })
            assertEquals(ranges.dropLast(1).map { it.end }, ranges.drop(1).map { it.start })
        }
    }

    @Test fun mergeUsesDocumentCoordinatesAndDeduplicatesBoundaryIntersectingTokens() {
        val first = listOf(0, 0, 7, 1, 0, 2, 4, 4, 3, 1)
        val second = listOf(2, 4, 4, 3, 1, 0, 9, 6, 4, 0, 3, 1, 8, 2, 0)
        assertEquals(listOf(0, 0, 7, 1, 0, 2, 4, 4, 3, 1, 0, 9, 6, 4, 0, 3, 1, 8, 2, 0),
            GoSemanticTokenChunks.merge(listOf(second, first)))
        assertEquals(listOf(0, 0, 200_000, 1, 0), GoSemanticTokenChunks.merge(List(4) { listOf(0, 0, 200_000, 1, 0) }))
        assertFailsWith<IllegalArgumentException> { GoSemanticTokenChunks.merge(listOf(listOf(0, 0, 1))) }
        assertFailsWith<IllegalArgumentException> { GoSemanticTokenChunks.merge(listOf(listOf(-1, 0, 1, 0, 0))) }
    }

    @Test fun oversizedFullRequestCollectsEveryRangeAndDoesNotSendFailingFullRequest() {
        val peer = Peer()
        GoSemanticTokensServer(peer).use { bridge ->
            open(bridge, large)
            peer.tokens.range = { params ->
                CompletableFuture.completedFuture(SemanticTokens(listOf(params.range.start.line, params.range.start.character, 1, 0, 0)))
            }
            val full = bridge.server.textDocumentService.semanticTokensFull(SemanticTokensParams(TextDocumentIdentifier(uri))).get(5, TimeUnit.SECONDS)
            assertEquals(0, peer.tokens.fullCalls)
            val expected = GoSemanticTokenChunks.partition(large)
            assertEquals(expected, peer.tokens.ranges.map { it.range })
            assertEquals(expected.size * 5, full.data.size)
            assertTrue(full.resultId.startsWith("opengoland-"))
        }
    }

    @Test fun unchangedSmallDocumentsAndAllOtherDefaultRpcMethodsAreForwarded() {
        val peer = Peer()
        GoSemanticTokensServer(peer).use { bridge ->
            open(bridge, "package main\n")
            val params = SemanticTokensParams(TextDocumentIdentifier(uri))
            assertSame(peer.tokens.small, bridge.server.textDocumentService.semanticTokensFull(params).get(5, TimeUnit.SECONDS))
            assertEquals(1, peer.tokens.fullCalls)
            assertTrue(peer.tokens.ranges.isEmpty())
            assertSame(peer.tokens.hover, bridge.server.textDocumentService.hover(HoverParams(TextDocumentIdentifier(uri), Position(0, 0))).join())
            bridge.server.initialized(InitializedParams())
            assertTrue(peer.initialized)
            assertSame(peer.workspace, bridge.server.workspaceService)
            bridge.server.shutdown().join()
            assertTrue(peer.shutdown)
        }
    }

    @Test fun deltaReturnsALegalFreshFullResultInsteadOfForwardingAnAggregateId() {
        val peer = Peer()
        GoSemanticTokensServer(peer).use { bridge ->
            open(bridge, large)
            val response = bridge.server.textDocumentService.semanticTokensFullDelta(
                SemanticTokensDeltaParams(TextDocumentIdentifier(uri), "opengoland-old-session-42")).get(5, TimeUnit.SECONDS)
            assertTrue(response.isLeft)
            assertEquals(0, peer.tokens.deltaCalls)
            assertEquals(GoSemanticTokenChunks.partition(large).size, peer.tokens.ranges.size)
        }
    }

    @Test fun cancellationCancelsTheActualRpcAndCannotStartLaterChunks() {
        for (delta in listOf(false, true)) {
            val peer = Peer()
            val pending = CompletableFuture<SemanticTokens>()
            val started = CountDownLatch(1)
            peer.tokens.range = { started.countDown(); pending }
            GoSemanticTokensServer(peer).use { bridge ->
                open(bridge, large)
                val request: CompletableFuture<*> = if (delta) bridge.server.textDocumentService.semanticTokensFullDelta(
                    SemanticTokensDeltaParams(TextDocumentIdentifier(uri), "old-result"))
                    else bridge.server.textDocumentService.semanticTokensFull(SemanticTokensParams(TextDocumentIdentifier(uri)))
                assertTrue(started.await(5, TimeUnit.SECONDS))
                assertTrue(request.cancel(true))
                assertTrue(pending.isCancelled)
                assertFalse(pending.complete(SemanticTokens(emptyList())))
                assertEquals(1, peer.tokens.ranges.size)
            }
        }
    }

    @Test fun malformedDirtyNotificationsCannotFallbackToSavedDiskContent() {
        val peer = Peer()
        GoSemanticTokensServer(peer).use { bridge ->
            open(bridge, large)
            bridge.server.textDocumentService.didChange(DidChangeTextDocumentParams(VersionedTextDocumentIdentifier(uri, 2),
                listOf(TextDocumentContentChangeEvent().apply { range = Range(Position(0, 900), Position(0, 901)); text = "invalid" })))
            val failure = assertFailsWith<ExecutionException> {
                bridge.server.textDocumentService.semanticTokensFull(SemanticTokensParams(TextDocumentIdentifier(uri))).get(5, TimeUnit.SECONDS)
            }.cause as ResponseErrorException
            assertEquals(ResponseErrorCode.ContentModified.value, failure.responseError.code)
            assertEquals(0, peer.tokens.fullCalls)
            assertTrue(peer.tokens.ranges.isEmpty())
        }
    }

    @Test fun aggregateResultIdentifiersCannotCollideAcrossServerSessions() {
        val ids = List(2) {
            GoSemanticTokensServer(Peer()).use { bridge ->
                open(bridge, large)
                bridge.server.textDocumentService.semanticTokensFull(SemanticTokensParams(TextDocumentIdentifier(uri))).get(5, TimeUnit.SECONDS).resultId
            }
        }
        assertNotEquals(ids[0], ids[1])
    }

    @Test fun documentChangesCancelTheAggregateRatherThanMixingVersions() {
        val peer = Peer()
        val pending = CompletableFuture<SemanticTokens>()
        val started = CountDownLatch(1)
        peer.tokens.range = { started.countDown(); pending }
        GoSemanticTokensServer(peer).use { bridge ->
            open(bridge, large)
            val request = bridge.server.textDocumentService.semanticTokensFull(SemanticTokensParams(TextDocumentIdentifier(uri)))
            assertTrue(started.await(5, TimeUnit.SECONDS))
            bridge.server.textDocumentService.didChange(DidChangeTextDocumentParams(VersionedTextDocumentIdentifier(uri, 2),
                listOf(TextDocumentContentChangeEvent("package updated\n"))))
            val cause = assertFailsWith<ExecutionException> { request.get(5, TimeUnit.SECONDS) }.cause
            assertEquals(ResponseErrorCode.ContentModified.value, (cause as ResponseErrorException).responseError.code)
            assertTrue(pending.isCancelled)
            assertEquals(1, peer.tokens.ranges.size)
            bridge.server.textDocumentService.semanticTokensFull(SemanticTokensParams(TextDocumentIdentifier(uri))).get(5, TimeUnit.SECONDS)
            assertEquals(1, peer.tokens.fullCalls)
        }
    }

    @Test fun closeAndServerDisposalInvalidatePendingRequests() {
        for (dispose in listOf(false, true)) {
            val peer = Peer()
            val pending = CompletableFuture<SemanticTokens>()
            val started = CountDownLatch(1)
            peer.tokens.range = { started.countDown(); pending }
            GoSemanticTokensServer(peer).use { bridge ->
                open(bridge, large)
                val result = bridge.server.textDocumentService.semanticTokensFull(SemanticTokensParams(TextDocumentIdentifier(uri)))
                assertTrue(started.await(5, TimeUnit.SECONDS))
                if (dispose) {
                    bridge.close()
                    open(bridge, "package late\n")
                    assertEquals(1, peer.tokens.openCalls, "A disposed bridge must not forward late notifications or revive its documents")
                } else bridge.server.textDocumentService.didClose(DidCloseTextDocumentParams(TextDocumentIdentifier(uri)))
                assertTrue(result.isCompletedExceptionally)
                assertTrue(pending.isCancelled)
                assertEquals(1, peer.tokens.ranges.size)
            }
        }
    }

    @Test fun largeExplicitRangeUsesUtf16OffsetsAndRetainsItsAbsoluteCoordinates() {
        val peer = Peer()
        GoSemanticTokensServer(peer).use { bridge ->
            open(bridge, large)
            val range = Range(Position(1, 0), Position(11_000, 0))
            bridge.server.textDocumentService.semanticTokensRange(SemanticTokensRangeParams(TextDocumentIdentifier(uri), range)).get(5, TimeUnit.SECONDS)
            assertEquals(range.start, peer.tokens.ranges.first().range.start)
            assertEquals(range.end, peer.tokens.ranges.last().range.end)
        }
    }

    @Test fun closedFilesAreReadOffThreadAndChangedDiskContentRejectsTheAggregate() {
        val root = Files.createTempDirectory("opengoland-semantic-disk-")
        try {
            val file = root.resolve("large.go")
            Files.writeString(file, large)
            val peer = Peer()
            val pending = CompletableFuture<SemanticTokens>()
            val started = CountDownLatch(1)
            peer.tokens.range = { if (peer.tokens.ranges.size == 1) { started.countDown(); pending }
                else CompletableFuture.completedFuture(SemanticTokens(emptyList())) }
            GoSemanticTokensServer(peer).use { bridge ->
                val result = bridge.server.textDocumentService.semanticTokensFull(SemanticTokensParams(TextDocumentIdentifier(file.toUri().toString())))
                assertTrue(started.await(5, TimeUnit.SECONDS))
                Files.writeString(file, "package changed\n")
                pending.complete(SemanticTokens(emptyList()))
                val error = assertFailsWith<ExecutionException> { result.get(5, TimeUnit.SECONDS) }.cause as ResponseErrorException
                assertEquals(ResponseErrorCode.ContentModified.value, error.responseError.code)
            }
        } finally { Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }

    private fun open(bridge: GoSemanticTokensServer, text: String) =
        bridge.server.textDocumentService.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "go", 1, text)))
    private fun offset(text: String, position: Position): Int {
        var start = 0
        repeat(position.line) { start = text.indexOf('\n', start) + 1 }
        return start + position.character
    }

    private class Peer : LanguageServer {
        val tokens = Tokens()
        val workspace = Proxy.newProxyInstance(WorkspaceService::class.java.classLoader, arrayOf(WorkspaceService::class.java)) { _, _, _ -> null } as WorkspaceService
        var initialized = false
        var shutdown = false
        override fun getTextDocumentService() = tokens
        override fun getWorkspaceService() = workspace
        override fun initialize(params: InitializeParams) = CompletableFuture.completedFuture(InitializeResult(ServerCapabilities()))
        override fun initialized(params: InitializedParams) { initialized = true }
        override fun shutdown(): CompletableFuture<Any> { shutdown = true; return CompletableFuture.completedFuture(Any()) }
        override fun exit() {}
    }
    private class Tokens : TextDocumentService {
        val small = SemanticTokens(listOf(0, 0, 7, 0, 0))
        val hover = Hover(MarkupContent("plaintext", "forwarded"))
        val ranges = CopyOnWriteArrayList<SemanticTokensRangeParams>()
        var fullCalls = 0
        var deltaCalls = 0
        var openCalls = 0
        var range: (SemanticTokensRangeParams) -> CompletableFuture<SemanticTokens> = { CompletableFuture.completedFuture(SemanticTokens(emptyList())) }
        override fun didOpen(params: DidOpenTextDocumentParams) { openCalls++ }
        override fun didChange(params: DidChangeTextDocumentParams) {}
        override fun didClose(params: DidCloseTextDocumentParams) {}
        override fun didSave(params: DidSaveTextDocumentParams) {}
        override fun semanticTokensFull(params: SemanticTokensParams): CompletableFuture<SemanticTokens> { fullCalls++; return CompletableFuture.completedFuture(small) }
        override fun semanticTokensRange(params: SemanticTokensRangeParams): CompletableFuture<SemanticTokens> { ranges += params; return range(params) }
        override fun semanticTokensFullDelta(params: SemanticTokensDeltaParams): CompletableFuture<org.eclipse.lsp4j.jsonrpc.messages.Either<SemanticTokens, SemanticTokensDelta>> {
            deltaCalls++; throw AssertionError("Aggregate resultIds must not reach the upstream delta endpoint")
        }
        override fun hover(params: HoverParams): CompletableFuture<Hover> = CompletableFuture.completedFuture(hover)
    }
}
