package dev.openidea.goide.refactor

import dev.openidea.goide.lsp.GoSyncedDocument
import dev.openidea.goide.lsp.GoDocumentSync
import org.eclipse.lsp4j.AnnotatedTextEdit
import org.eclipse.lsp4j.CreateFile
import org.eclipse.lsp4j.DeleteFile
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.RenameFile
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.WorkspaceEdit
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Path

data class GoEditDocumentStamp(val stamp: Long, val synced: GoSyncedDocument?)
data class GoEditDiskStamp(val size: Long, val sha256: String, val modifiedAtMillis: Long, val fileKey: String?) {
    fun matches(other: GoEditDiskStamp): Boolean = size == other.size && sha256 == other.sha256 && modifiedAtMillis == other.modifiedAtMillis &&
        (fileKey == null || other.fileKey == null || fileKey == other.fileKey)
}
data class GoEditRequestSnapshot(
    val documents: Map<String, GoEditDocumentStamp>, val capturedAtMillis: Long = System.currentTimeMillis(),
    val changeSequence: Long = 0, val acceptedChanges: Map<String, Long> = emptyMap(),
    val acceptedDisk: Map<String, GoEditDiskStamp> = emptyMap(),
)

internal object GoWorkspaceEditGuard {
    fun unacknowledged(path: Path, changedAt: Long, request: GoEditRequestSnapshot): Boolean =
        changedAt > maxOf(request.changeSequence, request.acceptedChanges[GoDocumentSync.fileUri(path)] ?: Long.MIN_VALUE)

    fun acknowledge(request: GoEditRequestSnapshot, ownedChanges: Map<String, Long>, touchedUris: Set<String>, currentDocuments: Map<String, GoEditDocumentStamp>): GoEditRequestSnapshot =
        request.copy(documents = request.documents.filterKeys { it !in touchedUris } + currentDocuments,
            acceptedChanges = request.acceptedChanges + ownedChanges)

    fun validateClosedDisk(path: Path, disk: GoEditDiskStamp, request: GoEditRequestSnapshot?) {
        val accepted = request?.acceptedDisk?.get(GoDocumentSync.fileUri(path))
        if (accepted != null && !accepted.matches(disk))
            throw GoWorkspaceEditRejected("Closed document no longer matches its acknowledged disk baseline: $path")
        if (request != null && disk.modifiedAtMillis > request.capturedAtMillis && accepted?.matches(disk) != true)
            throw GoWorkspaceEditRejected("Closed document changed on disk after the language-server request: $path")
    }

    fun validate(path: Path, baselineStamp: Long, baselineText: String, stamp: Long, text: String, captured: GoEditDocumentStamp?, currentSync: GoSyncedDocument?) {
        if (stamp != baselineStamp || text != baselineText) throw GoWorkspaceEditRejected("Document changed after the edit was prepared: $path")
        if (captured != null && captured.stamp != stamp) throw GoWorkspaceEditRejected("Document changed after the language-server request: $path")
        if (captured?.synced != null && captured.synced != currentSync) throw GoWorkspaceEditRejected("The language-server document version/session changed after the request: $path")
    }
}

internal data class GoEditNode(
    val directory: Boolean, val bytes: ByteArray = byteArrayOf(), val text: String? = null, val origin: Path? = null,
    val disk: GoEditDiskStamp? = null, val closedOnDisk: Boolean = false,
)
internal sealed interface GoEditStep {
    data class Text(val path: Path, val before: String, val after: String, val origin: Path?) : GoEditStep
    data class Create(val path: Path, val overwrite: Boolean) : GoEditStep
    data class Rename(val old: Path, val new: Path, val overwrite: Boolean, val caseOnly: Boolean = false) : GoEditStep
    data class Delete(val path: Path) : GoEditStep
    data class Directory(val path: Path) : GoEditStep
}
internal data class GoEditPreview(val path: Path, val kind: String, val before: String, val after: String) {
    override fun toString() = "$kind  $path"
}
internal data class GoEditPlan(val steps: List<GoEditStep>, val before: Map<Path, GoEditNode>, val after: Map<Path, GoEditNode>, val preview: List<GoEditPreview>, val requiresConfirmation: Boolean)
internal class GoWorkspaceEditRejected(message: String, val failedChange: Int? = null) : IllegalArgumentException(message)

/** Pure planner. No VFS/document changes occur until the entire ordered edit is accepted. */
internal object GoWorkspaceEditPlan {
    fun path(uri: String): Path {
        val value = runCatching { URI(uri) }.getOrElse { throw GoWorkspaceEditRejected("Invalid file URI: $uri") }
        requireEdit(value.scheme == "file" && value.query == null && value.fragment == null && value.authority.isNullOrEmpty(), "Only local file URIs are supported: $uri")
        return runCatching { Path.of(value).toAbsolutePath().normalize() }.getOrElse { throw GoWorkspaceEditRejected("Invalid local file URI: $uri") }
    }

    fun paths(edit: WorkspaceEdit): Set<Path> = buildSet {
        edit.changes?.keys?.forEach { add(path(it)) }
        edit.documentChanges?.forEach { entry ->
            if (entry.isLeft) add(path(entry.left.textDocument.uri)) else when (val operation = entry.right) {
                is CreateFile -> add(path(operation.uri))
                is DeleteFile -> add(path(operation.uri))
                is RenameFile -> { add(path(operation.oldUri)); add(path(operation.newUri)) }
                else -> throw GoWorkspaceEditRejected("Unsupported resource operation: ${operation.kind}")
            }
        }
    }

    fun build(edit: WorkspaceEdit, initial: Map<Path, GoEditNode>, synchronized: (Path) -> GoSyncedDocument?, editable: (Path) -> Boolean): GoEditPlan =
        build(edit, initial, synchronized, emptySet(), editable)

    fun build(edit: WorkspaceEdit, initial: Map<Path, GoEditNode>, synchronized: (Path) -> GoSyncedDocument?,
              aliases: Set<GoCaseRenameAlias>, editable: (Path) -> Boolean): GoEditPlan {
        requireEdit(edit.changes.isNullOrEmpty() || edit.documentChanges.isNullOrEmpty(), "WorkspaceEdit must not mix changes and documentChanges.")
        val tree = GoWorkspaceEditCaseAliases.logicalTree(initial, aliases).toMutableMap()
        val steps = mutableListOf<GoEditStep>()
        val preview = mutableListOf<GoEditPreview>()
        val versionedOrigins = mutableSetOf<Path>()
        fun logical(path: Path) = GoWorkspaceEditCaseAliases.resolve(path, tree, initial, aliases)
        var confirmation = false
        fun checkAnnotation(id: String?) {
            if (id == null) return
            val annotation = edit.changeAnnotations?.get(id) ?: throw GoWorkspaceEditRejected("Unknown change annotation: $id")
            if (annotation.needsConfirmation == true) confirmation = true
        }
        fun checkPath(p: Path) { requireEdit(editable(p), "Resource is outside the editable workspace: $p") }
        fun makeParents(p: Path) {
            val parent = p.parent ?: throw GoWorkspaceEditRejected("Cannot edit a filesystem root")
            if (tree[parent] == null) { makeParents(parent); tree[parent] = GoEditNode(true); steps += GoEditStep.Directory(parent) }
            requireEdit(tree[parent]?.directory == true, "Parent is not a directory: $parent")
        }
        fun text(p: Path, requestedVersion: Int?, changes: List<TextEdit>) {
            checkPath(p)
            val node = tree[p] ?: throw GoWorkspaceEditRejected("Text document does not exist: $p")
            requireEdit(!node.directory, "Cannot apply text edits to a directory: $p")
            val before = text(node)
            val origin = node.origin
            val synced = origin?.let(synchronized)
            if (requestedVersion != null) {
                requireEdit(versionedOrigins.add(origin ?: p), "Repeated versioned edits require an intermediate synchronized version; request a new workspace edit: $p")
                if (synced == null) {
                    // gopls v0.21.1 file.Handle.Version explicitly returns 0 for disk files.
                    // This is not a synthetic didOpen version: service preparation must prove closure
                    // and an unchanged disk baseline, with no opened or dirty IDE document.
                    requireEdit(requestedVersion == 0 && origin != null && node.closedOnDisk && node.disk != null,
                        "No synchronized document version or verified closed-disk baseline is available for $p")
                    requireEdit(before == text(node.copy(text = null)), "Closed-document buffer differs from disk: $p")
                } else {
                    requireEdit(requestedVersion == synced.version, "Document version changed for $p (edit $requestedVersion, current ${synced.version})")
                    requireEdit(synced.text == before, "The IDE document and language-server text differ: $p")
                }
            }
            changes.filterIsInstance<AnnotatedTextEdit>().forEach { checkAnnotation(it.annotationId) }
            val after = applyText(before, changes)
            tree[p] = node.copy(text = after)
            if (after != before) { steps += GoEditStep.Text(p, before, after, origin); preview += GoEditPreview(p, "Edit", before, after) }
        }
        edit.changes?.forEach { (uri, changes) -> text(logical(path(uri)), null, changes) }
        edit.documentChanges?.forEachIndexed { index, entry ->
            try {
                if (entry.isLeft) {
                    val value = entry.left
                    // Platform SDKs and LSP4IJ use two binary-compatible List element shapes.
                    val payloads: List<*> = value.edits
                    val changes = payloads.map { payload -> when (payload) {
                        is TextEdit -> payload
                        is org.eclipse.lsp4j.jsonrpc.messages.Either<*, *> -> payload.left as? TextEdit
                            ?: throw GoWorkspaceEditRejected("Snippet workspace edits are not supported")
                        else -> throw GoWorkspaceEditRejected("Unsupported workspace text edit payload")
                    } }
                    text(logical(path(value.textDocument.uri)), value.textDocument.version, changes)
                } else when (val op = entry.right) {
                    is CreateFile -> {
                        checkAnnotation(op.annotationId)
                        val p = logical(path(op.uri)); checkPath(p)
                        val old = tree[p]
                        if (old != null && op.options?.overwrite != true && op.options?.ignoreIfExists == true) return@forEachIndexed
                        requireEdit(old == null || op.options?.overwrite == true, "Create target already exists: $p")
                        requireEdit(old?.directory != true, "CreateFile cannot replace a directory: $p")
                        makeParents(p)
                        steps += GoEditStep.Create(p, old != null)
                        tree[p] = GoEditNode(false, origin = null)
                        preview += GoEditPreview(p, if (old == null) "Create" else "Replace", old?.let(::display).orEmpty(), "")
                    }
                    is RenameFile -> {
                        checkAnnotation(op.annotationId)
                        val old = logical(path(op.oldUri))
                        val requestedNew = path(op.newUri)
                        val new = logical(requestedNew.parent).resolve(requestedNew.fileName)
                        checkPath(old); checkPath(new)
                        if (old == new) return@forEachIndexed
                        val node = tree[old] ?: throw GoWorkspaceEditRejected("Rename source does not exist: $old")
                        requireEdit(!new.startsWith(old) && !old.startsWith(new), "Cannot rename overlapping ancestor/descendant paths")
                        val targetPath = logical(new)
                        val caseOnly = targetPath == old && targetPath != new
                        val target = if (caseOnly) null else tree[targetPath]
                        if (target != null && op.options?.overwrite != true && op.options?.ignoreIfExists == true) return@forEachIndexed
                        requireEdit(target == null || op.options?.overwrite == true, "Rename target already exists: $new")
                        makeParents(new)
                        if (target != null) tree.keys.filter { it.startsWith(new) }.toList().forEach(tree::remove)
                        val moving = tree.filterKeys { it.startsWith(old) }
                        moving.keys.forEach(tree::remove)
                        moving.forEach { (p, child) -> tree[new.resolve(old.relativize(p))] = child }
                        steps += GoEditStep.Rename(old, new, target != null, caseOnly)
                        preview += GoEditPreview(new, "Rename from $old", display(node), display(node))
                    }
                    is DeleteFile -> {
                        checkAnnotation(op.annotationId)
                        val p = logical(path(op.uri)); checkPath(p)
                        val old = tree[p]
                        if (old == null && op.options?.ignoreIfNotExists == true) return@forEachIndexed
                        requireEdit(old != null, "Delete target does not exist: $p")
                        val children = tree.keys.filter { it != p && it.startsWith(p) }
                        requireEdit(old?.directory != true || children.isEmpty() || op.options?.recursive == true, "Directory deletion requires recursive=true: $p")
                        children.forEach(tree::remove)
                        tree.remove(p)
                        steps += GoEditStep.Delete(p)
                        preview += GoEditPreview(p, "Delete", display(old!!), "")
                    }
                    else -> throw GoWorkspaceEditRejected("Unsupported resource operation: ${op.kind}")
                }
            } catch (error: GoWorkspaceEditRejected) { throw GoWorkspaceEditRejected(error.message ?: "Invalid workspace edit", index) }
        }
        return GoEditPlan(steps, initial, tree, preview, confirmation)
    }

    fun applyText(original: String, edits: List<TextEdit>): String {
        data class OffsetEdit(val start: Int, val end: Int, val replacement: String, val index: Int)
        val offsets = edits.mapIndexed { index, edit ->
            val start = offset(original, edit.range.start); val end = offset(original, edit.range.end)
            requireEdit(end >= start, "Reversed text edit range")
            OffsetEdit(start, end, edit.newText, index)
        }.sortedWith(compareBy<OffsetEdit> { it.start }.thenBy { it.index })
        var lastEnd = -1
        var replacementStart = -1
        offsets.forEach {
            requireEdit(it.start >= lastEnd, "Overlapping text edits")
            // Same-position insertions are valid only before a replace/delete at that position.
            requireEdit(it.start != replacementStart, "Insertions must precede a replacement at the same position")
            if (it.end > it.start) { lastEnd = it.end; replacementStart = it.start }
        }
        val result = StringBuilder(original)
        offsets.asReversed().forEach { result.replace(it.start, it.end, it.replacement) }
        return result.toString()
    }

    fun offset(text: String, position: Position): Int {
        requireEdit(position.line >= 0 && position.character >= 0, "Negative UTF-16 position")
        var start = 0
        var line = 0
        while (line < position.line) {
            var end = start
            while (end < text.length && text[end] != '\n' && text[end] != '\r') end++
            requireEdit(end < text.length, "UTF-16 line is outside the document")
            start = end + if (text[end] == '\r' && end + 1 < text.length && text[end + 1] == '\n') 2 else 1
            line++
        }
        var end = start
        while (end < text.length && text[end] != '\n' && text[end] != '\r') end++
        requireEdit(position.character <= end - start, "UTF-16 column is outside the line")
        val result = start + position.character
        requireEdit(result == start || result == end || !(text[result - 1].isHighSurrogate() && text[result].isLowSurrogate()), "UTF-16 position splits a surrogate pair")
        return result
    }

    fun text(node: GoEditNode): String = node.text ?: Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(node.bytes)).toString().removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
    private fun display(node: GoEditNode): String = if (node.directory) "Directory" else runCatching { text(node) }.getOrDefault("Binary file (${node.bytes.size} bytes)")
    private fun requireEdit(condition: Boolean, message: String) { if (!condition) throw GoWorkspaceEditRejected(message) }
}
