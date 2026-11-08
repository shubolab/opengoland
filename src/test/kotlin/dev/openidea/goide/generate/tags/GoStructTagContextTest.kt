package dev.openidea.goide.generate.tags

import junit.framework.TestCase

class GoStructTagContextTest : TestCase() {
    fun testNestedStructsTagsAndUnicodeRemainInsideOnlyTheirOwnBody() {
        val source = """
            package fixture
            // 😀 struct { not a declaration }
            var text = "struct { fake }"
            type Subject struct {
                ID string `json:"id" note:"{😀}"`
                Nested struct { Value int }
            }
            func After() { _ = 1 }
        """.trimIndent()
        for (needle in listOf("Subject struct", "ID string", "json:", "Nested struct", "Value int")) {
            val offset = source.indexOf(needle) + if (needle == "Subject struct") "Subject ".length else 0
            assertTrue(needle, GoStructTagContext.contains(source, offset))
        }
        for (needle in listOf("not a declaration", "fake", "After()", "_ = 1"))
            assertFalse(needle, GoStructTagContext.contains(source, source.indexOf(needle)))
    }

    fun testAnonymousStructAndCommentBetweenKeywordAndBrace() {
        val source = "package fixture\nvar v = struct /* 😀 */ { Field string }{}\n"
        assertTrue(GoStructTagContext.contains(source, source.indexOf("Field")))
        assertFalse(GoStructTagContext.contains(source, source.lastIndexOf('{')))
        assertFalse(GoStructTagContext.contains(source, -1))
        assertFalse(GoStructTagContext.contains(source, source.length + 1))
    }
}
