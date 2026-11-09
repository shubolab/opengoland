package dev.openidea.goide.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GoModuleEnvironmentTest {
    @Test fun windowsCreationOverridesRemoveMixedCaseDuplicatesAndPreserveTheNativePath() {
        val inherited = linkedMapOf("GoRoot" to "other-sdk", "goroot" to "second-sdk", "gOfLaGs" to "unsafe-flags",
            "goWork" to "other-workspace", "goEnv" to "other-config", "GoToolChain" to "auto", "Go111Module" to "off",
            "goTelemetry" to "on", "tErM" to "xterm", "Path" to "native-path", "GoProxy" to "user-proxy", "CUSTOM" to "retained")
        val before = inherited.toMap()
        val actual = GoModuleProcesses.creationEnvironment(inherited, true)
        assertEquals(before, inherited)
        assertFalse(actual.keys.any { it.equals("GOROOT", true) || it.equals("GOFLAGS", true) })
        for ((name, value) in mapOf("GOWORK" to "off", "GOENV" to "off", "GOTOOLCHAIN" to "local", "GO111MODULE" to "on", "GOTELEMETRY" to "off", "TERM" to "dumb")) {
            assertEquals(listOf(name), actual.keys.filter { it.equals(name, true) })
            assertEquals(value, actual[name])
        }
        assertEquals("native-path", actual["Path"])
        assertFalse(actual.containsKey("PATH"))
        assertEquals("user-proxy", actual["GoProxy"])
        assertEquals("retained", actual["CUSTOM"])
    }

    @Test fun posixCreationEnvironmentRetainsItsCaseSensitiveNames() {
        val inherited = mapOf("GOROOT" to "old", "GOFLAGS" to "flags", "GoRoot" to "case-sensitive-other",
            "goflags" to "case-sensitive-flags", "GOWORK" to "old-work", "goWork" to "case-sensitive-work", "PATH" to "native-path")
        val actual = GoModuleProcesses.creationEnvironment(inherited, false)
        assertFalse(actual.containsKey("GOROOT"))
        assertFalse(actual.containsKey("GOFLAGS"))
        assertEquals("case-sensitive-other", actual["GoRoot"])
        assertEquals("case-sensitive-flags", actual["goflags"])
        assertEquals("case-sensitive-work", actual["goWork"])
        assertEquals("off", actual["GOWORK"])
        assertEquals("native-path", actual["PATH"])
        assertTrue(actual.containsKey("GOTOOLCHAIN"))
    }
}
