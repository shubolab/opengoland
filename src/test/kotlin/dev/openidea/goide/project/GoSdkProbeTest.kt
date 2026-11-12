package dev.openidea.goide.project

import dev.openidea.goide.process.GoOwnedTestExecutable
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.concurrent.CancellationException
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

    @Test fun silentSuccessfulExecutableCannotCreateProjectParentsOrAModule() = fixture { root ->
        val program = program(root, "silent success", "silent")
        val destination = root.resolve("new parent/project")
        val failure = assertFailsWith<IOException> {
            GoModuleGenerator.generate(GoModuleRequest("example.com/false", destination, program.toString(), false))
        }
        assertTrue(failure.message.orEmpty().contains("Choose a Go installation's bin/go"))
        assertFalse(Files.exists(destination.parent))
    }

    @Test fun plausibleMetadataStillCannotPublishWithoutARealGoMod() = fixture { root ->
        val fake = Files.createDirectories(root.resolve("fake sdk with spaces"))
        Files.createDirectories(fake.resolve("src/runtime"))
        Files.createDirectories(fake.resolve("bin"))
        val program = program(fake.resolve("bin"), "go", "plausible")
        val destination = root.resolve("published")
        val failure = assertFailsWith<IOException> {
            GoModuleGenerator.generate(GoModuleRequest("example.com/missing", destination, program.toString(), false))
        }
        assertTrue(failure.message.orEmpty().contains("go.mod"), failure.message)
        assertFalse(Files.exists(destination))
        assertFalse(Files.list(root).use { files -> files.anyMatch { it.fileName.toString().startsWith(".openidea-go-module-") } })
    }

    @Test fun failedExecutableReportsTheToolErrorWithoutCreatingTheDestination() = fixture { root ->
        val program = program(root, "failed sdk", "failed")
        val destination = root.resolve("project")
        val failure = assertFailsWith<IOException> { GoModuleGenerator.generate(GoModuleRequest("example.com/failure", destination, program.toString())) }
        assertTrue(failure.message.orEmpty().contains("exit 7"), failure.message)
        assertTrue(failure.message.orEmpty().contains("not a working Go SDK"), failure.message)
        assertFalse(Files.exists(destination))
    }

    @Test fun oversizedSingleLineOutputIsBoundedAndDoesNotPublishAProject() = fixture { root ->
        val program = program(root, "large sdk output", "oversized")
        val destination = root.resolve("project")
        val failure = assertFailsWith<IOException> { GoModuleGenerator.generate(GoModuleRequest("example.com/large", destination, program.toString())) }
        assertTrue(failure.message.orEmpty().contains("256 KiB"), failure.message)
        assertFalse(Files.exists(destination))
    }

    @Test fun cancellationKillsOnlyTheOwnedGroupIncludingATermResistantLauncher() = fixture { root ->
        val pidFile = root.resolve("owned pids")
        val program = program(root, "blocked sdk", "cancel")
        val sentinel = ProcessBuilder(program.toString(), "owned-child").start()
        try {
            assertFailsWith<CancellationException> {
                bounded(root) {
                    GoModuleGenerator.generate(GoModuleRequest("example.com/cancel", root.resolve("project"), program.toString()), { Files.exists(pidFile) })
                }
            }
            val pids = Files.readAllLines(pidFile).map(String::toLong)
            pids.forEach(::assertStopped)
            assertTrue(sentinel.isAlive, "An unrelated process must not receive the owned group's signals")
            assertFalse(Files.exists(root.resolve("project")))
        } finally { sentinel.destroyForcibly(); assertTrue(sentinel.waitFor(3, TimeUnit.SECONDS)) }
    }

    @Test fun cancellationBeforeTheFirstChildSampleClosesOwnedPipesAndLeavesTheSentinelAlive() = fixture { root ->
        val pidFile = root.resolve("owned pids")
        val program = program(root, "cancel before sampling", "cancel")
        val sentinel = ProcessBuilder(program.toString(), "owned-child").start()
        try {
            var beforeLaunch = true
            assertFailsWith<CancellationException> {
                bounded(root) {
                    GoModuleProcesses.run(program.toString(), listOf("version"), root, {
                        if (beforeLaunch) { beforeLaunch = false; false }
                        else {
                            // This is the first post-launch cancellation check, before
                            // the old Windows descendant scan. Real children must exist
                            // while the caller is held at that precise boundary.
                            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                            while (!Files.exists(pidFile) && System.nanoTime() < deadline) Thread.sleep(10)
                            assertTrue(Files.exists(pidFile), "The owned helper must start before requesting cancellation")
                            val pids = Files.readAllLines(pidFile).map(String::toLong)
                            assertEquals(2, pids.size)
                            pids.forEach { assertTrue(ProcessHandle.of(it).map { handle -> handle.isAlive }.orElse(false)) }
                            assertTrue(sentinel.isAlive, "The unrelated sentinel must be live before cancellation")
                            true
                        }
                    })
                }
            }
            Files.readAllLines(pidFile).map(String::toLong).forEach(::assertStopped)
            assertTrue(sentinel.isAlive, "Cancellation must leave the unrelated sentinel running")
        } finally { sentinel.destroyForcibly(); assertTrue(sentinel.waitFor(3, TimeUnit.SECONDS)) }
    }

    @Test fun aCompletedLauncherCannotLeaveItsChildRunning() = fixture { root ->
        val pidFile = root.resolve("orphan pid")
        val program = program(root, "exiting sdk", "orphan")
        assertFailsWith<IOException> { bounded(root) { GoSdkProbe.inspect(program.toString()) } }
        assertStopped(Files.readString(pidFile).trim().toLong())
    }

    @Test fun timeoutTerminatesTheOwnedCommandAndItsChildren() = fixture { root ->
        val pidFile = root.resolve("timeout pids")
        val program = program(root, "timeout sdk", "timeout")
        var beforeLaunch = true
        var ownedPids: List<Long>? = null
        val failure = assertFailsWith<IOException> {
            GoModuleProcesses.run(program.toString(), listOf("version"), root, {
                // The first cancellation check precedes launch. At the first
                // post-launch check, establish that the fixture has really
                // started both processes before allowing the timeout loop to
                // run. A loaded machine may otherwise time out before the helper
                // reaches its PID marker; this fixture deliberately starts more
                // slowly than the timeout. The production 500ms deadline stays
                // unchanged, and cancellation is never requested by this test.
                if (beforeLaunch) beforeLaunch = false
                else if (ownedPids == null) {
                    val readyDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                    while (!Files.exists(pidFile) && System.nanoTime() < readyDeadline) Thread.sleep(10)
                    assertTrue(Files.exists(pidFile), "The timeout fixture must start before testing its cleanup")
                    ownedPids = Files.readAllLines(pidFile).map(String::toLong).also { pids ->
                        assertEquals(2, pids.size)
                        pids.forEach { pid -> assertTrue(ProcessHandle.of(pid).map { it.isAlive }.orElse(false), "Owned process $pid must be running before timeout") }
                    }
                }
                false
            }, timeoutMillis = 500)
        }
        assertTrue(failure.message.orEmpty().contains("timed out"), failure.message)
        val pids = requireNotNull(ownedPids) { "Timeout cleanup must be checked against observed live owned processes" }
        pids.forEach(::assertStopped)
    }

    private fun assertStopped(pid: Long) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (ProcessHandle.of(pid).map { it.isAlive }.orElse(false) && System.nanoTime() < deadline) Thread.sleep(20)
        assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false), "Owned process $pid must terminate")
    }
    /** A 30-second helper ending naturally must never masquerade as prompt cleanup. */
    private fun <T> bounded(root: Path, action: () -> T): T {
        val executor = Executors.newSingleThreadExecutor()
        val result = executor.submit(Callable { action() })
        try {
            return result.get(5, TimeUnit.SECONDS)
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        } catch (error: TimeoutException) {
            // Recovery follows a failed deadline. It cannot make a leaked child
            // satisfy the test; it only releases this fixture's own binaries.
            result.cancel(true)
            stopRecordedFixtureProcesses(root)
            throw AssertionError("Owned SDK command did not finish within 5 seconds; do not wait for the helper's natural 30-second exit", error)
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "The SDK control worker must terminate")
        }
    }

    private fun fixture(action: (Path) -> Unit) {
        val root = Files.createTempDirectory("open-goland-sdk-test-")
        try { action(root) } finally {
            // Failure recovery runs after the assertions; it cannot turn a
            // leaked child into a successful cleanup assertion. These records
            // are written only by our native helper in this exclusive root.
            stopRecordedFixtureProcesses(root)
            val paths = Files.walk(root).use { it.sorted(Comparator.reverseOrder()).toList() }
            paths.forEach(Files::deleteIfExists)
        }
    }

    private fun stopRecordedFixtureProcesses(root: Path) {
        val records = Files.walk(root).use { paths -> paths.filter { it.fileName.toString() == "launched-pids" }.toList() }
        val handles = records.flatMap { Files.readAllLines(it) }.map(String::toLong).distinct()
            .mapNotNull { ProcessHandle.of(it).orElse(null) }
        handles.filter { it.isAlive }.forEach { handle ->
            val command = handle.info().command().orElseThrow { AssertionError("Cannot establish owned fixture process ${handle.pid()}") }
            check(Path.of(command).toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize())) {
                "The fixture PID was reused or its command changed"
            }
            handle.destroyForcibly()
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (handles.any { it.isAlive } && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(handles.none { it.isAlive }, "Release all owned helper binaries before removing the fixture")
    }

    private fun program(directory: Path, filename: String, mode: String): Path =
        GoOwnedTestExecutable.compile(directory.resolve(GoOwnedTestExecutable.name(filename)), """
            package main
            import ("encoding/json"; "fmt"; "os"; "os/exec"; "os/signal"; "path/filepath"; "syscall"; "time")
            func main() {
                // Unix's TERM-resistant parent/child coverage is preserved. On
                // Windows the same real executables exercise process-tree ownership.
                if len(os.Args)>1 && os.Args[1]=="owned-child" {
                    signal.Ignore(syscall.SIGTERM); time.Sleep(30*time.Second); return
                }
                executable, err := os.Executable(); if err != nil { panic(err) }
                root := filepath.Dir(executable)
                launches, err := os.OpenFile(filepath.Join(root, "launched-pids"), os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0600); if err != nil { panic(err) }
                if _, err := fmt.Fprintln(launches, os.Getpid()); err != nil { panic(err) }; launches.Close()
                switch "$mode" {
                case "silent": return
                case "failed": fmt.Fprintln(os.Stderr, "this is not a working Go SDK"); os.Exit(7)
                case "oversized": os.Stdout.Write(make([]byte, 4096*80)); return
                case "plausible":
                    if len(os.Args)>1 && os.Args[1]=="version" { fmt.Println("go version go1.24.3 test/arch") }
                    if len(os.Args)>1 && os.Args[1]=="env" {
                        if err := json.NewEncoder(os.Stdout).Encode(map[string]string{"GOROOT":filepath.Dir(root), "GOVERSION":"go1.24.3"}); err != nil { panic(err) }
                    }
                    return
                }
                signal.Ignore(syscall.SIGTERM)
                if "$mode"=="timeout" { time.Sleep(time.Second) }
                child := exec.Command(executable, "owned-child"); child.Stdout=os.Stdout; child.Stderr=os.Stderr
                if err := child.Start(); err != nil { panic(err) }
                launches, err = os.OpenFile(filepath.Join(root, "launched-pids"), os.O_APPEND|os.O_WRONLY, 0600); if err != nil { panic(err) }
                if _, err := fmt.Fprintln(launches, child.Process.Pid); err != nil { panic(err) }; launches.Close()
                name := "owned pids"; data := fmt.Sprintf("%d\n%d\n", os.Getpid(), child.Process.Pid)
                if "$mode"=="orphan" { name="orphan pid"; data=fmt.Sprintf("%d\n", child.Process.Pid) }
                if "$mode"=="timeout" { name="timeout pids" }
                pending := filepath.Join(root, name+" pending")
                if err := os.WriteFile(pending, []byte(data), 0600); err != nil { panic(err) }
                if err := os.Rename(pending, filepath.Join(root, name)); err != nil { panic(err) }
                if "$mode"=="orphan" { fmt.Println("not go"); return }
                child.Wait()
            }
        """.trimIndent())
}
