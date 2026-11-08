package dev.openidea.goide.debug

import com.intellij.execution.Executor
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.EdtTestUtil
import com.intellij.testFramework.LightPlatformTestCase
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.breakpoints.XBreakpointType
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.redhat.devtools.lsp4ij.dap.DAPDebugRunner
import com.redhat.devtools.lsp4ij.dap.breakpoints.DAPBreakpointHandler
import com.redhat.devtools.lsp4ij.dap.breakpoints.DAPBreakpointProperties
import com.redhat.devtools.lsp4ij.dap.breakpoints.DAPBreakpointType
import com.redhat.devtools.lsp4ij.dap.definitions.DebugAdapterServerDefinition
import com.redhat.devtools.lsp4ij.dap.descriptors.DebugAdapterDescriptor
import com.redhat.devtools.lsp4ij.dap.descriptors.DebugAdapterDescriptorFactory
import com.redhat.devtools.lsp4ij.server.definition.ServerMapping
import org.eclipse.lsp4j.debug.Capabilities
import org.eclipse.lsp4j.debug.SetBreakpointsArguments
import org.eclipse.lsp4j.debug.SetBreakpointsResponse
import org.eclipse.lsp4j.debug.services.IDebugProtocolServer
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import javax.swing.Icon

/** Real SDK breakpoint manager, source positions and inherited DAP dispatch; only the session and peer are doubles. */
class GoDapBreakpointHandlerTest : LightPlatformTestCase() {
    override fun runInDispatchThread() = false
    fun testRemovingLastBreakpointInOneFileSendsEmptySourceAndPreservesAnotherFile() {
        for (connection in listOf(false, true)) Fixture(connection).use { fixture ->
            val app = fixture.add(fixture.app, 1)
            val removed = fixture.add(fixture.other, 2)
            fixture.initialize()
            fixture.peer.calls.clear()

            fixture.remove(removed)

            assertEquals(emptyList<Int>(), fixture.peer.installed[fixture.other.path])
            assertEquals(listOf(2), fixture.peer.installed[fixture.app.path])
            assertTrue("The removed source must explicitly reach DAP with an empty replacement", fixture.peer.calls.any {
                it.first == fixture.other.path && it.second.isEmpty()
            })
            assertTrue(app.isEnabled)
        }
    }

    fun testRemovingOneOfSeveralBreakpointsInSameFileKeepsTheOthers() {
        Fixture().use { fixture ->
            fixture.add(fixture.app, 1)
            val removed = fixture.add(fixture.other, 1)
            fixture.add(fixture.other, 3)
            fixture.initialize()
            fixture.remove(removed)
            assertEquals(listOf(4), fixture.peer.installed[fixture.other.path])
            assertEquals(listOf(2), fixture.peer.installed[fixture.app.path])
        }
    }

    fun testRemovingAllBreakpointsClearsEverySourceAtThePeer() {
        Fixture().use { fixture ->
            val app = fixture.add(fixture.app, 1)
            val other = fixture.add(fixture.other, 2)
            fixture.initialize()
            fixture.remove(other)
            fixture.remove(app)
            assertEquals(setOf(fixture.app.path, fixture.other.path), fixture.peer.installed.keys)
            assertTrue(fixture.peer.installed.values.all { it.isEmpty() })
        }
    }

    fun testRunToCursorTemporaryRemovalKeepsPersistentBreakpoints() {
        Fixture().use { fixture ->
            fixture.add(fixture.app, 1)
            fixture.add(fixture.other, 2)
            fixture.initialize()
            val position = onEdt { XDebuggerUtil.getInstance().createPosition(fixture.other, 3)!! }
            onEdt { fixture.handler.registerTemporaryBreakpoint(position).join() }
            assertEquals(listOf(3, 4), fixture.peer.installed[fixture.other.path])
            onEdt { fixture.handler.unregisterTemporaryBreakpoint(position).join() }
            assertEquals(listOf(3), fixture.peer.installed[fixture.other.path])
            assertEquals(listOf(2), fixture.peer.installed[fixture.app.path])
        }
    }

    fun testUpstreamHandlerReproducesMissingEmptyReplacementWithTheSameRealBreakpoints() {
        Fixture(upstream = true).use { fixture ->
            fixture.add(fixture.app, 1)
            val other = fixture.add(fixture.other, 2)
            fixture.initialize()
            fixture.peer.calls.clear()
            fixture.remove(other)
            assertEquals("The upstream regression leaves the removed file installed", listOf(3), fixture.peer.installed[fixture.other.path])
            assertFalse(fixture.peer.calls.any { it.first == fixture.other.path })
        }
    }

    private inner class Fixture(connection: Boolean = false, upstream: Boolean = false) : AutoCloseable {
        private val manager = XDebuggerManager.getInstance(project).breakpointManager
        private val owned = mutableListOf<XLineBreakpoint<DAPBreakpointProperties>>()
        private val root = Files.createTempDirectory("opengoland-breakpoint-").toRealPath()
        private val disposable = Disposer.newDisposable("Go breakpoint file fixture")
        val app: VirtualFile
        val other: VirtualFile
        val peer = Peer()
        private val type = onEdt { XDebuggerUtil.getInstance().findBreakpointType(DAPBreakpointType::class.java)
            ?: DAPBreakpointType().also { XBreakpointType.EXTENSION_POINT_NAME.point.registerExtension(it, testRootDisposable) } }
        private val session = Proxy.newProxyInstance(XDebugSession::class.java.classLoader, arrayOf(XDebugSession::class.java)) { _, method, _ ->
            when (method.name) { "getProject" -> project; else -> null }
        } as XDebugSession
        val handler: DAPBreakpointHandler

        init {
            VfsRootAccess.allowRootAccess(disposable, root.toString())
            app = nativeFile("app.go")
            other = nativeFile("fixture.go")
            handler = onEdt {
            val profile = object : RunProfile {
                override fun getName() = "Go breakpoint replacement test"
                override fun getIcon(): Icon? = null
                override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState? = null
            }
            val environment = ExecutionEnvironmentBuilder(project, DefaultDebugExecutor.getDebugExecutorInstance())
                .runProfile(profile).runner(DAPDebugRunner()).build()
            val definition = object : DebugAdapterServerDefinition("opengoland-breakpoint-test", "Go breakpoint test") {
                override fun createFactory(): DebugAdapterDescriptorFactory = GoDebugAdapterFactory()
                override fun getServerMappings(): List<ServerMapping> = emptyList()
            }
            val descriptor: DebugAdapterDescriptor = if (connection) GoConnectionDescriptor(GoConnectionOptions().apply {
                processId = "37"; workingDirectory = "/opengoland-breakpoint-test"
            }, environment, definition, true) else GoDebugDescriptor(GoDebugSessionOptions(GoDebugLaunch(
                "/opengoland-breakpoint-test", "/opengoland-breakpoint-test", "debug", emptyList(), emptyList(), emptyMap()
            )), environment, definition)
            if (upstream) DAPBreakpointHandler(session, descriptor, project)
                else descriptor.createBreakpointHandler(session, project).also {
                    assertTrue("Both production descriptors must install the Go-owned handler", it is GoDapBreakpointHandler)
                } as DAPBreakpointHandler
            }
        }

        private fun nativeFile(name: String): VirtualFile {
            val path = root.resolve(name)
            Files.writeString(path, SOURCE)
            return LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!
        }

        fun add(file: VirtualFile, line: Int): XLineBreakpoint<DAPBreakpointProperties> {
            var breakpoint: XLineBreakpoint<DAPBreakpointProperties>? = null
            onEdt { WriteCommandAction.runWriteCommandAction(project) {
                breakpoint = manager.addLineBreakpoint(type, file.url, line, DAPBreakpointProperties())
            } }
            return breakpoint!!.also { owned += it; onEdt { handler.registerBreakpoint(it) } }
        }

        fun initialize() { onEdt { handler.initialize(peer, Capabilities()).join() } }
        fun remove(breakpoint: XLineBreakpoint<DAPBreakpointProperties>) {
            onEdt {
                handler.unregisterBreakpoint(breakpoint, false)
                WriteCommandAction.runWriteCommandAction(project) { manager.removeBreakpoint(breakpoint) }
            }
            owned.remove(breakpoint)
        }

        override fun close() {
            try {
                onEdt {
                    handler.dispose()
                    WriteCommandAction.runWriteCommandAction(project) {
                        owned.forEach(manager::removeBreakpoint)
                        LocalFileSystem.getInstance().findFileByNioFile(root)?.delete(this)
                    }
                }
                if (Files.exists(root)) Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
            } finally { Disposer.dispose(disposable) }
        }
    }

    private fun <T> onEdt(action: () -> T): T {
        var result: T? = null
        EdtTestUtil.runInEdtAndWait<RuntimeException> { WriteIntentReadAction.run(Runnable { result = action() }) }
        @Suppress("UNCHECKED_CAST") return result as T
    }

    private class Peer : IDebugProtocolServer {
        val installed = linkedMapOf<String, List<Int>>()
        val calls = mutableListOf<Pair<String, List<Int>>>()
        override fun setBreakpoints(args: SetBreakpointsArguments): CompletableFuture<SetBreakpointsResponse> {
            val path = args.source.path
            val lines = args.breakpoints.map { it.line }
            check(args.lines.toList() == lines)
            installed[path] = lines; calls += path to lines
            return CompletableFuture.completedFuture(SetBreakpointsResponse().apply { breakpoints = emptyArray() })
        }
    }

    companion object { private const val SOURCE = "package main\nvar One = 1\nvar Two = 2\nvar Three = 3\n" }
}
