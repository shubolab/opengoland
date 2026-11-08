package dev.openidea.goide.generate.tests

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAwareAction
import dev.openidea.goide.lang.GoLanguage

abstract class GoGenerateTestsScopeAction(private val initialScope: String) : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        val file = event.getData(CommonDataKeys.PSI_FILE)
        event.presentation.isEnabledAndVisible = event.project?.isDisposed == false && file?.language == GoLanguage &&
            file.virtualFile?.let { it.isInLocalFileSystem && it.isWritable && !it.isDirectory } == true
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project?.takeUnless { it.isDisposed } ?: return
        val file = event.getData(CommonDataKeys.PSI_FILE)?.takeIf { it.language == GoLanguage } ?: return
        GoGenerateTestsService.get(project).invoke(file, event.getData(CommonDataKeys.EDITOR), initialScope = initialScope)
    }
}

class GoGenerateTestsAction : GoGenerateTestsScopeAction("function")
class GoGenerateEmptyTestFileAction : GoGenerateTestsScopeAction("empty")
class GoGenerateFunctionTestAction : GoGenerateTestsScopeAction("function")
class GoGenerateFileTestsAction : GoGenerateTestsScopeAction("file")
class GoGeneratePackageTestsAction : GoGenerateTestsScopeAction("package")
