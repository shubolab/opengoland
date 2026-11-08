package dev.openidea.goide.generate.tests

import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.FakePsiElement
import com.intellij.util.IncorrectOperationException
import javax.swing.Icon

/**
 * A read-only, named navigation item for our flat Go PSI. Its identifier range is
 * in the analyzed native document's UTF-16 coordinates, including unsaved edits.
 * Old chooser entries expire on edits rather than navigating to stale offsets.
 */
internal class GoTestNavigationDeclaration(
    private val file: PsiFile,
    private val declarationName: String,
    private val receiver: String,
    private val offset: Int,
) : FakePsiElement() {
    private val document: Document? = file.virtualFile?.let { FileDocumentManager.getInstance().getCachedDocument(it) }
    private val documentStamp = document?.modificationStamp
    private val psiStamp = file.modificationStamp
    private val identifierRange = TextRange(offset, offset + declarationName.length)

    override fun getParent(): PsiElement = file
    override fun getContainingFile(): PsiFile = file
    override fun getName(): String = declarationName
    override fun getText(): String = declarationName
    override fun getTextLength(): Int = declarationName.length
    override fun getTextOffset(): Int = offset
    override fun getTextRange(): TextRange = identifierRange
    override fun getStartOffsetInParent(): Int = offset
    override fun getPresentableText(): String = if (receiver.isEmpty()) declarationName else "$receiver.$declarationName"
    override fun getLocationString(): String = file.virtualFile?.path ?: file.name
    override fun getIcon(unused: Boolean): Icon = AllIcons.Nodes.Method

    override fun isValid(): Boolean {
        if (file.project.isDisposed || !file.isValid) return false
        val current = file.virtualFile?.let { FileDocumentManager.getInstance().getCachedDocument(it) }
        return if (document != null) current === document && current.modificationStamp == documentStamp
        else file.modificationStamp == psiStamp && (current == null || PsiDocumentManager.getInstance(file.project).isCommitted(current))
    }

    override fun canNavigate(): Boolean = isValid() && file.virtualFile?.isValid == true
    override fun canNavigateToSource(): Boolean = canNavigate()
    override fun setName(name: String): PsiElement = throw IncorrectOperationException("Use the Go Rename action to change declarations.")

    override fun isEquivalentTo(another: PsiElement?): Boolean = another === this || another is GoTestNavigationDeclaration &&
        isValid() && another.isValid() && file == another.file && identifierRange == another.identifierRange && declarationName == another.declarationName
}
