package dev.openidea.goide.generate.tests

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.testIntegration.TestCreator
import dev.openidea.goide.lang.GoLanguage

/** The standard Go to Test action delegates generation to the same native preview workflow. */
class GoTestCreator : TestCreator, DumbAware {
    override fun isAvailable(project: Project, editor: Editor, file: PsiFile): Boolean =
        !project.isDisposed && file.language == GoLanguage && !file.name.endsWith("_test.go") &&
            file.virtualFile?.let { it.isInLocalFileSystem && !it.isDirectory } == true

    override fun createTest(project: Project, editor: Editor, file: PsiFile) {
        if (isAvailable(project, editor, file)) GoGenerateTestsService.get(project).invoke(file, editor, initialScope = "function")
    }
}
