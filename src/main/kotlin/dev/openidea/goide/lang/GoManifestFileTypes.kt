package dev.openidea.goide.lang

import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.LanguageFileType
import dev.openidea.goide.icons.GoIcons

object GoModLanguage : Language("OpenIDEAGoMod") {
    override fun getDisplayName() = "Go Module"
}
object GoWorkLanguage : Language("OpenIDEAGoWork") {
    override fun getDisplayName() = "Go Workspace"
}

class GoModFileType private constructor() : LanguageFileType(GoModLanguage) {
    override fun getName() = "open Goland Module"
    override fun getDescription() = "Go module definition"
    override fun getDefaultExtension() = "mod"
    override fun getIcon() = GoIcons.Module
    companion object { @JvmField val INSTANCE = GoModFileType() }
}
class GoWorkFileType private constructor() : LanguageFileType(GoWorkLanguage) {
    override fun getName() = "open Goland Workspace"
    override fun getDescription() = "Go workspace definition"
    override fun getDefaultExtension() = "work"
    override fun getIcon() = GoIcons.Workspace
    companion object { @JvmField val INSTANCE = GoWorkFileType() }
}
class GoModParserDefinition : GoParserDefinition(GoModLanguage)
class GoWorkParserDefinition : GoParserDefinition(GoWorkLanguage)
