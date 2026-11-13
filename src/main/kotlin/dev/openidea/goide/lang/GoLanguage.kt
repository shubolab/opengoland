package dev.openidea.goide.lang

import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.LanguageFileType
import dev.openidea.goide.icons.GoIcons
import javax.swing.Icon

object GoLanguage : Language("OpenIDEAGo") {
    override fun getDisplayName() = "Go"
}

class GoFileType private constructor() : LanguageFileType(GoLanguage) {
    override fun getName() = "open Goland"
    override fun getDescription() = "Go source file"
    override fun getDefaultExtension() = "go"
    override fun getIcon(): Icon = GoIcons.Source

    companion object {
        @JvmField val INSTANCE = GoFileType()
    }
}
