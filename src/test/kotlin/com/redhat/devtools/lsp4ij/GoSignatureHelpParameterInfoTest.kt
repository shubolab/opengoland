package com.redhat.devtools.lsp4ij

import com.intellij.codeInsight.CodeInsightBundle
import com.intellij.lang.parameterInfo.ParameterInfoUIContext
import com.intellij.testFramework.LightPlatformTestCase
import com.redhat.devtools.lsp4ij.features.signatureHelp.LSPParameterInfoHandler
import org.eclipse.lsp4j.ParameterInformation
import org.eclipse.lsp4j.SignatureInformation
import org.eclipse.lsp4j.jsonrpc.messages.Tuple
import java.awt.Color
import java.lang.reflect.Proxy

/** Actual upstream parameter renderer; only the platform presentation sink is recorded. */
class GoSignatureHelpParameterInfoTest : LightPlatformTestCase() {
    override fun runInDispatchThread() = false

    fun testMissingParameterListUsesTheExistingEmptyListPresentationInBothModes() {
        for (single in listOf(false, true)) {
            val missing = signature("Greeting()", null)
            assertNull("The protocol's omitted parameter array must remain null in this control", missing.parameters)
            val actual = present(missing, single = single)
            val empty = present(signature("Greeting()", emptyList()), single = single)
            assertEquals(empty, actual)
            assertEquals(CodeInsightBundle.message("parameter.info.no.parameters"), actual.text)
            assertEquals(-1, actual.start)
            assertEquals(-1, actual.end)
            assertEquals(single, actual.raw)
        }
    }

    fun testStringLabelsAndVariadicLabelTextKeepTheirPresentationAndActiveRange() {
        val first = "prefix string"
        val last = "values ...string"
        val actual = present(signature("Join($first, $last)", listOf(parameter(first), parameter(last))), active = 1)
        val prefix = " $first, "
        assertEquals(prefix + " $last", actual.text)
        assertEquals(prefix.length, actual.start)
        assertEquals(actual.text.length, actual.end)
        assertEquals(" $last", actual.text.substring(actual.start, actual.end))
        assertFalse(actual.disabled)
        assertSame(parameterColor, actual.color)
    }

    fun testTupleLabelsRetainUtf16OffsetsAfterChineseAndEmoji() {
        val first = "前缀😀 string"
        val last = "values ...string"
        val label = "Join($first, $last)"
        val parameters = listOf(first, last).map { text ->
            ParameterInformation().apply { setLabel(Tuple.two(label.indexOf(text), label.indexOf(text) + text.length)) }
        }
        val actual = present(signature(label, parameters), active = 1)
        val prefix = " $first, "
        assertEquals(prefix + " $last", actual.text)
        assertEquals(prefix.length, actual.start)
        assertEquals(actual.text.length, actual.end)
        assertEquals(" $last", actual.text.substring(actual.start, actual.end))
    }

    fun testDisabledPresentationKeepsTheLabelsWithoutAnActiveHighlight() {
        val actual = present(signature("Join(prefix string, values ...string)",
            listOf(parameter("prefix string"), parameter("values ...string"))), active = 1, enabled = false)
        assertEquals(" prefix string,  values ...string", actual.text)
        assertEquals(-1, actual.start)
        assertEquals(-1, actual.end)
        assertTrue(actual.disabled)
        assertFalse(actual.strikeout)
        assertFalse(actual.disabledBeforeDoc)
        assertSame(parameterColor, actual.color)
    }

    private data class Presentation(
        val text: String,
        val start: Int = -1,
        val end: Int = -1,
        val disabled: Boolean = false,
        val strikeout: Boolean = false,
        val disabledBeforeDoc: Boolean = false,
        val color: Color? = null,
        val raw: Boolean = false,
    )

    private val parameterColor = Color(20, 30, 40)

    private fun present(signature: SignatureInformation, active: Int = 0, single: Boolean = false, enabled: Boolean = true): Presentation {
        val calls = mutableListOf<Presentation>()
        val context = Proxy.newProxyInstance(ParameterInfoUIContext::class.java.classLoader, arrayOf(ParameterInfoUIContext::class.java)) { _, method, args ->
            when (method.name) {
                "getCurrentParameterIndex" -> active
                "isSingleParameterInfo" -> single
                "isUIComponentEnabled" -> enabled
                "getDefaultParameterColor" -> parameterColor
                "setupUIComponentPresentation" -> {
                    calls += Presentation(args[0] as String, args[1] as Int, args[2] as Int,
                        args[3] as Boolean, args[4] as Boolean, args[5] as Boolean, args[6] as Color)
                    args[0] as String
                }
                "setupRawUIComponentPresentation" -> { calls += Presentation(args[0] as String, raw = true); null }
                else -> error("Unexpected parameter UI operation: ${method.name}")
            }
        } as ParameterInfoUIContext
        LSPParameterInfoHandler().updateUI(signature, context)
        assertEquals("The real handler must still publish one parameter presentation", 1, calls.size)
        return calls.single()
    }

    private fun signature(label: String, parameters: List<ParameterInformation>?) = SignatureInformation().apply {
        this.label = label
        this.parameters = parameters
    }

    private fun parameter(label: String) = ParameterInformation().apply { setLabel(label) }
}
