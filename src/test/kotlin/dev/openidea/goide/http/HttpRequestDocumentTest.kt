package dev.openidea.goide.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HttpRequestDocumentTest {
    @Test fun selectsRequestAtCaretAndPreservesCrlfBody() {
        val text = "@host = http://127.0.0.1:8080\r\n### first\r\nGET {{host}}/first\r\n\r\n### echo\r\nPOST {{host}}/echo HTTP/1.1\r\nContent-Type: application/json\r\nX-Name: example\r\n\r\n{\"value\":\"你好😀\"}\r\n"
        val first = HttpRequestDocument.requestAt(text, text.indexOf("GET"))
        val second = HttpRequestDocument.requestAt(text, text.indexOf("POST"))
        assertEquals("/first", first.uri.path)
        assertEquals(0, first.body.size)
        assertEquals("POST", second.method)
        assertEquals("{\"value\":\"你好😀\"}\r\n", String(second.body, Charsets.UTF_8))
        assertEquals(listOf("Content-Type" to "application/json", "X-Name" to "example"), second.headers)
        assertEquals(6, second.line)
    }

    @Test fun resolvesNestedFileVariablesAndOverridesForLaterRequests() {
        val text = "@port = 8080\n@base = http://localhost:{{port}}\nGET {{base}}/one\n### two\n@port = 8081\nPOST {{base}}/two\nX-Port: {{port}}\n\n{{port}}"
        assertEquals(8080, HttpRequestDocument.requestAt(text, 0).uri.port)
        val second = HttpRequestDocument.requestAt(text, text.length)
        assertEquals(8081, second.uri.port)
        assertEquals("8081", second.headers.single().second)
        assertEquals("8081", String(second.body))
    }

    @Test fun refusesUnresolvedDynamicAndCyclicVariables() {
        for (text in listOf("GET {{unknown}}", "GET http://localhost/{{$" + "uuid}}", "@a = {{b}}\n@b = {{a}}\nGET {{a}}"))
            assertFailsWith<IllegalArgumentException> { HttpRequestDocument.requestAt(text, 0) }
    }

    @Test fun refusesExponentialEmptyVariableExpansion() {
        val text = buildString {
            append("@v0 = \n")
            repeat(9) { n -> append("@v${n + 1} = {{v$n}}{{v$n}}{{v$n}}{{v$n}}\n") }
            append("POST http://localhost\n\n{{v9}}")
        }
        assertTrue(assertFailsWith<IllegalArgumentException> { HttpRequestDocument.requestAt(text, 0) }.message!!.contains("complex"))
    }

    @Test fun boundsExpandedContentBeforeBuildingAnOversizedResult() {
        val text = "@large = " + "x".repeat(400_000) + "\nPOST http://localhost\n\n" + "{{large}}".repeat(10)
        assertFailsWith<IllegalArgumentException> { HttpRequestDocument.requestAt(text, 0) }
    }

    @Test fun refusesFileBodiesScriptsAndUnknownDirectives() {
        for (tail in listOf("< ./secret.txt", "> {% client.log('x'); %}", "< {% request.variables.set('a', 'b'); %}"))
            assertFailsWith<IllegalArgumentException> { HttpRequestDocument.requestAt("POST http://localhost\n\n$tail", 0) }
        assertFailsWith<IllegalArgumentException> { HttpRequestDocument.requestAt("# @no-log\nGET http://localhost", 0) }
        assertFailsWith<IllegalArgumentException> { HttpRequestDocument.requestAt("// @no-log\nGET http://localhost", 0) }
        assertFailsWith<IllegalArgumentException> { HttpRequestDocument.requestAt("#   @no-log\nGET http://localhost", 0) }
        assertEquals("GET", HttpRequestDocument.requestAt("# @name hello\nGET http://localhost", 0).method)
    }

    @Test fun refusesHeaderInjectionAndTransportManagedHeaders() {
        for (header in listOf("Host: evil.test", "Content-Length: 123", "Connection: close", "Expect: 100-continue", "Upgrade: websocket", "Bad Name: bad", "X-Token: {{token}}")) {
            val text = "@token = first\rsecond\nGET http://localhost\n$header"
            assertFailsWith<IllegalArgumentException> { HttpRequestDocument.requestAt(text, 0) }
        }
    }

    @Test fun refusesUnsupportedSchemesVersionsCredentialsFragmentsAndCharsets() {
        for (request in listOf("GET file:///etc/passwd", "GET ftp://localhost/file", "GET http://user:secret@localhost", "GET http://localhost/#fragment", "GET http://localhost:99999", "CONNECT http://localhost", "GET http://localhost HTTP/2", "GET http://localhost/path with spaces", "POST http://localhost\nContent-Type: text/plain; charset=ISO-8859-1\n\nbody"))
            assertFailsWith<IllegalArgumentException>(request) { HttpRequestDocument.requestAt(request, 0) }
    }

    @Test fun requiresBlankLineBeforeBodyAndRefusesHeadBody() {
        assertFailsWith<IllegalArgumentException> { HttpRequestDocument.requestAt("POST http://localhost\n{\"a\":1}", 0) }
        assertFailsWith<IllegalArgumentException> { HttpRequestDocument.requestAt("HEAD http://localhost\n\nbody", 0) }
        assertEquals(0, HttpRequestDocument.requestAt("GET http://localhost\nX-Empty:\n", 0).body.size)
    }

    @Test fun boundsExpandedHeaderTotalsAndUrlSize() {
        val text = "@value = " + "x".repeat(33_000) + "\nGET http://localhost\nX-One: {{value}}\nX-Two: {{value}}"
        assertTrue(assertFailsWith<IllegalArgumentException> { HttpRequestDocument.requestAt(text, 0) }.message!!.contains("64 KiB"))
        val url = "@path = " + "x".repeat(17_000) + "\nGET http://localhost/{{path}}"
        assertTrue(assertFailsWith<IllegalArgumentException> { HttpRequestDocument.requestAt(url, 0) }.message!!.contains("16 KiB"))
        val unicode = "GET http://localhost/" + "中".repeat(2000)
        assertTrue(assertFailsWith<IllegalArgumentException> { HttpRequestDocument.requestAt(unicode, 0) }.message!!.contains("16 KiB"))
    }

    @Test fun boundsHeaderCountAndRejectsUnsupportedUnicodeHeaderValues() {
        val text = "GET http://localhost\n" + (1..201).joinToString("\n") { "X-$it: value" }
        assertTrue(assertFailsWith<IllegalArgumentException> { HttpRequestDocument.requestAt(text, 0) }.message!!.contains("200 headers"))
        assertFailsWith<IllegalArgumentException> { HttpRequestDocument.requestAt("GET http://localhost\nX-Value: 中文", 0) }
    }
}
