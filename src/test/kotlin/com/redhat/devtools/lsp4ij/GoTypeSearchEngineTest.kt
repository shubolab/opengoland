package com.redhat.devtools.lsp4ij

import com.intellij.ide.impl.OpenProjectTask
import com.intellij.ide.util.gotoByName.ChooseByNamePopup
import com.intellij.ide.util.gotoByName.GotoClassModel2
import com.intellij.ide.util.gotoByName.DefaultChooseByNameItemProvider
import com.intellij.openapi.progress.util.ProgressIndicatorBase
import com.intellij.util.Processor
import com.intellij.navigation.ChooseByNameContributor
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.module.EmptyModuleType
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.project.ex.ProjectManagerEx
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.EdtTestUtil
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.LightPlatformTestCase
import com.intellij.testFramework.IndexingTestUtil
import dev.openidea.goide.engine.EngineClient
import dev.openidea.goide.navigation.search.GoTypeSearchContributor
import dev.openidea.goide.navigation.search.GoTypeSearchItem
import dev.openidea.goide.navigation.search.GoTypeSearchService
import com.intellij.psi.search.GlobalSearchScope
import dev.openidea.goide.settings.GoProjectSettings
import dev.openidea.goide.settings.GoToolchain
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Actual default EngineClient/service and actual native type chooser; no in-memory catalog. */
class GoTypeSearchEngineTest : LightPlatformTestCase() {
    override fun runInDispatchThread() = false

    fun testColdDefaultServiceFindsRealTypedDeclarationsAndUnregisteredSdkBuffer() = fixture { f ->
        assertTrue(FileEditorManager.getInstance(f.project).openFiles.none { it.extension == "go" })
        val started = System.nanoTime()
        assertEquals(listOf("PilotRecord"), f.search("PilotRecord").map { it.name })
        val loaded = System.nanoTime()
        val index = GoTypeSearchService.get(f.project)
        val catalog = index.catalog(GlobalSearchScope.allScope(f.project))
        // All Places uses native fuzzy matching: other platforms' SDKs can
        // legitimately contribute callbackArgs/winCallback and similar names.
        val callback = f.search("Callback").single { it.qualifiedName == "example.org/typesearchnative.Callback" }
        assertEquals("Callback", callback.name)
        val callbackEntry = catalog.catalog.types.single {
            it.name == "Callback" && it.packagePath == "example.org/typesearchnative"
        }
        assertEquals("function", callbackEntry.kind)
        val callbackSource = ReadAction.compute<Pair<Path, Int>, RuntimeException> {
            Path.of(callback.containingFile.virtualFile.path).toAbsolutePath().normalize() to callback.textOffset
        }
        assertEquals(f.root.resolve("model.go"), callbackSource.first)
        assertEquals(SOURCE.indexOf("type Callback") + "type ".length, callbackSource.second)
        val projectCallbacks = mutableListOf<GoTypeSearchItem>()
        DefaultChooseByNameItemProvider(null).filterElements(f.popup, "Callback", false, ProgressIndicatorBase(),
            Processor { item -> if (item is GoTypeSearchItem) projectCallbacks += item; true })
        assertEquals(listOf(callback.qualifiedName), projectCallbacks.map { it.qualifiedName })
        assertEquals(listOf("BoxAlias"), f.search("BoxAlias").map { it.name })
        val projectMatches = mutableListOf<Any>()
        // calcPopupElements intentionally expands to libraries after an empty
        // Project result. Test its actual provider's explicit Project scope here.
        DefaultChooseByNameItemProvider(null).filterElements(f.popup, "bytes.Buffer", false, ProgressIndicatorBase(),
            Processor { projectMatches += it; true })
        assertTrue(projectMatches.isEmpty())
        assertEquals("A real SDK/dependency superset must serve Project without another engine catalog", catalog.catalog.id,
            index.catalog(GlobalSearchScope.projectScope(f.project)).catalog.id)
        println("Go type search: actual engine cold ms=${(loaded - started) / 1_000_000}, cached lookups and Project switch ms=${(System.nanoTime() - loaded) / 1_000_000}")
        val buffer = f.search("bytes.Buffer").single { it.qualifiedName == "bytes.Buffer" }
        assertTrue(buffer.canNavigateToSource())
        edt { buffer.navigate(true) }
        edt {
            val editor = requireNotNull(FileEditorManager.getInstance(f.project).selectedTextEditor)
            assertTrue(requireNotNull(editor.virtualFile).path.replace('\\', '/').endsWith("/src/bytes/buffer.go"))
            val expected = editor.document.text.indexOf("type Buffer struct") + "type ".length
            assertEquals(expected, editor.caretModel.offset)
        }
        assertTrue(f.search("OrdinaryFunction").isEmpty())
    }

    fun testRealCatalogConsumesUnsavedUtf16SourceAndNavigatesTheSameNativeBuffer() = fixture { f ->
        val file = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(f.root.resolve("model.go")))
        val document = ReadAction.compute<com.intellij.openapi.editor.Document, RuntimeException> { requireNotNull(FileDocumentManager.getInstance().getDocument(file)) }
        lateinit var editor: com.intellij.openapi.editor.Editor
        edt { editor = EditorFactory.getInstance().createEditor(document, f.project) }
        try {
            val dirty = "// unsaved 前😀\n" + SOURCE.replace("PilotRecord", "DirtyPilot")
            edt {
                WriteCommandAction.runWriteCommandAction(f.project) { document.setText(dirty) }
            }
            val selected = f.search("DirtyPilot").single()
            edt { selected.navigate(true) }
            edt {
                val actual = requireNotNull(FileEditorManager.getInstance(f.project).selectedTextEditor)
                assertSame(document, actual.document)
                assertEquals(dirty.indexOf("DirtyPilot"), actual.caretModel.offset)
            }
            assertEquals(SOURCE, Files.readString(f.root.resolve("model.go")))
        } finally { edt { if (!editor.isDisposed) EditorFactory.getInstance().releaseEditor(editor) } }
    }

    private inner class Fixture(val project: Project, val root: Path, val popup: ChooseByNamePopup) {
        fun search(pattern: String, everywhere: Boolean = true): List<GoTypeSearchItem> {
            var result = emptyList<GoTypeSearchItem>()
            edt { result = popup.calcPopupElements(pattern, everywhere).filterIsInstance<GoTypeSearchItem>() }
            return result
        }
    }

    private fun fixture(block: (Fixture) -> Unit) {
        val configuredGo = System.getProperty("opengoland.testGo") ?: System.getenv("OPENGOLAND_GO_TEST").orEmpty()
        val go = Path.of(GoToolchain.resolve("go", configuredGo)
            ?: error("A real Go SDK is required. Set opengoland.testGo or OPENGOLAND_GO_TEST, or add go to PATH."))
        val root = Files.createTempDirectory("open-goland-native-type-engine-").toRealPath()
        Files.writeString(root.resolve("go.mod"), "module example.org/typesearchnative\n\ngo 1.24.0\n")
        Files.writeString(root.resolve("model.go"), SOURCE)
        val initial = Files.list(root).use { entries -> entries.toList().associate { it.fileName.toString() to Files.readString(it) } }
        val disposable = Disposer.newDisposable("owned real type search")
        VfsRootAccess.allowRootAccess(disposable, root.toString(), go.parent.parent.toString())
        var owner: Project? = null
        var popup: ChooseByNamePopup? = null
        var child: Process? = null
        var descendants = emptyList<ProcessHandle>()
        try {
            val project = requireNotNull(ProjectManagerEx.getInstanceEx().newProject(root, OpenProjectTask {
                isNewProject = true; useDefaultProjectAsTemplate = false; projectName = "owned native Go type search"
                preloadServices = false; createModule = false
            }))
            owner = project
            val content = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root))
            edt {
                // Project scope must describe this owned project, rather than
                // an empty module model. No Go editor or catalog is opened.
                WriteAction.run<RuntimeException> {
                    val module = ModuleManager.getInstance(project).newNonPersistentModule("native-type-search", EmptyModuleType.EMPTY_MODULE)
                    ModuleRootModificationUtil.addContentRoot(module, content.path)
                }
                GoProjectSettings.get(project).apply {
                    goExecutable = go.toString()
                    environment = "GOENV=off\nGOPROXY=off\nGOSUMDB=off\nGOTOOLCHAIN=local\nGOTELEMETRY=off\nGOWORK=off"
                }
            }
            IndexingTestUtil.waitUntilIndexesAreReady(project)
            ExtensionTestUtil.maskExtensions(ChooseByNameContributor.CLASS_EP_NAME, listOf(GoTypeSearchContributor()), disposable)
            edt { popup = ChooseByNamePopup.createPopup(project, GotoClassModel2(project), null as com.intellij.psi.PsiElement?, "") }
            block(Fixture(project, root, requireNotNull(popup)))
        } finally {
            owner?.getServiceIfCreated(EngineClient::class.java)?.let { engine ->
                child = EngineClient::class.java.getDeclaredField("process").apply { isAccessible = true }.get(engine) as? Process
                descendants = child?.toHandle()?.descendants()?.use { it.toList() }.orEmpty()
            }
            edt {
                popup?.let { if (!Disposer.isDisposed(it)) Disposer.dispose(it) }
                owner?.let { project -> if (!project.isDisposed) WriteAction.run<RuntimeException> { Disposer.dispose(project) } }
            }
            child?.let { assertTrue("The owned engine must exit with its project", it.waitFor(10, TimeUnit.SECONDS)) }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (descendants.any { it.isAlive } && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue("Observed owned descendants must not outlive the project", descendants.none { it.isAlive })
            assertEquals(initial, Files.list(root).use { entries -> entries.filter { it.fileName.toString() in initial }.toList().associate { it.fileName.toString() to Files.readString(it) } })
            Disposer.dispose(disposable)
            root.toFile().deleteRecursively()
        }
    }
    private fun edt(action: () -> Unit) = EdtTestUtil.runInEdtAndWait<RuntimeException> { WriteIntentReadAction.run(Runnable(action)) }
    companion object {
        private const val SOURCE = "package sample\nimport \"bytes\"\ntype PilotRecord struct { Value bytes.Buffer }\ntype Callback func(int) string\ntype Box[T any] struct { Value T }\ntype BoxAlias[T any] = Box[T]\nfunc OrdinaryFunction() {}\n"
    }
}
