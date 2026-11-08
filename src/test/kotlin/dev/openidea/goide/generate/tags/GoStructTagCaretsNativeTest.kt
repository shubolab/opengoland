package dev.openidea.goide.generate.tags

import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.actionSystem.TypedAction
import com.intellij.openapi.editor.ex.DocumentEx
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.EdtTestUtil
import com.intellij.testFramework.LightPlatformTestCase
import java.nio.file.Files

/** Real native carets, TypedAction, document Undo/Redo and local Go PSI; no engine or mock editor. */
class GoStructTagCaretsNativeTest : LightPlatformTestCase() {
    override fun runInDispatchThread() = false

    fun testNativeMultipleCaretTypingAndUndoPreserveOptionsOtherKeysAndNestedFields() {
        val source = """
            package fixture
            // 中文 😀: offsets below are UTF-16.
            type Model struct {
                A int `json:"alpha,omitempty" xml:"first"`
                B string `xml:"second" json:"beta,string"`
                Nested struct { Child int `json:"child"` } `json:"nested"`
                Missing int `xml:"untouched"`
            }
            type Other struct { Else int `json:"else"` }
        """.trimIndent()
        fixture(source) { file, editor -> onEdt {
            val action = GoStructTagValuesIntention()
            editor.caretModel.moveToOffset(source.indexOf("json:"))
            assertTrue(action.isAvailable(project, editor, file))
            assertEquals("Edit tag values for json", action.text)
            val stamp = editor.document.modificationStamp
            editor.selectionModel.setSelection(editor.caretModel.offset, editor.caretModel.offset + 4)
            action.invoke(project, editor, file)
            assertEquals("Entering multi-caret editing must not mutate the source", source, editor.document.text)
            assertEquals(stamp, editor.document.modificationStamp)
            assertEquals(listOf(source.indexOf("alpha"), source.indexOf("beta"), source.indexOf("nested\"")), offsets(editor))
            assertEquals("The selected field stays primary", source.indexOf("alpha"), editor.caretModel.primaryCaret.offset)
            assertFalse(editor.caretModel.allCarets.any { it.hasSelection() })

            val context = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project)
                .add(CommonDataKeys.EDITOR, editor).add(CommonDataKeys.PSI_FILE, file).build()
            TypedAction.getInstance().actionPerformed(editor, 'x', context)
            val changed = source.replace("alpha,omitempty", "xalpha,omitempty").replace("beta,string", "xbeta,string")
                .replace("json:\"nested\"", "json:\"xnested\"")
            assertEquals(changed, editor.document.text)
            val wrapper = TextEditorProvider.getInstance().getTextEditor(editor)
            val undo = UndoManager.getInstance(project)
            assertTrue("Real native typing must be undoable", undo.isUndoAvailable(wrapper))
            undo.undo(wrapper)
            assertEquals(source, editor.document.text)
            assertTrue(undo.isRedoAvailable(wrapper))
            undo.redo(wrapper)
            assertEquals(changed, editor.document.text)
        } }
    }

    fun testEscapedInterpretedTagsUnicodeAndSharedDeclarationsUsePhysicalSourceOffsets() {
        val raw = "`键𐐀:\"原始😀,omitempty\" json:\"kept\"`"
        val interpreted = "\"\\u952e\\U00010400:\\\"escaped\\\\\\\"name,omitempty\\\" xml:\\\"kept\\\"\""
        val source = "package fixture\n// 😀\ntype Model struct{ A int $raw; B,C int $interpreted }\n"
        fixture(source) { file, editor -> onEdt {
            editor.caretModel.moveToOffset(source.indexOf("原始"))
            val action = GoStructTagValuesIntention()
            assertTrue(action.isAvailable(project, editor, file))
            assertEquals("Edit tag values for 键𐐀", action.text)
            action.invoke(project, editor, file)
            assertEquals("One physical shared tag has one caret", listOf(source.indexOf("原始"), source.indexOf("escaped")), offsets(editor))
            assertEquals(source, editor.document.text)
            // The same action is available from the encoded key/value too.
            editor.caretModel.removeSecondaryCarets()
            editor.caretModel.moveToOffset(source.indexOf("\\U00010400") + 3)
            assertTrue(action.isAvailable(project, editor, file))
            action.invoke(project, editor, file)
            assertEquals(listOf(source.indexOf("原始"), source.indexOf("escaped")), offsets(editor))
        } }
    }

    fun testNearestNestedStructEmptyValuesAndTypesContainingStringExpressions() {
        val source = """
            package fixture
            type Model struct {
                Outer int `json:"outer"`
                Nested struct {
                    A [len("array { json:\"fake\" }")]byte `json:""`
                    B func(string) (string, error) `json:"b,omitempty"`
                    C map[string]interface{ Read() } `json:"c"`
                } `json:"nested"`
            }
        """.trimIndent()
        fixture(source) { file, editor -> onEdt {
            editor.caretModel.moveToOffset(source.indexOf("json:\"b"))
            val action = GoStructTagValuesIntention()
            assertTrue(action.isAvailable(project, editor, file))
            action.invoke(project, editor, file)
            assertEquals(listOf(source.indexOf("json:\"\"") + 6, source.indexOf("b,omitempty"), source.indexOf("json:\"c") + 6), offsets(editor))
            assertEquals(source, editor.document.text)
        } }
    }

    fun testReadonlyDeletedAndChangedDocumentsCannotUseOldTargets() {
        val source = "package fixture\ntype Model struct{ A int `json:\"a\"`; B int `yaml:\"b\"` }\n"
        fixture(source) { file, editor ->
            val action = GoStructTagValuesIntention()
            onEdt {
                editor.caretModel.moveToOffset(source.indexOf("json:"))
                assertTrue(action.isAvailable(project, editor, file))
                (editor.document as DocumentEx).setReadOnly(true)
                assertFalse(action.isAvailable(project, editor, file))
                action.invoke(project, editor, file)
                assertEquals(1, editor.caretModel.caretCount)
                editor.document.setReadOnly(false)
                WriteCommandAction.runWriteCommandAction(project, Runnable {
                    editor.document.replaceString(source.indexOf("json:"), source.indexOf("json:") + 4, "yaml")
                })
                // Invoke the already-created action after the menu's snapshot is stale.
                action.invoke(project, editor, file)
                assertEquals(listOf(source.indexOf("a\""), source.indexOf("b\"")), offsets(editor))
                WriteCommandAction.runWriteCommandAction(project, Runnable { file.virtualFile.delete(this) })
                val carets = offsets(editor)
                assertFalse(action.isAvailable(project, editor, file))
                action.invoke(project, editor, file)
                assertEquals(carets, offsets(editor))
            }
        }
    }

    fun testMalformedAmbiguousAndNonTagLocationsAreRejectedWithoutChangingNativeCarets() {
        val samples = listOf(
            "type Model struct{ A int `json:\"a\" json:\"duplicate\"` }",
            "type Model struct{ A int `json:unquoted` }",
            "type Model struct{ A int `json:\"a\"`; B int `xml:broken` }",
            "type Model struct{ A int `json:\"a\"`", // Missing struct closing brace.
            "type Model struct{ A int `json:\"a\"`; B func(] }", // Mismatch inside the target.
            "type Model struct{ A int `json:\"a\"`; /* unfinished comment }",
            "type Model struct{ A,B `json:\"a\"` }", // Multiple field names without a type.
        )
        for (body in samples) {
            val source = "package fixture\n$body\n"
            fixture(source) { file, editor -> onEdt {
                editor.caretModel.moveToOffset(source.indexOf("json:"))
                val before = offsets(editor)
                val action = GoStructTagValuesIntention()
                assertFalse(source, action.isAvailable(project, editor, file))
                action.invoke(project, editor, file)
                assertEquals(before, offsets(editor)); assertEquals(source, editor.document.text)
            } }
        }
        val source = "package fixture\ntype Model struct{ A int `json:\"a\"` }\n"
        fixture(source) { file, editor -> onEdt {
            val action = GoStructTagValuesIntention()
            for (offset in listOf(source.indexOf("Model"), source.indexOf("A int"), source.indexOf('`'))) {
                editor.caretModel.moveToOffset(offset)
                assertFalse(action.isAvailable(project, editor, file))
            }
        } }
    }

    fun testCompleteStructRemainsEditableBesideUnfinishedUnrelatedScopes() {
        val complete = "package fixture\ntype Model struct{ A int `json:\"a\"`; B int `json:\"b,omitempty\"` }\n"
        for (suffix in listOf("func unfinished() {\n if true {", "/* unfinished comment {", "func broken(] {")) {
            val source = complete + suffix
            fixture(source) { file, editor -> onEdt {
                editor.caretModel.moveToOffset(source.indexOf("json:"))
                val action = GoStructTagValuesIntention()
                assertTrue("A complete target must survive unrelated unfinished code: $suffix", action.isAvailable(project, editor, file))
                action.invoke(project, editor, file)
                assertEquals(listOf(source.indexOf("a\""), source.indexOf("b,omitempty")), offsets(editor))
                assertEquals(source, editor.document.text)
            } }
        }
    }

    private fun offsets(editor: Editor) = editor.caretModel.allCarets.map { it.offset }.sorted()
    private fun onEdt(action: () -> Unit) = EdtTestUtil.runInEdtAndWait<RuntimeException> { WriteIntentReadAction.run(Runnable(action)) }

    private fun fixture(source: String, block: (PsiFile, Editor) -> Unit) {
        val root = Files.createTempDirectory("open-goland-tag-carets-").toRealPath()
        val disposable = Disposer.newDisposable("owned tag caret fixture")
        VfsRootAccess.allowRootAccess(disposable, root.toString())
        var editor: Editor? = null
        try {
            val path = root.resolve("model.go")
            Files.writeString(path, source)
            val virtual = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path))
            val file = ReadAction.compute<PsiFile, RuntimeException> { requireNotNull(PsiManager.getInstance(project).findFile(virtual)) }
            val document = ReadAction.compute<com.intellij.openapi.editor.Document, RuntimeException> {
                requireNotNull(PsiDocumentManager.getInstance(project).getDocument(file))
            }
            lateinit var native: Editor
            onEdt { native = EditorFactory.getInstance().createEditor(document, project); editor = native }
            block(file, native)
        } finally {
            onEdt {
                editor?.takeUnless { it.isDisposed }?.let {
                    if (!it.document.isWritable) it.document.setReadOnly(false)
                    EditorFactory.getInstance().releaseEditor(it)
                }
            }
            root.toFile().deleteRecursively()
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root)
            Disposer.dispose(disposable)
        }
    }
}
