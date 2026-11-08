package dev.openidea.goide.lsp

import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.services.ServiceEndpoints
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageServer
import org.junit.Assume
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Actual factory/launcher/proxy and owned gopls process, with an upstream bypass only as a failure/control oracle. */
class GoSemanticTokensGoplsTest {
    @Test fun realGoplsFullHighlightingCoversLargeUnicodeFilesAndOversizedSingleLineTokens() {
        val executable = System.getenv("OPENGOLAND_GOPLS_TEST")?.let(Path::of)
            ?: System.getenv("PATH").orEmpty().split(java.io.File.pathSeparator).map { Path.of(it, "gopls") }.firstOrNull { Files.isExecutable(it) }
        Assume.assumeTrue("Install gopls or set OPENGOLAND_GOPLS_TEST to run the tool integration regression", executable != null)
        val root = Files.createTempDirectory("opengoland-gopls-semantics-").toRealPath()
        val pool = Executors.newCachedThreadPool()
        val process = ProcessBuilder(executable.toString(), "serve").directory(root.toFile())
            .redirectError(root.resolve("gopls.stderr").toFile()).apply {
                environment()["GOTOOLCHAIN"] = "local"
                environment()["GOTELEMETRY"] = "off"
                environment()["GOPROXY"] = "off"
                environment()["GOWORK"] = "off"
            }.start()
        val features = GoLanguageServerFactory().createClientFeatures()
        val launcher = features.createLauncherBuilder<LanguageServer>().setRemoteInterface(LanguageServer::class.java)
            .setLocalService(Client()).setInput(process.inputStream).setOutput(process.outputStream).setExecutorService(pool).create()
        val listening = launcher.startListening()
        val server = launcher.remoteProxy
        val upstream = ServiceEndpoints.toServiceObject(launcher.remoteEndpoint, LanguageServer::class.java)
        try {
            Files.writeString(root.resolve("go.mod"), "module example.com/semanticprobe\n\ngo 1.24.0\n")
            val capabilities = ClientCapabilities().apply {
                general = GeneralClientCapabilities().apply { positionEncodings = listOf("utf-16") }
                textDocument = TextDocumentClientCapabilities().apply {
                    semanticTokens = SemanticTokensCapabilities().apply {
                        requests = SemanticTokensClientCapabilitiesRequests(true, true)
                        tokenTypes = listOf("namespace", "type", "typeParameter", "parameter", "variable", "function", "method", "macro", "keyword", "comment", "string", "number", "operator", "label")
                        tokenModifiers = listOf("definition", "readonly", "defaultLibrary")
                        formats = listOf("relative")
                        multilineTokenSupport = false
                        overlappingTokenSupport = false
                    }
                }
            }
            val initialized = server.initialize(InitializeParams().apply {
                this.capabilities = capabilities
                rootUri = root.toUri().toString()
                workspaceFolders = listOf(WorkspaceFolder(root.toUri().toString(), "semanticprobe"))
                initializationOptions = mapOf("semanticTokens" to true)
            }).get(30, TimeUnit.SECONDS)
            assertTrue(initialized.capabilities.positionEncoding in listOf(null, "utf-16"))
            val legend = initialized.capabilities.semanticTokensProvider.legend
            assertTrue(legend.tokenTypes.contains("variable"))
            server.initialized(InitializedParams())
            val file = root.resolve("main.go")
            val uri = file.toUri().toString()
            val identifier = TextDocumentIdentifier(uri)
            fun open(text: String, version: Int = 1) {
                Files.writeString(file, text)
                server.textDocumentService.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "go", version, text)))
            }
            fun close() = server.textDocumentService.didClose(DidCloseTextDocumentParams(identifier))
            val medium = "package main\n" + "// 中文 😀" + "x".repeat(83_000) + "\nvar Value = 37\n"
            assertTrue(medium.toByteArray().size in 80_001..99_999)
            open(medium)
            val expected = upstream.textDocumentService.semanticTokensFull(SemanticTokensParams(identifier)).get(30, TimeUnit.SECONDS)
            val collected = server.textDocumentService.semanticTokensFull(SemanticTokensParams(identifier)).get(30, TimeUnit.SECONDS)
            assertEquals(expected.data, collected.data, "Chunking must retain exactly the semantic data returned by a valid upstream full request")
            close()

            val count = 12_000
            val large = buildString {
                append("package main\r\n")
                repeat(count) { append("var V%05d = %d // 中文 😀\r\n".format(it, it)) }
            }
            assertTrue(large.toByteArray().size > 200_000)
            open(large, 2)
            val failure = assertFailsWith<java.util.concurrent.ExecutionException> {
                upstream.textDocumentService.semanticTokensFull(SemanticTokensParams(identifier)).get(30, TimeUnit.SECONDS)
            }.cause as ResponseErrorException
            assertContains(failure.responseError.message, "too large")
            val full = server.textDocumentService.semanticTokensFull(SemanticTokensParams(identifier)).get(30, TimeUnit.SECONDS)
            val variableType = legend.tokenTypes.indexOf("variable")
            val tokens = decode(full.data).filter { it.type == variableType && it.column == 4 && it.length == 6 }
            assertEquals(count, tokens.size)
            assertEquals((1..count).toList(), tokens.map { it.line })
            close()

            val literal = "😀中文".repeat(30_000)
            val singleLine = "package main\nconst Long = \"$literal\"\nvar After = 17\n"
            open(singleLine, 3)
            val single = server.textDocumentService.semanticTokensFull(SemanticTokensParams(identifier)).get(30, TimeUnit.SECONDS)
            val decoded = decode(single.data)
            val strings = decoded.filter { it.type == legend.tokenTypes.indexOf("string") }
            assertEquals(1, strings.size, "A token intersecting several chunks must occur once")
            assertEquals(literal.length + 2, strings.single().length)
            assertTrue(decoded.any { it.line == 2 && it.column == 4 && it.length == 5 && it.type == variableType })
            close()
        } finally {
            runCatching { server.shutdown().get(3, TimeUnit.SECONDS); server.exit() }
            runCatching { process.outputStream.close() }
            if (!process.waitFor(3, TimeUnit.SECONDS)) { process.destroy(); if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly().waitFor() }
            listening.cancel(true)
            pool.shutdownNow()
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    private data class Token(val line: Int, val column: Int, val length: Int, val type: Int)
    private fun decode(data: List<Int>): List<Token> {
        val result = mutableListOf<Token>()
        var line = 0
        var column = 0
        for (index in data.indices step 5) {
            line += data[index]
            column = if (data[index] == 0) column + data[index + 1] else data[index + 1]
            result += Token(line, column, data[index + 2], data[index + 3])
        }
        return result
    }
    private class Client : LanguageClient {
        override fun telemetryEvent(value: Any?) {}
        override fun publishDiagnostics(params: PublishDiagnosticsParams) {}
        override fun showMessage(params: MessageParams) {}
        override fun showMessageRequest(params: ShowMessageRequestParams): CompletableFuture<MessageActionItem> = CompletableFuture.completedFuture(null)
        override fun logMessage(params: MessageParams) {}
        override fun configuration(params: ConfigurationParams): CompletableFuture<List<Any>> = CompletableFuture.completedFuture(params.items.map { emptyMap<String, Any>() })
    }
}
