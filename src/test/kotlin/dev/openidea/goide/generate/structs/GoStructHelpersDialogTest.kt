package dev.openidea.goide.generate.structs

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.testFramework.LightPlatformTestCase
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.table.JBTable
import dev.openidea.goide.generate.tests.genString
import java.awt.Component
import java.awt.Container
import javax.swing.JButton
import javax.swing.JTextArea
import javax.swing.JTextField

class GoStructHelpersDialogTest : LightPlatformTestCase() {
    override fun runInDispatchThread() = true

    fun testConstructorUsesNativeCellCommitAndAllowsZeroInputs() {
        val dialog = GoStructHelpersDialog(project, inspection(), "constructor")
        try {
            assertTrue(dialog.isOKActionEnabled)
            assertEquals(listOf("name", "age", "embedded"), dialog.selection().selectedIds())
            assertTrue(components(dialog).filterIsInstance<JBLabel>().any { it.text == "Record[T any]" })
            val table = table(dialog)
            assertFalse(table.model.isCellEditable(3, 0))
            assertEquals("Blank fields cannot be selected", table.model.getValueAt(3, column(table, "Status")))
            assertTrue(table.editCellAt(0, column(table, "Parameter")))
            (table.editorComponent as JTextField).text = "displayName"
            field(dialog, "Function name:").text = "CreateRecord"
            components(dialog).filterIsInstance<JBCheckBox>().single().doClick()
            val selection = dialog.selection() // commits the actual native text cell editor
            assertFalse(table.isEditing)
            assertEquals("displayName", selection.getAsJsonArray("selections")[0].asJsonObject.genString("parameterName"))
            assertEquals("CreateRecord", selection.genString("constructorName"))
            assertEquals("value", selection.genString("constructorKind"))
            button(dialog, "Select none").doClick()
            assertTrue("A zero-input constructor is useful and valid", dialog.isOKActionEnabled)
            assertEquals(0, dialog.selection().getAsJsonArray("selections").size())
            button(dialog, "Select all").doClick()
            assertEquals(3, dialog.selection().getAsJsonArray("selections").size())
        } finally { dialog.close(DialogWrapper.CANCEL_EXIT_CODE) }
    }

    fun testGetterCollisionFeedbackCanBeResolvedByRenamingAndPointerChoiceIsExplicit() {
        val initial = inspection().apply {
            getAsJsonArray("fields")[0].asJsonObject.addProperty("getterConflict", "GetName already exists on Record")
        }
        val dialog = GoStructHelpersDialog(project, initial, "getter")
        try {
            val table = table(dialog)
            assertFalse(dialog.isOKActionEnabled)
            assertEquals("GetName already exists on Record", table.model.getValueAt(0, column(table, "Status")))
            table.model.setValueAt("ReadName", 0, column(table, "Getter method"))
            assertTrue(dialog.isOKActionEnabled)
            assertEquals("", table.model.getValueAt(0, column(table, "Status")))
            table.model.setValueAt("Name", 0, column(table, "Getter method"))
            assertFalse("A method cannot reuse a struct field name", dialog.isOKActionEnabled)
            table.model.setValueAt("GetAge", 0, column(table, "Getter method"))
            assertFalse("Selected helper names must be distinct", dialog.isOKActionEnabled)
            table.model.setValueAt("ReadName", 0, column(table, "Getter method"))
            combo(dialog, "Getter receiver:").selectedIndex = 1
            field(dialog, "Receiver variable:").text = "self"
            val result = dialog.selection()
            assertEquals("value", result.genString("getterKind"))
            assertEquals("self", result.genString("receiverName"))
            assertEquals("getter", result.genString("kind"))
            table.model.setValueAt(false, 0, 0)
            table.model.setValueAt("not a name", 0, column(table, "Getter method"))
            assertTrue("Unchecked rows do not block the selected getters", dialog.isOKActionEnabled)
        } finally { dialog.close(DialogWrapper.CANCEL_EXIT_CODE) }
    }

    fun testSetterUsesPointerReceiversAndChecksEachValueParameterAgainstItsReceiver() {
        val dialog = GoStructHelpersDialog(project, inspection(), "setter")
        try {
            val table = table(dialog)
            assertTrue(components(dialog).filterIsInstance<JBLabel>().any { it.text == "Setters always use a pointer receiver." })
            assertFalse(components(dialog).filterIsInstance<JBLabel>().any { it.text == "Getter receiver:" })
            val parameter = column(table, "Value parameter")
            for (row in 0..2) table.model.setValueAt("value", row, parameter)
            assertTrue("Each setter has its own value parameter scope", dialog.isOKActionEnabled)
            field(dialog, "Receiver variable:").text = "value"
            assertFalse(dialog.isOKActionEnabled)
            field(dialog, "Receiver variable:").text = "self"
            table.model.setValueAt("SetDisplayName", 0, column(table, "Setter method"))
            val result = dialog.selection()
            assertEquals("setter", result.genString("kind"))
            assertEquals("SetDisplayName", result.getAsJsonArray("selections")[0].asJsonObject.genString("setterName"))
            assertEquals(listOf("value", "value", "value"), result.getAsJsonArray("selections").map { it.asJsonObject.genString("parameterName") })
            button(dialog, "Select none").doClick()
            assertFalse("An accessor action needs at least one field", dialog.isOKActionEnabled)
        } finally { dialog.close(DialogWrapper.CANCEL_EXIT_CODE) }
    }

    fun testBothKeepsValueGetterChoiceAndDraftRestoresPartialNamesWithoutOldMetadata() {
        val initial = inspection()
        val first = GoStructHelpersDialog(project, initial, "both")
        var draft: JsonObject
        try {
            val table = table(first)
            combo(first, "Getter receiver:").selectedIndex = 1
            field(first, "Receiver variable:").text = "self"
            table.model.setValueAt(false, 1, 0)
            table.model.setValueAt(false, 2, 0)
            table.model.setValueAt(" unfinished method ", 1, column(table, "Getter method"))
            table.model.setValueAt("ReadName", 0, column(table, "Getter method"))
            table.model.setValueAt("WriteName", 0, column(table, "Setter method"))
            table.model.setValueAt("newName", 0, column(table, "Value parameter"))
            assertEquals("value", first.selection().genString("getterKind"))
            draft = first.draft().apply {
                addProperty("kind", "constructor"); addProperty("inspectionId", "stale")
                addProperty("configurationDigest", "stale"); addProperty("structId", "stale")
            }
        } finally { first.close(DialogWrapper.CANCEL_EXIT_CODE) }
        val restored = GoStructHelpersDialog(project, initial, "both", draft)
        try {
            val result = restored.selection()
            assertEquals("both", result.genString("kind"))
            assertEquals("current-inspection", result.genString("inspectionId"))
            assertEquals("current-digest", result.genString("configurationDigest"))
            assertEquals("current-struct", result.genString("structId"))
            assertEquals(listOf("name"), result.selectedIds())
            assertEquals("value", result.genString("getterKind"))
            assertEquals("self", result.genString("receiverName"))
            val selected = result.getAsJsonArray("selections").single().asJsonObject
            assertEquals("ReadName", selected.genString("getterName"))
            assertEquals("WriteName", selected.genString("setterName"))
            assertEquals("newName", selected.genString("parameterName"))
            assertEquals(" unfinished method ", table(restored).model.getValueAt(1, column(table(restored), "Getter method")))
            table(restored).model.setValueAt("ReadName", 0, column(table(restored), "Setter method"))
            assertFalse("Getters and setters share the same method namespace", restored.isOKActionEnabled)
        } finally { restored.close(DialogWrapper.CANCEL_EXIT_CODE) }
    }

    fun testConstructorAndInspectionConflictsAreVisibleAndEditableNamesStayInTheDialog() {
        val initial = inspection().apply { addProperty("constructorConflict", "NewRecord is already declared") }
        val dialog = GoStructHelpersDialog(project, initial, "constructor")
        try {
            assertFalse(dialog.isOKActionEnabled)
            assertTrue(components(dialog).filterIsInstance<JTextArea>().single().text.contains("NewRecord is already declared"))
            field(dialog, "Function name:").text = "CreateRecord"
            assertTrue(dialog.isOKActionEnabled)
            table(dialog).model.setValueAt("name", 1, column(table(dialog), "Parameter"))
            assertFalse("Constructor parameters belong to one function", dialog.isOKActionEnabled)
            table(dialog).model.setValueAt("age", 1, column(table(dialog), "Parameter"))
            field(dialog, "Function name:").text = "func"
            assertFalse(dialog.isOKActionEnabled)
            assertFalse(dialog.isDisposed)
        } finally { dialog.close(DialogWrapper.CANCEL_EXIT_CODE) }
        val conflicted = GoStructHelpersDialog(project, inspection().apply {
            getAsJsonArray("conflicts").add(JsonObject().apply { addProperty("message", "This alias has no editable local struct.") })
        }, "both")
        try {
            assertFalse(conflicted.isOKActionEnabled)
            assertTrue(components(conflicted).filterIsInstance<JTextArea>().single().text.contains("no editable local struct"))
        } finally { conflicted.close(DialogWrapper.CANCEL_EXIT_CODE) }
    }

    fun testIntentionPrefersOnlyAValidCaretFieldAndDraftSelectionTakesPrecedence() {
        val initial = inspection().apply { addProperty("caretFieldId", "age"); addProperty("preferCaretField", true) }
        val first = GoStructHelpersDialog(project, initial, "getter")
        var draft: JsonObject
        try {
            assertEquals(listOf("age"), first.selection().selectedIds())
            table(first).model.setValueAt(true, 0, 0)
            draft = first.draft()
        } finally { first.close(DialogWrapper.CANCEL_EXIT_CODE) }
        val restored = GoStructHelpersDialog(project, initial, "getter", draft)
        try { assertEquals(listOf("name", "age"), restored.selection().selectedIds()) }
        finally { restored.close(DialogWrapper.CANCEL_EXIT_CODE) }
        val fallback = GoStructHelpersDialog(project, initial.deepCopy().apply { addProperty("caretFieldId", "unknown") }, "getter")
        try { assertEquals(listOf("name", "age", "embedded"), fallback.selection().selectedIds()) }
        finally { fallback.close(DialogWrapper.CANCEL_EXIT_CODE) }
        val constructor = GoStructHelpersDialog(project, initial, "constructor")
        try { assertEquals(listOf("name", "age", "embedded"), constructor.selection().selectedIds()) }
        finally { constructor.close(DialogWrapper.CANCEL_EXIT_CODE) }
    }

    private fun inspection() = JsonObject().apply {
        addProperty("inspectionId", "current-inspection"); addProperty("configurationDigest", "current-digest")
        addProperty("structId", "current-struct"); addProperty("name", "Record"); addProperty("constructorName", "NewRecord")
        add("typeParameters", JsonArray().apply { add(JsonObject().apply { addProperty("name", "T"); addProperty("constraint", "any") }) })
        add("conflicts", JsonArray())
        add("fields", JsonArray().apply {
            add(field("name", "Name", "name"))
            add(field("age", "age", "age").apply { addProperty("exported", false) })
            add(field("embedded", "Embedded", "embedded").apply { addProperty("embedded", true) })
            add(field("blank", "_", "blank").apply { addProperty("editable", false); addProperty("reason", "Blank fields cannot be selected") })
        })
    }
    private fun field(id: String, name: String, parameter: String) = JsonObject().apply {
        addProperty("fieldId", id); addProperty("name", name); addProperty("type", "T")
        addProperty("parameterName", parameter)
        val exported = name.replaceFirstChar(Char::uppercaseChar)
        addProperty("getterName", "Get$exported"); addProperty("setterName", "Set$exported")
        addProperty("editable", true); addProperty("exported", true); addProperty("embedded", false)
        add("names", JsonArray().apply { add(name) })
    }
    private fun JsonObject.selectedIds() = getAsJsonArray("selections").map { it.asJsonObject.genString("fieldId") }
    private fun table(dialog: GoStructHelpersDialog) = components(dialog).filterIsInstance<JBTable>().single()
    private fun column(table: JBTable, name: String) = (0 until table.columnCount).single { table.model.getColumnName(it) == name }
    private fun field(dialog: GoStructHelpersDialog, label: String) = components(dialog).filterIsInstance<JBLabel>().single { it.text == label }.labelFor as JTextField
    private fun combo(dialog: GoStructHelpersDialog, label: String) = components(dialog).filterIsInstance<JBLabel>().single { it.text == label }.labelFor as ComboBox<*>
    private fun button(dialog: GoStructHelpersDialog, name: String) = components(dialog).filterIsInstance<JButton>().single { it.text == name }
    private fun components(dialog: GoStructHelpersDialog): List<Component> {
        // The SDK headless peer omits contentPane, while init builds this real panel.
        val field = GoStructHelpersDialog::class.java.getDeclaredField("panel").apply { isAccessible = true }
        return components(field.get(dialog) as Container)
    }
    private fun components(container: Container): List<Component> = container.components.flatMap { listOf(it) + if (it is Container) components(it) else emptyList() }
}
