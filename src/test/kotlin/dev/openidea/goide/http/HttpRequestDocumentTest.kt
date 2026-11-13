package dev.openidea.goide.http

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HttpRequestDocumentTest {
    @Test fun selectsRequestAtCaretAndPreservesCrlfBody() {
        val text = "@host = http://127.0.0.1:8080\r\n### first\r\nGET {{host}}/first\r\n\r\n### echo\r\nPOST {{host}}/echo HTTP/1.1\r\nContent-Type: application/json\r\nX-Name: example\r\n\r\n{\r\n  \"value\":\"你好😀\"\r\n}\r\n"
        val first = HttpRequestDocument.requestAt(text, text.indexOf("GET"))
        val second = HttpRequestDocument.requestAt(text, text.indexOf("POST"))
        assertEquals("/first", first.uri.path)
        assertEquals(0, first.body.size)
        assertEquals("POST", second.method)
        assertEquals("{\r\n  \"value\":\"你好😀\"\r\n}", String(second.body, Charsets.UTF_8))
        assertEquals(listOf("Content-Type" to "application/json", "X-Name" to "example"), second.headers)
        assertEquals(6, second.line)
    }

    @Test fun resolvesUniqueFileVariablesIncludingForwardReferences() {
        val text = "@base = http://localhost:{{port}}\nGET {{base}}/one\n### two\n@port = 8081\nPOST {{base}}/two\nX-Port: {{port}}\n\n{{port}}"
        assertEquals(8081, HttpRequestDocument.requestAt(text, 0).uri.port)
        val second = HttpRequestDocument.requestAt(text, text.length)
        assertEquals(8081, second.uri.port)
        assertEquals("8081", second.headers.single().second)
        assertEquals("8081", String(second.body))
    }

    @Test fun duplicateDeclarationsFailTheirReferencesWithoutPoisoningIndependentRequests() {
        val text = "@step = one\nGET http://localhost/{{step}}\n### duplicate\n@step = two\nGET http://localhost/{{step}}\n### independent\nGET http://localhost/independent"
        val plan = HttpRequestDocument.plan(text)
        assertEquals(3, plan.entries.size)
        plan.entries.take(2).forEach { entry ->
            assertTrue(assertFailsWith<IllegalArgumentException> { plan.parse(entry) }.message!!.contains("Duplicate HTTP variable: step"))
        }
        assertEquals("/independent", plan.parse(plan.entries.last()).uri.path)
    }

    @Test fun planPreservesOrderAndDuplicateExplicitNamesAheadOfSeparatorTitles() {
        val text = "@base = http://localhost\n### first title\n# @name repeated\nGET {{base}}/one\n### second title\n// @name repeated\nPOST {{base}}/two\n### readable title\nGET {{base}}/three\n###\nget {{base}}/four\n###\nnot a request"
        val plan = HttpRequestDocument.plan(text)
        assertEquals(listOf("repeated", "repeated", "readable title", "GET {{base}}/four", "Request 5"), plan.entries.map { it.displayName })
        assertEquals(listOf(1, 2, 3, 4, 5), plan.entries.map { it.blockOrdinal })
        assertEquals(listOf("/one", "/two", "/three", "/four"), plan.entries.take(4).map { plan.parse(it).uri.path })
        assertFailsWith<IllegalArgumentException> { plan.parse(plan.entries.last()) }
    }

    @Test fun firstExplicitNameWinsWhenOneRequestHasMultipleNames() {
        val text = "### Separator title\n# @name firstExplicit\n# @name secondExplicit\nGET http://localhost/names"
        val plan = HttpRequestDocument.plan(text)
        assertEquals("firstExplicit", plan.entries.single().displayName)
        assertEquals("/names", plan.parse(plan.entries.single()).uri.path)
    }

    @Test fun matchesTheCapturedGoLandBatchPlanningVectors() {
        val resource = javaClass.classLoader.getResourceAsStream("http-plan-reference-20261113.json")!!
        val oracle = resource.bufferedReader(Charsets.UTF_8).use { JsonParser.parseString(it.readText()).asJsonObject }
        val cases = oracle.getAsJsonArray("cases")
        var successfulParses = 0
        var failedParses = 0
        for (item in cases) {
            val observed = item.asJsonObject
            val name = observed.get("file").asString
            val plan = HttpRequestDocument.plan(observed.get("document").asString)
            val expected = observed.getAsJsonArray("expectedRequests").map { it.asJsonObject }
            assertEquals(expected.map { it.get("name").asString }, plan.entries.map { it.displayName }, name)
            expected.zip(plan.entries).forEach { (request, entry) ->
                if (request.has("uri")) {
                    val spec = plan.parse(entry)
                    assertEquals(request.get("method").asString, spec.method, "$name: ${entry.displayName}")
                    assertEquals(request.get("uri").asString, spec.uri.toASCIIString(), "$name: ${entry.displayName}")
                    successfulParses++
                } else {
                    val expectedError = request.getAsJsonObject("error")
                    val failure = assertFailsWith<IllegalArgumentException>("$name: ${entry.displayName}") { plan.parse(entry) }
                    val kind = when (expectedError.get("kind").asString) {
                        "undefined-variable" -> "Undefined HTTP variable:"
                        "duplicate-variable" -> "Duplicate HTTP variable:"
                        else -> error("Unknown captured parse error kind")
                    }
                    assertTrue(failure.message!!.contains(kind) && failure.message!!.contains(expectedError.get("variable").asString), name)
                    failedParses++
                }
            }
        }
        assertEquals(7, cases.size())
        assertEquals(13, successfulParses)
        assertEquals(3, failedParses)
    }

    @Test fun isolatesMalformedDirectivesAndUndefinedVariablesWhileKeepingFileDeclarations() {
        val text = "@base = http://localhost\n### unsupported directive\n# @no-log\n@path = final\nGET {{base}}/bad\n### missing variable\nGET {{base}}/{{missing}}\n### valid afterwards\nGET {{base}}/{{path}}"
        val plan = HttpRequestDocument.plan(text)
        assertEquals(3, plan.entries.size)
        assertTrue(assertFailsWith<IllegalArgumentException> { plan.parse(plan.entries[0]) }.message!!.contains("directives are not supported"))
        assertTrue(assertFailsWith<IllegalArgumentException> { plan.parse(plan.entries[1]) }.message!!.contains("Undefined HTTP variable: missing"))
        assertEquals("/final", plan.parse(plan.entries[2]).uri.path)
        assertEquals("/final", HttpRequestDocument.requestAt(text, text.length).uri.path)
    }

    @Test fun skipsEmptyBlocksAndPreamblesWithoutDroppingTheirVariableDeclarations() {
        val text = "@base = http://localhost\n### empty\n# only a comment\n@path = final\n###\n\n### last\nGET {{base}}/{{path}}\n### trailing"
        val plan = HttpRequestDocument.plan(text)
        val entry = plan.entries.single()
        assertEquals(3, entry.blockOrdinal)
        assertEquals("/final", plan.parse(entry).uri.path)
        assertEquals(null, plan.entryAt(0))
        assertEquals(null, plan.entryAt(text.indexOf("# only")))
        assertEquals(entry, plan.entryAt(text.indexOf("### last")))
        assertEquals(null, plan.entryAt(text.length))
        assertTrue(assertFailsWith<IllegalArgumentException> { HttpRequestDocument.requestAt(text, text.length) }.message!!.contains("Place the caret"))
        assertEquals(0, HttpRequestDocument.plan("\n###\n# comment only\n###").entries.size)
    }

    @Test fun preservesUtf16OffsetsCrlfLinesAndFinalRequestAtEof() {
        val text = "@base = http://localhost\r\n### first\r\n# 😀 comment\r\n  POST {{base}}/one\r\nContent-Type: text/plain\r\n\r\n  body \t\r\n\r\n### second\r\nGET {{base}}/two"
        val plan = HttpRequestDocument.plan(text)
        val first = plan.entries[0]
        val second = plan.entries[1]
        assertEquals(text.indexOf("POST"), first.requestOffset)
        assertEquals(text.indexOf("# 😀"), first.startOffset)
        assertEquals(text.indexOf("### second"), first.endOffset)
        assertEquals(4, first.line)
        assertEquals(10, second.line)
        assertEquals(first, plan.entryAt(text.indexOf("### first") + 2))
        assertEquals(first, plan.entryAt(text.indexOf("body")))
        assertEquals(second, plan.entryAt(text.length))
        assertEquals("  body \t", String(plan.parse(first).body, Charsets.UTF_8))
        assertEquals(10, plan.parse(second).line)
    }

    @Test fun doesNotExpandAnotherRequestBodyWhenPlanningOrParsingAnIndependentEntry() {
        val text = "@large = " + "x".repeat(400_000) + "\nPOST http://localhost/too-large\n\n" + "{{large}}".repeat(10) + "\n### independent\nGET http://localhost/ok"
        val plan = HttpRequestDocument.plan(text)
        assertEquals(2, plan.entries.size)
        assertEquals("/ok", plan.parse(plan.entries.last()).uri.path)
        assertFailsWith<IllegalArgumentException> { plan.parse(plan.entries.first()) }
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
