package dev.openidea.goide.lsp

import com.intellij.ide.structureView.StructureViewTreeElement
import com.redhat.devtools.lsp4ij.client.features.LSPDocumentSymbolFeature
import com.redhat.devtools.lsp4ij.features.documentSymbol.DocumentSymbolData
import com.redhat.devtools.lsp4ij.features.documentSymbol.LSPDocumentSymbolStructureViewModel
import com.intellij.psi.PsiFile
import dev.openidea.goide.lang.GoLanguage
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.SymbolKind

/** Carries gopls signature/type details into the native Structure presentation. */
class GoDocumentSymbolFeature : LSPDocumentSymbolFeature() {
    override fun getPresentableText(documentSymbol: DocumentSymbol, psiFile: PsiFile): String? =
        if (psiFile.language == GoLanguage) formattedText(documentSymbol, psiFile) ?: super.getPresentableText(documentSymbol, psiFile)
        else super.getPresentableText(documentSymbol, psiFile)

    override fun getLocationString(documentSymbol: DocumentSymbol, psiFile: PsiFile): String? =
        if (psiFile.language == GoLanguage && (formattedText(documentSymbol, psiFile) != null ||
                documentSymbol.kind == SymbolKind.Struct || documentSymbol.kind == SymbolKind.Interface)) null
        else super.getLocationString(documentSymbol, psiFile)

    override fun getStructureViewTreeElement(documentSymbol: DocumentSymbolData): StructureViewTreeElement? =
        if (documentSymbol.containingFile?.language == GoLanguage) GoDocumentSymbolTreeElement(documentSymbol)
        else super.getStructureViewTreeElement(documentSymbol)

    private fun formattedText(symbol: DocumentSymbol, file: PsiFile): String? {
        val detail = symbol.detail?.takeIf { it.isNotBlank() } ?: return null
        return when (symbol.kind) {
            SymbolKind.Function, SymbolKind.Method, SymbolKind.Constructor ->
                if (symbol.kind == SymbolKind.Function && symbol.range?.start == symbol.selectionRange?.start &&
                    GoStructureSource.create(file)?.typeAlias(symbol) != null) "${symbol.name}: $detail"
                else if (detail.startsWith("func(")) symbol.name + detail.removePrefix("func") else null
            SymbolKind.Field, SymbolKind.Variable, SymbolKind.Constant, SymbolKind.Class, SymbolKind.TypeParameter ->
                "${symbol.name}: $detail"
            else -> null
        }
    }
}

private class GoDocumentSymbolTreeElement(symbol: DocumentSymbolData) :
    LSPDocumentSymbolStructureViewModel.LSPDocumentSymbolViewElement(symbol) {
    // Keep the upstream name, kind/icon, child factory, selection-range navigation
    // and document-symbol lifecycle; the SDK renders this as secondary text.
    override fun getLocationString(): String? = element?.locationString
}
