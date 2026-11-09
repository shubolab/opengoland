package dev.openidea.goide.lang

import com.intellij.psi.TokenType
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

    @Test fun numericBoundariesAgreeWithTheGoScannerInsteadOfConsumingIdentifiers() {
        // Independently checked with the real Go 1.24.3 go/scanner, including
        // malformed numeric literals whose diagnostic recovery is still lexical.
        val source = "1abc 0x1.foo 12..3 1e+2i 0b123x 0x1p-2i .5e2 1p2x"
        assertEquals(listOf(
            "GO_NUMBER" to "1", "GO_IDENTIFIER" to "abc",
            "GO_NUMBER" to "0x1.f", "GO_IDENTIFIER" to "oo",
            "GO_NUMBER" to "12.", "GO_NUMBER" to ".3",
            "GO_NUMBER" to "1e+2i", "GO_NUMBER" to "0b123", "GO_IDENTIFIER" to "x",
            "GO_NUMBER" to "0x1p-2i", "GO_NUMBER" to ".5e2", "GO_NUMBER" to "1p2", "GO_IDENTIFIER" to "x",
        ), tokens(source).filter { it.kind != "WHITE_SPACE" }.map { it.kind to it.text })
    }

    @Test fun goIdentifiersUseUnicodeLettersAndDecimalDigitsWithoutJavaIdentifierExtras() {
        val source = "x\u0301 Ⅰx Ⅷ 你好 名𝟘 𐐀name \u200c １x _x ²x"
        assertEquals(listOf("x", "x", "你好", "名𝟘", "𐐀name", "x", "_x", "x"),
            tokens(source).filter { it.kind == "GO_IDENTIFIER" }.map { it.text })
        assertEquals(listOf("\u0301", "Ⅰ", "Ⅷ", "\u200c", "１", "²"),
            tokens(source).filter { it.kind == "BAD_CHARACTER" }.map { it.text })
    }

    @Test fun onlyGoWhitespaceAndAnInitialBomAreWhitespace() {
        val source = "\uFEFF \t\r\n\u000b\u000c\u2003\u00a0\uFEFF"
        val tokens = tokens(source)
        assertEquals(listOf("\uFEFF", " \t\r\n"), tokens.filter { it.kind == "WHITE_SPACE" }.map { it.text })
        assertEquals(listOf("\u000b", "\u000c", "\u2003", "\u00a0", "\uFEFF"),
            tokens.filter { it.kind == "BAD_CHARACTER" }.map { it.text })
    }

    @Test fun invalidInterpretedLiteralsNeverConsumeTheNextLine() {
        val source = "\"bad\\\nnext\"\n'bad\\\nnext'\n`raw\\\nnext`"
        assertEquals(listOf("\"bad\\", "\"", "'bad\\", "'", "`raw\\\nnext`"),
            tokens(source).filter { it.kind == "GO_STRING" }.map { it.text })
        assertEquals(listOf("next", "next"), tokens(source).filter { it.kind == "GO_IDENTIFIER" }.map { it.text })
    }

    @Test fun operatorsUseLongestTokensAndUnknownCharactersAreNotOperators() {
        val source = "a:=b ... c<-d <<= >>= &^= != ++ @?\\$"
        assertEquals(listOf(":=", "...", "<-", "<<=", ">>=", "&^=", "!=", "++"),
            tokens(source).filter { it.kind == "GO_OPERATOR" }.map { it.text })
        assertEquals(listOf("@", "?", "\\", "$"), tokens(source).filter { it.kind == "BAD_CHARACTER" }.map { it.text })
    }

    @Test fun boundedLexerNeverReadsPastItsEndOrSplitsTheFollowingRange() {
        val source = "A𝟘Z 𐐀名 \"a\\\nnext\" `first\nsecond` /* 多😀 */ 0x1p+2i"
        for (start in 0..source.length) {
            for (end in start..source.length) {
                val lexer = GoLexer()
                lexer.start(source, start, end, 0)
                var offset = start
                while (lexer.tokenType != null) {
                    assertEquals(offset, lexer.tokenStart)
                    assertTrue(lexer.tokenEnd > offset && lexer.tokenEnd <= end, "[$start,$end): $offset..${lexer.tokenEnd}")
                    offset = lexer.tokenEnd
                    lexer.advance()
                }
                assertEquals(end, offset)
            }
        }
    }

    @Test fun restartingAtEveryReportedTokenBoundaryPreservesUtf16Tokens() {
        val source = "\uFEFFpackage 名𝟘\r\nvar raw = `first😀\nsecond`\n// end\nfunc f() { x:=0x1p2i; _=\"bad\\\nnext }"
        val all = tokens(source)
        all.forEachIndexed { index, token -> assertEquals(all.drop(index), tokens(source, token.start)) }
        assertTrue(all.any { it.kind == TokenType.WHITE_SPACE.toString() })
    }

    private data class Token(val kind: String, val start: Int, val end: Int, val text: String)
    private fun tokens(source: String, start: Int = 0): List<Token> {
        val lexer = GoLexer()
        lexer.start(source, start, source.length, 0)
        return buildList {
            while (lexer.tokenType != null) {
                add(Token(lexer.tokenType.toString(), lexer.tokenStart, lexer.tokenEnd, source.substring(lexer.tokenStart, lexer.tokenEnd)))
                lexer.advance()
            }
        }
    }
}
