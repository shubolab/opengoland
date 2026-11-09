package dev.openidea.goide.debug

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows

class GoDebugTestManagedArgumentsTest {
    @Test fun managedOutputKeepsBuildAndTestFlagsAndReplacesKnownVerbosity() {
        val args = GoTestDebugArguments.create(listOf("-race", "-v=false", "-tags", "integration"),
            listOf("-run", "^TestParent$/^bad$", "-v=true", "-timeout=10s"), "", true)
        assertEquals(listOf("-race", "-tags", "integration"), args.buildFlags)
        assertEquals(listOf("-test.v=test2json", "-test.run", "^TestParent$/^bad$", "-test.timeout=10s"), args.arguments)
    }
    @Test fun originalThreeArgumentApiKeepsItsExistingVerbosity() {
        assertEquals(listOf("-test.v=false"), GoTestDebugArguments.create(emptyList(), listOf("-v=false")).arguments)
    }
    @Test fun conflictingRawVerbosityIsExplainedRatherThanSilentlyLosingFraming() {
        assertThrows(IllegalArgumentException::class.java) {
            GoTestDebugArguments.create(emptyList(), listOf("-args", "-test.v=false"), "", true)
        }
        assertEquals(listOf("-test.v=test2json", "-custom", "中文😀"),
            GoTestDebugArguments.create(emptyList(), listOf("-args", "-custom", "中文😀"), "", true).arguments)
        assertEquals(listOf("-test.v=test2json", "--", "-test.v=false"),
            GoTestDebugArguments.create(emptyList(), listOf("--", "-test.v=false"), "", true).arguments)
    }
}
