package dev.openidea.goide.generate.tests

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.redhat.devtools.lsp4ij.LSPIJUtils
import dev.openidea.goide.engine.EngineClient
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
import java.util.concurrent.atomic.AtomicReference

/** Typed generation only returns edits. Native preview and one global Undo own every filesystem change. */
@Service(Service.Level.PROJECT)
class GoGenerateTestsService(private val project: Project) : Disposable {
    private data class Observed(val file: VirtualFile, val document: Document, val stamp: Long)
    private data class Snapshot(val wire: JsonObject, val request: GoEditRequestSnapshot, val toolchain: GoToolchain.Settings, val documents: List<Observed>) {
        val configuration: List<String> get() = toolchain.key
    }
    private val active = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var disposed = false
    private val work get() = GoSemanticOperations.get(project)
    private val engine get() = project.getService(EngineClient::class.java)
    private inner class InspectionOwner {
        private var closed = false
        private val operations = mutableSetOf<String>()
        @Synchronized fun add(id: String) {
            if (closed) throw ProcessCanceledException()
            operations += id
        }
        @Synchronized fun remove(id: String) { operations -= id }
        fun close() {
            val cancelled = synchronized(this) { closed = true; operations.toList() }
            cancelled.forEach(engine::cancelOperation)
        }
    }

    fun invoke(file: PsiFile, editor: Editor?, initialScope: String = "function", initialPackageMode: String = "same") {
        if (file.language != GoLanguage) return
        val snapshot = capture(file, editor)
        work.background(TITLE) { indicator ->
            checkCurrent(snapshot)
            val prepared = environment(snapshot, indicator)
            val inspection = inspect(prepared, initialScope, initialPackageMode, indicator, snapshot.toolchain)
            work.later {
                checkCurrent(snapshot)
                showEditor(snapshot.copy(wire = prepared), inspection)
            }
        }
    }

    private fun capture(file: PsiFile, editor: Editor?): Snapshot = ReadAction.compute<Snapshot, RuntimeException> {
        val toolchain = GoToolchain.capture(project)
        val manager = FileDocumentManager.getInstance()
        val virtual = file.virtualFile ?: error("Generate Tests requires a local Go source file")
        require(virtual.isInLocalFileSystem && virtual.isWritable) { "Generate Tests requires writable Go source" }
        val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: error("No Go source document")
        val sourceEditor = editor?.takeIf { !it.isDisposed && it.document === document }
        val position = LSPIJUtils.toPosition(sourceEditor?.caretModel?.offset ?: 0, document)
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

    private fun inspect(request: JsonObject, scope: String, mode: String, indicator: ProgressIndicator, settings: GoToolchain.Settings, owner: InspectionOwner? = null) =
        operation("generate/tests/inspect", request.deepCopy().apply { addProperty("scope", scope); addProperty("packageMode", mode) }, indicator, settings, owner)

    private fun showEditor(snapshot: Snapshot, initialInspection: JsonObject, draft: JsonObject? = null) {
        checkCurrent(snapshot)
        val inspections = AtomicReference<InspectionOwner?>()
        val dialog = GoGenerateTestsDialog(project, initialInspection, draft) { scope, mode, success, failure ->
            val owner = InspectionOwner()
            inspections.getAndSet(owner)?.close()
            work.background("Analyze Go Test Targets") { indicator ->
                try {
                    checkCurrent(snapshot)
                    val response = inspect(snapshot.wire, scope, mode, indicator, snapshot.toolchain, owner)
                    // The modal dialog owns the appropriate EDT modality. A
                    // non-modal invokeLater here would wait until it closes.
                    success(response)
                } catch (_: ProcessCanceledException) { failure("Go analysis was cancelled. Refresh to try again.") }
                catch (error: Exception) { failure(error.cause?.message ?: error.message ?: "Go analysis failed") }
            }
        }
        val accepted = try { dialog.showAndGet() } finally { inspections.getAndSet(null)?.close() }
        if (!accepted) return
        val selection = dialog.selection()
        val savedDraft = dialog.draft()
        work.background("Plan Go Tests") { indicator ->
            checkCurrent(snapshot)
            val request = snapshot.wire.deepCopy().apply { selection.entrySet().forEach { (key, value) -> add(key, value.deepCopy()) } }
            val plan = operation("generate/tests/plan", request, indicator, snapshot.toolchain)
            if (GoSignatureCodec.conflicts(plan).isNotEmpty()) {
                work.later { showConflicts(plan); showEditor(snapshot, initialInspection, savedDraft) }
                return@background
            }
            require(plan.getAsJsonObject("validation")?.bool("typeChecked") == true) { "Generated tests did not pass Go type checking" }
            val edit = GoTestGenerationCodec.workspaceEdit(plan)
            val response = work.await(GoWorkspaceEditService.get(project).apply(edit, TITLE, preview = true,
                request = snapshot.request, validation = GoSignatureValidation(project, plan, snapshot.configuration, operationName = TITLE),
                usages = GoSignatureCodec.usages(plan)), indicator)
            if (response.isApplied) {
                val uri = GoTestGenerationCodec.firstEditedUri(plan)
                val file = uri?.let { LocalFileSystem.getInstance().refreshAndFindFileByNioFile(Path.of(URI(it))) }
                if (file != null) work.later { FileEditorManager.getInstance(project).openTextEditor(OpenFileDescriptor(project, file), true) }
            } else if (response.failureReason?.contains("cancel", ignoreCase = true) != true) {
                error(response.failureReason ?: "The generated tests could not be applied")
            }
        }
    }

    private fun operation(method: String, parameters: JsonObject, indicator: ProgressIndicator, settings: GoToolchain.Settings, owner: InspectionOwner? = null): JsonObject {
        val id = UUID.randomUUID().toString()
        val request = parameters.deepCopy().apply { addProperty("operationId", id) }
        owner?.add(id)
        active += id
        var succeeded = false
        return try {
            work.await(engine.request(method, request, settings, timeoutSeconds = 180), indicator).asJsonObject.also { succeeded = true }
        } finally { active -= id; owner?.remove(id); if (!succeeded) engine.cancelOperation(id) }
    }

    private fun checkCurrent(snapshot: Snapshot) {
        check(!disposed && !project.isDisposed) { "The Go project is closed" }
        require(GoToolchain.configurationKey(project) == snapshot.configuration) { "Go build settings changed. Run Generate Tests again." }
        ReadAction.run<RuntimeException> {
            val manager = FileDocumentManager.getInstance()
            snapshot.documents.forEach { observed ->
                val current = manager.getCachedDocument(observed.file)
                require(observed.file.isValid && current === observed.document && current.modificationStamp == observed.stamp) {
                    "An analyzed editor changed or closed. Run Generate Tests again."
                }
            }
        }
    }

    private fun showConflicts(value: JsonObject) = Messages.showErrorDialog(project, GoSignatureCodec.conflicts(value).joinToString("\n\n"), "Generate Go Tests Conflicts")
    override fun dispose() { disposed = true; active.toList().forEach(engine::cancelOperation); active.clear() }

    companion object {
        private const val TITLE = "Generate Go Tests"
        fun get(project: Project): GoGenerateTestsService = project.getService(GoGenerateTestsService::class.java)
    }
}

/** Background-only resolution shared with the service regression; never consults mutable project settings. */
internal fun generationToolchainParameters(project: Project, settings: GoToolchain.Settings): JsonObject {
    val command = GoToolchain.command(project, "go", emptyList(), settings)
    return JsonObject().apply {
        addProperty("goExecutable", command.exePath)
        add("environment", JsonObject().apply { command.environment.forEach { (key, value) -> addProperty(key, value) } })
        add("buildTags", JsonArray().apply { GoToolchain.buildTags(settings.buildTags).forEach(::add) })
    }
}
