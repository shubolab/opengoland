package dev.openidea.goide.generate.tests

import com.intellij.openapi.project.Project
import dev.openidea.goide.process.GoOwnedTestExecutable
import dev.openidea.goide.settings.GoProjectSettings
import dev.openidea.goide.settings.GoToolchain
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GoGenerateTestsConfigurationTest {
    @Test fun capturedSettingsOwnTheGenerationPayloadEvenWhenTheLiveProjectUsesAnotherSdkAndBuildContext() {
        val root = Files.createTempDirectory("open-goland-generation-settings-").toRealPath()
        try {
            val sdkA = root.resolve("captured sdk A")
            val sdkB = root.resolve("live sdk B")
            lateinit var service: GoProjectSettings
            var settingsReads = 0
            val project = Proxy.newProxyInstance(Project::class.java.classLoader, arrayOf(Project::class.java)) { proxy, method, args ->
                when (method.name) {
                    "getBasePath" -> root.toString()
                    "getService" -> if (args?.firstOrNull() == GoProjectSettings::class.java) { settingsReads++; service } else null
                    "toString" -> "CapturedGenerationSettingsProject"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    else -> null
                }
            } as Project
            service = GoProjectSettings(project)
            service.state.apply {
                goExecutable = tool(sdkA, "go").toString()
                goplsExecutable = tool(sdkA, "gopls").toString()
                delveExecutable = tool(sdkA, "dlv").toString()
                buildTags = "captured, integration"
                goos = "linux"; goarch = "arm64"; cgoEnabled = "0"
                environment = "GOOS=freebsd\nGOFLAGS=-mod=vendor\nPATH=/captured/path\nGENERATION_CONTEXT=A\n"
            }

            // Capture is the EDT-safe memory-only path: these selected executables do not exist yet.
            assertFalse(Files.exists(sdkA))
            val captured = GoToolchain.capture(project)
            val capturedKey = captured.key
            assertEquals(1, settingsReads)

            service.state.apply {
                goExecutable = tool(sdkB, "go").toString()
                goplsExecutable = tool(sdkB, "gopls").toString()
                delveExecutable = tool(sdkB, "dlv").toString()
                buildTags = "feature"
                goos = "windows"; goarch = "amd64"; cgoEnabled = "1"
                environment = "GOFLAGS=-mod=readonly\nPATH=/live/path\nGENERATION_CONTEXT=B\n"
            }
            createSdk(sdkA); createSdk(sdkB)

            // Exercise the exact background resolver used by Generate Tests, rather than a copied JSON fixture.
            val wire = generationToolchainParameters(project, captured)
            assertEquals(1, settingsReads, "Preparing the captured request must not reread mutable project settings")
            assertEquals(tool(sdkA, "go").toString(), wire.get("goExecutable").asString)
            assertEquals(listOf("captured", "integration"), wire.getAsJsonArray("buildTags").map { it.asString })
            val env = wire.getAsJsonObject("environment")
            assertEquals("linux", env.get("GOOS").asString)
            assertEquals("arm64", env.get("GOARCH").asString)
            assertEquals("0", env.get("CGO_ENABLED").asString)
            assertEquals("A", env.get("GENERATION_CONTEXT").asString)
            assertEquals("-mod=vendor -tags=captured,integration", env.get("GOFLAGS").asString)
            assertEquals(sdkA.toString(), env.get("GOROOT").asString)
            assertTrue(env.get("PATH").asString.startsWith(sdkA.resolve("bin").toString()))
            assertTrue(env.get("PATH").asString.endsWith("/captured/path"))
            assertEquals(capturedKey, captured.key)

            val live = GoToolchain.command(project, "go", emptyList())
            assertEquals(tool(sdkB, "go").toString(), live.exePath)
            assertEquals("windows", live.environment["GOOS"])
            assertEquals("B", live.environment["GENERATION_CONTEXT"])
            assertEquals("-mod=readonly -tags=feature", live.environment["GOFLAGS"])
        } finally { root.toFile().deleteRecursively() }
    }

    private fun createSdk(root: Path) {
        Files.createDirectories(root.resolve("src/runtime"))
        Files.createDirectories(root.resolve("bin"))
        for (name in listOf("go", "gopls", "dlv")) {
            val executable = tool(root, name)
            // This is a path-resolution control, never a launched or verified SDK.
            Files.write(executable, byteArrayOf())
            check(executable.toFile().setExecutable(true))
        }
    }

    private fun tool(root: Path, name: String): Path = root.resolve("bin").resolve(GoOwnedTestExecutable.name(name))
}
