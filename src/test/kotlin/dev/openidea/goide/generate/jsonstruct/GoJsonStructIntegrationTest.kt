package dev.openidea.goide.generate.jsonstruct

import com.google.gson.JsonArray
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.progress.EmptyProgressIndicator
import dev.openidea.goide.generate.GoGenerationSnapshot
import com.google.gson.JsonObject
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.EdtTestUtil
import com.intellij.testFramework.HeavyPlatformTestCase
import dev.openidea.goide.engine.EngineClient
import dev.openidea.goide.lsp.GoDocumentSync
import dev.openidea.goide.refactor.GoWorkspaceEditService
import dev.openidea.goide.refactor.signature.GoSignatureValidation
import dev.openidea.goide.refactor.signature.GoSignatureCodec
import dev.openidea.goide.refactor.signature.array
import dev.openidea.goide.refactor.signature.bool
import dev.openidea.goide.refactor.signature.string
import dev.openidea.goide.settings.GoProjectSettings
import dev.openidea.goide.settings.GoToolchain
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Real engine RPC, typed planner, native resource transaction and platform global Undo providers. */
class GoJsonStructIntegrationTest : HeavyPlatformTestCase() {
    override fun runInDispatchThread() = false
    override fun isCreateDirectoryBasedProject() = true
    override fun getProjectDirOrFile(isDirectoryBased: Boolean): Path =
        super.getProjectDirOrFile(true).resolve("JSON 类型 😀")

    fun testJsonTypesAndFieldsReuseImportAliasesDirtySourceAndSeparateGlobalUndo() = fixture { state ->
        val typePlan = state.plan("type", JSON_TYPE)
        state.apply(typePlan)
        val typed = state.text()
        assertTrue(typed, typed.contains("type Payload struct"))
        assertTrue("Number policy must reuse the real file import alias", typed.contains("jsoncodec.Number"))
        assertTrue(typed, typed.contains("未保存 中文 😀"))
        state.assertDiskUnchanged()
        onEdt {
            val undo = UndoManager.getInstance(project)
            undo.undo(null)
            assertEquals(DIRTY_SOURCE, state.document.text)
            undo.redo(null)
            assertEquals(typed, state.document.text)
        }
        val fieldsPlan = state.plan("fields", JSON_FIELDS)
        state.apply(fieldsPlan)
        val generated = state.text()
        assertTrue(generated, generated.contains("json:\"trace_id\""))
        assertTrue(generated, generated.contains("json:\"count\""))
        assertTrue(generated, generated.contains("json:\"existing,omitempty\""))
        assertTrue(generated, generated.contains("type Payload struct"))
        onEdt {
            val undo = UndoManager.getInstance(project)
            undo.undo(null)
            assertEquals("Undoing field additions must retain the earlier generated type", typed, state.document.text)
            undo.redo(null)
            assertEquals(generated, state.document.text)
        }
        state.assertDiskUnchanged()
    }

    fun testStaleTypedDependencyRejectsTheCompleteJsonTypeEdit() = fixture { state ->
        val plan = state.plan("type", JSON_TYPE)
        val guard = state.guard(plan)
        guard.validateBackground()
        Files.writeString(state.root.resolve("dependency.go"), "package fixture\nfunc Other(value int) int { return value }\n")
        val result = state.service.apply(GoSignatureCodec.workspaceEdit(plan), TITLE, preview = false,
            request = state.snapshot.request, validation = guard).get(90, TimeUnit.SECONDS)
        assertFalse(result.failureReason.orEmpty(), result.isApplied)
        assertTrue(result.failureReason.orEmpty(), result.failureReason?.contains("changed", ignoreCase = true) == true)
        assertEquals(DIRTY_SOURCE, state.text())
        state.assertDiskUnchanged()
    }

    private inner class Fixture(
        val root: Path, val source: Path, val document: Document, val editor: Editor,
        val service: GoWorkspaceEditService,
    ) {
        lateinit var snapshot: GoGenerationSnapshot
        private val engine get() = project.getService(EngineClient::class.java)
        fun capture(): GoGenerationSnapshot {
            lateinit var captured: GoGenerationSnapshot
            onEdt {
                editor.caretModel.moveToOffset(document.text.indexOf("Envelope"))
                val psi = requireNotNull(PsiDocumentManager.getInstance(project).getPsiFile(document))
                captured = GoGenerationSnapshot.capture(psi, editor, TITLE)
            }
            return captured
        }
        private fun rpc(method: String, params: JsonObject): JsonObject = engine.request(method,
            params.deepCopy().apply { addProperty("operationId", UUID.randomUUID().toString()) }, snapshot.toolchain, timeoutSeconds = 90)
            .get(100, TimeUnit.SECONDS).asJsonObject

        fun plan(mode: String, json: String): JsonObject {
            snapshot = capture().prepare(EmptyProgressIndicator())
            val wire = snapshot.wire.deepCopy().apply {
                addProperty("mode", mode); addProperty("jsonText", json); addProperty("typeName", "Payload")
                addProperty("numericPolicy", "number")
            }
            val inspection = rpc("generate/json/inspect", wire)
            assertTrue(inspection.toString(), inspection.array("conflicts").isEmpty())
            val plan = rpc("generate/json/plan", wire.deepCopy().apply {
                addProperty("inspectionId", inspection.string("inspectionId"))
                addProperty("configurationDigest", inspection.string("configurationDigest"))
                addProperty("structId", inspection.string("structId"))
                if (mode == "fields") add("fieldIds", JsonArray().apply {
                    inspection.array("fields").map { it.asJsonObject }.filter { it.bool("editable") }.forEach { add(it.string("fieldId")) }
                })
            })
            assertTrue(plan.toString(), plan.array("conflicts").isEmpty())
            assertTrue(plan.toString(), plan.getAsJsonObject("validation").bool("typeChecked"))
            val precondition = plan.array("preconditions").map { it.asJsonObject }.single { it.string("uri") == uri(source) }
            assertEquals(ReadAction.compute<Long, RuntimeException> { document.modificationStamp }, precondition.get("documentStamp").asLong)
            assertEquals(GoSignatureValidation.sha256(text().toByteArray()), precondition.string("textSha256"))
            return plan
        }
        fun apply(plan: JsonObject) {
            val result = service.apply(GoSignatureCodec.workspaceEdit(plan), TITLE, preview = false,
                request = snapshot.request, validation = guard(plan)).get(90, TimeUnit.SECONDS)
            assertTrue(result.failureReason.orEmpty(), result.isApplied)
        }
        fun guard(plan: JsonObject) = GoSignatureValidation(project, plan, snapshot.toolchain.key, operationName = TITLE)
        fun text(): String = ReadAction.compute<String, RuntimeException> { document.text }
        fun assertDiskUnchanged() = assertEquals("Generation must not save source overlays", DISK_SOURCE, Files.readString(source))
    }

    private fun fixture(action: (Fixture) -> Unit) {
        val previousWorkspace = System.getProperty("openidea.workspace")
        val repository = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
        val filename = if (System.getProperty("os.name").startsWith("Windows")) "opengoland-engine.exe" else "opengoland-engine"
        assertTrue("This integration requires the actual buildEngine artifact", Files.isExecutable(repository.resolve("engine/bin/$filename")))
        System.setProperty("openidea.workspace", repository.toString())
        // The heavy fixture can open an unsaved directory project before its
        // storage directory exists. Materialize it on this test worker first.
        val root = Files.createDirectories(Path.of(requireNotNull(project.basePath))).toRealPath()
        assertTrue(root.toString(), root.toString().contains("JSON 类型 😀"))
        val disposable = Disposer.newDisposable("native generated test roots")
        VfsRootAccess.allowRootAccess(disposable, root.toString())
        var document: Document? = null
        var editor: Editor? = null
        try {
            val source = root.resolve("源 文件.go")
            Files.writeString(root.resolve("go.mod"), "module example.org/nativegeneration\n\ngo 1.24.0\n")
            Files.writeString(source, DISK_SOURCE)
            Files.writeString(root.resolve("dependency.go"), "package fixture\nfunc Other(value bool) bool { return value }\n")
            val local = LocalFileSystem.getInstance()
            val rootFile = local.refreshAndFindFileByNioFile(root)!!
            val virtual = local.refreshAndFindFileByNioFile(source)!!
            local.refreshAndFindFileByNioFile(root.resolve("dependency.go"))!!
            local.refreshAndFindFileByNioFile(root.resolve("go.mod"))!!
            rootFile.children
            val doc = ReadAction.compute<Document, RuntimeException> { FileDocumentManager.getInstance().getDocument(virtual)!! }
            document = doc
            val settings = GoProjectSettings.get(project)
            settings.goExecutable = requireNotNull(GoToolchain.resolve("go"))
            settings.environment = "GOWORK=off\nGOPROXY=off\nGOTOOLCHAIN=local\nGOTELEMETRY=off"
            val service = GoWorkspaceEditService.get(project)
            onEdt {
                if (ProjectRootManager.getInstance(project).contentRoots.none { it == rootFile })
                    ModuleRootModificationUtil.addContentRoot(module, rootFile.path)
                WriteCommandAction.runWriteCommandAction(project) { doc.setText(DIRTY_SOURCE) }
                PsiDocumentManager.getInstance(project).commitAllDocuments()
            }
            lateinit var sourceEditor: Editor
            onEdt {
                sourceEditor = EditorFactory.getInstance().createEditor(doc, project)
                sourceEditor.caretModel.moveToOffset(DIRTY_SOURCE.indexOf("Envelope"))
                editor = sourceEditor
            }
            action(Fixture(root, source, doc, sourceEditor, service))
        } finally {
            try {
                // Restore only this fixture's dirty input before platform teardown.
                onEdt {
                    editor?.let { EditorFactory.getInstance().releaseEditor(it) }
                    WriteCommandAction.runWriteCommandAction(project) {
                        document?.let { doc ->
                            if (doc.text != DISK_SOURCE) doc.setText(DISK_SOURCE)
                            FileDocumentManager.getInstance().saveDocument(doc)
                        }
                    }
                }
            } finally {
                project.getService(EngineClient::class.java).dispose()
                Disposer.dispose(disposable)
                if (previousWorkspace == null) System.clearProperty("openidea.workspace") else System.setProperty("openidea.workspace", previousWorkspace)
            }
        }
    }

    private fun onEdt(action: () -> Unit) = EdtTestUtil.runInEdtAndWait<RuntimeException> { WriteIntentReadAction.run(Runnable(action)) }
    private fun uri(path: Path) = GoDocumentSync.fileUri(path)

    companion object {
        private const val TITLE = "Generate Go from JSON"
        private val DISK_SOURCE = """
            package fixture
            import jsoncodec "encoding/json"
            var _ = jsoncodec.Valid
            // 源码 😀
            type Envelope struct { Existing string `json:"existing,omitempty"` }

        """.trimIndent()
        private val DIRTY_SOURCE = DISK_SOURCE.replace("// 源码 😀", "// 未保存 中文 😀")
        private const val JSON_TYPE = """{"id":9007199254740993,"label":"中文😀","items":[{"active":true},{"active":false}],"empty":null}"""
        private const val JSON_FIELDS = """{"trace_id":"中文😀","count":3,"extra":{"enabled":true}}"""
    }
}
