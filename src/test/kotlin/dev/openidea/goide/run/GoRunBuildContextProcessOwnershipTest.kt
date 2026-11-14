package dev.openidea.goide.run

import com.google.gson.JsonParser
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.util.SystemInfo
import com.intellij.testFramework.LightPlatformTestCase
import dev.openidea.goide.process.GoOwnedTestExecutable
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Real finite commands through the metadata loader, including native Windows Job ownership. */
class GoRunBuildContextProcessOwnershipTest : LightPlatformTestCase() {
    override fun runInDispatchThread() = false

    fun testCompletedLauncherCannotLeaveAChildHoldingTheMetadataPipes() = Fixture().use { f ->
        val sentinel = f.sentinel()
        try {
            var owned = emptyList<Pair<Long, ProcessHandle>>()
            val checks = AtomicInteger()
            val output = f.execute("orphan") {
                if (checks.incrementAndGet() == 2) {
                    owned = f.recorded("orphan")
                    assertEquals(2, owned.size)
                    assertTrue("The normal-exit gate must keep both command processes alive", owned.all { it.second.isAlive })
                    Files.writeString(f.root.resolve("release-orphan"), "allow the launcher to exit normally")
                }
            }
            assertEquals("metadata 😀", output)
            assertEquals(2, owned.size)
            f.assertExited(owned)
            assertTrue("A separately launched process must not enter the metadata command's ownership", sentinel.isAlive)
        } finally { f.stop(sentinel) }
    }

    fun testCancellationBeforeTheFirstLoopCheckClosesOwnedPipesAndPreservesTheSentinel() = Fixture().use { f ->
        val sentinel = f.sentinel()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cancelled = AtomicBoolean()
        val checks = AtomicInteger()
        val worker = Executors.newSingleThreadExecutor()
        val priorReaders = Thread.getAllStackTraces().keys.toSet()
        val result = worker.submit {
            val checkCurrent: () -> Unit = {
                if (checks.incrementAndGet() == 2) {
                    entered.countDown()
                    check(release.await(15, TimeUnit.SECONDS)) { "The cancellation gate was not released" }
                }
                if (cancelled.get()) throw CancellationException("owned metadata cancellation")
            }
            try {
                f.execute("blocked", checkCurrent)
                error("The cancelled metadata command returned normally")
            } catch (failure: CancellationException) {
                assertEquals("owned metadata cancellation", failure.message)
            }
        }
        try {
            assertTrue("The real command must reach the first post-launch check", entered.await(10, TimeUnit.SECONDS))
            val owned = f.recorded("blocked")
            assertEquals(2, owned.size)
            assertTrue("Both command processes must exist before cancellation", owned.all { it.second.isAlive })
            val readers = Thread.getAllStackTraces().keys.filter {
                it !in priorReaders && it.name == "open Goland build-context output"
            }
            assertEquals(2, readers.size)
            cancelled.set(true)
            release.countDown()
            result.get(10, TimeUnit.SECONDS)
            f.assertExited(owned)
            assertTrue("Inherited-pipe readers must terminate with their owned command", readers.none { it.isAlive })
            assertTrue("Cancellation must leave the independently launched process alive", sentinel.isAlive)
        } finally {
            cancelled.set(true)
            release.countDown()
            try { result.get(10, TimeUnit.SECONDS) } finally {
                worker.shutdownNow()
                f.stop(sentinel)
            }
        }
    }

    fun testShortLivedCommandsKeepPrivateEnvironmentWorkingDirectoryUtf8AndFailureStatus() = Fixture().use { f ->
        repeat(12) {
            assertEquals("前😀|${f.root}|absent", f.execute("output"))
        }
        try {
            f.execute("failure")
            fail("The selected command's nonzero exit must remain a metadata failure")
        } catch (failure: IllegalStateException) {
            assertTrue(failure.toString(), failure.message.orEmpty().contains("exit 7"))
            assertTrue(failure.toString(), failure.message.orEmpty().contains("original metadata error 前😀"))
        }
    }

    private inner class Fixture : AutoCloseable {
        val root: Path = Files.createTempDirectory("go-metadata-owned-process-").toRealPath()
        private val loader = GoRunBuildContextLoader(project)
        private val program = GoOwnedTestExecutable.compile(root.resolve(GoOwnedTestExecutable.name("metadata-command")), HELPER)
        private val owned = linkedMapOf<Long, Pair<ProcessHandle, java.time.Instant>>()
        private val execute = GoRunBuildContextLoader::class.java.getDeclaredMethod(
            "execute", GeneralCommandLine::class.java, kotlin.jvm.functions.Function0::class.java,
        ).apply { isAccessible = true }

        fun execute(mode: String, checkCurrent: () -> Unit = {}): String {
            val command = GeneralCommandLine(program.toString(), mode, root.toString()).withWorkDirectory(root.toFile())
                .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.NONE)
                .withEnvironment("OWNED_METADATA_VALUE", "前😀")
            if (SystemInfo.isWindows) System.getenv("SystemRoot")?.let { command.withEnvironment("SystemRoot", it) }
            try { return execute.invoke(loader, command, checkCurrent) as String }
            catch (failure: InvocationTargetException) { throw failure.targetException }
        }

        fun recorded(mode: String): List<Pair<Long, ProcessHandle>> {
            val file = root.resolve("$mode.json")
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (!Files.isRegularFile(file)) {
                check(System.nanoTime() < deadline) { "The real command did not record its children" }
                Thread.sleep(10)
            }
            return recordPids(mode).map { pid ->
                pid to checkNotNull(capture(pid)) { "The gated fixture process $pid exited before identity capture" }
            }
        }

        private fun recordPids(mode: String): List<Long> =
            JsonParser.parseString(Files.readString(root.resolve("$mode.json"))).asJsonArray.map { it.asLong }

        private fun capture(pid: Long): ProcessHandle? {
            // A saved handle is already bound to the incarnation proved while
            // the gate kept it alive. Never re-query its command after exit.
            owned[pid]?.let { return it.first }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (true) {
                val handle = ProcessHandle.of(pid).orElse(null)
                if (handle == null || !handle.isAlive) return null
                val info = handle.info()
                val executable = info.command().orElse(null)
                val started = info.startInstant().orElse(null)
                if (executable != null && started != null && handle.isAlive) {
                    check(Files.isSameFile(Path.of(executable), program)) { "Recorded PID $pid belongs to another executable" }
                    owned[pid] = handle to started
                    return handle
                }
                check(System.nanoTime() < deadline) { "Cannot identify the live fixture process $pid within two seconds" }
                Thread.sleep(10)
            }
        }

        fun assertExited(handles: List<Pair<Long, ProcessHandle>>) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (handles.any { it.second.isAlive } && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue("No recorded owned process may survive metadata cleanup: ${handles.map { it.first }}", handles.none { it.second.isAlive })
        }

        fun sentinel(): Process = ProcessBuilder(program.toString(), "child").directory(root.toFile()).start()
        fun stop(process: Process) {
            if (process.isAlive) process.destroyForcibly()
            check(process.waitFor(3, TimeUnit.SECONDS)) { "The test-owned sentinel did not exit" }
        }

        override fun close() {
            loader.dispose()
            // Recovery only; these assertions have already run before cleanup.
            // Never rediscover a PID tree after its root has exited.
            val recoveryFailures = mutableListOf<Throwable>()
            try {
                // Recover only identities missed before an earlier failure.
                // Known handles must survive optional post-exit info becoming
                // unavailable; an unknown live identity still fails closed.
                listOf("orphan", "blocked").forEach { mode ->
                    if (Files.isRegularFile(root.resolve("$mode.json"))) {
                        recordPids(mode).filterNot(owned::containsKey).forEach { pid ->
                            runCatching { capture(pid) }.exceptionOrNull()?.let { recoveryFailures += it }
                        }
                    }
                }
            } finally {
                owned.values.forEach { (handle, _) ->
                    if (handle.isAlive) handle.destroyForcibly()
                }
                owned.values.forEach { (handle, _) -> if (handle.isAlive) handle.onExit().get(3, TimeUnit.SECONDS) }
                Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
            }
            recoveryFailures.firstOrNull()?.let { failure ->
                recoveryFailures.drop(1).forEach { failure.addSuppressed(it) }
                throw failure
            }
        }
    }

    private companion object {
        val HELPER = """
            package main
            import("encoding/json";"fmt";"os";"os/exec";"path/filepath";"time")
            func main(){
                mode:=os.Args[1]
                if mode=="child"{for{time.Sleep(time.Second)}}
                dir:=os.Args[2]
                if mode=="output"{
                    cwd,err:=os.Getwd();if err!=nil{panic(err)}
                    parent:="absent";if os.Getenv("PATH")!=""{parent="inherited"}
                    fmt.Printf("%s|%s|%s",os.Getenv("OWNED_METADATA_VALUE"),cwd,parent);return
                }
                if mode=="failure"{fmt.Fprint(os.Stderr,"original metadata error 前😀");os.Exit(7)}
                child:=exec.Command(os.Args[0],"child");child.Stdout=os.Stdout;child.Stderr=os.Stderr
                if err:=child.Start();err!=nil{panic(err)}
                data,_:=json.Marshal([]int{os.Getpid(),child.Process.Pid})
                name:=filepath.Join(dir,mode+".json");if err:=os.WriteFile(name+".tmp",data,0600);err!=nil{panic(err)}
                if err:=os.Rename(name+".tmp",name);err!=nil{panic(err)}
                if mode=="orphan"{
                    deadline:=time.Now().Add(20*time.Second)
                    for{if _,err:=os.Stat(filepath.Join(dir,"release-orphan"));err==nil{break};if time.Now().After(deadline){child.Process.Kill();child.Wait();panic("normal-exit gate was not released")};time.Sleep(time.Millisecond)}
                    fmt.Print("metadata 😀");return
                }
                child.Wait()
            }
        """.trimIndent()
    }
}
