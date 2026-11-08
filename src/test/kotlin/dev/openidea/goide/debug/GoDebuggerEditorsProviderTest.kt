package dev.openidea.goide.debug

import com.intellij.lang.Language
import com.intellij.lang.LanguageParserDefinitions
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.RuntimeExceptionWithAttachments
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.testFramework.LightPlatformTestCase
import com.intellij.xdebugger.XExpression
import com.intellij.xdebugger.evaluation.EvaluationMode
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProviderBase
import com.redhat.devtools.lsp4ij.dap.evaluation.DAPExpressionCodeFragment
import dev.openidea.goide.lang.GoFileType
import dev.openidea.goide.lang.GoLanguage
import dev.openidea.goide.lang.GoParserDefinition

/** Real Application/Project/PSI/document services; the regression occurs before document commit. */
class GoDebuggerEditorsProviderTest : LightPlatformTestCase() {
    private var parser: GoParserDefinition? = null

    override fun setUp() {
        super.setUp()
        // The isolated test runtime can omit our plugin EPs. Use the production
        // parser implementation, while keeping every PSI/document service real.
        if (LanguageParserDefinitions.INSTANCE.forLanguage(GoLanguage) == null) {
            parser = GoParserDefinition().also { LanguageParserDefinitions.INSTANCE.addExplicitExtension(GoLanguage, it) }
        }
    }

    override fun tearDown() {
        try { parser?.let { LanguageParserDefinitions.INSTANCE.removeExplicitExtension(GoLanguage, it) } }
        finally { super.tearDown() }
    }

    fun testPublicProviderKeepsDocumentCachedWhileExpressionIsUncommitted() {
        val manager = PsiDocumentManager.getInstance(project)
        val provider = GoDebuggerEditorsProvider(GoFileType.INSTANCE, null)
        val document = provider.createDocument(project, expression(""), contextFile(), EvaluationMode.EXPRESSION)
        val fragment = manager.getPsiFile(document)
        assertTrue("The public provider must create the Go-owned DAP fragment", fragment is GoDapExpressionCodeFragment)
        fragment!!
        assertTrue(fragment is DAPExpressionCodeFragment)
        assertEquals(GoLanguage, fragment.language)
        assertEquals(true, fragment.getUserData(XDebuggerEditorsProviderBase.DEBUGGER_FILE_KEY))
        assertEquals("", fragment.text) // Load the initial tree before editing.
        assertSame(document, manager.getDocument(fragment))

        WriteCommandAction.runWriteCommandAction(project) {
            document.insertString(0, "cart.Value")
            assertFalse("The assertion must exercise the uncommitted window", manager.isCommitted(document))
            assertSame(document, manager.getDocument(fragment))
            assertEquals("cart.Value", document.text)
        }
        manager.commitDocument(document)
        assertEquals(document.text, fragment.text)
        assertEquals(document.textLength, fragment.textLength)
    }

    fun testRepeatedPasteReplaceDeleteAndUtf16RemainConsistentAfterCommit() {
        val manager = PsiDocumentManager.getInstance(project)
        val provider = GoDebuggerEditorsProvider(GoFileType.INSTANCE, null)
        val document = provider.createDocument(project, expression(""), contextFile(), EvaluationMode.EXPRESSION)
        val fragment = manager.getPsiFile(document)!!
        assertEquals("", fragment.text)
        for (text in listOf("cart.Value", "cart.Items[0].Price", "\"中文👋\"", "cart.Value\n+ 1", "")) {
            WriteCommandAction.runWriteCommandAction(project) {
                document.setText(text)
                assertSame("A paste/delete must keep the same document before commit", document, manager.getDocument(fragment))
            }
            manager.commitDocument(document)
            assertEquals(text, fragment.text)
            assertEquals(text.length, fragment.textLength)
        }
    }

    fun testOriginalLsp4ijFragmentReproducesMismatchInSameRealServices() {
        val manager = PsiDocumentManager.getInstance(project)
        val original = DAPExpressionCodeFragment("", GoFileType.INSTANCE, GoLanguage, null, project)
        val document = manager.getDocument(original)!!
        assertEquals("", original.text)
        WriteCommandAction.runWriteCommandAction(project) {
            try {
                document.insertString(0, "cart.Value")
                assertFalse(manager.isCommitted(document))
                var failure: RuntimeExceptionWithAttachments? = null
                try { manager.getDocument(original) }
                catch (error: RuntimeExceptionWithAttachments) { failure = error }
                assertNotNull("The upstream fragment must reproduce the original mismatch", failure)
                assertTrue(failure!!.message.orEmpty().contains("Document/PSI mismatch"))
            } finally {
                // Restore matching text so the fixture teardown has no broken document.
                document.setText("")
            }
        }
        manager.commitDocument(document)
        assertEquals(document.text, original.text)
    }

    private fun contextFile(): PsiFile = PsiFileFactory.getInstance(project).createFileFromText(
        "context.go", GoFileType.INSTANCE, "package main\nvar cart struct { Value int }\n")

    private fun expression(text: String) = object : XExpression {
        override fun getExpression() = text
        override fun getLanguage(): Language = GoLanguage
        override fun getCustomInfo(): String? = null
        override fun getMode() = EvaluationMode.EXPRESSION
    }
}
