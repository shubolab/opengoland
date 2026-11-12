package dev.openidea.goide.terminal

import junit.framework.TestCase

class GoTerminalCompletionFallbackTest : TestCase() {
    fun testShowsAllSubcommandsAfterPromptText() {
        assertEquals(
            listOf("go test", "go telemetry"),
            resolveGoTerminalCompletions(" ~/project  main !2  go te"),
        )
    }

    fun testCompletesUniqueGoSubcommand() {
        assertEquals(
            "go test",
            resolveGoTerminalCompletion(" ~/project  main !2  go tes"),
        )
    }

    fun testShowsAllMatchingGoTestOptions() {
        assertEquals(
            listOf("go test -run", "go test -race"),
            resolveGoTerminalCompletions(" ~/project  main !2  go test -r"),
        )
        assertEquals(
            "go test -run",
            resolveGoTerminalCompletion(" ~/project  main !2  go test -ru"),
        )
    }

    fun testLeavesEmptyAndExactInputsUnchanged() {
        assertNull(resolveGoTerminalCompletion("go "))
        assertNull(resolveGoTerminalCompletion("go test"))
    }

    fun testLeavesUnrelatedCommandsUnchanged() {
        assertNull(resolveGoTerminalCompletion("git status"))
    }

    fun testAmbiguousSubcommandPrefixReturnsVisibleChoices() {
        assertEquals(
            listOf("go test", "go telemetry", "go tool"),
            resolveGoTerminalCompletions("go t"),
        )
    }

    fun testExactCommandDoesNotReopenCompletion() {
        assertTrue(resolveGoTerminalCompletions("go test").isEmpty())
    }

    fun testArgumentsAreNotTreatedAsSubcommands() {
        assertTrue(resolveGoTerminalCompletions("go test -run te").isEmpty())
        assertTrue(resolveGoTerminalCompletions("go run t").isEmpty())
    }

    fun testUnixClearSequenceUsesLineKill() {
        assertTrue(terminalLineClearSequence("Mac OS X", 42).contentEquals(byteArrayOf(0x15)))
        assertTrue(terminalLineClearSequence("Linux", 42).contentEquals(byteArrayOf(0x15)))
    }

    fun testWindowsClearSequenceUsesEditableLineBackspaces() {
        val sequence = terminalLineClearSequence("Windows 11", 7)
        assertEquals(7, sequence.size)
        assertTrue(sequence.all { it == 0x08.toByte() })
    }

    fun testWindowsClearSequenceNeverSendsAnEmptyControlSequence() {
        assertTrue(terminalLineClearSequence("Windows 11", 0).contentEquals(byteArrayOf(0x08)))
    }
}
