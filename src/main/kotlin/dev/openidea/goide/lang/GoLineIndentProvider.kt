package dev.openidea.goide.lang

import com.intellij.application.options.CodeStyle
import com.intellij.lang.Language
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.codeStyle.lineIndent.LineIndentProvider

/** Fast Go indentation uses the live UTF-16 document, including incomplete code. */
class GoLineIndentProvider : LineIndentProvider {
    override fun isSuitableFor(language: Language?) = language == GoLanguage
    override fun getLineIndent(project: Project, editor: Editor, language: Language?, offset: Int): String? {
        if (!isSuitableFor(language)) return null
        val file = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) ?: return null
        val indent = CodeStyle.getIndentOptions(file)
        val options = GoIndentContext.Options(indent.USE_TAB_CHARACTER, indent.TAB_SIZE, indent.INDENT_SIZE, indent.CONTINUATION_INDENT_SIZE, indent.SMART_TABS)
        return GoIndentContext(editor.document.charsSequence).lineIndent(offset, options) ?: LineIndentProvider.DO_NOT_ADJUST
    }
}
