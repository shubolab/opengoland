package dev.openidea.goide.debug

import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.openapi.project.Project
import com.redhat.devtools.lsp4ij.dap.DAPDebugRunner
import com.redhat.devtools.lsp4ij.dap.DebugMode
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoDebugConnectionTest {
    private fun options() = GoConnectionOptions().apply { processId = "12345"; workingDirectory = "/workspace" }

    @Test fun localAttachSpawnsAdapterButUsesRealAttachRequestAndNoLaunchFields() {
        val connection = GoDebugConnection.fromOptions(options(), "/workspace", true)
        assertEquals(DebugMode.LAUNCH, connection.adapterMode)
        assertEquals(DebugMode.ATTACH, connection.requestMode)
        val dap = connection.dapParameters("local")
        assertEquals("attach", dap["request"])
        assertEquals("local", dap["mode"])
        assertEquals(12345, dap["processId"])
        assertFalse(dap.containsKey("program"))
        assertFalse(dap.containsKey("args"))
        assertFalse(dap.containsKey("env"))
        assertTrue(dap["showGlobalVariables"] as Boolean)
        assertFalse(dap["hideSystemGoroutines"] as Boolean)
    }

    @Test fun externalDapAndHeadlessUseDistinctAttachModesWithoutSpawningServer() {
        val options = options().apply { host = "::1"; port = "56789" }
        val external = GoDebugConnection.fromOptions(options, "/workspace", false)
        assertEquals(DebugMode.ATTACH, external.adapterMode)
        assertEquals("local", external.dapParameters("external")["mode"])
        assertEquals(12345, external.processId)
        options.remoteKind = GoRemoteServerKind.HEADLESS_SESSION.name
        options.processId = "not used for headless sessions"
        val headless = GoDebugConnection.fromOptions(options, "/workspace", false)
        assertNull(headless.processId)
        assertEquals("remote", headless.dapParameters("headless")["mode"])
        assertFalse(headless.dapParameters("headless").containsKey("processId"))
        assertEquals(DebugMode.ATTACH, headless.requestMode)
        assertEquals("::1", headless.host)
        assertEquals(56789, headless.port)
    }

    @Test fun mappingDirectionIsClientToServerAndOptionsSnapshotDoesNotMutate() {
        val options = options().apply {
            sourceMappings = "/local/project => /remote/project\n/local/windows => C:\\agent\\src"
            environmentVariables = "ADAPTER_ENV=first\nMULTILINE=second "
            hideSystemGoroutines = true
        }
        val snapshot = GoDebugConnection.fromOptions(options, "/workspace", true)
        options.sourceMappings = "changed"; options.environmentVariables = "ADAPTER_ENV=changed"
        assertEquals(listOf(mapOf("from" to "/local/project", "to" to "/remote/project"), mapOf("from" to "/local/windows", "to" to "C:\\agent\\src")), snapshot.substitutePath)
        assertEquals("first", snapshot.environment["ADAPTER_ENV"])
        assertEquals("second ", snapshot.environment["MULTILINE"])
        assertTrue(snapshot.hideSystemGoroutines)
    }

    @Test fun malformedEndpointsPidsAndMappingsAreRejectedBeforeConnecting() {
        for (host in listOf("http://127.0.0.1", "127.0.0.1:38697", "[::1]", "bad host")) {
            assertFailsWith<IllegalArgumentException> { GoDebugConnection.fromOptions(options().apply { this.host = host }, "/workspace", false) }
        }
        for (port in listOf("0", "65536", "not a port")) {
            assertFailsWith<IllegalArgumentException> { GoDebugConnection.fromOptions(options().apply { this.port = port }, "/workspace", false) }
        }
        for (pid in listOf("0", "-1", "999999999999", "")) {
            assertFailsWith<IllegalArgumentException> { GoDebugConnection.fromOptions(options().apply { processId = pid }, "/workspace", true) }
        }
        for (mapping in listOf("a=b", "relative => /remote", "/local => relative", "/local => /a\n/local => /b")) {
            assertFailsWith<IllegalArgumentException> { GoDebugConnection.parseMappings(mapping) }
        }
    }

    @Test fun storedConnectionOptionsCopyEveryFieldIndependently() {
        val original = options().apply {
            remoteKind = GoRemoteServerKind.HEADLESS_SESSION.name; host = "example.org"; port = "1234"
            environmentVariables = "ADAPTER=1"; sourceMappings = "/local => /remote"; hideSystemGoroutines = true
        }
        val copy = GoConnectionOptions().apply { copyFrom(original) }
        assertEquals(original.processId, copy.processId)
        assertEquals(original.host, copy.host); assertEquals(original.port, copy.port)
        assertEquals(original.remoteKind, copy.remoteKind); assertEquals(original.workingDirectory, copy.workingDirectory)
        assertEquals(original.environmentVariables, copy.environmentVariables)
        assertEquals(original.sourceMappings, copy.sourceMappings); assertTrue(copy.hideSystemGoroutines)
        copy.processId = "54321"; assertEquals("12345", original.processId)
    }

    @Test fun nativeConfigurationTypesAreEligibleForDebugOnly() {
        val project = Proxy.newProxyInstance(Project::class.java.classLoader, arrayOf(Project::class.java)) { proxy, method, args ->
            when (method.name) {
                "getBasePath" -> "/workspace"; "toString" -> "GoAttachConfigurationTestProject"
                "hashCode" -> System.identityHashCode(proxy); "equals" -> proxy === args?.firstOrNull(); else -> null
            }
        } as Project
        val runner = DAPDebugRunner()
        for (type in listOf(GoLocalAttachConfigurationType(), GoRemoteDebugConfigurationType())) {
            val configuration = type.configurationFactories.single().createTemplateConfiguration(project)
            assertTrue(runner.canRun(DefaultDebugExecutor.EXECUTOR_ID, configuration))
            assertFalse(runner.canRun(DefaultRunExecutor.EXECUTOR_ID, configuration))
            assertFalse(runner.canRun("Coverage", configuration))
        }
    }
}
