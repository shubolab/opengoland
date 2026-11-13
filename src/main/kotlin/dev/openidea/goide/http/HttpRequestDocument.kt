package dev.openidea.goide.http

import java.net.URI
import java.nio.charset.StandardCharsets.UTF_8

internal data class HttpRequestSpec(
    val method: String,
    val uri: URI,
    val headers: List<Pair<String, String>>,
    val body: ByteArray,
    val line: Int,
)

internal data class HttpRequestEntry(
    val blockOrdinal: Int,
    val startOffset: Int,
    val endOffset: Int,
    val requestOffset: Int,
    val line: Int,
    val displayName: String,
)

/** An editor snapshot; entries are cheap identities and are parsed only when they execute. */
internal class HttpRequestPlan internal constructor(private val text: String) {
    private data class Block(
        val selectionStart: Int,
        val entry: HttpRequestEntry?,
        val requestLineIndex: Int,
        val preambleError: String?,
    )
    private val definitions = linkedMapOf<String, MutableList<String>>()
    private val blocks: List<Block>
    val entries: List<HttpRequestEntry>

    init {
        require(text.length <= 2 * 1024 * 1024) { "HTTP request documents are limited to 2 MiB of text." }
        blocks = scan()
        entries = blocks.mapNotNull { it.entry }
    }

    /** A separator, including its newline, selects the following block as in the single runner. */
    fun entryAt(offset: Int): HttpRequestEntry? {
        require(offset in 0..text.length) { "The request location is outside the document." }
        var low = 0
        var high = blocks.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (blocks[middle].selectionStart <= offset) low = middle + 1 else high = middle
        }
        return blocks.getOrNull(low - 1)?.entry
    }

    /** A malformed entry fails on its own, without invalidating other requests in this snapshot. */
    fun parse(entry: HttpRequestEntry): HttpRequestSpec {
        val selected = blocks.getOrNull(entry.blockOrdinal)
        require(selected != null && selected.entry === entry) { "The HTTP request does not belong to this document snapshot." }
        selected.preambleError?.let { throw IllegalArgumentException(it) }
        val block = text.substring(entry.startOffset, entry.endOffset)
        val lines = block.split('\n').map { it.removeSuffix("\r") }
        var index = selected.requestLineIndex
        val match = requestLine.matchEntire(lines[index].trim())
            ?: throw IllegalArgumentException("Expected METHOD http(s)://host/path, optionally followed by HTTP/1.1.")
        val method = match.groupValues[1].uppercase()
        require(method in methods) { "Supported request methods: ${methods.joinToString()}." }
        val target = expand(match.groupValues[2])
        require(target.length <= 16 * 1024) { "HTTP request URLs are limited to 16 KiB." }
        val uri = try { URI(target) } catch (_: Exception) {
            throw IllegalArgumentException("The request URL is invalid. Encode spaces and other URL characters.")
        }
        require(uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.port in -1..65535) {
            "Use an absolute http:// or https:// URL with a valid host and port."
        }
        require(uri.rawUserInfo == null) { "Credentials in URLs are unsupported. Set an explicit Authorization header instead." }
        require(uri.rawFragment == null) { "HTTP request URLs cannot contain a fragment (#...)." }
        require(uri.toASCIIString().length <= 16 * 1024) { "Encoded HTTP request URLs are limited to 16 KiB." }
        val headers = mutableListOf<Pair<String, String>>()
        var headerBytes = 0
        index++
        while (index < lines.size && lines[index].isNotBlank()) {
            require(headers.size < 200) { "HTTP requests are limited to 200 headers." }
            val line = lines[index++]
            val colon = line.indexOf(':')
            require(colon > 0 && headerName.matches(line.substring(0, colon))) { "Expected Header-Name: value. Separate the body with a blank line." }
            val name = line.substring(0, colon)
            require(name.lowercase() !in managedHeaders) { "$name is managed by the HTTP transport and cannot be set manually." }
            val value = expand(line.substring(colon + 1).trim())
            require(value.none { it == '\r' || it == '\n' || it == '\u007f' || it.code > 255 || (it < ' ' && it != '\t') }) {
                "HTTP header values must use ASCII/Latin-1 without control characters."
            }
            headerBytes += name.length + value.length + 4
            require(headerBytes <= 64 * 1024) { "Expanded HTTP headers are limited to 64 KiB in total." }
            headers += name to value
        }
        headers.filter { it.first.equals("Content-Type", true) }.forEach { (_, value) ->
            val charset = Regex("charset\\s*=\\s*\"?([^;\\s\"]+)", RegexOption.IGNORE_CASE).find(value)?.groupValues?.get(1)
            require(charset == null || charset.equals("utf-8", true) || charset.equals("utf8", true)) {
                "Request bodies are encoded as UTF-8. Other request charsets are not supported."
            }
        }
        // Preserve internal CRLF and horizontal whitespace on content lines.
        // Boundary blank lines belong to the .http document, not the wire body.
        var bodyStart = 0
        repeat((index + 1).coerceAtMost(lines.size)) {
            bodyStart = block.indexOf('\n', bodyStart).let { if (it < 0) block.length else it + 1 }
        }
        val rawBody = block.substring(bodyStart)
        require(!Regex("(?m)^[\\t ]*(?:<[\\t ]+|>|\\{%|%})").containsMatchIn(rawBody)) {
            "File bodies and request/response scripts are not supported by the built-in HTTP runner."
        }
        val body = expand(withoutBoundaryBlankLines(rawBody)).toByteArray(UTF_8)
        require(body.size <= 1024 * 1024) { "HTTP request bodies are limited to 1 MiB." }
        require(method != "HEAD" || body.isEmpty()) { "HEAD requests cannot have a body in this runner." }
        return HttpRequestSpec(method, uri, headers, body, entry.line)
    }

    /** Matches the captured GoLand body-edge cases without trimming meaningful spaces or tabs. */
    private fun withoutBoundaryBlankLines(value: String): String {
        var firstContent = -1
        var lastContentEnd = 0
        var lineStart = 0
        while (lineStart < value.length) {
            val newline = value.indexOf('\n', lineStart)
            val rawEnd = if (newline < 0) value.length else newline
            val lineEnd = if (newline >= 0 && rawEnd > lineStart && value[rawEnd - 1] == '\r') rawEnd - 1 else rawEnd
            var cursor = lineStart
            while (cursor < lineEnd && (value[cursor] == ' ' || value[cursor] == '\t')) cursor++
            if (cursor < lineEnd) {
                if (firstContent < 0) firstContent = lineStart
                lastContentEnd = lineEnd
            }
            if (newline < 0) break
            lineStart = newline + 1
        }
        return if (firstContent < 0) "" else value.substring(firstContent, lastContentEnd)
    }

    private fun scan(): List<Block> {
        val result = mutableListOf<Block>()
        var contentStart = 0
        var selectionStart = 0
        var firstLine = 1
        var title: String? = null
        var requestCount = 0

        fun add(end: Int) {
            var cursor = contentStart
            var lineIndex = 0
            var explicitName: String? = null
            var error: String? = null
            var errorOffset = contentStart
            var errorLine = firstLine
            var requestOffset: Int? = null
            var sourceLine: String? = null
            while (cursor < end) {
                val newline = text.indexOf('\n', cursor).let { if (it < 0 || it >= end) end else it }
                val raw = text.substring(cursor, newline).removeSuffix("\r")
                val line = raw.trim()
                val named = namedDirective.matchEntire(line)
                val declaration = assignment.matchEntire(line)
                when {
                    line.isEmpty() -> Unit
                    named != null -> if (explicitName == null) explicitName = named.groupValues[1]
                    directive.matches(line) -> if (error == null) {
                        error = "HTTP request directives are not supported. Only # @name is accepted as a label."
                        errorOffset = cursor + raw.indexOfFirst { !it.isWhitespace() }
                        errorLine = firstLine + lineIndex
                    }
                    line.startsWith('#') || line.startsWith("//") -> Unit
                    declaration != null -> definitions.getOrPut(declaration.groupValues[1]) { mutableListOf() }
                        .add(declaration.groupValues[2])
                    else -> {
                        requestOffset = cursor + raw.indexOfFirst { !it.isWhitespace() }
                        sourceLine = line
                        break
                    }
                }
                if (newline == end) break
                cursor = newline + 1
                lineIndex++
            }
            val entry = if (requestOffset != null || error != null) {
                requestCount++
                val fallback = sourceLine?.let { requestLine.matchEntire(it) }?.let {
                    "${it.groupValues[1].uppercase()} ${it.groupValues[2]}"
                } ?: "Request $requestCount"
                HttpRequestEntry(result.size, contentStart, end, requestOffset ?: errorOffset,
                    if (requestOffset != null) firstLine + lineIndex else errorLine,
                    explicitName ?: title ?: fallback)
            } else null
            result += Block(selectionStart, entry, lineIndex, error)
        }

        separator.findAll(text).forEach { match ->
            add(match.range.first)
            val next = match.range.last + 1
            for (offset in contentStart until next) if (text[offset] == '\n') firstLine++
            contentStart = next
            selectionStart = match.range.first
            title = match.value.trimStart(' ', '\t').removePrefix("###").trim().takeIf { it.isNotEmpty() }
        }
        add(text.length)
        return result
    }

    // Real GoLand observations show file-wide forward references, with duplicate
    // declarations making only references to that name invalid. Keep this apart
    // from each block's own syntax errors and do not copy a variable map per entry.
    private fun resolve(name: String): String? {
        val history = definitions[name] ?: return null
        require(history.size == 1) {
            "Duplicate HTTP variable: $name. File variables must have a single declaration in the document."
        }
        return history.single()
    }

    private class ExpansionBudget(var remaining: Int = 10_000)

    private fun expand(value: String, chain: Set<String> = emptySet(), budget: ExpansionBudget = ExpansionBudget()): String {
        val output = StringBuilder()
        fun append(part: String) {
            require(part.length <= 2 * 1024 * 1024 - output.length) { "Expanded HTTP content is too large." }
            output.append(part)
        }
        var offset = 0
        variable.findAll(value).forEach { match ->
            require(budget.remaining-- > 0) { "HTTP variable expansion is too complex." }
            append(value.substring(offset, match.range.first))
            val name = match.groupValues[1].trim()
            require(name !in chain && chain.size < 16) { "HTTP variable expansion contains a cycle or is too deeply nested." }
            val content = resolve(name) ?: throw IllegalArgumentException("Undefined HTTP variable: $name. Only @name = value file variables are supported.")
            append(expand(content, chain + name, budget))
            offset = match.range.last + 1
        }
        append(value.substring(offset))
        return output.toString()
    }

    private companion object {
        val separator = Regex("(?m)^[\\t ]*###[^\\r\\n]*(?:\\r?\\n|$)")
        val assignment = Regex("@([A-Za-z_][A-Za-z0-9_.-]*)\\s*=\\s*(.*)")
        val variable = Regex("\\{\\{\\s*([^{}]+?)\\s*}}")
        val requestLine = Regex("([A-Za-z]+)\\s+(\\S+)(?:\\s+HTTP/(1\\.1))?")
        val namedDirective = Regex("(?:#|//)\\s+@name\\s+(\\S.*)")
        val directive = Regex("(?:#|//)\\s+@.*")
        val headerName = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
        val methods = setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")
        val managedHeaders = setOf("content-length", "host", "connection", "expect", "upgrade")
    }
}

/** Parses a snapshot of the editor. Unsupported executable syntax fails before any network I/O. */
internal object HttpRequestDocument {
    fun plan(text: String) = HttpRequestPlan(text)

    fun requestAt(text: String, offset: Int): HttpRequestSpec {
        val plan = plan(text)
        val entry = plan.entryAt(offset)
            ?: throw IllegalArgumentException("Place the caret inside an HTTP request, below its ### separator.")
        return plan.parse(entry)
    }
}
