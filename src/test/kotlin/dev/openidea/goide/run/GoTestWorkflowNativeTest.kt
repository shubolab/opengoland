package dev.openidea.goide.run

import com.intellij.testFramework.LightPlatformTestCase
import org.jdom.Element

class GoTestWorkflowNativeTest : LightPlatformTestCase() {
    fun testFrameworkFieldsPersistAndCloneIndependently() {
        val factory = GoTestConfigurationType().configurationFactories.single()
        val config = factory.createTemplateConfiguration(project) as GoRunConfiguration
        config.parameters = GoRunParameters(testMode = GoTestMode.FUZZ, fuzzPattern = "^Fuzz中文$", fuzzTime = "100x",
            fuzzMinimizeTime = "0", fuzzParallelism = "2", benchmarkPattern = "^BenchmarkOther$", benchmarkTime = "3x",
            benchmarkMemory = false, environment = linkedMapOf("FIXTURE" to "😀"))
        val element = Element("configuration")
        config.writeExternal(element)
        val restored = factory.createTemplateConfiguration(project) as GoRunConfiguration
        restored.readExternal(element)
        assertEquals(config.parameters, restored.parameters)
        val clone = restored.clone()
        clone.parameters.environment["FIXTURE"] = "changed"
        assertEquals("😀", restored.parameters.environment["FIXTURE"])
        val old = Element("configuration").apply { addContent(Element("openidea-go").setAttribute("testPattern", "^TestOld$")) }
        restored.readExternal(old)
        assertEquals(GoTestMode.TEST, restored.parameters.testMode)
        assertEquals("^TestOld$", restored.parameters.testPattern)
        assertEquals("1s", restored.parameters.benchmarkTime)
    }

    fun testCoverageDoesNotClaimBenchmarkOrFuzzFrameworks() {
        val config = GoTestConfigurationType().configurationFactories.single().createTemplateConfiguration(project) as GoRunConfiguration
        val runner = GoProgramRunner()
        assertTrue(runner.canRun("Coverage", config))
        for (mode in listOf(GoTestMode.BENCHMARK, GoTestMode.FUZZ)) {
            config.parameters.testMode = mode
            assertFalse(runner.canRun("Coverage", config))
            assertTrue(runner.canRun("Run", config))
        }
    }
}
