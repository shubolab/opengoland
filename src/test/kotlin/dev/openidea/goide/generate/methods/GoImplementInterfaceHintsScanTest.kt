package dev.openidea.goide.generate.methods

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GoImplementInterfaceHintsScanTest {
    @Test fun topLevelGroupedGenericAndUnicodeHeadersKeepUtf16Offsets() {
        val source = """
            package fixture
            // 中文 😀 before all offsets
            type 第一 interface { Read([]byte) (int, error) }
            type Generic[T interface{ ~int | ~string }, U any] interface { Get(T) U }
            type (
                Value struct { Inner interface{ Hidden() } }
                Grouped /* comment { [ */ interface { /* } */ Save() }
                Pair[K, V comparable] interface { Pair(K) V }
                Alias = interface { AliasMethod() }
                Array [10]interface { ArrayMethod() }
                Last interface{}
            )
            type 𐐀Name interface { Unicode() }
            type Consecutive interface{}
        """.trimIndent()
        val found = GoInterfaceHeaders.scan(source)
        assertEquals(listOf("第一", "Generic", "Grouped", "Pair", "Alias", "Last", "𐐀Name", "Consecutive"), found.map { it.name })
        for (header in found) {
            assertEquals(header.name, source.substring(header.nameOffset, header.nameOffset + header.name.length))
            assertEquals('{', source[header.headerEnd - 1])
        }
        assertEquals(source.indexOf("𐐀Name"), found.single { it.name == "𐐀Name" }.nameOffset)
    }

    @Test fun stringsLocalDeclarationsAndArrayElementInterfacesNeverBecomeNamedInterfaceHints() {
        val source = """
            package fixture
            var fake = "type Quoted interface { }"
            var raw = `type Raw interface { }`
            /* type Comment interface { } */
            func local() { type Local interface { Run() }; _ = "}" }
            var function = func() { type Closure interface{} }
            type Box struct { Field interface { Nested() } }
            type Slice []interface { Element() }
            type Fixed [N]interface { Element() }
            type Product [N*2]interface { Element() }
            type Length [len("type Wrong interface{}")]interface { Element() }
            type Pointer *interface { Element() }
            type Good interface { Method(arg struct{ F string }, raw string) }
        """.trimIndent()
        assertEquals(listOf("Good"), GoInterfaceHeaders.scan(source).map { it.name })
    }

    @Test fun malformedBracketsAndUnclosedBodiesAreConservative() {
        assertTrue(GoInterfaceHeaders.scan("package p\nfunc broken() { type Local interface{}\n").isEmpty())
        assertTrue(GoInterfaceHeaders.scan("package p\ntype Broken[T any interface {}\n").isEmpty())
        assertTrue(GoInterfaceHeaders.scan("package p\ntype Broken interface {\n").isEmpty())
        assertEquals(listOf("Valid"), GoInterfaceHeaders.scan("package p\ntype Valid interface{}\nfunc broken(){ type Local interface{}\n").map { it.name })
    }
}
