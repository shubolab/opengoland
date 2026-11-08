package dev.openidea.goide.semantic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GoIdentifiersTest {
    @Test fun goNamesUseUnicodeLettersAndDecimalDigits() {
        listOf("Greeting", "_value2", "价格", "𐐀Value", "v٣").forEach { assertTrue(GoIdentifiers.valid(it), it) }
        listOf("", "_", "3name", "var", "func", "go", "foo-bar", "foo$", "a\u0301", "😀", "a²").forEach { assertFalse(GoIdentifiers.valid(it), it) }
    }

    @Test fun caretWordsRespectUtf16CodePointsAndDoNotAbsorbPunctuation() {
        val source = "fmt.Println(价格 + 𐐀Value2) // 😀"
        assertEquals("Println", GoIdentifiers.wordAt(source, source.indexOf("Println") + 3))
        assertEquals("价格", GoIdentifiers.wordAt(source, source.indexOf("价格") + 1))
        assertEquals("𐐀Value2", GoIdentifiers.wordAt(source, source.indexOf("Value2") + 2))
        assertEquals("", GoIdentifiers.wordAt(source, source.indexOf("😀") + 2))
        assertEquals("", GoIdentifiers.wordAt(source, -1))
    }
}
