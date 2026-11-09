package dev.openidea.goide.run

import com.google.gson.JsonObject
import jetbrains.buildServer.messages.serviceMessages.ServiceMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GoTestOwnershipTest {
    private fun event(action: String, test: String? = null, output: String? = null, elapsed: Double? = null) = JsonObject().apply {
        addProperty("Action", action); addProperty("Package", "example.com/owned")
        test?.let { addProperty("Test", it) }; output?.let { addProperty("Output", it) }; elapsed?.let { addProperty("Elapsed", it) }
    }.toString() + "\n"
    private fun decode(records: List<String>) = records.mapNotNull { ServiceMessage.parse(it.trimEnd()) }
    private fun console(messages: List<ServiceMessage>) = messages.joinToString("") { it.attributes["out"] ?: if (it.messageName == "message") it.attributes["text"].orEmpty() else "" }

    @Test fun parentAndLeafLogsEachHaveOneNativeOwner() {
        val output = GoTestOutput("/tmp")
        val messages = decode(listOf(
            event("run", "TestParent"), event("output", "TestParent", "parent-before\n"),
            event("run", "TestParent/child"), event("output", "TestParent/child", "child-owned\n"),
            event("pass", "TestParent/child", elapsed = .125),
            event("output", "TestParent", "parent-after\n"), event("pass", "TestParent", elapsed = .2),
            event("run", "TestLeaf"), event("output", "TestLeaf", "leaf-owned\n"), event("pass", "TestLeaf", elapsed = 0.0),
            event("pass", elapsed = .679),
        ).flatMap(output::feed))
        val text = console(messages)
        for (line in listOf("parent-before", "child-owned", "parent-after", "leaf-owned")) assertEquals(1, Regex(line).findAll(text).count(), text)
        assertFalse(messages.any { it.messageName == "message" }, "Test logs must not also be emitted to the root console")
        assertEquals(setOf("go-test:example.com/owned#TestParent:1", "go-test:example.com/owned#TestParent/child:1", "go-test:example.com/owned#TestLeaf:1"), messages.filter { it.messageName == "testStdOut" }.map { it.attributes["nodeId"] }.toSet())
        assertEquals("125", messages.single { it.messageName == "testFinished" && it.attributes["name"] == "child" }.attributes["duration"])
        assertEquals("0", messages.single { it.messageName == "testFinished" && it.attributes["name"] == "TestLeaf" }.attributes["duration"])
        assertTrue(messages.filter { it.messageName == "testSuiteFinished" }.all { "duration" !in it.attributes })
        output.finish(0)
    }

    @Test fun largeUnicodeOutputStreamsBeforeFinishWithoutDuplicationOrTruncation() {
        val expected = "β😀|[]'\n".repeat(50_000)
        val output = GoTestOutput("/tmp")
        val records = mutableListOf<String>()
        output.consume(event("run", "TestLarge"), records::add)
        expected.chunked(1024).forEach { output.consume(event("output", "TestLarge", it), records::add) }
        assertEquals(expected, console(decode(records)), "All output must be available while this test is still running")
        output.consume(event("pass", "TestLarge"), records::add)
        output.finish(0, records::add)
        assertEquals(expected, console(decode(records)))
        val interrupted = GoTestOutput("/tmp")
        interrupted.feed(event("run", "TestInterrupted"))
        interrupted.feed(event("output", "TestInterrupted", expected))
        interrupted.close()
        assertTrue(interrupted.feed(event("output", "TestInterrupted", "late output")).isEmpty())
    }

    @Test fun failureOutputIsNotCopiedIntoFailureDetailsAndKeepsItsRecordedLocation() {
        val directory = java.nio.file.Path.of("/tmp/the actual package").toAbsolutePath().normalize()
        val output = GoTestOutput(directory.parent.toString(), mapOf("example.com/owned" to directory.toString()))
        val messages = decode(listOf(event("run", "TestBad"),
            event("output", "TestBad", "    owned_test.go:23: controlled-failure\n"),
            event("fail", "TestBad", elapsed = .0506), event("fail", elapsed = .679)).flatMap(output::feed))
        assertEquals(1, Regex("controlled-failure").findAll(console(messages)).count())
        assertEquals("", messages.single { it.messageName == "testFailed" }.attributes["details"])
        val hint = messages.single { it.messageName == "testStarted" }.attributes["locationHint"]!!
        assertEquals("file://${directory.resolve("owned_test.go")}:23", output.locationForToken(hint.substringAfter("://")))
        assertEquals("51", messages.single { it.messageName == "testFinished" }.attributes["duration"])
        assertEquals(mapOf("example.com/owned" to listOf("TestBad")), output.failures())
    }

}
