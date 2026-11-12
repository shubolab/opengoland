package dev.openidea.goide.lang

import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.psi.PsiFileFactory
import com.intellij.testFramework.LightPlatformTestCase

class HttpRequestNativeTest : LightPlatformTestCase() {
    fun testHttpRequestTypeAndDescriptor() {
        assertEquals("http", HttpRequestFileType.INSTANCE.defaultExtension)
        assertSame(HttpRequestLanguage, HttpRequestFileType.INSTANCE.language)
        val descriptor = requireNotNull(javaClass.classLoader.getResource("META-INF/plugin.xml")).readText()
        assertTrue(descriptor.contains("language=\"OpenIDEAGoHttpRequest\""))
        assertTrue(descriptor.contains("extensions=\"http\""))
    }

    fun testHttpRequestPsiAndSyntaxHighlighter() {
        val file = PsiFileFactory.getInstance(project)
            .createFileFromText("api.http", HttpRequestFileType.INSTANCE, "GET https://example.test HTTP/1.1\n")
        assertSame(HttpRequestLanguage, file.language)
        assertSame(HttpRequestFileType.INSTANCE, file.fileType)
        val highlighter = SyntaxHighlighterFactory.getSyntaxHighlighter(HttpRequestLanguage, project, file.virtualFile)
        assertTrue(highlighter.getTokenHighlights(HttpRequestTokens.METHOD).isNotEmpty())
        assertTrue(highlighter.getTokenHighlights(HttpRequestTokens.URL).isNotEmpty())
    }
}
