package dev.openidea.goide.refactor.signature

import com.intellij.lang.refactoring.RefactoringSupportProvider
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.refactoring.changeSignature.ChangeSignatureHandler
import dev.openidea.goide.lang.GoLanguage

class GoRefactoringSupportProvider : RefactoringSupportProvider() {
    override fun isAvailable(context: PsiElement) = context.containingFile?.language == GoLanguage || context is PsiFile && context.language == GoLanguage
    override fun getChangeSignatureHandler(): ChangeSignatureHandler = GoChangeSignatureHandler()
}

/** Fast local availability; Go type/package work begins only after the native action is invoked. */
class GoChangeSignatureHandler : ChangeSignatureHandler {
    override fun findTargetMember(element: PsiElement): PsiElement? = element.containingFile?.takeIf { it.language == GoLanguage }
    override fun findTargetMember(file: PsiFile, editor: Editor): PsiElement? = file.takeIf { it.language == GoLanguage }
    override fun getTargetNotFoundMessage() = "Place the caret on a Go function or method declaration, call, or function value."
    override fun invoke(project: Project, editor: Editor, file: PsiFile, dataContext: DataContext) {
        if (file.language == GoLanguage) project.getService(GoChangeSignatureService::class.java).invoke(file, editor)
    }
    override fun invoke(project: Project, elements: Array<out PsiElement>, dataContext: DataContext?) {
        dataContext ?: return
        val file = CommonDataKeys.PSI_FILE.getData(dataContext) ?: elements.firstOrNull()?.containingFile ?: return
        val editor = CommonDataKeys.EDITOR.getData(dataContext) ?: return
        invoke(project, editor, file, dataContext)
    }
}
