package dev.openidea.goide.settings

import com.intellij.openapi.project.Project
import java.lang.reflect.Proxy
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
        org.junit.Assume.assumeFalse(System.getProperty("os.name").startsWith("Windows"))
        val root = Files.createTempDirectory("openidea-sdk-test").toRealPath()
        try {
            val sdk = root.resolve("sdk with spaces")
            val go = sdk.resolve("bin/go")
            Files.createDirectories(go.parent)
            Files.createDirectories(sdk.resolve("src/runtime"))
            Files.writeString(go, "#!/bin/sh\nexit 0\n")
            go.toFile().setExecutable(true)
            val service = GoProjectSettings()
            service.state.apply {
                goExecutable = go.toString()
                goos = "linux"
                goarch = "arm64"
                cgoEnabled = "0"
                buildTags = "integration, feature"
                environment = "GOOS=freebsd\nGOARCH=amd64\nCGO_ENABLED=1\nGOFLAGS=-mod=vendor\nPATH=/custom/path\n"
            }
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
}
