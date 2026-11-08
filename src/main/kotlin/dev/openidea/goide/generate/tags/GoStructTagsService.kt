package dev.openidea.goide.generate.tags

import com.google.gson.JsonObject
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Editor
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

/** The typed planner returns edits; the native transaction alone changes documents. */
@Service(Service.Level.PROJECT)
class GoStructTagsService(private val project: Project) : Disposable {
    private val active = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var disposed = false
    private val work get() = GoSemanticOperations.get(project)
    private val engine get() = project.getService(EngineClient::class.java)

    fun invoke(file: PsiFile, editor: Editor) {
        if (file.language != GoLanguage || editor.isDisposed) return
        val snapshot = GoGenerationSnapshot.capture(file, editor, TITLE)
        work.background(TITLE) { indicator ->
            checkCurrent(snapshot)
            val prepared = snapshot.prepare(indicator)
            val inspection = operation("generate/tags/inspect", prepared.wire, indicator, snapshot.toolchain)
            work.later(indicator) { checkCurrent(snapshot); showEditor(prepared, inspection) }
        }
    }

    private fun showEditor(snapshot: GoGenerationSnapshot, inspection: JsonObject, draft: JsonObject? = null) {
        checkCurrent(snapshot)
        val dialog = GoStructTagsDialog(project, inspection, draft)
        if (!dialog.showAndGet()) return
        val selection = dialog.selection()
        val savedDraft = dialog.draft()
        work.background(TITLE) { indicator ->
            checkCurrent(snapshot)
            val request = snapshot.wire.deepCopy().apply { selection.entrySet().forEach { (key, value) -> add(key, value.deepCopy()) } }
            val plan = operation("generate/tags/plan", request, indicator, snapshot.toolchain)
            if (GoSignatureCodec.conflicts(plan).isNotEmpty()) {
                work.later(indicator) {
                    Messages.showErrorDialog(project, GoSignatureCodec.conflicts(plan).joinToString("\n\n"), "Struct Tags Conflicts")
                    showEditor(snapshot, inspection, savedDraft)
                }
                return@background
            }
            require(plan.getAsJsonObject("validation")?.bool("typeChecked") == true) { "The struct tag changes did not pass Go type checking" }
            val response = work.await(GoWorkspaceEditService.get(project).apply(
                GoSignatureCodec.workspaceEdit(plan), TITLE, preview = true, request = snapshot.request,
                validation = GoSignatureValidation(project, plan, snapshot.toolchain.key, operationName = TITLE),
                usages = GoSignatureCodec.usages(plan)), indicator)
            if (!response.isApplied && response.failureReason?.contains("cancel", ignoreCase = true) != true)
                error(response.failureReason ?: "The struct tag changes could not be applied")
        }
    }

    private fun operation(method: String, parameters: JsonObject, indicator: ProgressIndicator, settings: GoToolchain.Settings): JsonObject {
        val id = UUID.randomUUID().toString()
        val request = parameters.deepCopy().apply { addProperty("operationId", id) }
        active += id
        var succeeded = false
        return try {
            check(!disposed && !project.isDisposed) { "The Go project is closed" }
            work.await(engine.request(method, request, settings, timeoutSeconds = 180), indicator).asJsonObject.also { succeeded = true }
        } finally { active -= id; if (!succeeded) engine.cancelOperation(id) }
    }

    private fun checkCurrent(snapshot: GoGenerationSnapshot) {
        check(!disposed && !project.isDisposed) { "The Go project is closed" }
        snapshot.checkCurrent()
    }

    override fun dispose() { disposed = true; active.toList().forEach(engine::cancelOperation); active.clear() }
    companion object {
        private const val TITLE = "Edit Go Struct Tags"
        fun get(project: Project): GoStructTagsService = project.getService(GoStructTagsService::class.java)
    }
}
