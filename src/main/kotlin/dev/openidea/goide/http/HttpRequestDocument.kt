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

/** Parses a snapshot of the editor. Unsupported executable syntax fails before any network I/O. */
internal object HttpRequestDocument {
    private val separator = Regex("(?m)^[\\t ]*###[^\\r\\n]*(?:\\r?\\n|$)")
    private val assignment = Regex("@([A-Za-z_][A-Za-z0-9_.-]*)\\s*=\\s*(.*)")
    private val variable = Regex("\\{\\{\\s*([^{}]+?)\\s*}}")
    private val requestLine = Regex("([A-Za-z]+)\\s+(\\S+)(?:\\s+HTTP/(1\\.1))?")
    private val headerName = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
    private val methods = setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")
    private val managedHeaders = setOf("content-length", "host", "connection", "expect", "upgrade")

    fun requestAt(text: String, offset: Int): HttpRequestSpec {
        require(text.length <= 2 * 1024 * 1024) { "HTTP request documents are limited to 2 MiB of text." }
        require(offset in 0..text.length) { "The request location is outside the document." }
        val separators = separator.findAll(text).toList()
        val selected = separators.indexOfLast { it.range.first <= offset }
        val start = if (selected < 0) 0 else separators[selected].range.last + 1
        val end = separators.getOrNull(selected + 1)?.range?.first ?: text.length
        val variables = linkedMapOf<String, String>()
        var previous = 0
        separators.take(selected + 1).forEach { match ->
            readPreamble(text.substring(previous, match.range.first), variables)
            previous = match.range.last + 1
        }
        val block = text.substring(start, end)
        val lines = block.split('\n').map { it.removeSuffix("\r") }
        var index = readPreamble(block, variables)
        require(index < lines.size && lines[index].isNotBlank()) { "Place the caret inside an HTTP request, below its ### separator." }
        val match = requestLine.matchEntire(lines[index].trim())
            ?: throw IllegalArgumentException("Expected METHOD http(s)://host/path, optionally followed by HTTP/1.1.")
        val method = match.groupValues[1].uppercase()
        require(method in methods) { "Supported request methods: ${methods.joinToString()}." }
        val requestLineIndex = index
        val target = expand(match.groupValues[2], variables)
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
            val value = expand(line.substring(colon + 1).trim(), variables)
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
        // Preserve the body's original CRLF and trailing newline, rather than rejoining parsed lines.
        var bodyStart = 0
        repeat((index + 1).coerceAtMost(lines.size)) {
            bodyStart = block.indexOf('\n', bodyStart).let { if (it < 0) block.length else it + 1 }
        }
        val rawBody = block.substring(bodyStart)
        require(!Regex("(?m)^[\\t ]*(?:<[\\t ]+|>|\\{%|%})").containsMatchIn(rawBody)) {
            "File bodies and request/response scripts are not supported by the built-in HTTP runner."
        }
        val body = expand(rawBody, variables).toByteArray(UTF_8)
        require(body.size <= 1024 * 1024) { "HTTP request bodies are limited to 1 MiB." }
        require(method != "HEAD" || body.isEmpty()) { "HEAD requests cannot have a body in this runner." }
        return HttpRequestSpec(method, uri, headers, body, text.take(start).count { it == '\n' } + requestLineIndex + 1)
    }

    private fun readPreamble(block: String, variables: MutableMap<String, String>): Int {
        val lines = block.split('\n')
        var index = 0
        while (index < lines.size) {
            val line = lines[index].trim()
            when {
                line.isEmpty() -> Unit
                Regex("(?:#|//)\\s+@.*").matches(line) && !Regex("(?:#|//)\\s+@name\\s+\\S.*").matches(line) ->
                    throw IllegalArgumentException("HTTP request directives are not supported. Only # @name is accepted as a label.")
                line.startsWith('#') || line.startsWith("//") -> Unit
                assignment.matches(line) -> assignment.matchEntire(line)!!.let { variables[it.groupValues[1]] = it.groupValues[2] }
                else -> return index
            }
            index++
        }
        return index
    }

    private class ExpansionBudget(var remaining: Int = 10_000)

    private fun expand(value: String, variables: Map<String, String>, chain: Set<String> = emptySet(), budget: ExpansionBudget = ExpansionBudget()): String {
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
            val content = variables[name] ?: throw IllegalArgumentException("Undefined HTTP variable: $name. Only @name = value file variables are supported.")
            append(expand(content, variables, chain + name, budget))
            offset = match.range.last + 1
        }
        append(value.substring(offset))
        return output.toString()
    }
}
