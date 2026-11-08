package dev.openidea.goide.semantic

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.progress.util.ProgressIndicatorUtils
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.redhat.devtools.lsp4ij.LSPIJUtils
import com.redhat.devtools.lsp4ij.LanguageServerItem
import com.redhat.devtools.lsp4ij.LanguageServerManager
import com.redhat.devtools.lsp4ij.features.codeAction.CodeActionData
import com.redhat.devtools.lsp4ij.features.codeAction.intention.LSPIntentionCodeActionSupport
import com.intellij.psi.PsiFile
import dev.openidea.goide.refactor.GoEditRequestSnapshot
import dev.openidea.goide.refactor.GoWorkspaceEditService
import org.eclipse.lsp4j.ApplyWorkspaceEditParams
import org.eclipse.lsp4j.ApplyWorkspaceEditResponse
import org.eclipse.lsp4j.CodeActionContext
import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.Command
import org.eclipse.lsp4j.ExecuteCommandParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.PrepareRenameParams
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.RenameParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.WorkspaceEdit
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock

internal data class GoSemanticContext(val file: PsiFile, val editor: Editor?, val document: Document, val uri: String, val range: Range, val stamp: Long, val word: String) {
    fun checkCurrent() {
        ReadAction.run<RuntimeException> {
            check(file.isValid && editor?.isDisposed != true) { "The Go editor was closed" }
            check(document.modificationStamp == stamp) { "The document changed. Run the action again." }
        }
    }
}

/** Native entry points share cancellation, versioned edits, preview and command ownership. */
@Service(Service.Level.PROJECT)
class GoSemanticOperations(private val project: Project) : Disposable {
    private val futures = ConcurrentHashMap.newKeySet<CompletableFuture<*>>()
    private val commandLock = ReentrantLock()
    private data class CommandEdit(val title: String, @Volatile var request: GoEditRequestSnapshot, val preview: Boolean, val owner: GoCommandEditOwner = GoCommandEditOwner())
    private val commandEdit = AtomicReference<CommandEdit?>()
    @Volatile private var disposed = false

    internal fun background(title: String, operation: (ProgressIndicator) -> Unit) {
        object : Task.Backgroundable(project, title, true) {
            override fun run(indicator: ProgressIndicator) {
                checkOpen()
                operation(indicator)
            }
            override fun onThrowable(error: Throwable) {
                if (disposed || project.isDisposed || error is ProcessCanceledException) return
                var cause = error
                while (cause.cause != null && (cause is java.util.concurrent.ExecutionException || cause is java.util.concurrent.CompletionException)) cause = cause.cause!!
                if (cause !is ProcessCanceledException && cause !is java.util.concurrent.CancellationException)
                    WriteIntentReadAction.run { Messages.showErrorDialog(project, cause.message ?: cause.javaClass.simpleName, title) }
            }
        }.queue()
    }

    internal fun later(action: () -> Unit) {
        ApplicationManager.getApplication().invokeLater({
            if (!disposed && !project.isDisposed) WriteIntentReadAction.run {
                try { action() } catch (error: ProcessCanceledException) { throw error }
                catch (error: RuntimeException) { Messages.showErrorDialog(project, error.message ?: "The Go action could not finish", "Go Refactoring") }
            }
        }, project.disposed)
    }

    internal fun <T> await(future: CompletableFuture<T>, indicator: ProgressIndicator): T {
        checkOpen()
        futures += future
        return try { ProgressIndicatorUtils.awaitWithCheckCanceled(future, indicator) }
        catch (error: Throwable) { future.cancel(true); throw error }
        finally { futures -= future }
    }

    internal fun actions(context: GoSemanticContext, kind: String, indicator: ProgressIndicator): List<CodeActionData> {
        context.checkCurrent()
        val params = CodeActionParams(TextDocumentIdentifier(context.uri), context.range, CodeActionContext(emptyList(), listOf(kind)))
        return await(LSPIntentionCodeActionSupport(context.file).getCodeActions(params), indicator)
            .filter { it.languageServer().serverDefinition.id == "openidea.gopls" && (it.codeAction().isLeft || it.codeAction().right.disabled == null) }
    }

    internal fun applyAction(data: CodeActionData, title: String, preview: Boolean, request: GoEditRequestSnapshot, indicator: ProgressIndicator) {
        val item = data.languageServer()
        var action = if (data.codeAction().isRight) data.codeAction().right else null
        if (action?.data != null && item.serverCapabilities.codeActionProvider?.let { it.isRight && it.right.resolveProvider == true } == true) {
            val unresolved = action
            val server = initialized(item, indicator)
            action = await(server.textDocumentService.resolveCodeAction(unresolved), indicator)
        }
        check(action?.disabled == null) { action?.disabled?.reason ?: "This refactoring is unavailable" }
        val command = action?.command ?: data.codeAction().takeIf { it.isLeft }?.left
        check(command?.command != "gopls.change_signature") { "Use Refactor > Change Signature to preview typed Go signature changes." }
        var baseline = request
        action?.edit?.let { baseline = apply(it, title, preview, baseline, indicator) }
        if (command != null) execute(item, command, title, preview, baseline, indicator)
    }

    private fun execute(item: LanguageServerItem, command: Command, title: String, preview: Boolean, request: GoEditRequestSnapshot, indicator: ProgressIndicator) {
        check(item.supportsCommand(command)) { "gopls does not advertise command ${command.command}" }
        while (!commandLock.tryLock(100, TimeUnit.MILLISECONDS)) { indicator.checkCanceled(); checkOpen() }
        try {
            indicator.checkCanceled()
            val context = CommandEdit(title, request, preview)
            commandEdit.set(context)
            val server = initialized(item, indicator)
            val response = await(server.workspaceService.executeCommand(ExecuteCommandParams(command.command, command.arguments)), indicator)
            // Most gopls commands use workspace/applyEdit. Support a typed edit response as well.
            if (response is WorkspaceEdit) apply(response, title, preview, context.request, indicator)
        } finally { commandEdit.getAndSet(null)?.owner?.close(); commandLock.unlock() }
    }

    internal fun apply(edit: WorkspaceEdit, title: String, preview: Boolean, request: GoEditRequestSnapshot, indicator: ProgressIndicator): GoEditRequestSnapshot {
        var advanced = request
        val result = await(edits().apply(edit, title, preview, request, onApplied = { advanced = it }), indicator)
        if (!result.isApplied && result.failureReason == "Workspace edit was cancelled") throw ProcessCanceledException()
        check(result.isApplied) { result.failureReason ?: "The Go workspace edit was rejected" }
        return advanced
    }

    /** Called by our language client for server-initiated edits, including resolved commands. */
    fun applyServerEdit(params: ApplyWorkspaceEditParams): CompletableFuture<ApplyWorkspaceEditResponse> {
        val context = commandEdit.get()
        return if (context != null) context.owner.enqueue {
            edits().apply(params.edit, params.label ?: context.title, context.preview, context.request, onApplied = { context.request = it })
        } else edits().apply(params.edit, params.label ?: "Go Workspace Edit", true)
    }

    internal fun organize(context: GoSemanticContext) = background("Optimize Go Imports") { indicator ->
        val request = edits().captureRequest()
        val action = actions(context, "source.organizeImports", indicator).firstOrNull() ?: return@background
        context.checkCurrent()
        applyAction(action, "Optimize Go Imports", false, request, indicator)
    }

    internal fun rename(context: GoSemanticContext, showName: (String) -> Unit) = background("Prepare Go Rename") { indicator ->
        val item = server(indicator)
        val server = initialized(item, indicator)
        val prepared = if (item.isPrepareRenameSupported) await(server.textDocumentService.prepareRename(PrepareRenameParams(TextDocumentIdentifier(context.uri), context.range.start)), indicator) else null
        context.checkCurrent()
        check(!item.isPrepareRenameSupported || prepared != null) { "This Go symbol cannot be renamed" }
        val placeholder = if (prepared?.isSecond == true) prepared.second.placeholder else context.word
        later { context.checkCurrent(); showName(placeholder) }
    }

    internal fun rename(context: GoSemanticContext, name: String) = background("Rename Go Symbol") { indicator ->
        context.checkCurrent()
        val request = edits().captureRequest()
        val item = server(indicator)
        val server = initialized(item, indicator)
        val edit = await(server.textDocumentService.rename(RenameParams(TextDocumentIdentifier(context.uri), context.range.start, name)), indicator) ?: error("gopls returned no rename edits")
        apply(edit, "Rename Go Symbol to $name", true, request, indicator)
    }

    private fun server(indicator: ProgressIndicator): LanguageServerItem =
        await(LanguageServerManager.getInstance(project).getLanguageServer("openidea.gopls"), indicator)
            ?: error("gopls is not available. Check Go settings and the language server status.")
    // Initialization is shared by every editor. Cancel only our dependent future.
    private fun initialized(item: LanguageServerItem, indicator: ProgressIndicator) =
        await(item.initializedServer.thenApply { it }, indicator)
    internal fun edits() = GoWorkspaceEditService.get(project)
    private fun checkOpen() { if (disposed || project.isDisposed) throw ProcessCanceledException() }
    override fun dispose() { disposed = true; futures.forEach { it.cancel(true) }; futures.clear(); commandEdit.getAndSet(null)?.owner?.close() }

    companion object {
        fun get(project: Project): GoSemanticOperations = project.getService(GoSemanticOperations::class.java)
        internal fun context(file: PsiFile, editor: Editor?): GoSemanticContext = ReadAction.compute<GoSemanticContext, RuntimeException> {
            val document = editor?.document ?: com.intellij.psi.PsiDocumentManager.getInstance(file.project).getDocument(file) ?: error("No Go text document")
            val caret = editor?.caretModel?.offset ?: 0
            val start = editor?.selectionModel?.selectionStart ?: caret
            val end = editor?.selectionModel?.selectionEnd ?: caret
            GoSemanticContext(file, editor, document, LSPIJUtils.toUriAsString(file) ?: error("Go actions require a local file"),
                Range(LSPIJUtils.toPosition(start, document), LSPIJUtils.toPosition(end, document)), document.modificationStamp,
                GoIdentifiers.wordAt(document.charsSequence, caret))
        }
    }
}
