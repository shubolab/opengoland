package dev.openidea.goide.refactor

import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Path

/** Native write-only half of a prevalidated transaction; the service owns its one global command. */
internal class GoWorkspaceEditNativeExecutor(private val requestor: Any, private val documents: Map<Path, Document>) {
    fun execute(plan: GoEditPlan, aliases: Set<GoCaseRenameAlias>, journal: GoWorkspaceEditJournal) {
        val created = mutableSetOf<Path>()
        val working = GoWorkspaceEditCaseAliases.logicalTree(plan.before, aliases).toMutableMap()
        fun deletedEntries(path: Path) = working.filterKeys { it.startsWith(path) }
        plan.steps.forEach { step ->
            when (step) {
                is GoEditStep.Directory -> {
                    val directory = parent(step.path).createChildDirectory(requestor, step.path.fileName.toString())
                    journal.record { if (directory.isValid) { check(directory.children.isEmpty()) { "Created directory has unexpected contents: ${step.path}" }; directory.delete(requestor) } }
                    working[step.path] = GoEditNode(true)
                    created.add(step.path)
                }
                is GoEditStep.Create -> {
                    val old = find(step.path)
                    if (old != null) {
                        check(step.overwrite) { "Unexpected create target collision: ${step.path}" }
                        val backup = deletedEntries(step.path)
                        journal.record { restoreEntries(backup) }
                        old.delete(requestor)
                    }
                    val file = parent(step.path).createChildData(requestor, step.path.fileName.toString())
                    journal.record { if (file.isValid) file.delete(requestor) }
                    created.add(step.path)
                    working[step.path] = GoEditNode(false)
                }
                is GoEditStep.Rename -> {
                    val file = find(step.old) ?: error("Rename source disappeared: ${step.old}")
                    val target = find(step.new)
                    if (step.caseOnly) check(target == file && file.parent == parent(step.new)) { "Case-only target is no longer the same native VFS resource: ${step.new}" }
                    if (target != null && target != file) {
                        check(step.overwrite) { "Unexpected rename target collision: ${step.new}" }
                        val backup = deletedEntries(step.new)
                        journal.record { restoreEntries(backup) }
                        target.delete(requestor)
                        working.keys.filter { it.startsWith(step.new) }.toList().forEach(working::remove)
                    }
                    val originalParent = file.parent
                    val originalParentPath = Path.of(originalParent.path)
                    val originalName = file.name
                    journal.record {
                        // A later planned delete may have been undone by an earlier inverse,
                        // recreating the same logical resource with a new native VFS identity.
                        val current = if (file.isValid) file else find(step.new) ?: error("Cannot restore moved resource: ${step.old}")
                        val destination = if (originalParent.isValid) originalParent else find(originalParentPath) ?: error("Cannot restore move parent")
                        if (current.parent != destination) current.move(requestor, destination)
                        if (current.name != originalName) current.rename(requestor, originalName)
                    }
                    val newParent = parent(step.new)
                    if (file.parent != newParent) file.move(requestor, newParent)
                    if (file.name != step.new.fileName.toString()) file.rename(requestor, step.new.fileName.toString())
                    val relocated = created.filter { it.startsWith(step.old) }.map { step.new.resolve(step.old.relativize(it)) }
                    created.removeAll { it.startsWith(step.old) }; created += relocated
                    val entries = deletedEntries(step.old)
                    entries.keys.forEach(working::remove)
                    entries.forEach { (path, node) -> working[step.new.resolve(step.old.relativize(path))] = node }
                }
                is GoEditStep.Delete -> {
                    val backup = deletedEntries(step.path)
                    journal.record { restoreEntries(backup) }
                    find(step.path)?.delete(requestor) ?: error("Delete target disappeared: ${step.path}")
                    backup.keys.forEach(working::remove)
                }
                is GoEditStep.Text -> {
                    val file = find(step.path) ?: error("Text target disappeared: ${step.path}")
                    val document = step.origin?.let { documents[it] }?.takeIf { FileDocumentManager.getInstance().getFile(it) == file }
                    if (document != null) {
                        check(document.text == step.before) { "Document changed during commit: ${step.path}" }
                        journal.record {
                            val current = find(step.path) ?: error("Cannot restore text: ${step.path}")
                            val doc = if (FileDocumentManager.getInstance().getFile(document) == current) document else
                                FileDocumentManager.getInstance().getCachedDocument(current) ?: FileDocumentManager.getInstance().getDocument(current)
                            check(doc != null) { "Cannot restore document: ${step.path}" }
                            if (doc.text != step.before) doc.setText(step.before)
                        }
                        document.setText(step.after)
                    } else {
                        check(step.path in created) { "No preloaded document for ${step.path}" }
                        journal.record { find(step.path)?.setBinaryContent(step.before.toByteArray(Charsets.UTF_8), -1, -1, this) ?: error("Cannot restore created document") }
                        file.setBinaryContent(step.after.toByteArray(Charsets.UTF_8), -1, -1, this)
                    }
                    working[step.path] = working.getValue(step.path).copy(text = step.after)
                }
            }
        }
    }

    /** Called only for a native delete which actually began; untouched later resources are never rebuilt. */
    private fun restoreEntries(entries: Map<Path, GoEditNode>) {
        entries.entries.sortedBy { it.key.nameCount }.forEach { (path, node) ->
            if (find(path) == null) {
                val file = if (node.directory) parent(path).createChildDirectory(requestor, path.fileName.toString()) else parent(path).createChildData(requestor, path.fileName.toString())
                if (!node.directory) {
                    file.setBinaryContent(if (node.origin == null && node.text != null) node.text.toByteArray(Charsets.UTF_8) else node.bytes, -1, -1, this)
                    if (node.text != null) FileDocumentManager.getInstance().getDocument(file)?.let { if (it.text != node.text) it.setText(node.text) }
                }
            }
        }
    }

    private fun find(path: Path): VirtualFile? = LocalFileSystem.getInstance().findFileByNioFile(path)
    private fun parent(path: Path): VirtualFile = find(path.parent) ?: error("Parent directory disappeared: ${path.parent}")
}
