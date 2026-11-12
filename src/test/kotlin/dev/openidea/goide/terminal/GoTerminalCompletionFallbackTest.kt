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
}
