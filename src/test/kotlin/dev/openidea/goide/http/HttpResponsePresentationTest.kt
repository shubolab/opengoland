package dev.openidea.goide.http

import java.nio.charset.Charset
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HttpResponsePresentationTest {
    private fun response(text: String, contentType: String = "application/json") =
        HttpRequestResponse(200, mapOf("Content-Type" to listOf(contentType)), text.toByteArray(Charsets.UTF_8), 12)

    @Test fun formatsJsonWithoutChangingWireBytesNumbersDuplicateKeysOrNulls() {
        val raw = "{\"message\":\"你好😀\",\"number\":123456789012345678901234567890,\"exponent\":1e+10,\"key\":1,\"key\":2,\"nil\":null}"
        val response = response(raw, "application/problem+json; charset=UTF-8")
        val original = response.body.copyOf()
        val presentation = HttpResponsePresentation.prepare(response)
        assertTrue(presentation.formatted)
        assertEquals("json", presentation.extension)
        assertEquals(response.body.size, presentation.wireBytes)
        assertTrue(presentation.wireBytes > raw.length)
        assertTrue(presentation.bodyText.contains("\n  \"message\": \"你好😀\""))
        assertTrue(presentation.bodyText.contains("123456789012345678901234567890"))
        assertTrue(presentation.bodyText.contains("1e+10"))
        assertTrue(presentation.bodyText.contains("\"key\": 1"))
        assertTrue(presentation.bodyText.contains("\"key\": 2"))
        assertTrue(presentation.bodyText.contains("\"nil\": null"))
        assertContentEquals(original, response.body)
    }

    @Test fun respectsQuotedCaseInsensitiveDeclaredCharsetIncludingUtf16Json() {
        val text = "{\"message\":\"你好😀\"}"
        val response = HttpRequestResponse(201,
            mapOf("cOnTeNt-TyPe" to listOf("application/json; CHARSET = \"UTF-16LE\"")),
            text.toByteArray(Charset.forName("UTF-16LE")), 2)
        val presentation = HttpResponsePresentation.prepare(response)
        assertTrue(presentation.bodyText.contains("你好😀"))
        assertTrue(presentation.formatted)
        assertFalse(presentation.binary)
        assertTrue(presentation.headersText.startsWith("HTTP 201"))
        assertFalse(presentation.headersText.contains("Invalid byte sequence"))
    }

    @Test fun decodesWindows1252RatherThanAssumingUtf8() {
        val response = HttpRequestResponse(200,
            mapOf("Content-Type" to listOf("text/plain; charset=windows-1252")),
            "café €".toByteArray(Charset.forName("windows-1252")), 0)
        val presentation = HttpResponsePresentation.prepare(response)
        assertEquals("café €", presentation.bodyText)
        assertFalse(presentation.formatted)
        assertFalse(presentation.binary)
    }

    @Test fun unknownCharsetAndInvalidDeclaredTextEncodingAreReported() {
        val unsupported = HttpResponsePresentation.prepare(response("你好😀", "text/plain; charset=not-a-real-charset"))
        assertEquals("你好😀", unsupported.bodyText)
        assertTrue(unsupported.headersText.contains("Unsupported response charset"))
        assertTrue(unsupported.headersText.contains("displayed as UTF-8"))
        val malformed = HttpResponsePresentation.prepare(HttpRequestResponse(200,
            mapOf("Content-Type" to listOf("text/plain; charset=UTF-8")), byteArrayOf(0xc3.toByte(), 0x28), 0))
        assertEquals("\ufffd(", malformed.bodyText)
        assertFalse(malformed.binary)
        assertTrue(malformed.headersText.contains("Invalid byte sequence for UTF-8"))
    }

    @Test fun strictInvalidJsonAlwaysFallsBackToTheOriginalText() {
        val invalid = listOf("{\"x\":1,}", "{unquoted:1}", "{\"x\":NaN}", "[01]", "{} {}",
            "/* comment */ {}", "{\"x\":\"raw\nnewline\"}", "[1 2]", "{\"unclosed\": [1,2}")
        invalid.forEach { raw ->
            val presentation = HttpResponsePresentation.prepare(response(raw))
            assertEquals(raw, presentation.bodyText, raw)
            assertFalse(presentation.formatted, raw)
        }
    }

    @Test fun emptyResponseAndStreamingJsonAreNotInventedOrCombined() {
        val empty = HttpResponsePresentation.prepare(response(""))
        assertEquals("", empty.bodyText)
        assertFalse(empty.formatted)
        val lines = "{\"x\":1}\n{\"x\":2}\n"
        val stream = HttpResponsePresentation.prepare(response(lines, "application/x-ndjson"))
        assertEquals(lines, stream.bodyText)
        assertFalse(stream.formatted)
        assertFalse(stream.binary)
    }

    @Test fun deepAndWideValidJsonFallBackWithoutRecursiveParsing() {
        val deep = "[".repeat(5_000) + "0" + "]".repeat(5_000)
        val wide = List(100_001) { "0" }.joinToString(",", "[", "]")
        for (raw in listOf(deep, wide)) {
            val presentation = HttpResponsePresentation.prepare(response(raw))
            assertEquals(raw, presentation.bodyText)
            assertFalse(presentation.formatted)
        }
    }

    @Test fun indentedJsonExpansionCannotGrowTheDisplayWithoutBound() {
        val raw = "[".repeat(60) + List(40_000) { "0" }.joinToString(",") + "]".repeat(60)
        val presentation = HttpResponsePresentation.prepare(response(raw))
        assertEquals(raw, presentation.bodyText)
        assertFalse(presentation.formatted)
        assertTrue(presentation.bodyText.length < 2 * 1024 * 1024)
    }

    @Test fun htmlAndXmlRemainLiteralTextAndBinaryBodiesAreNotDecoded() {
        val script = "<html><script>fetch('http://127.0.0.1:1/execute')</script>你好</html>"
        val html = HttpResponsePresentation.prepare(response(script, "text/html; charset=UTF-8"))
        assertEquals(script, html.bodyText)
        assertEquals("html", html.extension)
        assertFalse(html.formatted)
        val xml = HttpResponsePresentation.prepare(response("<response>你好</response>", "application/example+xml"))
        assertEquals("xml", xml.extension)
        assertEquals("<response>你好</response>", xml.bodyText)
        val binaryResponse = response(script, "application/octet-stream")
        val binary = HttpResponsePresentation.prepare(binaryResponse)
        assertTrue(binary.binary)
        assertEquals(binaryResponse.body.size, binary.wireBytes)
        assertFalse(binary.bodyText.contains("<script>"))
        assertTrue(binary.bodyText.contains("${binary.wireBytes} bytes"))
    }

    @Test fun missingContentTypeCanDisplayTextButDoesNotInventTextForArbitraryBytes() {
        val text = HttpResponsePresentation.prepare(HttpRequestResponse(200, emptyMap(), "你好😀".toByteArray(), 0))
        assertEquals("你好😀", text.bodyText)
        assertFalse(text.binary)
        for (bytes in listOf(byteArrayOf(0, 1, 2), byteArrayOf(0xff.toByte(), 0xfe.toByte()))) {
            assertTrue(HttpResponsePresentation.prepare(HttpRequestResponse(200, emptyMap(), bytes, 0)).binary)
        }
    }

    @Test fun oversizedBodiesAndHeadersHaveExplicitBoundedPresentations() {
        val response = HttpRequestResponse(200, mapOf("Content-Type" to listOf("text/plain")), ByteArray(1024 * 1024 + 1) { 65 }, 0)
        val presentation = HttpResponsePresentation.prepare(response)
        assertTrue(presentation.bodyOmitted)
        assertFalse(presentation.binary)
        assertEquals(response.body.size, presentation.wireBytes)
        assertTrue(presentation.bodyText.length < 200)
        val manyHeaders = HttpResponsePresentation.prepare(HttpRequestResponse(200,
            mapOf("X-Large" to listOf("a".repeat(200_000))), "ok".toByteArray(), 1))
        assertTrue(manyHeaders.headersText.length <= 64 * 1024)
        assertTrue(manyHeaders.headersText.contains("headers omitted"))
        assertEquals("ok", manyHeaders.bodyText)
    }
}
