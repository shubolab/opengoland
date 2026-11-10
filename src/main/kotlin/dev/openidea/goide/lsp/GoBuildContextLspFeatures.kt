package dev.openidea.goide.lsp

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.util.concurrency.AppExecutorUtil
import com.redhat.devtools.lsp4ij.LSPFileSupport
import com.redhat.devtools.lsp4ij.client.features.LSPCodeActionFeature
import com.redhat.devtools.lsp4ij.client.features.LSPSemanticTokensFeature
import dev.openidea.goide.lang.GoLanguage
import dev.openidea.goide.run.GoRunBuildContextService
import dev.openidea.goide.run.GoRunBuildStatus

/** Per-server initialize options must still describe the current project settings. */
internal interface GoLspBuildContextEpoch {
    fun isBuildContextCurrent(): Boolean
}

/**
 * These package-dependent features wait for authoritative selected-Go membership.
 * lookup is memory-only and coalesces background metadata work. Its existing
 * completion/invalidation path requests daemon analysis for the observing files.
 * Each eligibility check reads current state; CHECKING is not an exclusion.
 * Document synchronization and diagnostics remain attached to the language server.
 */
internal object GoLspBuildContextEligibility {
    fun isEnabled(file: PsiFile): Boolean {
        if (!file.isValid || file.project.isDisposed) return false
        if (file.language != GoLanguage) return true
        return when (GoRunBuildContextService.get(file.project).lookup(file).status) {
            GoRunBuildStatus.CHECKING, GoRunBuildStatus.IGNORED -> false
            // Invalid source or a failed metadata command is not an excluded
            // file. Preserve normal server requests and their error reporting.
            GoRunBuildStatus.ACTIVE, GoRunBuildStatus.INVALID, GoRunBuildStatus.ERROR -> true
        }
    }
}

internal class GoBuildContextSemanticTokensFeature : LSPSemanticTokensFeature() {
    override fun isEnabled(file: PsiFile) = super.isEnabled(file) && GoLspBuildContextEligibility.isEnabled(file)
    override fun isSemanticTokensSupported(file: PsiFile) = GoLspBuildContextEligibility.isEnabled(file) &&
        (file.language != GoLanguage || (clientFeatures as? GoLspBuildContextEpoch)?.isBuildContextCurrent() != false) &&
        super.isSemanticTokensSupported(file)
}

internal class GoBuildContextCodeActionFeature : LSPCodeActionFeature() {
    override fun isEnabled(file: PsiFile) = super.isEnabled(file) && GoLspBuildContextEligibility.isEnabled(file)
    override fun isCodeActionSupported(file: PsiFile) = GoLspBuildContextEligibility.isEnabled(file) &&
        (file.language != GoLanguage || (clientFeatures as? GoLspBuildContextEpoch)?.isBuildContextCurrent() != false) &&
        super.isCodeActionSupported(file)
}

/** Metadata can change without a PSI edit, while these SDK supports cache by PSI stamp. */
internal object GoBuildContextLspRefresh {
    fun refresh(project: Project, files: List<PsiFile>) {
        if (project.isDisposed) return
        AppExecutorUtil.getAppExecutorService().execute {
            if (project.isDisposed) return@execute
            // Read PSI and capture existing supports only. Cancellation can write an LSP
            // $/cancelRequest, so perform it on this pool thread after releasing the read lock.
            val supports = ReadAction.compute<List<LSPFileSupport>, RuntimeException> {
                if (project.isDisposed) emptyList() else files.asSequence()
                    .filter { it.isValid && it.language == GoLanguage && LSPFileSupport.hasSupport(it) }
                    .map { LSPFileSupport.getSupport(it) }.distinct().toList()
            }
            supports.forEach {
                it.semanticTokensSupport.cancel()
                it.intentionCodeActionSupport.cancel()
            }
            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed) files.filter { it.isValid }.forEach {
                    DaemonCodeAnalyzer.getInstance(project).restart(it)
                }
            }
        }
    }
}
