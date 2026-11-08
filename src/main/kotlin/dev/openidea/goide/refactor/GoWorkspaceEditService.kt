package dev.openidea.goide.refactor

import com.intellij.history.LocalHistory
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.command.UndoConfirmationPolicy
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import dev.openidea.goide.engine.EngineClient
import dev.openidea.goide.lsp.GoDocumentSync
import dev.openidea.goide.lsp.GoSyncedDocument
import org.eclipse.lsp4j.ApplyWorkspaceEditResponse
import org.eclipse.lsp4j.WorkspaceEdit
import java.lang.ref.WeakReference
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

@Service(Service.Level.PROJECT)
class GoWorkspaceEditService(private val project: Project) : Disposable {
    private val knownDocuments = ConcurrentHashMap<Path, WeakReference<Document>>()
    private val pending = ConcurrentHashMap.newKeySet<CompletableFuture<ApplyWorkspaceEditResponse>>()
    private val sequence = AtomicLong()
    private val lastChanged = ConcurrentHashMap<Path, Long>()
    private val transactionChanges = ThreadLocal<MutableMap<Path, Long>?>()
    @Volatile private var disposed = false

    init {
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                FileDocumentManager.getInstance().getFile(event.document)?.takeIf { it.isInLocalFileSystem }?.let { recordChange(it.path) }
            }
        }, this)
        project.messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) { events.forEach { event -> recordChange(event.path) } }
        })
    }

    /** Reads only already-open/unsaved/previously cached documents. Never enumerates project files. */
    fun captureRequest(): GoEditRequestSnapshot = ReadAction.compute<GoEditRequestSnapshot, RuntimeException> {
        val sync = GoDocumentSync.get(project)
        GoEditRequestSnapshot(cachedDocuments().mapKeys { uri(it.key) }.mapValues { (uri, document) ->
            GoEditDocumentStamp(document.modificationStamp, sync.snapshot(uri))
        }, changeSequence = sequence.get())
    }

    fun apply(edit: WorkspaceEdit, title: String, preview: Boolean = false, request: GoEditRequestSnapshot? = null,
              onApplied: ((GoEditRequestSnapshot) -> Unit)? = null,
              validation: GoWorkspaceEditValidation? = null,
              usages: List<GoEditUsage> = emptyList()): CompletableFuture<ApplyWorkspaceEditResponse> {
        val result = GoWorkspaceEditFuture()
        if (disposed || project.isDisposed) { result.complete(rejected("The project is closed")); return result }
        pending += result
        result.whenComplete { _, _ -> pending -= result }
        pooled {
            try {
                validation?.validateBackground()
                val prepared = prepare(edit, request, validation?.additionalEditableRoots().orEmpty())
                if (prepared.plan.steps.isEmpty()) {
                    onEdt(result) { onApplied?.invoke(request ?: captureRequest()); result.complete(ApplyWorkspaceEditResponse(true)) }
                    return@pooled
                }
                onEdt(result) {
                    val approved = !preview && !prepared.plan.requiresConfirmation || GoWorkspaceEditPreviewDialog(project, title, prepared.plan.preview, usages).showAndGet()
                    if (!approved) result.complete(rejected("Workspace edit was cancelled"))
                    else pooled {
                        try {
                            // User interaction is complete. Recheck physical files off the EDT.
                            validateDisk(prepared)
                            validation?.validateBackground()
                            onEdt(result) { commit(prepared, title, request, result, onApplied, validation) }
                        } catch (error: Throwable) { result.complete(rejected(error)) }
                    }
                }
            } catch (error: Throwable) { result.complete(rejected(error)) }
        }
        return result
    }

    private data class DocumentBaseline(val document: Document, val stamp: Long, val text: String, val unsaved: Boolean)
    private data class FileBaseline(val file: VirtualFile, val stamp: Long, val children: Set<String>?)
    private data class Prepared(
        val paths: Set<Path>, val plan: GoEditPlan,
        val files: Map<Path, FileBaseline>, val documents: Map<Path, DocumentBaseline>,
        val synchronized: Map<Path, GoSyncedDocument?>,
        val closedOrigins: Set<Path>, val expectedPhysical: Map<Path, GoEditDiskStamp>,
        val aliases: Set<GoCaseRenameAlias>,
    )

    private fun prepare(edit: WorkspaceEdit, request: GoEditRequestSnapshot?, additionalRoots: Set<Path> = emptySet()): Prepared {
        checkOpen()
        val paths = GoWorkspaceEditPlan.paths(edit)
        val access = editableRoots().let { (roots, excluded) -> (roots + additionalRoots) to excluded }
        fun editable(path: Path) = access.first.any(path::startsWith) && access.second.none(path::startsWith)
        paths.forEach { if (!editable(it)) throw GoWorkspaceEditRejected("Resource is outside the editable Go workspace: $it") }
        val cached = ReadAction.compute<Map<Path, DocumentBaseline>, RuntimeException> {
            cachedDocuments().mapValues { (_, doc) -> DocumentBaseline(doc, doc.modificationStamp, doc.text, FileDocumentManager.getInstance().isDocumentUnsaved(doc)) }
        }
        val sync = GoDocumentSync.get(project)
        val opened = ReadAction.compute<Set<Path>, RuntimeException> { openedPaths() }
        val aliases = GoWorkspaceEditCaseAliases.detect(edit)
        val initial = snapshot(paths, aliases).mapValues { (path, node) ->
            val physical = node.origin ?: path
            val buffer = cached[physical] ?: cached[path]
            val closed = !node.directory && node.disk != null && physical !in opened && sync.snapshot(uri(physical)) == null && buffer?.unsaved != true &&
                (buffer == null || runCatching { GoWorkspaceEditPlan.text(node) == buffer.text }.getOrDefault(false))
            node.copy(text = buffer?.text, closedOnDisk = closed)
        }
        val synchronized = mutableMapOf<Path, GoSyncedDocument?>()
        val plan = GoWorkspaceEditPlan.build(edit, initial, { path ->
            if (path !in synchronized) synchronized[path] = sync.snapshot(uri(path))
            synchronized[path]
        }, aliases, ::editable)
        val textOrigins = plan.steps.filterIsInstance<GoEditStep.Text>().mapNotNull { it.origin }.toSet()
        val files = mutableMapOf<Path, FileBaseline>()
        val documents = cached.filterKeys { it in initial }.toMutableMap()
        // Cache VFS nodes and directory children before the write command; no lazy tree scan in commit.
        initial.forEach { (path, node) ->
            val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
                ?: throw GoWorkspaceEditRejected("A file disappeared while preparing the edit: $path")
            val children = if (node.directory) file.children.map { it.name }.toSet() else null
            if (!file.isWritable && (path in paths || path in textOrigins)) throw GoWorkspaceEditRejected("File is read-only: $path")
            files[path] = FileBaseline(file, file.modificationStamp, children)
            if (path in textOrigins) {
                val baseline = ReadAction.compute<DocumentBaseline, RuntimeException> {
                    val doc = FileDocumentManager.getInstance().getDocument(file)
                        ?: throw GoWorkspaceEditRejected("Cannot edit this file as a text document: $path")
                    DocumentBaseline(doc, doc.modificationStamp, doc.text, FileDocumentManager.getInstance().isDocumentUnsaved(doc))
                }
                val expected = initial[path]?.let(GoWorkspaceEditPlan::text)
                if (baseline.text != expected) throw GoWorkspaceEditRejected("Document changed while preparing the edit: $path")
                documents[path] = baseline
                knownDocuments[path] = WeakReference(baseline.document)
            }
        }
        val resourcePaths = plan.steps.flatMap { step -> when (step) {
            is GoEditStep.Create -> listOf(step.path)
            is GoEditStep.Rename -> listOf(step.old, step.new)
            is GoEditStep.Delete -> listOf(step.path)
            else -> emptyList()
        } }
        if (resourcePaths.isNotEmpty()) {
            val history = LocalHistory.getInstance()
            if (!history.isEnabled) throw GoWorkspaceEditRejected("Native file Undo is unavailable; resource edits were not applied")
            resourcePaths.forEach { path ->
                var current: Path? = path
                while (current != null && current !in files) current = current.parent
                val file = current?.let { files[it]?.file } ?: throw GoWorkspaceEditRejected("Resource parent disappeared: $path")
                // Local History consults the project file index. Its cached
                // model query needs a read lock even on this background worker.
                val controlled = ReadAction.compute<Boolean, RuntimeException> { history.isUnderControl(file) }
                if (!controlled) throw GoWorkspaceEditRejected("Native file Undo does not cover this resource: $path")
            }
        }
        val closed = textOrigins.filter { initial[it]?.closedOnDisk == true && synchronized[it] == null }.toSet()
        val physical = plan.after.filterValues { !it.directory }.mapValues { (_, node) ->
            val content = if (node.origin == null && node.text != null) node.text.toByteArray(Charsets.UTF_8) else node.bytes
            GoEditDiskStamp(content.size.toLong(), hash(content), node.disk?.modifiedAtMillis ?: 0, node.disk?.fileKey)
        }
        val prepared = Prepared(paths, plan, files, documents, synchronized, closed, physical, aliases)
        ReadAction.run<RuntimeException> { validateDocuments(prepared, request) }
        validateDisk(prepared)
        return prepared
    }

    private fun editableRoots(): Pair<Set<Path>, Set<Path>> {
        val roots = ReadAction.compute<MutableSet<Path>, RuntimeException> {
            buildSet {
                project.basePath?.let { add(Path.of(it).toAbsolutePath().normalize()) }
                ProjectRootManager.getInstance(project).contentRoots.filter { it.isInLocalFileSystem }.forEach { add(Path.of(it.path).toAbsolutePath().normalize()) }
                // Files explicitly opened in this project are valid external refactoring targets.
                FileEditorManager.getInstance(project).openFiles.filter { it.isInLocalFileSystem }.forEach {
                    Path.of(it.path).toAbsolutePath().normalize().parent?.let(::add)
                }
            }.toMutableSet()
        }
        val excluded = mutableSetOf<Path>()
        // Main modules in go.work can live outside the project directory. Do not reduce them to basePath.
        val info = project.getService(EngineClient::class.java).request("project/info").get(35, TimeUnit.SECONDS).asJsonObject
        info.getAsJsonArray("modules")?.forEach { entry ->
            val item = entry.asJsonObject
            if (item.get("Main")?.asBoolean == true) item.get("Dir")?.takeUnless { it.isJsonNull }?.asString?.let { roots.add(Path.of(it).toAbsolutePath().normalize()) }
        }
        val env = info.getAsJsonObject("environment")
        listOf("GOROOT", "GOMODCACHE").forEach { key -> env?.get(key)?.takeUnless { it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }?.let { excluded.add(Path.of(it).toAbsolutePath().normalize()) } }
        return roots to excluded
    }

    private fun snapshot(paths: Set<Path>, aliases: Set<GoCaseRenameAlias> = emptySet()): Map<Path, GoEditNode> {
        val result = linkedMapOf<Path, GoEditNode>()
        var bytes = 0L
        var resources = 0
        fun add(path: Path) {
            checkOpen()
            if (path in result || !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
            if (Files.isSymbolicLink(path)) throw GoWorkspaceEditRejected("Symbolic-link resource operations require an explicit resolved target: $path")
            val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            val directory = attributes.isDirectory
            if (!directory && !attributes.isRegularFile) throw GoWorkspaceEditRejected("Unsupported filesystem resource: $path")
            val content = if (directory) byteArrayOf() else {
                val size = attributes.size()
                bytes += size
                if (size > 16L * 1024 * 1024 || bytes > 64L * 1024 * 1024) throw GoWorkspaceEditRejected("Workspace edit exceeds the 16 MiB per-file / 64 MiB transaction limit")
                Files.readAllBytes(path)
            }
            val after = if (directory) null else Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            if (after != null && (attributes.size() != after.size() || attributes.lastModifiedTime() != after.lastModifiedTime() || attributes.fileKey() != after.fileKey()))
                throw GoWorkspaceEditRejected("File changed while reading its closed-document baseline: $path")
            val disk = after?.let { GoEditDiskStamp(content.size.toLong(), hash(content), it.lastModifiedTime().toMillis(), it.fileKey()?.toString()) }
            result[path] = GoEditNode(directory, content, origin = path, disk = disk)
            if (++resources > 20_000) throw GoWorkspaceEditRejected("Workspace edit contains too many filesystem resources")
        }
        paths.forEach { path ->
            val alias = aliases.firstOrNull { it.new == path && it.old in result }
            if (path !in result && alias != null) {
                // Reuse the same physical bytes and original document identity for alternate
                // spellings; directory aliases must not double the backup byte/resource budget.
                result.filterKeys { it.startsWith(alias.old) }.forEach { (source, node) ->
                    result[path.resolve(alias.old.relativize(source))] = node
                }
            } else if (path !in result) {
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) Files.walk(path).use { stream -> stream.forEach(::add) } else add(path)
            }
            var parent = path.parent
            while (parent != null) { add(parent); parent = parent.parent }
        }
        return result
    }

    private fun validateDisk(prepared: Prepared) {
        checkOpen()
        prepared.aliases.forEach { alias ->
            if (GoWorkspaceEditCaseAliases.actualEntryName(alias.old, alias.new) != alias.originalName)
                throw GoWorkspaceEditRejected("Case-only rename source identity or filename changed while pending: ${alias.old}")
        }
        val actual = snapshot(prepared.paths, prepared.aliases)
        val expected = prepared.plan.before
        if (actual.keys != expected.keys || actual.any { (path, node) -> node.directory != expected[path]?.directory || node.disk != expected[path]?.disk || !node.bytes.contentEquals(expected[path]!!.bytes) })
            throw GoWorkspaceEditRejected("Files changed since this workspace edit was prepared; request it again")
    }

    private fun validateDocuments(prepared: Prepared, request: GoEditRequestSnapshot?) {
        checkOpen()
        val sync = GoDocumentSync.get(project)
        val opened = openedPaths()
        prepared.closedOrigins.forEach { path ->
            if (path in opened || sync.snapshot(uri(path)) != null) throw GoWorkspaceEditRejected("A closed document was opened while its disk edit was pending: $path")
            val disk = prepared.plan.before[path]?.disk ?: throw GoWorkspaceEditRejected("Closed-document disk baseline is unavailable: $path")
            GoWorkspaceEditGuard.validateClosedDisk(path, disk, request)
        }
        if (request != null && lastChanged.any { (path, at) -> GoWorkspaceEditGuard.unacknowledged(path, at, request) && prepared.paths.any { path == it || path.startsWith(it) || it.startsWith(path) } })
            throw GoWorkspaceEditRejected("Workspace documents or resources changed after the language-server request; request the edit again")
        prepared.documents.forEach { (path, baseline) ->
            val document = baseline.document
            val captured = request?.documents?.get(uri(path))
            GoWorkspaceEditGuard.validate(path, baseline.stamp, baseline.text, document.modificationStamp, document.text, captured, sync.snapshot(uri(path)))
        }
        // Explicitly versioned edits must still match the live server mirror at commit time.
        prepared.synchronized.forEach { (origin, expected) ->
            val current = sync.snapshot(uri(origin))
            if (current != expected) throw GoWorkspaceEditRejected("Language-server document version/session changed before commit: $origin")
            if (current != null && current.text != prepared.documents[origin]?.text)
                throw GoWorkspaceEditRejected("The language server is not synchronized with the IDE document: $origin")
        }
    }

    private fun commit(prepared: Prepared, title: String, request: GoEditRequestSnapshot?, result: GoWorkspaceEditFuture,
                       onApplied: ((GoEditRequestSnapshot) -> Unit)?, validation: GoWorkspaceEditValidation?) {
        if (!result.beginCommit()) return
        try {
            val ownedChanges = linkedMapOf<Path, Long>()
            val baseline = request ?: GoEditRequestSnapshot(emptyMap(), changeSequence = sequence.get())
            WriteCommandAction.writeCommandAction(project).withName(title).withGroupId(UUID.randomUUID().toString())
                .withGlobalUndo().withUndoConfirmationPolicy(UndoConfirmationPolicy.DO_NOT_REQUEST_CONFIRMATION).run<RuntimeException> {
                    validateDocuments(prepared, request)
                    validation?.validateDocuments()
                    prepared.files.forEach { (path, baseline) ->
                        if (!baseline.file.isValid || baseline.file.modificationStamp != baseline.stamp)
                            throw GoWorkspaceEditRejected("VFS resource changed before commit: $path")
                        if (baseline.children != null && baseline.file.children.map { it.name }.toSet() != baseline.children)
                            throw GoWorkspaceEditRejected("Directory contents changed before commit: $path")
                    }
                    prepared.paths.filter { it !in prepared.plan.before }.forEach { path ->
                        if (find(path) != null) throw GoWorkspaceEditRejected("A workspace edit target appeared before commit: $path")
                    }
                    // No custom UndoableAction: both native providers record this single command.
                    if (prepared.documents.isNotEmpty()) CommandProcessor.getInstance().addAffectedDocuments(project, *prepared.documents.values.map { it.document }.toTypedArray())
                    val journal = GoWorkspaceEditJournal()
                    transactionChanges.set(ownedChanges)
                    try { GoWorkspaceEditNativeExecutor(this, prepared.documents.mapValues { it.value.document }).execute(prepared.plan, prepared.aliases, journal) }
                    catch (failure: Throwable) {
                        try { journal.rollback() }
                        catch (rollback: Throwable) {
                            failure.addSuppressed(rollback)
                            throw GoWorkspaceEditRejected("Workspace edit failed (${failure.message}); rollback also failed (${rollback.message}). Inspect the affected files before continuing.")
                        }
                        throw GoWorkspaceEditRejected("Workspace edit failed and was rolled back: ${failure.message}")
                    }
                    finally { transactionChanges.remove() }
                }
            val advanced = acknowledgedRequest(prepared, baseline, ownedChanges)
            try { onApplied?.invoke(advanced) }
            catch (error: Throwable) { Logger.getInstance(GoWorkspaceEditService::class.java).error("Workspace edit was applied but its request-baseline callback failed", error) }
            result.complete(ApplyWorkspaceEditResponse(true))
        } catch (error: Throwable) { result.complete(rejected(error)) }
    }

    private fun acknowledgedRequest(prepared: Prepared, baseline: GoEditRequestSnapshot, owned: Map<Path, Long>): GoEditRequestSnapshot {
        fun affected(path: Path): Boolean = prepared.plan.steps.any { step -> when (step) {
            is GoEditStep.Text -> path == step.path || path == step.origin
            is GoEditStep.Directory -> path == step.path
            is GoEditStep.Create -> path == step.path
            is GoEditStep.Delete -> path.startsWith(step.path)
            is GoEditStep.Rename -> path.startsWith(step.old) || path.startsWith(step.new)
        } }
        val documents = cachedDocuments().filterKeys(::affected).mapKeys { uri(it.key) }.mapValues { (uri, doc) ->
            val synced = GoDocumentSync.get(project).snapshot(uri)?.takeIf { it.text == doc.text }
            GoEditDocumentStamp(doc.modificationStamp, synced)
        }
        val removed = baseline.documents.keys.filter { value -> runCatching { affected(GoWorkspaceEditPlan.path(value)) }.getOrDefault(false) }.toSet()
        val advanced = GoWorkspaceEditGuard.acknowledge(baseline, owned.filterKeys(::affected).mapKeys { uri(it.key) }, removed, documents)
        val retainedDisk = baseline.acceptedDisk.filterKeys { value -> !runCatching { affected(GoWorkspaceEditPlan.path(value)) }.getOrDefault(false) }
        val currentDisk = prepared.expectedPhysical.filterKeys(::affected).mapNotNull { (path, expected) ->
            find(path)?.takeIf { it.isValid && !it.isDirectory }?.let { uri(path) to expected.copy(modifiedAtMillis = it.timeStamp) }
        }.toMap()
        return advanced.copy(acceptedDisk = retainedDisk + currentDisk)
    }

    private fun cachedDocuments(): Map<Path, Document> {
        val manager = FileDocumentManager.getInstance()
        val documents = (EditorFactory.getInstance().allEditors.map { it.document } + manager.unsavedDocuments + knownDocuments.values.mapNotNull { it.get() }).distinct()
        return documents.mapNotNull { document -> manager.getFile(document)?.takeIf { it.isValid && it.isInLocalFileSystem }?.let { Path.of(it.path).toAbsolutePath().normalize() to document } }.toMap()
    }
    private fun openedPaths(): Set<Path> = EditorFactory.getInstance().allEditors.mapNotNull { editor ->
        FileDocumentManager.getInstance().getFile(editor.document)?.takeIf { it.isValid && it.isInLocalFileSystem }?.let { Path.of(it.path).toAbsolutePath().normalize() }
    }.toSet()
    private fun find(path: Path): VirtualFile? = LocalFileSystem.getInstance().findFileByNioFile(path)
    private fun uri(path: Path): String = GoDocumentSync.fileUri(path)
    private fun checkOpen() { if (disposed || project.isDisposed) throw GoWorkspaceEditRejected("The project is closed") }
    private fun pooled(action: () -> Unit) = ApplicationManager.getApplication().executeOnPooledThread(action)
    private fun onEdt(result: CompletableFuture<ApplyWorkspaceEditResponse>, action: () -> Unit) {
        ApplicationManager.getApplication().invokeLater {
            if (result.isDone) return@invokeLater
            try { checkOpen(); WriteIntentReadAction.run(Runnable(action)) } catch (error: Throwable) { result.complete(rejected(error)) }
        }
    }
    private fun recordChange(path: String) { runCatching { Path.of(path).toAbsolutePath().normalize() }.getOrNull()?.let {
        val changedAt = sequence.incrementAndGet()
        lastChanged[it] = changedAt
        transactionChanges.get()?.set(it, changedAt)
    } }
    override fun dispose() { disposed = true; knownDocuments.clear(); lastChanged.clear(); pending.forEach { it.complete(rejected("The project is closed")) }; pending.clear() }
    companion object {
        fun get(project: Project): GoWorkspaceEditService = project.getService(GoWorkspaceEditService::class.java)
        private fun rejected(error: Throwable): ApplyWorkspaceEditResponse = rejected(error.cause?.message ?: error.message ?: error.javaClass.simpleName).apply {
            if (error is GoWorkspaceEditRejected) failedChange = error.failedChange
        }
        private fun rejected(message: String) = ApplyWorkspaceEditResponse(false).apply { failureReason = message }
        private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
