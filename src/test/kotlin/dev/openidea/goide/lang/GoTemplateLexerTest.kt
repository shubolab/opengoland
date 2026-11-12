package dev.openidea.goide.lang

import com.intellij.psi.TokenType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GoTemplateLexerTest {
    @Test fun highlightsActionsWithoutTreatingOutputTextAsGo() {
        val source = "Hello, {{ if .User.Active }}{{ printf \"%q\" ${'$'}name | html }}{{ else }}guest{{ end }}!"
        val tokens = tokens(source)

        assertTrue("GO_TEMPLATE_TEXT" to "Hello, " in tokens)
        assertTrue("GO_TEMPLATE_ACTION_START" to "{{" in tokens)
        assertTrue("GO_TEMPLATE_KEYWORD" to "if" in tokens)
        assertTrue("GO_TEMPLATE_OPERATOR" to "." in tokens)
        assertTrue("GO_TEMPLATE_IDENTIFIER" to "User" in tokens)
        assertTrue("GO_TEMPLATE_IDENTIFIER" to "Active" in tokens)
        assertTrue("GO_TEMPLATE_BUILTIN" to "printf" in tokens)
        assertTrue("GO_TEMPLATE_STRING" to "\"%q\"" in tokens)
        assertTrue("GO_TEMPLATE_VARIABLE" to "\$name" in tokens)
        assertTrue("GO_TEMPLATE_OPERATOR" to "|" in tokens)
        assertTrue("GO_TEMPLATE_BUILTIN" to "html" in tokens)
        assertTrue("GO_TEMPLATE_ACTION_END" to "}}" in tokens)
        assertTrue("GO_TEMPLATE_TEXT" to "guest" in tokens)
        assertEquals(source.length, tokens.sumOf { it.second.length })
    }

    @Test fun preservesTrimDelimitersAndTreatsTemplateCommentsAtomically() {
        val source = "before {{- /* ignored {{ if .Broken }} */ -}} after"
        assertEquals(
            listOf(
                "GO_TEMPLATE_TEXT" to "before ",
                "GO_TEMPLATE_COMMENT" to "{{- /* ignored {{ if .Broken }} */ -}}",
                "GO_TEMPLATE_TEXT" to " after",
            ),
            tokens(source),
        )
    }

    @Test fun actionStateIsRestartableAndBounded() {
        val source = "text {{ range .Items }}item{{ end }} tail"
        val lexer = GoTemplateLexer()
        lexer.start(source, 5, source.indexOf(" tail"), 0)
        assertEquals(GoTemplateTokens.ACTION_START, lexer.tokenType)
        assertEquals(1, lexer.state)
        lexer.advance()
        assertEquals(TokenType.WHITE_SPACE, lexer.tokenType)
        assertEquals(1, lexer.state)

        var offset = lexer.tokenStart
        while (lexer.tokenType != null) {
            assertEquals(offset, lexer.tokenStart)
            assertTrue(lexer.tokenEnd > lexer.tokenStart)
            offset = lexer.tokenEnd
            lexer.advance()
        }
        assertEquals(source.indexOf(" tail"), offset)
    }

    @Test fun malformedActionsRemainLocalToTheirTemplateBoundary() {
        val source = "{{ if .Ready }}ok{{ else }} {{"
        val tokens = tokens(source)
        assertEquals("GO_TEMPLATE_KEYWORD" to "if", tokens.first { it.first == "GO_TEMPLATE_KEYWORD" })
        assertTrue("GO_TEMPLATE_ACTION_START" to "{{" in tokens)
        assertEquals("GO_TEMPLATE_TEXT" to "ok", tokens.first { it.first == "GO_TEMPLATE_TEXT" })
    }

    private fun tokens(source: String): List<Pair<String, String>> {
        val lexer = GoTemplateLexer()
        lexer.start(source)
        var offset = 0
        return buildList {
            while (lexer.tokenType != null) {
                assertEquals(offset, lexer.tokenStart)
                assertTrue(lexer.tokenEnd > lexer.tokenStart)
                add(lexer.tokenType.toString() to source.substring(lexer.tokenStart, lexer.tokenEnd))
                offset = lexer.tokenEnd
                lexer.advance()
            }
        }.also { assertEquals(source.length, offset) }
    }
}
