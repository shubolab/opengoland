package dev.openidea.goide.project

import java.nio.file.Files
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GoModuleGeneratorTest {
    @Test fun createsARealModuleWhoseGeneratedTestsPass() {
        val parent = Files.createTempDirectory("openidea-new-go-test")
        try {
            val destination = parent.resolve("project with spaces")
            val created = GoModuleGenerator.generate(GoModuleRequest("example.com/hello", destination, "go"))
            assertEquals(destination, created)
            assertTrue(Files.readString(created.resolve("go.mod")).contains("module example.com/hello"))
            val builder = ProcessBuilder("go", "test", "./...").directory(created.toFile()).redirectErrorStream(true)
            builder.environment()["GOWORK"] = "off"
            val process = builder.start()
            val output = process.inputStream.bufferedReader().readText()
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Generated Go test timed out")
            assertEquals(0, process.exitValue(), output)
            assertFalse(Files.list(parent).use { paths -> paths.anyMatch { it.fileName.toString().startsWith(".openidea-go-module-") } })
        } finally { Files.walk(parent).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }

    @Test fun existingDirectoriesAreNeverReplacedEvenWhenEmpty() {
        val parent = Files.createTempDirectory("openidea-existing-go-test")
        try {
            val existing = Files.createDirectory(parent.resolve("existing"))
            assertFailsWith<java.io.IOException> { GoModuleGenerator.generate(GoModuleRequest("example.com/hello", existing, "go")) }
            Files.writeString(existing.resolve("keep.txt"), "unchanged")
            assertFailsWith<java.io.IOException> { GoModuleGenerator.generate(GoModuleRequest("example.com/hello", existing, "go")) }
            assertEquals("unchanged", Files.readString(existing.resolve("keep.txt")))
        } finally { Files.walk(parent).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }

    @Test fun cancellationAndInvalidModuleLeaveNoDestinationOrStaging() {
        val parent = Files.createTempDirectory("openidea-cancel-go-test")
        try {
            val target = parent.resolve("new-project")
            assertFailsWith<CancellationException> { GoModuleGenerator.generate(GoModuleRequest("example.com/hello", target, "go"), { true }) }
            assertFalse(Files.exists(target))
            assertFailsWith<java.io.IOException> { GoModuleGenerator.generate(GoModuleRequest("bad@module", target, "go")) }
            assertFalse(Files.exists(target))
            assertEquals(0L, Files.list(parent).use { it.count() })
        } finally { Files.walk(parent).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }
}
