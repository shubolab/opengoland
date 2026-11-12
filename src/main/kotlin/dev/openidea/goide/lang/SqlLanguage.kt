package dev.openidea.goide.lang

import com.intellij.icons.AllIcons
import com.intellij.lang.BracePair
import com.intellij.lang.Commenter
import com.intellij.lang.Language
import com.intellij.lang.PairedBraceMatcher
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType

object SqlLanguage : Language("OpenIDEAGoSql") {
    override fun getDisplayName() = "SQL"
}

class SqlFileType private constructor() : LanguageFileType(SqlLanguage) {
    override fun getName() = "open Goland SQL"
    override fun getDescription() = "SQL source file"
    override fun getDefaultExtension() = "sql"
    override fun getIcon() = AllIcons.FileTypes.Text

    companion object {
        @JvmField val INSTANCE = SqlFileType()
    }
}

class SqlCommenter : Commenter {
    override fun getLineCommentPrefix() = "--"
    override fun getBlockCommentPrefix() = "/*"
    override fun getBlockCommentSuffix() = "*/"
    override fun getCommentedBlockCommentPrefix(): String? = null
    override fun getCommentedBlockCommentSuffix(): String? = null
}

class SqlBraceMatcher : PairedBraceMatcher {
    override fun getPairs() = arrayOf(
        BracePair(SqlTokens.LPAREN, SqlTokens.RPAREN, true),
        BracePair(SqlTokens.LBRACKET, SqlTokens.RBRACKET, false),
        BracePair(SqlTokens.LBRACE, SqlTokens.RBRACE, false),
    )
    override fun isPairedBracesAllowedBeforeType(lbraceType: IElementType, contextType: IElementType?) = true
    override fun getCodeConstructStart(file: PsiFile, openingBraceOffset: Int) = openingBraceOffset
}
