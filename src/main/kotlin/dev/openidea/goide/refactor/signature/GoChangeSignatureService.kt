package dev.openidea.goide.refactor.signature

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiFile
import com.redhat.devtools.lsp4ij.LSPIJUtils
import dev.openidea.goide.engine.EngineClient
import dev.openidea.goide.lsp.GoDocumentSync
import dev.openidea.goide.refactor.GoEditRequestSnapshot
import dev.openidea.goide.refactor.GoWorkspaceEditService
import dev.openidea.goide.semantic.GoSemanticOperations
import dev.openidea.goide.settings.GoProjectSettings
import dev.openidea.goide.settings.GoToolchain
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.PROJECT)
class GoChangeSignatureService(private val project: Project) : Disposable {
    private data class Snapshot(val wire: JsonObject, val request: GoEditRequestSnapshot, val configuration: List<String>)
    private val active = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var disposed = false
    private val work get() = GoSemanticOperations.get(project)
    private val engine = project.getService(EngineClient::class.java)

    fun invoke(file: PsiFile, editor: Editor) {
        val snapshot = capture(file, editor)
        work.background("Analyze Go Signature") { indicator ->
            checkCurrent(snapshot)
            val request = environment(snapshot, indicator)
            val inspected = operation("refactor/signature/inspect", request, indicator)
            val conflicts = GoSignatureCodec.conflicts(inspected)
            work.later {
                if (conflicts.isNotEmpty()) showConflicts(conflicts)
                else showEditor(GoSignatureInspection(inspected), snapshot.copy(wire = request))
            }
        }
    }

    private fun capture(file: PsiFile, editor: Editor): Snapshot = ReadAction.compute<Snapshot, RuntimeException> {
        val manager = FileDocumentManager.getInstance()
        val virtual = file.virtualFile ?: error("Change Signature requires a local Go file")
        require(virtual.isInLocalFileSystem && virtual.isWritable) { "Change Signature requires a writable Go source file" }
        val uri = GoDocumentSync.fileUri(Path.of(virtual.path))
        val position = LSPIJUtils.toPosition(editor.caretModel.offset, editor.document)
        val sync = GoDocumentSync.get(project)
        val documents = (EditorFactory.getInstance().allEditors.map { it.document } + manager.unsavedDocuments + editor.document).distinct()
        val overlays = JsonArray()
        for (document in documents) {
            val source = manager.getFile(document)?.takeIf { it.isValid && it.isInLocalFileSystem } ?: continue
            if (source.extension != "go" && source.name !in setOf("go.mod", "go.work", "go.sum", "go.work.sum")) continue
            val sourceUri = GoDocumentSync.fileUri(Path.of(source.path))
            val text = document.text
            overlays.add(JsonObject().apply {
                addProperty("uri", sourceUri); addProperty("text", text); addProperty("documentStamp", document.modificationStamp)
                addProperty("dirty", manager.isDocumentUnsaved(document))
                sync.snapshot(sourceUri)?.takeIf { it.text == text }?.let { synchronized -> add("lsp", JsonObject().apply {
                    addProperty("session", synchronized.session); addProperty("version", synchronized.version)
                }) }
            })
        }
        Snapshot(JsonObject().apply {
            addProperty("root", project.basePath ?: error("Open a Go project first"))
            add("target", JsonObject().apply { addProperty("uri", uri); add("position", JsonObject().apply {
                addProperty("line", position.line); addProperty("character", position.character)
            }) })
            add("overlays", overlays)
        }, GoWorkspaceEditService.get(project).captureRequest(), GoToolchain.configurationKey(project))
    }

    /** Resolve toolchain and workspace modules on the background worker, never in action availability. */
    private fun environment(snapshot: Snapshot, indicator: ProgressIndicator): JsonObject {
        val command = GoToolchain.command(project, "go", emptyList())
        val info = work.await(engine.request("project/info"), indicator).asJsonObject
        val roots = mutableListOf(Path.of(project.basePath!!).toAbsolutePath().normalize())
        info.array("modules").forEach { entry ->
            val module = entry.asJsonObject
            if (module.bool("Main") && module.string("Dir").isNotBlank()) roots.add(Path.of(module.string("Dir")).toAbsolutePath().normalize())
        }
        return snapshot.wire.deepCopy().apply {
            addProperty("goExecutable", command.exePath)
            add("environment", JsonObject().apply { command.environment.forEach { (key, value) -> addProperty(key, value) } })
            add("buildTags", JsonArray().apply { GoToolchain.buildTags(GoProjectSettings.get(project).buildTags).forEach(::add) })
            val filtered = JsonArray()
            array("overlays").forEach { overlay ->
                val path = Path.of(java.net.URI(overlay.asJsonObject.string("uri"))).toAbsolutePath().normalize()
                // A dirty go.work can introduce new external main modules. Let the
                // typed loader classify those unsaved inputs against that new graph.
                val value = overlay.asJsonObject
                if (roots.any(path::startsWith) || value.bool("dirty") || path.fileName.toString() in setOf("go.mod", "go.work", "go.sum", "go.work.sum")) filtered.add(overlay)
            }
            add("overlays", filtered)
        }
    }

    private fun showEditor(inspection: GoSignatureInspection, snapshot: Snapshot, draft: JsonObject? = null) {
        checkCurrent(snapshot)
        val dialog = GoChangeSignatureDialog(project, inspection, draft)
        if (!dialog.showAndGet()) return
        val change = dialog.change()
        work.background("Plan Go Signature Changes") { indicator ->
            checkCurrent(snapshot)
            val request = snapshot.wire.deepCopy().apply {
                addProperty("symbolId", inspection.symbolId); addProperty("configurationDigest", inspection.configurationDigest); add("change", change)
            }
            val plan = operation("refactor/signature/plan", request, indicator)
            val conflicts = GoSignatureCodec.conflicts(plan)
            if (conflicts.isNotEmpty()) {
                work.later { showConflicts(conflicts); showEditor(inspection, snapshot, change) }
                return@background
            }
            require(plan.getAsJsonObject("validation")?.bool("typeChecked") == true) { "The complete signature plan did not pass Go type checking" }
            val validation = GoSignatureValidation(project, plan, snapshot.configuration)
            val response = work.await(GoWorkspaceEditService.get(project).apply(GoSignatureCodec.workspaceEdit(plan), "Change Go Signature", preview = true,
                request = snapshot.request, validation = validation, usages = GoSignatureCodec.usages(plan)), indicator)
            if (!response.isApplied && response.failureReason?.contains("cancel", ignoreCase = true) != true)
                error(response.failureReason ?: "The signature edit could not be applied")
        }
    }

    private fun operation(method: String, parameters: JsonObject, indicator: ProgressIndicator): JsonObject {
        val id = UUID.randomUUID().toString()
        val request = parameters.deepCopy().apply { addProperty("operationId", id) }
        active += id
        var succeeded = false
        return try {
            work.await(engine.request(method, request, timeoutSeconds = 180), indicator).asJsonObject.also { succeeded = true }
        } finally { active -= id; if (!succeeded) engine.cancelOperation(id) }
    }

    private fun checkCurrent(snapshot: Snapshot) {
        check(!disposed && !project.isDisposed) { "The Go project is closed" }
        require(GoToolchain.configurationKey(project) == snapshot.configuration) { "Go build settings changed. Run Change Signature again." }
        ReadAction.run<RuntimeException> {
            val manager = FileDocumentManager.getInstance()
            val current = (EditorFactory.getInstance().allEditors.map { it.document } + manager.unsavedDocuments).associateBy {
                manager.getFile(it)?.let { source -> GoDocumentSync.fileUri(Path.of(source.path)) }
            }
            snapshot.wire.array("overlays").forEach { entry ->
                val value = entry.asJsonObject
                val document = current[value.string("uri")]
                require(document != null && document.modificationStamp == value.get("documentStamp").asLong) { "An analyzed editor changed or closed. Run Change Signature again." }
            }
        }
    }
    private fun showConflicts(conflicts: List<String>) = Messages.showErrorDialog(project, conflicts.joinToString("\n\n"), "Change Signature Conflicts")
    override fun dispose() { disposed = true; active.toList().forEach(engine::cancelOperation); active.clear() }
}
