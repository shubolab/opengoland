package dev.openidea.goide.generate.methods

import com.google.gson.JsonParser
import com.intellij.codeInsight.hints.declarative.InlayActionHandler
import com.intellij.codeInsight.hints.declarative.InlineInlayPosition
import com.intellij.codeInsight.hints.declarative.StringInlayActionPayload
import com.intellij.codeInsight.hints.declarative.impl.ActionWithContent
import com.intellij.codeInsight.hints.declarative.impl.InlayData
import com.intellij.codeInsight.hints.declarative.impl.InlayTreeSinkImpl
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseEventArea
import com.intellij.openapi.editor.ex.DocumentEx
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.EdtTestUtil
import com.intellij.testFramework.LightPlatformTestCase
import com.intellij.testFramework.LightVirtualFile
import dev.openidea.goide.lang.GoFileType
import java.awt.event.MouseEvent
import java.nio.file.Files

/** The real SDK declarative tree and public mouse-click path; only the final worker launch is injected. */
class GoImplementInterfaceHintsNativeTest : LightPlatformTestCase() {
    override fun runInDispatchThread() = false

    fun testNativePresentationRoutesItsBoundUnicodeInterfaceToTheExistingAction() = fixture { files, editors ->
        val file = files.first(); val editor = editors.first()
        val data = collect(file, editor).single()
        val node = action(data)
        assertEquals("Implement interface", node.content)
        assertEquals(GoImplementInterfaceHints.ACTION_ID, node.actionData.handlerId)
        assertEquals(SOURCE.indexOf('{') + 1, (data.position as InlineInlayPosition).offset)
        assertTrue(InlayActionHandler.getActionHandler(node.actionData.handlerId) is GoImplementInterfaceInlayAction)
        var calls = 0
        val handler = GoImplementInterfaceInlayAction { target, selectedEditor ->
            calls++
            assertSame(file, target); assertSame(editor, selectedEditor)
            assertEquals(SOURCE.indexOf("接口𐐀"), editor.caretModel.offset)
            assertEquals("接口𐐀", editor.selectionModel.selectedText)
        }
        onEdt { handler.handleClick(mouse(editor), node.actionData.payload) }
        assertEquals(1, calls)
    }

    fun testStaleAndOtherDocumentPayloadsCannotStartAnalysis() = fixture { files, editors ->
        val first = action(collect(files[0], editors[0]).single()).actionData.payload as StringInlayActionPayload
        collect(files[1], editors[1]) // Assign the other real document its own distinct identity.
        val json = JsonParser.parseString(first.text).asJsonObject
        onEdt { WriteCommandAction.runWriteCommandAction(project, Runnable {
            (editors[1].document as DocumentEx).setModificationStamp(editors[0].document.modificationStamp)
        }) }
        json.addProperty("file", files[1].virtualFile.url)
        var calls = 0
        val handler = GoImplementInterfaceInlayAction { _, _ -> calls++ }
        onEdt { handler.handleClick(mouse(editors[1]), StringInlayActionPayload(json.toString())) }
        assertEquals("Matching text/stamp/file URL must not bypass the document identity", 0, calls)
        onEdt { WriteCommandAction.runWriteCommandAction(project, Runnable { editors[0].document.insertString(0, "// edited\n") }) }
        onEdt { handler.handleClick(mouse(editors[0]), first) }
        assertEquals(0, calls)
    }

    fun testDeletedFileAndMalformedPayloadCannotStartAnalysis() = fixture { files, editors ->
        val payload = action(collect(files[0], editors[0]).single()).actionData.payload
        var calls = 0
        val handler = GoImplementInterfaceInlayAction { _, _ -> calls++ }
        onEdt { handler.handleClick(mouse(editors[0]), StringInlayActionPayload("{}")) }
        onEdt { WriteCommandAction.runWriteCommandAction(project, Runnable { files[0].virtualFile.delete(this) }) }
        onEdt { handler.handleClick(mouse(editors[0]), payload) }
        assertEquals(0, calls)
    }

    fun testSettingsPreviewHasTheNativeTextWithoutAnExecutableClickPayload() {
        val virtual = LightVirtualFile("preview.go", GoFileType.INSTANCE, SOURCE)
        val file = ReadAction.compute<PsiFile, RuntimeException> {
            requireNotNull(PsiManager.getInstance(project).findFile(virtual))
        }
        val document = ReadAction.compute<com.intellij.openapi.editor.Document, RuntimeException> {
            requireNotNull(FileDocumentManager.getInstance().getDocument(virtual))
        }
        assertFalse("Settings preview must remain a non-local native file", virtual.isInLocalFileSystem)
        lateinit var editor: Editor
        onEdt { editor = EditorFactory.getInstance().createEditor(document, project) }
        try {
            val data = collect(file, editor).single()
            val payloads = (0 until data.tree.size).map { data.tree.getDataPayload(it.toByte()) }
            assertTrue(payloads.any { it == "Implement interface" })
            assertFalse(payloads.any { it is ActionWithContent })
        } finally { onEdt { EditorFactory.getInstance().releaseEditor(editor) } }
    }

    private fun collect(file: PsiFile, editor: Editor): List<InlayData> = ReadAction.compute<List<InlayData>, RuntimeException> {
        val sink = InlayTreeSinkImpl(GoImplementInterfaceHints.PROVIDER_ID, emptyMap(), false, false, GoImplementInterfaceHints::class.java, "native-test")
        requireNotNull(GoImplementInterfaceHints().createCollector(file, editor)).collectHintsForFile(file, sink)
        sink.finish()
    }

    private fun action(data: InlayData): ActionWithContent = (0 until data.tree.size).map { data.tree.getDataPayload(it.toByte()) }.filterIsInstance<ActionWithContent>().single()
    private fun mouse(editor: Editor) = EditorMouseEvent(editor,
        MouseEvent(editor.contentComponent, MouseEvent.MOUSE_CLICKED, 0, 0, 1, 1, 1, false), EditorMouseEventArea.EDITING_AREA)
    private fun onEdt(action: () -> Unit) = EdtTestUtil.runInEdtAndWait<RuntimeException> { WriteIntentReadAction.run(Runnable(action)) }

    private fun fixture(block: (List<PsiFile>, List<Editor>) -> Unit) {
        val root = Files.createTempDirectory("open-goland-interface-inlay-").toRealPath()
        val roots = Disposer.newDisposable("owned interface inlay roots")
        VfsRootAccess.allowRootAccess(roots, root.toString())
        val editors = mutableListOf<Editor>()
        try {
            val files = listOf("first.go", "other.go").map { name ->
                val path = root.resolve(name)
                Files.writeString(path, SOURCE)
                val virtual = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path))
                ReadAction.compute<PsiFile, RuntimeException> { requireNotNull(PsiManager.getInstance(project).findFile(virtual)) }
            }
            for (file in files) {
                val document = ReadAction.compute<com.intellij.openapi.editor.Document, RuntimeException> { requireNotNull(PsiDocumentManager.getInstance(project).getDocument(file)) }
                onEdt { editors += EditorFactory.getInstance().createEditor(document, project) }
            }
            block(files, editors)
        } finally {
            onEdt { editors.forEach { if (!it.isDisposed) EditorFactory.getInstance().releaseEditor(it) } }
            root.toFile().deleteRecursively()
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root)
            Disposer.dispose(roots)
        }
    }

    companion object { private const val SOURCE = "package fixture\n// 中文 😀\ntype 接口𐐀 interface { Read([]byte) (int, error) }\n" }
}
