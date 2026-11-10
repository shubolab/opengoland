package dev.openidea.goide.settings

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.project.Project
import java.lang.reflect.Proxy
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/** Parent controls are simulated maps; the command fixture is never started. Native inheritance is separate. */
class GoGoplsEnvironmentTest {
    @Test fun onlyExactConfiguredAutoBecomesPresentEmpty() {
        for (raw in listOf("auto", "", "off", "/workspace 中文😀/go.work", "Auto", " auto", "auto ")) {
            val overrides = linkedMapOf("GOWORK" to raw, "UNCHANGED" to " spaces = preserved ")
            val adapted = GoToolchain.goplsEnvironment(overrides, emptyMap(), windows = false)
            assertTrue(adapted.containsKey("GOWORK"))
            assertEquals(if (raw == "auto") "" else raw, adapted["GOWORK"])
            assertEquals(" spaces = preserved ", adapted["UNCHANGED"])
            assertEquals(raw, overrides["GOWORK"])
        }
    }

    @Test fun simulatedInheritedAutoIsOverriddenButExplicitProjectValuesWin() {
        val parent = linkedMapOf("GOWORK" to "auto", "PARENT_ONLY" to "kept in parent")
        val inherited = GoToolchain.goplsEnvironment(mapOf("PROJECT_ONLY" to "kept"), parent, windows = false)
        assertTrue(inherited.containsKey("GOWORK"), "Removing the key would re-expose parent auto in gopls")
        assertEquals("", inherited["GOWORK"])
        assertFalse(inherited.containsKey("PARENT_ONLY"), "The adapter must not copy unrelated parent variables into initialize.env")
        for (raw in listOf("", "off", "/workspace/go.work", "invalid-relative", "Auto")) {
            val explicit = GoToolchain.goplsEnvironment(mapOf("GOWORK" to raw), parent, windows = false)
            assertEquals(mapOf("GOWORK" to raw), explicit, "Project value must override simulated inherited auto")
        }
        assertEquals("auto", parent["GOWORK"])
        assertEquals(emptyMap(), GoToolchain.goplsEnvironment(emptyMap(), mapOf("GOWORK" to "off"), windows = false))
        // Simulated CONSOLE and SYSTEM can differ on macOS. Callers must supply
        // the command's actual parent map; these are not native inheritance proof.
        val simulatedConsole = mapOf("GOWORK" to "auto", "CONSOLE_CREDENTIAL" to "owned-fixture-value")
        val simulatedSystem = mapOf("GOWORK" to "off")
        assertEquals(mapOf("GOWORK" to ""), GoToolchain.goplsEnvironment(emptyMap(), simulatedConsole, windows = false))
        assertEquals(emptyMap(), GoToolchain.goplsEnvironment(emptyMap(), simulatedSystem, windows = false))
        // initialize.env has no inherited source: leave the key absent so gopls
        // falls back to the child environment normalized at actual launch.
        assertEquals(emptyMap(), GoToolchain.goplsEnvironment(emptyMap()))
        assertEquals(mapOf("OTHER" to "unchanged"), GoToolchain.goplsEnvironment(mapOf("OTHER" to "unchanged")))
    }

    @Test fun windowsCaseInsensitiveWinnerPreservesRawValues() {
        assertEquals(mapOf("GOWORK" to ""), GoToolchain.goplsEnvironment(emptyMap(), mapOf("gowork" to "auto"), windows = true))
        val overridden = linkedMapOf("GOWORK" to "auto", "GoWork" to "off", "OTHER" to "verbatim")
        assertEquals(mapOf("GOWORK" to "off", "OTHER" to "verbatim"),
            GoToolchain.goplsEnvironment(overridden, mapOf("GOWORK" to "auto"), windows = true))
        assertEquals(mapOf("GOWORK" to ""),
            GoToolchain.goplsEnvironment(linkedMapOf("gowork" to "off", "GOWORK" to "auto"), emptyMap(), windows = true))
        assertEquals(mapOf("gowork" to "auto"), GoToolchain.goplsEnvironment(mapOf("gowork" to "auto"), emptyMap(), windows = false))
        assertEquals("auto", overridden["GOWORK"])
        assertEquals("off", overridden["GoWork"])
    }

    @Test fun toolIntentAdaptsOnlyGoplsWhenAllToolsSelectTheSameWrapper() {
        val root = Files.createTempDirectory("owned-gopls-environment-wrapper-").toRealPath()
        try {
            val sdk = root.resolve("sdk")
            val executable = sdk.resolve("bin/wrapper" + if (System.getProperty("os.name").startsWith("Windows")) ".exe" else "")
            Files.createDirectories(executable.parent)
            Files.createDirectories(sdk.resolve("src/runtime"))
            Files.writeString(executable, "Owned command-preparation fixture; never executed")
            executable.toFile().setExecutable(true)
            lateinit var service: GoProjectSettings
            val project = Proxy.newProxyInstance(Project::class.java.classLoader, arrayOf(Project::class.java)) { proxy, method, args ->
                when (method.name) {
                    "getBasePath" -> root.toString()
                    "getService" -> if (args?.firstOrNull() == GoProjectSettings::class.java) service else null
                    "toString" -> "GoGoplsEnvironmentWrapperFixture"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    else -> null
                }
            } as Project
            service = GoProjectSettings(project)
            service.state.apply {
                goExecutable = executable.toString(); goplsExecutable = executable.toString(); delveExecutable = executable.toString()
                environment = "GOWORK=auto\nUNCHANGED=raw"
            }
            for ((tool, expected) in listOf("go" to "auto", "dlv" to "auto", "gopls" to "")) {
                val command = GoToolchain.command(project, tool, listOf("owned-not-executed"))
                assertEquals(executable.toString(), command.exePath)
                assertEquals(GeneralCommandLine.ParentEnvironmentType.CONSOLE, command.parentEnvironmentType,
                    "Do not switch to SYSTEM and drop the user's console environment")
                assertEquals(expected, command.environment["GOWORK"], "Adapt by tool intent, never by executable equality")
                assertEquals("raw", command.environment["UNCHANGED"])
            }
            assertEquals("GOWORK=auto\nUNCHANGED=raw", service.state.environment)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun adaptationOwnsItsMapAndKeepsRawSettingsEpochDistinct() {
        val settings = GoToolchain.Settings("go", "gopls", "dlv", "tag", "", "", "0", "GOWORK=auto\nOTHER=unchanged")
        val raw = GoToolchain.parseEnvironment(settings.environment)
        val parent = linkedMapOf("GOWORK" to "auto")
        val first = GoToolchain.goplsEnvironment(raw, parent, windows = false)
        val second = GoToolchain.goplsEnvironment(raw, parent, windows = false)
        assertNotSame(raw, first)
        assertNotSame(first, second)
        assertEquals("auto", raw["GOWORK"])
        assertEquals("auto", parent["GOWORK"])
        assertEquals("GOWORK=auto\nOTHER=unchanged", settings.environment)
        assertNotEquals(settings.key, settings.copy(environment = "GOWORK=\nOTHER=unchanged").key)
        (first as MutableMap<String, String>)["GOWORK"] = "off"
        assertEquals("", second["GOWORK"])
        assertEquals("auto", raw["GOWORK"])
    }
}
