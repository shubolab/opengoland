package dev.openidea.goide.lang

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HttpRequestLexerTest {
    @Test fun highlightsRequestsHeadersVariablesAndBodies() {
        val source = "# Shared variables\n@baseUrl = https://api.example.test\n### Products\nGET {{baseUrl}}/products?limit=10 HTTP/1.1\nAccept: application/json\nContent-Type: application/json\n\n{\"active\": true, \"count\": 2}\n"
        val tokens = tokens(source)
        assertTrue("HTTP_REQUEST_COMMENT" to "# Shared variables" in tokens)
        assertTrue("HTTP_REQUEST_VARIABLE" to "@baseUrl" in tokens)
        assertTrue("HTTP_REQUEST_SEPARATOR" to "### Products" in tokens)
        assertTrue("HTTP_REQUEST_METHOD" to "GET" in tokens)
        assertTrue("HTTP_REQUEST_URL" to "{{baseUrl}}/products?limit=10" in tokens)
        assertTrue("HTTP_REQUEST_VERSION" to "HTTP/1.1" in tokens)
        assertTrue("HTTP_REQUEST_HEADER_NAME" to "Accept" in tokens)
        assertTrue("HTTP_REQUEST_HEADER_NAME" to "Content-Type" in tokens)
        assertTrue("HTTP_REQUEST_STRING" to "\"active\"" in tokens)
        assertTrue("HTTP_REQUEST_NUMBER" to "2" in tokens)
    }

    @Test fun checksEveryHttpMethodAndBoundedUtf16Offsets() {
        for (method in HttpRequestTokens.methods) {
            val source = "$method https://api.example.test/中文?q=😀 HTTP/2\n"
            val tokens = tokens(source)
            assertTrue(tokens.any { it == ("HTTP_REQUEST_METHOD" to method) })
            assertTrue(tokens.any { it.first == "HTTP_REQUEST_URL" && it.second.contains("中文") })
            assertTrue(tokens.any { it == ("HTTP_REQUEST_VERSION" to "HTTP/2") })
        }
        val source = "# 注释😀\nPOST https://example.test\nX-Test: 值\n"
        for (begin in 0..source.length) for (end in begin..source.length) {
            val lexer = HttpRequestLexer()
            lexer.start(source, begin, end, 0)
            var offset = begin
            while (lexer.tokenType != null) {
                assertEquals(offset, lexer.tokenStart)
                assertTrue(lexer.tokenEnd > lexer.tokenStart)
                assertTrue(lexer.tokenEnd <= end)
                offset = lexer.tokenEnd
                lexer.advance()
            }
            assertEquals(end, offset)
        }
    }

    private fun tokens(source: String): List<Pair<String, String>> {
        val lexer = HttpRequestLexer()
        lexer.start(source)
        var offset = 0
        return buildList {
            while (lexer.tokenType != null) {
                assertEquals(offset, lexer.tokenStart)
                add(lexer.tokenType.toString() to source.substring(lexer.tokenStart, lexer.tokenEnd))
                offset = lexer.tokenEnd
                lexer.advance()
            }
        }.also { assertEquals(source.length, offset) }
    }
}
