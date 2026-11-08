package dev.openidea.goide.generate.jsonstruct

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.editor.event.DocumentEvent as EditorDocumentEvent
import com.intellij.openapi.editor.event.DocumentListener as EditorDocumentListener
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.EditorTextField
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import dev.openidea.goide.generate.tests.genString
import dev.openidea.goide.semantic.GoIdentifiers
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.AbstractTableModel
import javax.swing.table.TableCellRenderer

private data class JsonNumberPolicy(val value: String, val label: String) { override fun toString() = label }
private data class JsonInferredField(val wire: JsonObject, var selected: Boolean) {
    val id: String get() = wire.genString("fieldId")
    val key: String get() = wire.genString("jsonKey")
    val editable: Boolean get() = wire.get("editable")?.asBoolean == true
    fun notes(): String = buildList {
        if (wire.get("optional")?.asBoolean == true) add("Missing in some samples")
        if (wire.get("nullable")?.asBoolean == true) add("Contains null")
        wire.genString("reason").takeIf(String::isNotBlank)?.let(::add)
    }.joinToString("; ")
}

/** JSON inference is asynchronous. Only the exact last analyzed input may reach Preview. */
internal class GoJsonStructDialog(
    project: Project,
    initialInspection: JsonObject,
    mode: String,
    initialRequest: JsonObject = JsonObject(),
    draft: JsonObject? = null,
    private val inspect: (JsonObject, (JsonObject) -> Unit, (String) -> Unit) -> Unit,
) : DialogWrapper(project) {
    private val ownerProject = project
    private val mode = mode.also { require(it in setOf("type", "fields")) }
    private var inspection = initialInspection.deepCopy()
    private var analyzedRequest = initialRequest.deepCopy()
    private var stale = draft != null || initialInspection.genString("inspectionId").isEmpty() || initialInspection.genString("mode").let { it.isNotEmpty() && it != mode }
    private var loading = false
    private var error: String? = null
    private var sequence = 0L
    private val json = EditorTextField(draft?.genString("jsonText") ?: initialRequest.genString("jsonText"), project,
        FileTypeManager.getInstance().getFileTypeByExtension("json")).apply {
        setOneLineMode(false)
        setSupplementary(true)
        setPlaceholder("Paste JSON here")
        minimumSize = Dimension(300, 180)
        addSettingsProvider { editor -> editor.settings.isLineNumbersShown = true }
    }
    private val typeName = JBTextField(draft?.genString("typeName") ?: initialRequest.genString("typeName").ifEmpty { "Root" })
    private val numericPolicy = ComboBox(arrayOf(
        JsonNumberPolicy("auto", "Infer from numeric samples"),
        JsonNumberPolicy("number", "Preserve numbers as json.Number"),
    )).apply { selectedIndex = if ((draft?.genString("numericPolicy") ?: initialRequest.genString("numericPolicy")) == "number") 1 else 0 }
    private val fieldSelections = linkedMapOf<String, Boolean>()
    private val fieldModel = object : AbstractTableModel() {
        var rows = emptyList<JsonInferredField>()
        private val offset: Int get() = if (mode == "fields") 1 else 0
        override fun getRowCount() = rows.size
        override fun getColumnCount() = 4 + offset
        override fun getColumnName(column: Int) = if (offset == 1 && column == 0) "Generate" else listOf("JSON key", "Go field", "Go type", "Notes")[column - offset]
        override fun getColumnClass(columnIndex: Int): Class<*> = if (offset == 1 && columnIndex == 0) java.lang.Boolean::class.java else String::class.java
        override fun isCellEditable(rowIndex: Int, columnIndex: Int) = offset == 1 && columnIndex == 0 && rows[rowIndex].editable && !stale && !loading
        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any = rows[rowIndex].let { field ->
            if (offset == 1 && columnIndex == 0) field.selected else when (columnIndex - offset) {
                0 -> field.key
                1 -> field.wire.genString("name")
                2 -> field.wire.genString("type")
                else -> field.notes()
            }
        }
        override fun setValueAt(value: Any?, rowIndex: Int, columnIndex: Int) {
            if (!isCellEditable(rowIndex, columnIndex)) return
            rows[rowIndex].selected = value == true
            fieldSelections[rows[rowIndex].key] = rows[rowIndex].selected
            fireTableRowsUpdated(rowIndex, rowIndex)
            updateState()
        }
    }
    private val fieldsTable = object : JBTable(fieldModel) {
        override fun prepareRenderer(renderer: TableCellRenderer, row: Int, column: Int): Component =
            super.prepareRenderer(renderer, row, column).apply { isEnabled = mode != "fields" || fieldModel.rows[convertRowIndexToModel(row)].editable }
        override fun getToolTipText(event: MouseEvent): String? = rowAtPoint(event.point).takeIf { it >= 0 }
            ?.let { fieldModel.rows[convertRowIndexToModel(it)].notes().takeIf(String::isNotBlank) }
    }.apply { putClientProperty("terminateEditOnFocusLost", true) }
    private val declarationsModel = object : AbstractTableModel() {
        var rows = emptyList<JsonObject>()
        override fun getRowCount() = rows.size
        override fun getColumnCount() = 3
        override fun getColumnName(column: Int) = listOf("Name", "Kind", "Go type")[column]
        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any = rows[rowIndex].genString(listOf("name", "kind", "type")[columnIndex])
    }
    private val declarationsTable = JBTable(declarationsModel)
    private val generated = JTextArea().apply { isEditable = false; font = Font(Font.MONOSPACED, Font.PLAIN, 12) }
    private val rootType = JBLabel()
    private val messages = JTextArea(5, 60).apply { isEditable = false; isOpaque = false; lineWrap = true; wrapStyleWord = true }
    private val analyze = JButton("Analyze / Refresh").apply { addActionListener { analyze() } }
    private val panel = JPanel(BorderLayout(0, 10))
    private var visibleInput = JsonObject()

    init {
        draft?.getAsJsonObject("fieldSelections")?.entrySet()?.forEach { (key, value) -> fieldSelections[key] = value.asBoolean }
        title = if (mode == "type") "Generate Go Type from JSON" else "Generate Struct Fields from JSON"
        setOKButtonText("Preview")
        json.setDisposedWith(disposable)
        init()
        replaceAnalysis()
        visibleInput = request()
        if (analyzedRequest != visibleInput) stale = true
        json.addDocumentListener(object : EditorDocumentListener { override fun documentChanged(event: EditorDocumentEvent) = changed() })
        typeName.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(event: DocumentEvent) = changed()
            override fun removeUpdate(event: DocumentEvent) = changed()
            override fun changedUpdate(event: DocumentEvent) = changed()
        })
        numericPolicy.addActionListener { changed() }
        updateState()
    }

    override fun getPreferredFocusedComponent(): JComponent = json
    override fun createCenterPanel(): JComponent = panel.apply {
        preferredSize = Dimension(1040, 780)
        add(JPanel(GridBagLayout()).apply {
            fun row(index: Int, label: String, component: JComponent) {
                add(JBLabel(label).apply { labelFor = component }, GridBagConstraints().apply {
                    gridx = 0; gridy = index; anchor = GridBagConstraints.WEST; insets = Insets(3, 0, 3, 10)
                })
                add(component, GridBagConstraints().apply {
                    gridx = 1; gridy = index; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL; insets = Insets(3, 0, 3, 0)
                })
            }
            if (mode == "type") row(0, "Root type name:", typeName)
            row(1, "Numbers:", numericPolicy)
            row(2, "", JBLabel("JSON samples suggest types; they do not define a complete schema. Nested objects receive named Go types."))
        }, BorderLayout.NORTH)
        add(JSplitPane(JSplitPane.VERTICAL_SPLIT,
            JPanel(BorderLayout(0, 4)).apply { add(JBLabel("JSON input:"), BorderLayout.NORTH); add(json, BorderLayout.CENTER) },
            JPanel(BorderLayout(0, 4)).apply {
                add(rootType, BorderLayout.NORTH)
                add(JBTabbedPane().apply {
                    addTab("Fields", JBScrollPane(fieldsTable))
                    addTab("Types", JBScrollPane(declarationsTable))
                    addTab("Generated Go", JBScrollPane(generated))
                }, BorderLayout.CENTER)
            }).apply { resizeWeight = 0.5; dividerLocation = 300 }, BorderLayout.CENTER)
        add(JPanel(BorderLayout(10, 0)).apply {
            add(JBScrollPane(messages), BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.RIGHT)).apply { add(analyze) }, BorderLayout.EAST)
        }, BorderLayout.SOUTH)
    }

    private fun request() = JsonObject().apply {
        addProperty("mode", mode); addProperty("jsonText", json.text)
        if (mode == "type") addProperty("typeName", typeName.text.trim())
        addProperty("numericPolicy", (numericPolicy.selectedItem as JsonNumberPolicy).value)
    }
    private fun changed() {
        if (isDisposed || ownerProject.isDisposed) return
        val current = request()
        if (current == visibleInput) return
        visibleInput = current
        sequence++
        loading = false; stale = true; error = null
        updateState()
    }
    private fun analyze() {
        if (fieldsTable.isEditing && !fieldsTable.cellEditor.stopCellEditing()) return
        inputValidation()?.let { error = it.message; updateState(); return }
        val input = request()
        visibleInput = input.deepCopy()
        val ticket = ++sequence
        loading = true; stale = true; error = null
        updateState()
        fun deliver(action: () -> Unit) {
            val task = Runnable { if (!isDisposed && !ownerProject.isDisposed && sequence == ticket) action() }
            val app = ApplicationManager.getApplication()
            // Only this dialog's widgets change here, never project/source state.
            if (app.isDispatchThread) task.run() else app.invokeLater(task, ModalityState.any())
        }
        try {
            inspect(input.deepCopy(), { response -> deliver {
                if (response.genString("mode") != mode) {
                    loading = false; stale = true; error = "Analysis returned a different generation mode. Analyze again."
                } else {
                    inspection = response.deepCopy(); analyzedRequest = input.deepCopy()
                    loading = false; stale = false; error = null
                    replaceAnalysis()
                }
                updateState()
            } }, { message -> deliver {
                loading = false; stale = true; error = message.ifBlank { "JSON analysis failed. Analyze to try again." }
                updateState()
            } })
        } catch (failure: Exception) { deliver { loading = false; stale = true; error = failure.message ?: "JSON analysis failed."; updateState() } }
    }
    private fun replaceAnalysis() {
        fieldModel.rows = inspection.getAsJsonArray("fields")?.map { item ->
            val field = item.asJsonObject.deepCopy()
            JsonInferredField(field, field.get("editable")?.asBoolean == true && (fieldSelections[field.genString("jsonKey")] ?: true))
        }.orEmpty()
        fieldModel.fireTableDataChanged()
        declarationsModel.rows = inspection.getAsJsonArray("declarations")?.map { it.asJsonObject.deepCopy() }.orEmpty()
        declarationsModel.fireTableDataChanged()
        generated.text = inspection.genString("preview")
        generated.caretPosition = 0
        rootType.text = inspection.genString("rootType").takeIf(String::isNotBlank)?.let { "Inferred Go type: $it" } ?: "Analyze JSON to see inferred Go types."
    }
    private fun conflicts() = inspection.getAsJsonArray("conflicts")?.map { it.asJsonObject.genString("message") }?.filter(String::isNotBlank).orEmpty()
    private fun warnings() = inspection.getAsJsonArray("warnings")?.map { item -> item.asJsonObject.let { warning ->
        listOf(warning.genString("path"), warning.genString("message")).filter(String::isNotBlank).joinToString(": ")
    } }?.filter(String::isNotBlank).orEmpty()
    private fun inputValidation(): ValidationInfo? {
        if (json.text.isBlank()) return ValidationInfo("Paste JSON and click Analyze / Refresh.", json)
        if (mode == "type" && !GoIdentifiers.valid(typeName.text.trim())) return ValidationInfo("Enter a valid Go root type name.", typeName)
        return null
    }
    private fun validation(): ValidationInfo? {
        inputValidation()?.let { return it }
        if (loading || stale || request() != analyzedRequest) return ValidationInfo("Analyze the current JSON and settings before Preview.", analyze)
        error?.let { return ValidationInfo(it, analyze) }
        conflicts().firstOrNull()?.let { return ValidationInfo(it, json) }
        if (listOf("inspectionId", "configurationDigest", "structId").any { inspection.genString(it).isEmpty() })
            return ValidationInfo("Analyze JSON before Preview.", analyze)
        if (mode == "fields" && fieldModel.rows.none { it.editable && it.selected }) return ValidationInfo("Select at least one new editable field.", fieldsTable)
        return null
    }
    private fun updateState() {
        val ready = !loading && !stale
        fieldsTable.isEnabled = ready; declarationsTable.isEnabled = ready; generated.isEnabled = ready
        rootType.isEnabled = ready
        analyze.isEnabled = !loading && inputValidation() == null
        isOKActionEnabled = validation() == null
        val headline = when {
            loading -> "Analyzing JSON…"
            error != null -> error.orEmpty()
            inputValidation() != null -> inputValidation()!!.message
            stale && inspection.genString("inspectionId").isEmpty() -> "Click Analyze / Refresh to infer Go types from these JSON samples."
            stale -> "Input changed. Analyze again before Preview; the types below are from the previous analysis."
            conflicts().isNotEmpty() -> conflicts().joinToString("\n")
            mode == "fields" && fieldModel.rows.none { it.editable && it.selected } -> "Select at least one new editable field."
            else -> "Review the inferred types and warnings. Preview checks the current Go workspace before applying changes."
        }
        messages.text = (listOf(headline) + warnings().map { "${if (ready) "Warning" else "Last analysis warning"}: $it" }).joinToString("\n")
        messages.caretPosition = 0
        messages.foreground = if (error != null || ready && conflicts().isNotEmpty()) JBColor.RED else JBColor.foreground()
    }
    override fun doValidate(): ValidationInfo? = validation()
    override fun doOKAction() { if (!fieldsTable.isEditing || fieldsTable.cellEditor.stopCellEditing()) super.doOKAction() }
    fun backendRequest(): JsonObject = analyzedRequest.deepCopy()
    fun selection(): JsonObject {
        check(!fieldsTable.isEditing || fieldsTable.cellEditor.stopCellEditing()) { "Finish selecting fields." }
        check(validation() == null) { validation()?.message ?: "Analyze the current JSON before Preview." }
        return backendRequest().apply {
            listOf("inspectionId", "configurationDigest", "structId").forEach { addProperty(it, inspection.genString(it)) }
            if (mode == "fields") add("fieldIds", JsonArray().also { ids -> fieldModel.rows.filter { it.editable && it.selected }.forEach { ids.add(it.id) } })
        }
    }
    fun draft(): JsonObject = request().apply {
        if (fieldsTable.isEditing) fieldsTable.cellEditor.stopCellEditing()
        addProperty("typeName", typeName.text)
        add("fieldSelections", JsonObject().also { selected ->
            fieldSelections.forEach { (key, value) -> selected.addProperty(key, value) }
            fieldModel.rows.forEach { selected.addProperty(it.key, it.selected) }
        })
    }
}
