package dev.openidea.goide.run

import dev.openidea.goide.settings.GoToolchain
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GoSourceFunctionsTest {
    @Test fun entryPointScanIgnoresCommentsStringsMethodsAndNestedFunctions() {
        val source = """
            package main
            import "testing"
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
        assertEquals(listOf(GoFunctionKind.MAIN, GoFunctionKind.TEST), parsed.functions.map { it.kind })
    }
    @Test fun goTestNamingRuleDoesNotTreatLowercaseSuffixAsATest() {
        assertTrue(GoSourceFunctions.isTestName("TestX"))
        assertTrue(GoSourceFunctions.isTestName("Test"))
        assertFalse(GoSourceFunctions.isTestName("Testing"))
        assertFalse(GoSourceFunctions.isTestName("BenchmarkX"))
        assertFalse(GoSourceFunctions.isTestName("Testé"))
        assertFalse(GoSourceFunctions.isTestName("Test\uD801\uDC28")) // supplementary lowercase Deseret letter
        assertTrue(GoSourceFunctions.isTestName("Test\uD801\uDC00"))
        assertTrue(GoSourceFunctions.isTestName("Testª")) // Lo, although Java's isLowerCase includes Other_Lowercase
    }

    @Test fun signaturesWithTypeBracesAndFunctionResultsKeepTheirActualBodies() {
        val source = """
            package fixture
            import test "testing"
            func Before(value struct { X int }, callback func(interface { M() })) struct { X int } {
                nested := func() { _ = "}" }; nested()
                return struct { X int }{1}
            }
            func Factory[T interface { ~int | ~string }](value T) func() interface { M() } {
                return func() interface { M() } { return nil }
            }
            func Channel() <-chan func() map[string]struct { X int } { return nil }
            func Pointer() *struct { X int } { return nil }
            func TestAfter(t *test.T) { t.Log("body") }
        """.trimIndent()
        val parsed = GoSourceFunctions.scan(source)
        assertEquals(listOf("Before", "Factory", "Channel", "Pointer", "TestAfter"), parsed.functions.map { it.name })
        for ((function, next) in parsed.functions.zipWithNext()) {
            assertTrue(function.endOffset < next.nameOffset)
            assertEquals('}', source[function.endOffset - 1])
        }
        assertTrue(source.substring(parsed.functions[0].nameOffset, parsed.functions[0].endOffset).contains("return struct { X int }{1}"))
        assertEquals(GoFunctionKind.TEST, parsed.functions.last().kind)
    }

    @Test fun directionalChannelResultsUseWholeArrowTokensAndRejectSplitOperators() {
        val source = """
            package fixture
            func Receive() <-chan int { return nil }
            func Send() chan<- int { return nil }
            func Both() chan int { return nil }
            func Nested() <-chan chan<- func() struct { X int } { return nil }
            func Factory() func() <-chan map[string]struct { X int } { return nil }
            func Generic[T ~chan<- int | ~<-chan int](value T) T { return value }
            func BadReceive() < -chan int { return nil }
            func BadSend() chan< - int { return nil }
            func After() {}
        """.trimIndent()
        val parsed = GoSourceFunctions.scan(source)
        assertEquals(listOf("Receive", "Send", "Both", "Nested", "Factory", "Generic", "After"), parsed.functions.map { it.name })
        parsed.functions.zipWithNext().forEach { (function, next) ->
            assertEquals('}', source[function.endOffset - 1])
            assertTrue(function.endOffset < next.nameOffset)
        }
    }

    @Test fun testingEntryPointsNeedTheRightImportedTypeAndSignature() {
        val parsed = GoSourceFunctions.scan("""
            package fixture
            import (
                "fmt"
                check "testing"
            )
            func TestOK(t *check.T) {}
            func TestUnnamed(*check.T) {}
            func TestTrailing(t *check.T,) {}
            func BenchmarkOK(b *check.B) {}
            func FuzzOK(f *check.F) {}
            func TestWrong(t *check.B) {}
            func BenchmarkWrong(t *check.T) {}
            func FuzzWrong(t *check.T) {}
            func TestFake(t *testing.T) {}
            func TestNumber(1 *check.T) {}
            func TestTwo(a,b *check.T) {}
            func TestValue(t check.T) {}
            func TestVarargs(t ...*check.T) {}
            func TestReturns(t *check.T) int { return 1 }
            func TestGeneric[T any](t *check.T) {}
            func TestMain(t *check.T) {}
            func Benchmarking(b *check.B) {}
            func Fuzzing(f *check.F) {}
            func (r Receiver) TestMethod(t *check.T) {}
        """.trimIndent())
        assertEquals(mapOf("TestOK" to GoFunctionKind.TEST, "TestUnnamed" to GoFunctionKind.TEST,
            "TestTrailing" to GoFunctionKind.TEST, "BenchmarkOK" to GoFunctionKind.BENCHMARK, "FuzzOK" to GoFunctionKind.FUZZ),
            parsed.functions.filter { it.kind != GoFunctionKind.FUNCTION }.associate { it.name to it.kind })
        assertFalse(parsed.functions.any { it.name == "TestMethod" })
    }

    @Test fun dotRawAndEscapedTestingImportsKeepTheirLexicalBinding() {
        assertEquals(GoFunctionKind.FUZZ, GoSourceFunctions.scan("package fixture\nimport . `testing`\nfunc FuzzBytes(*F) {}\n").functions.single().kind)
        assertEquals(GoFunctionKind.BENCHMARK, GoSourceFunctions.scan("package fixture\nimport alias \"te\\x73ting\"\nfunc BenchmarkBytes(b *alias.B) {}\n").functions.single().kind)
        assertEquals(GoFunctionKind.FUNCTION, GoSourceFunctions.scan("package fixture\nimport alias \"example/testing\"\nfunc TestFake(t *alias.T) {}\n").functions.single().kind)
        assertEquals(GoFunctionKind.FUNCTION, GoSourceFunctions.scan("package fixture\nimport _ \"testing\"\nfunc TestFake(t *testing.T) {}\n").functions.single().kind)
    }

    @Test fun examplesAreRunnableOnlyWithOutputAndNoArgumentsOrResults() {
        val source = """
            package fixture
            func ExampleReady() { println("ok")
                // Output: ok
            }
            func ExampleUnordered() { println("ok")
                /* Unordered output: ok */
            }
            func ExampleEmpty() {
                // Output:
            }
            func ExampleNotExecuted() { println("ok") }
            func ExampleCommentAfter() {
                // Output: ok

                // An unrelated final comment.
            }
            func ExampleArgument(x int) {
                // Output:
            }
            func ExampleResult() int {
                return 1
                // Output:
            }
            func ExampleGeneric[T any]() {
                // Output:
            }
            func ExampleString() { _ = "// Output: fake" }
        """.trimIndent()
        assertEquals(listOf("ExampleReady", "ExampleUnordered", "ExampleEmpty"),
            GoSourceFunctions.scan(source).functions.filter { it.kind == GoFunctionKind.EXAMPLE }.map { it.name })
    }

    @Test fun mainAndBodylessDeclarationsDoNotLeakIntoTheNextFunction() {
        val source = """
            package main
            func External()
            func main(value int) {}
            func Main() {}
            func Valid[T interface { ~int }](value T) {}
            func Tail() {}
        """.trimIndent()
        val parsed = GoSourceFunctions.scan(source)
        assertEquals(listOf("main", "Main", "Valid", "Tail"), parsed.functions.map { it.name })
        assertTrue(parsed.functions.all { it.kind == GoFunctionKind.FUNCTION })
        assertEquals(GoFunctionKind.MAIN, GoSourceFunctions.scan("package main\nfunc main() {}\n").functions.single().kind)
        assertEquals(GoFunctionKind.FUNCTION, GoSourceFunctions.scan("package elsewhere\nfunc main() {}\n").functions.single().kind)
    }

    @Test fun utf16OffsetsAndIncompleteTrailingCodeStayInsideTheirOwnDeclarations() {
        val source = "package fixture\nimport \"testing\"\n// 中文 😀\nfunc Benchmark你好(b *testing.B) {}\nfunc Broken(value struct { X int }"
        val function = GoSourceFunctions.scan(source).functions.single()
        assertEquals("Benchmark你好", function.name)
        assertEquals(source.indexOf("Benchmark你好"), function.nameOffset)
        assertEquals(GoFunctionKind.BENCHMARK, function.kind)
        assertTrue(function.endOffset < source.indexOf("func Broken"))
    }

    @Test fun extremeTypeDepthCannotExhaustTheNativeActionThread() {
        val source = "package fixture\nimport \"testing\"\nfunc Deep() " + "*".repeat(3000) + "int { return nil }\nfunc TestAfter(t *testing.T) {}\n"
        assertEquals(GoFunctionKind.TEST, GoSourceFunctions.scan(source).functions.single { it.name == "TestAfter" }.kind)
    }

    @Test fun unicodeEntryPointNamesAgreeWithTheRealGoTestDriver() {
        val source = """
            package fixture
            import "testing"
            func Testª(t *testing.T) {}
            func Test𐐨(t *testing.T) {}
            func Test𐐀(t *testing.T) {}
            func Benchmarkª(b *testing.B) {}
            func Fuzzª(f *testing.F) {}
        """.trimIndent()
        val root = Files.createTempDirectory("opengoland-test-entry-names-")
        var process: Process? = null
        try {
            Files.writeString(root.resolve("go.mod"), "module example.org/entrynames\n\ngo 1.24.0\n")
            Files.writeString(root.resolve("names_test.go"), source)
            val go = GoToolchain.resolve("go", System.getProperty("opengoland.testGo") ?: System.getenv("OPENGOLAND_GO_TEST").orEmpty()) ?: throw AssertionError("The repository's required Go toolchain is unavailable")
            // Vet separately diagnoses the deliberately non-test lowercase name;
            // this probe compares the driver's executable entry selection.
            process = ProcessBuilder(go, "test", "-vet=off", "-list", ".").directory(root.toFile()).redirectErrorStream(true).apply {
                environment().putAll(mapOf("GOWORK" to "off", "GOTOOLCHAIN" to "local", "GOPROXY" to "off", "GOTELEMETRY" to "off", "GOFLAGS" to "",
                    "GOROOT" to Path.of(go).toRealPath().parent.parent.toString(), "GOOS" to "", "GOARCH" to ""))
            }.start()
            assertTrue(process.waitFor(45, TimeUnit.SECONDS), "The owned Go naming probe timed out")
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(0, process.exitValue(), output)
            val actual = output.lineSequence().filter { it.startsWith("Test") || it.startsWith("Benchmark") || it.startsWith("Fuzz") }.toSet()
            val inferred = GoSourceFunctions.scan(source).functions.filter { it.kind in setOf(GoFunctionKind.TEST, GoFunctionKind.BENCHMARK, GoFunctionKind.FUZZ) }.map { it.name }.toSet()
            assertEquals(setOf("Testª", "Test𐐀", "Benchmarkª", "Fuzzª"), actual)
            assertEquals(actual, inferred)
        } finally {
            process?.takeIf { it.isAlive }?.let { owned ->
                owned.descendants().forEach { it.destroyForcibly() }; owned.destroyForcibly(); owned.waitFor(5, TimeUnit.SECONDS)
            }
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
}
