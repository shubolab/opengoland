package dev.openidea.goide.lang

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DockerfileLexerTest {
    @Test fun highlightsInstructionsVariablesStringsAndOperators() {
        val source = "# syntax=docker/dockerfile:1\nARG BASE_IMAGE=golang:1.27\nFROM ${'$'}{BASE_IMAGE} AS builder\nRUN echo \"${'$'}HOME\" && mkdir -p /out\nCOPY [\"go.mod\", \"/src/go.mod\"]\n"
        val tokens = tokens(source)
        assertTrue("DOCKERFILE_COMMENT" to "# syntax=docker/dockerfile:1" in tokens)
        assertTrue("DOCKERFILE_INSTRUCTION" to "ARG" in tokens)
        assertTrue("DOCKERFILE_INSTRUCTION" to "FROM" in tokens)
        assertTrue("DOCKERFILE_VARIABLE" to "${'$'}{BASE_IMAGE}" in tokens)
        assertTrue("DOCKERFILE_KEYWORD" to "AS" in tokens)
        assertTrue("DOCKERFILE_INSTRUCTION" to "RUN" in tokens)
        assertTrue("DOCKERFILE_STRING" to "\"${'$'}HOME\"" in tokens)
        assertTrue("DOCKERFILE_OPERATOR" to "&&" in tokens)
        assertTrue("DOCKERFILE_INSTRUCTION" to "COPY" in tokens)
        assertTrue("DOCKERFILE_LBRACKET" to "[" in tokens)
    }

    @Test fun instructionWordsInsideArgumentsStayOrdinaryText() {
        val source = "RUN printf 'RUN FROM COPY'\n  ENV VALUE=from\n# comment\n  # indented comment"
        val tokens = tokens(source).filter { it.first != "WHITE_SPACE" }
        assertEquals(listOf("RUN", "FROM", "COPY").count { it == "RUN" }, tokens.count { it == ("DOCKERFILE_INSTRUCTION" to "RUN") })
        assertEquals(0, tokens.count { it == ("DOCKERFILE_INSTRUCTION" to "FROM") })
        assertEquals(1, tokens.count { it == ("DOCKERFILE_INSTRUCTION" to "ENV") })
        assertEquals(2, tokens.count { it.first == "DOCKERFILE_COMMENT" })
    }

    @Test fun preservesUtf16OffsetsAndBounds() {
        val source = "# 注释😀\nFROM golang:1.27 AS 构建阶段\nRUN echo ${'$'}HOME\n"
        for (begin in 0..source.length) for (end in begin..source.length) {
            val lexer = DockerfileLexer()
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
        val lexer = DockerfileLexer()
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
