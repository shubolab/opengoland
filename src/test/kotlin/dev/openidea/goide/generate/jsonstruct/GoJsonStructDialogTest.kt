package dev.openidea.goide.generate.jsonstruct

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.application.impl.LaterInvocator
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.testFramework.LightPlatformTestCase
import com.intellij.ui.EditorTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.UIUtil
import dev.openidea.goide.generate.tests.genString
import java.awt.Component
import java.awt.Container
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.swing.JButton
import javax.swing.JTextArea
import javax.swing.JTextField

class GoJsonStructDialogTest : LightPlatformTestCase() {
    override fun runInDispatchThread() = true
    private data class Pending(val request: JsonObject, val success: (JsonObject) -> Unit, val failure: (String) -> Unit)

    fun testEmptyDialogDoesNotInspectAndOwnsARealMultilineEditorUntilCancellation() {
        val pending = mutableListOf<Pending>()
        val dialog = dialog("type", pending)
        val nativeEditor = json(dialog).getEditor(true)!!
        try {
            assertSame(json(dialog).document, nativeEditor.document)
            assertFalse(nativeEditor.isOneLineMode)
            assertFalse(dialog.isOKActionEnabled)
            assertFalse(analyzeButton(dialog).isEnabled)
            assertTrue(messages(dialog).text.contains("Paste JSON"))
            assertTrue(pending.isEmpty())
            json(dialog).text = "{\n  \"a\": 1\n}"
            assertTrue(analyzeButton(dialog).isEnabled)
            assertTrue("Typing must not start background inference", pending.isEmpty())
            analyzeButton(dialog).doClick()
            assertFalse(dialog.isOKActionEnabled)
            assertEquals(json(dialog).text, pending.single().request.genString("jsonText"))
            pending.single().success(response("type", "first"))
            assertTrue(dialog.isOKActionEnabled)
            assertEquals(pending.single().request, dialog.backendRequest())
        } finally { dialog.close(DialogWrapper.CANCEL_EXIT_CODE) }
        UIUtil.dispatchAllInvocationEvents()
        assertTrue("Cancelling the dialog must release its native editor", nativeEditor.isDisposed)
    }

    fun testJsonAndPolicyEditsExpireAnalysisAndOutOfOrderRepliesCannotRestoreOldIds() {
        val pending = mutableListOf<Pending>()
        val dialog = dialog("type", pending)
        try {
            json(dialog).text = "{\"a\":1}"
            analyzeButton(dialog).doClick()
            json(dialog).text = " {\"a\":2} \n"
            typeName(dialog).text = "Sample"
            numeric(dialog).selectedIndex = 1
            analyzeButton(dialog).doClick()
            assertEquals(2, pending.size)
            pending[0].success(response("type", "obsolete"))
            assertFalse(dialog.isOKActionEnabled)
            pending[1].success(response("type", "current"))
            val selected = dialog.selection()
            assertEquals("current", selected.genString("inspectionId"))
            assertEquals(" {\"a\":2} \n", selected.genString("jsonText"))
            assertEquals("Sample", selected.genString("typeName"))
            assertEquals("number", selected.genString("numericPolicy"))
            assertFalse("Type generation uses the whole inferred root", selected.has("fieldIds"))
            assertFalse(fields(dialog).model.isCellEditable(0, 0))
            json(dialog).text += " "
            assertFalse(dialog.isOKActionEnabled)
            assertTrue(messages(dialog).text.contains("previous analysis"))
            assertTrue(runCatching { dialog.selection() }.exceptionOrNull() is IllegalStateException)
        } finally { dialog.close(DialogWrapper.CANCEL_EXIT_CODE) }
    }

    fun testFieldsUseOnlyEditableLatestIdsAndShowNullableOptionalAndMixedSampleWarnings() {
        val pending = mutableListOf<Pending>()
        val dialog = dialog("fields", pending)
        try {
            json(dialog).text = "[{\"a\":null},{\"a\":1,\"b\":true}]"
            analyzeButton(dialog).doClick()
            pending.single().success(response("fields", "one"))
            val table = fields(dialog)
            assertEquals(listOf(true, true, false), (0..2).map { table.model.getValueAt(it, 0) })
            assertFalse(table.model.isCellEditable(2, 0))
            assertTrue(table.model.getValueAt(0, 4).toString().contains("Contains null"))
            assertTrue(table.model.getValueAt(0, 4).toString().contains("Missing in some samples"))
            assertEquals("Existing JSON key", table.model.getValueAt(2, 4))
            assertTrue(messages(dialog).text.contains("$.a: Mixed samples use any"))
            table.model.setValueAt(false, 0, 0)
            table.model.setValueAt(true, 2, 0)
            assertEquals(listOf("one-b"), dialog.selection().ids())
            numeric(dialog).selectedIndex = 1
            assertFalse(table.model.isCellEditable(1, 0))
            analyzeButton(dialog).doClick()
            pending.last().success(response("fields", "two"))
            val selected = dialog.selection()
            assertEquals(listOf("two-b"), selected.ids())
            assertEquals("fields", selected.genString("mode"))
            assertFalse(selected.has("typeName"))
        } finally { dialog.close(DialogWrapper.CANCEL_EXIT_CODE) }
    }

    fun testDraftMustReanalyzeAndBackgroundReplyCanArriveInsideDialogModality() {
        val pending = mutableListOf<Pending>()
        val first = dialog("fields", pending)
        var saved: JsonObject
        try {
            json(first).text = "{\"a\":1,\"b\":true}"
            analyzeButton(first).doClick()
            pending.single().success(response("fields", "before"))
            fields(first).model.setValueAt(false, 0, 0)
            saved = first.draft().apply { addProperty("mode", "type"); addProperty("inspectionId", "obsolete") }
        } finally { first.close(DialogWrapper.CANCEL_EXIT_CODE) }
        pending.clear()
        val restored = GoJsonStructDialog(project, JsonObject(), "fields", draft = saved) { request, success, failure -> pending += Pending(request, success, failure) }
        try {
            assertFalse(restored.isOKActionEnabled)
            assertTrue("Restoring a draft does not launch inference before the dialog opens", pending.isEmpty())
            analyzeButton(restored).doClick()
            assertEquals("fields", pending.single().request.genString("mode"))
            val modal = restored.disposable
            LaterInvocator.enterModal(modal)
            try {
                CompletableFuture.runAsync { pending.single().success(response("fields", "modal")) }.get(5, TimeUnit.SECONDS)
                UIUtil.dispatchAllInvocationEvents()
                assertTrue(restored.isOKActionEnabled)
                assertEquals(listOf("modal-b"), restored.selection().ids())
                assertEquals("modal", restored.selection().genString("inspectionId"))
            } finally { LaterInvocator.leaveModal(modal) }
        } finally { restored.close(DialogWrapper.CANCEL_EXIT_CODE) }
    }

    fun testFailuresWrongModeConflictsAndDisposedRepliesCannotProduceAPreview() {
        val pending = mutableListOf<Pending>()
        val dialog = dialog("type", pending)
        try {
            json(dialog).text = "{broken"
            analyzeButton(dialog).doClick()
            pending.last().failure("Expected a quoted JSON property")
            assertTrue(messages(dialog).text.contains("quoted JSON property"))
            assertFalse(dialog.isDisposed)
            assertFalse(dialog.isOKActionEnabled)
            json(dialog).text = "{}"
            analyzeButton(dialog).doClick()
            pending.last().success(response("fields", "wrong-mode"))
            assertFalse(dialog.isOKActionEnabled)
            assertTrue(messages(dialog).text.contains("different generation mode"))
            analyzeButton(dialog).doClick()
            pending.last().success(response("type", "conflict").apply {
                getAsJsonArray("conflicts").add(JsonObject().apply { addProperty("message", "Root is already declared") })
            })
            assertFalse(dialog.isOKActionEnabled)
            assertTrue(messages(dialog).text.contains("Root is already declared"))
            typeName(dialog).text = "AnotherRoot"
            analyzeButton(dialog).doClick()
        } finally { dialog.close(DialogWrapper.CANCEL_EXIT_CODE) }
        CompletableFuture.runAsync { pending.last().success(response("type", "late")) }.get(5, TimeUnit.SECONDS)
        UIUtil.dispatchAllInvocationEvents()
        assertTrue(dialog.isDisposed)
        assertTrue("A reply after disposal cannot replace the last accepted request", dialog.backendRequest().genString("typeName") != "AnotherRoot")
    }

    fun testNullRootIsAVisibleInferenceAndInvalidRootNamesDoNotStartAnalysis() {
        val pending = mutableListOf<Pending>()
        val dialog = dialog("type", pending)
        try {
            json(dialog).text = "null"
            typeName(dialog).text = "not a Go name"
            assertFalse(analyzeButton(dialog).isEnabled)
            assertTrue(pending.isEmpty())
            typeName(dialog).text = "NullableRoot"
            analyzeButton(dialog).doClick()
            pending.single().success(response("type", "null").apply {
                addProperty("rootType", "any"); add("fields", JsonArray())
                addProperty("preview", "type NullableRoot any\n")
                add("warnings", JsonArray().apply { add(JsonObject().apply {
                    addProperty("path", "$"); addProperty("code", "null-inference"); addProperty("message", "Null provides no concrete type; using any")
                }) })
            })
            assertTrue(dialog.isOKActionEnabled)
            assertTrue(messages(dialog).text.contains("Null provides no concrete type; using any"))
            assertTrue(components(dialog).filterIsInstance<JBLabel>().any { it.text == "Inferred Go type: any" })
            assertTrue(components(dialog).filterIsInstance<JTextArea>().any { it.text == "type NullableRoot any\n" })
            assertEquals("null", dialog.selection().genString("jsonText"))
        } finally { dialog.close(DialogWrapper.CANCEL_EXIT_CODE) }
    }

    private fun dialog(mode: String, pending: MutableList<Pending>) = GoJsonStructDialog(project, JsonObject(), mode) { request, success, failure -> pending += Pending(request, success, failure) }
    private fun response(mode: String, id: String) = JsonObject().apply {
        addProperty("inspectionId", id); addProperty("configurationDigest", "digest-$id"); addProperty("structId", "struct-$id")
        addProperty("mode", mode); addProperty("rootType", "Root"); addProperty("preview", "type Root struct { A any; B bool }\n")
        add("fields", JsonArray().apply {
            add(field(id, "a", editable = true).apply { addProperty("optional", true); addProperty("nullable", true) })
            add(field(id, "b", editable = true)); add(field(id, "existing", editable = false).apply { addProperty("reason", "Existing JSON key") })
        })
        add("declarations", JsonArray().apply { add(JsonObject().apply { addProperty("name", "Root"); addProperty("kind", "struct"); addProperty("type", "struct { A any; B bool }") }) })
        add("warnings", JsonArray().apply { add(JsonObject().apply { addProperty("path", "$.a"); addProperty("code", "mixed"); addProperty("message", "Mixed samples use any") }) })
        add("conflicts", JsonArray())
    }
    private fun field(id: String, key: String, editable: Boolean) = JsonObject().apply {
        addProperty("fieldId", "$id-$key"); addProperty("jsonKey", key); addProperty("name", key.replaceFirstChar(Char::uppercaseChar))
        addProperty("type", if (key == "a") "any" else "bool"); addProperty("editable", editable)
    }
    private fun JsonObject.ids() = getAsJsonArray("fieldIds").map { it.asString }
    private fun json(dialog: GoJsonStructDialog) = components(dialog).filterIsInstance<EditorTextField>().single()
    private fun typeName(dialog: GoJsonStructDialog) = components(dialog).filterIsInstance<JBLabel>().single { it.text == "Root type name:" }.labelFor as JTextField
    private fun numeric(dialog: GoJsonStructDialog) = components(dialog).filterIsInstance<ComboBox<*>>().single()
    private fun fields(dialog: GoJsonStructDialog) = components(dialog).filterIsInstance<JBTable>().single { table -> (0 until table.columnCount).any { table.model.getColumnName(it) == "JSON key" } }
    private fun messages(dialog: GoJsonStructDialog) = components(dialog).filterIsInstance<JTextArea>().single { !it.isOpaque }
    private fun analyzeButton(dialog: GoJsonStructDialog) = components(dialog).filterIsInstance<JButton>().single { it.text == "Analyze / Refresh" }
    private fun components(dialog: GoJsonStructDialog): List<Component> {
        // DialogWrapper's SDK headless peer discards contentPane; init still builds the actual UI.
        val field = GoJsonStructDialog::class.java.getDeclaredField("panel").apply { isAccessible = true }
        return components(field.get(dialog) as Container)
    }
    private fun components(container: Container): List<Component> = container.components.flatMap { listOf(it) + if (it is Container) components(it) else emptyList() }
}
