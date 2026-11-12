package dev.openidea.goide.lang

import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.psi.PsiFileFactory
import com.intellij.testFramework.LightPlatformTestCase

class GoTemplateNativeTest : LightPlatformTestCase() {
    fun testTemplateNamesUseTheNativeTemplateFileType() {
        assertEquals("gotmpl", GoTemplateFileType.INSTANCE.defaultExtension)
        assertSame(GoTemplateLanguage, GoTemplateFileType.INSTANCE.language)
        val descriptor = requireNotNull(javaClass.classLoader.getResource("META-INF/plugin.xml")).readText()
        assertTrue(descriptor.contains("extensions=\"gotmpl;gohtml;gotext\""))
        assertTrue(descriptor.contains("patterns=\"*.go.html;*.go.tmpl;*.go.txt\""))
        assertTrue(descriptor.contains("language=\"OpenIDEAGoTemplate\""))
    }

    fun testPsiAndSyntaxHighlighterUseTemplateLanguage() {
        val source = "Hello {{ if .Name }}world{{ end }}"
        val file = PsiFileFactory.getInstance(project)
            .createFileFromText("view.gotmpl", GoTemplateFileType.INSTANCE, source)
        assertSame(GoTemplateLanguage, file.language)
        assertSame(GoTemplateFileType.INSTANCE, file.fileType)

        val highlighter = SyntaxHighlighterFactory.getSyntaxHighlighter(GoTemplateLanguage, project, file.virtualFile)
        assertTrue(highlighter.getTokenHighlights(GoTemplateTokens.ACTION_START).isNotEmpty())
        assertTrue(highlighter.getTokenHighlights(GoTemplateTokens.KEYWORD).isNotEmpty())
        assertTrue(highlighter.getTokenHighlights(GoTemplateTokens.BUILTIN).isNotEmpty())
        assertTrue(highlighter.getTokenHighlights(GoTemplateTokens.COMMENT).isNotEmpty())
        assertEquals(Language.findLanguageByID("OpenIDEAGoTemplate"), GoTemplateLanguage)
    }
}
