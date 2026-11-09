package dev.openidea.goide.project

import java.nio.file.Files
import java.util.concurrent.CancellationException
import dev.openidea.goide.settings.GoToolchain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GoModuleGeneratorTest {
    @Test fun moduleCommandFlagsAreRejectedBeforeSdkChecksOrCreatingParents() {
        val parent = Files.createTempDirectory("openidea-module-flags-")
        try {
            val witness = Files.writeString(parent.resolve("outside.mod"), "unchanged")
            val destination = parent.resolve("missing-parent/project")
            for (module in listOf("-modfile=../outside.mod", "-C=..", "--help")) {
                assertFailsWith<IllegalArgumentException> {
                    GoModuleGenerator.generate(GoModuleRequest(module, destination, "must-not-execute-this-program"))
                }
                assertFalse(Files.exists(destination.parent))
                assertEquals("unchanged", Files.readString(witness))
            }
            assertEquals(listOf("outside.mod"), Files.list(parent).use { it.map { path -> path.fileName.toString() }.sorted().toList() })
        } finally { Files.walk(parent).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }
    @Test fun createsARealModuleWhoseGeneratedTestsPass() {
        val parent = Files.createTempDirectory("openidea-new-go-test")
        try {
            val destination = parent.resolve("project with spaces")
            val created = GoModuleGenerator.generate(GoModuleRequest("example.com/hello", destination, testGo()))
            assertEquals(destination, created)
            assertTrue(Files.readString(created.resolve("go.mod")).contains("module example.com/hello"))
            val output = GoModuleProcesses.run(testGo(), listOf("test", "-count=1", "./..."), created, { false })
            assertTrue(output.stdout.contains("ok") && !output.stdout.contains("(cached)"), output.stdout)
            assertFalse(Files.list(parent).use { paths -> paths.anyMatch { it.fileName.toString().startsWith(".openidea-go-module-") } })
        } finally { Files.walk(parent).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }

    @Test fun existingDirectoriesAreNeverReplacedEvenWhenEmpty() {
        val parent = Files.createTempDirectory("openidea-existing-go-test")
        try {
            val existing = Files.createDirectory(parent.resolve("existing"))
            assertFailsWith<java.io.IOException> { GoModuleGenerator.generate(GoModuleRequest("example.com/hello", existing, testGo())) }
            Files.writeString(existing.resolve("keep.txt"), "unchanged")
            assertFailsWith<java.io.IOException> { GoModuleGenerator.generate(GoModuleRequest("example.com/hello", existing, testGo())) }
            assertEquals("unchanged", Files.readString(existing.resolve("keep.txt")))
        } finally { Files.walk(parent).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }

    @Test fun emptyProjectStillContainsARealParsedGoModule() {
        val parent = Files.createTempDirectory("openidea-empty-go-test")
        try {
            val destination = parent.resolve("empty module")
            val result = GoModuleGenerator.generateVerified(GoModuleRequest("example.com/empty", destination, testGo(), createExample = false))
            assertEquals(destination, result.directory)
            GoSdkProbe.validateModule(result.sdk, destination, "example.com/empty") { false }
            assertEquals(listOf("go.mod"), Files.list(destination).use { files -> files.map { it.fileName.toString() }.sorted().toList() })
        } finally { Files.walk(parent).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }

    @Test fun cancellationAndInvalidModuleLeaveNoDestinationOrStaging() {
        val parent = Files.createTempDirectory("openidea-cancel-go-test")
        try {
            val target = parent.resolve("new-project")
            assertFailsWith<CancellationException> { GoModuleGenerator.generate(GoModuleRequest("example.com/hello", target, testGo()), { true }) }
            assertFalse(Files.exists(target))
            assertFailsWith<java.io.IOException> { GoModuleGenerator.generate(GoModuleRequest("bad@module", target, testGo())) }
            assertFalse(Files.exists(target))
            assertEquals(0L, Files.list(parent).use { it.count() })
        } finally { Files.walk(parent).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }
    private fun testGo() = requireNotNull(GoToolchain.resolve("go",
        System.getProperty("opengoland.testGo") ?: System.getenv("OPENGOLAND_GO_TEST").orEmpty()))
}
