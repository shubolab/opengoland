package dev.openidea.goide.lsp

import com.intellij.openapi.project.Project
import com.redhat.devtools.lsp4ij.client.LanguageClientImpl
import dev.openidea.goide.semantic.GoSemanticOperations
import org.eclipse.lsp4j.ApplyWorkspaceEditParams
import org.eclipse.lsp4j.ApplyWorkspaceEditResponse
import java.util.concurrent.CompletableFuture

/** Server requests use the same atomic edit path as native Go refactor actions. */
class GoLanguageClient(project: Project) : LanguageClientImpl(project) {
    override fun applyEdit(params: ApplyWorkspaceEditParams): CompletableFuture<ApplyWorkspaceEditResponse> {
        if (project.isDisposed) return CompletableFuture.completedFuture(ApplyWorkspaceEditResponse(false).apply { failureReason = "The project is closed" })
        return GoSemanticOperations.get(project).applyServerEdit(params)
    }
}
