package dev.openidea.goide.generate

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.redhat.devtools.lsp4ij.LSPIJUtils
import dev.openidea.goide.engine.EngineClient
import dev.openidea.goide.generate.tests.generationToolchainParameters
import dev.openidea.goide.lsp.GoDocumentSync
import dev.openidea.goide.refactor.GoEditRequestSnapshot
import dev.openidea.goide.refactor.GoWorkspaceEditService
import dev.openidea.goide.refactor.signature.array
import dev.openidea.goide.refactor.signature.bool
import dev.openidea.goide.refactor.signature.string
import dev.openidea.goide.semantic.GoSemanticOperations
import dev.openidea.goide.settings.GoToolchain
import java.net.URI
import java.nio.file.Path

/** Shared immutable input ownership for typed generation. Capture never discovers files or tools. */
internal class GoGenerationSnapshot private constructor(
    private val project: Project,
    val wire: JsonObject,
    val request: GoEditRequestSnapshot,
    val toolchain: GoToolchain.Settings,
    private val documents: List<Observed>,
    private val title: String,
) {
    private data class Observed(val file: VirtualFile, val url: String, val document: Document, val stamp: Long)

    /** Tool/filesystem work belongs on the caller's cancellable background task. */
    fun prepare(indicator: ProgressIndicator): GoGenerationSnapshot {
        checkCurrent()
        val work = GoSemanticOperations.get(project)
        val engine = project.getService(EngineClient::class.java)
        val configuration = generationToolchainParameters(project, toolchain)
        val info = work.await(engine.request("project/info", JsonObject(), toolchain), indicator).asJsonObject
        val roots = mutableListOf(Path.of(wire.string("root")).toAbsolutePath().normalize())
        info.array("modules").forEach { entry ->
            val module = entry.asJsonObject
            if (module.bool("Main") && module.string("Dir").isNotBlank()) roots.add(Path.of(module.string("Dir")).toAbsolutePath().normalize())
        }
        val prepared = wire.deepCopy().apply {
            configuration.entrySet().forEach { (key, value) -> add(key, value) }
            add("overlays", JsonArray().apply {
                wire.array("overlays").forEach { entry ->
                    val overlay = entry.asJsonObject
                    val path = Path.of(URI(overlay.string("uri"))).toAbsolutePath().normalize()
                    if (roots.any(path::startsWith) || overlay.bool("dirty") || path.fileName.toString() in METADATA) add(overlay)
                }
            })
        }
        checkCurrent()
        return GoGenerationSnapshot(project, prepared, request, toolchain, documents, title)
    }

    fun checkCurrent() {
        check(!project.isDisposed) { "The Go project is closed" }
        require(project.basePath == wire.string("root")) { "The project location changed. Run $title again." }
        require(GoToolchain.configurationKey(project) == toolchain.key) { "Go build settings changed. Run $title again." }
        ReadAction.run<RuntimeException> {
            val manager = FileDocumentManager.getInstance()
            documents.forEach { observed ->
                val current = manager.getCachedDocument(observed.file)
                require(observed.file.isValid && observed.file.url == observed.url && current === observed.document && current.modificationStamp == observed.stamp) {
                    "An analyzed editor changed or closed. Run $title again."
                }
            }
        }
    }

    companion object {
        private val METADATA = setOf("go.mod", "go.work", "go.sum", "go.work.sum")
        fun capture(file: PsiFile, editor: Editor, title: String): GoGenerationSnapshot = ReadAction.compute<GoGenerationSnapshot, RuntimeException> {
            val project = file.project
            val toolchain = GoToolchain.capture(project)
            val manager = FileDocumentManager.getInstance()
            val virtual = file.virtualFile ?: error("$title requires a local Go source file")
            require(!project.isDisposed && file.isValid && virtual.isValid && virtual.isInLocalFileSystem && virtual.isWritable) { "$title requires writable Go source" }
            val document = manager.getCachedDocument(virtual) ?: error("No Go source document")
            require(!editor.isDisposed && editor.project === project && editor.document === document && !editor.isViewer) { "Choose an editable Go source document" }
            val position = LSPIJUtils.toPosition(editor.caretModel.offset, document)
            val index = ProjectFileIndex.getInstance(project)
            val open = EditorFactory.getInstance().allEditors.filter { it.project == project }.map { it.document }
            val unsaved = manager.unsavedDocuments.filter { candidate -> manager.getFile(candidate)?.let(index::isInContent) == true }
            val overlays = JsonArray()
            val observed = mutableListOf<Observed>()
            for (candidate in (open + unsaved + document).distinct()) {
                val source = manager.getFile(candidate)?.takeIf { it.isValid && it.isInLocalFileSystem } ?: continue
                if (source.extension != "go" && source.name !in METADATA) continue
                val uri = GoDocumentSync.fileUri(Path.of(source.path))
                val text = candidate.text
                observed += Observed(source, source.url, candidate, candidate.modificationStamp)
                overlays.add(JsonObject().apply {
                    addProperty("uri", uri); addProperty("text", text); addProperty("documentStamp", candidate.modificationStamp)
                    addProperty("dirty", manager.isDocumentUnsaved(candidate))
                    GoDocumentSync.get(project).snapshot(uri)?.takeIf { it.text == text }?.let { synced ->
                        add("lsp", JsonObject().apply { addProperty("session", synced.session); addProperty("version", synced.version) })
                    }
                })
            }
            val wire = JsonObject().apply {
                addProperty("root", project.basePath ?: error("Open a Go project first"))
                add("target", JsonObject().apply {
                    addProperty("uri", GoDocumentSync.fileUri(Path.of(virtual.path)))
                    add("position", JsonObject().apply { addProperty("line", position.line); addProperty("character", position.character) })
                })
                add("overlays", overlays)
            }
            GoGenerationSnapshot(project, wire, GoWorkspaceEditService.get(project).captureRequest(), toolchain, observed, title)
        }
    }
}
