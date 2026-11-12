package dev.openidea.goide.terminal

import junit.framework.TestCase

class GoTerminalCompletionFallbackTest : TestCase() {
    fun testCompletesGoSubcommandAfterPromptText() {
        assertEquals(
            "go test",
            resolveGoTerminalCompletion(" ~/project  main !2  go te"),
        )
    }

    fun testCompletesCommonGoTestOption() {
        assertEquals(
            "go test -run",
            resolveGoTerminalCompletion(" ~/project  main !2  go test -r"),
        )
    }

    fun testLeavesEmptyAndExactInputsUnchanged() {
        assertNull(resolveGoTerminalCompletion("go "))
        assertNull(resolveGoTerminalCompletion("go test"))
    }

    fun testLeavesUnrelatedCommandsUnchanged() {
        assertNull(resolveGoTerminalCompletion("git status"))
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
