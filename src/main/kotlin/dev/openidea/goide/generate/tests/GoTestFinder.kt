package dev.openidea.goide.generate.tests

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.vfs.newvfs.NewVirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.TokenType
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.testIntegration.TestFinder
import dev.openidea.goide.lang.GoLanguage
import dev.openidea.goide.lang.GoLexer
import dev.openidea.goide.lang.GoTokens
import dev.openidea.goide.run.GoSourceFunctions
import dev.openidea.goide.run.GoFunctionKind

internal data class GoTestNavigationSymbol(val name: String, val receiver: String, val offset: Int, val end: Int) {
    fun testCores(): List<String> {
        val name = upperFirst(name)
        return if (receiver.isEmpty()) listOf(if (name == "Main") "MainFunction" else name) else listOf(upperFirst(receiver) + "_" + name, name)
    }
}

/** Lexical navigation hints only. The generation backend independently resolves typed declarations. */
internal fun goTestNavigationSymbols(text: String): List<GoTestNavigationSymbol> {
    data class Token(val text: String, val start: Int, val end: Int, val identifier: Boolean)
    val tokens = mutableListOf<Token>()
    val lexer = GoLexer()
    lexer.start(text)
    while (lexer.tokenType != null) {
        if (lexer.tokenType != TokenType.WHITE_SPACE && lexer.tokenType != GoTokens.COMMENT)
            tokens += Token(text.substring(lexer.tokenStart, lexer.tokenEnd), lexer.tokenStart, lexer.tokenEnd, lexer.tokenType == GoTokens.IDENTIFIER)
        lexer.advance()
    }
    val masked = text.toCharArray()
    val receivers = mutableMapOf<Int, String>()
    for ((index, token) in tokens.withIndex()) {
        if (token.text != "func" || tokens.getOrNull(index + 1)?.text != "(") continue
        var depth = 1
        var end = index + 2
        while (end < tokens.size && depth > 0) {
            if (tokens[end].text == "(") depth++
            if (tokens[end].text == ")") depth--
            if (depth > 0) end++
        }
        if (depth != 0) continue
        val name = tokens.getOrNull(end + 1)?.takeIf { it.identifier } ?: continue
        if (tokens.getOrNull(end + 2)?.text != "(") continue
        val receiver = tokens.subList(index + 2, end).takeWhile { it.text != "[" }.lastOrNull { it.identifier }?.text ?: continue
        receivers[name.start] = receiver
        // Mask only receiver syntax while preserving all UTF-16 offsets and body boundaries.
        for (offset in tokens[index + 1].start until tokens[end].end) if (masked[offset] != '\n' && masked[offset] != '\r') masked[offset] = ' '
    }
    return GoSourceFunctions.scan(String(masked)).functions.map {
        GoTestNavigationSymbol(it.name, receivers[it.nameOffset].orEmpty(), it.nameOffset, it.endOffset)
    }
}

/** Standard Go to Test / Go to Subject Code integration with a native multiple-target chooser. */
class GoTestFinder : TestFinder {
    override fun findSourceElement(from: PsiElement): PsiElement? {
        val file = goFile(from) ?: return null
        if (!committed(file)) return file
        val offset = from.textRange.startOffset
        val declaration = symbols(file).lastOrNull { offset in it.offset until it.end }
        return declaration?.let { anchor(file, it.name, it.receiver, it.offset) } ?: file
    }

    override fun isTest(element: PsiElement): Boolean = goFile(element)?.name?.endsWith("_test.go") == true

    override fun findTestsForClass(element: PsiElement): Collection<PsiElement> {
        val source = goFile(element) ?: return emptyList()
        if (isTest(source)) return emptyList()
        val subject = if (committed(source)) symbols(source).lastOrNull { element.textRange.startOffset in it.offset until it.end } else null
        val pkg = entries(source).packageName
        val candidates = packageFiles(source).filter {
            it.name.endsWith("_test.go") && entries(it).packageName in setOf(pkg, "${pkg}_test")
        }
        if (subject != null) {
            val matches = candidates.flatMap { file ->
                entries(file).functions.filter { function ->
                    function.kind in runnableKinds && subject.testCores().any { matchesTestName(function.name, it) }
                }.map { anchor(file, it.name, "", it.nameOffset) }
            }
            if (matches.isNotEmpty()) return matches
        }
        val preferred = source.name.removeSuffix(".go") + "_test.go"
        return candidates.sortedWith(compareBy<PsiFile> { it.name != preferred }.thenBy { it.name })
    }

    override fun findClassesForTest(element: PsiElement): Collection<PsiElement> {
        val test = goFile(element)?.takeIf { isTest(it) } ?: return emptyList()
        val declaration = if (committed(test)) entries(test).functions.lastOrNull {
            it.kind in runnableKinds && element.textRange.startOffset in it.nameOffset until it.endOffset
        } else null
        val testPackage = entries(test).packageName
        val pkg = testPackage.removeSuffix("_test")
        val candidates = packageFiles(test).filter { !it.name.endsWith("_test.go") && entries(it).packageName in setOf(testPackage, pkg) }
        if (declaration != null) {
            val matches = candidates.flatMap { file ->
                symbols(file).filter { candidate -> candidate.testCores().any { matchesTestName(declaration.name, it) } }
                    .map { anchor(file, it.name, it.receiver, it.offset) }
            }
            if (matches.isNotEmpty()) return matches
        }
        val preferred = test.name.removeSuffix("_test.go") + ".go"
        return candidates.sortedWith(compareBy<PsiFile> { it.name != preferred }.thenBy { it.name })
    }

    private fun goFile(element: PsiElement): PsiFile? = (element.containingFile ?: element as? PsiFile)?.takeIf { it.language == GoLanguage }

    private fun symbols(file: PsiFile): List<GoTestNavigationSymbol> {
        val document = FileDocumentManager.getInstance().getCachedDocument(file.virtualFile)
        if (document != null && !committed(file)) return goTestNavigationSymbols(document.immutableCharSequence.toString())
        return CachedValuesManager.getCachedValue(file) { CachedValueProvider.Result.create(goTestNavigationSymbols(file.text), file) }
    }

    private fun committed(file: PsiFile): Boolean = FileDocumentManager.getInstance().getCachedDocument(file.virtualFile)
        ?.let { PsiDocumentManager.getInstance(file.project).isCommitted(it) } ?: true

    private fun entries(file: PsiFile): GoSourceFunctions {
        val document = FileDocumentManager.getInstance().getCachedDocument(file.virtualFile)
        return if (document != null && !committed(file)) GoSourceFunctions.scan(document.immutableCharSequence) else GoSourceFunctions.forFile(file)
    }

    private fun anchor(file: PsiFile, name: String, receiver: String, offset: Int): PsiElement =
        GoTestNavigationDeclaration(file, name, receiver, offset)

    private fun packageFiles(file: PsiFile): List<PsiFile> {
        val parent = file.virtualFile?.parent ?: return listOf(file)
        val background = !ApplicationManager.getApplication().isDispatchThread
        val children = if (background) parent.children.toList() else (parent as? NewVirtualFile)?.cachedChildren.orEmpty().toList()
        val documents = FileDocumentManager.getInstance()
        val psiDocuments = PsiDocumentManager.getInstance(file.project)
        val psi = PsiManager.getInstance(file.project)
        return children.filter { !it.isDirectory && it.name.endsWith(".go") }.mapNotNull { child ->
            ProgressManager.checkCanceled()
            val cached = documents.getCachedDocument(child)?.let(psiDocuments::getCachedPsiFile)
            (cached ?: if (background) psi.findFile(child) else null)?.takeIf { it.language == GoLanguage }
        }
    }
}

private fun matchesTestName(test: String, core: String): Boolean = listOf("Test", "Benchmark", "Fuzz", "Example").any {
    test == it + core || test.startsWith(it + core + "_")
}

private val runnableKinds = setOf(GoFunctionKind.TEST, GoFunctionKind.BENCHMARK, GoFunctionKind.FUZZ, GoFunctionKind.EXAMPLE)

private fun upperFirst(name: String): String {
    if (name.isEmpty()) return name
    val first = name.codePointAt(0)
    return String(Character.toChars(Character.toUpperCase(first))) + name.substring(Character.charCount(first))
}
