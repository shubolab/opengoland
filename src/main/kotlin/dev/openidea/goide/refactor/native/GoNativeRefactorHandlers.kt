package dev.openidea.goide.refactor.native

import com.intellij.lang.ContextAwareActionHandler
import com.intellij.ide.TitledHandler
import com.intellij.lang.Language
import com.intellij.lang.refactoring.InlineActionHandler
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.refactoring.RefactoringActionHandler
import dev.openidea.goide.lang.GoLanguage

/** Standard menu/shortcut/Refactor This handlers; availability never starts a tool. */
class GoNativeExtractHandler(private val kind: String) : RefactoringActionHandler, ContextAwareActionHandler, TitledHandler {
    override fun getActionTitle() = GoNativeRefactorDialog.titleFor(kind)
    override fun isAvailableForQuickList(editor: Editor, file: PsiFile, context: DataContext) =
        file.language == GoLanguage && !editor.isDisposed && !editor.isViewer
    override fun invoke(project: Project, editor: Editor, file: PsiFile, context: DataContext) {
        if (file.language == GoLanguage) GoNativeRefactorService.get(project).invoke(file, editor, kind)
    }
    override fun invoke(project: Project, elements: Array<out PsiElement>, context: DataContext) {
        val editor = CommonDataKeys.EDITOR.getData(context) ?: return
        val file = CommonDataKeys.PSI_FILE.getData(context) ?: elements.firstOrNull()?.containingFile ?: return
        invoke(project, editor, file, context)
    }
}

/** Flat PSI is routed through a typed operation, rather than GenericInlineHandler PSI rewrites. */
class GoInlineActionHandler : InlineActionHandler() {
    override fun isEnabledForLanguage(language: Language) = language == GoLanguage
    override fun canInlineElement(element: PsiElement) = element !is PsiFile && element.containingFile?.language == GoLanguage
    override fun isEnabledOnElement(element: PsiElement, editor: Editor?) = editor != null &&
        !editor.isDisposed && !editor.isViewer && canInlineElement(element)
    override fun canInlineElementInEditor(element: PsiElement, editor: Editor) = isEnabledOnElement(element, editor)
    override fun inlineElement(project: Project, editor: Editor?, element: PsiElement) {
        editor ?: return
        val file = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) ?: return
        if (file.language == GoLanguage) GoNativeRefactorService.get(project).invoke(file, editor, "inline")
    }
    override fun getActionName(element: PsiElement) = "Inline Go…"
}
