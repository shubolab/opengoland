package dev.openidea.goide.lang

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SqlLexerTest {
    @Test fun highlightsDialectNeutralSqlTokens() {
        val source = "SELECT u.\"display name\", $1, 12.5e2 FROM users u WHERE u.id >= $2 -- filter\nAND u.name <> 'O''Brien'"
        val tokens = tokens(source)

        assertTrue("SQL_KEYWORD" to "SELECT" in tokens)
        assertTrue("SQL_IDENTIFIER" to "users" in tokens)
        assertTrue("SQL_QUOTED_IDENTIFIER" to "\"display name\"" in tokens)
        assertTrue("SQL_VARIABLE" to "\$1" in tokens)
        assertTrue("SQL_NUMBER" to "12.5e2" in tokens)
        assertTrue("SQL_OPERATOR" to ">=" in tokens)
        assertTrue("SQL_COMMENT" to "-- filter" in tokens)
        assertTrue("SQL_STRING" to "'O''Brien'" in tokens)
    }

    @Test fun handlesBlockCommentsDollarQuotesAndQuotedIdentifiers() {
        val source = "/* outer /* nested */ done */ select [Order], `plain`, \$tag\$a 'quoted' value\$tag\$"
        val tokens = tokens(source)
        assertTrue("SQL_COMMENT" to "/* outer /* nested */ done */" in tokens)
        assertTrue("SQL_QUOTED_IDENTIFIER" to "[Order]" in tokens)
        assertTrue("SQL_QUOTED_IDENTIFIER" to "`plain`" in tokens)
        assertTrue("SQL_STRING" to "\$tag\$a 'quoted' value\$tag\$" in tokens)
    }

    @Test fun preservesUtf16OffsetsAndBounds() {
        val source = "select 名称, '值😀' from 表 where ${'$'}1::int = 1"
        for (begin in 0..source.length) for (end in begin..source.length) {
            val lexer = SqlLexer()
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
        val lexer = SqlLexer()
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
