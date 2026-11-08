package dev.openidea.goide.generate.methods

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.application.impl.LaterInvocator
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.testFramework.LightPlatformTestCase
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.UIUtil
import dev.openidea.goide.generate.tests.genString
import java.awt.Component
import java.awt.Container
import java.awt.event.FocusEvent
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.SwingUtilities

class GoImplementMethodsDialogTest : LightPlatformTestCase() {
    override fun runInDispatchThread() = true

    fun testInitialImplicitRequestStaysExactAndOnlyMissingMethodsAreSelectable() {
        val dialog = GoImplementMethodsDialog(project, inspection()) { _, _, _ -> fail("The initial inspection is usable") }
        try {
            assertTrue(dialog.isOKActionEnabled)
            assertEquals(JsonObject(), dialog.backendRequest())
            val table = components(dialog).filterIsInstance<JBTable>().single()
            assertEquals(listOf(true, true, false, false), (0..3).map { table.model.getValueAt(it, 0) })
            assertFalse(table.model.isCellEditable(2, 0)); assertFalse(table.model.isCellEditable(3, 0))
            table.model.setValueAt(false, 0, 0)
            val result = dialog.selection()
            assertFalse(result.get("allMissing").asBoolean)
            assertEquals(listOf("write"), result.getAsJsonArray("methodIds").map { it.asString })
            assertFalse("Implicit caret inference must not be replaced with uninspected explicit request fields", result.has("receiverType"))
            assertEquals("original", result.genString("inspectionId"))
        } finally { dialog.close(DialogWrapper.CANCEL_EXIT_CODE) }
    }

    fun testChangedFieldsAndOutOfOrderInspectionCannotReuseOldIds() {
        data class Pending(val request: JsonObject, val success: (JsonObject) -> Unit, val failure: (String) -> Unit)
        val pending = mutableListOf<Pending>()
        val dialog = GoImplementMethodsDialog(project, inspection()) { request, success, failure -> pending += Pending(request, success, failure) }
        try {
            val pointer = components(dialog).filterIsInstance<JBCheckBox>().single { it.text == "Pointer receiver" }
            pointer.doClick()
            assertFalse(dialog.isOKActionEnabled)
            analyze(dialog)
            assertEquals("value", pending.single().request.genString("receiverKind"))
            combo(dialog, "Receiver type:").selectedIndex = 2 // Beta
            analyze(dialog)
            pending[0].success(inspection("old-value"))
            assertFalse("A stale success cannot enable Preview for Beta", dialog.isOKActionEnabled)
            pending[1].success(inspection("beta-value", receiverName = "Beta", kind = "value"))
            assertTrue(dialog.isOKActionEnabled)
            assertEquals(pending[1].request, dialog.backendRequest())
            val result = dialog.selection()
            assertEquals("beta-value", result.genString("inspectionId"))
            assertEquals("Beta", result.genString("receiverType"))
            field(dialog, "Additional import path (optional):").text = "example.org/contracts"
            analyze(dialog)
            pending.last().failure("Cannot resolve the requested interface")
            assertFalse(dialog.isDisposed)
            assertFalse(dialog.isOKActionEnabled)
            assertTrue(components(dialog).filterIsInstance<JTextArea>().single().text.contains("Cannot resolve"))
        } finally { dialog.close(DialogWrapper.CANCEL_EXIT_CODE) }
    }

    fun testInterfaceEntryCanChooseANewStructAndManualGenericInterface() {
        var captured: JsonObject? = null
        val dialog = GoImplementMethodsDialog(project, inspection(receiverName = "")) { request, success, _ ->
            captured = request
            success(inspection("new", receiverName = "Adapter", isNew = true))
        }
        try {
            assertFalse(dialog.isOKActionEnabled)
            val receiver = combo(dialog, "Receiver type:")
            receiver.selectedIndex = receiver.itemCount - 1
            field(dialog, "New struct name:").text = "Adapter"
            combo(dialog, "Interface:").editor.item = "contracts.Mapper"
            field(dialog, "Additional import path (optional):").text = "example.org/contracts"
            field(dialog, "Interface type arguments:").text = "map[string]Pair[int, string], func(int, string) error"
            analyze(dialog)
            val request = captured!!
            assertEquals("Adapter", request.genString("newTypeName"))
            assertFalse(request.has("receiverType"))
            assertEquals("contracts.Mapper", request.genString("interfaceExpression"))
            assertEquals(listOf("map[string]Pair[int, string]", "func(int, string) error"), request.getAsJsonArray("interfaceTypeArguments").map { it.asString })
            assertEquals(request, dialog.backendRequest())
            assertEquals("new", dialog.selection().genString("inspectionId"))
        } finally { dialog.close(DialogWrapper.CANCEL_EXIT_CODE) }
    }

    fun testDraftRestorationRequiresNewInspectionAndPreservesPartialSelectionAndReceiverVariable() {
        var draft: JsonObject
        val first = GoImplementMethodsDialog(project, inspection()) { _, _, _ -> fail("No initial refresh") }
        try {
            val table = components(first).filterIsInstance<JBTable>().single()
            table.model.setValueAt(false, 0, 0)
            components(first).filterIsInstance<JTextField>().single { it.text == "r" }.text = "self"
            assertTrue("The receiver variable is a plan-only choice", first.isOKActionEnabled)
            draft = first.draft()
        } finally { first.close(DialogWrapper.CANCEL_EXIT_CODE) }
        val restored = GoImplementMethodsDialog(project, inspection(), draft = draft) { _, success, _ -> success(inspection("reinspected")) }
        try {
            assertFalse("Restored fields must never be paired with the original IDs", restored.isOKActionEnabled)
            analyze(restored)
            val result = restored.selection()
            assertEquals("reinspected", result.genString("inspectionId"))
            assertEquals("self", result.genString("receiverName"))
            assertEquals(listOf("write"), result.getAsJsonArray("methodIds").map { it.asString })
        } finally { restored.close(DialogWrapper.CANCEL_EXIT_CODE) }
    }

    fun testPreModalAnalyzeReceivesBackgroundReplyInsideModalityAndIgnoresReplyAfterDisposal() {
        var success: ((JsonObject) -> Unit)? = null
        val draft = JsonObject().apply {
            addProperty("receiverType", "Alpha"); addProperty("receiverKind", "pointer"); addProperty("receiverName", "r")
            addProperty("interfaceId", "example.org/fixture.Reader")
        }
        val dialog = GoImplementMethodsDialog(project, inspection(), draft = draft) { _, callback, _ -> success = callback }
        analyze(dialog)
        val modal = dialog.disposable
        LaterInvocator.enterModal(modal)
        try {
            CompletableFuture.runAsync { success!!(inspection("modal")) }.get(5, TimeUnit.SECONDS)
            UIUtil.dispatchAllInvocationEvents()
            assertTrue(dialog.isOKActionEnabled)
            assertEquals("modal", dialog.selection().genString("inspectionId"))
        } finally {
            LaterInvocator.leaveModal(modal)
            dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
        }
        success!!(inspection("disposed"))
        assertTrue(dialog.isDisposed)
    }

    fun testDraftInterfaceOutsideTheInitialCatalogIsKeptForReanalysis() {
        val extraId = "example.org/additional.Mapper"
        val draft = JsonObject().apply {
            addProperty("receiverType", "Alpha"); addProperty("receiverKind", "pointer"); addProperty("receiverName", "panic")
            addProperty("interfaceId", extraId); addProperty("interfaceExpression", "")
            addProperty("interfaceImportPath", "example.org/additional")
        }
        var sent: JsonObject? = null
        val dialog = GoImplementMethodsDialog(project, inspection(), draft = draft) { request, success, _ ->
            sent = request
            success(inspection("restored-extra").apply {
                val extra = JsonObject().apply { addProperty("interfaceId", extraId); addProperty("display", "additional.Mapper"); addProperty("name", "Mapper") }
                getAsJsonArray("interfaces").add(extra)
                add("selectedInterface", extra.deepCopy())
            })
        }
        try {
            assertFalse(dialog.isOKActionEnabled)
            assertEquals(extraId, combo(dialog, "Interface:").editor.item.toString())
            analyze(dialog)
            assertEquals(extraId, sent!!.genString("interfaceId"))
            assertEquals("example.org/additional", sent!!.genString("interfaceImportPath"))
            assertTrue(dialog.isOKActionEnabled)
            assertEquals(extraId, dialog.backendRequest().genString("interfaceId"))
            assertEquals("restored-extra", dialog.selection().genString("inspectionId"))
        } finally { dialog.close(DialogWrapper.CANCEL_EXIT_CODE) }
    }

    fun testDuplicateCatalogLabelsShowPackagePathsAndAnalyzeTheSelectedIdentity() {
        val paths = listOf("math/rand", "math/rand/v2")
        fun response(selectedId: String = "") = inspection("catalog-$selectedId").apply {
            paths.forEach { path ->
                getAsJsonArray("interfaces").add(JsonObject().apply {
                    addProperty("interfaceId", "$path.Source"); addProperty("display", "rand.Source")
                    addProperty("name", "Source"); addProperty("packagePath", path)
                })
            }
            getAsJsonArray("interfaces").firstOrNull { it.asJsonObject.genString("interfaceId") == selectedId }
                ?.let { add("selectedInterface", it.deepCopy()) }
        }
        val analyzed = mutableListOf<JsonObject>()
        val dialog = GoImplementMethodsDialog(project, response()) { request, success, _ ->
            analyzed += request.deepCopy()
            success(response(request.genString("interfaceId")))
        }
        try {
            val choices = combo(dialog, "Interface:")
            for (path in paths) {
                val label = "rand.Source — $path"
                choices.selectedIndex = (0 until choices.itemCount).single { choices.getItemAt(it).toString() == label }
                choices.selectedItem = choices.editor.item.toString()
                (choices.editor.editorComponent as JTextField).postActionEvent()
                analyze(dialog)
                assertEquals("$path.Source", analyzed.last().genString("interfaceId"))
                assertFalse(analyzed.last().has("interfaceExpression"))
                assertEquals(analyzed.last(), dialog.backendRequest())
                assertEquals("$path.Source", dialog.selection().genString("interfaceId"))
            }
            assertEquals(paths.map { "$it.Source" }, analyzed.map { it.genString("interfaceId") })
            choices.editor.item = "io.Reader"
            analyze(dialog)
            assertEquals("io.Reader", analyzed.last().genString("interfaceExpression"))
            assertFalse("Manual expressions must not inherit the previous catalog identity", analyzed.last().has("interfaceId"))
        } finally { dialog.close(DialogWrapper.CANCEL_EXIT_CODE) }
    }

    fun testNativeEditableCommitAndQueuedFocusEventsKeepCompletedAnalysisButRealEditsExpireIt() {
        data class Pending(val request: JsonObject, val success: (JsonObject) -> Unit)
        val pending = mutableListOf<Pending>()
        val dialog = GoImplementMethodsDialog(project, inspection()) { request, success, _ -> pending += Pending(request, success) }
        try {
            combo(dialog, "Receiver type:").selectedIndex = 2
            analyze(dialog)
            CompletableFuture.runAsync { pending[0].success(inspection("background-beta", receiverName = "Beta")) }.get(5, TimeUnit.SECONDS)
            UIUtil.dispatchAllInvocationEvents()
            assertTrue(dialog.isOKActionEnabled)
            val originalRequest = dialog.backendRequest()
            val choices = combo(dialog, "Interface:")
            val editor = choices.editor.editorComponent as JTextField
            val button = components(dialog).filterIsInstance<JButton>().single { it.text == "Analyze / Refresh" }
            SwingUtilities.invokeLater {
                // Use the actual editor/ComboBox action listeners and UI focus handlers.
                editor.postActionEvent()
                val focusLost = FocusEvent(editor, FocusEvent.FOCUS_LOST, false, button)
                editor.focusListeners.forEach { it.focusLost(focusLost) }
                choices.selectedItem = editor.text
                editor.postActionEvent()
            }
            UIUtil.dispatchAllInvocationEvents()
            assertTrue("Exercise the editable ComboBox's committed String representation", choices.selectedItem is String)
            assertTrue("A native no-op editor/focus commit must not expire completed analysis", dialog.isOKActionEnabled)
            assertEquals(originalRequest, dialog.backendRequest())
            assertEquals("example.org/fixture.Reader", dialog.selection().genString("interfaceId"))
            assertEquals("background-beta", dialog.selection().genString("inspectionId"))
            editor.text = "io.Writer"
            editor.postActionEvent()
            assertFalse("A genuine visible interface change must still require analysis", dialog.isOKActionEnabled)
            pending[0].success(inspection("late-old", receiverName = "Beta"))
            assertFalse("No-op handling must not allow an old callback to validate changed fields", dialog.isOKActionEnabled)
            analyze(dialog)
            assertEquals("io.Writer", pending.last().request.genString("interfaceExpression"))
            assertFalse(pending.last().request.has("interfaceId"))
        } finally { dialog.close(DialogWrapper.CANCEL_EXIT_CODE) }
    }

    private fun analyze(dialog: GoImplementMethodsDialog) = components(dialog).filterIsInstance<JButton>().single { it.text == "Analyze / Refresh" }.doClick()
    private fun field(dialog: GoImplementMethodsDialog, label: String) = labeled(dialog, label) as JTextField
    private fun combo(dialog: GoImplementMethodsDialog, label: String) = labeled(dialog, label) as ComboBox<*>
    private fun labeled(dialog: GoImplementMethodsDialog, label: String): JComponent = components(dialog).filterIsInstance<JBLabel>().single { it.text == label }.labelFor as JComponent
    private fun components(dialog: GoImplementMethodsDialog): List<Component> {
        val field = GoImplementMethodsDialog::class.java.getDeclaredField("panel").apply { isAccessible = true }
        return components(field.get(dialog) as Container)
    }
    private fun components(parent: Container): List<Component> = parent.components.flatMap { listOf(it) + if (it is Container) components(it) else emptyList() }

    private fun inspection(id: String = "original", receiverName: String = "Alpha", kind: String = "pointer", isNew: Boolean = false) = JsonObject().apply {
        addProperty("inspectionId", id); addProperty("configurationDigest", "digest"); addProperty("entryKind", if (receiverName.isEmpty()) "interface" else "receiver")
        addProperty("receiverKind", kind)
        if (receiverName.isNotEmpty()) add("receiver", receiver(receiverName, isNew))
        add("receiverCandidates", JsonArray().apply { add(receiver("Alpha")); add(receiver("Beta")) })
        add("interfaces", JsonArray().apply { add(iface()) })
        add("selectedInterface", iface())
        add("conflicts", JsonArray())
        add("methods", JsonArray().apply {
            for ((name, status) in listOf("read" to "missing", "write" to "missing", "close" to "existing", "reset" to "conflict"))
                add(JsonObject().apply { addProperty("methodId", name); addProperty("name", name.replaceFirstChar(Char::uppercase)); addProperty("signature", "func() error"); addProperty("status", status) })
        })
    }
    private fun receiver(name: String, isNew: Boolean = false) = JsonObject().apply { addProperty("name", name); addProperty("receiverId", "receiver-$name"); addProperty("new", isNew) }
    private fun iface() = JsonObject().apply { addProperty("interfaceId", "example.org/fixture.Reader"); addProperty("display", "Reader"); addProperty("name", "Reader"); add("typeParameters", JsonArray()) }
}
