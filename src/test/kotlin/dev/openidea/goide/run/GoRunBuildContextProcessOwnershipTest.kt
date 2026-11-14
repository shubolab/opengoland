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
            val output = f.execute("orphan")
            val owned = f.recorded("orphan")
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
            assertTrue("Both command processes must exist before cancellation", owned.all { it.second?.isAlive == true })
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
        private val owned = linkedMapOf<Long, Pair<ProcessHandle, java.time.Instant?>>()
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

        fun recorded(mode: String): List<Pair<Long, ProcessHandle?>> {
            val file = root.resolve("$mode.json")
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (!Files.isRegularFile(file)) {
                check(System.nanoTime() < deadline) { "The real command did not record its children" }
                Thread.sleep(10)
            }
            return JsonParser.parseString(Files.readString(file)).asJsonArray.map { value ->
                val pid = value.asLong
                // A normal launcher and its child can both be gone before the
                // reader returns. Keep that outcome distinct from a live leak.
                val handle = ProcessHandle.of(pid).orElse(null)?.takeIf { candidate ->
                    if (!candidate.isAlive) false else {
                        // The launcher may already have exited. A reused PID is
                        // not test ownership: this executable has a unique path
                        // under this fixture and is never launched elsewhere.
                        val info = candidate.info()
                        val executable = info.command().orElse(null)
                        check(executable != null || !candidate.isAlive) { "Cannot identify the live fixture process $pid" }
                        executable != null && Files.isSameFile(Path.of(executable), program)
                    }
                }?.also {
                    val started = it.info().startInstant().orElse(null)
                    check(started != null || !it.isAlive) { "Cannot identify the live fixture process incarnation" }
                    if (started != null) owned[pid] = it to started
                }
                pid to handle
            }
        }

        fun assertExited(handles: List<Pair<Long, ProcessHandle?>>) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (handles.any { it.second?.isAlive == true } && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue("No recorded owned process may survive metadata cleanup: ${handles.map { it.first }}", handles.none { it.second?.isAlive == true })
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
            try {
                // execute or an earlier assertion can fail before recorded().
                // Read only this fixture's two atomically published records;
                // recorded() still proves the unique executable and incarnation.
                listOf("orphan", "blocked").forEach { mode ->
                    if (Files.isRegularFile(root.resolve("$mode.json"))) recorded(mode)
                }
            } finally {
                owned.values.forEach { (handle, started) ->
                    if (handle.isAlive && started != null && handle.info().startInstant().orElse(null) == started) handle.destroyForcibly()
                }
                owned.values.forEach { (handle, _) -> if (handle.isAlive) handle.onExit().get(3, TimeUnit.SECONDS) }
                Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
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
                if mode=="orphan"{fmt.Print("metadata 😀");return}
                child.Wait()
            }
        """.trimIndent()
    }
}
