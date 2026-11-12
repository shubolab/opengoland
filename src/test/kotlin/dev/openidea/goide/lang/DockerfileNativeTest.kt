package dev.openidea.goide.lang

import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.psi.PsiFileFactory
import com.intellij.testFramework.LightPlatformTestCase

class DockerfileNativeTest : LightPlatformTestCase() {
    fun testDockerfileTypeAndDescriptor() {
        assertEquals("dockerfile", DockerfileFileType.INSTANCE.defaultExtension)
        assertSame(DockerfileLanguage, DockerfileFileType.INSTANCE.language)
        val descriptor = requireNotNull(javaClass.classLoader.getResource("META-INF/plugin.xml")).readText()
        assertTrue(descriptor.contains("language=\"OpenIDEAGoDockerfile\""))
        assertTrue(descriptor.contains("patterns=\"Dockerfile;Dockerfile.*;*.dockerfile\""))
    }

    fun testDockerfilePsiAndSyntaxHighlighter() {
        val file = PsiFileFactory.getInstance(project)
            .createFileFromText("Dockerfile", DockerfileFileType.INSTANCE, "FROM golang:1.27\nRUN go test ./...\n")
        assertSame(DockerfileLanguage, file.language)
        assertSame(DockerfileFileType.INSTANCE, file.fileType)
        val highlighter = SyntaxHighlighterFactory.getSyntaxHighlighter(DockerfileLanguage, project, file.virtualFile)
        assertTrue(highlighter.getTokenHighlights(DockerfileTokens.INSTRUCTION).isNotEmpty())
        assertTrue(highlighter.getTokenHighlights(DockerfileTokens.COMMENT).isNotEmpty())
    }
}
