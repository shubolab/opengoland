package dev.openidea.goide.refactor.signature

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import dev.openidea.goide.semantic.GoIdentifiers
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.ListSelectionModel
import javax.swing.table.AbstractTableModel

internal class GoSignatureTableModel(original: List<GoSignatureRow>, private val results: Boolean) : AbstractTableModel() {
    val rows = original.map { it.copy() }.toMutableList()
    override fun getRowCount() = rows.size
    override fun getColumnCount() = if (results) 3 else 4
    override fun getColumnName(column: Int) = when (column) { 0 -> "Name"; 1 -> "Type"; 2 -> if (results) "Return value / conversion" else "Variadic"; else -> "Default / caller conversion" }
    override fun getColumnClass(columnIndex: Int): Class<*> = if (!results && columnIndex == 2) java.lang.Boolean::class.java else String::class.java
    override fun isCellEditable(rowIndex: Int, columnIndex: Int) = true
    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any = rows[rowIndex].let { row -> when (columnIndex) {
        0 -> row.name; 1 -> row.type; 2 -> if (results) row.defaultExpression else row.variadic; else -> row.defaultExpression
    } }
    override fun setValueAt(value: Any?, rowIndex: Int, columnIndex: Int) {
        val row = rows[rowIndex]
        when (columnIndex) { 0 -> row.name = value?.toString().orEmpty().trim(); 1 -> row.type = value?.toString().orEmpty().trim()
            2 -> if (results) row.defaultExpression = value?.toString().orEmpty() else row.variadic = value == true
            else -> row.defaultExpression = value?.toString().orEmpty() }
        fireTableRowsUpdated(rowIndex, rowIndex)
    }
    fun add(): Int { rows += GoSignatureRow.new(results); fireTableRowsInserted(rows.lastIndex, rows.lastIndex); return rows.lastIndex }
    fun remove(index: Int) { if (index in rows.indices) { rows.removeAt(index); fireTableRowsDeleted(index, index) } }
    fun move(index: Int, direction: Int): Int {
        val target = index + direction
        if (index !in rows.indices || target !in rows.indices) return index
        val item = rows.removeAt(index); rows.add(target, item); fireTableDataChanged(); return target
    }
    fun json(): JsonArray = JsonArray().also { array -> rows.forEach { array.add(it.json(results)) } }
}

/** Native editable parameter/result tables. Preview is performed by the shared atomic transaction UI. */
internal class GoChangeSignatureDialog(project: Project, private val inspection: GoSignatureInspection, draft: JsonObject? = null) : DialogWrapper(project) {
    private val functionName = JBTextField(draft?.string("name") ?: inspection.name)
    private val parameters = GoSignatureTableModel(draft?.array("parameters")?.map { GoSignatureRow.parse(it.asJsonObject, false) } ?: inspection.parameters, false)
    private val results = GoSignatureTableModel(draft?.array("results")?.map { GoSignatureRow.parse(it.asJsonObject, true) } ?: inspection.results, true)
    private val parameterTable = JBTable(parameters)
    private val resultTable = JBTable(results)
    private val signature = JTextArea()
    private val parameterMigrations = migrationFields(inspection.parameters, draft, "parameterBodyMigrations")
    private val resultMigrations = migrationFields(inspection.results, draft, "removedResultUseMigrations")
    init {
        title = "Change Signature"
        setOKButtonText("Preview")
        init()
        functionName.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent) = updateSignature()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent) = updateSignature()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) = updateSignature()
        })
        parameters.addTableModelListener { updateSignature() }; results.addTableModelListener { updateSignature() }
        updateSignature()
    }
    override fun getPreferredFocusedComponent(): JComponent = functionName
    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, 12)).apply {
        preferredSize = Dimension(970, 640)
        add(JPanel(BorderLayout(10, 8)).apply {
            add(JBLabel("Name:"), BorderLayout.WEST); add(functionName, BorderLayout.CENTER)
            val receiver = inspection.wire.string("receiver").let { if (it.isEmpty()) "" else "Receiver: $it · " }
            add(JBLabel("${receiver}Related declarations: ${inspection.wire.get("relatedDeclarations")?.asInt ?: 1} · Function value uses: ${inspection.wire.get("functionValues")?.asInt ?: 0}"), BorderLayout.SOUTH)
        }, BorderLayout.NORTH)
        add(JBTabbedPane().apply {
            addTab("Parameters", tablePanel(parameterTable, parameters))
            addTab("Result parameters", tablePanel(resultTable, results))
            addTab("Body and usage migrations", migrationPanel())
        }, BorderLayout.CENTER)
        add(JPanel(BorderLayout(0, 6)).apply {
            add(JBLabel("Signature preview:"), BorderLayout.NORTH)
            signature.isEditable = false; signature.lineWrap = true; signature.wrapStyleWord = true
            signature.font = Font(Font.MONOSPACED, Font.PLAIN, 13); signature.rows = 4
            add(JBScrollPane(signature), BorderLayout.CENTER)
            add(JBLabel("Empty defaults generate the Go zero value. Existing arguments keep their evaluation order. Related interface declarations and implementations are updated together."), BorderLayout.SOUTH)
        }, BorderLayout.SOUTH)
    }
    private fun tablePanel(table: JBTable, model: GoSignatureTableModel) = JPanel(BorderLayout(0, 8)).apply {
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        table.putClientProperty("terminateEditOnFocusLost", true)
        add(JBScrollPane(table), BorderLayout.CENTER)
        add(JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            fun button(name: String, action: () -> Unit) { add(JButton(name).apply { addActionListener { finishEditing(); action() } }) }
            button("Add") { val row = model.add(); table.setRowSelectionInterval(row, row) }
            button("Remove") { model.remove(table.selectedRow) }
            button("Move Up") { val row = model.move(table.selectedRow, -1); if (row >= 0) table.setRowSelectionInterval(row, row) }
            button("Move Down") { val row = model.move(table.selectedRow, 1); if (row >= 0) table.setRowSelectionInterval(row, row) }
        }, BorderLayout.SOUTH)
    }
    private fun migrationFields(rows: List<GoSignatureRow>, draft: JsonObject?, key: String): Map<String, JBTextField> {
        val existing = draft?.array(key)?.associate { it.asJsonObject.string("rowId") to it.asJsonObject.string("expression") }.orEmpty()
        return rows.associate { it.rowId to JBTextField(existing[it.rowId].orEmpty()) }
    }
    private fun migrationPanel(): JComponent = JBScrollPane(JPanel().apply {
        layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
        add(JBLabel("Parameter body replacements / old-type bindings"))
        add(JBLabel("Supply an expression when a removed parameter is still used, or its new type needs an explicit conversion for the existing body."))
        inspection.parameters.forEachIndexed { index, row -> add(migrationRow("${row.name.ifBlank { "Parameter ${index + 1}" }} (${row.type})", parameterMigrations.getValue(row.rowId))) }
        add(javax.swing.Box.createVerticalStrut(18))
        add(JBLabel("Removed result replacements at callers"))
        add(JBLabel("Supply the value callers should receive if a removed result is still used. Leave blank to report unresolved uses in Preview."))
        inspection.results.forEachIndexed { index, row -> add(migrationRow("${row.name.ifBlank { "Result ${index + 1}" }} (${row.type})", resultMigrations.getValue(row.rowId))) }
    })
    private fun migrationRow(label: String, field: JBTextField) = JPanel(BorderLayout(10, 0)).apply {
        maximumSize = Dimension(Int.MAX_VALUE, 40); preferredSize = Dimension(900, 40)
        add(JBLabel(label).apply { preferredSize = Dimension(260, 28) }, BorderLayout.WEST); add(field, BorderLayout.CENTER)
    }
    private fun finishEditing() { listOf(parameterTable, resultTable).forEach { if (it.isEditing) it.cellEditor.stopCellEditing() } }
    private fun updateSignature() {
        fun field(row: GoSignatureRow) = listOf(row.name, (if (row.variadic) "..." else "") + row.type).filter(String::isNotBlank).joinToString(" ")
        signature.text = "func ${functionName.text}(${parameters.rows.joinToString(", ", transform = ::field)})" +
            results.rows.takeIf { it.isNotEmpty() }?.joinToString(", ", " (", ")", transform = ::field).orEmpty()
    }
    override fun doValidate(): ValidationInfo? {
        if (!GoIdentifiers.valid(functionName.text.trim())) return ValidationInfo("Enter a valid Go function or method name.", functionName)
        for ((rows, table) in listOf(parameters.rows to parameterTable, results.rows to resultTable)) {
            if (rows.any { it.type.isBlank() }) return ValidationInfo("Every parameter and result needs a Go type.", table)
            if (rows.any { it.name.isNotBlank() && it.name != "_" && !GoIdentifiers.valid(it.name) }) return ValidationInfo("Enter valid Go parameter names.", table)
            val names = rows.map { it.name }.filter { it.isNotBlank() && it != "_" }
            if (names.distinct().size != names.size) return ValidationInfo("Parameter names must be unique.", table)
        }
        if (parameters.rows.withIndex().any { it.value.variadic && it.index != parameters.rows.lastIndex }) return ValidationInfo("The variadic parameter must be last.", parameterTable)
        if (results.rows.any { it.name.isNotBlank() } && results.rows.any { it.name.isBlank() }) return ValidationInfo("Name every result parameter, or leave all result names empty.", resultTable)
        return null
    }
    override fun doOKAction() { finishEditing(); super.doOKAction() }
    fun change(): JsonObject = JsonObject().apply {
        finishEditing()
        addProperty("name", functionName.text.trim()); add("parameters", parameters.json()); add("results", results.json())
        fun migrations(fields: Map<String, JBTextField>) = JsonArray().also { values -> fields.forEach { (rowId, field) ->
            if (field.text.isNotBlank()) values.add(JsonObject().apply { addProperty("rowId", rowId); addProperty("expression", field.text.trim()) })
        } }
        add("parameterBodyMigrations", migrations(parameterMigrations)); add("removedResultUseMigrations", migrations(resultMigrations))
    }
}
