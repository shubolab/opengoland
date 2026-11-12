package dev.openidea.goide.lang

import com.intellij.icons.AllIcons
import com.intellij.lang.Commenter
import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.LanguageFileType

object HttpRequestLanguage : Language("OpenIDEAGoHttpRequest") {
    override fun getDisplayName() = "HTTP Request"
}

class HttpRequestFileType private constructor() : LanguageFileType(HttpRequestLanguage) {
    override fun getName() = "open Goland HTTP Request"
    override fun getDescription() = "HTTP request document"
    override fun getDefaultExtension() = "http"
    override fun getIcon() = AllIcons.FileTypes.Text

    companion object {
        @JvmField val INSTANCE = HttpRequestFileType()
    }
}

class HttpRequestCommenter : Commenter {
    override fun getLineCommentPrefix() = "#"
    override fun getBlockCommentPrefix(): String? = null
    override fun getBlockCommentSuffix(): String? = null
    override fun getCommentedBlockCommentPrefix(): String? = null
    override fun getCommentedBlockCommentSuffix(): String? = null
}
