package dev.openidea.goide.generate.tests

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.history.LocalHistory
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
class GoTestGenerationIntegrationTest : HeavyPlatformTestCase() {
    override fun runInDispatchThread() = false
    override fun isCreateDirectoryBasedProject() = true
    override fun getProjectDirOrFile(isDirectoryBased: Boolean): Path =
        tempDir.newPath("project").resolve("生成 测试 😀")

    fun testRealPlanCreatesBothFilesAndOneGlobalUndoRedoPreservesTheDirtySource() = fixture { state ->
        val plan = state.plan()
        val changes = plan.getAsJsonObject("workspaceEdit").array("documentChanges")
        assertEquals(2, changes.count { it.asJsonObject.string("kind") == "create" })
        assertEquals(2, changes.count { it.asJsonObject.has("textDocument") })
        assertTrue("The actual JSON must carry encoded Unicode/space resource URIs", changes.any {
            it.asJsonObject.string("uri").contains("%20")
        })
        val applied = state.service.apply(GoTestGenerationCodec.workspaceEdit(plan), TITLE, preview = false,
            request = state.request, validation = state.guard(plan)).get(90, TimeUnit.SECONDS)
        assertTrue(applied.failureReason.orEmpty(), applied.isApplied)
        state.assertDirtySourceUnchanged()
        val expected = state.generated.associateWith { path ->
            assertTrue("The real CreateFile/TextEdit transaction must write $path", Files.isRegularFile(path))
            Files.readString(path).also { text ->
                assertTrue(text, text.contains("func Test"))
                assertTrue(text, text.contains("TODO: Add test cases."))
            }
        }
        assertTrue(expected.getValue(state.generated.first()).contains("value string"))
        onEdt {
            val undo = UndoManager.getInstance(project)
            assertTrue("One native global Undo must own the whole two-file generation", undo.isUndoAvailable(null))
            undo.undo(null)
            state.generated.forEach { path -> assertNull(LocalFileSystem.getInstance().findFileByNioFile(path)) }
            assertEquals(DIRTY_SOURCE, state.document.text)
            assertTrue(undo.isRedoAvailable(null))
        }
        state.generated.forEach { assertFalse("Undo must remove the created file", Files.exists(it)) }
        state.assertDirtySourceUnchanged()
        onEdt { UndoManager.getInstance(project).redo(null) }
        for ((path, text) in expected) {
            assertEquals("Redo must restore the complete generated text", text, Files.readString(path))
            val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!
            val document = ReadAction.compute<Document, RuntimeException> { FileDocumentManager.getInstance().getDocument(file)!! }
            assertEquals(text, ReadAction.compute<String, RuntimeException> { document.text })
        }
        state.assertDirtySourceUnchanged()
    }

    fun testDependencyChangedAfterRealPlanningRejectsBothFilesWithoutPartialApplication() = fixture { state ->
        val plan = state.plan()
        val dependency = state.root.resolve("dependency.go")
        val guard = state.guard(plan)
        guard.validateBackground()
        Files.writeString(dependency, "package fixture\nfunc Other(value int) int { return value }\n")
        val result = state.service.apply(GoTestGenerationCodec.workspaceEdit(plan), TITLE, preview = false,
            request = state.request, validation = guard).get(90, TimeUnit.SECONDS)
        assertFalse("A real stale dependency must reject the complete plan", result.isApplied)
        assertTrue(result.failureReason.orEmpty(), result.failureReason?.contains("changed", ignoreCase = true) == true)
        for (path in state.generated) {
            assertFalse("No file may be partially created after guard rejection: $path", Files.exists(path))
            assertNull(LocalFileSystem.getInstance().findFileByNioFile(path))
        }
        assertTrue(Files.readString(dependency).contains("value int"))
        state.assertDirtySourceUnchanged()
    }

    private inner class Fixture(
        val root: Path, val source: Path, val document: Document,
        val service: GoWorkspaceEditService, val request: GoEditRequestSnapshot,
        val configuration: List<String>, val wire: JsonObject,
    ) {
        val generated = listOf(root.resolve("源 文件_test.go"), root.resolve("dependency_test.go"))
        private val engine get() = project.getService(EngineClient::class.java)
        private fun rpc(method: String, params: JsonObject): JsonObject = engine.request(method,
            params.deepCopy().apply { addProperty("operationId", UUID.randomUUID().toString()) }, timeoutSeconds = 90)
            .get(100, TimeUnit.SECONDS).asJsonObject

        fun plan(): JsonObject {
            val inspection = rpc("generate/tests/inspect", wire)
            assertTrue(inspection.toString(), inspection.array("conflicts").isEmpty())
            assertEquals(2, inspection.array("symbols").size())
            val plan = rpc("generate/tests/plan", wire.deepCopy().apply {
                addProperty("inspectionId", inspection.string("inspectionId"))
                addProperty("configurationDigest", inspection.string("configurationDigest"))
                add("selections", JsonArray().apply { inspection.array("symbols").forEach { entry ->
                    add(JsonObject().apply { addProperty("symbolId", entry.asJsonObject.string("symbolId")) })
                } })
            })
            assertTrue(plan.toString(), plan.array("conflicts").isEmpty())
            assertTrue(plan.toString(), plan.getAsJsonObject("validation").bool("typeChecked"))
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
        assertTrue("The native process fixture must keep its cwd below Windows MAX_PATH: $root", root.toString().length < 260)
        println("Owned native fixture cwd UTF-16 length=${root.toString().length}: $root")
        assertTrue(root.toString(), root.toString().contains("生成 测试 😀"))
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
            settings.goExecutable = requireNotNull(GoToolchain.resolve("go", System.getProperty("opengoland.testGo") ?: System.getenv("OPENGOLAND_GO_TEST").orEmpty()))
            settings.environment = "GOWORK=off\nGOPROXY=off\nGOTOOLCHAIN=local\nGOTELEMETRY=off"
            val service = GoWorkspaceEditService.get(project)
            onEdt {
                if (ProjectRootManager.getInstance(project).contentRoots.none { it == rootFile })
                    ModuleRootModificationUtil.addContentRoot(module, rootFile.path)
                assertTrue("Native Local History must cover generated resource Undo", LocalHistory.getInstance().isEnabled)
                assertTrue(LocalHistory.getInstance().isUnderControl(rootFile))
                WriteCommandAction.runWriteCommandAction(project) { doc.setText(DIRTY_SOURCE) }
                PsiDocumentManager.getInstance(project).commitAllDocuments()
            }
            val request = service.captureRequest()
            val command = GoToolchain.command(project, "go", emptyList())
            val wire = JsonObject().apply {
                addProperty("root", root.toString()); addProperty("goExecutable", command.exePath)
                addProperty("scope", "package"); addProperty("packageMode", "same")
                add("environment", JsonObject().apply { command.environment.forEach { (key, value) -> addProperty(key, value) } })
                add("target", JsonObject().apply {
                    addProperty("uri", uri(source)); add("position", JsonObject().apply { addProperty("line", 2); addProperty("character", 6) })
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
        private const val TITLE = "Generate Go Tests"
        private const val DISK_SOURCE = "package fixture\n// 源码 😀\nfunc Echo(value int) int { return value }\n"
        private const val DIRTY_SOURCE = "package fixture\n// 未保存 中文 😀\nfunc Echo(value string) string { return value }\n"
    }
}
