package dev.openidea.goide.generate.tests

import com.intellij.codeInsight.navigation.GotoTargetHandler
import com.intellij.codeInsight.navigation.ItemWithPresentation
import com.intellij.codeInsight.navigation.activateFileWithPsiElement
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.SmartPointerManager
import com.intellij.testFramework.EdtTestUtil
import com.intellij.testFramework.LightPlatformTestCase
import com.intellij.testIntegration.GotoTestOrCodeHandler
import com.intellij.testIntegration.TestFinderHelper
import com.intellij.ui.TitlePanel
import com.intellij.ui.popup.AbstractPopup
import java.awt.Component
import java.awt.Container
import java.nio.file.Files
import java.util.function.Consumer
import javax.swing.JList

class GoTestChooserNativeTest : LightPlatformTestCase() {
    override fun runInDispatchThread() = false

    fun testActualSdkChooserUsesNamedTitlesAndReceiverAwareTargetPresentations() = fixture { files ->
        for ((filename, name, expected) in listOf(
            Triple("subject.go", "Load", setOf("TestLoad", "TestLoad_extra")),
            Triple("subject_test.go", "TestRead", setOf("Reader.Read", "Writer.Read")),
        )) {
            val file = files.getValue(filename)
            val data = ReadAction.compute<GotoTargetHandler.GotoData, RuntimeException> {
                val selected = requireNotNull(file.findElementAt(file.text.indexOf("$name(") + 1))
                val source = requireNotNull(TestFinderHelper.findSourceElement(selected))
                val targets = if (TestFinderHelper.isTest(selected)) TestFinderHelper.findClassesForTest(selected) else TestFinderHelper.findTestsForClass(selected)
                GotoTargetHandler.GotoData(source, targets.toTypedArray(), emptyList())
            }
            onEdt {
                val document = FileDocumentManager.getInstance().getCachedDocument(file.virtualFile)!!
                val editor = EditorFactory.getInstance().createEditor(document, project)
                try {
                    var inspected = false
                    NativeHandler().showNative(project, editor, file, data) { popup ->
                        val title = ((popup as AbstractPopup).title as TitlePanel).label.text
                        assertTrue("The actual SDK chooser title must name $name: $title", title.contains(name))
                        assertFalse("A native Test/Subject title cannot contain null: $title", title.contains("null"))
                        val list = components(popup.content).filterIsInstance<JList<*>>().single()
                        val items = (0 until list.model.size).mapNotNull { list.model.getElementAt(it) as? ItemWithPresentation }
                        assertEquals(expected, items.map { it.presentation.presentableText }.toSet())
                        assertTrue(items.all { it.dereference()?.isValid == true })
                        assertTrue(items.all { it.presentation.containerText.orEmpty().contains(".go") })
                        inspected = true
                    }
                    assertTrue("The actual SDK show path must construct its popup", inspected)
                } finally { EditorFactory.getInstance().releaseEditor(editor) }
            }
        }
    }

    fun testDirtyTargetNavigationUsesCurrentUtf16OffsetsAndExpiresOnFurtherEdits() = fixture { files ->
        val source = files.getValue("subject.go")
        val test = files.getValue("subject_test.go")
        val document = FileDocumentManager.getInstance().getCachedDocument(test.virtualFile)!!
        onEdt { WriteCommandAction.runWriteCommandAction(project) { document.insertString(0, "// 未保存 😀\n") } }
        val target = ReadAction.compute<PsiElement, RuntimeException> {
            val sourceName = source.findElementAt(source.text.indexOf("Load(") + 1)!!
            val targets = TestFinderHelper.findTestsForClass(sourceName)
            assertEquals(2, targets.size)
            targets.single { it.containingFile == test }
        }
        val pointer = ReadAction.compute<com.intellij.psi.SmartPsiElementPointer<PsiElement>, RuntimeException> { SmartPointerManager.createPointer(target) }
        onEdt {
            val expectedOffset = document.text.indexOf("TestLoad(")
            assertEquals(expectedOffset, target.textRange.startOffset)
            assertTrue(activateFileWithPsiElement(target, true))
            val selected = FileEditorManager.getInstance(project).selectedTextEditor!!
            assertSame(document, selected.document)
            assertEquals(expectedOffset, selected.caretModel.offset)
            WriteCommandAction.runWriteCommandAction(project) { document.insertString(0, "// another edit\n") }
            assertFalse("Old chooser entries cannot navigate after their document changes", target.isValid)
            assertFalse((target as Navigatable).canNavigate())
        }
        ReadAction.run<RuntimeException> { assertNull("SDK chooser pointers must not restore an expired navigation snapshot", pointer.element) }
    }

    private class NativeHandler : GotoTestOrCodeHandler() {
        fun showNative(project: Project, editor: Editor, file: PsiFile, data: GotoTargetHandler.GotoData, inspect: (JBPopup) -> Unit) =
            show(project, editor, file, data, Consumer { inspect(it) })

        // The SDK automatically accepts a popup in unit-test mode. Keep this test on
        // its real creation/presentation path; actual editor navigation is tested above.
        override fun navigateToElement(project: Project?, descriptor: Navigatable) = Unit
    }

    private fun components(container: Container): List<Component> = container.components.flatMap {
        listOf(it) + if (it is Container) components(it) else emptyList()
    }

    private fun fixture(operation: (Map<String, PsiFile>) -> Unit) {
        val root = Files.createTempDirectory("opengoland-native-test-chooser-").toRealPath()
        val disposable = Disposer.newDisposable("native Test/Subject chooser roots")
        VfsRootAccess.allowRootAccess(disposable, root.toString())
        try {
            val content = mapOf(
                "subject.go" to "package fixture\n// 中文 😀\ntype Reader struct{}\ntype Writer struct{}\nfunc (r Reader) Read() {}\nfunc (r Writer) Read() {}\nfunc Load() {}\n",
                "subject_test.go" to "package fixture\nimport \"testing\"\nfunc TestLoad(t *testing.T) {}\nfunc TestRead(t *testing.T) {}\n",
                "other_test.go" to "package fixture_test\nimport \"testing\"\nfunc TestLoad_extra(t *testing.T) {}\n",
            )
            content.forEach { (name, text) -> Files.writeString(root.resolve(name), text) }
            val files = content.keys.associateWith { name ->
                val virtual = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root.resolve(name))!!
                ReadAction.compute<PsiFile, RuntimeException> {
                    FileDocumentManager.getInstance().getDocument(virtual)!!
                    PsiManager.getInstance(project).findFile(virtual)!!
                }
            }
            operation(files)
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

    private fun onEdt(action: () -> Unit) = EdtTestUtil.runInEdtAndWait<RuntimeException> { action() }
}
