package dev.openidea.goide.run

import com.google.gson.JsonObject
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.testframework.sm.runner.GeneralIdBasedToSMTRunnerEventsConvertor
import com.intellij.execution.testframework.sm.runner.SMTRunnerEventsAdapter
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.execution.testframework.sm.runner.states.TestStateInfo.Magnitude
import com.intellij.openapi.project.Project
import com.intellij.util.messages.MessageBus
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Actual SDK service-message parser and native proxy states, with only the project bus boundary stubbed. */
class GoCancelledParentNativeTest {
    @Test fun stoppedParentTeardownRemainsVisibleAfterItsChildAlreadyPassed() {
        for (terminal in listOf(null, "pass", "fail")) verifyStoppedParent(terminal, false)
    }

    @Test fun stoppedParentTeardownKeepsItsEarlierChildFailureAndDoesNotInventAParentRetry() {
        for (terminal in listOf(null, "pass", "fail")) verifyStoppedParent(terminal, true)
    }

    private fun verifyStoppedParent(terminal: String?, childFailed: Boolean) {
        val root = SMTestProxy.SMRootTestProxy()
        val processor = GeneralIdBasedToSMTRunnerEventsConvertor(project(), root, "Go cancelled parent native test")
        val converter = Records()
        val output = GoTestOutput("/tmp/unused")
        converter.setProcessor(processor)
        converter.startTesting()
        try {
            output.consume(event("run", "TestParent"), converter::accept)
            output.consume(event("run", "TestParent/child"), converter::accept)
            output.consume(event(if (childFailed) "fail" else "pass", "TestParent/child"), converter::accept)
            // The real parent's teardown/body is still active after the child completed.
            // SIGINT may produce a late terminal record or stop before reporting one.
            output.requestCancellation()
            if (terminal != null) {
                output.consume(event(terminal, "TestParent"), converter::accept)
                output.consume(event(terminal), converter::accept)
            }
            output.finish(130, true, converter::accept)
            converter.flushBufferOnProcessTermination(130)
            converter.finishTesting()

            val nodes = root.allTests.associateBy { it.getUserData(SMTestProxy.NODE_ID) }
            val parent = requireNotNull(nodes["go-test:p#TestParent:1"])
            val child = requireNotNull(nodes["go-test:p#TestParent/child:1"])
            assertTrue(parent.isSuite)
            assertTrue(child.isFinal)
            assertEquals(childFailed, child.isDefect)
            if (!childFailed) assertTrue(child.isPassed)
            assertEquals(if (childFailed) mapOf("p" to listOf("TestParent/child")) else emptyMap(), output.failures())
            assertTrue(parent.children.any { it.isIgnored || it.isInterrupted },
                "Parent cancellation disappeared after terminal=$terminal, childFailed=$childFailed: " +
                    "parentPassed=${parent.isPassed}; children=${parent.children.map { "${it.name}:${it.magnitudeInfo}" }}")
            // The fixed SDK deliberately reports ignored tests as isDefect=true.
            // Inspect the actual failure/error magnitudes rather than conflating them with cancellation.
            assertFalse(parent.children.filterNot { it === child }.any {
                it.magnitudeInfo == Magnitude.FAILED_INDEX || it.magnitudeInfo == Magnitude.ERROR_INDEX
            }, "Cancellation must not create a synthetic failure")
        } finally { converter.dispose(); output.close() }
    }

    private class Records : GoTestEventsConverter("Go cancelled parent native test") {
        fun accept(record: String) = processConsistentText(record, ProcessOutputTypes.STDOUT)
    }

    private fun event(action: String, test: String? = null) = JsonObject().apply {
        addProperty("Action", action); addProperty("Package", "p")
        test?.let { addProperty("Test", it) }
        if (action == "pass" || action == "fail") addProperty("Elapsed", .125)
    }.toString() + "\n"

    private fun project(): Project {
        val sink = object : SMTRunnerEventsAdapter() {}
        val bus = Proxy.newProxyInstance(MessageBus::class.java.classLoader, arrayOf(MessageBus::class.java)) { _, method, _ ->
            if (method.name == "syncPublisher") sink else null
        } as MessageBus
        return Proxy.newProxyInstance(Project::class.java.classLoader, arrayOf(Project::class.java)) { _, method, _ ->
            if (method.name == "getMessageBus") bus else null
        } as Project
    }
}
