package dev.openidea.goide.refactor.native

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.redhat.devtools.lsp4ij.LanguageServerItem
import com.redhat.devtools.lsp4ij.LanguageServiceAccessor
import com.redhat.devtools.lsp4ij.LSPIJUtils
import com.redhat.devtools.lsp4ij.client.features.LSPClientFeatures
import com.redhat.devtools.lsp4ij.features.codeAction.CodeActionData
import dev.openidea.goide.engine.EngineClient
import dev.openidea.goide.lang.GoLanguage
import dev.openidea.goide.lsp.GoDocumentSymbolFeature
import dev.openidea.goide.lsp.GoDocumentSync
import dev.openidea.goide.refactor.GoWorkspaceEditService
import dev.openidea.goide.refactor.signature.GoSignatureCodec
import dev.openidea.goide.refactor.signature.array
import dev.openidea.goide.refactor.signature.bool
import dev.openidea.goide.refactor.signature.string
import dev.openidea.goide.semantic.GoSemanticContext
import dev.openidea.goide.semantic.GoSemanticOperations
import dev.openidea.goide.settings.GoToolchain
import org.eclipse.lsp4j.Command
import org.eclipse.lsp4j.ExecuteCommandParams
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.jsonrpc.messages.Either
import java.security.MessageDigest
import java.nio.file.Path
import java.net.URI
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Predicate

/** Standard native refactorings share one immutable typed/named plan and atomic preview. */
@Service(Service.Level.PROJECT)
class GoNativeRefactorService(private val project: Project) : Disposable {
    private val active = ConcurrentHashMap<String, EngineClient>()
    @Volatile private var disposed = false
    @Volatile private var ownedEngine: EngineClient? = null
    private val work get() = GoSemanticOperations.get(project)
    private val gson = Gson()

    fun invoke(file: PsiFile, editor: Editor, kind: String) = begin(file, editor, kind, null, null)

    internal fun invokeAction(file: PsiFile, editor: Editor, action: CodeActionData, context: GoSemanticContext) {
        context.checkCurrent()
        val kind = actionKind(action).removePrefix("refactor.")
        begin(file, editor, kind, action, rangeJson(context.range))
    }

    private fun begin(file: PsiFile, editor: Editor, kind: String, chosen: CodeActionData?, selection: JsonObject?) {
        if (file.language != GoLanguage || editor.isDisposed || editor.isViewer) return
        val captured = GoNativeRefactorSnapshot.capture(file, editor)
        work.background(TITLE) { indicator ->
            var snapshot = captured.prepare(indicator)
            var input = request(snapshot, kind, selection ?: snapshot.selection)
            var context = operation("refactor/native/inspect", input, indicator, snapshot.toolchain)
            // Actual dirty metadata may reveal external go.work members. Add
            // only buffers under the engine's proved main roots before querying
            // gopls, including closed unsaved sources. Other projects are omitted.
            repeat(3) {
                val included = snapshot.includeCoverage(context)
                if (included.wire.toString() != snapshot.wire.toString()) {
                    snapshot = included
                    input = request(snapshot, kind, selection ?: snapshot.selection)
                    context = operation("refactor/native/inspect", input, indicator, snapshot.toolchain)
                }
            }
            snapshot.checkCurrent()
            requireNoConflicts(context)
            snapshot = snapshot.withSourceDigest(context.string("sourceDigest"))
            val selected = selection ?: snapshot.selection
            val empty = selected.getAsJsonObject("start") == selected.getAsJsonObject("end")
            if (chosen == null && (empty || kind == "inline")) {
                val targets = context.array("targets").map { Target(it.asJsonObject) }
                require(targets.isNotEmpty()) { "Choose a complete Go expression, statements, or an inline use supported by this action." }
                work.later(indicator) {
                    snapshot.checkCurrent()
                    if (targets.size == 1) chooseTarget(file, editor, snapshot, targets[0])
                    else JBPopupFactory.getInstance().createPopupChooserBuilder(targets)
                        .setTitle("Choose Go Refactoring Target")
                        .setItemChosenCallback { target -> snapshot.checkCurrent(); chooseTarget(file, editor, snapshot, target) }
                        .createPopup().showInBestPositionFor(editor)
                }
            } else work.later(indicator) { snapshot.checkCurrent(); chooseActions(file, editor, snapshot, kind, selected, chosen) }
        }
    }

    private fun chooseTarget(file: PsiFile, editor: Editor, snapshot: GoNativeRefactorSnapshot, target: Target) {
        if (target.value.string("uri") == snapshot.targetUri) {
            chooseActions(file, editor, snapshot, target.kind, target.range, null)
            return
        }
        work.background(TITLE) { indicator ->
            snapshot.checkCurrent()
            val virtual = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(Path.of(URI(target.value.string("uri"))))
                ?: error("The selected inline usage file disappeared")
            val pair = ReadAction.compute<Pair<PsiFile, Int>, RuntimeException> {
                val source = PsiManager.getInstance(project).findFile(virtual) ?: error("The inline usage has no Go source")
                val document = FileDocumentManager.getInstance().getDocument(virtual) ?: error("The inline usage has no document")
                source to LSPIJUtils.toOffset(parseRange(target.range).start, document)
            }
            work.later(indicator) {
                snapshot.checkCurrent()
                val selectedEditor = FileEditorManager.getInstance(project).openTextEditor(OpenFileDescriptor(project, virtual, pair.second), true)
                    ?: error("The inline usage editor could not open")
                begin(pair.first, selectedEditor, target.kind, null, target.range)
            }
        }
    }

    private fun chooseActions(file: PsiFile, editor: Editor, captured: GoNativeRefactorSnapshot,
                              kind: String, selection: JsonObject, chosen: CodeActionData?) {
        if (kind.startsWith("inline.") && kind.endsWith("-all")) {
            work.background(TITLE) { indicator ->
                captured.checkCurrent()
                val input = request(captured, kind, selection)
                val inspection = operation("refactor/native/inspect", input, indicator, captured.toolchain)
                requireNoConflicts(inspection)
                work.later(indicator) { captured.checkCurrent(); showNames(editor, captured, input, inspection) }
            }
            return
        }
        work.background(TITLE) { indicator ->
            synchronize(captured, file, indicator)
            val snapshot = captured.synchronized()
            val range = parseRange(selection)
            val context = GoSemanticOperations.context(file, editor).copy(range = range)
            // The custom chooser's old item identifies a kind, not an edit to
            // replay. Requery after the typed context/synchronization baseline.
            val candidates = work.actions(context, if (kind.startsWith("inline")) "refactor.inline" else "refactor.extract", indicator)
                .filter { accepts(kind, actionKind(it)) && (chosen == null || actionKind(it) == actionKind(chosen)) }
            snapshot.checkCurrent()
            require(candidates.isNotEmpty()) { "gopls has no supported ${GoNativeRefactorDialog.titleFor(kind)} candidate for this selection. Source is unchanged." }
            work.later(indicator) {
                snapshot.checkCurrent()
                val choices = candidates.map { Candidate(it, kind) }
                if (choices.size == 1) inspectCandidate(file, editor, snapshot, choices[0], selection)
                else JBPopupFactory.getInstance().createPopupChooserBuilder(choices).setTitle("Choose Go Refactoring")
                    .setItemChosenCallback { choice -> snapshot.checkCurrent(); inspectCandidate(file, editor, snapshot, choice, selection) }
                    .createPopup().showInBestPositionFor(editor)
            }
        }
    }

    private fun inspectCandidate(file: PsiFile, editor: Editor, snapshot: GoNativeRefactorSnapshot,
                                 candidate: Candidate, selection: JsonObject) {
        work.background(TITLE) { indicator ->
            snapshot.checkCurrent()
            val edit = resolve(candidate.data, indicator)
            snapshot.checkCurrent()
            val source = GoDocumentSync.get(project).snapshot(snapshot.targetUri)
            require(source != null && source.text == snapshot.sourceText) { "The Go source and synchronized language-server input differ. Invoke the refactoring again." }
            val current = snapshot.synchronized()
            val input = request(current, candidate.kind, selection).apply {
                add("candidateEdit", edit)
                addProperty("candidateKind", actionKind(candidate.data))
                addProperty("candidateSourceSha256", sha256(current.sourceText))
            }
            val inspection = operation("refactor/native/inspect", input, indicator, current.toolchain)
            requireNoConflicts(inspection)
            work.later(indicator) { current.checkCurrent(); showNames(editor, current, input, inspection) }
        }
    }

    private fun showNames(editor: Editor, snapshot: GoNativeRefactorSnapshot, input: JsonObject,
                          inspection: JsonObject, draft: JsonObject? = null) {
        snapshot.checkCurrent()
        val dialog = GoNativeRefactorDialog(project, inspection, input.string("kind"), draft)
        if (!dialog.showAndGet()) return
        val chosen = dialog.selection()
        work.background(TITLE) { indicator ->
            snapshot.checkCurrent()
            val plan = operation("refactor/native/plan", input.deepCopy().apply {
                chosen.entrySet().forEach { (key, value) -> add(key, value.deepCopy()) }
            }, indicator, snapshot.toolchain)
            if (GoSignatureCodec.conflicts(plan).isNotEmpty()) {
                work.later(indicator) {
                    snapshot.checkCurrent()
                    Messages.showErrorDialog(project, GoSignatureCodec.conflicts(plan).joinToString("\n\n"), "Go Refactoring Conflicts")
                    showNames(editor, snapshot, input, inspection, chosen)
                }
                return@background
            }
            require(plan.getAsJsonObject("validation")?.bool("typeChecked") == true) { "The refactoring did not pass candidate Go type checking" }
            val result = work.await(GoWorkspaceEditService.get(project).apply(
                GoSignatureCodec.workspaceEdit(plan), GoNativeRefactorDialog.titleFor(input.string("kind")), true,
                snapshot.request, validation = snapshot.validation(plan), usages = GoSignatureCodec.usages(plan)), indicator)
            if (!result.isApplied && result.failureReason?.contains("cancel", ignoreCase = true) != true)
                error(result.failureReason ?: "The Go refactoring could not be applied")
        }
    }

    private fun synchronize(snapshot: GoNativeRefactorSnapshot, file: PsiFile, indicator: ProgressIndicator) {
        snapshot.checkCurrent()
        val go = Predicate<LSPClientFeatures> { it.documentSymbolFeature is GoDocumentSymbolFeature }
        val accessor = LanguageServiceAccessor.getInstance(project)
        val items = mutableListOf<LanguageServerItem>()
        for (virtual in snapshot.filesToSynchronize()) {
            val connection = ReadAction.compute<CompletableFuture<List<LanguageServerItem>>?, RuntimeException> {
                PsiManager.getInstance(project).findFile(virtual)?.let { accessor.getLanguageServers(it, go, go) }
            } ?: continue
            items += work.await(connection.thenApply { it }, indicator)
        }
        val matched = items.filter { it.serverDefinition.id == "openidea.gopls" }.distinctBy { it.serverWrapper }
        require(matched.isNotEmpty()) { "The selected Go source has no initialized gopls connection" }
        for (item in matched) for (document in item.serverWrapper.openedDocuments) document.synchronizer?.let { sync ->
            work.await(sync.didOpenFuture.thenCompose { sync.flushPendingChanges() }.thenApply { Unit }, indicator)
        }
        snapshot.checkCurrent()
    }

    /** ApplyFix is explicitly resolved to edits; no server/applyEdit is allowed here. */
    private fun resolve(data: CodeActionData, indicator: ProgressIndicator): JsonObject {
        val item = data.languageServer()
        var action = data.codeAction().takeIf { it.isRight }?.right
        val server = work.await(item.initializedServer.thenApply { it }, indicator)
        if (action?.data != null && item.serverCapabilities.codeActionProvider?.let { it.isRight && it.right.resolveProvider == true } == true)
            action = work.await(server.textDocumentService.resolveCodeAction(action), indicator)
        require(action?.disabled == null) { action?.disabled?.reason ?: "The refactoring is unavailable" }
        action?.edit?.let { return encode(it) }
        val command = action?.command ?: data.codeAction().takeIf { it.isLeft }?.left ?: error("gopls returned no refactoring edit")
        require(command.command == "gopls.apply_fix" && item.supportsCommand(command)) { "This native operation requires an edit-resolving gopls.apply_fix candidate" }
        require(command.arguments?.size == 1) { "Unexpected Go refactoring command arguments" }
        val args = gson.toJsonTree(command.arguments[0]).asJsonObject.deepCopy()
        require(args.string("Fix") in FIXES) { "Unsupported Go refactoring fix" }
        args.addProperty("ResolveEdits", true)
        val result = work.await(server.workspaceService.executeCommand(ExecuteCommandParams(command.command, listOf(args))), indicator)
            ?: error("gopls returned no resolved workspace edit")
        return if (result is WorkspaceEdit) encode(result) else canonical(gson.toJsonTree(result).asJsonObject)
    }

    private fun encode(edit: WorkspaceEdit): JsonObject {
        require(edit.changeAnnotations.isNullOrEmpty()) { "Annotated refactoring edits need explicit confirmation support" }
        val documents = JsonArray()
        edit.changes?.forEach { (uri, edits) -> documents.add(document(uri, null, edits)) }
        edit.documentChanges?.forEach { entry ->
            require(entry.isLeft) { "This native refactoring does not perform file operations" }
            val value = entry.left
            val payloads: List<*> = value.edits
            val edits = payloads.map { payload -> when (payload) {
                is TextEdit -> payload
                is Either<*, *> -> payload.left as? TextEdit ?: error("Snippet workspace edits are unsupported")
                else -> error("Unsupported workspace text-edit payload")
            } }
            documents.add(document(value.textDocument.uri, value.textDocument.version, edits))
        }
        return JsonObject().apply { add("documentChanges", documents) }
    }
    private fun document(uri: String, version: Int?, edits: List<TextEdit>) = JsonObject().apply {
        add("textDocument", JsonObject().apply { addProperty("uri", uri); add("version", version?.let { com.google.gson.JsonPrimitive(it) } ?: JsonNull.INSTANCE) })
        add("edits", JsonArray().apply { edits.forEach { edit -> add(JsonObject().apply { add("range", rangeJson(edit.range)); addProperty("newText", edit.newText) }) } })
    }
    private fun canonical(raw: JsonObject): JsonObject {
        if (raw.has("changes")) {
            val documents = raw.getAsJsonArray("documentChanges") ?: JsonArray()
            raw.getAsJsonObject("changes").entrySet().forEach { (uri, edits) ->
                documents.add(JsonObject().apply { add("textDocument", JsonObject().apply { addProperty("uri", uri); add("version", JsonNull.INSTANCE) }); add("edits", edits.deepCopy()) })
            }
            raw.remove("changes"); raw.add("documentChanges", documents)
        }
        require(raw.getAsJsonArray("documentChanges")?.size()?.let { it > 0 } == true) { "gopls returned no document changes" }
        require(raw.getAsJsonObject("changeAnnotations")?.size()?.let { it > 0 } != true) { "Annotated edits require additional confirmation support" }
        return raw
    }

    private fun request(snapshot: GoNativeRefactorSnapshot, kind: String, selection: JsonObject) = snapshot.wire.deepCopy().apply {
        addProperty("kind", kind); add("selection", selection.deepCopy())
    }
    private fun operation(method: String, params: JsonObject, indicator: ProgressIndicator, settings: GoToolchain.Settings): JsonObject {
        indicator.checkCanceled()
        check(!disposed && !project.isDisposed) { "The Go project is closed" }
        val connection = ownedEngine ?: project.getService(EngineClient::class.java).also { ownedEngine = it }
        val id = UUID.randomUUID().toString(); active[id] = connection; var succeeded = false
        return try {
            check(!disposed && !project.isDisposed) { "The Go project is closed" }
            val future = connection.request(method, params.deepCopy().apply { addProperty("operationId", id) }, settings, timeoutSeconds = 180)
            if (disposed || project.isDisposed || indicator.isCanceled) {
                connection.cancelOperation(id)
                future.cancel(false)
                indicator.checkCanceled()
                error("The Go project is closed")
            }
            work.await(future, indicator)
                .asJsonObject.also { succeeded = true }
        } finally { active.remove(id); if (!succeeded) runCatching { connection.cancelOperation(id) } }
    }
    private fun requireNoConflicts(inspection: JsonObject) {
        require(GoSignatureCodec.conflicts(inspection).isEmpty()) { GoSignatureCodec.conflicts(inspection).joinToString("\n\n") }
    }
    override fun dispose() {
        disposed = true
        active.entries.toList().forEach { (id, connection) -> runCatching { connection.cancelOperation(id) } }
        active.clear()
    }

    private data class Target(val value: JsonObject) {
        val kind get() = value.string("kind")
        val range get() = value.getAsJsonObject("range")
        override fun toString() = value.string("text").replace('\n', ' ') + "  — ${value.string("uri").substringAfterLast('/')}:${range.getAsJsonObject("start").get("line").asInt + 1}:${range.getAsJsonObject("start").get("character").asInt + 1}"
    }
    private data class Candidate(val data: CodeActionData, val requested: String) {
        val kind: String get() {
            val raw = actionKind(data).removePrefix("refactor.")
            return if (requested.startsWith("extract.variable") && raw.startsWith("extract.constant")) raw.replace("extract.constant", "extract.variable") else raw
        }
        override fun toString(): String {
            val title = data.codeAction().let { if (it.isRight) it.right.title else it.left.title }
            return if (requested.startsWith("extract.variable") && actionKind(data).startsWith("refactor.extract.constant")) "$title → variable" else title
        }
    }

    companion object {
        private const val TITLE = "Go Refactoring"
        private val FIXES = setOf("extract_variable", "extract_variable_all", "extract_function", "extract_method", "inline_call", "inline_variable")
        fun get(project: Project): GoNativeRefactorService = project.getService(GoNativeRefactorService::class.java)
        internal fun recognizes(data: CodeActionData) = actionKind(data) in setOf("refactor.extract.variable", "refactor.extract.variable-all", "refactor.extract.constant", "refactor.extract.constant-all", "refactor.extract.function", "refactor.extract.method", "refactor.inline.call", "refactor.inline.variable")
        private fun actionKind(data: CodeActionData): String {
            data.codeAction().takeIf { it.isRight }?.right?.kind?.let { if (it.startsWith("refactor.")) return it }
            val command = data.codeAction().let { if (it.isRight) it.right.command else it.left }
            val fix = command?.arguments?.firstOrNull()?.let { Gson().toJsonTree(it) }?.takeIf { it.isJsonObject }?.asJsonObject?.string("Fix")
            return when (fix) { "extract_variable" -> "refactor.extract.variable"; "extract_variable_all" -> "refactor.extract.variable-all"; "extract_function" -> "refactor.extract.function"; "extract_method" -> "refactor.extract.method"; "inline_call" -> "refactor.inline.call"; "inline_variable" -> "refactor.inline.variable"; else -> "" }
        }
        private fun accepts(requested: String, kind: String) = when {
            requested.startsWith("extract.variable") -> kind in setOf("refactor.extract.variable", "refactor.extract.variable-all", "refactor.extract.constant", "refactor.extract.constant-all")
            requested.startsWith("extract.constant") -> kind in setOf("refactor.extract.constant", "refactor.extract.constant-all")
            requested.startsWith("extract.function") || requested.startsWith("extract.method") -> kind in setOf("refactor.extract.function", "refactor.extract.method")
            requested == "inline" -> kind in setOf("refactor.inline.call", "refactor.inline.variable")
            else -> kind == "refactor.$requested"
        }
        private fun rangeJson(range: Range) = JsonObject().apply {
            fun position(p: org.eclipse.lsp4j.Position) = JsonObject().apply { addProperty("line", p.line); addProperty("character", p.character) }
            add("start", position(range.start)); add("end", position(range.end))
        }
        private fun parseRange(value: JsonObject) = Range(value.getAsJsonObject("start").let { org.eclipse.lsp4j.Position(it.get("line").asInt, it.get("character").asInt) },
            value.getAsJsonObject("end").let { org.eclipse.lsp4j.Position(it.get("line").asInt, it.get("character").asInt) })
        private fun sha256(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
