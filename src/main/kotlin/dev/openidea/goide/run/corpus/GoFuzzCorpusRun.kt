package dev.openidea.goide.run.corpus

import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.lineMarker.ExecutorAction
import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.icons.AllIcons
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import dev.openidea.goide.run.GoRunConfiguration
import dev.openidea.goide.run.GoRunKind
import dev.openidea.goide.run.GoRunParameters
import dev.openidea.goide.run.GoTestConfigurationType
import dev.openidea.goide.run.GoTestMode

/** Editor context is already parsed; availability reads only its cached document and VFS path. */
internal fun corpusContext(element: PsiElement?): Pair<PsiFile, GoFuzzCorpusPath>? {
    val file = element?.containingFile ?: element as? PsiFile ?: return null
    if (file.language != GoFuzzCorpusLanguage) return null
    val virtual = file.virtualFile?.takeIf { it.isInLocalFileSystem && !it.isDirectory } ?: return null
    val document = FileDocumentManager.getInstance().getCachedDocument(virtual) ?: return null
    if (!GoFuzzCorpusPath.hasHeader(document.immutableCharSequence)) return null
    return file to (GoFuzzCorpusPath.fromPath(virtual.path)?.takeIf { it.canReplayExactly } ?: return null)
}

class GoFuzzCorpusConfigurationProducer : LazyRunConfigurationProducer<GoRunConfiguration>(), DumbAware {
    override fun getConfigurationFactory(): ConfigurationFactory = ConfigurationTypeUtil.findConfigurationType(GoTestConfigurationType::class.java).configurationFactories.single()

    override fun setupConfigurationFromContext(configuration: GoRunConfiguration, context: ConfigurationContext, sourceElement: Ref<PsiElement>): Boolean {
        val (file, corpus) = corpusContext(context.psiLocation) ?: return false
        configuration.parameters = GoRunParameters(target = ".", workingDirectory = corpus.packageDirectory.toString(),
            testMode = GoTestMode.TEST, testPattern = corpus.testPattern)
        configuration.name = "${corpus.fuzzFunction}/${corpus.caseName}"
        sourceElement.set(file.findElementAt(0) ?: file)
        return true
    }

    override fun isConfigurationFromContext(configuration: GoRunConfiguration, context: ConfigurationContext): Boolean {
        val (_, corpus) = corpusContext(context.psiLocation) ?: return false
        return configuration.kind == GoRunKind.TEST && configuration.parameters.testMode == GoTestMode.TEST &&
            configuration.parameters.target == "." && configuration.parameters.workingDirectory == corpus.packageDirectory.toString() &&
            configuration.parameters.testPattern == corpus.testPattern
    }
}

class GoFuzzCorpusRunLineMarkerContributor : RunLineMarkerContributor(), DumbAware {
    override fun getInfo(element: PsiElement): Info? {
        if (element.textRange.startOffset != 0 || element.firstChild != null) return null
        val (_, corpus) = corpusContext(element) ?: return null
        val actions = ExecutorAction.getActions(0).filter {
            it is ExecutorAction && it.executor.id in setOf(DefaultRunExecutor.EXECUTOR_ID, DefaultDebugExecutor.EXECUTOR_ID)
        }.toTypedArray()
        return Info(AllIcons.RunConfigurations.TestState.Run, actions) { "Run / Debug fuzz corpus input: ${corpus.fuzzFunction}/${corpus.caseName}" }
    }
}
