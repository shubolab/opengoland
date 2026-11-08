package dev.openidea.goide.debug

import dev.openidea.goide.run.GoRunKind
import dev.openidea.goide.run.GoRunParameters
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GoDebugLaunchTest {
    @Test fun nativeTestConfigurationBecomesTestBinaryArguments() {
        val root = Files.createTempDirectory("openidea-debug-test")
        try {
            val original = GoRunParameters(target = ".", workingDirectory = root.toString(),
                testPattern = "^TestCartTotal/two_products$", arguments = "-test.v -test.count=1",
                environment = linkedMapOf("MULTILINE" to "first\nsecond"), passParentEnvironment = false)
            val launch = GoDebugLaunch.fromRun(GoRunKind.TEST, original, root.toString())
            original.environment["MULTILINE"] = "changed later"
            assertEquals("test", launch.mode)
            assertEquals(root.toString(), launch.program)
            assertEquals(listOf("-test.run", "^TestCartTotal/two_products$", "-test.v", "-test.count=1"), launch.arguments)
            assertEquals("first\nsecond", launch.environment["MULTILINE"])
            assertFalse(launch.passParentEnvironment)
            val dap = launch.dapParameters("test cart", mapOf("GOOS" to "darwin"), "integration")
            assertEquals(root.toString(), dap["dlvCwd"])
            assertEquals("remote", dap["outputMode"])
            assertEquals(listOf("-tags=integration"), dap["buildFlags"])
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun importPathsAndBuildFlagsArePreservedWithoutShellQuoting() {
        val root = Files.createTempDirectory("openidea-debug-flags")
        try {
            val parameters = GoRunParameters(target = "example.com/app/cmd/server", workingDirectory = root.toString(),
                buildFlags = "-tags=custom '-ldflags=-X main.version=a b'", arguments = "--name 'Go developer'",
                environment = linkedMapOf("GOOS" to "linux"))
            val launch = GoDebugLaunch.fromRun(GoRunKind.BUILD, parameters, root.toString())
            val dap = launch.dapParameters("server", mapOf("GOOS" to "darwin"), "project")
            assertEquals("example.com/app/cmd/server", launch.program)
            assertEquals(listOf("-tags=custom", "-ldflags=-X main.version=a b"), dap["buildFlags"])
            assertEquals(listOf("--name", "Go developer"), dap["args"])
            assertEquals("linux", (dap["env"] as Map<*, *>)["GOOS"])
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun unsupportedMultiPackageDebugDoesNotSilentlyChooseOne() {
        val root = Files.createTempDirectory("openidea-debug-scope")
        try {
            for (target in listOf("./...", "a.go b.go", "./missing.go")) {
                assertFailsWith<IllegalArgumentException> {
                    GoDebugLaunch.fromRun(GoRunKind.BUILD, GoRunParameters(target = target, workingDirectory = root.toString()), root.toString())
                }
            }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun storedDebugOptionsCopyAllFieldsIndependently() {
        val first = GoDebugOptions().apply {
            program = "./cmd/server"
            workingDirectory = "/project"
            mode = "test"
            arguments = "-test.run TestCart"
            buildFlags = "-tags=integration"
            environmentVariables = "ENV=value"
        }
        val second = GoDebugOptions().apply { copyFrom(first) }
        assertEquals(first.program, second.program)
        assertEquals(first.workingDirectory, second.workingDirectory)
        assertEquals(first.mode, second.mode)
        assertEquals(first.arguments, second.arguments)
        assertEquals(first.buildFlags, second.buildFlags)
        assertEquals(first.environmentVariables, second.environmentVariables)
        second.program = "different"
        assertEquals("./cmd/server", first.program)
        assertTrue(second.modificationCount > 0)
    }

    @Test fun readyPatternExtractsActualLoopbackPort() {
        val extractor = com.redhat.devtools.lsp4ij.dap.configurations.extractors.NetworkAddressExtractor(
            "DAP server listening at: \${address}:\${port}")
        val endpoint = extractor.extract("DAP server listening at: 127.0.0.1:57491\n")
        assertTrue(endpoint.matches)
        assertEquals("127.0.0.1", endpoint.address)
        assertEquals("57491", endpoint.port)
    }
}
