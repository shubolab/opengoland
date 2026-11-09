package dev.openidea.goide.refactor.native

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.ide.TitledHandler
import com.intellij.lang.Language
import com.intellij.lang.refactoring.InlineActionHandler
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.LangDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.EdtTestUtil
import com.intellij.testFramework.LightPlatformTestCase
import dev.openidea.goide.lang.GoLanguage
import dev.openidea.goide.engine.EngineClient
import dev.openidea.goide.refactor.signature.array
import dev.openidea.goide.refactor.signature.string
import java.nio.file.Files
import java.nio.file.Path

/** Actual platform action dispatch, native document/Undo and DialogWrapper models. */
class GoNativeRefactorNativeTest : LightPlatformTestCase() {
    override fun runInDispatchThread() = false
    override fun setUp() {
        super.setUp()
        // The private SDK harness uses the previous sandbox descriptor. Official
        // Gradle tests omit this flag and require the real plugin registration.
        if (java.lang.Boolean.getBoolean("opengoland.testNativeRefactorInline"))
            InlineActionHandler.EP_NAME.point.registerExtension(GoInlineActionHandler(), testRootDisposable)
    }

    fun testStandardIntroduceAndExtractActionsResolveTheGoHandlers() = fixture { f ->
        edt {
            f.editor.caretModel.moveToOffset(SOURCE.indexOf("value + 1") + 2)
            val leaf = requireNotNull(f.file.findElementAt(f.editor.caretModel.offset))
            for ((id, title) in mapOf("IntroduceVariable" to "Extract Go Variable", "IntroduceConstant" to "Extract Go Constant", "ExtractMethod" to "Extract Go Function")) {
                val action = requireNotNull(ActionManager.getInstance().getAction(id))
                val context = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).add(CommonDataKeys.EDITOR, f.editor)
                    .add(LangDataKeys.CONTEXT_LANGUAGES, arrayOf<Language>(GoLanguage))
                    .add(CommonDataKeys.PSI_FILE, f.file).add(CommonDataKeys.PSI_ELEMENT, leaf).build()
                val event = AnActionEvent.createFromDataContext(ActionPlaces.MAIN_MENU, null, context)
                action.update(event)
                assertTrue("The standard $id action must be enabled", event.presentation.isEnabled)
                val method = generateSequence(action.javaClass as Class<*>?) { it.superclass }.flatMap { it.declaredMethods.asSequence() }
                    .first { it.name == "getHandler" && it.parameterTypes.contentEquals(arrayOf(Language::class.java, PsiElement::class.java)) }
                    .apply { isAccessible = true }
                val handler = method.invoke(action, GoLanguage, leaf)
                assertTrue("Standard $id resolved ${handler?.javaClass}", handler is GoNativeExtractHandler)
                assertEquals(title, (handler as TitledHandler).actionTitle)
            }
        }
    }

    fun testStandardInlineActionUsesTheRegisteredGoActionHandler() = fixture { f -> edt {
        val handlers = InlineActionHandler.EP_NAME.extensionList.filterIsInstance<GoInlineActionHandler>()
        assertEquals("Exactly one real Go inline registration is required", 1, handlers.size)
        f.editor.caretModel.moveToOffset(SOURCE.lastIndexOf("result"))
        val leaf = requireNotNull(f.file.findElementAt(f.editor.caretModel.offset))
        val action = requireNotNull(ActionManager.getInstance().getAction("Inline"))
        val context = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).add(CommonDataKeys.EDITOR, f.editor)
            .add(LangDataKeys.CONTEXT_LANGUAGES, arrayOf<Language>(GoLanguage))
            .add(CommonDataKeys.PSI_FILE, f.file).add(CommonDataKeys.PSI_ELEMENT, leaf).build()
        val event = AnActionEvent.createFromDataContext(ActionPlaces.MAIN_MENU, null, context)
        action.update(event)
        assertTrue(event.presentation.isEnabled)
        assertTrue(handlers.single().canInlineElementInEditor(leaf, f.editor))
        assertFalse(handlers.single().isEnabledOnElement(leaf, null))
    } }

    fun testCapturedInputDoesNotReviveAfterNativeEditAndUndo() = fixture { f ->
        val snapshot = read { GoNativeRefactorSnapshot.capture(f.file, f.editor) }
        snapshot.checkCurrent()
        edt {
            WriteCommandAction.runWriteCommandAction(project) { f.document.insertString(0, "// changed 😀\n") }
            PsiDocumentManager.getInstance(project).commitDocument(f.document)
        }
        assertRejected { snapshot.checkCurrent() }
        edt {
            UndoManager.getInstance(project).undo(TextEditorProvider.getInstance().getTextEditor(f.editor))
            PsiDocumentManager.getInstance(project).commitDocument(f.document)
        }
        assertEquals(SOURCE, read { f.document.text })
        assertRejected { snapshot.checkCurrent() }
        read { GoNativeRefactorSnapshot.capture(f.file, f.editor) }.checkCurrent()
    }

    fun testDependencyFirstOpenedAfterCaptureIsStillRejectedAfterEditAndUndo() = fixture { f ->
        val snapshot = read { GoNativeRefactorSnapshot.capture(f.file, f.editor) }.includeCoverage(JsonObject().apply {
            add("coverage", JsonObject().apply { add("modules", JsonArray().apply { add(f.root.toString()) }) })
        })
        val dependency = read { requireNotNull(FileDocumentManager.getInstance().getDocument(f.dependency)) }
        var dependencyEditor: Editor? = null
        try {
            edt {
                dependencyEditor = EditorFactory.getInstance().createEditor(dependency, project)
                WriteCommandAction.runWriteCommandAction(project) { dependency.insertString(0, "// user change\n") }
                PsiDocumentManager.getInstance(project).commitDocument(dependency)
                UndoManager.getInstance(project).undo(TextEditorProvider.getInstance().getTextEditor(requireNotNull(dependencyEditor)))
                PsiDocumentManager.getInstance(project).commitDocument(dependency)
            }
            assertEquals(DEPENDENCY, read { dependency.text })
            assertRejected { snapshot.checkCurrent() }
        } finally { edt { dependencyEditor?.let(EditorFactory.getInstance()::releaseEditor) } }
    }

    fun testNamingDialogUsesEditableNamesAndExplicitLastUseRemoval() = fixture { _ -> edt {
        val inspection = JsonObject().apply {
            addProperty("inspectionId", "actual-input"); addProperty("configurationDigest", "actual-config")
            add("names", JsonArray().apply { add(JsonObject().apply { addProperty("nameId", "introduced"); addProperty("generatedName", "newMethod"); addProperty("suggestedName", "calculateValue"); addProperty("type", "func() int") }) })
        }
        val dialog = GoNativeRefactorDialog(project, inspection, "extract.method")
        try { val choice = dialog.selection(); assertEquals("calculateValue", choice.array("names")[0].asJsonObject.string("name")); assertEquals("actual-input", choice.string("inspectionId")) }
        finally { Disposer.dispose(dialog.disposable) }
        val inline = JsonObject().apply {
            addProperty("inspectionId", "inline"); addProperty("configurationDigest", "config")
            add("inline", JsonObject().apply { addProperty("name", "x"); addProperty("references", 1); addProperty("canKeep", false); addProperty("canRemove", true) })
        }
        val inlineDialog = GoNativeRefactorDialog(project, inline, "inline.variable")
        try { assertEquals("remove", inlineDialog.selection().string("inlineDeclaration")) }
        finally { Disposer.dispose(inlineDialog.disposable) }
    } }

    fun testDisposingAnUnusedRefactoringServiceDoesNotCreateAnEngine() {
        val before = project.getServiceIfCreated(EngineClient::class.java)
        val service = GoNativeRefactorService(project)
        edt { service.dispose() }
        assertSame(before, project.getServiceIfCreated(EngineClient::class.java))
    }

    private class Fixture(val root: Path, val file: PsiFile, val dependency: VirtualFile, val document: Document, val editor: Editor)
    private fun fixture(action: (Fixture) -> Unit) {
        val root = Files.createTempDirectory("opengoland-refactor-native-").toRealPath()
        val disposable = Disposer.newDisposable("native refactoring fixture")
        VfsRootAccess.allowRootAccess(disposable, root.toString())
        var editor: Editor? = null
        try {
            Files.writeString(root.resolve("model.go"), SOURCE); Files.writeString(root.resolve("dependency.go"), DEPENDENCY)
            val local = LocalFileSystem.getInstance(); val virtual = requireNotNull(local.refreshAndFindFileByNioFile(root.resolve("model.go")))
            val dependency = requireNotNull(local.refreshAndFindFileByNioFile(root.resolve("dependency.go")))
            val rootFile = requireNotNull(local.refreshAndFindFileByNioFile(root)); rootFile.children
            val file = read { requireNotNull(PsiManager.getInstance(project).findFile(virtual)) }
            val document = read { requireNotNull(FileDocumentManager.getInstance().getDocument(virtual)) }
            edt { ModuleRootModificationUtil.addContentRoot(module, rootFile.path); editor = EditorFactory.getInstance().createEditor(document, project) }
            action(Fixture(root, file, dependency, document, requireNotNull(editor)))
        } finally {
            edt {
                editor?.let { if (!it.isDisposed) EditorFactory.getInstance().releaseEditor(it) }
                WriteCommandAction.runWriteCommandAction(project) {
                    FileDocumentManager.getInstance().unsavedDocuments.filter { FileDocumentManager.getInstance().getFile(it)?.path?.startsWith(root.toString()) == true }
                        .forEach { FileDocumentManager.getInstance().saveDocument(it) }
                }
            }
            Disposer.dispose(disposable); root.toFile().deleteRecursively(); LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root)
        }
    }
    private fun assertRejected(action: () -> Unit) { try { action(); fail("The old native request revived") } catch (_: IllegalArgumentException) { } catch (_: IllegalStateException) { } }
    private fun edt(action: () -> Unit) = EdtTestUtil.runInEdtAndWait<RuntimeException> { WriteIntentReadAction.run(Runnable(action)) }
    private fun <T> read(action: () -> T): T = ReadAction.compute<T, RuntimeException> { action() }
    companion object {
        private const val SOURCE = "package fixture\n// 中文 😀\nfunc F(value int) int { result := value + 1; println(result); return result }\n"
        private const val DEPENDENCY = "package fixture\nfunc Other()int{return 1}\n"
    }
}
