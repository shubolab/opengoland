package dev.openidea.goide.lsp

import com.intellij.ide.structureView.StructureViewBuilder
import com.intellij.ide.structureView.StructureViewModel
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiFile
import com.redhat.devtools.lsp4ij.features.documentSymbol.LSPDocumentSymbolStructureViewFactory
import dev.openidea.goide.lang.GoLanguage

/** Keeps the existing LSP capability gate and native Structure hosts. */
class GoStructureViewFactory : LSPDocumentSymbolStructureViewFactory() {
    override fun getStructureViewBuilder(psiFile: PsiFile): StructureViewBuilder? {
        val upstream = super.getStructureViewBuilder(psiFile) ?: return null
        if (psiFile.language != GoLanguage) return upstream
        return object : TreeBasedStructureViewBuilder() {
            override fun createStructureViewModel(editor: Editor?): StructureViewModel = GoStructureViewModel(psiFile, editor)
            override fun isRootNodeShown() = (upstream as? TreeBasedStructureViewBuilder)?.isRootNodeShown ?: true
        }
    }
}
