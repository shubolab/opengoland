package dev.openidea.goide.run

import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.lineMarker.ExecutorAction
import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.TokenType
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import dev.openidea.goide.lang.GoLanguage
import dev.openidea.goide.lang.GoLexer
import dev.openidea.goide.lang.GoTokens

enum class GoFunctionKind { FUNCTION, MAIN, TEST, EXAMPLE, BENCHMARK, FUZZ }

data class GoFunctionDeclaration(val name: String, val nameOffset: Int, val endOffset: Int, val kind: GoFunctionKind = GoFunctionKind.FUNCTION)
data class GoSourceFunctions(val packageName: String, val functions: List<GoFunctionDeclaration>) {
    companion object {
        /** A lexical entry-point scan only; semantic navigation remains the language service's job. */
        fun scan(source: CharSequence): GoSourceFunctions = GoEntryPointScanner(source).scan()

        fun forFile(file: PsiFile): GoSourceFunctions = CachedValuesManager.getCachedValue(file) {
            CachedValueProvider.Result.create(scan(file.text), file)
        }

        fun isTestName(name: String) = isNamed(name, "Test")
        fun isBenchmarkName(name: String) = isNamed(name, "Benchmark")
        fun isFuzzName(name: String) = isNamed(name, "Fuzz")
        internal fun isNamed(name: String, prefix: String) = name.startsWith(prefix) &&
            (name.length == prefix.length || Character.getType(name.codePointAt(prefix.length)) != Character.LOWERCASE_LETTER.toInt())
    }
}

/** Balanced signatures prevent struct/interface braces in types from becoming a function's body. */
private class GoEntryPointScanner(private val source: CharSequence) {
    private data class Token(val text: String, val start: Int, val end: Int, val identifier: Boolean)
    private data class Comment(val text: String, val start: Int, val end: Int)
    private data class Function(val name: Token, val parameterStart: Int, val parameterEnd: Int,
        val hasResults: Boolean, val generic: Boolean, val body: Int, val end: Int)
    private val tokens = mutableListOf<Token>()
    private val comments = mutableListOf<Comment>()
    private val testingAliases = mutableSetOf<String>()
    private var testingDotImport = false
    private val matching = mapOf("(" to ")", "[" to "]", "{" to "}")

    init {
        val lexer = GoLexer()
        lexer.start(source)
        while (lexer.tokenType != null) {
            val text = source.subSequence(lexer.tokenStart, lexer.tokenEnd).toString()
            if (lexer.tokenType == GoTokens.COMMENT) comments += Comment(text, lexer.tokenStart, lexer.tokenEnd)
            else if (lexer.tokenType != TokenType.WHITE_SPACE)
                tokens += Token(text, lexer.tokenStart, lexer.tokenEnd, lexer.tokenType == GoTokens.IDENTIFIER)
            lexer.advance()
        }
    }

    fun scan(): GoSourceFunctions {
        var packageName = ""
        val found = mutableListOf<Function>()
        var depth = 0
        var index = 0
        while (index < tokens.size) {
            val token = tokens[index]
            if (depth == 0) {
                if (token.text == "package") packageName = tokens.getOrNull(index + 1)?.takeIf { it.identifier }?.text.orEmpty()
                if (token.text == "import") readImports(index + 1)
                if (token.text == "func") {
                    function(index)?.let { declaration ->
                        found += declaration
                        index = declaration.end
                    }
                }
            }
            when (tokens.getOrNull(index)?.text) { "{" -> depth++; "}" -> depth = (depth - 1).coerceAtLeast(0) }
            index++
        }
        return GoSourceFunctions(packageName, found.map { function ->
            GoFunctionDeclaration(function.name.text, function.name.start, tokens[function.end].end, kind(function, packageName))
        })
    }

    private fun function(index: Int): Function? {
        val name = tokens.getOrNull(index + 1)?.takeIf { it.identifier } ?: return null // receivers and literals are excluded
        var next = index + 2
        val generic = tokens.getOrNull(next)?.text == "["
        if (generic) next = (close(next) ?: return null) + 1
        if (tokens.getOrNull(next)?.text != "(") return null
        val parameterStart = next
        val parameterEnd = close(next) ?: return null
        next = parameterEnd + 1
        var hasResults = false
        if (tokens.getOrNull(next)?.text == "(") {
            val resultEnd = close(next) ?: return null
            hasResults = resultEnd != next + 1
            next = resultEnd + 1
        } else if (tokens.getOrNull(next)?.text != "{") {
            next = typeEnd(next) ?: return null
            hasResults = true
        }
        if (tokens.getOrNull(next)?.text != "{") return null // declarations implemented in assembly have no runnable body
        val end = close(next) ?: return null
        return Function(name, parameterStart, parameterEnd, hasResults, generic, next, end)
    }

    private fun close(start: Int): Int? {
        val first = matching[tokens.getOrNull(start)?.text] ?: return null
        val stack = ArrayDeque<String>()
        stack.addLast(first)
        for (index in start + 1 until tokens.size) {
            val token = tokens[index].text
            matching[token]?.let { stack.addLast(it) }
            if (token in setOf(")", "]", "}")) {
                if (stack.removeLastOrNull() != token) return null
                if (stack.isEmpty()) return index
            }
        }
        return null
    }

    /** The Go type forms needed to find the body, including function-valued results. */
    private fun typeEnd(start: Int, depth: Int = 0): Int? {
        if (depth > 128) return null // malformed/extreme types must not exhaust the native action thread's stack
        val token = tokens.getOrNull(start) ?: return null
        return when (token.text) {
            "*" -> typeEnd(start + 1, depth + 1)
            "[" -> typeEnd((close(start) ?: return null) + 1, depth + 1)
            "(" -> close(start)?.plus(1)
            "struct", "interface" -> if (tokens.getOrNull(start + 1)?.text == "{") close(start + 1)?.plus(1) else null
            "map" -> if (tokens.getOrNull(start + 1)?.text == "[") typeEnd((close(start + 1) ?: return null) + 1, depth + 1) else null
            "chan" -> typeEnd(if (tokens.getOrNull(start + 1)?.text == "<-") start + 2 else start + 1, depth + 1)
            "<-" -> if (tokens.getOrNull(start + 1)?.text == "chan") typeEnd(start + 2, depth + 1) else null
            "func" -> {
                if (tokens.getOrNull(start + 1)?.text != "(") return null
                val afterParameters = (close(start + 1) ?: return null) + 1
                if (tokens.getOrNull(afterParameters)?.text == "(") close(afterParameters)?.plus(1)
                else typeEnd(afterParameters, depth + 1) ?: afterParameters
            }
            else -> if (token.identifier) {
                var end = start + 1
                if (tokens.getOrNull(end)?.text == ".") {
                    if (tokens.getOrNull(end + 1)?.identifier != true) return null
                    end += 2
                }
                if (tokens.getOrNull(end)?.text == "[") end = (close(end) ?: return null) + 1
                end
            } else null
        }
    }

    private fun readImports(start: Int) {
        val grouped = tokens.getOrNull(start)?.text == "("
        val end = if (grouped) close(start) ?: return else tokens.size
        var index = if (grouped) start + 1 else start
        while (index < end) {
            if (tokens[index].text == ";") { index++; continue }
            var alias = "testing"
            if (tokens[index].identifier || tokens[index].text == ".") alias = tokens[index++].text
            val path = tokens.getOrNull(index) ?: return
            val value = importPath(path.text) ?: return
            if (value == "testing") {
                if (alias == ".") testingDotImport = true
                else if (alias != "_") testingAliases += alias
            }
            index++
            if (!grouped) return
        }
    }

    private fun importPath(text: String): String? {
        if (text.length < 2) return null
        if (text.first() == '`' && text.last() == '`') return text.substring(1, text.lastIndex).replace("\r", "")
        if (text.first() != '"' || text.last() != '"') return null
        val value = StringBuilder()
        var index = 1
        while (index < text.lastIndex) {
            val c = text[index++]
            if (c != '\\') { value.append(c); continue }
            val escape = text.getOrNull(index++) ?: return null
            val digits = when (escape) { 'x' -> 2; 'u' -> 4; 'U' -> 8; in '0'..'7' -> 3; else -> 0 }
            if (digits > 0) {
                if (escape in '0'..'7') index--
                if (index + digits > text.lastIndex) return null
                val code = text.substring(index, index + digits).toLongOrNull(if (escape in '0'..'7') 8 else 16) ?: return null
                if (code > 0x10ffffL || code in 0xd800L..0xdfffL) return null
                value.appendCodePoint(code.toInt()); index += digits
            } else value.append(when (escape) {
                'a' -> '\u0007'; 'b' -> '\b'; 'f' -> '\u000c'; 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'; 'v' -> '\u000b'
                '\\', '\'', '"' -> escape; else -> return null
            })
        }
        return value.toString()
    }

    private fun kind(function: Function, packageName: String): GoFunctionKind {
        if (function.generic || function.hasResults) return GoFunctionKind.FUNCTION
        val parameterTokens = tokens.subList(function.parameterStart + 1, function.parameterEnd)
        val parameters = parameterTokens.map { it.text }.toMutableList()
        if (parameters.lastOrNull() == ",") parameters.removeLast()
        if (function.name.text == "main" && packageName == "main" && parameters.isEmpty()) return GoFunctionKind.MAIN
        if (GoSourceFunctions.isNamed(function.name.text, "Example") && parameters.isEmpty() && hasExampleOutput(function)) return GoFunctionKind.EXAMPLE
        if (function.name.text == "TestMain") return GoFunctionKind.FUNCTION
        val (type, result) = when {
            GoSourceFunctions.isTestName(function.name.text) -> "T" to GoFunctionKind.TEST
            GoSourceFunctions.isBenchmarkName(function.name.text) -> "B" to GoFunctionKind.BENCHMARK
            GoSourceFunctions.isFuzzName(function.name.text) -> "F" to GoFunctionKind.FUZZ
            else -> return GoFunctionKind.FUNCTION
        }
        if (parameters.size >= 3 && parameterTokens.firstOrNull()?.identifier == true && parameters[1] == "*") parameters.removeAt(0)
        val qualified = parameters.size == 4 && parameters[0] == "*" && parameters[1] in testingAliases && parameters[2] == "." && parameters[3] == type
        val dotImported = testingDotImport && parameters == listOf("*", type)
        return if (qualified || dotImported) result else GoFunctionKind.FUNCTION
    }

    private fun hasExampleOutput(function: Function): Boolean {
        val enclosed = comments.filter { it.start > tokens[function.body].start && it.end < tokens[function.end].end }
        if (enclosed.isEmpty()) return false
        var first = enclosed.lastIndex
        while (first > 0) {
            val gap = source.subSequence(enclosed[first - 1].end, enclosed[first].start).toString()
            if (gap.any { !it.isWhitespace() } || gap.count { it == '\n' } > 1) break
            first--
        }
        val text = enclosed.subList(first, enclosed.size).joinToString("\n") {
            if (it.text.startsWith("//")) it.text.substring(2).removePrefix(" ") else it.text.removePrefix("/*").removeSuffix("*/")
        }
        return Regex("(?i)^\\s*(unordered )?output:").containsMatchIn(text)
    }
}

private data class GoContext(val file: PsiFile, val directory: String, val function: GoFunctionDeclaration?, val source: GoSourceFunctions)

private fun sourceContext(element: PsiElement?): GoContext? {
    val anchor = element ?: return null
    val file = anchor.containingFile ?: anchor as? PsiFile ?: return null
    if (file.language != GoLanguage) return null
    val virtual = file.virtualFile ?: return null
    val source = GoSourceFunctions.forFile(file)
    val offset = anchor.textRange.startOffset
    val function = source.functions.lastOrNull { offset in it.nameOffset until it.endOffset }
    return GoContext(file, virtual.parent?.path ?: return null, function, source)
}

class GoBuildConfigurationProducer : LazyRunConfigurationProducer<GoRunConfiguration>(), DumbAware {
    override fun getConfigurationFactory(): ConfigurationFactory = ConfigurationTypeUtil.findConfigurationType(GoBuildConfigurationType::class.java).configurationFactories.single()
    override fun setupConfigurationFromContext(configuration: GoRunConfiguration, context: ConfigurationContext, sourceElement: Ref<PsiElement>): Boolean {
        val source = sourceContext(context.psiLocation) ?: return false
        if (source.source.packageName != "main" || source.file.name.endsWith("_test.go")) return false
        val main = source.source.functions.firstOrNull { it.kind == GoFunctionKind.MAIN } ?: return false
        configuration.parameters = GoRunParameters(target = ".", workingDirectory = source.directory)
        configuration.name = "go run ${source.file.virtualFile.parent.name}"
        sourceElement.set(source.file.findElementAt(main.nameOffset) ?: source.file)
        return true
    }
    override fun isConfigurationFromContext(configuration: GoRunConfiguration, context: ConfigurationContext): Boolean {
        val source = sourceContext(context.psiLocation) ?: return false
        return configuration.kind == GoRunKind.BUILD && configuration.parameters.target == "." &&
            configuration.parameters.workingDirectory == source.directory && !source.file.name.endsWith("_test.go") &&
            source.source.functions.any { it.kind == GoFunctionKind.MAIN }
    }
}

abstract class GoFrameworkConfigurationProducer(private val mode: GoTestMode) : LazyRunConfigurationProducer<GoRunConfiguration>(), DumbAware {
    override fun getConfigurationFactory(): ConfigurationFactory = ConfigurationTypeUtil.findConfigurationType(GoTestConfigurationType::class.java).configurationFactories.single()
    override fun setupConfigurationFromContext(configuration: GoRunConfiguration, context: ConfigurationContext, sourceElement: Ref<PsiElement>): Boolean {
        val source = sourceContext(context.psiLocation) ?: return false
        if (!applicable(source)) return false
        val test = selected(source)
        val pattern = pattern(test)
        configuration.parameters = GoRunParameters(target = ".", workingDirectory = source.directory, testMode = mode).apply {
            when (mode) {
                GoTestMode.TEST -> testPattern = pattern
                GoTestMode.BENCHMARK -> benchmarkPattern = pattern
                GoTestMode.FUZZ -> fuzzPattern = pattern
            }
        }
        configuration.name = test?.name?.let { "$it in ${source.file.virtualFile.parent.name}" } ?: "go test ${source.file.virtualFile.parent.name}"
        sourceElement.set(test?.let { source.file.findElementAt(it.nameOffset) } ?: source.file)
        return true
    }
    override fun isConfigurationFromContext(configuration: GoRunConfiguration, context: ConfigurationContext): Boolean {
        val source = sourceContext(context.psiLocation) ?: return false
        if (!applicable(source)) return false
        val expected = pattern(selected(source))
        val actual = when (mode) {
            GoTestMode.TEST -> configuration.parameters.testPattern
            GoTestMode.BENCHMARK -> configuration.parameters.benchmarkPattern
            GoTestMode.FUZZ -> configuration.parameters.fuzzPattern
        }
        return configuration.kind == GoRunKind.TEST && configuration.parameters.target == "." &&
            configuration.parameters.workingDirectory == source.directory && configuration.parameters.testMode == mode && actual == expected
    }

    private fun applicable(source: GoContext) = source.file.name.endsWith("_test.go") && when (mode) {
        GoTestMode.TEST -> source.function == null || source.function.kind in setOf(GoFunctionKind.TEST, GoFunctionKind.EXAMPLE)
        GoTestMode.BENCHMARK -> source.function?.kind == GoFunctionKind.BENCHMARK
        GoTestMode.FUZZ -> source.function?.kind == GoFunctionKind.FUZZ
    }
    private fun selected(source: GoContext) = source.function?.takeIf { when (mode) {
        GoTestMode.TEST -> it.kind in setOf(GoFunctionKind.TEST, GoFunctionKind.EXAMPLE)
        GoTestMode.BENCHMARK -> it.kind == GoFunctionKind.BENCHMARK
        GoTestMode.FUZZ -> it.kind == GoFunctionKind.FUZZ
    } }
    private fun pattern(function: GoFunctionDeclaration?) = function?.name?.let { "^${GoRerunFailedTestsAction.goQuoteMeta(it)}$" }.orEmpty()
}

class GoTestConfigurationProducer : GoFrameworkConfigurationProducer(GoTestMode.TEST)
class GoBenchmarkConfigurationProducer : GoFrameworkConfigurationProducer(GoTestMode.BENCHMARK)
class GoFuzzConfigurationProducer : GoFrameworkConfigurationProducer(GoTestMode.FUZZ)

class GoRunLineMarkerContributor : RunLineMarkerContributor(), DumbAware {
    override fun getInfo(element: PsiElement): Info? {
        if (element.node.elementType != GoTokens.IDENTIFIER) return null
        val context = sourceContext(element) ?: return null
        val declaration = context.source.functions.firstOrNull { it.nameOffset == element.textRange.startOffset } ?: return null
        val eligible = if (context.file.name.endsWith("_test.go")) declaration.kind in setOf(GoFunctionKind.TEST, GoFunctionKind.EXAMPLE, GoFunctionKind.BENCHMARK, GoFunctionKind.FUZZ)
            else declaration.kind == GoFunctionKind.MAIN
        if (!eligible) return null
        val actions = ExecutorAction.getActions(0).filter {
            it is ExecutorAction && it.executor.id in setOf(DefaultRunExecutor.EXECUTOR_ID, DefaultDebugExecutor.EXECUTOR_ID)
        }.toTypedArray()
        return Info(AllIcons.RunConfigurations.TestState.Run, actions) {
            if (declaration.kind == GoFunctionKind.FUZZ) "Run fuzzing / Debug seed corpus: ${declaration.name}" else "Run ${declaration.name}"
        }
    }
}
