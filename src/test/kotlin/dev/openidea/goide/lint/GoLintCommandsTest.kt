package dev.openidea.goide.lint

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GoLintCommandsTest {
    @Test fun quotesAndVersionSpecificOutputFlagsMatchOfficialCli() {
        val report = Path.of("/tmp/inspection report.json")
        assertEquals(listOf("vet", "-printf=false", "-tags=one two", "./..."), GoLintCommands.vet("-printf=false '-tags=one two'"))
        val first = GoLintCommands.golangci(1, "--config 'config dir/lint.yml'", report, listOf("integration"))
        assertTrue("--out-format=json:$report" in first)
        assertTrue("--config" in first && "config dir/lint.yml" in first)
        assertTrue(first.none { it.startsWith("--output.json") || it.startsWith("--path-mode") })
        val second = GoLintCommands.golangci(2, "--enable-only=govet", report, listOf("integration", "custom"))
        assertTrue("--output.json.path=$report" in second)
        assertTrue("--path-mode=abs" in second)
        assertTrue("--build-tags=integration,custom" in second)
        assertEquals("./...", second.last())
        assertFailsWith<IllegalArgumentException> { GoLintCommands.golangci(2, "--output.json.path=somewhere", report, emptyList()) }
        assertFailsWith<IllegalArgumentException> { GoLintCommands.vet("-fix") }
    }

    @Test fun plainAndJsonVersionsAreRecognizedWithoutAssumingTheSameVersionFlags() {
        assertEquals(2, GoLintCommands.versionMajor("golangci-lint has version 2.15.0 built with go1.26.6 from hash on date"))
        assertEquals(1, GoLintCommands.versionMajor("golangci-lint has version 1.64.8 built with go1.24.1"))
        assertEquals(2, GoLintCommands.versionMajor("""{"version":"v2.15.0","goVersion":"go1.26.6"}"""))
    }
}
