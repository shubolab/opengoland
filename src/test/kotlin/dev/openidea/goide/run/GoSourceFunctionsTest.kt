package dev.openidea.goide.run

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GoSourceFunctionsTest {
    @Test fun entryPointScanIgnoresCommentsStringsMethodsAndNestedFunctions() {
        val source = """
            package main
            // func TestFake(t *testing.T) {}
            var text = "func TestString() {}"
            func (r Receiver) TestMethod() {}
            func main() { callback := func() {}; _ = callback }
            func TestValid(t *testing.T) { t.Run("row", func(t *testing.T) {}) }
        """.trimIndent()
        val parsed = GoSourceFunctions.scan(source)
        assertEquals("main", parsed.packageName)
        assertEquals(listOf("main", "TestValid"), parsed.functions.map { it.name })
        assertEquals("main", source.substring(parsed.functions[0].nameOffset, parsed.functions[0].nameOffset + 4))
        assertTrue(parsed.functions[0].endOffset < parsed.functions[1].nameOffset)
    }
    @Test fun goTestNamingRuleDoesNotTreatLowercaseSuffixAsATest() {
        assertTrue(GoSourceFunctions.isTestName("TestX"))
        assertTrue(GoSourceFunctions.isTestName("Test"))
        assertFalse(GoSourceFunctions.isTestName("Testing"))
        assertFalse(GoSourceFunctions.isTestName("BenchmarkX"))
    }
}
