package dev.openidea.goide.generate.tests

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GoGenerateTestsModelTest {
    @Test fun concreteTypesSplitOnlyAtTopLevelGoCommas() {
        val types = listOf("map[string]Pair[int, string]", "func(int, string) (bool, error)", "struct { Value string `json:\"a,b\"` }", "[len(\"a,b\")]byte")
        assertEquals(types, splitGoTestTypeArguments(types.joinToString(", ") + ", // trailing comment"))
        assertEquals(listOf("int /* inner, comma */", "[]string"), splitGoTestTypeArguments("int /* inner, comma */, []string"))
        assertEquals(emptyList(), splitGoTestTypeArguments(" // no concrete types"))
        for (invalid in listOf(",int", "int,,string", "map[string]int]", "func(int, string", "`unterminated", "int /* unfinished"))
            assertFailsWith<IllegalArgumentException>(invalid) { splitGoTestTypeArguments(invalid) }
    }

    @Test fun receiverNavigationPreservesUnicodeOffsetsAndRealBodyBoundaries() {
        val source = """
            package fixture
            // 中文 😀 func (r Fake) Hidden() {}
            func (r *Reader[T]) Read(value struct { A int }) interface { M() } { return nil }
            func (r Writer) Main() {}
            func Main() {}
            func Tail() { _ = func(x int) func(string) bool { return nil } }
        """.trimIndent()
        val symbols = goTestNavigationSymbols(source)
        assertEquals(listOf("Read", "Main", "Main", "Tail"), symbols.map { it.name })
        assertEquals(listOf("Reader", "Writer", "", ""), symbols.map { it.receiver })
        assertEquals(listOf("Reader_Read", "Read"), symbols[0].testCores())
        assertEquals(listOf("Writer_Main", "Main"), symbols[1].testCores())
        assertEquals(listOf("MainFunction"), symbols[2].testCores())
        assertEquals(source.indexOf("Read(value"), symbols[0].offset)
        assertEquals('}', source[symbols[0].end - 1])
    }

    @Test fun declarationDisplaysVariadicElementTypeWithoutRemovingNestedSlices() {
        fun declaration(type: String, variadic: Boolean): String {
            val symbol = JsonObject().apply {
                addProperty("name", "Collect")
                add("typeParameters", JsonArray()); add("results", JsonArray())
                add("parameters", JsonArray().apply {
                    add(JsonObject().apply { addProperty("name", "args"); addProperty("type", type); addProperty("variadic", variadic) })
                })
            }
            return GoGenerateTestsRow(symbol, true, "TestCollect", "").declaration()
        }
        assertEquals("Collect(args ...int)", declaration("[]int", true))
        assertEquals("Collect(args ...[]int)", declaration("[][]int", true))
        assertEquals("Collect(args []int)", declaration("[]int", false))
    }
}
