package dev.openidea.goide.navigation

import com.intellij.codeInsight.TargetElementEvaluatorEx2
import com.intellij.lang.Language
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.redhat.devtools.lsp4ij.features.LSPPsiElement
import dev.openidea.goide.lang.GoLanguage
import dev.openidea.goide.lang.GoTokens

/** Supplies a lexical query origin to the platform's existing navigation providers. */
class GoTargetElementEvaluator : TargetElementEvaluatorEx2() {
    override fun adjustReferenceOrReferencedElement(
        file: PsiFile, editor: Editor, offset: Int, flags: Int, refElement: PsiElement?
    ): PsiElement? {
        // Keep a real resolved target if a future Go PSI reference supplies one.
        if (refElement != null) return refElement
        if (file.language != GoLanguage || !file.isValid || file.project.isDisposed) return null
        val document = editor.document
        val documents = PsiDocumentManager.getInstance(file.project)
        // Both a real editor and the platform's symbol bridge use this cached
        // document. Do not load files, commit PSI or start a server in this hook.
        if (documents.getCachedDocument(file) !== document || !documents.isCommitted(document)) return null
        if (offset < 0 || offset > document.textLength) return null
        val leaf = file.findElementAt(offset)?.takeIf(::isIdentifier)
            ?: if (offset > 0) file.findElementAt(offset - 1)?.takeIf {
                isIdentifier(it) && it.textRange.endOffset == offset
            } else null
        if (leaf == null || leaf.text == "_") return null
        return QueryOrigin(file, leaf.textRange, document, document.modificationStamp, leaf.text)
    }

    // The query origin is not itself a discovered implementation. The platform
    // still owns result collection, progress, cancellation, chooser and navigation.
    override fun includeSelfInGotoImplementation(element: PsiElement) = false

    private fun isIdentifier(element: PsiElement) = element.node?.elementType == GoTokens.IDENTIFIER

    private class QueryOrigin(
        private val file: PsiFile,
        range: TextRange,
        private val document: Document,
        private val stamp: Long,
        private val identifier: String
    ) : LSPPsiElement(file, range) {
        override fun getLanguage(): Language = GoLanguage
        override fun getName() = identifier
        override fun getText() = identifier
        override fun isValid() = file.isValid && !file.project.isDisposed && document.modificationStamp == stamp
    }
}
