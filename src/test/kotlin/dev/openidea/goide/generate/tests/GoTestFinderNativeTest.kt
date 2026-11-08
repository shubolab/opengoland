package dev.openidea.goide.generate.tests

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.LightPlatformTestCase
import com.intellij.testIntegration.TestFinderHelper
import dev.openidea.goide.lang.GoLexer
import dev.openidea.goide.lang.GoTokens
import java.nio.file.Files

class GoTestFinderNativeTest : LightPlatformTestCase() {
    override fun runInDispatchThread() = false

    fun testPlatformFinderKeepsAllNameMatchesAcrossSameAndExternalTestPackages() = fixture { files ->
        ReadAction.run<RuntimeException> {
            val source = files.getValue("subject_linux.go")
            val load = at(source, "Load")
            assertNotNull(TestFinderHelper.findSourceElement(load))
            val tests = TestFinderHelper.findTestsForClass(load)
            assertEquals(setOf("TestLoad", "TestLoad_extra"), tests.map { it.text }.toSet())
            assertEquals(setOf("generated_linux_test.go", "other_test.go"), tests.map { it.containingFile.name }.toSet())
            val genericRead = at(files.getValue("generated_linux_test.go"), "TestRead")
            assertEquals("TestRead", genericRead.text)
            val sources = TestFinderHelper.findClassesForTest(genericRead)
            assertEquals("A receiver-free test name cannot establish a unique receiver relationship", 2, sources.size)
            assertEquals(setOf("Read"), sources.map { it.text }.toSet())
            val explicit = TestFinderHelper.findClassesForTest(at(files.getValue("generated_linux_test.go"), "TestReader_Read"))
            assertEquals(1, explicit.size)
            assertTrue(explicit.single().textRange.startOffset < source.text.indexOf("Writer) Read"))
        }
    }

    fun testUnknownTestNamesOfferMultiplePackageFilesRatherThanAnInventedUniqueFilenameRelation() = fixture { files ->
        ReadAction.run<RuntimeException> {
            val unknown = at(files.getValue("generated_linux_test.go"), "TestCustomScenario")
            val candidates = TestFinderHelper.findClassesForTest(unknown)
            assertEquals(setOf("subject_linux.go", "unrelated.go"), candidates.map { (it.containingFile ?: it as PsiFile).name }.toSet())
            assertTrue(TestFinderHelper.isTest(unknown))
            assertFalse(TestFinderHelper.isTest(files.getValue("subject_linux.go")))
        }
    }

    private fun at(file: PsiFile, name: String): PsiElement {
        val text = file.text
        val lexer = GoLexer()
        lexer.start(text)
        while (lexer.tokenType != null) {
            if (lexer.tokenType == GoTokens.IDENTIFIER && text.substring(lexer.tokenStart, lexer.tokenEnd) == name)
                return file.findElementAt(lexer.tokenStart)!!
            lexer.advance()
        }
        error("Missing fixture identifier $name")
    }

    private fun fixture(operation: (Map<String, PsiFile>) -> Unit) {
        val root = Files.createTempDirectory("opengoland-test-navigation-").toRealPath()
        val disposable = Disposer.newDisposable("Go source-test navigation roots")
        VfsRootAccess.allowRootAccess(disposable, root.toString())
        try {
            val contents = linkedMapOf(
                "subject_linux.go" to "package fixture\ntype Reader struct{}\ntype Writer struct{}\nfunc (r Reader) Read() {}\nfunc (r Writer) Read() {}\nfunc Load() {}\n",
                "unrelated.go" to "package fixture\nfunc Unrelated() {}\n",
                "generated_linux_test.go" to "package fixture\nimport \"testing\"\nfunc TestReader_Read(t *testing.T) {}\nfunc TestRead(t *testing.T) {}\nfunc TestLoad(t *testing.T) {}\nfunc TestCustomScenario(t *testing.T) {}\n",
                "other_test.go" to "package fixture_test\nimport \"testing\"\nfunc TestLoad_extra(t *testing.T) {}\n",
            )
            contents.forEach { (name, text) -> Files.writeString(root.resolve(name), text) }
            val files = contents.keys.associateWith { name ->
                val virtual = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root.resolve(name))!!
                ReadAction.compute<PsiFile, RuntimeException> { PsiManager.getInstance(project).findFile(virtual)!! }
            }
            operation(files)
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root)
            Disposer.dispose(disposable)
        }
    }
}
