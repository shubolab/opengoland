package dev.openidea.goide.semantic

import com.intellij.lang.ImportOptimizer
import com.intellij.ide.TitledHandler
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.refactoring.rename.RenameHandler
import com.redhat.devtools.lsp4ij.features.codeAction.CodeActionData
import dev.openidea.goide.lang.GoLanguage
import dev.openidea.goide.lang.GoTokens
import dev.openidea.goide.refactor.signature.GoChangeSignatureService

internal object GoIdentifiers {
    fun valid(value: String): Boolean {
        if (value.isEmpty() || value == "_" || value in GoTokens.keywords) return false
        val points = value.codePoints().toArray()
        return (points[0] == '_'.code || Character.isLetter(points[0])) && points.drop(1).all { it == '_'.code || Character.isLetter(it) || Character.isDigit(it) }
    }
    fun wordAt(text: CharSequence, offset: Int): String {
        if (offset !in 0..text.length || text.isEmpty()) return ""
        var start = offset
        var end = offset
        fun part(cp: Int) = cp == '_'.code || Character.isLetter(cp) || Character.isDigit(cp)
        while (start > 0 && part(Character.codePointBefore(text, start))) start -= Character.charCount(Character.codePointBefore(text, start))
        while (end < text.length && part(Character.codePointAt(text, end))) end += Character.charCount(Character.codePointAt(text, end))
        return text.subSequence(start, end).toString()
    }
}

class GoImportOptimizer : ImportOptimizer {
    override fun supports(file: PsiFile) = file.language == GoLanguage
    override fun processFile(file: PsiFile) = Runnable {
        GoSemanticOperations.get(file.project).organize(GoSemanticOperations.context(file, null))
    }
}

class GoRenameHandler : RenameHandler, TitledHandler {
    override fun getActionTitle() = "Go Symbol"
    override fun isAvailableOnDataContext(dataContext: DataContext): Boolean {
        val file = CommonDataKeys.PSI_FILE.getData(dataContext) ?: return false
        val editor = CommonDataKeys.EDITOR.getData(dataContext) ?: return false
        return file.language == GoLanguage && GoIdentifiers.valid(GoIdentifiers.wordAt(editor.document.charsSequence, editor.caretModel.offset))
    }
    override fun invoke(project: Project, editor: Editor, file: PsiFile, dataContext: DataContext) {
        if (file.language != GoLanguage) return
        val context = GoSemanticOperations.context(file, editor)
        val service = GoSemanticOperations.get(project)
        service.rename(context) { initial ->
            val name = Messages.showInputDialog(project, "New name:", "Rename Go Symbol", null, initial, object : InputValidator {
                override fun checkInput(inputString: String) = GoIdentifiers.valid(inputString)
                override fun canClose(inputString: String) = checkInput(inputString)
            }) ?: return@rename
            if (name != initial) service.rename(context, name)
        }
    }
    override fun invoke(project: Project, elements: Array<out PsiElement>, dataContext: DataContext) {
        val editor = CommonDataKeys.EDITOR.getData(dataContext) ?: return
        val file = CommonDataKeys.PSI_FILE.getData(dataContext) ?: return
        invoke(project, editor, file, dataContext)
    }
}

class GoRefactorAction : DumbAwareAction() {
    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = event.getData(CommonDataKeys.PSI_FILE)?.language == GoLanguage && event.getData(CommonDataKeys.EDITOR) != null
    }
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val file = event.getData(CommonDataKeys.PSI_FILE) ?: return
        val editor = event.getData(CommonDataKeys.EDITOR) ?: return
        val context = GoSemanticOperations.context(file, editor)
        val service = GoSemanticOperations.get(project)
        service.background("Go Refactor") { indicator ->
            val request = service.edits().captureRequest()
            val actions = listOf(Choice(null)) + service.actions(context, "refactor", indicator).filter { data ->
                val command = data.codeAction().let { if (it.isLeft) it.left else it.right.command }
                command?.command != "gopls.change_signature"
            }.map(::Choice)
            service.later {
                if (actions.isEmpty()) Messages.showInfoMessage(project, "gopls has no refactoring for this selection.", "Go Refactor")
                else JBPopupFactory.getInstance().createPopupChooserBuilder(actions).setTitle("Go Refactor")
                    .setItemChosenCallback { choice ->
                        if (choice.data == null) {
                            context.checkCurrent()
                            project.getService(GoChangeSignatureService::class.java).invoke(file, editor)
                        } else service.background(choice.toString()) { progress ->
                            context.checkCurrent()
                            service.applyAction(choice.data, choice.toString(), true, request, progress)
                        }
                    }.createPopup().showInBestPositionFor(editor)
            }
        }
    }
    private class Choice(val data: CodeActionData?) {
        override fun toString(): String = data?.let { if (it.codeAction().isRight) it.codeAction().right.title else it.codeAction().left.title } ?: "Change Signature…"
    }
}
