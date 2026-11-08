package dev.openidea.goide.run

import com.google.gson.JsonObject
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.testframework.sm.runner.GeneralIdBasedToSMTRunnerEventsConvertor
import com.intellij.execution.testframework.sm.runner.SMTRunnerEventsAdapter
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.execution.testframework.sm.runner.events.TestOutputEvent
import com.intellij.openapi.project.Project
import com.intellij.util.messages.MessageBus
import dev.openidea.goide.coverage.GoCoverageModule
import dev.openidea.goide.coverage.GoCoverageProfile
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GoLiveTestRunnerTest {
    @Test fun nativeDurationsUseRealParentAndPackageElapsedWithoutSummingParallelWorkTwice() {
        val protocol = listOf(
            event("run", "example.com/first", "TestParent"),
            event("run", "example.com/first", "TestParent/left"),
            event("run", "example.com/first", "TestParent/right"),
            event("run", "example.com/second", "TestOther"),
            event("pass", "example.com/first", "TestParent/left", 6.0),
            event("pass", "example.com/first", "TestParent/right", 6.0),
            event("pass", "example.com/first", "TestParent", 6.5),
            event("pass", "example.com/second", "TestOther", 7.0),
            event("pass", "example.com/second", elapsed = 7.0),
            event("pass", "example.com/first", elapsed = 7.2),
        )
        val root = replay(protocol, 8000)
        val nodes = root.allTests.associateBy { it.getUserData(SMTestProxy.NODE_ID) }
        assertEquals(6000L, nodes["go-test:example.com/first#TestParent/left:1"]!!.duration)
        assertEquals(6000L, nodes["go-test:example.com/first#TestParent/right:1"]!!.duration)
        assertEquals(6500L, nodes["go-test:example.com/first#TestParent:1"]!!.duration, "Parent elapsed includes its own work; parallel children must not turn it into 12 seconds")
        assertEquals(7200L, nodes["go-package:example.com/first"]!!.duration)
        assertEquals(7000L, nodes["go-package:example.com/second"]!!.duration)
        assertEquals(8000L, root.duration, "The two packages overlap; root uses process wall time, not 14.2 seconds of summed work")
        assertEquals(7200L, replay(protocol, 100).duration, "Root must not understate elapsed already reported by Go")
    }

    @Test fun virtualPathDurationsSumOnlyTheirOwnChildrenAndKeepZeroAsARealElapsedValue() {
        val root = replay(listOf(
            event("run", "example.com/virtual", "TestPath"),
            event("run", "example.com/virtual", "TestPath/dir/file"),
            event("pass", "example.com/virtual", "TestPath/dir/file", .125),
            event("run", "example.com/virtual", "TestPath/dir"),
            event("pass", "example.com/virtual", "TestPath/dir", .25),
            event("pass", "example.com/virtual", "TestPath", .5),
            event("run", "example.com/virtual", "TestZero"),
            event("pass", "example.com/virtual", "TestZero", 0.0),
            event("pass", "example.com/virtual", elapsed = .75),
        ), 1000)
        val nodes = root.allTests.associateBy { it.getUserData(SMTestProxy.NODE_ID) }
        assertEquals(375L, nodes["go-test:example.com/virtual#TestPath/dir:1"]!!.duration)
        assertEquals(500L, nodes["go-test:example.com/virtual#TestPath:1"]!!.duration)
        assertEquals(0L, nodes["go-test:example.com/virtual#TestZero:1"]!!.duration)
        assertEquals(750L, nodes["go-package:example.com/virtual"]!!.duration)
        assertEquals(1000L, root.duration)
    }

    @Test fun realLongParallelAndRepeatedGoTestsUpdateTheNativeTreeBeforeTheyFinish() {
        val directory = Files.createTempDirectory("opengoland-live-tests").toRealPath()
        val io = Executors.newSingleThreadExecutor()
        var child: Process? = null
        val output = GoTestOutput(directory.toString(), mapOf("example.com/live" to directory.toString()))
        val capture = NativeCapture()
        val root = SMTestProxy.SMRootTestProxy()
        val events = GeneralIdBasedToSMTRunnerEventsConvertor(testProject(capture), root, "Go native streaming test")
        val converter = RecordConverter()
        events.addEventsListener(capture)
        converter.setProcessor(events)
        converter.startTesting()
        try {
            Files.writeString(directory.resolve("go.mod"), "module example.com/live\n\ngo 1.20\n")
            Files.writeString(directory.resolve("value.go"), "package live\nfunc Value() int { return 25 }\n")
            Files.writeString(directory.resolve("live_test.go"), """
                package live
                import (
                    "os"
                    "testing"
                    "time"
                )
                func TestLong(t *testing.T) {
                    t.Parallel()
                    t.Log("long-running-ready")
                    deadline := time.Now().Add(20 * time.Second)
                    for {
                        if _, err := os.Stat(os.Getenv("IDE_RELEASE")); err == nil { break }
                        if time.Now().After(deadline) { t.Fatal("fixture was not released") }
                        time.Sleep(10 * time.Millisecond)
                    }
                    if Value() != 25 { t.Fatal("wrong value") }
                    t.Log("long-running-released")
                }
                func TestDynamic(t *testing.T) {
                    for _, name := range []string{"alpha", "beta"} {
                        name := name
                        t.Run(name, func(t *testing.T) {
                            t.Parallel()
                            t.Run("deep", func(t *testing.T) {
                                t.Log("parallel-" + name)
                                if name == "beta" { t.Error("controlled nested failure") }
                            })
                        })
                    }
                }
                func TestSlash(t *testing.T) {
                    t.Run("dir/file", func(t *testing.T) { if Value() != 25 { t.Fatal("wrong slash value") } })
                    t.Run("dir", func(t *testing.T) { if Value() != 25 { t.Fatal("wrong direct value") } })
                }
            """.trimIndent() + "\n")
            val release = directory.resolve("release")
            val profile = directory.resolve("coverage.out")
            val builder = ProcessBuilder("go", "test", "-json", "-count=2", "-parallel=3", "-coverprofile=$profile", ".")
                .directory(directory.toFile()).redirectError(directory.resolve("stderr.log").toFile())
            builder.environment()["GOTOOLCHAIN"] = "local"
            builder.environment()["GOWORK"] = "off"
            builder.environment()["IDE_RELEASE"] = release.toString()
            val process = builder.start().also { child = it }
            val protocol = directory.resolve("protocol.jsonl")
            val reader = io.submit {
                Files.newBufferedWriter(protocol).use { trace ->
                    process.inputStream.bufferedReader().useLines { lines -> lines.forEach { line ->
                        trace.appendLine(line)
                        trace.flush()
                        output.consume(line + "\n", converter::acceptRecord)
                    } }
                }
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25)
            while (!capture.longReady.await(50, TimeUnit.MILLISECONDS)) {
                // Surface parser/model failures immediately instead of mistaking them for missing Go output.
                if (reader.isDone) {
                    reader.get()
                    error("Go ended before the native live marker. stderr: ${Files.readString(directory.resolve("stderr.log"))}\nProtocol: ${Files.readString(protocol).takeLast(8192)}")
                }
                if (System.nanoTime() >= deadline)
                    error("Native live marker timed out. stderr: ${Files.readString(directory.resolve("stderr.log"))}\nProtocol: ${Files.readString(protocol).takeLast(8192)}")
            }
            val running = assertNotNull(capture.nodes["go-test:example.com/live#TestLong:1"])
            assertTrue(process.isAlive, "The test must still be blocked when its native node and output become visible")
            assertTrue(running.isInProgress)
            assertFalse(running.isFinal)
            assertTrue(capture.logs.any { it.contains("long-running-ready") }, "Output must be visible before release")
            Files.writeString(release, "continue")
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Real Go test subprocess did not finish")
            reader.get(5, TimeUnit.SECONDS)
            assertEquals(1, process.exitValue(), Files.readString(directory.resolve("stderr.log")))
            output.finish(process.exitValue(), converter::acceptRecord)
            converter.flushBufferOnProcessTermination(process.exitValue())
            converter.finishTesting()

            val leaves = root.allTests.filterNot(SMTestProxy::isSuite)
            assertEquals(10, leaves.size, leaves.map { it.name }.toString())
            assertTrue(leaves.all { it.isFinal })
            assertEquals(2, leaves.count { it.isDefect })
            assertEquals(10, capture.finished.get(), "Dynamic parents must not be counted as extra tests")
            assertEquals(2, capture.failed.get())
            assertTrue(root.isDefect)
            for (attempt in 1..2) {
                val parent = assertNotNull(capture.nodes["go-test:example.com/live#TestDynamic:$attempt"])
                assertTrue(parent.isSuite, "The original running leaf must be promoted in place")
                assertEquals(setOf("alpha", "beta"), parent.children.map { it.name }.toSet())
                parent.children.forEach { branch ->
                    assertTrue(branch.isSuite)
                    assertEquals(listOf("deep"), branch.children.map { it.name })
                }
                val slash = assertNotNull(capture.nodes["go-test:example.com/live#TestSlash:$attempt"])
                val group = slash.children.single { it.name == "dir" }
                assertTrue(group.isSuite)
                assertEquals(setOf("file", "dir result"), group.children.map { it.name }.toSet())
                assertTrue(output.locationForNode("go-test:example.com/live#TestDynamic/beta/deep:$attempt")?.startsWith("file://$directory/live_test.go:") == true)
            }
            assertEquals(mapOf("example.com/live" to listOf("TestDynamic/beta/deep")), output.failures())
            assertEquals(1, GoTestRerunPlan.create(output.failures(), GoRunParameters()).size)
            assertEquals(2, capture.logs.count { it.contains("controlled nested failure") }, "Each attempt's failure output has exactly one native owner")
            val report = GoCoverageProfile.parse(Files.readString(profile), listOf(GoCoverageModule("example.com/live", directory.toString())))
            assertTrue(report.unmappedFiles.isEmpty())
            assertEquals(100.0, report.totals.percent)
        } finally {
            child?.let { process -> if (process.isAlive) {
                process.descendants().use { children -> children.forEach { it.destroyForcibly() } }
                process.destroyForcibly()
                process.waitFor(5, TimeUnit.SECONDS)
            } }
            io.shutdownNow()
            converter.dispose()
            output.close()
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    /** Complete records are produced by GoTestOutput; the UI line splitter additionally needs a running IDE's UISettings. */
    private class RecordConverter : GoTestEventsConverter("Go native streaming test") {
        fun acceptRecord(record: String) = processConsistentText(record, ProcessOutputTypes.STDOUT)
    }

    private fun replay(protocol: List<String>, elapsedMillis: Long): SMTestProxy.SMRootTestProxy {
        val root = SMTestProxy.SMRootTestProxy()
        val events = GeneralIdBasedToSMTRunnerEventsConvertor(testProject(NativeCapture()), root, "Go duration test")
        val converter = RecordConverter()
        val output = GoTestOutput("/tmp", elapsedMillis = { elapsedMillis })
        converter.setProcessor(events)
        converter.startTesting()
        try {
            protocol.forEach { output.consume(it, converter::acceptRecord) }
            output.finish(0, converter::acceptRecord)
            converter.finishTesting()
            return root
        } finally { converter.dispose(); output.close() }
    }

    private fun event(action: String, pkg: String, test: String? = null, elapsed: Double? = null): String = JsonObject().apply {
        addProperty("Action", action); addProperty("Package", pkg)
        test?.let { addProperty("Test", it) }; elapsed?.let { addProperty("Elapsed", it) }
    }.toString() + "\n"

    private class NativeCapture : SMTRunnerEventsAdapter() {
        val nodes = ConcurrentHashMap<String, SMTestProxy>()
        val logs = java.util.concurrent.CopyOnWriteArrayList<String>()
        val longReady = CountDownLatch(1)
        val finished = AtomicInteger()
        val failed = AtomicInteger()
        override fun onTestStarted(test: SMTestProxy) { test.getUserData(SMTestProxy.NODE_ID)?.let { nodes[it] = test } }
        override fun onTestOutput(proxy: SMTestProxy, event: TestOutputEvent) {
            logs += event.text
            if (event.text.contains("long-running-ready")) longReady.countDown()
        }
        override fun onCustomProgressTestFinished() { finished.incrementAndGet() }
        override fun onCustomProgressTestFailed() { failed.incrementAndGet() }
    }

    /** Only the project message bus is stubbed; tree, state transitions, service parser and counters use the shipped SM APIs. */
    private fun testProject(capture: NativeCapture): Project {
        val sink = object : SMTRunnerEventsAdapter() {
            override fun onTestOutput(proxy: SMTestProxy, event: TestOutputEvent) { capture.onTestOutput(proxy, event) }
        }
        val bus = Proxy.newProxyInstance(MessageBus::class.java.classLoader, arrayOf(MessageBus::class.java)) { _, method, _ ->
            if (method.name == "syncPublisher") sink else null
        } as MessageBus
        return Proxy.newProxyInstance(Project::class.java.classLoader, arrayOf(Project::class.java)) { _, method, _ ->
            if (method.name == "getMessageBus") bus else null
        } as Project
    }
}
