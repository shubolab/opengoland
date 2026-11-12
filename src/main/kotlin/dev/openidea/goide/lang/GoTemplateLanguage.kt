package dev.openidea.goide.lang

import com.intellij.lang.Commenter
import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.LanguageFileType

object GoTemplateLanguage : Language("OpenIDEAGoTemplate") {
    override fun getDisplayName() = "Go Template"
}

class GoTemplateFileType private constructor() : LanguageFileType(GoTemplateLanguage) {
    override fun getName() = "open Goland Go Template"
    override fun getDescription() = "Go text/template file"
    override fun getDefaultExtension() = "gotmpl"
    override fun getIcon() = GoFileType.INSTANCE.icon

    companion object {
        @JvmField val INSTANCE = GoTemplateFileType()
    }
}

/** Go templates only have block comments enclosed by an action. */
class GoTemplateCommenter : Commenter {
    override fun getLineCommentPrefix(): String? = null
    override fun getBlockCommentPrefix() = "{{/*"
    override fun getBlockCommentSuffix() = "*/}}"
    override fun getCommentedBlockCommentPrefix(): String? = null
    override fun getCommentedBlockCommentSuffix(): String? = null
}
