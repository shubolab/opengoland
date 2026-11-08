package dev.openidea.goide.generate.methods

import com.google.gson.JsonArray
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
import dev.openidea.goide.refactor.GoEditRequestSnapshot
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
class GoMethodGenerationIntegrationTest : HeavyPlatformTestCase() {
    override fun runInDispatchThread() = false
    override fun isCreateDirectoryBasedProject() = true
    override fun getProjectDirOrFile(isDirectoryBased: Boolean): Path =
        super.getProjectDirOrFile(true).resolve("接口 生成 😀")

    fun testRealTypedMethodsPreserveDirtySourceAndUseOneGlobalUndoRedo() = fixture { state ->
        val plan = state.plan()
        assertEquals(1, plan.getAsJsonObject("workspaceEdit").array("documentChanges").size())
        val applied = state.service.apply(GoSignatureCodec.workspaceEdit(plan), TITLE, preview = false,
            request = state.request, validation = state.guard(plan)).get(90, TimeUnit.SECONDS)
        assertTrue(applied.failureReason.orEmpty(), applied.isApplied)
        assertEquals("Applying must not save the user's source overlay", DISK_SOURCE, Files.readString(state.source))
        val generated = ReadAction.compute<String, RuntimeException> { state.document.text }
        assertTrue(generated, generated.contains("Read("))
        assertTrue(generated, generated.contains("Label string"))
        onEdt {
            assertTrue(FileDocumentManager.getInstance().isDocumentUnsaved(state.document))
            val undo = UndoManager.getInstance(project)
            assertTrue("A native global Undo must own the complete method/import edit", undo.isUndoAvailable(null))
            undo.undo(null)
            assertEquals(DIRTY_SOURCE, state.document.text)
            assertTrue(undo.isRedoAvailable(null))
            undo.redo(null)
            assertEquals(generated, state.document.text)
        }
        assertEquals(DISK_SOURCE, Files.readString(state.source))
    }

    fun testStaleTypedDependencyRejectsEveryMethodAndPreservesTheDirtyBuffer() = fixture { state ->
        val plan = state.plan()
        val guard = state.guard(plan)
        guard.validateBackground()
        Files.writeString(state.root.resolve("dependency.go"), "package fixture\nfunc Other(value int) int { return value }\n")
        val result = state.service.apply(GoSignatureCodec.workspaceEdit(plan), TITLE, preview = false,
            request = state.request, validation = guard).get(90, TimeUnit.SECONDS)
        assertFalse("A dependency change must reject the complete generated method plan", result.isApplied)
        assertTrue(result.failureReason.orEmpty(), result.failureReason?.contains("changed", ignoreCase = true) == true)
        state.assertDirtySourceUnchanged()
    }

    private inner class Fixture(
        val root: Path, val source: Path, val document: Document,
        val service: GoWorkspaceEditService, val request: GoEditRequestSnapshot,
        val configuration: List<String>, val wire: JsonObject,
    ) {
        private val engine get() = project.getService(EngineClient::class.java)
        private fun rpc(method: String, params: JsonObject): JsonObject = engine.request(method,
            params.deepCopy().apply { addProperty("operationId", UUID.randomUUID().toString()) }, timeoutSeconds = 90)
            .get(100, TimeUnit.SECONDS).asJsonObject

        fun plan(): JsonObject {
            val inspection = rpc("generate/methods/inspect", wire)
            assertTrue(inspection.toString(), inspection.array("conflicts").isEmpty())
            assertEquals("Read", inspection.array("methods").single().asJsonObject.string("name"))
            val plan = rpc("generate/methods/plan", wire.deepCopy().apply {
                addProperty("inspectionId", inspection.string("inspectionId"))
                addProperty("configurationDigest", inspection.string("configurationDigest"))
                addProperty("receiverId", inspection.getAsJsonObject("receiver").string("receiverId"))
                addProperty("receiverName", "b")
                addProperty("allMissing", true)
            })
            assertTrue(plan.toString(), plan.array("conflicts").isEmpty())
            assertTrue(plan.toString(), plan.getAsJsonObject("validation").bool("typeChecked"))
            assertTrue("The complete candidate must implement io.Reader", plan.bool("implemented"))
            val precondition = plan.array("preconditions").map { it.asJsonObject }.single { it.string("uri") == uri(source) }
            assertEquals(ReadAction.compute<Long, RuntimeException> { document.modificationStamp }, precondition.get("documentStamp").asLong)
            assertEquals(GoSignatureValidation.sha256(DIRTY_SOURCE.toByteArray()), precondition.string("textSha256"))
            return plan
        }

        fun guard(plan: JsonObject) = GoSignatureValidation(project, plan, configuration, operationName = TITLE)
        fun assertDirtySourceUnchanged() {
            assertEquals("Planning/applying/Undo must not save the source overlay", DISK_SOURCE, Files.readString(source))
            onEdt {
                assertEquals(DIRTY_SOURCE, document.text)
                assertTrue("The user's source document must remain unsaved", FileDocumentManager.getInstance().isDocumentUnsaved(document))
            }
        }
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
        assertTrue(root.toString(), root.toString().contains("接口 生成 😀"))
        val disposable = Disposer.newDisposable("native generated test roots")
        VfsRootAccess.allowRootAccess(disposable, root.toString())
        var document: Document? = null
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
            val request = service.captureRequest()
            val command = GoToolchain.command(project, "go", emptyList())
            val wire = JsonObject().apply {
                addProperty("root", root.toString()); addProperty("goExecutable", command.exePath)
                addProperty("receiverKind", "pointer"); addProperty("interfaceExpression", "io.Reader")
                add("environment", JsonObject().apply { command.environment.forEach { (key, value) -> addProperty(key, value) } })
                add("target", JsonObject().apply {
                    addProperty("uri", uri(source)); add("position", JsonObject().apply { addProperty("line", 2); addProperty("character", 8) })
                })
                add("overlays", JsonArray().apply { add(JsonObject().apply {
                    addProperty("uri", uri(source)); addProperty("text", DIRTY_SOURCE)
                    addProperty("documentStamp", ReadAction.compute<Long, RuntimeException> { doc.modificationStamp })
                }) })
            }
            action(Fixture(root, source, doc, service, request, GoToolchain.configurationKey(project), wire))
        } finally {
            try {
                // Restore only this fixture's dirty input before platform teardown.
                onEdt { WriteCommandAction.runWriteCommandAction(project) {
                    document?.let { doc ->
                        if (doc.text != DISK_SOURCE) doc.setText(DISK_SOURCE)
                        FileDocumentManager.getInstance().saveDocument(doc)
                    }
                } }
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
        private const val TITLE = "Implement Go Methods"
        private const val DISK_SOURCE = "package fixture\n// 源码 😀\ntype Buffer struct{}\n"
        private const val DIRTY_SOURCE = "package fixture\n// 未保存 中文 😀\ntype Buffer struct { Label string }\n"
    }
}
