package dev.openidea.goide.lsp

import com.google.gson.Gson
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentContentChangeEvent
import org.eclipse.lsp4j.jsonrpc.messages.Message
import org.eclipse.lsp4j.jsonrpc.messages.NotificationMessage
import java.net.URI
import java.io.File
import java.nio.file.Path

/** The document version/text observed in this session's notification queue, never a guessed IDE stamp. */
data class GoSyncedDocument(val version: Int, val text: String, val session: Long)

/**
 * Mirrors public connection-provider notifications without changing their payload or dispatch.
 * LSP4IJ 0.21 calls the hook after enqueueing the transport write, not after an acknowledgement.
 * A snapshot is therefore an observed client version, not proof that gopls has processed it.
 * LSP4IJ invokes the provider hook for outgoing as well as incoming messages. Only the
 * client-owned didOpen/didChange/didClose notifications affect this state.
 */
@Service(Service.Level.PROJECT)
class GoDocumentSync : Disposable {
    private val documents = mutableMapOf<String, GoSyncedDocument>()
    private var session = 0L
    private var active = false
    private var disposed = false
    private val gson = Gson()

    @Synchronized fun startSession(): Long {
        documents.clear()
        active = !disposed
        return ++session
    }

    @Synchronized fun stopSession(id: Long) {
        if (id == session) { active = false; documents.clear() }
    }

    @Synchronized fun snapshot(uri: String): GoSyncedDocument? = documents[canonicalUri(uri)]

    @Synchronized fun observe(id: Long, message: Message) {
        if (!active || id != session || message !is NotificationMessage) return
        var affected: String? = null
        try {
            when (message.method) {
                "textDocument/didOpen" -> {
                    val params = parameters(message.params, DidOpenTextDocumentParams::class.java)
                    val item = params.textDocument
                    val uri = canonicalUri(item.uri)
                    affected = uri
                    require(item.version >= 0)
                    require(!documents.containsKey(uri)) { "Duplicate didOpen without didClose" }
                    documents[uri] = GoSyncedDocument(item.version, item.text, id)
                }
                "textDocument/didChange" -> {
                    val params = parameters(message.params, DidChangeTextDocumentParams::class.java)
                    val uri = canonicalUri(params.textDocument.uri)
                    affected = uri
                    val previous = documents[uri] ?: return
                    val version = params.textDocument.version
                    require(version > previous.version) { "Out-of-order LSP document notification" }
                    documents[uri] = GoSyncedDocument(version, applyChanges(previous.text, params.contentChanges), id)
                }
                "textDocument/didClose" -> {
                    val params = parameters(message.params, DidCloseTextDocumentParams::class.java)
                    documents.remove(canonicalUri(params.textDocument.uri))
                }
            }
        } catch (_: RuntimeException) {
            // A malformed tracking event must neither corrupt the mirror nor break LSP transport.
            // Losing the version causes the transaction layer to refuse an unverifiable edit.
            if (affected == null) documents.clear() else documents.remove(affected)
        }
    }

    private fun <T> parameters(value: Any?, type: Class<T>): T =
        if (type.isInstance(value)) type.cast(value) else gson.fromJson(gson.toJsonTree(value), type)

    @Synchronized override fun dispose() { disposed = true; active = false; session++; documents.clear() }

    companion object {
        fun get(project: Project): GoDocumentSync = project.getService(GoDocumentSync::class.java)

        fun canonicalUri(uri: String): String {
            val parsed = runCatching { URI(uri).normalize() }.getOrNull() ?: return uri
            if (!parsed.scheme.equals("file", ignoreCase = true)) return parsed.toASCIIString()
            return runCatching {
                val authority = parsed.authority?.takeUnless { it.equals("localhost", ignoreCase = true) }
                val local = URI("file", authority, parsed.path, parsed.query, parsed.fragment)
                fileUri(Path.of(URI(local.toASCIIString())))
            }.getOrElse { parsed.toASCIIString() }
        }

        /** Lexical only. Path.toUri() performs a directory stat on Unix and must not run here. */
        fun fileUri(path: Path): String {
            var value = path.toAbsolutePath().normalize().toString()
            if (File.separatorChar == '\\') {
                value = value.replace('\\', '/')
                if (value.startsWith("//")) {
                    val authority = value.substring(2).substringBefore('/')
                    return URI("file", authority, value.removePrefix("//$authority").ifEmpty { "/" }, null, null).toASCIIString()
                }
                if (value.length > 1 && value[1] == ':') value = value[0].uppercaseChar() + value.substring(1)
                if (!value.startsWith('/')) value = "/$value"
            }
            return URI("file", "", value, null, null).toASCIIString()
        }

        /** Changes in one didChange notification refer to the result of their predecessor. */
        fun applyChanges(original: String, changes: List<TextDocumentContentChangeEvent>): String {
            var text = original
            for (change in changes) {
                val range = change.range
                text = if (range == null) change.text else {
                    val start = offset(text, range.start)
                    val end = offset(text, range.end)
                    require(end >= start) { "Reversed LSP document range" }
                    @Suppress("DEPRECATION")
                    change.rangeLength?.let { require(it == end - start) { "Invalid LSP range length" } }
                    text.substring(0, start) + change.text + text.substring(end)
                }
            }
            return text
        }

        private fun offset(text: String, position: Position): Int {
            require(position.line >= 0 && position.character >= 0)
            var start = 0
            repeat(position.line) {
                val newline = text.indexOf('\n', start)
                require(newline >= 0) { "LSP line is outside the document" }
                start = newline + 1
            }
            var end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
            if (end > start && text[end - 1] == '\r') end--
            require(position.character <= end - start) { "LSP column is outside the line" }
            val offset = start + position.character
            require(offset == 0 || offset == text.length || !text[offset - 1].isHighSurrogate() || !text[offset].isLowSurrogate()) {
                "LSP UTF-16 position splits a surrogate pair"
            }
            return offset
        }
    }
}
