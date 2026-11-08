package dev.openidea.goide.run

import com.intellij.util.execution.ParametersListUtil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GoTestRerunPlanTest {
    @Test fun retrySelectsExactFailedPackageAndSubtestWithoutOldPatternOverrides() {
        val original = GoRunParameters(target = "./...", workingDirectory = "/work/original", buildFlags = "-race -run Old -count=1",
            arguments = "-test.run=Old -timeout 20s", environment = linkedMapOf("KEEP" to "original"))
        val retries = GoTestRerunPlan.create(mapOf("example.com/a" to listOf("TestA", "TestA/row[1]"), "example.com/b" to listOf("TestB/row+2")), original)
        assertEquals(2, retries.size)
        assertEquals("example.com/a", retries[0].parameters.target)
        assertEquals("^TestA$/^row\\[1\\]$", retries[0].parameters.testPattern)
        assertEquals("^TestB$/^row\\+2$", retries[1].parameters.testPattern)
        assertEquals(listOf("-race", "-count=1"), ParametersListUtil.parse(retries[0].parameters.buildFlags))
        assertEquals(listOf("-timeout", "20s"), ParametersListUtil.parse(retries[0].parameters.arguments))
        assertEquals("/work/original", retries[0].parameters.workingDirectory)
        original.environment["KEEP"] = "changed"
        assertEquals("original", retries[0].parameters.environment["KEEP"])
        assertTrue(retries[0].parameters.commandArguments(GoRunKind.TEST, "").contains("^TestA$/^row\\[1\\]$"))
    }

    @Test fun failedPackageInitializationRetriesWholePackageOnce() {
        val retry = GoTestRerunPlan.create(mapOf("example.com/a" to listOf("", "TestA")), GoRunParameters(testPattern = "Old")).single()
        assertEquals("example.com/a", retry.parameters.target)
        assertEquals("", retry.parameters.testPattern)
    }
}
