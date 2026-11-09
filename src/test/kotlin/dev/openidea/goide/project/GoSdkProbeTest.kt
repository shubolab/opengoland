package dev.openidea.goide.project

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Assume.assumeFalse

class GoSdkProbeTest {
    @Test fun realSdkReportsTheVerifiedExecutableAndParsesTheActualModuleGrammar() = fixture { root ->
        val sdk = GoSdkProbe.inspect("go")
        assertTrue(sdk.executable.isAbsolute)
        assertTrue(sdk.root.isAbsolute)
        assertTrue(Files.isExecutable(sdk.executable))
        assertTrue(sdk.version.startsWith("go") || sdk.version.startsWith("devel "))
        Files.writeString(root.resolve("go.mod"), "module example.com/expected\n\ngo 1.20\n")
        GoSdkProbe.validateModule(sdk, root, "example.com/expected") { false }
        assertFailsWith<IOException> { GoSdkProbe.validateModule(sdk, root, "example.com/other") { false } }
        Files.writeString(root.resolve("go.mod"), "module example.com/expected\nnot valid go module syntax\n")
        assertFailsWith<IOException> { GoSdkProbe.validateModule(sdk, root, "example.com/expected") { false } }
    }

    @Test fun silentSuccessfulExecutableCannotCreateProjectParentsOrAModule() = unixFixture { root ->
        val program = script(root, "silent success", "exit 0\n")
        val destination = root.resolve("new parent/project")
        val failure = assertFailsWith<IOException> {
            GoModuleGenerator.generate(GoModuleRequest("example.com/false", destination, program.toString(), false))
        }
        assertTrue(failure.message.orEmpty().contains("Choose a Go installation's bin/go"))
        assertFalse(Files.exists(destination.parent))
    }

    @Test fun plausibleMetadataStillCannotPublishWithoutARealGoMod() = unixFixture { root ->
        val fake = Files.createDirectories(root.resolve("fake sdk with spaces"))
        Files.createDirectories(fake.resolve("src/runtime"))
        Files.createDirectories(fake.resolve("bin"))
        val env = "{\"GOROOT\":${quoteJson(fake.toString())},\"GOVERSION\":\"go1.24.3\"}"
        val program = script(fake.resolve("bin"), "go", """
            case "${'$'}1" in
              version) printf '%s\n' 'go version go1.24.3 test/arch' ;;
              env) printf '%s\n' ${shellQuote(env)} ;;
            esac
            exit 0
        """.trimIndent())
        val destination = root.resolve("published")
        val failure = assertFailsWith<IOException> {
            GoModuleGenerator.generate(GoModuleRequest("example.com/missing", destination, program.toString(), false))
        }
        assertTrue(failure.message.orEmpty().contains("go.mod"), failure.message)
        assertFalse(Files.exists(destination))
        assertFalse(Files.list(root).use { files -> files.anyMatch { it.fileName.toString().startsWith(".openidea-go-module-") } })
    }

    @Test fun failedExecutableReportsTheToolErrorWithoutCreatingTheDestination() = unixFixture { root ->
        val program = script(root, "failed sdk", "printf '%s\\n' 'this is not a working Go SDK' >&2\nexit 7\n")
        val destination = root.resolve("project")
        val failure = assertFailsWith<IOException> { GoModuleGenerator.generate(GoModuleRequest("example.com/failure", destination, program.toString())) }
        assertTrue(failure.message.orEmpty().contains("exit 7"), failure.message)
        assertTrue(failure.message.orEmpty().contains("not a working Go SDK"), failure.message)
        assertFalse(Files.exists(destination))
    }

    @Test fun oversizedSingleLineOutputIsBoundedAndDoesNotPublishAProject() = unixFixture { root ->
        val program = script(root, "large sdk output", "dd if=/dev/zero bs=4096 count=80 2>/dev/null\n")
        val destination = root.resolve("project")
        val failure = assertFailsWith<IOException> { GoModuleGenerator.generate(GoModuleRequest("example.com/large", destination, program.toString())) }
        assertTrue(failure.message.orEmpty().contains("256 KiB"), failure.message)
        assertFalse(Files.exists(destination))
    }

    @Test fun cancellationKillsOnlyTheOwnedGroupIncludingATermResistantLauncher() = unixFixture { root ->
        val pidFile = root.resolve("owned pids")
        val program = script(root, "blocked sdk", """
            trap '' TERM
            sleep 30 &
            child=${'$'}!
            printf '%s\n%s\n' "${'$'}${'$'}" "${'$'}child" > ${shellQuote(pidFile.toString())}
            wait
        """.trimIndent())
        val sentinel = ProcessBuilder("sleep", "30").start()
        try {
            assertFailsWith<CancellationException> {
                GoModuleGenerator.generate(GoModuleRequest("example.com/cancel", root.resolve("project"), program.toString()), { Files.exists(pidFile) })
            }
            val pids = Files.readAllLines(pidFile).map(String::toLong)
            pids.forEach(::assertStopped)
            assertTrue(sentinel.isAlive, "An unrelated process must not receive the owned group's signals")
            assertFalse(Files.exists(root.resolve("project")))
        } finally { sentinel.destroyForcibly(); sentinel.waitFor(3, TimeUnit.SECONDS) }
    }

    @Test fun aCompletedLauncherCannotLeaveItsChildRunning() = unixFixture { root ->
        val pidFile = root.resolve("orphan pid")
        val program = script(root, "exiting sdk", """
            sleep 30 &
            printf '%s\n' "${'$'}!" > ${shellQuote(pidFile.toString())}
            printf '%s\n' 'not go'
            exit 0
        """.trimIndent())
        assertFailsWith<IOException> { GoSdkProbe.inspect(program.toString()) }
        assertStopped(Files.readString(pidFile).trim().toLong())
    }

    @Test fun timeoutTerminatesTheOwnedCommandAndItsChildren() = unixFixture { root ->
        val pidFile = root.resolve("timeout pids")
        val program = script(root, "timeout sdk", """
            sleep 30 &
            printf '%s\n%s\n' "${'$'}${'$'}" "${'$'}!" > ${shellQuote(pidFile.toString())}
            wait
        """.trimIndent())
        val failure = assertFailsWith<IOException> {
            GoModuleProcesses.run(program.toString(), listOf("version"), root, { false }, timeoutMillis = 500)
        }
        assertTrue(failure.message.orEmpty().contains("timed out"), failure.message)
        Files.readAllLines(pidFile).map(String::toLong).forEach(::assertStopped)
    }

    private fun assertStopped(pid: Long) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (ProcessHandle.of(pid).map { it.isAlive }.orElse(false) && System.nanoTime() < deadline) Thread.sleep(20)
        assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false), "Owned process $pid must terminate")
    }
    private fun unixFixture(action: (Path) -> Unit) { assumeFalse(System.getProperty("os.name").startsWith("Windows")); fixture(action) }
    private fun fixture(action: (Path) -> Unit) {
        val root = Files.createTempDirectory("open-goland-sdk-test-")
        try { action(root) } finally { Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }
    private fun script(directory: Path, filename: String, body: String): Path = directory.resolve(filename).also {
        Files.writeString(it, "#!/bin/sh\n$body\n")
        assertTrue(it.toFile().setExecutable(true))
    }
    private fun shellQuote(text: String) = "'" + text.replace("'", "'\"'\"'") + "'"
    private fun quoteJson(text: String) = com.google.gson.Gson().toJson(text)
}
