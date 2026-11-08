package dev.openidea.goide.generate.structs

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiFile
import dev.openidea.goide.generate.GoNamedTypeHeader
import dev.openidea.goide.generate.GoNamedTypeHeaders
import dev.openidea.goide.lang.GoLanguage

abstract class GoStructHelpersAction(private val kind: String) : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(event: AnActionEvent) {
        val file = event.getData(CommonDataKeys.PSI_FILE)
        val editor = event.getData(CommonDataKeys.EDITOR)
        event.presentation.isEnabledAndVisible = file != null && editor != null && GoStructHelpersContext.available(file, editor)
    }
    override fun actionPerformed(event: AnActionEvent) {
        val file = event.getData(CommonDataKeys.PSI_FILE) ?: return
        val editor = event.getData(CommonDataKeys.EDITOR) ?: return
        if (GoStructHelpersContext.available(file, editor)) GoStructHelpersService.get(file.project).invoke(file, editor, kind)
    }
}

class GoGenerateConstructorAction : GoStructHelpersAction("constructor")
class GoGenerateGetterAction : GoStructHelpersAction("getter")
class GoGenerateSetterAction : GoStructHelpersAction("setter")
class GoGenerateGetterSetterAction : GoStructHelpersAction("both")

abstract class GoStructHelpersIntention(private val kind: String, private val label: String) : IntentionAction {
    override fun getText() = label
    override fun getFamilyName() = "Go struct helpers"
    override fun startInWriteAction() = false
    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean =
        editor != null && file != null && GoStructHelpersContext.available(file, editor)
    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        if (editor != null && file != null && isAvailable(project, editor, file))
            GoStructHelpersService.get(project).invoke(file, editor, kind, fromIntention = true)
    }
}

class GoGenerateConstructorIntention : GoStructHelpersIntention("constructor", "Generate constructor")
class GoGenerateGetterIntention : GoStructHelpersIntention("getter", "Generate getter")
class GoGenerateSetterIntention : GoStructHelpersIntention("setter", "Generate setter")
class GoGenerateGetterSetterIntention : GoStructHelpersIntention("both", "Generate getter and setter")

/** No PSI construction, file discovery or semantic process is needed to offer the native entry points. */
internal object GoStructHelpersContext {
    private data class Headers(val stamp: Long, val values: List<GoNamedTypeHeader>)
    private val CACHE = Key.create<Headers>("opengoland.struct.helper.headers")
    fun available(file: PsiFile, editor: Editor): Boolean = ReadAction.compute<Boolean, RuntimeException> {
        if (file.project.isDisposed || !file.isValid || file.language != GoLanguage || editor.isDisposed || editor.isViewer || editor.project !== file.project) return@compute false
        val virtual = file.virtualFile ?: return@compute false
        if (!virtual.isValid || !virtual.isInLocalFileSystem || !virtual.isWritable) return@compute false
        val offset = editor.caretModel.offset
        headers(editor.document).any { !it.alias && offset >= it.nameOffset && offset < it.bodyEnd }
    }
    private fun headers(document: Document): List<GoNamedTypeHeader> {
        val stamp = document.modificationStamp
        document.getUserData(CACHE)?.takeIf { it.stamp == stamp }?.let { return it.values }
        val values = GoNamedTypeHeaders.scan(document.immutableCharSequence, "struct")
        document.putUserData(CACHE, Headers(stamp, values))
        return values
    }
}
