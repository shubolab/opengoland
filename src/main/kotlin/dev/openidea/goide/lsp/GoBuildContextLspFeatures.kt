package dev.openidea.goide.lsp

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.hints.declarative.impl.DeclarativeInlayHintsPassFactory
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.util.concurrency.AppExecutorUtil
import com.redhat.devtools.lsp4ij.LSPFileSupport
import com.redhat.devtools.lsp4ij.client.features.LSPCodeActionFeature
import com.redhat.devtools.lsp4ij.client.features.LSPDocumentHighlightFeature
import com.redhat.devtools.lsp4ij.client.features.LSPInlayHintFeature
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

internal class GoBuildContextInlayHintFeature : LSPInlayHintFeature() {
    override fun isEnabled(file: PsiFile) = super.isEnabled(file) && GoLspBuildContextEligibility.isEnabled(file)
    override fun isInlayHintSupported(file: PsiFile) = GoLspBuildContextEligibility.isEnabled(file) &&
        (file.language != GoLanguage || (clientFeatures as? GoLspBuildContextEpoch)?.isBuildContextCurrent() != false) &&
        super.isInlayHintSupported(file)
    override fun isResolveInlayHintSupported(file: PsiFile) = GoLspBuildContextEligibility.isEnabled(file) &&
        (file.language != GoLanguage || (clientFeatures as? GoLspBuildContextEpoch)?.isBuildContextCurrent() != false) &&
        super.isResolveInlayHintSupported(file)
}

internal class GoBuildContextDocumentHighlightFeature : LSPDocumentHighlightFeature() {
    override fun isEnabled(file: PsiFile) = super.isEnabled(file) && GoLspBuildContextEligibility.isEnabled(file)
    override fun isDocumentHighlightSupported(file: PsiFile) = GoLspBuildContextEligibility.isEnabled(file) &&
        (file.language != GoLanguage || (clientFeatures as? GoLspBuildContextEpoch)?.isBuildContextCurrent() != false) &&
        super.isDocumentHighlightSupported(file)
}

/** Build membership can change without a source edit; refresh the dependent Go features. */
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
                it.inlayHintsSupport.cancel()
                it.highlightSupport.cancel()
            }
            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed) {
                    // Enumerate current views here so a split/reopened editor cannot miss
                    // the reset. Both APIs only read existing in-memory documents/editors.
                    supports.map { it.file }.filter { it.isValid }.forEach { file ->
                        file.virtualFile?.let { virtualFile ->
                            FileDocumentManager.getInstance().getCachedDocument(virtualFile)?.let { document ->
                                EditorFactory.getInstance().getEditors(document, project)
                                    .filter { !it.isDisposed }.forEach {
                                        // Public platform API resets this editor's declarative
                                        // stamp and requests project-level daemon analysis.
                                        DeclarativeInlayHintsPassFactory.scheduleRecompute(it, project)
                                    }
                            }
                        }
                    }
                    files.filter { it.isValid }.forEach {
                        DaemonCodeAnalyzer.getInstance(project).restart(it)
                    }
                }
            }
        }
    }
}
