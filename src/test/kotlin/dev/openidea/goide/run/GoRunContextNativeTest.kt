package dev.openidea.goide.run

import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.lineMarker.ExecutorAction
import com.intellij.execution.lineMarker.RunLineMarkerProvider
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.EdtTestUtil
import com.intellij.testFramework.LightPlatformTestCase
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.util.xmlb.XmlSerializerUtil
import dev.openidea.goide.settings.GoProjectSettings
import dev.openidea.goide.settings.GoToolchain
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** Real native PSI/configuration contexts; setup and cleanup filesystem work stay off the EDT. */
class GoRunContextNativeTest : LightPlatformTestCase() {
    override fun runInDispatchThread() = false

    fun testBenchmarkAndFuzzProducersUseTheNativeGoTestFactoryAndExactSelectors() = fixture { file ->
        onEdt {
            val test = GoTestConfigurationProducer()
            val benchmark = GoBenchmarkConfigurationProducer()
            val fuzz = GoFuzzConfigurationProducer()
            assertSame(test.configurationFactory, benchmark.configurationFactory)
            assertSame(test.configurationFactory, fuzz.configurationFactory)
            for ((producer, name, mode) in listOf(Triple(benchmark, "Benchmark你好", GoTestMode.BENCHMARK),
                Triple(fuzz, "FuzzBytes", GoTestMode.FUZZ))) {
                val context = ConfigurationContext(element(file, name))
                val produced = requireNotNull(producer.createConfigurationFromContext(context)) { "No native configuration for $name" }
                val configuration = produced.configuration as GoRunConfiguration
                assertEquals(GoRunKind.TEST, configuration.kind)
                assertEquals(mode, configuration.parameters.testMode)
                assertEquals(".", configuration.parameters.target)
                assertEquals(file.virtualFile.parent.path, configuration.parameters.workingDirectory)
                assertEquals(name, produced.sourceElement.text)
                val selected = if (mode == GoTestMode.BENCHMARK) configuration.parameters.benchmarkPattern else configuration.parameters.fuzzPattern
                assertEquals("^$name$", selected)
                assertTrue(producer.isConfigurationFromContext(configuration, context))
                assertNull(test.createConfigurationFromContext(context))
            }
        }
    }

    fun testModeAndFunctionBothParticipateInConfigurationReuse() = fixture { file ->
        onEdt {
            val benchmark = GoBenchmarkConfigurationProducer()
            val fuzz = GoFuzzConfigurationProducer()
            val benchmarkContext = ConfigurationContext(element(file, "Benchmark你好"))
            val fuzzContext = ConfigurationContext(element(file, "FuzzBytes"))
            val produced = requireNotNull(benchmark.createConfigurationFromContext(benchmarkContext))
            val configuration = produced.configuration as GoRunConfiguration
            assertFalse(benchmark.isConfigurationFromContext(configuration, fuzzContext))
            assertFalse(fuzz.isConfigurationFromContext(configuration, fuzzContext))
            configuration.parameters.testMode = GoTestMode.TEST
            assertFalse(benchmark.isConfigurationFromContext(configuration, benchmarkContext))
            configuration.parameters.testMode = GoTestMode.BENCHMARK
            configuration.parameters.benchmarkPattern = "^BenchmarkOther$"
            assertFalse(benchmark.isConfigurationFromContext(configuration, benchmarkContext))
            assertNull(benchmark.createConfigurationFromContext(ConfigurationContext(element(file, "TestReady"))))
            assertNull(fuzz.createConfigurationFromContext(ConfigurationContext(element(file, "Benchmark你好"))))
        }
    }

    fun testGutterRecognizesRealSignaturesAndExamplesWithoutClaimingInvalidOrMethodEntries() = fixture { file ->
        onEdt {
            val gutter = GoRunLineMarkerContributor()
            for (name in listOf("TestReady", "Benchmark你好", "FuzzBytes", "ExampleReady"))
                assertNotNull("A valid $name entry must have native execution actions", gutter.getInfo(element(file, name)))
            for (name in listOf("TestWrong", "TestMain", "TestGeneric", "TestMethod", "ExampleNoOutput"))
                assertNull("$name must not be presented as an independently runnable entry", gutter.getInfo(element(file, name)))
            val producer = GoTestConfigurationProducer()
            val produced = requireNotNull(producer.createConfigurationFromContext(ConfigurationContext(element(file, "ExampleReady"))))
            val configuration = produced.configuration as GoRunConfiguration
            assertEquals(GoTestMode.TEST, configuration.parameters.testMode)
            assertEquals("^ExampleReady$", configuration.parameters.testPattern)
            for (name in listOf("TestWrong", "TestMain", "TestGeneric", "ExampleNoOutput"))
                assertNull(producer.createConfigurationFromContext(ConfigurationContext(element(file, name))))
        }
    }

    fun testRealEditorDataContextSelectsTheCaretEntryWithoutNamedPsi() = fixture { file ->
        val document = ReadAction.compute<com.intellij.openapi.editor.Document, RuntimeException> {
            requireNotNull(PsiDocumentManager.getInstance(project).getDocument(file))
        }
        onEdt {
            val editor = EditorFactory.getInstance().createEditor(document, project)
            try {
                for ((name, mode) in listOf("Benchmark你好" to GoTestMode.BENCHMARK, "FuzzBytes" to GoTestMode.FUZZ)) {
                    editor.caretModel.moveToOffset(file.text.indexOf(name) + 1)
                    val data = SimpleDataContext.builder()
                        .add(CommonDataKeys.PROJECT, project)
                        .add(CommonDataKeys.EDITOR, editor)
                        .add(CommonDataKeys.VIRTUAL_FILE, file.virtualFile)
                        .build()
                    val context = ConfigurationContext.getFromContext(data)
                    assertEquals(name, context.psiLocation?.text)
                    val produced = requireNotNull(context.createConfigurationsFromContext()).map { it.configuration }
                        .filterIsInstance<GoRunConfiguration>()
                    assertEquals("The native editor context must offer exactly the selected Go entry", 1, produced.size)
                    assertEquals(mode, produced.single().parameters.testMode)
                }
            } finally { EditorFactory.getInstance().releaseEditor(editor) }
        }
    }

    fun testNativeGutterPopupOffersEnabledRunAndDebugForTheClickedEntry() = fixture { file ->
        // The fixture's VFS refresh can still be pushing file properties. The
        // SDK intentionally hides non-dumb-aware context configurations then.
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val document = ReadAction.compute<com.intellij.openapi.editor.Document, RuntimeException> {
            requireNotNull(PsiDocumentManager.getInstance(project).getDocument(file))
        }
        onEdt {
            val editor = EditorFactory.getInstance().createEditor(document, project)
            try {
                // The gutter's location must take priority over a different entry at the editor caret.
                editor.caretModel.moveToOffset(file.text.indexOf("TestReady") + 1)
                val data = SimpleDataContext.builder()
                    .add(CommonDataKeys.PROJECT, project)
                    .add(CommonDataKeys.EDITOR, editor)
                    .add(CommonDataKeys.VIRTUAL_FILE, file.virtualFile)
                    .build()
                for (name in listOf("TestReady", "Benchmark你好", "FuzzBytes", "ExampleReady")) {
                    val identifier = element(file, name)
                    val info = requireNotNull(GoRunLineMarkerContributor().getInfo(identifier))
                    assertEquals(setOf(DefaultRunExecutor.EXECUTOR_ID, DefaultDebugExecutor.EXECUTOR_ID),
                        info.actions.filterIsInstance<ExecutorAction>().map { it.executor.id }.toSet())
                    val marker = requireNotNull(RunLineMarkerProvider().getLineMarkerInfo(identifier))
                    val popup = requireNotNull(marker.createGutterRenderer()?.popupMenuActions)
                    val actions = popup.getChildren(null)
                    assertEquals("A native gutter popup must retain both execution actions", 2, actions.size)
                    for (action in actions) {
                        val event = AnActionEvent.createFromAnAction(action, null, ActionPlaces.UNKNOWN, data)
                        action.update(event)
                        assertTrue("The native $name gutter action must be enabled: ${event.presentation.text}", event.presentation.isEnabledAndVisible)
                        assertTrue("The native action must target the clicked $name: ${event.presentation.text}", event.presentation.text.orEmpty().contains(name))
                    }
                }
            } finally { EditorFactory.getInstance().releaseEditor(editor) }
        }
    }

    fun testNativeGutterPopupActionPerformedRegistersTheClickedTestFrameworkAndSelector() = fixture { file ->
        for ((name, mode) in listOf("TestReady" to GoTestMode.TEST, "ExampleReady" to GoTestMode.TEST,
            "Benchmark你好" to GoTestMode.BENCHMARK, "FuzzBytes" to GoTestMode.FUZZ)) {
            val anchor = ReadAction.compute<PsiElement, RuntimeException> { element(file, name) }
            for (executor in listOf(DefaultRunExecutor.EXECUTOR_ID, DefaultDebugExecutor.EXECUTOR_ID)) {
                val parameters = invokeNativeGoGutter(file, anchor, executor, file.virtualFile.parent.path)
                assertEquals(mode, parameters.testMode)
                assertEquals(".", parameters.target)
                val selected = when (mode) {
                    GoTestMode.TEST -> parameters.testPattern
                    GoTestMode.BENCHMARK -> parameters.benchmarkPattern
                    GoTestMode.FUZZ -> parameters.fuzzPattern
                }
                assertEquals("^$name$", selected)
            }
        }
    }

    private fun element(file: PsiFile, name: String): PsiElement {
        val offset = file.text.indexOf(name)
        assertTrue("Missing test fixture identifier $name", offset >= 0)
        return file.findElementAt(offset + 1)!!
    }

    private fun fixture(operation: (PsiFile) -> Unit) {
        val root = Files.createTempDirectory("opengoland-native-test-entry-").toRealPath()
        val disposable = Disposer.newDisposable("native Go test producer roots")
        VfsRootAccess.allowRootAccess(disposable, root.toString())
        val path = root.resolve("entrypoints_test.go")
        val before = GoProjectSettings.Data().also { XmlSerializerUtil.copyBean(GoProjectSettings.get(project), it) }
        try {
            // These assertions exercise the ordinary ready native actions. The
            // separate build-context matrix covers Checking/Ignored/Error gates.
            Files.writeString(root.resolve("go.mod"), "module example.org/nativeentries\n\ngo 1.26.10\n")
            GoProjectSettings.get(project).apply {
                goExecutable = requireNotNull(GoToolchain.resolve("go", System.getProperty("opengoland.testGo") ?: System.getenv("OPENGOLAND_GO_TEST").orEmpty()))
                goos = ""; goarch = ""; cgoEnabled = "0"; buildTags = ""
                environment = "GOENV=off\nGOFLAGS=\nGOWORK=off\nGOTOOLCHAIN=local\nGOPROXY=off\nGOSUMDB=off\nGOTELEMETRY=off"
            }
            Files.writeString(path, """
                package fixture
                import check "testing"
                // 中文 😀 keeps native offsets distinct from UTF-8 byte positions.
                func TestReady(t *check.T) {}
                func Benchmark你好(b *check.B) {}
                func FuzzBytes(f *check.F) {}
                func ExampleReady() {
                    // Output:
                }
                func ExampleNoOutput() {}
                func TestWrong(t *check.B) {}
                func TestMain(m *check.M) {}
                func TestGeneric[T any](t *check.T) {}
                func (r Receiver) TestMethod(t *check.T) {}
            """.trimIndent())
            val virtual = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!
            val file = ReadAction.compute<PsiFile, RuntimeException> { PsiManager.getInstance(project).findFile(virtual)!! }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
            while (true) {
                val view = GoRunBuildContextService.get(project).lookup(file)
                if (view.status != GoRunBuildStatus.CHECKING) { assertEquals(view.message, GoRunBuildStatus.ACTIVE, view.status); break }
                check(System.nanoTime() < deadline) { "Native entry fixture build context did not complete" }; Thread.sleep(10)
            }
            IndexingTestUtil.waitUntilIndexesAreReady(project)
            operation(file)
        } finally {
            try {
                XmlSerializerUtil.copyBean(before, GoProjectSettings.get(project))
                Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
                LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root)
            } finally { Disposer.dispose(disposable) }
        }
    }

    private fun onEdt(action: () -> Unit) = EdtTestUtil.runInEdtAndWait<RuntimeException> { WriteIntentReadAction.run(Runnable(action)) }
}
