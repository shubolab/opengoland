package dev.openidea.goide.debug

import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.redhat.devtools.lsp4ij.dap.DAPDebugProcess
import com.redhat.devtools.lsp4ij.dap.DAPDebuggerEditorsProvider
import com.redhat.devtools.lsp4ij.dap.evaluation.DAPExpressionCodeFragment

/** Keep DAP's expression context while using the platform's normal editable PSI lifecycle. */
class GoDebuggerEditorsProvider(fileType: FileType?, private val dapProcess: DAPDebugProcess?) :
    DAPDebuggerEditorsProvider(fileType, dapProcess) {
    override fun createExpressionCodeFragment(project: Project, text: String, fileType: FileType, language: Language): PsiFile =
        GoDapExpressionCodeFragment(text, fileType, language, dapProcess, project)
}

internal class GoDapExpressionCodeFragment(text: CharSequence, fileType: FileType, language: Language,
    debugProcess: DAPDebugProcess?, project: Project) : DAPExpressionCodeFragment(text, fileType, language, debugProcess, project) {
    // LSP4IJ 0.21 hard-codes false although its view provider enables document events.
    // Restore PsiFileImpl's rule so an uncommitted editor document is retrieved from
    // the cache, rather than being compared to an older PSI tree as a new document.
    // The LightVirtualFile remains in memory and keeps its DAP fragment identity.
    override fun isPhysical() = viewProvider.isEventSystemEnabled
}
