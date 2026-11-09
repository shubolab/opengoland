package dev.openidea.goide.settings

import com.intellij.openapi.project.Project
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.execution.process.CapturingProcessHandler
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class GoToolchainTest {
    @Test fun buildTagsPreserveLettersAndNormalizeWhitespace() {
        assertEquals(listOf("windows", "linux", "integration"), GoToolchain.buildTags("windows, linux\tintegration\nintegration"))
    }

    @Test fun environmentValuesPreserveMeaningfulSpaces() {
        assertEquals(mapOf("VALUE" to " leading and trailing ", "OTHER" to "a=b"),
            GoToolchain.parseEnvironment("VALUE= leading and trailing \nOTHER=a=b\n"))
        assertNull(GoToolchain.resolve("go", "\u0000invalid"))
    }

    @Test fun selectedSDKAndTargetFieldsAreConsistentAcrossTools() {
        val root = Files.createTempDirectory("openidea-sdk-test").toRealPath()
        try {
            val sdk = root.resolve("sdk with spaces")
            val go = sdk.resolve("bin/go" + if (System.getProperty("os.name").startsWith("Windows")) ".exe" else "")
            Files.createDirectories(go.parent)
            Files.createDirectories(sdk.resolve("src/runtime"))
            // This command-preparation fixture is never executed.
            Files.writeString(go, "owned native executable placeholder")
            go.toFile().setExecutable(true)
            lateinit var service: GoProjectSettings
            val project = Proxy.newProxyInstance(Project::class.java.classLoader, arrayOf(Project::class.java)) { proxy, method, args ->
                when (method.name) {
                    "getBasePath" -> root.toString()
                    "getService" -> if (args?.firstOrNull() == GoProjectSettings::class.java) service else null
                    "toString" -> "GoToolchainTestProject"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    else -> null
                }
            } as Project
            service = GoProjectSettings(project)
            service.state.apply {
                goExecutable = go.toString()
                goos = "linux"
                goarch = "arm64"
                cgoEnabled = "0"
                buildTags = "integration, feature"
                environment = "GOOS=freebsd\nGOARCH=amd64\nCGO_ENABLED=1\nGOFLAGS=-mod=vendor\nPATH=/custom/path\n"
            }
            val command = GoToolchain.command(project, "go", listOf("version"))
            assertEquals(go.toString(), command.exePath)
            assertEquals("linux", command.environment["GOOS"])
            assertEquals("arm64", command.environment["GOARCH"])
            assertEquals("0", command.environment["CGO_ENABLED"])
            assertEquals(sdk.toString(), command.environment["GOROOT"])
            assertEquals("-mod=vendor -tags=integration,feature", command.environment["GOFLAGS"])
            assertTrue(command.environment.getValue("PATH").startsWith(go.parent.toString() + java.io.File.pathSeparator))
            assertTrue(command.environment.getValue("PATH").endsWith("/custom/path"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun hostPathToolLookupFindsAndActuallyExecutesTheNativeGoExecutable() {
        // Explicitly bypass the product bundle: this is the standard host PATH
        // branch, which must work with Windows' executable extensions too.
        val executable = assertNotNull(GoToolchain.resolve("go", "", null), "The required Go tool on the test host PATH must be discoverable")
        val path = Path.of(executable).toRealPath()
        val directories = PathEnvironmentVariableUtil.getPathDirs(PathEnvironmentVariableUtil.getPathVariableValue().orEmpty())
        assertTrue(directories.any { candidate -> runCatching { Path.of(candidate).resolve(Path.of(executable).fileName).toRealPath() == path }.getOrDefault(false) })
        val sdk = path.parent.parent.takeIf { Files.isDirectory(it.resolve("src/runtime")) }?.toString().orEmpty()
        val handler = CapturingProcessHandler(GeneralCommandLine(executable, "version")
            .withEnvironment("GOROOT", sdk).withEnvironment("GOTOOLCHAIN", "local"))
        try {
            val output = handler.runProcess(10_000, true)
            assertEquals(0, output.exitCode, output.stdout + output.stderr)
            assertFalse(output.isTimeout || output.isCancelled)
            assertTrue(output.stdout.startsWith("go version go") || output.stdout.startsWith("go version devel "), output.stdout)
        } finally { if (!handler.isProcessTerminated) handler.destroyProcess() }
    }
}
