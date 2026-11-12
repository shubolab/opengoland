package dev.openidea.goide.lang

import com.intellij.icons.AllIcons
import com.intellij.lang.BracePair
import com.intellij.lang.Commenter
import com.intellij.lang.Language
import com.intellij.lang.PairedBraceMatcher
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType

object DockerfileLanguage : Language("OpenIDEAGoDockerfile") {
    override fun getDisplayName() = "Dockerfile"
}

class DockerfileFileType private constructor() : LanguageFileType(DockerfileLanguage) {
    override fun getName() = "open Goland Dockerfile"
    override fun getDescription() = "Dockerfile"
    override fun getDefaultExtension() = "dockerfile"
    override fun getIcon() = AllIcons.FileTypes.Text

    companion object {
        @JvmField val INSTANCE = DockerfileFileType()
    }
}

class DockerfileCommenter : Commenter {
    override fun getLineCommentPrefix() = "#"
    override fun getBlockCommentPrefix(): String? = null
    override fun getBlockCommentSuffix(): String? = null
    override fun getCommentedBlockCommentPrefix(): String? = null
    override fun getCommentedBlockCommentSuffix(): String? = null
}

class DockerfileBraceMatcher : PairedBraceMatcher {
    override fun getPairs() = arrayOf(
        BracePair(DockerfileTokens.LBRACE, DockerfileTokens.RBRACE, false),
        BracePair(DockerfileTokens.LBRACKET, DockerfileTokens.RBRACKET, false),
        BracePair(DockerfileTokens.LPAREN, DockerfileTokens.RPAREN, false),
    )
    override fun isPairedBracesAllowedBeforeType(lbraceType: IElementType, contextType: IElementType?) = true
    override fun getCodeConstructStart(file: PsiFile, openingBraceOffset: Int) = openingBraceOffset
}
