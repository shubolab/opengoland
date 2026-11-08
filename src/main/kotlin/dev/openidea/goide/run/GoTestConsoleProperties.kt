package dev.openidea.goide.run

import com.intellij.execution.Executor
import com.intellij.execution.Location
import com.intellij.execution.testframework.TestConsoleProperties
import com.intellij.execution.testframework.sm.FileUrlProvider
import com.intellij.execution.testframework.sm.SMCustomMessagesParsing
import com.intellij.execution.testframework.sm.runner.GeneralIdBasedToSMTRunnerEventsConvertor
import com.intellij.execution.testframework.sm.runner.GeneralTestEventsProcessor
import com.intellij.execution.testframework.sm.runner.OutputToGeneralTestEventsConverter
import com.intellij.execution.testframework.sm.runner.SMTRunnerConsoleProperties
import com.intellij.execution.testframework.sm.runner.SMTestLocator
import com.intellij.execution.testframework.sm.runner.events.TestDurationStrategy
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ModificationTracker
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiModificationTracker
import jetbrains.buildServer.messages.serviceMessages.ServiceMessage
import dev.openidea.goide.run.corpus.GoFuzzCorpusConsoleFilter

class GoTestConsoleProperties(configuration: GoRunConfiguration, executor: Executor, private val output: GoTestOutput) :
    SMTRunnerConsoleProperties(configuration, "OpenIDEAGo", executor), SMCustomMessagesParsing {
    val fuzzCorpusFilter = GoFuzzCorpusConsoleFilter(output.corpusPackageDirectory())
    init {
        isIdBasedTestTree = true
        addStackTraceFilter(fuzzCorpusFilter)
    }
    private val locator = GoTestLocator(output)
    override fun getTestLocator(): SMTestLocator = locator
    override fun createTestEventsConverter(testFrameworkName: String, consoleProperties: TestConsoleProperties): OutputToGeneralTestEventsConverter =
        GoTestEventsConverter(testFrameworkName, consoleProperties)
}

/** Keep the platform's native processor, console, output ownership, history, and parallel id handling. */
internal open class GoTestEventsConverter : OutputToGeneralTestEventsConverter {
    constructor(framework: String, properties: TestConsoleProperties) : super(framework, properties)
    internal constructor(framework: String) : super(framework, true, false, false)
    private var nativeProcessor: GeneralIdBasedToSMTRunnerEventsConvertor? = null
    private val countedLeaves = mutableSetOf<String>()

    override fun setProcessor(value: GeneralTestEventsProcessor?) {
        nativeProcessor = value as? GeneralIdBasedToSMTRunnerEventsConvertor
        if (value != null) countedLeaves.clear()
        super.setProcessor(value)
    }

    override fun handleUnexpectedServiceMessage(message: ServiceMessage) {
        val events = nativeProcessor ?: return super.handleUnexpectedServiceMessage(message)
        when (message.messageName) {
            "goTestProgress" -> {
                // Go parent elapsed includes its own work and waits; summing leaves loses that time.
                events.onDurationStrategyChanged(TestDurationStrategy.MANUAL)
                events.onCustomProgressTestsCategory("Tests", 0)
            }
            "goTestSuite" -> {
                val id = message.attributes["nodeId"] ?: return
                val proxy = events.findProxyById(id) ?: return
                if (!proxy.isSuite) proxy.setSuiteStarted()
                // The immediately following child-start notification refreshes this same native node.
            }
            "goTestLeafResult" -> {
                val id = message.attributes["nodeId"] ?: return
                val proxy = events.findProxyById(id) ?: return
                if (proxy.isSuite || !countedLeaves.add(id)) return
                // Go's run event cannot say whether a test will later create dynamic subtests.
                // Count only confirmed leaves; provisional parents never become extra tests.
                events.onCustomProgressTestStarted()
                if (message.attributes["outcome"] == "fail") events.onCustomProgressTestFailed()
                events.onCustomProgressTestFinished()
            }
            "goTestDuration" -> {
                val proxy = message.attributes["nodeId"]?.let(events::findProxyById) ?: return
                val reported = message.attributes["duration"]?.toLongOrNull()?.takeIf { it >= 0 }
                // Only virtual grouping paths (or absent Go elapsed) use direct-child totals.
                val duration = reported ?: proxy.children.mapNotNull { it.duration }.takeIf { it.isNotEmpty() }?.sum()
                if (duration != null) proxy.setDuration(duration)
            }
            else -> super.handleUnexpectedServiceMessage(message)
        }
    }
}

/** SMTestProxy's location URL is immutable; this stable URL resolves to the latest reported Go source position. */
internal class GoTestLocator(private val output: GoTestOutput) : SMTestLocator, DumbAware {
    override fun getLocation(protocol: String, path: String, project: Project, scope: GlobalSearchScope): List<Location<*>> {
        if (protocol != GoTestOutput.LOCATION_PROTOCOL) return FileUrlProvider.INSTANCE.getLocation(protocol, path, project, scope)
        val location = output.locationForToken(path) ?: return emptyList()
        return FileUrlProvider.INSTANCE.getLocation("file", location.removePrefix("file://"), project, scope)
    }
    override fun getLocationCacheModificationTracker(project: Project): ModificationTracker =
        ModificationTracker { output.locationRevision + PsiModificationTracker.getInstance(project).modificationCount }
}
