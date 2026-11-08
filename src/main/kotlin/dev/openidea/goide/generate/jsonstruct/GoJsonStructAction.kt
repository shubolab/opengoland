package dev.openidea.goide.generate.jsonstruct

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import dev.openidea.goide.generate.structs.GoStructHelpersContext
import dev.openidea.goide.generate.tags.GoStructTagContext
import dev.openidea.goide.lang.GoLanguage

abstract class GoJsonStructAction(private val mode: String) : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(event: AnActionEvent) {
        val file = event.getData(CommonDataKeys.PSI_FILE)
        val editor = event.getData(CommonDataKeys.EDITOR)
        event.presentation.isEnabledAndVisible = file != null && editor != null && available(file, editor, mode)
    }
    override fun actionPerformed(event: AnActionEvent) {
        val file = event.getData(CommonDataKeys.PSI_FILE) ?: return
        val editor = event.getData(CommonDataKeys.EDITOR) ?: return
        if (available(file, editor, mode)) GoJsonStructService.get(file.project).invoke(file, editor, mode)
    }
}
class GoGenerateJsonTypeAction : GoJsonStructAction("type")
class GoGenerateJsonFieldsAction : GoJsonStructAction("fields")

class GoGenerateJsonFieldsIntention : IntentionAction {
    override fun getText() = "Generate fields from JSON…"
    override fun getFamilyName() = "Go fields from JSON"
    override fun startInWriteAction() = false
    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean =
        file != null && editor != null && available(file, editor, "fields")
    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        if (file != null && editor != null && isAvailable(project, editor, file)) GoJsonStructService.get(project).invoke(file, editor, "fields")
    }
}

private fun available(file: PsiFile, editor: Editor, mode: String): Boolean {
    if (file.project.isDisposed || !file.isValid || file.language != GoLanguage || editor.isDisposed || editor.isViewer || editor.project !== file.project) return false
    val virtual = file.virtualFile ?: return false
    if (!virtual.isValid || !virtual.isInLocalFileSystem || !virtual.isWritable) return false
    return mode == "type" || GoStructHelpersContext.available(file, editor) ||
        GoStructTagContext.contains(editor.document.immutableCharSequence, editor.caretModel.offset)
}
