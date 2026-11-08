package dev.openidea.goide.generate.jsonstruct

import com.google.gson.JsonObject
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiFile
import dev.openidea.goide.engine.EngineClient
import dev.openidea.goide.generate.GoGenerationSnapshot
import dev.openidea.goide.lang.GoLanguage
import dev.openidea.goide.refactor.GoWorkspaceEditService
import dev.openidea.goide.refactor.signature.GoSignatureCodec
import dev.openidea.goide.refactor.signature.GoSignatureValidation
import dev.openidea.goide.refactor.signature.bool
import dev.openidea.goide.semantic.GoSemanticOperations
import dev.openidea.goide.settings.GoToolchain
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/** JSON input belongs to the dialog. Only a validated native transaction can modify Go source. */
@Service(Service.Level.PROJECT)
class GoJsonStructService(private val project: Project) : Disposable {
    private val active = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var disposed = false
    private val work get() = GoSemanticOperations.get(project)
    private val engine get() = project.getService(EngineClient::class.java)
    private inner class InspectionOwner {
        private var closed = false
        private val operations = mutableSetOf<String>()
        @Synchronized fun add(id: String) { if (closed) throw ProcessCanceledException(); operations += id }
        @Synchronized fun remove(id: String) { operations -= id }
        fun close() {
            val cancelled = synchronized(this) { closed = true; operations.toList() }
            cancelled.forEach(engine::cancelOperation)
        }
    }

    fun invoke(file: PsiFile, editor: Editor, mode: String) {
        require(mode in setOf("type", "fields")) { "Choose type or struct field generation" }
        if (file.language != GoLanguage || editor.isDisposed) return
        val snapshot = GoGenerationSnapshot.capture(file, editor, TITLE)
        work.background(TITLE) { indicator ->
            checkCurrent(snapshot)
            val prepared = snapshot.prepare(indicator)
            // No empty JSON request is sent: paste input first, then explicitly analyze it.
            work.later(indicator) { checkCurrent(prepared); showEditor(prepared, mode) }
        }
    }

    private fun showEditor(snapshot: GoGenerationSnapshot, mode: String, draft: JsonObject? = null) {
        checkCurrent(snapshot)
        val inspections = AtomicReference<InspectionOwner?>()
        val initialRequest = JsonObject().apply { addProperty("mode", mode) }
        val dialog = GoJsonStructDialog(project, JsonObject(), mode, initialRequest, draft) { selection, success, failure ->
            val owner = InspectionOwner()
            inspections.getAndSet(owner)?.close()
            work.background("Analyze JSON for Go") { indicator ->
                try {
                    checkCurrent(snapshot)
                    val request = request(snapshot, selection, mode)
                    val response = operation("generate/json/inspect", request, indicator, snapshot.toolchain, owner)
                    indicator.checkCanceled()
                    success(response)
                } catch (_: ProcessCanceledException) { failure("JSON analysis was cancelled. Analyze to try again.") }
                catch (error: Exception) { failure(error.cause?.message ?: error.message ?: "JSON analysis failed") }
            }
        }
        val accepted = try { dialog.showAndGet() } finally { inspections.getAndSet(null)?.close() }
        if (!accepted) return
        val selection = dialog.selection()
        val savedDraft = dialog.draft()
        work.background(TITLE) { indicator ->
            checkCurrent(snapshot)
            val plan = operation("generate/json/plan", request(snapshot, selection, mode), indicator, snapshot.toolchain)
            if (GoSignatureCodec.conflicts(plan).isNotEmpty()) {
                work.later(indicator) {
                    Messages.showErrorDialog(project, GoSignatureCodec.conflicts(plan).joinToString("\n\n"), "JSON to Go Conflicts")
                    showEditor(snapshot, mode, savedDraft)
                }
                return@background
            }
            require(plan.getAsJsonObject("validation")?.bool("typeChecked") == true) { "The generated Go types did not pass type checking" }
            val response = work.await(GoWorkspaceEditService.get(project).apply(
                GoSignatureCodec.workspaceEdit(plan), TITLE, preview = true, request = snapshot.request,
                validation = GoSignatureValidation(project, plan, snapshot.toolchain.key, operationName = TITLE),
                usages = GoSignatureCodec.usages(plan)), indicator)
            if (!response.isApplied && response.failureReason?.contains("cancel", ignoreCase = true) != true)
                error(response.failureReason ?: "The JSON-generated Go changes could not be applied")
        }
    }

    private fun request(snapshot: GoGenerationSnapshot, selection: JsonObject, mode: String) = snapshot.wire.deepCopy().apply {
        selection.entrySet().forEach { (key, value) -> add(key, value.deepCopy()) }
        addProperty("mode", mode)
    }

    private fun operation(method: String, parameters: JsonObject, indicator: ProgressIndicator, settings: GoToolchain.Settings, owner: InspectionOwner? = null): JsonObject {
        val id = UUID.randomUUID().toString()
        val request = parameters.deepCopy().apply { addProperty("operationId", id) }
        owner?.add(id)
        active += id
        var succeeded = false
        return try {
            check(!disposed && !project.isDisposed) { "The Go project is closed" }
            work.await(engine.request(method, request, settings, timeoutSeconds = 180), indicator).asJsonObject.also {
                indicator.checkCanceled(); succeeded = true
            }
        } finally { active -= id; owner?.remove(id); if (!succeeded) engine.cancelOperation(id) }
    }

    private fun checkCurrent(snapshot: GoGenerationSnapshot) {
        check(!disposed && !project.isDisposed) { "The Go project is closed" }
        snapshot.checkCurrent()
    }
    override fun dispose() { disposed = true; active.toList().forEach(engine::cancelOperation); active.clear() }
    companion object {
        private const val TITLE = "Generate Go from JSON"
        fun get(project: Project): GoJsonStructService = project.getService(GoJsonStructService::class.java)
    }
}
