package dev.openidea.goide.generate.tags

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.redhat.devtools.lsp4ij.LSPIJUtils
import dev.openidea.goide.engine.EngineClient
import dev.openidea.goide.generate.tests.generationToolchainParameters
import dev.openidea.goide.lang.GoLanguage
import dev.openidea.goide.lsp.GoDocumentSync
import dev.openidea.goide.refactor.GoEditRequestSnapshot
import dev.openidea.goide.refactor.GoWorkspaceEditService
import dev.openidea.goide.refactor.signature.GoSignatureCodec
import dev.openidea.goide.refactor.signature.GoSignatureValidation
import dev.openidea.goide.refactor.signature.array
import dev.openidea.goide.refactor.signature.bool
import dev.openidea.goide.refactor.signature.string
import dev.openidea.goide.semantic.GoSemanticOperations
import dev.openidea.goide.settings.GoToolchain
import java.net.URI
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** The typed planner returns edits; the native transaction alone changes documents. */
@Service(Service.Level.PROJECT)
class GoStructTagsService(private val project: Project) : Disposable {
    private data class Observed(val file: VirtualFile, val document: Document, val stamp: Long)
    private data class Snapshot(
        val wire: JsonObject, val request: GoEditRequestSnapshot,
        val toolchain: GoToolchain.Settings, val documents: List<Observed>,
    )
    private val active = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var disposed = false
    private val work get() = GoSemanticOperations.get(project)
    private val engine get() = project.getService(EngineClient::class.java)

    fun invoke(file: PsiFile, editor: Editor) {
        if (file.language != GoLanguage || editor.isDisposed) return
        val snapshot = capture(file, editor)
        work.background(TITLE) { indicator ->
            checkCurrent(snapshot)
            val prepared = environment(snapshot, indicator)
            val inspection = operation("generate/tags/inspect", prepared, indicator, snapshot.toolchain)
            work.later { checkCurrent(snapshot); showEditor(snapshot.copy(wire = prepared), inspection) }
        }
    }

    private fun capture(file: PsiFile, editor: Editor): Snapshot = ReadAction.compute<Snapshot, RuntimeException> {
        val toolchain = GoToolchain.capture(project)
        val manager = FileDocumentManager.getInstance()
        val virtual = file.virtualFile ?: error("Struct Tags requires a local Go source file")
        require(virtual.isInLocalFileSystem && virtual.isWritable) { "Struct Tags requires writable Go source" }
        val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: error("No Go source document")
        require(editor.document === document && !editor.isViewer) { "Choose an editable Go source document" }
        val position = LSPIJUtils.toPosition(editor.caretModel.offset, document)
        val index = ProjectFileIndex.getInstance(project)
        val open = EditorFactory.getInstance().allEditors.filter { it.project == project }.map { it.document }
        val unsaved = manager.unsavedDocuments.filter { candidate -> manager.getFile(candidate)?.let(index::isInContent) == true }
        val overlays = JsonArray()
        val observed = mutableListOf<Observed>()
        for (candidate in (open + unsaved + document).distinct()) {
            val source = manager.getFile(candidate)?.takeIf { it.isValid && it.isInLocalFileSystem } ?: continue
            if (source.extension != "go" && source.name !in setOf("go.mod", "go.work", "go.sum", "go.work.sum")) continue
            val uri = GoDocumentSync.fileUri(Path.of(source.path))
            val text = candidate.text
            observed += Observed(source, candidate, candidate.modificationStamp)
            overlays.add(JsonObject().apply {
                addProperty("uri", uri); addProperty("text", text); addProperty("documentStamp", candidate.modificationStamp)
                addProperty("dirty", manager.isDocumentUnsaved(candidate))
                GoDocumentSync.get(project).snapshot(uri)?.takeIf { it.text == text }?.let { synced ->
                    add("lsp", JsonObject().apply { addProperty("session", synced.session); addProperty("version", synced.version) })
                }
            })
        }
        Snapshot(JsonObject().apply {
            addProperty("root", project.basePath ?: error("Open a Go project first"))
            add("target", JsonObject().apply {
                addProperty("uri", GoDocumentSync.fileUri(Path.of(virtual.path)))
                add("position", JsonObject().apply { addProperty("line", position.line); addProperty("character", position.character) })
            })
            add("overlays", overlays)
        }, GoWorkspaceEditService.get(project).captureRequest(), toolchain, observed)
    }

    private fun environment(snapshot: Snapshot, indicator: ProgressIndicator): JsonObject {
        val configuration = generationToolchainParameters(project, snapshot.toolchain)
        val info = work.await(engine.request("project/info", JsonObject(), snapshot.toolchain), indicator).asJsonObject
        val roots = mutableListOf(Path.of(project.basePath!!).toAbsolutePath().normalize())
        info.array("modules").forEach { entry ->
            val module = entry.asJsonObject
            if (module.bool("Main") && module.string("Dir").isNotBlank()) roots.add(Path.of(module.string("Dir")).toAbsolutePath().normalize())
        }
        return snapshot.wire.deepCopy().apply {
            configuration.entrySet().forEach { (key, value) -> add(key, value) }
            add("overlays", JsonArray().apply {
                snapshot.wire.array("overlays").forEach { entry ->
                    val overlay = entry.asJsonObject
                    val path = Path.of(URI(overlay.string("uri"))).toAbsolutePath().normalize()
                    if (roots.any(path::startsWith) || overlay.bool("dirty") || path.fileName.toString() in setOf("go.mod", "go.work", "go.sum", "go.work.sum")) add(overlay)
                }
            })
        }
    }

    private fun showEditor(snapshot: Snapshot, inspection: JsonObject, draft: JsonObject? = null) {
        checkCurrent(snapshot)
        val dialog = GoStructTagsDialog(project, inspection, draft)
        if (!dialog.showAndGet()) return
        val selection = dialog.selection()
        val savedDraft = dialog.draft()
        work.background(TITLE) { indicator ->
            checkCurrent(snapshot)
            val request = snapshot.wire.deepCopy().apply { selection.entrySet().forEach { (key, value) -> add(key, value.deepCopy()) } }
            val plan = operation("generate/tags/plan", request, indicator, snapshot.toolchain)
            if (GoSignatureCodec.conflicts(plan).isNotEmpty()) {
                work.later {
                    Messages.showErrorDialog(project, GoSignatureCodec.conflicts(plan).joinToString("\n\n"), "Struct Tags Conflicts")
                    showEditor(snapshot, inspection, savedDraft)
                }
                return@background
            }
            require(plan.getAsJsonObject("validation")?.bool("typeChecked") == true) { "The struct tag changes did not pass Go type checking" }
            val response = work.await(GoWorkspaceEditService.get(project).apply(
                GoSignatureCodec.workspaceEdit(plan), TITLE, preview = true, request = snapshot.request,
                validation = GoSignatureValidation(project, plan, snapshot.toolchain.key, operationName = TITLE),
                usages = GoSignatureCodec.usages(plan)), indicator)
            if (!response.isApplied && response.failureReason?.contains("cancel", ignoreCase = true) != true)
                error(response.failureReason ?: "The struct tag changes could not be applied")
        }
    }

    private fun operation(method: String, parameters: JsonObject, indicator: ProgressIndicator, settings: GoToolchain.Settings): JsonObject {
        val id = UUID.randomUUID().toString()
        val request = parameters.deepCopy().apply { addProperty("operationId", id) }
        active += id
        var succeeded = false
        return try {
            check(!disposed && !project.isDisposed) { "The Go project is closed" }
            work.await(engine.request(method, request, settings, timeoutSeconds = 180), indicator).asJsonObject.also { succeeded = true }
        } finally { active -= id; if (!succeeded) engine.cancelOperation(id) }
    }

    private fun checkCurrent(snapshot: Snapshot) {
        check(!disposed && !project.isDisposed) { "The Go project is closed" }
        require(GoToolchain.configurationKey(project) == snapshot.toolchain.key) { "Go build settings changed. Run Struct Tags again." }
        ReadAction.run<RuntimeException> {
            val manager = FileDocumentManager.getInstance()
            snapshot.documents.forEach { observed ->
                val current = manager.getCachedDocument(observed.file)
                require(observed.file.isValid && current === observed.document && current.modificationStamp == observed.stamp) {
                    "An analyzed editor changed or closed. Run Struct Tags again."
                }
            }
        }
    }

    override fun dispose() { disposed = true; active.toList().forEach(engine::cancelOperation); active.clear() }
    companion object {
        private const val TITLE = "Edit Go Struct Tags"
        fun get(project: Project): GoStructTagsService = project.getService(GoStructTagsService::class.java)
    }
}
