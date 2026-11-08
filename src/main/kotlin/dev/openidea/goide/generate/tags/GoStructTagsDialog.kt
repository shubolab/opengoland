package dev.openidea.goide.generate.tags

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import dev.openidea.goide.generate.tests.genString
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.AbstractTableModel
import javax.swing.table.TableCellRenderer

private data class TagSetting(val value: String, val label: String) { override fun toString() = label }
private data class TagField(val wire: JsonObject, var selected: Boolean) {
    val id: String get() = wire.genString("fieldId")
    val editable: Boolean get() = wire.get("editable")?.asBoolean == true
    fun status(): String = if (!editable) wire.genString("reason").ifBlank { "Unsupported field" } else buildList {
        if (wire.get("exported")?.asBoolean != true) add("Package private")
        if (wire.get("embedded")?.asBoolean == true) add("Embedded field")
        wire.getAsJsonArray("groupNames")?.map { it.asString }?.takeIf { it.size > 1 }
            ?.let { add("Shared declaration: ${it.joinToString(", ")}") }
    }.joinToString("; ")
}

/** A native selection draft. The owning service inspects Go types and previews edits. */
internal class GoStructTagsDialog(
    project: Project,
    inspection: JsonObject,
    draft: JsonObject? = null,
) : DialogWrapper(project) {
    private val inspection = inspection.deepCopy()
    private val key = ComboBox(arrayOf("json", "xml", "yaml")).apply {
        isEditable = true
        selectedItem = draft?.genString("key") ?: "json"
    }
    private val mode = ComboBox(arrayOf(
        TagSetting("add-missing", "Add missing tags"),
        TagSetting("update", "Update tags"),
        TagSetting("remove", "Remove tags"),
    )).apply { selectedIndex = (0 until itemCount).firstOrNull { getItemAt(it).value == draft?.genString("mode") } ?: 0 }
    private val style = ComboBox(arrayOf(
        TagSetting("original", "Original field name"),
        TagSetting("camel", "camelCase"),
        TagSetting("snake", "snake_case"),
        TagSetting("lower", "lowercase"),
    )).apply { selectedIndex = (0 until itemCount).firstOrNull { getItemAt(it).value == draft?.genString("style") } ?: 0 }
    private val options = JBTextField(draft?.genString("optionsText")
        ?: draft?.getAsJsonArray("options")?.joinToString(", ") { it.asString }.orEmpty()).apply {
        emptyText.text = "omitempty, string"
    }
    private val replaceOptions = JBCheckBox("Replace existing options", draft?.get("replaceOptions")?.asBoolean ?: false).apply {
        toolTipText = "Unchecked preserves options on existing tags. Checked replaces them with the options above, or clears them when empty."
    }
    private val rows = inspection.getAsJsonArray("fields")?.map { item ->
        val field = item.asJsonObject.deepCopy()
        val selectedIds = draft?.getAsJsonArray("fieldIds")?.map { it.asString }?.toSet()
        TagField(field, field.get("editable")?.asBoolean == true &&
            (selectedIds?.contains(field.genString("fieldId"))
                ?: (field.get("exported")?.asBoolean == true && field.get("embedded")?.asBoolean != true)))
    }.orEmpty()
    private val model = object : AbstractTableModel() {
        override fun getRowCount() = rows.size
        override fun getColumnCount() = 5
        override fun getColumnName(column: Int) = listOf("Edit", "Field", "Type", "Existing tags", "Status")[column]
        override fun getColumnClass(columnIndex: Int): Class<*> = if (columnIndex == 0) java.lang.Boolean::class.java else String::class.java
        override fun isCellEditable(rowIndex: Int, columnIndex: Int) = columnIndex == 0 && rows[rowIndex].editable
        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any = rows[rowIndex].let { field ->
            when (columnIndex) {
                0 -> field.selected
                1 -> field.wire.genString("name")
                2 -> field.wire.genString("type")
                3 -> field.wire.genString("tag")
                else -> field.status()
            }
        }
        override fun setValueAt(value: Any?, rowIndex: Int, columnIndex: Int) {
            if (!isCellEditable(rowIndex, columnIndex)) return
            rows[rowIndex].selected = value == true
            fireTableRowsUpdated(rowIndex, rowIndex)
            updateState()
        }
    }
    private val table = object : JBTable(model) {
        override fun prepareRenderer(renderer: TableCellRenderer, row: Int, column: Int): Component =
            super.prepareRenderer(renderer, row, column).apply { isEnabled = rows[convertRowIndexToModel(row)].editable }
        override fun getToolTipText(event: MouseEvent): String? = rowAtPoint(event.point).takeIf { it >= 0 }
            ?.let { rows[convertRowIndexToModel(it)].status().takeIf(String::isNotEmpty) }
    }.apply { putClientProperty("terminateEditOnFocusLost", true) }
    private val explanation = JTextArea(2, 60).apply { isEditable = false; isOpaque = false; lineWrap = true; wrapStyleWord = true }
    private val status = JTextArea(3, 60).apply { isEditable = false; isOpaque = false; lineWrap = true; wrapStyleWord = true }
    private val panel = JPanel(BorderLayout(0, 10))

    init {
        title = "Struct Tags"
        setOKButtonText("Preview")
        init()
        key.addActionListener { updateState() }
        (key.editor.editorComponent as? JTextField)?.let(::listen)
        mode.addActionListener { updateState() }
        style.addActionListener { updateState() }
        replaceOptions.addActionListener { updateState() }
        listen(options)
        updateState()
    }

    override fun getPreferredFocusedComponent(): JComponent = key
    override fun createCenterPanel(): JComponent = panel.apply {
        preferredSize = Dimension(950, 560)
        add(JPanel(GridBagLayout()).apply {
            fun row(index: Int, label: String, component: JComponent) {
                add(JBLabel(label).apply { labelFor = component }, GridBagConstraints().apply {
                    gridx = 0; gridy = index; anchor = GridBagConstraints.WEST; insets = Insets(3, 0, 3, 10)
                })
                add(component, GridBagConstraints().apply {
                    gridx = 1; gridy = index; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL; insets = Insets(3, 0, 3, 0)
                })
            }
            row(0, "Struct:", JBLabel(inspection.genString("name")))
            row(1, "Tag key:", key)
            row(2, "Operation:", mode)
            row(3, "Name style:", style)
            row(4, "Options (comma-separated):", options)
            row(5, "", replaceOptions)
            row(6, "", explanation)
        }, BorderLayout.NORTH)
        add(JPanel(BorderLayout(0, 5)).apply {
            add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                add(JBLabel("Fields   "))
                add(JButton("Select all").apply { addActionListener { selectFields(true) } })
                add(JButton("Select none").apply { addActionListener { selectFields(false) } })
            }, BorderLayout.NORTH)
            add(JBScrollPane(table), BorderLayout.CENTER)
        }, BorderLayout.CENTER)
        add(status, BorderLayout.SOUTH)
        listOf(45, 150, 180, 280, 260).forEachIndexed { index, width -> table.columnModel.getColumn(index).preferredWidth = width }
    }

    private fun selectedMode() = (mode.selectedItem as TagSetting).value
    private fun selectedStyle() = (style.selectedItem as TagSetting).value
    private fun keyText() = key.editor.item?.toString().orEmpty().trim()
    private fun parsedOptions(): List<String> {
        require(options.text.none { it.code < 32 || it.code == 127 }) { "Options cannot contain control characters." }
        if (options.text.isBlank()) return emptyList()
        val values = options.text.split(',').map(String::trim)
        require(values.none(String::isEmpty)) { "Enter an option between commas." }
        require(values.distinct().size == values.size) { "Enter each option only once." }
        return values
    }

    private fun selectFields(selected: Boolean) {
        if (table.isEditing && !table.cellEditor.stopCellEditing()) return
        rows.filter { it.editable }.forEach { it.selected = selected }
        model.fireTableDataChanged()
        updateState()
    }

    private fun conflicts() = inspection.getAsJsonArray("conflicts")?.map { it.asJsonObject.genString("message") }?.filter(String::isNotBlank).orEmpty()
    private fun validation(): ValidationInfo? {
        conflicts().firstOrNull()?.let { return ValidationInfo(it, table) }
        if (listOf("inspectionId", "configurationDigest", "structId").any { inspection.genString(it).isEmpty() })
            return ValidationInfo("Run Struct Tags again to analyze the current struct.", table)
        val text = keyText()
        if (text.isEmpty() || text.any { it.code <= 32 || it.code == 127 || it == ':' || it == '"' })
            return ValidationInfo("Enter a tag key without spaces, control characters, quotes or colons.", key)
        if (selectedMode() != "remove") {
            try { parsedOptions() } catch (failure: IllegalArgumentException) { return ValidationInfo(failure.message ?: "Enter valid options.", options) }
        }
        if (rows.none { it.editable && it.selected }) return ValidationInfo("Select at least one editable field.", table)
        return null
    }

    private fun updateState() {
        val removing = selectedMode() == "remove"
        style.isEnabled = !removing
        options.isEnabled = !removing
        replaceOptions.isEnabled = selectedMode() == "update"
        explanation.text = when (selectedMode()) {
            "remove" -> "Remove only this key from the selected fields. Other tag keys stay unchanged."
            "update" -> "Update names or add the key where missing. Existing options are preserved unless replacement is selected. Tags with value \"-\" stay unchanged."
            else -> "Add the key only where it is missing. Existing values and options stay unchanged."
        }
        val validation = validation()
        isOKActionEnabled = validation == null
        status.text = validation?.message ?: "${rows.count { it.selected && it.editable }} fields selected. Shared declarations must use the same tag. Preview checks the changes before applying them."
        status.foreground = if (validation != null) JBColor.RED else JBColor.foreground()
    }

    override fun doValidate(): ValidationInfo? = validation()
    override fun doOKAction() { if (!table.isEditing || table.cellEditor.stopCellEditing()) super.doOKAction() }

    fun selection(): JsonObject {
        check(!table.isEditing || table.cellEditor.stopCellEditing()) { "Finish editing the field selection." }
        check(validation() == null) { validation()?.message ?: "Choose valid struct tag settings." }
        val removing = selectedMode() == "remove"
        return JsonObject().apply {
            listOf("inspectionId", "configurationDigest", "structId").forEach { addProperty(it, inspection.genString(it)) }
            add("fieldIds", selectedFields())
            addProperty("key", keyText()); addProperty("mode", selectedMode()); addProperty("style", selectedStyle())
            add("options", JsonArray().also { array -> if (!removing) parsedOptions().forEach(array::add) })
            addProperty("replaceOptions", selectedMode() == "update" && replaceOptions.isSelected)
        }
    }

    fun draft(): JsonObject {
        if (table.isEditing) table.cellEditor.stopCellEditing()
        return JsonObject().apply {
            add("fieldIds", selectedFields()); addProperty("key", key.editor.item?.toString().orEmpty())
            addProperty("mode", selectedMode()); addProperty("style", selectedStyle())
            addProperty("optionsText", options.text); addProperty("replaceOptions", replaceOptions.isSelected)
        }
    }

    private fun selectedFields() = JsonArray().also { ids -> rows.filter { it.editable && it.selected }.forEach { ids.add(it.id) } }
    private fun listen(field: JTextField) = field.document.addDocumentListener(object : DocumentListener {
        override fun insertUpdate(event: DocumentEvent) = updateState()
        override fun removeUpdate(event: DocumentEvent) = updateState()
        override fun changedUpdate(event: DocumentEvent) = updateState()
    })
}
