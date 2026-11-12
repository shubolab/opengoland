package dev.openidea.goide.debug

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.testFramework.LightPlatformTestCase
import dev.openidea.goide.process.GoOwnedTestExecutable
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Pre-configuration Stop must reap the launch adapter's exact observed tree. */
class GoOwnedLaunchedAdapterLifecycleTest : LightPlatformTestCase() {
    override fun runInDispatchThread() = false

    fun testOwnedLaunchStopReapsChildAndGrandchildButNotAnUnrelatedProcess() {
        val root = Files.createTempDirectory("opengoland-launch-tree-").toRealPath()
        val executable = GoOwnedTestExecutable.compile(root.resolve(GoOwnedTestExecutable.name("owned-launch-tree")), HELPER)
        val handler = GoOwnedDapAdapterProcessHandler(
            GeneralCommandLine(executable.toString(), "adapter", root.toString()).withWorkDirectory(root.toFile()),
            graceSeconds = 1,
        )
        var sentinel: Process? = null
        try {
            handler.disconnect = { CompletableFuture.completedFuture(null) }
            handler.startNotify()
            waitFor { listOf("ready", "child", "grandchild").all { Files.isRegularFile(root.resolve(it)) } }

            sentinel = ProcessBuilder(executable.toString(), "sentinel", root.toString()).start()
            waitFor { Files.isRegularFile(root.resolve("sentinel")) }
            val child = ProcessHandle.of(Files.readString(root.resolve("child")).trim().toLong()).orElseThrow()
            val grandchild = ProcessHandle.of(Files.readString(root.resolve("grandchild")).trim().toLong()).orElseThrow()
            val unrelated = sentinel.toHandle()
            assertTrue(child.isAlive)
            assertTrue(grandchild.isAlive)
            assertTrue(unrelated.isAlive)

            handler.destroyProcess()
            handler.process.onExit().get(6, TimeUnit.SECONDS)
            waitFor { !child.isAlive && !grandchild.isAlive }
            assertFalse("A launch-owned child survived pre-configuration Stop", child.isAlive)
            assertFalse("A launch-owned grandchild survived pre-configuration Stop", grandchild.isAlive)
            assertTrue("Identity tracking must never signal an unrelated process", unrelated.isAlive)
        } finally {
            runCatching { handler.destroyProcess() }
            runCatching { handler.process.destroyForcibly() }
            runCatching { sentinel?.destroyForcibly() }
            runCatching { Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
        }
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue("Owned launch process tree did not reach its bounded state", condition())
    }

    private companion object {
        val HELPER = """
            package main
            import ("os"; "os/exec"; "path/filepath"; "strconv"; "time")
            func write(root, name string) {
                if err := os.WriteFile(filepath.Join(root, name), []byte(strconv.Itoa(os.Getpid())), 0600); err != nil { panic(err) }
            }
            func main() {
                executable, err := os.Executable(); if err != nil { panic(err) }
                root := os.Args[2]
                switch os.Args[1] {
                case "child":
                    grandchild := exec.Command(executable, "grandchild", root)
                    if err := grandchild.Start(); err != nil { panic(err) }
                    if err := os.WriteFile(filepath.Join(root, "grandchild"), []byte(strconv.Itoa(grandchild.Process.Pid)), 0600); err != nil { panic(err) }
                    write(root, "child")
                case "grandchild":
                    write(root, "grandchild-ready")
                case "sentinel":
                    write(root, "sentinel")
                default:
                    child := exec.Command(executable, "child", root)
                    if err := child.Start(); err != nil { panic(err) }
                    if err := os.WriteFile(filepath.Join(root, "child"), []byte(strconv.Itoa(child.Process.Pid)), 0600); err != nil { panic(err) }
                    write(root, "ready")
                }
                for { time.Sleep(time.Hour) }
            }
        """.trimIndent()
    }
}
