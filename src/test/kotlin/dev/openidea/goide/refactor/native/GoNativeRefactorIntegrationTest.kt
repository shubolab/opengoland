package dev.openidea.goide.refactor.native

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.EdtTestUtil
import com.intellij.testFramework.HeavyPlatformTestCase
import dev.openidea.goide.engine.EngineClient
import dev.openidea.goide.lsp.GoDocumentSync
import dev.openidea.goide.refactor.GoWorkspaceEditService
import dev.openidea.goide.refactor.signature.GoSignatureCodec
import dev.openidea.goide.refactor.signature.GoSignatureValidation
import dev.openidea.goide.refactor.signature.array
import dev.openidea.goide.refactor.signature.bool
import dev.openidea.goide.refactor.signature.string
import dev.openidea.goide.settings.GoProjectSettings
import dev.openidea.goide.settings.GoToolchain
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Real engine worker/RPC → named typed plan → native guarded command → global Undo/Redo. */
class GoNativeRefactorIntegrationTest : HeavyPlatformTestCase() {
    override fun runInDispatchThread() = false
    override fun isCreateDirectoryBasedProject() = true
    override fun getProjectDirOrFile(isDirectoryBased: Boolean): Path = tempDir.newPath("project").resolve("原生 重构 😀")

    fun testNamedMethodCollisionPlanHasOneGlobalUndoRedoAndDoesNotSaveDirtySource() = fixture { f ->
        val plan = f.plan()
        val text = plan.getAsJsonObject("workspaceEdit").array("documentChanges")[0].asJsonObject.array("edits")[0].asJsonObject.string("newText")
        assertTrue(text, text.contains("func (t T) calculate() int"))
        assertEquals(1, Regex("func \\(t T\\) newMethod\\(\\)").findAll(text).count())
        val result = f.service.apply(GoSignatureCodec.workspaceEdit(plan), TITLE, false, f.snapshot.request,
            validation = f.snapshot.validation(plan)).get(90, TimeUnit.SECONDS)
        assertTrue(result.failureReason.orEmpty(), result.isApplied)
        assertEquals(text, read { f.document.text }); assertEquals(DISK, Files.readString(f.source))
        edt {
            val undo = UndoManager.getInstance(project)
            assertTrue("One global native command owns the complete named extraction", undo.isUndoAvailable(null))
            undo.undo(null)
            assertEquals(DIRTY, f.document.text)
            assertTrue(FileDocumentManager.getInstance().isDocumentUnsaved(f.document))
            assertTrue(undo.isRedoAvailable(null)); undo.redo(null)
            assertEquals(text, f.document.text)
        }
        assertEquals(DISK, Files.readString(f.source))
    }

    fun testEditAndNativeUndoAfterPlanningCannotReviveTheOldPlan() = fixture { f ->
        val plan = f.plan()
        edt {
            WriteCommandAction.runWriteCommandAction(project) { f.document.insertString(0, "// another edit\n") }
            PsiDocumentManager.getInstance(project).commitDocument(f.document)
            UndoManager.getInstance(project).undo(TextEditorProvider.getInstance().getTextEditor(f.editor))
            PsiDocumentManager.getInstance(project).commitDocument(f.document)
            assertEquals(DIRTY, f.document.text)
        }
        val result = f.service.apply(GoSignatureCodec.workspaceEdit(plan), TITLE, false, f.snapshot.request,
            validation = f.snapshot.validation(plan)).get(90, TimeUnit.SECONDS)
        assertFalse("Text restoration must not authorize an old operation", result.isApplied)
        assertEquals(DIRTY, read { f.document.text }); assertEquals(DISK, Files.readString(f.source))
    }

    fun testDependencyOpenedAfterCaptureAndRestoredByUndoStillRejectsAtomicApplication() = fixture { f ->
        val plan = f.plan()
        val dependency = read { requireNotNull(FileDocumentManager.getInstance().getDocument(f.dependency)) }
        var editor: Editor? = null
        try {
            edt {
                editor = EditorFactory.getInstance().createEditor(dependency, project)
                WriteCommandAction.runWriteCommandAction(project) { dependency.insertString(0, "// dependency edit\n") }
                PsiDocumentManager.getInstance(project).commitDocument(dependency)
                UndoManager.getInstance(project).undo(TextEditorProvider.getInstance().getTextEditor(requireNotNull(editor)))
                PsiDocumentManager.getInstance(project).commitDocument(dependency)
            }
            val result = f.service.apply(GoSignatureCodec.workspaceEdit(plan), TITLE, false, f.snapshot.request,
                validation = f.snapshot.validation(plan)).get(90, TimeUnit.SECONDS)
            assertFalse(result.isApplied)
            assertEquals(DIRTY, read { f.document.text }); assertEquals(DISK, Files.readString(f.source))
        } finally { edt { editor?.let(EditorFactory.getInstance()::releaseEditor) } }
    }

    fun testDeclarationInlineAllAcrossFilesHasOneNativeUndoAndKeepsBothBuffersUnsaved() = fixture { f ->
        val before = DIRTY + "func UseOther()int{return Other()+Other()}\n"
        edt {
            WriteCommandAction.runWriteCommandAction(project) { f.document.setText(before) }
            PsiDocumentManager.getInstance(project).commitDocument(f.document)
        }
        val dependency = read { requireNotNull(FileDocumentManager.getInstance().getDocument(f.dependency)) }
        val dependencyBefore = read { dependency.text }
        val plan = f.planAll()
        assertEquals(2, plan.getAsJsonObject("workspaceEdit").array("documentChanges").size())
        val result = f.service.apply(GoSignatureCodec.workspaceEdit(plan), "Inline Go Declaration", false,
            f.snapshot.request, validation = f.snapshot.validation(plan)).get(90, TimeUnit.SECONDS)
        assertTrue(result.failureReason.orEmpty(), result.isApplied)
        assertFalse(read { dependency.text }.contains("func Other"))
        assertFalse(Regex("\\bOther\\(").containsMatchIn(read { f.document.text }))
        assertEquals(DISK, Files.readString(f.source))
        assertEquals(dependencyBefore, Files.readString(Path.of(f.dependency.path)))
        edt {
            val undo = UndoManager.getInstance(project)
            assertTrue(undo.isUndoAvailable(null)); undo.undo(null)
            assertEquals(before, f.document.text); assertEquals(dependencyBefore, dependency.text)
            assertTrue(undo.isRedoAvailable(null)); undo.redo(null)
            assertFalse(dependency.text.contains("func Other"))
            assertFalse(Regex("\\bOther\\(").containsMatchIn(f.document.text))
            assertTrue(FileDocumentManager.getInstance().isDocumentUnsaved(f.document))
            assertTrue(FileDocumentManager.getInstance().isDocumentUnsaved(dependency))
        }
    }

    private inner class Fixture(val root: Path, val source: Path, val document: Document, val editor: Editor,
                                val dependency: VirtualFile, val file: PsiFile) {
        val service = GoWorkspaceEditService.get(project)
        lateinit var snapshot: GoNativeRefactorSnapshot
        private val engine get() = project.getService(EngineClient::class.java)
        private fun rpc(method: String, params: JsonObject) = engine.request(method,
            params.deepCopy().apply { addProperty("operationId", UUID.randomUUID().toString()) }, timeoutSeconds = 120)
            .get(130, TimeUnit.SECONDS).asJsonObject
        fun plan(): JsonObject {
            snapshot = read { GoNativeRefactorSnapshot.capture(file, editor) }.prepare(EmptyProgressIndicator())
            val selection = JsonObject().apply { add("start", position(5, 1)); add("end", position(6, 16)) }
            fun request() = snapshot.wire.deepCopy().apply { addProperty("kind", "extract.method"); add("selection", selection) }
            val context = rpc("refactor/native/inspect", request())
            assertTrue(context.toString(), context.array("conflicts").isEmpty())
            snapshot = snapshot.includeCoverage(context).withSourceDigest(context.string("sourceDigest"))
            val input = request().apply {
                addProperty("candidateSourceSha256", GoSignatureValidation.sha256(DIRTY.toByteArray(Charsets.UTF_8)))
                addProperty("candidateKind", "refactor.extract.method")
                add("candidateEdit", JsonObject().apply { add("documentChanges", JsonArray().apply { add(JsonObject().apply {
                    add("textDocument", JsonObject().apply { addProperty("uri", GoDocumentSync.fileUri(source)); add("version", JsonNull.INSTANCE) })
                    add("edits", JsonArray().apply { add(JsonObject().apply {
                        add("range", JsonObject().apply { add("start", position(4, 0)); add("end", position(8, 1)) }); addProperty("newText", RAW)
                    }) })
                }) }) })
            }
            val inspection = rpc("refactor/native/inspect", input)
            assertTrue(inspection.toString(), inspection.array("conflicts").isEmpty())
            assertEquals(1, inspection.array("names").size())
            val result = rpc("refactor/native/plan", input.deepCopy().apply {
                addProperty("inspectionId", inspection.string("inspectionId")); addProperty("configurationDigest", inspection.string("configurationDigest"))
                add("names", JsonArray().apply { add(JsonObject().apply {
                    addProperty("nameId", inspection.array("names")[0].asJsonObject.string("nameId")); addProperty("name", "calculate")
                }) })
            })
            assertTrue(result.toString(), result.array("conflicts").isEmpty())
            assertTrue(result.toString(), result.getAsJsonObject("validation").bool("typeChecked"))
            assertEquals(DIRTY, read { document.text }); assertEquals(DISK, Files.readString(source))
            return result
        }
        fun planAll(): JsonObject {
            snapshot = read { GoNativeRefactorSnapshot.capture(file, editor) }.prepare(EmptyProgressIndicator())
            val text = read { document.text }
            val offset = text.indexOf("return Other()") + "return ".length
            val prefix = text.substring(0, offset)
            val line = prefix.count { it == '\n' }
            val column = prefix.length - (prefix.lastIndexOf('\n') + 1)
            fun input() = snapshot.wire.deepCopy().apply {
                addProperty("kind", "inline.call-all")
                add("selection", JsonObject().apply { add("start", position(line, column)); add("end", position(line, column + 5)) })
            }
            val inspection = rpc("refactor/native/inspect", input())
            assertTrue(inspection.toString(), inspection.array("conflicts").isEmpty())
            snapshot = snapshot.includeCoverage(inspection).withSourceDigest(inspection.string("sourceDigest"))
            val fresh = rpc("refactor/native/inspect", input())
            val result = rpc("refactor/native/plan", input().apply {
                addProperty("inspectionId", fresh.string("inspectionId")); addProperty("configurationDigest", fresh.string("configurationDigest"))
                addProperty("inlineDeclaration", "remove"); add("names", JsonArray())
            })
            assertTrue(result.toString(), result.array("conflicts").isEmpty())
            assertTrue(result.toString(), result.getAsJsonObject("validation").bool("typeChecked"))
            return result
        }
    }

    private fun fixture(action: (Fixture) -> Unit) {
        val prior = System.getProperty("openidea.workspace")
        val workspace = System.getProperty("opengoland.testNativeRefactorWorkspace") ?: System.getProperty("user.dir")
        System.setProperty("openidea.workspace", workspace)
        val root = Files.createDirectories(Path.of(requireNotNull(project.basePath))).toRealPath()
        assertTrue("The native process fixture must keep its cwd below Windows MAX_PATH: $root", root.toString().length < 260)
        println("Owned native fixture cwd UTF-16 length=${root.toString().length}: $root")
        val disposable = Disposer.newDisposable("native refactor integration roots")
        VfsRootAccess.allowRootAccess(disposable, root.toString())
        var editor: Editor? = null; var document: Document? = null
        try {
            Files.writeString(root.resolve("go.mod"), "module example.org/nativerefactor\n\ngo 1.24.0\n")
            val source = root.resolve("源 文件.go"); Files.writeString(source, DISK)
            Files.writeString(root.resolve("dependency.go"), "package fixture\nfunc Other()int{return 1}\n")
            val local = LocalFileSystem.getInstance(); val virtual = requireNotNull(local.refreshAndFindFileByNioFile(source))
            val dependency = requireNotNull(local.refreshAndFindFileByNioFile(root.resolve("dependency.go")))
            local.refreshAndFindFileByNioFile(root.resolve("go.mod"))
            val rootFile = requireNotNull(local.refreshAndFindFileByNioFile(root)); rootFile.children
            val file = read { requireNotNull(PsiManager.getInstance(project).findFile(virtual)) }
            val doc = read { requireNotNull(FileDocumentManager.getInstance().getDocument(virtual)) }; document = doc
            val settings = GoProjectSettings.get(project)
            settings.goExecutable = requireNotNull(GoToolchain.resolve("go", System.getProperty("opengoland.testGo") ?: System.getenv("OPENGOLAND_GO_TEST").orEmpty()))
            settings.environment = "GOWORK=off\nGOPROXY=off\nGOTOOLCHAIN=local\nGOTELEMETRY=off"
            edt {
                ModuleRootModificationUtil.addContentRoot(module, rootFile.path)
                editor = EditorFactory.getInstance().createEditor(doc, project)
                WriteCommandAction.runWriteCommandAction(project) { doc.setText(DIRTY) }
                PsiDocumentManager.getInstance(project).commitAllDocuments()
            }
            action(Fixture(root, source, doc, requireNotNull(editor), dependency, file))
        } finally {
            edt {
                editor?.let { if (!it.isDisposed) EditorFactory.getInstance().releaseEditor(it) }
                WriteCommandAction.runWriteCommandAction(project) { document?.let { it.setText(DISK); FileDocumentManager.getInstance().saveDocument(it) } }
            }
            project.getService(EngineClient::class.java).dispose()
            Disposer.dispose(disposable)
            if (prior == null) System.clearProperty("openidea.workspace") else System.setProperty("openidea.workspace", prior)
        }
    }
    private fun edt(action: () -> Unit) = EdtTestUtil.runInEdtAndWait<RuntimeException> { WriteIntentReadAction.run(Runnable(action)) }
    private fun <T> read(action: () -> T): T = ReadAction.compute<T, RuntimeException> { action() }
    companion object {
        private const val TITLE = "Extract Go Method"
        private const val DISK = "package fixture\n// 磁盘 😀\ntype T int\nfunc(t T)newMethod(){}\nfunc(t T)Keep()int{\n result:=int(t)+1\n println(result)\n return result\n}\n"
        private val DIRTY = DISK.replace("磁盘", "未保存")
        private const val RAW = "func(t T)Keep()int{\n result:=t.newMethod()\n return result\n}\n\nfunc(t T)newMethod()int{\n result:=int(t)+1\n println(result)\n return result\n}"
        private fun position(line: Int, character: Int) = JsonObject().apply { addProperty("line", line); addProperty("character", character) }
    }
}
