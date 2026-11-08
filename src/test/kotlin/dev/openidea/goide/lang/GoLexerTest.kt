package dev.openidea.goide.lang

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GoLexerTest {
    @Test fun preservesUtf16OffsetsAndMultilineTokens() {
        val source = "package main\n// 中文😀\nvar 名称 = `第一行\nsecond line`\nfunc main() { println(名称) }"
        val lexer = GoLexer()
        lexer.start(source)
        var offset = 0
        val tokens = mutableListOf<Pair<String, String>>()
        while (lexer.tokenType != null) {
            assertEquals(offset, lexer.tokenStart)
            assertTrue(lexer.tokenEnd > lexer.tokenStart)
            tokens.add(lexer.tokenType.toString() to source.substring(lexer.tokenStart, lexer.tokenEnd))
            offset = lexer.tokenEnd
            lexer.advance()
        }
        assertEquals(source.length, offset)
        assertTrue(tokens.any { it.first == "GO_COMMENT" && it.second == "// 中文😀" })
        assertTrue(tokens.any { it.first == "GO_STRING" && '\n' in it.second })
        assertTrue(tokens.any { it.first == "GO_IDENTIFIER" && it.second == "名称" })
    }
    @Test fun distinguishesKeywordsFromIdentifiersAndProtectsCommentText() {
        val source = "var variable = \"// not comment\" /* func not keyword */"
        val lexer = GoLexer()
        lexer.start(source)
        val selected = mutableListOf<Pair<String, String>>()
        while (lexer.tokenType != null) {
            selected.add(lexer.tokenType.toString() to source.substring(lexer.tokenStart, lexer.tokenEnd))
            lexer.advance()
        }
        assertTrue("GO_KEYWORD" to "var" in selected)
        assertTrue("GO_IDENTIFIER" to "variable" in selected)
        assertTrue("GO_STRING" to "\"// not comment\"" in selected)
        assertTrue("GO_COMMENT" to "/* func not keyword */" in selected)
    }
}
