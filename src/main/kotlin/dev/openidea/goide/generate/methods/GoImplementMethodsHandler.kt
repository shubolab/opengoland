package dev.openidea.goide.generate.methods

import com.intellij.lang.LanguageCodeInsightActionHandler
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import dev.openidea.goide.lang.GoLanguage

/** Native Code > Implement Methods and the platform shortcut share this handler. */
class GoImplementMethodsHandler : LanguageCodeInsightActionHandler {
    override fun isValidFor(editor: Editor, file: PsiFile): Boolean =
        !file.project.isDisposed && !editor.isDisposed && file.language == GoLanguage &&
            file.virtualFile?.let { it.isInLocalFileSystem && it.isWritable } == true

    override fun startInWriteAction() = false

    override fun invoke(project: Project, editor: Editor, file: PsiFile) {
        if (isValidFor(editor, file)) GoImplementMethodsService.get(project).invoke(file, editor)
    }
}
