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

data class GoFunctionDeclaration(val name: String, val nameOffset: Int, val endOffset: Int)
data class GoSourceFunctions(val packageName: String, val functions: List<GoFunctionDeclaration>) {
    companion object {
        /** A lexical entry-point scan only; semantic navigation remains the language service's job. */
        fun scan(source: CharSequence): GoSourceFunctions {
            data class Token(val text: String, val start: Int, val end: Int)
            val lexer = GoLexer()
            lexer.start(source)
            val tokens = mutableListOf<Token>()
            while (lexer.tokenType != null) {
                if (lexer.tokenType != TokenType.WHITE_SPACE && lexer.tokenType != GoTokens.COMMENT && lexer.tokenType != GoTokens.STRING) {
                    tokens += Token(source.subSequence(lexer.tokenStart, lexer.tokenEnd).toString(), lexer.tokenStart, lexer.tokenEnd)
                }
                lexer.advance()
            }
            var packageName = ""
            val functions = mutableListOf<GoFunctionDeclaration>()
            var depth = 0
            var index = 0
            while (index < tokens.size) {
                val token = tokens[index]
                if (depth == 0 && token.text == "package") packageName = tokens.getOrNull(index + 1)?.text.orEmpty()
                if (depth == 0 && token.text == "func") {
                    val name = tokens.getOrNull(index + 1)
                    if (name != null && name.text != "(" && tokens.getOrNull(index + 2)?.text in setOf("(", "[")) {
                        val body = (index + 2 until tokens.size).firstOrNull { tokens[it].text == "{" }
                        if (body != null) {
                            var nested = 1
                            var end = body + 1
                            while (end < tokens.size && nested > 0) {
                                if (tokens[end].text == "{") nested++
                                if (tokens[end].text == "}") nested--
                                end++
                            }
                            functions += GoFunctionDeclaration(name.text, name.start, tokens.getOrNull(end - 1)?.end ?: source.length)
                        }
                    }
                }
                if (token.text == "{") depth++
                if (token.text == "}") depth = (depth - 1).coerceAtLeast(0)
                index++
            }
            return GoSourceFunctions(packageName, functions)
        }

        fun forFile(file: PsiFile): GoSourceFunctions = CachedValuesManager.getCachedValue(file) {
            CachedValueProvider.Result.create(scan(file.text), file)
        }

        fun isTestName(name: String) = name.startsWith("Test") && (name.length == 4 || !name[4].isLowerCase())
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
        if (source.source.functions.none { it.name == "main" }) return false
        configuration.parameters = GoRunParameters(target = ".", workingDirectory = source.directory)
        configuration.name = "go run ${source.file.virtualFile.parent.name}"
        sourceElement.set(source.file.findElementAt(source.source.functions.first { it.name == "main" }.nameOffset) ?: source.file)
        return true
    }
    override fun isConfigurationFromContext(configuration: GoRunConfiguration, context: ConfigurationContext): Boolean {
        val source = sourceContext(context.psiLocation) ?: return false
        return configuration.kind == GoRunKind.BUILD && configuration.parameters.target == "." &&
            configuration.parameters.workingDirectory == source.directory && source.source.packageName == "main"
    }
}

class GoTestConfigurationProducer : LazyRunConfigurationProducer<GoRunConfiguration>(), DumbAware {
    override fun getConfigurationFactory(): ConfigurationFactory = ConfigurationTypeUtil.findConfigurationType(GoTestConfigurationType::class.java).configurationFactories.single()
    override fun setupConfigurationFromContext(configuration: GoRunConfiguration, context: ConfigurationContext, sourceElement: Ref<PsiElement>): Boolean {
        val source = sourceContext(context.psiLocation) ?: return false
        if (!source.file.name.endsWith("_test.go")) return false
        val test = source.function?.takeIf { GoSourceFunctions.isTestName(it.name) }
        configuration.parameters = GoRunParameters(target = ".", workingDirectory = source.directory,
            testPattern = test?.name?.let { "^${GoRerunFailedTestsAction.goQuoteMeta(it)}$" }.orEmpty())
        configuration.name = test?.name?.let { "$it in ${source.file.virtualFile.parent.name}" } ?: "go test ${source.file.virtualFile.parent.name}"
        sourceElement.set(test?.let { source.file.findElementAt(it.nameOffset) } ?: source.file)
        return true
    }
    override fun isConfigurationFromContext(configuration: GoRunConfiguration, context: ConfigurationContext): Boolean {
        val source = sourceContext(context.psiLocation) ?: return false
        if (!source.file.name.endsWith("_test.go")) return false
        val expected = source.function?.takeIf { GoSourceFunctions.isTestName(it.name) }?.name?.let { "^${GoRerunFailedTestsAction.goQuoteMeta(it)}$" }.orEmpty()
        return configuration.kind == GoRunKind.TEST && configuration.parameters.target == "." &&
            configuration.parameters.workingDirectory == source.directory && configuration.parameters.testPattern == expected
    }
}

class GoRunLineMarkerContributor : RunLineMarkerContributor(), DumbAware {
    override fun getInfo(element: PsiElement): Info? {
        if (element.node.elementType != GoTokens.IDENTIFIER) return null
        if (element.text != "main" && !GoSourceFunctions.isTestName(element.text)) return null
        val context = sourceContext(element) ?: return null
        val declaration = context.source.functions.firstOrNull { it.nameOffset == element.textRange.startOffset } ?: return null
        val eligible = if (context.file.name.endsWith("_test.go")) GoSourceFunctions.isTestName(declaration.name)
            else context.source.packageName == "main" && declaration.name == "main"
        if (!eligible) return null
        val actions = ExecutorAction.getActions(0).filter {
            it is ExecutorAction && it.executor.id in setOf(DefaultRunExecutor.EXECUTOR_ID, DefaultDebugExecutor.EXECUTOR_ID)
        }.toTypedArray()
        return Info(AllIcons.RunConfigurations.TestState.Run, actions) { "Run ${declaration.name}" }
    }
}
