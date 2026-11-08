package dev.openidea.goide.generate.structs

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
import dev.openidea.goide.semantic.GoIdentifiers
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

private data class HelperField(
    val wire: JsonObject,
    var selected: Boolean,
    var parameterName: String,
    var getterName: String,
    var setterName: String,
) {
    val id: String get() = wire.genString("fieldId")
    val editable: Boolean get() = wire.get("editable")?.asBoolean == true && wire.genString("name") != "_"
}

/** Only drafts native UI choices. The service owns typed analysis, preview and edits. */
internal class GoStructHelpersDialog(
    project: Project,
    inspection: JsonObject,
    kind: String,
    draft: JsonObject? = null,
) : DialogWrapper(project) {
    private val inspection = inspection.deepCopy()
    private val kind = kind.also { require(it in setOf("constructor", "getter", "setter", "both")) }
    private val constructor = kind == "constructor"
    private val hasGetter = kind == "getter" || kind == "both"
    private val hasSetter = kind == "setter" || kind == "both"
    private val constructorName = JBTextField(draft?.genString("constructorName") ?: inspection.genString("constructorName"))
    private val returnPointer = JBCheckBox("Return a pointer", (draft?.genString("constructorKind") ?: "pointer") != "value")
    private val receiverName = JBTextField(draft?.genString("receiverName") ?: "r")
    private val getterKind = ComboBox(arrayOf("Pointer receiver", "Value receiver")).apply {
        selectedIndex = if (draft?.genString("getterKind") == "value") 1 else 0
    }
    private val restored = draft?.getAsJsonArray("fields")?.associate { it.asJsonObject.let { field -> field.genString("fieldId") to field } }.orEmpty()
    private val preferredFieldId = inspection.genString("caretFieldId").takeIf { id ->
        !constructor && inspection.get("preferCaretField")?.asBoolean == true && id.isNotEmpty() &&
            inspection.getAsJsonArray("fields")?.any { item -> item.asJsonObject.let { field ->
                field.genString("fieldId") == id && field.get("editable")?.asBoolean == true && field.genString("name") != "_"
            } } == true
    }
    private val rows = inspection.getAsJsonArray("fields")?.map { item ->
        val field = item.asJsonObject.deepCopy()
        val saved = restored[field.genString("fieldId")]
        HelperField(field,
            field.get("editable")?.asBoolean == true && field.genString("name") != "_" &&
                (saved?.get("selected")?.asBoolean ?: (preferredFieldId == null || preferredFieldId == field.genString("fieldId"))),
            saved?.genString("parameterName") ?: field.genString("parameterName"),
            saved?.genString("getterName") ?: field.genString("getterName"),
            saved?.genString("setterName") ?: field.genString("setterName"))
    }.orEmpty()
    private val columns = buildList {
        add("selected"); add("field"); add("type")
        if (constructor || hasSetter) add("parameterName")
        if (hasGetter) add("getterName")
        if (hasSetter) add("setterName")
        add("status")
    }
    private val model = object : AbstractTableModel() {
        override fun getRowCount() = rows.size
        override fun getColumnCount() = columns.size
        override fun getColumnName(column: Int) = when (columns[column]) {
            "selected" -> "Generate"
            "field" -> "Field"
            "type" -> "Type"
            "parameterName" -> if (constructor) "Parameter" else "Value parameter"
            "getterName" -> "Getter method"
            "setterName" -> "Setter method"
            else -> "Status"
        }
        override fun getColumnClass(columnIndex: Int): Class<*> = if (columnIndex == 0) java.lang.Boolean::class.java else String::class.java
        override fun isCellEditable(rowIndex: Int, columnIndex: Int) = rows[rowIndex].editable && columns[columnIndex] in setOf("selected", "parameterName", "getterName", "setterName")
        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any = rows[rowIndex].let { field ->
            when (columns[columnIndex]) {
                "selected" -> field.selected
                "field" -> field.wire.genString("name")
                "type" -> field.wire.genString("type")
                "parameterName" -> field.parameterName
                "getterName" -> field.getterName
                "setterName" -> field.setterName
                else -> rowStatus(field)
            }
        }
        override fun setValueAt(value: Any?, rowIndex: Int, columnIndex: Int) {
            if (!isCellEditable(rowIndex, columnIndex)) return
            rows[rowIndex].apply {
                when (columns[columnIndex]) {
                    "selected" -> selected = value == true
                    "parameterName" -> parameterName = value?.toString().orEmpty()
                    "getterName" -> getterName = value?.toString().orEmpty()
                    "setterName" -> setterName = value?.toString().orEmpty()
                }
            }
            fireTableRowsUpdated(rowIndex, rowIndex)
            updateState()
        }
    }
    private val table = object : JBTable(model) {
        override fun prepareRenderer(renderer: TableCellRenderer, row: Int, column: Int): Component =
            super.prepareRenderer(renderer, row, column).apply { isEnabled = rows[convertRowIndexToModel(row)].editable }
        override fun getToolTipText(event: MouseEvent): String? = rowAtPoint(event.point).takeIf { it >= 0 }
            ?.let { rowStatus(rows[convertRowIndexToModel(it)]).takeIf(String::isNotEmpty) }
    }.apply { putClientProperty("terminateEditOnFocusLost", true) }
    private val status = JTextArea(3, 60).apply { isEditable = false; isOpaque = false; lineWrap = true; wrapStyleWord = true }
    private val panel = JPanel(BorderLayout(0, 10))

    init {
        title = when (kind) { "constructor" -> "Generate Constructor"; "getter" -> "Generate Getters"; "setter" -> "Generate Setters"; else -> "Generate Getters and Setters" }
        setOKButtonText("Preview")
        init()
        listen(constructorName); listen(receiverName)
        returnPointer.addActionListener { updateState() }
        getterKind.addActionListener { updateState() }
        updateState()
    }

    override fun getPreferredFocusedComponent(): JComponent = if (constructor) constructorName else receiverName
    override fun createCenterPanel(): JComponent = panel.apply {
        preferredSize = Dimension(if (kind == "both") 1100 else 950, 560)
        add(JPanel(GridBagLayout()).apply {
            var index = 0
            fun row(label: String, component: JComponent) {
                add(JBLabel(label).apply { labelFor = component }, GridBagConstraints().apply {
                    gridx = 0; gridy = index; anchor = GridBagConstraints.WEST; insets = Insets(3, 0, 3, 10)
                })
                add(component, GridBagConstraints().apply {
                    gridx = 1; gridy = index++; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL; insets = Insets(3, 0, 3, 0)
                })
            }
            val typeParameters = inspection.getAsJsonArray("typeParameters")?.joinToString(", ", "[", "]") {
                it.asJsonObject.let { parameter -> "${parameter.genString("name")} ${parameter.genString("constraint")}" }
            }?.takeUnless { it == "[]" }.orEmpty()
            row("Struct:", JBLabel(inspection.genString("name") + typeParameters))
            if (constructor) {
                row("Function name:", constructorName)
                row("", returnPointer)
                row("", JBLabel("Selected fields become constructor inputs. Unselected fields keep their zero values."))
            } else {
                row("Receiver variable:", receiverName)
                if (hasGetter) row("Getter receiver:", getterKind)
                if (hasSetter) row("", JBLabel("Setters always use a pointer receiver."))
            }
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
        columns.forEachIndexed { index, value -> table.columnModel.getColumn(index).preferredWidth = when (value) {
            "selected" -> 65; "type" -> 180; "status" -> 260; else -> 160
        } }
    }

    private fun knownConflict(field: HelperField, key: String, current: String): String =
        if (current.trim() == field.wire.genString(key)) field.wire.genString(key.removeSuffix("Name") + "Conflict") else ""
    private fun rowStatus(field: HelperField): String {
        if (!field.editable) return field.wire.genString("reason").ifBlank { "Unsupported field" }
        return buildList {
            if (hasGetter) knownConflict(field, "getterName", field.getterName).takeIf(String::isNotEmpty)?.let(::add)
            if (hasSetter) knownConflict(field, "setterName", field.setterName).takeIf(String::isNotEmpty)?.let(::add)
            if (field.wire.get("embedded")?.asBoolean == true) add("Embedded field")
        }.joinToString("; ")
    }
    private fun selected() = rows.filter { it.editable && it.selected }
    private fun conflicts() = inspection.getAsJsonArray("conflicts")?.map { it.asJsonObject.genString("message") }?.filter(String::isNotBlank).orEmpty()
    private fun validation(): ValidationInfo? {
        conflicts().firstOrNull()?.let { return ValidationInfo(it, table) }
        if (listOf("inspectionId", "configurationDigest", "structId").any { inspection.genString(it).isEmpty() })
            return ValidationInfo("Run Generate again to analyze the current struct.", table)
        val fields = selected()
        if (constructor) {
            if (!GoIdentifiers.valid(constructorName.text.trim())) return ValidationInfo("Enter a valid Go constructor function name.", constructorName)
            if (constructorName.text.trim() == inspection.genString("constructorName")) inspection.genString("constructorConflict").takeIf(String::isNotEmpty)
                ?.let { return ValidationInfo(it, constructorName) }
            val parameters = fields.map { it.parameterName.trim() }
            if (parameters.any { !GoIdentifiers.valid(it) }) return ValidationInfo("Enter a valid Go parameter name for every selected field.", table)
            if (parameters.distinct().size != parameters.size) return ValidationInfo("Constructor parameter names must be unique.", table)
        } else {
            if (!GoIdentifiers.valid(receiverName.text.trim())) return ValidationInfo("Enter a valid Go receiver variable name.", receiverName)
            if (fields.isEmpty()) return ValidationInfo("Select at least one editable field.", table)
            if (hasSetter && fields.any { !GoIdentifiers.valid(it.parameterName.trim()) }) return ValidationInfo("Enter a valid Go value parameter name for every selected setter.", table)
            if (hasSetter && fields.any { it.parameterName.trim() == receiverName.text.trim() }) return ValidationInfo("A setter value parameter must differ from its receiver variable.", table)
            val methods = buildList {
                fields.forEach { field ->
                    if (hasGetter) add(field.getterName.trim())
                    if (hasSetter) add(field.setterName.trim())
                }
            }
            if (methods.any { !GoIdentifiers.valid(it) }) return ValidationInfo("Enter a valid Go method name for every selected helper.", table)
            if (methods.distinct().size != methods.size) return ValidationInfo("Generated method names must be unique.", table)
            val fieldNames = rows.map { it.wire.genString("name") }.toSet()
            methods.firstOrNull { it in fieldNames }?.let { return ValidationInfo("Method $it conflicts with a struct field. Choose another name.", table) }
            fields.forEach { field ->
                if (hasGetter) knownConflict(field, "getterName", field.getterName).takeIf(String::isNotEmpty)?.let { return ValidationInfo(it, table) }
                if (hasSetter) knownConflict(field, "setterName", field.setterName).takeIf(String::isNotEmpty)?.let { return ValidationInfo(it, table) }
            }
        }
        return null
    }

    private fun updateState() {
        val problem = validation()
        isOKActionEnabled = problem == null
        status.text = problem?.message ?: "${selected().size} fields selected. Edit the suggested names as needed. Preview checks names, Go types and imports before applying changes."
        status.foreground = if (problem != null) JBColor.RED else JBColor.foreground()
    }
    private fun selectFields(value: Boolean) {
        if (table.isEditing && !table.cellEditor.stopCellEditing()) return
        rows.filter { it.editable }.forEach { it.selected = value }
        model.fireTableDataChanged()
        updateState()
    }
    override fun doValidate(): ValidationInfo? = validation()
    override fun doOKAction() { if (!table.isEditing || table.cellEditor.stopCellEditing()) super.doOKAction() }

    fun selection(): JsonObject {
        check(!table.isEditing || table.cellEditor.stopCellEditing()) { "Finish editing the field selection." }
        check(validation() == null) { validation()?.message ?: "Choose valid struct helper settings." }
        return settings().apply {
            listOf("inspectionId", "configurationDigest", "structId").forEach { addProperty(it, inspection.genString(it)) }
            add("selections", JsonArray().also { values -> selected().forEach { values.add(names(it)) } })
        }
    }
    fun draft(): JsonObject {
        if (table.isEditing) table.cellEditor.stopCellEditing()
        return settings().apply {
            add("fields", JsonArray().also { values -> rows.forEach { field -> values.add(names(field, trim = false).apply { addProperty("selected", field.selected) }) } })
            addProperty("constructorName", constructorName.text); addProperty("receiverName", receiverName.text)
        }
    }
    private fun settings() = JsonObject().apply {
        addProperty("kind", kind); addProperty("constructorName", constructorName.text.trim())
        addProperty("constructorKind", if (returnPointer.isSelected) "pointer" else "value")
        addProperty("getterKind", if (getterKind.selectedIndex == 0) "pointer" else "value")
        addProperty("receiverName", receiverName.text.trim())
    }
    private fun names(field: HelperField, trim: Boolean = true) = JsonObject().apply {
        fun value(text: String) = if (trim) text.trim() else text
        addProperty("fieldId", field.id); addProperty("parameterName", value(field.parameterName))
        addProperty("getterName", value(field.getterName)); addProperty("setterName", value(field.setterName))
    }
    private fun listen(field: JTextField) = field.document.addDocumentListener(object : DocumentListener {
        override fun insertUpdate(event: DocumentEvent) = updateState()
        override fun removeUpdate(event: DocumentEvent) = updateState()
        override fun changedUpdate(event: DocumentEvent) = updateState()
    })
}
