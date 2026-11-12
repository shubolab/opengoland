package dev.openidea.goide.lang

import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.psi.PsiFileFactory
import com.intellij.testFramework.LightPlatformTestCase

class SqlNativeTest : LightPlatformTestCase() {
    fun testSqlFileTypeAndDescriptor() {
        assertEquals("sql", SqlFileType.INSTANCE.defaultExtension)
        assertSame(SqlLanguage, SqlFileType.INSTANCE.language)
        val descriptor = requireNotNull(javaClass.classLoader.getResource("META-INF/plugin.xml")).readText()
        assertTrue(descriptor.contains("language=\"OpenIDEAGoSql\""))
        assertTrue(descriptor.contains("extensions=\"sql\""))
    }

    fun testSqlPsiAndSyntaxHighlighter() {
        val file = PsiFileFactory.getInstance(project)
            .createFileFromText("query.sql", SqlFileType.INSTANCE, "SELECT id FROM users")
        assertSame(SqlLanguage, file.language)
        assertSame(SqlFileType.INSTANCE, file.fileType)
        val highlighter = SyntaxHighlighterFactory.getSyntaxHighlighter(SqlLanguage, project, file.virtualFile)
        assertTrue(highlighter.getTokenHighlights(SqlTokens.KEYWORD).isNotEmpty())
        assertTrue(highlighter.getTokenHighlights(SqlTokens.COMMENT).isNotEmpty())
    }
}
