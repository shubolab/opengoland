package dev.openidea.goide.run.corpus

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.impl.DaemonProgressIndicator
import com.intellij.codeInsight.daemon.impl.HighlightingSessionImpl
import com.intellij.codeInsight.daemon.impl.LineMarkersPass
import com.intellij.codeInsight.multiverse.CodeInsightContextManager
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.lineMarker.RunLineMarkerProvider
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.ProperTextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.EdtTestUtil
import com.intellij.testFramework.LightPlatformTestCase
import dev.openidea.goide.run.GoRunConfiguration
import dev.openidea.goide.run.GoRunKind
import dev.openidea.goide.run.GoTestMode
import dev.openidea.goide.run.invokeNativeGoGutter
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real content detection, corpus PSI, native gutter and the existing test configuration factory. */
class GoFuzzCorpusNativeTest : LightPlatformTestCase() {
    override fun runInDispatchThread() = false

    fun testRecognizedCorpusGetsASingleSeedRunAndDebugContextAndReuseIsExact() = fixture { file ->
        onEdt {
            assertSame(GoFuzzCorpusFileType.INSTANCE, file.fileType)
            assertSame(GoFuzzCorpusLanguage, file.language)
            val element = file.findElementAt(0)!!
            val context = ConfigurationContext(element)
            val producer = GoFuzzCorpusConfigurationProducer()
            val produced = producer.createConfigurationFromContext(context)
            assertNotNull("The public producer flow must create a corpus configuration", produced)
            val configuration = produced!!.configurationSettings.configuration as GoRunConfiguration
            assertEquals(element, produced.sourceElement)
            assertEquals(GoRunKind.TEST, configuration.kind)
            assertEquals(GoTestMode.TEST, configuration.parameters.testMode)
            assertEquals(".", configuration.parameters.target)
            assertEquals(file.virtualFile.parent.parent.parent.parent.path, configuration.parameters.workingDirectory)
            assertEquals("^FuzzDecode$/^817e8c7d02cb3556$", configuration.parameters.testPattern)
            val run = configuration.parameters.commandArguments(GoRunKind.TEST, "")
            assertTrue(run.contains(configuration.parameters.testPattern))
            assertFalse(run.any { it.startsWith("-fuzz") })
            assertEquals(listOf("-run", configuration.parameters.testPattern), configuration.parameters.testRuntimeArguments(debug = true))
            assertTrue(producer.isConfigurationFromContext(configuration, context))
            val marker = GoFuzzCorpusRunLineMarkerContributor()
            assertNotNull(marker.getInfo(element))
            assertNull(marker.getInfo(file.findElementAt(file.text.indexOf("[]byte"))!!))
            configuration.parameters.testMode = GoTestMode.FUZZ
            assertFalse(producer.isConfigurationFromContext(configuration, context))
            configuration.parameters.testMode = GoTestMode.TEST
            configuration.parameters.testPattern = "^FuzzOther$/^817e8c7d02cb3556$"
            assertFalse(producer.isConfigurationFromContext(configuration, context))
        }
    }

    fun testUnsavedCorruptHeaderDisablesCorpusExecutionWithoutReadingTheSavedFile() = fixture { file ->
        onEdt {
            val document = FileDocumentManager.getInstance().getCachedDocument(file.virtualFile)!!
            val producer = GoFuzzCorpusConfigurationProducer()
            WriteCommandAction.runWriteCommandAction(project) { document.replaceString(0, 15, "go test fuzz v2") }
            assertNull(corpusContext(file))
            assertNull(producer.createConfigurationFromContext(ConfigurationContext(file)))
            WriteCommandAction.runWriteCommandAction(project) { document.replaceString(0, 15, "go test fuzz v1") }
            assertNotNull(corpusContext(file))
        }
    }

    fun testPlatformRunMarkerDispatchUsesTheCorpusLeafLanguage() = fixture { file ->
        onEdt {
            val header = file.findElementAt(0)!!
            assertSame("Native run marker contributors are selected by the PSI leaf's language", GoFuzzCorpusLanguage, header.language)
            val marker = RunLineMarkerProvider().getLineMarkerInfo(header)
            assertNotNull("The platform provider must reach the corpus contributor", marker)
            assertEquals(0, marker!!.startOffset)
            val popup = marker.createGutterRenderer()!!.popupMenuActions
            assertNotNull("The native corpus gutter must expose an execution popup", popup)
            assertEquals(2, popup!!.getChildren(null).size)
        }
    }

    fun testDaemonLineMarkerCollectionIncludesTheCorpusReplayGutter() = fixture { file ->
        // Use the actual daemon collector and its language/visibility gates, rather than
        // calling our contributor directly as the earlier producer test does.
        var collected: Collection<LineMarkerInfo<*>> = emptyList()
        ProgressManager.getInstance().runProcess({
            ReadAction.run<RuntimeException> {
                val document = FileDocumentManager.getInstance().getCachedDocument(file.virtualFile)!!
                val context = CodeInsightContextManager.getInstance(project).getCodeInsightContext(file.viewProvider)
                HighlightingSessionImpl.runInsideHighlightingSession(file, context,
                    EditorColorsManager.getInstance().globalScheme, ProperTextRange(file.textRange), false) {
                    collected = LineMarkersPass.queryLineMarkers(file, document)
                }
            }
        }, DaemonProgressIndicator())
        assertEquals("The real daemon collector must retain one corpus replay gutter", 1, collected.size)
        val marker = collected.single()
        assertEquals(0, marker.startOffset)
        assertEquals(2, marker.createGutterRenderer()!!.popupMenuActions!!.getChildren(null).size)
    }

    fun testNativeCorpusGutterActionPerformedRegistersExactSeedReplayForRunAndDebug() = fixture { file ->
        val header = ReadAction.compute<com.intellij.psi.PsiElement, RuntimeException> { file.findElementAt(0)!! }
        val directory = file.virtualFile.parent.parent.parent.parent.path
        for (executor in listOf(DefaultRunExecutor.EXECUTOR_ID, DefaultDebugExecutor.EXECUTOR_ID)) {
            val parameters = invokeNativeGoGutter(file, header, executor, directory)
            assertEquals(GoTestMode.TEST, parameters.testMode)
            assertEquals(".", parameters.target)
            assertEquals(directory, parameters.workingDirectory)
            assertEquals("^FuzzDecode$/^817e8c7d02cb3556$", parameters.testPattern)
            assertFalse(parameters.testRuntimeArguments().any { it.startsWith("-fuzz") })
        }
    }

    fun testConsoleHyperlinkOpensTheActualCorpusInANativeEditor() = fixture { file ->
        val opened = CountDownLatch(1)
        val connection = project.messageBus.connect()
        try {
            onEdt {
                connection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
                    override fun fileOpened(source: FileEditorManager, openedFile: VirtualFile) {
                        if (openedFile == file.virtualFile) opened.countDown()
                    }
                })
                val path = "testdata/fuzz/FuzzDecode/817e8c7d02cb3556"
                val line = "Failing input written to $path\n"
                val root = file.virtualFile.parent.parent.parent.parent.path
                val link = GoFuzzCorpusConsoleFilter(root).applyFilter(line, line.length)!!.hyperlinkInfo!!
                link.navigate(project)
            }
            assertTrue("Clicking the native console link must open its actual corpus file", opened.await(8, TimeUnit.SECONDS))
            onEdt { assertEquals(file.virtualFile, FileEditorManager.getInstance(project).selectedFiles.single()) }
        } finally { connection.disconnect() }
    }

    fun testManualNamesNeedingGoRewriteRemainCorpusDataWithoutAnIncorrectReplayAction() = fixture("seed_name") { file ->
        onEdt {
            assertSame(GoFuzzCorpusFileType.INSTANCE, file.fileType)
            assertSame(GoFuzzCorpusLanguage, file.language)
            assertNull(corpusContext(file))
            val producer = GoFuzzCorpusConfigurationProducer()
            assertNull(producer.createConfigurationFromContext(ConfigurationContext(file)))
            assertNull(GoFuzzCorpusRunLineMarkerContributor().getInfo(file.findElementAt(0)!!))
        }
    }

    private fun fixture(caseName: String = "817e8c7d02cb3556", operation: (PsiFile) -> Unit) {
        val root = Files.createTempDirectory("opengoland-native-fuzz-corpus-").toRealPath()
        val disposable = Disposer.newDisposable("Go fuzz corpus roots")
        VfsRootAccess.allowRootAccess(disposable, root.toString())
        val path = root.resolve("testdata/fuzz/FuzzDecode/$caseName")
        try {
            Files.createDirectories(path.parent)
            Files.writeString(path, "go test fuzz v1\n[]byte(\"中文😀\")\n")
            val virtual = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!
            // First detection and document loading deliberately happen on this test worker.
            assertSame(GoFuzzCorpusFileType.INSTANCE, virtual.fileType)
            val file = ReadAction.compute<PsiFile, RuntimeException> {
                FileDocumentManager.getInstance().getDocument(virtual)!!
                PsiManager.getInstance(project).findFile(virtual)!!
            }
            operation(file)
        } finally {
            try {
                onEdt {
                    val manager = FileEditorManager.getInstance(project)
                    manager.openFiles.filter { it.path.startsWith(root.toString() + "/") }.forEach(manager::closeFile)
                }
                Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
                LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root)
            } finally { Disposer.dispose(disposable) }
        }
    }

    private fun onEdt(action: () -> Unit) = EdtTestUtil.runInEdtAndWait<RuntimeException> { WriteIntentReadAction.run(Runnable(action)) }
}
