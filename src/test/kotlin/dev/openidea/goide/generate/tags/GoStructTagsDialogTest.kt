package dev.openidea.goide.generate.tags

import com.google.gson.JsonArray
import com.google.gson.JsonObject
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
import javax.swing.JButton
import javax.swing.JTextArea
import javax.swing.JTextField

class GoStructTagsDialogTest : LightPlatformTestCase() {
    override fun runInDispatchThread() = true

    fun testDefaultSelectionDisplaysExistingTagsAndOnlyUnsupportedFieldsAreDisabled() {
        val dialog = GoStructTagsDialog(project, inspection())
        try {
            val table = table(dialog)
            assertTrue(dialog.isOKActionEnabled)
            assertEquals(listOf(true, false, false, false, true, true), (0 until table.rowCount).map { table.model.getValueAt(it, 0) })
            assertEquals("json:\"name,omitempty\" xml:\"name\"", table.model.getValueAt(0, 3))
            assertEquals("Malformed existing tag", table.model.getValueAt(3, 4))
            assertFalse(table.model.isCellEditable(3, 0))
            table.model.setValueAt(true, 3, 0)
            assertEquals(false, table.model.getValueAt(3, 0))
            assertTrue(table.model.isCellEditable(1, 0))
            assertTrue(table.model.isCellEditable(2, 0))
            table.model.setValueAt(true, 1, 0)
            table.model.setValueAt(true, 2, 0)
            table.model.setValueAt(false, 4, 0)
            assertEquals(listOf("name", "private", "embedded", "b"), dialog.selection().ids())
            assertEquals("Shared declaration: A, B", table.model.getValueAt(5, 4))
            assertEquals(false, table.model.getValueAt(4, 0)) // no implicit selection of a shared declaration
        } finally { dialog.close(DialogWrapper.CANCEL_EXIT_CODE) }
    }

    fun testEditableKeyCommitAndOptionsRequireExplicitReplacement() {
        val dialog = GoStructTagsDialog(project, inspection())
        try {
            combo(dialog, "Operation:").selectedIndex = 1
            combo(dialog, "Name style:").selectedIndex = 2
            val key = combo(dialog, "Tag key:")
            val editor = key.editor.editorComponent as JTextField
            editor.text = "custom`key"
            editor.postActionEvent()
            options(dialog).text = "omitempty, custom: value, 引用\"`"
            UIUtil.dispatchAllInvocationEvents()
            val preserved = dialog.selection()
            assertEquals("custom`key", preserved.genString("key"))
            assertEquals("update", preserved.genString("mode"))
            assertEquals("snake", preserved.genString("style"))
            assertEquals(listOf("omitempty", "custom: value", "引用\"`"), preserved.getAsJsonArray("options").map { it.asString })
            assertFalse("Existing options must survive until the user explicitly chooses replacement", preserved.get("replaceOptions").asBoolean)
            replace(dialog).doClick()
            assertTrue(dialog.selection().get("replaceOptions").asBoolean)
            options(dialog).text = ""
            assertTrue(dialog.selection().get("replaceOptions").asBoolean)
            assertEquals(0, dialog.selection().getAsJsonArray("options").size())
            combo(dialog, "Operation:").selectedIndex = 0
            assertFalse(replace(dialog).isEnabled)
            assertFalse(dialog.selection().get("replaceOptions").asBoolean)
        } finally { dialog.close(DialogWrapper.CANCEL_EXIT_CODE) }
    }

    fun testRemoveIgnoresDisabledOptionsAndDraftRestoresAllUserChoices() {
        val initial = inspection()
        val first = GoStructTagsDialog(project, initial)
        var saved: JsonObject
        try {
            combo(first, "Tag key:").editor.item = "yaml"
            combo(first, "Operation:").selectedIndex = 1
            combo(first, "Name style:").selectedIndex = 1
            options(first).text = "omitempty,,string"
            replace(first).doClick()
            table(first).model.setValueAt(false, 0, 0)
            table(first).model.setValueAt(true, 1, 0)
            assertFalse(first.isOKActionEnabled)
            combo(first, "Operation:").selectedIndex = 2
            assertFalse(options(first).isEnabled)
            assertFalse(combo(first, "Name style:").isEnabled)
            assertFalse(replace(first).isEnabled)
            assertTrue(first.isOKActionEnabled)
            val selection = first.selection()
            assertEquals("remove", selection.genString("mode"))
            assertFalse(selection.get("replaceOptions").asBoolean)
            assertEquals(0, selection.getAsJsonArray("options").size())
            saved = first.draft()
            saved.addProperty("inspectionId", "do-not-reuse")
        } finally { first.close(DialogWrapper.CANCEL_EXIT_CODE) }
        val restored = GoStructTagsDialog(project, initial, saved)
        try {
            assertTrue(restored.isOKActionEnabled)
            assertEquals(listOf("private", "a", "b"), restored.selection().ids())
            assertEquals("current-inspection", restored.selection().genString("inspectionId"))
            assertEquals("current-digest", restored.selection().genString("configurationDigest"))
            assertEquals("current-struct", restored.selection().genString("structId"))
            assertEquals("yaml", restored.selection().genString("key"))
            assertEquals("camel", restored.selection().genString("style"))
            assertEquals("omitempty,,string", options(restored).text)
            assertTrue(replace(restored).isSelected)
            combo(restored, "Operation:").selectedIndex = 1
            assertFalse("Returning to Update must validate the preserved options", restored.isOKActionEnabled)
            options(restored).text = "omitempty"
            assertTrue(restored.isOKActionEnabled)
            assertTrue(restored.selection().get("replaceOptions").asBoolean)
        } finally { restored.close(DialogWrapper.CANCEL_EXIT_CODE) }
    }

    fun testValidationAndSelectButtonsUseTheActualSwingFields() {
        val dialog = GoStructTagsDialog(project, inspection())
        try {
            val keyEditor = combo(dialog, "Tag key:").editor.editorComponent as JTextField
            for (bad in listOf("", "json:xml", "two keys", "quoted\"key", "control\u007f")) {
                keyEditor.text = bad
                assertFalse("Invalid key: $bad", dialog.isOKActionEnabled)
            }
            keyEditor.text = "custom"
            for (bad in listOf("omitempty,", ",string", "same,same", "option\u007f")) {
                options(dialog).text = bad
                assertFalse("Invalid options: $bad", dialog.isOKActionEnabled)
            }
            options(dialog).text = "omitempty"
            assertTrue(dialog.isOKActionEnabled)
            button(dialog, "Select none").doClick()
            assertFalse(dialog.isOKActionEnabled)
            button(dialog, "Select all").doClick()
            assertEquals(listOf("name", "private", "embedded", "a", "b"), dialog.selection().ids())
            assertEquals(false, table(dialog).model.getValueAt(3, 0))
        } finally { dialog.close(DialogWrapper.CANCEL_EXIT_CODE) }
    }

    fun testInspectionConflictsRemainVisibleAndCannotBePreviewed() {
        val initial = inspection().apply {
            getAsJsonArray("conflicts").add(JsonObject().apply { addProperty("message", "The selected source is not a struct.") })
        }
        val dialog = GoStructTagsDialog(project, initial)
        try {
            assertFalse(dialog.isOKActionEnabled)
            assertTrue(components(dialog).filterIsInstance<JTextArea>().any { it.text.contains("The selected source is not a struct.") })
            combo(dialog, "Operation:").selectedIndex = 2
            assertFalse(dialog.isOKActionEnabled)
            assertFalse(dialog.isDisposed)
        } finally { dialog.close(DialogWrapper.CANCEL_EXIT_CODE) }
    }

    private fun inspection() = JsonObject().apply {
        addProperty("inspectionId", "current-inspection"); addProperty("configurationDigest", "current-digest")
        addProperty("structId", "current-struct"); addProperty("name", "Person")
        add("conflicts", JsonArray())
        add("fields", JsonArray().apply {
            add(field("name", "Name", tag = "json:\"name,omitempty\" xml:\"name\""))
            add(field("private", "private", exported = false))
            add(field("embedded", "Embedded", embedded = true))
            add(field("broken", "Broken", editable = false).apply { addProperty("reason", "Malformed existing tag") })
            for (name in listOf("A", "B")) add(field(name.lowercase(), name).apply {
                addProperty("groupId", "shared"); add("groupNames", JsonArray().apply { add("A"); add("B") })
            })
        })
    }

    private fun field(id: String, name: String, tag: String = "", exported: Boolean = true, embedded: Boolean = false, editable: Boolean = true) = JsonObject().apply {
        addProperty("fieldId", id); addProperty("name", name); addProperty("type", "string"); addProperty("tag", tag)
        addProperty("exported", exported); addProperty("embedded", embedded); addProperty("editable", editable)
        add("names", JsonArray().apply { add(name) }); add("groupNames", JsonArray().apply { add(name) })
    }
    private fun JsonObject.ids() = getAsJsonArray("fieldIds").map { it.asString }
    private fun table(dialog: GoStructTagsDialog) = components(dialog).filterIsInstance<JBTable>().single()
    private fun replace(dialog: GoStructTagsDialog) = components(dialog).filterIsInstance<JBCheckBox>().single()
    private fun button(dialog: GoStructTagsDialog, name: String) = components(dialog).filterIsInstance<JButton>().single { it.text == name }
    private fun options(dialog: GoStructTagsDialog) = components(dialog).filterIsInstance<JBLabel>().single { it.text == "Options (comma-separated):" }.labelFor as JTextField
    private fun combo(dialog: GoStructTagsDialog, label: String) = components(dialog).filterIsInstance<JBLabel>().single { it.text == label }.labelFor as ComboBox<*>
    private fun components(dialog: GoStructTagsDialog): List<Component> {
        // The SDK headless peer has no contentPane; init still builds the real panel.
        val field = GoStructTagsDialog::class.java.getDeclaredField("panel").apply { isAccessible = true }
        return components(field.get(dialog) as Container)
    }
    private fun components(container: Container): List<Component> = container.components.flatMap { listOf(it) + if (it is Container) components(it) else emptyList() }
}
