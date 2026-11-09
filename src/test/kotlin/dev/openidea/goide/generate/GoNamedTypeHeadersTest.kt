package dev.openidea.goide.generate

import junit.framework.TestCase

class GoNamedTypeHeadersTest : TestCase() {
    fun testNamedGenericGroupedStructsKeepNativeUtf16BodyRanges() {
        val source = """
            package fixture
            // 😀 before every name offset
            type Model[T ~string] struct { Label T; Nested struct { Value int } }
            type (
                First struct { Name string }
                Alias = struct { Value int }
                Array [N]struct { Value int }
                Last struct{}
            )
            type 𐐀Name struct { Value int }
        """.trimIndent()
        val values = GoNamedTypeHeaders.scan(source, "struct")
        assertEquals(listOf("Model", "First", "Alias", "Last", "𐐀Name"), values.map { it.name })
        assertEquals(listOf("Alias"), values.filter { it.alias }.map { it.name })
        for (header in values) {
            assertEquals(header.name, source.substring(header.nameOffset, header.nameOffset + header.name.length))
            assertEquals('{', source[header.headerEnd - 1])
            assertEquals('}', source[header.bodyEnd - 1])
        }
        assertTrue(values.first().bodyEnd > source.indexOf("Nested struct"))
    }

    fun testQuotedLocalAnonymousAndIncompleteDeclarationsDoNotBecomePackageHelperTargets() {
        val source = """
            package fixture
            var text = "type Fake struct { Value int }"
            var anonymous struct { Value int }
            func local() { type Local struct{ Value int } }
            type Good struct{ Value int }
            type Broken[T any struct {
        """.trimIndent()
        assertEquals(listOf("Good"), GoNamedTypeHeaders.scan(source, "struct").map { it.name })
        assertTrue(GoNamedTypeHeaders.scan("package p\ntype Broken struct{", "struct").isEmpty())
    }

    fun testDirectionalChannelAndFunctionConstraintsUseTheActualLongestGoOperator() {
        val source = """
            package fixture
            // 😀 direction is one <- token, including in constraints.
            type Receive[T <-chan int] struct { Value T }
            type Send[T chan<- int] struct { Value T }
            type Both[T chan int] struct { Value T }
            type Callback[T func() <-chan int] struct { Value T }
            type (
                ReceiveInterface[T <-chan int] interface { Get() T }
                SendInterface[T chan<- int] interface { Get() T }
            )
            type Array [N - 1]struct { Value int }
        """.trimIndent()
        val structs = GoNamedTypeHeaders.scan(source, "struct")
        assertEquals(listOf("Receive", "Send", "Both", "Callback"), structs.map { it.name })
        assertEquals(listOf("ReceiveInterface", "SendInterface"), GoNamedTypeHeaders.scan(source, "interface").map { it.name })
        structs.forEach { assertEquals(it.name, source.substring(it.nameOffset, it.nameOffset + it.name.length)) }
        for (invalid in listOf("< -chan int", "< /* gap */ -chan int", "<\n-chan int")) {
            assertTrue("Split punctuation is not a receive operator: $invalid", GoNamedTypeHeaders.scan("package p\ntype Broken[T $invalid] struct{}", "struct").isEmpty())
        }
    }
}
