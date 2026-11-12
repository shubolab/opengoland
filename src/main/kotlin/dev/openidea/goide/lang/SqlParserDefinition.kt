package dev.openidea.goide.lang

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.extapi.psi.PsiFileBase
import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet

class SqlParserDefinition : ParserDefinition {
    private val fileType = IFileElementType(SqlLanguage)

    override fun createLexer(project: Project?): Lexer = SqlLexer()
    override fun createParser(project: Project?): PsiParser = PsiParser { root, builder ->
        val marker = builder.mark()
        while (!builder.eof()) builder.advanceLexer()
        marker.done(root)
        builder.treeBuilt
    }
    override fun getFileNodeType() = fileType
    override fun getCommentTokens() = TokenSet.create(SqlTokens.COMMENT)
    override fun getStringLiteralElements() = TokenSet.create(SqlTokens.STRING)
    override fun createElement(node: ASTNode): PsiElement = ASTWrapperPsiElement(node)
    override fun createFile(viewProvider: FileViewProvider): PsiFile =
        object : PsiFileBase(viewProvider, SqlLanguage) {
            override fun getFileType() = viewProvider.virtualFile.fileType
        }
}
