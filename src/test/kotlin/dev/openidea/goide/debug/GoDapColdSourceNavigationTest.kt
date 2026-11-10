package dev.openidea.goide.debug

import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.configurations.RunConfigurationOptions
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.process.NopProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.ui.ConsoleView
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileEditor.impl.navigateAndSelectEditor
import com.intellij.openapi.fileEditor.impl.text.TextEditorImpl
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.platform.debugger.impl.shared.XDebuggerUtilImplShared
import com.redhat.devtools.lsp4ij.LanguageServersRegistry
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightPlatformTestCase
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugProcessStarter
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.impl.XDebugSessionImpl
import com.intellij.xdebugger.impl.XSourceKind
import com.intellij.xdebugger.impl.XDebuggerExecutionPointManagerImpl
import com.redhat.devtools.lsp4ij.dap.DAPDebugProcess
import com.redhat.devtools.lsp4ij.dap.DAPDebugRunner
import com.redhat.devtools.lsp4ij.dap.DAPServerReadyTracker
import com.redhat.devtools.lsp4ij.dap.DebugMode
import com.redhat.devtools.lsp4ij.dap.TransportStreams
import com.redhat.devtools.lsp4ij.dap.client.DAPClient
import com.redhat.devtools.lsp4ij.dap.client.DAPStackFrame
import com.redhat.devtools.lsp4ij.dap.configurations.DAPCommandLineState
import com.redhat.devtools.lsp4ij.dap.descriptors.DebugAdapterDescriptor
import com.redhat.devtools.lsp4ij.dap.descriptors.ServerReadyConfig
import com.redhat.devtools.lsp4ij.settings.ServerTrace
import dev.openidea.goide.lang.GoFileType
import org.eclipse.lsp4j.debug.Source
import org.eclipse.lsp4j.debug.StackFrame
import org.eclipse.lsp4j.debug.Thread as DapThread
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.function.Supplier
import javax.swing.Icon
import javax.swing.JPanel

/** Real DAP frame/source positions and native file-editor navigation; no adapter process is launched. */
class GoDapColdSourceNavigationTest : LightPlatformTestCase() {
    override fun runInDispatchThread() = false

    fun testFirstColdGoFrameOpensAtItsDapLineThroughNativeOpen() = withSource { fixture, file ->
        assertNull(ReadAction.compute<com.intellij.openapi.editor.Document?, RuntimeException> {
            FileDocumentManager.getInstance().getCachedDocument(file)
        })
        val position = edt { requireNotNull(fixture.frame(file).sourcePosition) }
        assertEquals("The first frame query must exercise the deferred EDT path",
            "com.redhat.devtools.lsp4ij.dap.client.files.DeferredLocalFileSourcePosition", position.javaClass.name)
        val descriptor = ReadAction.compute<OpenFileDescriptor, RuntimeException> {
            position.createNavigatable(project) as OpenFileDescriptor
        }
        // LightPlatform's TestEditorManager bypasses this production gate. Use
        // a real provider-created TextEditorImpl and the production selection
        // function first, exactly as FileEditorManagerImpl.openEditorImpl does.
        val nativeEditor = edt { TextEditorProvider.getInstance().createEditor(project, file) as TextEditorImpl }
        try {
            edt {
                assertTrue("The production text editor must accept the first deferred DAP position",
                    navigateAndSelectEditor(nativeEditor, descriptor, null))
                assertEquals(TARGET_LINE, nativeEditor.editor.caretModel.logicalPosition.line)
            }
        } finally { edt { TextEditorProvider.getInstance().disposeEditor(nativeEditor) } }
        // The pinned ExecutionPositionNavigator OPEN consumer passes the
        // existing descriptor unchanged to this public API.
        val editor = edt { requireNotNull(XDebuggerUtil.getInstance().openTextEditor(descriptor)) }
        edt {
            assertEquals(file, FileDocumentManager.getInstance().getFile(editor.document))
            assertEquals("The first native OPEN must retain the DAP line", TARGET_LINE, editor.caretModel.logicalPosition.line)
            assertEquals(editor.document.getLineStartOffset(TARGET_LINE), editor.caretModel.offset)
        }
    }

    fun testCachedGoFrameKeepsItsDapLineThroughNativeOpen() = withSource { fixture, file ->
        edt { FileEditorManager.getInstance(project).openTextEditor(OpenFileDescriptor(project, file, 0), false) }
        val position = ReadAction.compute<XSourcePosition, RuntimeException> { requireNotNull(fixture.frame(file).sourcePosition) }
        val descriptor = ReadAction.compute<OpenFileDescriptor, RuntimeException> {
            val navigatable = position.createNavigatable(project)
            if (navigatable is OpenFileDescriptor) navigatable else XDebuggerUtilImplShared.createOpenFileDescriptor(project, position)
        }
        val editor = edt { requireNotNull(XDebuggerUtil.getInstance().openTextEditor(descriptor)) }
        edt {
            assertEquals(file, FileDocumentManager.getInstance().getFile(editor.document))
            assertEquals(TARGET_LINE, editor.caretModel.logicalPosition.line)
        }
    }

    fun testColdExecutionPointManagerNavigatesAndHighlightsItsDapLine() = withSource { fixture, file ->
        assertNull(ReadAction.compute<com.intellij.openapi.editor.Document?, RuntimeException> {
            FileDocumentManager.getInstance().getCachedDocument(file)
        })
        val position = edt { requireNotNull(fixture.frame(file).sourcePosition) }
        val manager = XDebuggerExecutionPointManagerImpl.getInstance(project)
        try {
            edt { manager.setExecutionPoint(position, null, true, XSourceKind.MAIN) }
            waitFor {
                val editor = FileEditorManager.getInstance(project).selectedTextEditor
                editor != null && FileDocumentManager.getInstance().getFile(editor.document) == file && editor.caretModel.logicalPosition.line == TARGET_LINE &&
                    manager.isFullLineHighlighterAt(file, TARGET_LINE, project, true)
            }
        } finally { edt { manager.clearExecutionPoint() } }
    }

    private fun withSource(body: (ClientFixture, VirtualFile) -> Unit) {
        // Source-navigation controls must not start an unrelated real gopls.
        // Keep the native Go file/editor; disable only its project LSP mapping
        // for this bounded fixture and restore the previous state afterwards.
        val definition = requireNotNull(LanguageServersRegistry.getInstance().getServerDefinition("openidea.gopls"))
        val enabled = definition.isEnabled(project)
        definition.setEnabled(false, project)
        val directory = Files.createTempDirectory("owned-cold-go-source ").toRealPath()
        val path = directory.resolve("cold_source.go")
        Files.writeString(path, buildString {
            append("package navigation\n")
            repeat(70) { append("// 中文😀 line ").append(it).append('\n') }
        })
        VfsRootAccess.allowRootAccess(testRootDisposable, directory.toString())
        val file = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path))
        try { ClientFixture().use { body(it, file) } }
        finally {
            edt { FileEditorManager.getInstance(project).closeFile(file) }
            Files.deleteIfExists(path)
            Files.deleteIfExists(directory)
            definition.setEnabled(enabled, project)
        }
    }

    /** Only the irrelevant adapter handshake is controlled; source lookup/navigation stay unmodified. */
    private inner class ClientFixture : AutoCloseable {
        val client = CompletableFuture<DAPClient>()
        private val handler = NopProcessHandler()
        val session: XDebugSessionImpl
        private lateinit var process: DAPDebugProcess

        init {
            val console = Proxy.newProxyInstance(ConsoleView::class.java.classLoader, arrayOf(ConsoleView::class.java)) { _, method, _ ->
                when (method.name) {
                    "getComponent", "getPreferredFocusableComponent" -> JPanel()
                    else -> when (method.returnType) { Boolean::class.javaPrimitiveType -> false; Int::class.javaPrimitiveType -> 0; else -> null }
                }
            } as ConsoleView
            val profile = object : RunProfile {
                override fun getName() = "Owned DAP source navigation"
                override fun getIcon(): Icon? = null
                override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState? = null
            }
            val environment = ExecutionEnvironmentBuilder(project, DefaultDebugExecutor.getDebugExecutorInstance())
                .runProfile(profile).runner(DAPDebugRunner()).build()
            val options = object : RunConfigurationOptions() {}
            val descriptor = object : DebugAdapterDescriptor(options, environment, null) {
                override fun getId() = "owned-source-position-test"
                override fun getFileType() = GoFileType.INSTANCE
                override fun getDebugMode() = DebugMode.ATTACH
                override fun getDapParameters() = emptyMap<String, Any>()
                override fun getServerReadyConfig(mode: DebugMode) = ServerReadyConfig("127.0.0.1", 1)
                override fun startServer(): ProcessHandler = error("Source navigation must not launch an adapter")
                override fun createClient(process: DAPDebugProcess, parameters: MutableMap<String, Any>, debug: Boolean,
                    mode: DebugMode, trace: ServerTrace, parent: DAPClient?): DAPClient =
                    object : DAPClient(process, parameters, debug, mode, trace, parent) {
                        override fun connectToServer(indicator: ProgressIndicator) = CompletableFuture.completedFuture<Void>(null)
                        override fun getThreads(refresh: Boolean) = CompletableFuture.completedFuture(emptyArray<DapThread>())
                    }.also { client.complete(it) }
            }
            val state = DAPCommandLineState(descriptor, options, environment)
            handler.startNotify()
            DAPServerReadyTracker(descriptor.getServerReadyConfig(DebugMode.ATTACH), DebugMode.ATTACH, handler)
            session = edt {
                XDebuggerManager.getInstance(project).newSessionBuilder(object : XDebugProcessStarter() {
                    override fun start(session: XDebugSession): XDebugProcess =
                        object : DAPDebugProcess(state, session, DefaultExecutionResult(console, handler), true) {
                            override fun getStreamsSupplier(): Supplier<TransportStreams> = Supplier {
                                TransportStreams.DefaultTransportStreams(ByteArrayInputStream(byteArrayOf()), ByteArrayOutputStream())
                            }
                        }.also { process = it }
                }).environment(environment).showTab(false).startSession().session as XDebugSessionImpl
            }
        }

        fun frame(file: VirtualFile) = DAPStackFrame(requireNotNull(client.getNow(null)), StackFrame().apply {
            id = 123; name = "navigation.frame"; line = TARGET_LINE + 1; column = 1
            source = Source().apply { name = file.name; path = file.path; sourceReference = 0 }
        })

        override fun close() {
            edt { session.stop() }
            process.dispose()
            handler.destroyProcess()
            assertTrue(handler.waitFor(3_000))
        }
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            if (edt(condition)) return
            Thread.sleep(10)
        }
        assertTrue("Actual SDK navigation/highlight did not reach the DAP source line", edt(condition))
    }

    private fun <T> edt(body: () -> T): T {
        var value: T? = null
        ApplicationManager.getApplication().invokeAndWait { WriteIntentReadAction.run { value = body() } }
        @Suppress("UNCHECKED_CAST") return value as T
    }

    companion object { private const val TARGET_LINE = 42 }
}
