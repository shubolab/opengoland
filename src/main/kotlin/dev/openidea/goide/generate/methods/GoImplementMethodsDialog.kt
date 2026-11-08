package dev.openidea.goide.generate.methods

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
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
import dev.openidea.goide.generate.tests.splitGoTestTypeArguments
import dev.openidea.goide.semantic.GoIdentifiers
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.AbstractTableModel

private data class ReceiverChoice(val name: String, val create: Boolean = false) {
    override fun toString() = if (create) "Create new struct…" else name.ifEmpty { "Choose receiver type" }
}
private data class InterfaceChoice(val id: String, val display: String) { override fun toString() = display }
private data class MethodChoice(val wire: JsonObject, var selected: Boolean) {
    val id: String get() = wire.genString("methodId")
    val missing: Boolean get() = wire.genString("status") == "missing"
}

/** Native drafts only: the owning service performs every inspection and workspace edit. */
internal class GoImplementMethodsDialog(
    project: Project,
    initialInspection: JsonObject,
    initialRequest: JsonObject = JsonObject(),
    draft: JsonObject? = null,
    private val inspect: (JsonObject, (JsonObject) -> Unit, (String) -> Unit) -> Unit,
) : DialogWrapper(project) {
    private var inspection = initialInspection.deepCopy()
    private var analyzedRequest = initialRequest.deepCopy()
    private var updating = true
    private var stale = draft != null
    private var loading = false
    private var error: String? = null
    private var sequence = 0L
    private var visibleInput = JsonObject()
    private val receivers = ComboBox<ReceiverChoice>()
    private val newTypeName = JBTextField(draft?.genString("newTypeName") ?: initialRequest.genString("newTypeName"))
    private val pointer = JBCheckBox("Pointer receiver", (draft?.genString("receiverKind") ?: initialInspection.genString("receiverKind")) != "value")
    private val receiverName = JBTextField(draft?.genString("receiverName") ?: "r")
    private val interfaces = ComboBox<InterfaceChoice>().apply { isEditable = true }
    private val importPath = JBTextField(draft?.genString("interfaceImportPath") ?: initialRequest.genString("interfaceImportPath"))
    private val typeArguments = JBTextField(draft?.genString("interfaceTypeArgumentsText")
        ?: initialRequest.getAsJsonArray("interfaceTypeArguments")?.joinToString(", ") { it.asString }.orEmpty())
    private val allMissing = JBCheckBox("Implement all missing methods", draft?.get("allMissing")?.asBoolean ?: true)
    private val status = JTextArea(4, 60).apply { isEditable = false; lineWrap = true; wrapStyleWord = true; isOpaque = false }
    private val selectedMethods = linkedMapOf<String, Boolean>()
    private val model = object : AbstractTableModel() {
        var rows = emptyList<MethodChoice>()
        override fun getRowCount() = rows.size
        override fun getColumnCount() = 4
        override fun getColumnName(column: Int) = listOf("Implement", "Method", "Status", "Details")[column]
        override fun getColumnClass(columnIndex: Int): Class<*> = if (columnIndex == 0) java.lang.Boolean::class.java else String::class.java
        override fun isCellEditable(rowIndex: Int, columnIndex: Int) = columnIndex == 0 && rows[rowIndex].missing && !stale && !loading
        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any = rows[rowIndex].let {
            when (columnIndex) {
                0 -> it.selected
                1 -> it.wire.genString("name") + it.wire.genString("signature").removePrefix("func")
                2 -> it.wire.genString("status")
                else -> it.wire.genString("message")
            }
        }
        override fun setValueAt(value: Any?, rowIndex: Int, columnIndex: Int) {
            if (columnIndex != 0 || !rows[rowIndex].missing || stale || loading) return
            rows[rowIndex].selected = value == true
            selectedMethods[rows[rowIndex].id] = rows[rowIndex].selected
            updating = true
            allMissing.isSelected = rows.filter { it.missing }.all { it.selected }
            updating = false
            fireTableRowsUpdated(rowIndex, rowIndex)
            updateState()
        }
        fun replace(rows: List<MethodChoice>) { this.rows = rows; fireTableDataChanged() }
    }
    private val table = JBTable(model).apply { putClientProperty("terminateEditOnFocusLost", true) }
    private val analyze = JButton("Analyze / Refresh").apply { addActionListener { analyze() } }
    private val panel = JPanel(BorderLayout(0, 10))

    init {
        draft?.getAsJsonObject("methodSelections")?.entrySet()?.forEach { (id, value) -> selectedMethods[id] = value.asBoolean }
        title = "Implement Methods"
        setOKButtonText("Preview")
        setCatalog(initialInspection,
            draft?.genString("receiverType") ?: initialRequest.genString("receiverType").ifEmpty { initialInspection.getAsJsonObject("receiver")?.genString("name").orEmpty() },
            draft?.get("newType")?.asBoolean ?: (initialInspection.getAsJsonObject("receiver")?.get("new")?.asBoolean == true || newTypeName.text.isNotBlank()),
            draft?.genString("interfaceId") ?: initialRequest.genString("interfaceId").ifEmpty { initialInspection.getAsJsonObject("selectedInterface")?.genString("interfaceId").orEmpty() },
            draft?.genString("interfaceExpression") ?: initialRequest.genString("interfaceExpression"))
        init()
        replaceMethods()
        visibleInput = inputSnapshot()
        updating = false
        receivers.addActionListener { changed() }
        interfaces.addActionListener { changed() }
        pointer.addActionListener { changed() }
        listen(newTypeName, ::changed); listen(importPath, ::changed); listen(typeArguments, ::changed)
        (interfaces.editor.editorComponent as? JTextField)?.let { listen(it, ::changed) }
        allMissing.addActionListener {
            if (!updating && !stale && !loading && allMissing.isSelected) {
                model.rows.filter { it.missing }.forEach { it.selected = true; selectedMethods[it.id] = true }
                model.fireTableDataChanged()
            }
            updateState()
        }
        updateState()
    }

    override fun getPreferredFocusedComponent(): JComponent = interfaces
    override fun createCenterPanel(): JComponent = panel.apply {
        preferredSize = Dimension(1020, 620)
        add(JPanel(GridBagLayout()).apply {
            fun row(index: Int, label: String, component: JComponent) {
                add(JBLabel(label).apply { labelFor = component }, GridBagConstraints().apply { gridx = 0; gridy = index; anchor = GridBagConstraints.WEST; insets = Insets(3, 0, 3, 10) })
                add(component, GridBagConstraints().apply { gridx = 1; gridy = index; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL; insets = Insets(3, 0, 3, 0) })
            }
            row(0, "Receiver type:", receivers)
            row(1, "New struct name:", newTypeName)
            row(2, "Receiver:", JPanel(BorderLayout(10, 0)).apply { add(pointer, BorderLayout.WEST); add(receiverName, BorderLayout.CENTER) })
            row(3, "Interface:", interfaces)
            row(4, "Additional import path (optional):", importPath)
            row(5, "Interface type arguments:", typeArguments)
            row(6, "", JBLabel("Choose a catalog interface or enter a Go interface expression. Generic arguments use top-level commas."))
        }, BorderLayout.NORTH)
        add(JPanel(BorderLayout(0, 5)).apply { add(allMissing, BorderLayout.NORTH); add(JBScrollPane(table), BorderLayout.CENTER) }, BorderLayout.CENTER)
        add(JPanel(BorderLayout(10, 5)).apply {
            add(status, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.RIGHT)).apply { add(analyze) }, BorderLayout.SOUTH)
        }, BorderLayout.SOUTH)
        table.columnModel.getColumn(0).preferredWidth = 70
        table.columnModel.getColumn(1).preferredWidth = 450
        table.columnModel.getColumn(2).preferredWidth = 90
        table.columnModel.getColumn(3).preferredWidth = 350
    }

    private fun setCatalog(response: JsonObject, receiver: String, create: Boolean, interfaceId: String, expression: String) {
        receivers.removeAllItems()
        receivers.addItem(ReceiverChoice(""))
        response.getAsJsonArray("receiverCandidates")?.forEach { receivers.addItem(ReceiverChoice(it.asJsonObject.genString("name"))) }
        receivers.addItem(ReceiverChoice("", true))
        receivers.selectedItem = if (create) ReceiverChoice("", true) else
            (0 until receivers.itemCount).map { receivers.getItemAt(it) }.firstOrNull { !it.create && it.name == receiver } ?: ReceiverChoice("")
        interfaces.removeAllItems()
        interfaces.addItem(InterfaceChoice("", ""))
        val catalog = response.getAsJsonArray("interfaces")?.map { it.asJsonObject }
            ?.distinctBy { it.genString("interfaceId") }.orEmpty()
        val displayCounts = catalog.groupingBy { it.genString("display") }.eachCount()
        catalog.forEach { item ->
            val id = item.genString("interfaceId")
            val display = item.genString("display")
            val packagePath = item.genString("packagePath").ifBlank { id.substringBeforeLast('.', id) }
            val label = if (displayCounts.getValue(display) > 1) "$display — $packagePath" else display
            interfaces.addItem(InterfaceChoice(id, label))
        }
        val selected = (0 until interfaces.itemCount).map { interfaces.getItemAt(it) }.firstOrNull { it.id.isNotEmpty() && it.id == interfaceId }
            ?: interfaceId.takeIf(String::isNotEmpty)?.let { id ->
                // A restored draft may refer to a catalog loaded by a later analysis,
                // while the service deliberately reopens the original inspection.
                InterfaceChoice(id, id).also(interfaces::addItem)
            }
        interfaces.selectedItem = selected ?: expression
    }

    private fun currentReceiver() = receivers.selectedItem as? ReceiverChoice ?: ReceiverChoice("")
    private fun interfaceInput(): Pair<String, String> {
        val value = interfaces.editor.item
        val text = value?.toString().orEmpty().trim()
        val item = (interfaces.selectedItem as? InterfaceChoice)?.takeIf { it.id.isNotEmpty() && text == it.display }
            ?: (0 until interfaces.itemCount).map { interfaces.getItemAt(it) }
                .filter { it.id.isNotEmpty() && it.display == text }.singleOrNull()
        // Editable JComboBox commits may replace its selected object with the
        // editor's String. A unique visible catalog label retains its exact ID.
        return if (item != null) item.id to "" else "" to text
    }

    private fun inputFields(): JsonObject = JsonObject().apply {
        analyzedRequest.get("catalogPackages")?.let { add("catalogPackages", it.deepCopy()) }
        val receiver = currentReceiver()
        if (receiver.create) addProperty("newTypeName", newTypeName.text.trim()) else if (receiver.name.isNotEmpty()) addProperty("receiverType", receiver.name)
        addProperty("receiverKind", if (pointer.isSelected) "pointer" else "value")
        val (id, expression) = interfaceInput()
        if (id.isNotEmpty()) addProperty("interfaceId", id) else if (expression.isNotEmpty()) addProperty("interfaceExpression", expression)
        if (importPath.text.isNotBlank()) addProperty("interfaceImportPath", importPath.text.trim())
    }

    private fun request(): JsonObject = inputFields().apply {
        if (typeArguments.text.isNotBlank()) add("interfaceTypeArguments", JsonArray().also { values -> splitGoTestTypeArguments(typeArguments.text).forEach(values::add) })
    }

    private fun inputSnapshot(): JsonObject = inputFields().apply {
        if (typeArguments.text.isNotBlank()) {
            val arguments = runCatching { splitGoTestTypeArguments(typeArguments.text) }.getOrNull()
            if (arguments != null) add("interfaceTypeArguments", JsonArray().also { values -> arguments.forEach(values::add) })
            else addProperty("unparsedInterfaceTypeArguments", typeArguments.text)
        }
    }

    private fun changed() {
        if (updating || isDisposed) return
        val current = inputSnapshot()
        if (current == visibleInput) return
        visibleInput = current
        sequence++
        loading = false
        stale = true
        error = null
        updateState()
    }

    private fun analyze() {
        if (table.isEditing && !table.cellEditor.stopCellEditing()) return
        inputValidation()?.let { error = it.message; updateState(); return }
        val input = request()
        visibleInput = inputSnapshot()
        val ticket = ++sequence
        loading = true; stale = true; error = null
        updateState()
        fun deliver(action: () -> Unit) {
            val task = Runnable { if (!isDisposed && ticket == sequence) action() }
            val app = ApplicationManager.getApplication()
            if (app.isDispatchThread) task.run() else app.invokeLater(task, ModalityState.any())
        }
        try {
            inspect(input.deepCopy(), { response -> deliver {
                inspection = response.deepCopy()
                analyzedRequest = input.deepCopy()
                loading = false; stale = false; error = null
                updating = true
                val receiver = currentReceiver()
                val (id, expression) = interfaceInput()
                setCatalog(inspection, receiver.name, receiver.create, id, expression)
                replaceMethods()
                visibleInput = inputSnapshot()
                updating = false
                updateState()
            } }, { message -> deliver { loading = false; stale = true; error = message.ifBlank { "Interface analysis failed." }; updateState() } })
        } catch (failure: Exception) { deliver { loading = false; stale = true; error = failure.message ?: "Interface analysis failed."; updateState() } }
    }

    private fun replaceMethods() {
        model.replace(inspection.getAsJsonArray("methods")?.map { value ->
            val method = value.asJsonObject.deepCopy()
            MethodChoice(method, method.genString("status") == "missing" && (allMissing.isSelected || (selectedMethods[method.genString("methodId")] ?: true)))
        }.orEmpty())
    }

    private fun conflicts() = inspection.getAsJsonArray("conflicts")?.map { it.asJsonObject.genString("message") }.orEmpty()
    private fun hasPair() = inspection.getAsJsonObject("receiver") != null && inspection.getAsJsonObject("selectedInterface") != null
    private fun canGenerate() = model.rows.any { it.selected && it.missing } || inspection.getAsJsonObject("receiver")?.get("new")?.asBoolean == true
    private fun updateState() {
        newTypeName.isEnabled = currentReceiver().create
        table.isEnabled = !loading && !stale
        allMissing.isEnabled = !loading && !stale
        analyze.isEnabled = !loading
        val conflicts = conflicts()
        status.text = when {
            loading -> "Analyzing receiver and interface…"
            error != null -> error
            stale -> "Selection changed. Analyze the receiver and interface before Preview."
            conflicts.isNotEmpty() -> conflicts.joinToString("\n")
            !hasPair() -> "Choose an existing receiver or a new struct and an interface, then click Analyze / Refresh."
            !canGenerate() -> "All required methods already exist."
            model.rows.any { it.wire.genString("status") == "conflict" } -> "Some methods conflict with existing declarations. Select the missing methods you want to generate; Preview checks the complete candidate."
            else -> "Select all missing methods or a subset. Preview checks Go types, imports and the current workspace before applying changes."
        }
        status.foreground = if (error != null || !stale && conflicts.isNotEmpty()) JBColor.RED else JBColor.foreground()
        isOKActionEnabled = !loading && !stale && error == null && conflicts.isEmpty() && hasPair() && canGenerate()
    }

    private fun inputValidation(): ValidationInfo? {
        val receiver = currentReceiver()
        if (!receiver.create && receiver.name.isEmpty()) return ValidationInfo("Choose a receiver type or create a new struct.", receivers)
        if (receiver.create && !GoIdentifiers.valid(newTypeName.text.trim())) return ValidationInfo("Enter a valid new Go struct name.", newTypeName)
        if (interfaceInput().let { it.first.isEmpty() && it.second.isEmpty() }) return ValidationInfo("Choose or enter an interface.", interfaces)
        try { splitGoTestTypeArguments(typeArguments.text) } catch (failure: IllegalArgumentException) { return ValidationInfo(failure.message ?: "Enter valid concrete interface types.", typeArguments) }
        return null
    }

    override fun doValidate(): ValidationInfo? {
        if (loading || stale) return ValidationInfo("Analyze the current receiver/interface selection before Preview.", analyze)
        error?.let { return ValidationInfo(it, analyze) }
        conflicts().firstOrNull()?.let { return ValidationInfo(it, table) }
        inputValidation()?.let { return it }
        if (!GoIdentifiers.valid(receiverName.text.trim())) return ValidationInfo("Enter a valid Go receiver variable name.", receiverName)
        if (!hasPair() || !canGenerate()) return ValidationInfo("Select at least one missing method or a new struct.", table)
        return null
    }

    override fun doOKAction() { if ((!table.isEditing || table.cellEditor.stopCellEditing()) && !loading && !stale) super.doOKAction() }
    fun backendRequest(): JsonObject = analyzedRequest.deepCopy()

    fun selection(): JsonObject {
        check(doValidate() == null) { "Analyze the current selection before Preview." }
        return backendRequest().apply {
            addProperty("inspectionId", inspection.genString("inspectionId")); addProperty("configurationDigest", inspection.genString("configurationDigest"))
            addProperty("receiverId", inspection.getAsJsonObject("receiver").genString("receiverId"))
            addProperty("receiverName", receiverName.text.trim()); addProperty("allMissing", allMissing.isSelected)
            add("methodIds", JsonArray().also { ids -> model.rows.filter { it.missing && it.selected }.forEach { ids.add(it.id) } })
        }
    }

    fun draft(): JsonObject = JsonObject().apply {
        val receiver = currentReceiver(); val (id, expression) = interfaceInput()
        addProperty("receiverType", receiver.name); addProperty("newType", receiver.create); addProperty("newTypeName", newTypeName.text)
        addProperty("receiverKind", if (pointer.isSelected) "pointer" else "value"); addProperty("receiverName", receiverName.text)
        addProperty("interfaceId", id); addProperty("interfaceExpression", expression)
        addProperty("interfaceImportPath", importPath.text); addProperty("interfaceTypeArgumentsText", typeArguments.text)
        addProperty("allMissing", allMissing.isSelected)
        add("methodSelections", JsonObject().also { selected ->
            selectedMethods.forEach { (key, value) -> selected.addProperty(key, value) }
            model.rows.forEach { selected.addProperty(it.id, it.selected) }
        })
    }

    private fun listen(field: JTextField, action: () -> Unit) = field.document.addDocumentListener(object : DocumentListener {
        override fun insertUpdate(event: DocumentEvent) = action()
        override fun removeUpdate(event: DocumentEvent) = action()
        override fun changedUpdate(event: DocumentEvent) = action()
    })
}
