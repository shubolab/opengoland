package dev.openidea.goide.engine

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VirtualFile

/** Memory-only native check after SDK preparation and before releasing a paused CLI task. */
internal class GoTaskDocumentGuard private constructor(
    private val project: Project,
    private val directories: Set<String>,
    private val metadata: Set<String>,
    private val observed: List<Observed>,
) {
    private data class Observed(val document: Document, val file: VirtualFile, val url: String, val stamp: Long)

    fun including(inspection: GoTaskTargetInspection): GoTaskDocumentGuard {
        checkEdt()
        val roots = directories + inspection.targets.flatMap { it.moduleRoots + it.cwd }
        val files = metadata + listOfNotNull(inspection.workspace, inspection.workspace?.plus(".sum"))
        return create(project, roots, files, observed).also { it.validate() }
    }

    fun validate() {
        checkEdt()
        check(!project.isDisposed) { "The Go project was closed" }
        val manager = FileDocumentManager.getInstance()
        val dirty = manager.unsavedDocuments.mapNotNull(manager::getFile).filter(::relevant)
        check(dirty.isEmpty()) {
            "Go source or module files have unsaved changes: ${dirty.map { it.name }.distinct().joinToString(", ")}. Save them and run the Go action again."
        }
        check(observed.all { it.file.isValid && it.file.url == it.url && it.document.modificationStamp == it.stamp }) {
            "Go source or module files changed while the task was being prepared. Run the Go action again."
        }
    }

    private fun relevant(file: VirtualFile): Boolean {
        val namedMetadata = metadata.any { FileUtil.pathsEqual(it, file.path) }
        if (!namedMetadata && file.extension != "go" && file.name !in setOf("go.mod", "go.sum", "go.work", "go.work.sum")) return false
        return namedMetadata || directories.any { FileUtil.isAncestor(it, file.path, false) }
    }

    companion object {
        fun capture(project: Project, directories: List<String>): GoTaskDocumentGuard =
            create(project, directories.toSet(), emptySet(), emptyList()).also { it.validate() }

        private fun create(project: Project, roots: Set<String>, metadata: Set<String>, previous: List<Observed>): GoTaskDocumentGuard {
            checkEdt()
            val guard = GoTaskDocumentGuard(project, roots, metadata, previous)
            val manager = FileDocumentManager.getInstance()
            val existing = previous.map { it.document }.toSet()
            val documents = (EditorFactory.getInstance().allEditors.filter { it.project === project }.map { it.document } + manager.unsavedDocuments)
                .distinct().filterNot { it in existing }
            val additional = documents.mapNotNull { document ->
                manager.getFile(document)?.takeIf(guard::relevant)?.let { Observed(document, it, it.url, document.modificationStamp) }
            }
            return GoTaskDocumentGuard(project, roots, metadata, previous + additional)
        }
        private fun checkEdt() { check(ApplicationManager.getApplication().isDispatchThread) { "Go task document checks belong on the EDT" } }
    }
}
