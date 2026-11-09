package dev.openidea.goide.refactor.native

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.ex.DocumentEx
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.redhat.devtools.lsp4ij.LSPIJUtils
import dev.openidea.goide.generate.GoGenerationSnapshot
import dev.openidea.goide.lsp.GoDocumentSync
import dev.openidea.goide.refactor.GoWorkspaceEditValidation
import dev.openidea.goide.refactor.signature.GoSignatureValidation
import dev.openidea.goide.refactor.signature.array
import dev.openidea.goide.refactor.signature.string
import java.net.URI
import java.nio.file.Path

/** Event history also detects edit/Undo of a dependency first opened after capture. */
@Service(Service.Level.PROJECT)
internal class GoNativeRefactorDocuments(private val project: Project) : Disposable {
    private var clock = 0L
    private var discarded = 0L
    private val changed = linkedMapOf<String, Long>()
    init {
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                val file = FileDocumentManager.getInstance().getFile(event.document) ?: return
                if (file.extension != "go" && file.name !in METADATA) return
                val uri = GoDocumentSync.fileUri(Path.of(file.path))
                synchronized(this@GoNativeRefactorDocuments) {
                    changed.remove(uri)
                    changed[uri] = ++clock
                    if (changed.size > 4096) {
                        val first = changed.entries.first()
                        discarded = maxOf(discarded, first.value)
                        changed.remove(first.key)
                    }
                }
            }
        }, this)
    }
    @Synchronized fun capture() = clock
    @Synchronized fun check(epoch: Long, roots: Set<Path>, uris: Set<String>) {
        check(!project.isDisposed) { "The Go project is closed" }
        require(epoch >= discarded) { "Editor history changed substantially; invoke the Go refactoring again" }
        require(changed.none { (uri, version) -> version > epoch && (uri in uris ||
            runCatching { Path.of(URI(uri)).toAbsolutePath().normalize() }.getOrNull()?.let { path -> roots.any(path::startsWith) } == true) }) {
            "An analyzed Go document changed, including an edit followed by Undo. Invoke the refactoring again."
        }
    }
    override fun dispose() = synchronized(this) { changed.clear(); clock++; discarded = clock }
    companion object { val METADATA = setOf("go.mod", "go.sum", "go.work", "go.work.sum") }
}

internal class GoNativeRefactorSnapshot private constructor(
    private val project: Project,
    private val base: GoGenerationSnapshot,
    val wire: JsonObject,
    val selection: JsonObject,
    private val observed: List<Observed>,
    private val epoch: Long,
    private val roots: Set<Path>,
) {
    private data class Observed(val file: VirtualFile, val uri: String, val document: Document,
                                val stamp: Long, val sequence: Int?, val dirty: Boolean)
    val request get() = base.request
    val toolchain get() = base.toolchain
    val targetUri get() = wire.getAsJsonObject("target").string("uri")
    val sourceText get() = wire.array("overlays").first { it.asJsonObject.string("uri") == targetUri }.asJsonObject.string("text")
    private val history get() = project.getService(GoNativeRefactorDocuments::class.java)

    fun prepare(indicator: ProgressIndicator): GoNativeRefactorSnapshot {
        checkCurrent()
        val prepared = base.prepare(indicator)
        return GoNativeRefactorSnapshot(project, prepared, prepared.wire.deepCopy(), selection, observed, epoch, roots)
    }

    /** Coverage is from actual selected metadata, including dirty go.work inputs. */
    fun includeCoverage(inspection: JsonObject): GoNativeRefactorSnapshot {
        val nextRoots = roots + inspection.getAsJsonObject("coverage").array("modules").map { Path.of(it.asString).toAbsolutePath().normalize() }
        history.check(epoch, nextRoots, emptySet())
        val next = wire.deepCopy()
        val overlays = next.array("overlays").map { it.asJsonObject.string("uri") }.toMutableSet()
        val items = next.getAsJsonArray("overlays")
        for (entry in observed) {
            if (entry.uri !in overlays && nextRoots.any(Path.of(entry.file.path).toAbsolutePath().normalize()::startsWith)) {
                items.add(entry.overlay()); overlays.add(entry.uri)
            }
        }
        return GoNativeRefactorSnapshot(project, base, next, selection, observed, epoch, nextRoots).also { it.checkCurrent() }
    }

    fun synchronized(): GoNativeRefactorSnapshot {
        checkCurrent()
        val next = wire.deepCopy()
        for (item in next.array("overlays")) {
            val overlay = item.asJsonObject
            val uri = overlay.string("uri")
            GoDocumentSync.get(project).snapshot(uri)?.takeIf { it.text == overlay.string("text") }?.let { synced ->
                overlay.add("lsp", JsonObject().apply { addProperty("session", synced.session); addProperty("version", synced.version) })
            }
        }
        return GoNativeRefactorSnapshot(project, base, next, selection, observed, epoch, roots)
    }

    fun withSourceDigest(digest: String): GoNativeRefactorSnapshot {
        require(digest.isNotBlank()) { "The typed Go context has no source guards" }
        return GoNativeRefactorSnapshot(project, base, wire.deepCopy().apply { addProperty("expectedSourceDigest", digest) },
            selection, observed, epoch, roots)
    }

    fun filesToSynchronize(): List<VirtualFile> {
        val included = wire.array("overlays").map { it.asJsonObject.string("uri") }.toSet()
        return observed.filter { it.uri in included && (it.file.extension == "go" || it.file.name in setOf("go.mod", "go.work")) }.map { it.file }
    }

    fun checkCurrent(extraUris: Set<String> = emptySet()) {
        base.checkCurrent()
        val included = wire.array("overlays").map { it.asJsonObject.string("uri") }.toSet()
        history.check(epoch, roots, included + extraUris)
        ReadAction.run<RuntimeException> {
            val manager = FileDocumentManager.getInstance()
            observed.filter { it.uri in included }.forEach { entry ->
                require(entry.file.isValid && GoDocumentSync.fileUri(Path.of(entry.file.path)) == entry.uri &&
                    manager.getCachedDocument(entry.file) === entry.document && entry.document.modificationStamp == entry.stamp &&
                    (entry.sequence == null || (entry.document as DocumentEx).modificationSequence == entry.sequence)) {
                    "A Go refactoring input changed. Invoke the refactoring again."
                }
            }
        }
    }

    fun validation(plan: JsonObject): GoWorkspaceEditValidation {
        val delegate = GoSignatureValidation(project, plan, toolchain.key, operationName = TITLE)
        val guarded = plan.array("preconditions").map { it.asJsonObject.string("uri") }.toSet()
        return object : GoWorkspaceEditValidation by delegate {
            override fun validateBackground() { checkCurrent(guarded); delegate.validateBackground(); checkCurrent(guarded) }
            override fun validateDocuments() { checkCurrent(guarded); delegate.validateDocuments() }
        }
    }

    private fun Observed.overlay(): JsonObject {
        val text = ReadAction.compute<String, RuntimeException> {
            require(file.isValid && document.modificationStamp == stamp &&
                (sequence == null || (document as DocumentEx).modificationSequence == sequence)) { "An unsaved module buffer changed" }
            document.text
        }
        return JsonObject().apply { addProperty("uri", uri); addProperty("text", text); addProperty("documentStamp", stamp); addProperty("dirty", dirty) }
    }

    companion object {
        const val TITLE = "Go Refactoring"
        fun capture(file: PsiFile, editor: Editor): GoNativeRefactorSnapshot = ReadAction.compute<GoNativeRefactorSnapshot, RuntimeException> {
            val project = file.project
            val history = project.getService(GoNativeRefactorDocuments::class.java)
            val epoch = history.capture()
            val base = GoGenerationSnapshot.capture(file, editor, TITLE)
            val manager = FileDocumentManager.getInstance()
            val documents = manager.unsavedDocuments.toList() + EditorFactory.getInstance().allEditors.filter { it.project == project }.map { it.document } + editor.document
            val observed = documents.distinct().mapNotNull { document ->
                val virtual = manager.getFile(document)?.takeIf { it.isValid && it.isInLocalFileSystem } ?: return@mapNotNull null
                if (virtual.extension != "go" && virtual.name !in GoNativeRefactorDocuments.METADATA) return@mapNotNull null
                Observed(virtual, GoDocumentSync.fileUri(Path.of(virtual.path)), document, document.modificationStamp,
                    (document as? DocumentEx)?.modificationSequence, manager.isDocumentUnsaved(document))
            }
            val range = JsonObject().apply {
                fun position(offset: Int) = LSPIJUtils.toPosition(offset, editor.document).let { p -> JsonObject().apply { addProperty("line", p.line); addProperty("character", p.character) } }
                add("start", position(editor.selectionModel.selectionStart)); add("end", position(editor.selectionModel.selectionEnd))
            }
            GoNativeRefactorSnapshot(project, base, base.wire.deepCopy(), range, observed, epoch,
                setOf(Path.of(base.wire.string("root")).toAbsolutePath().normalize())).also { it.checkCurrent() }
        }
    }
}
