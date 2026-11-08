package dev.openidea.goide.generate.tests

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import dev.openidea.goide.run.GoSourceFunctions
import dev.openidea.goide.lsp.GoDocumentSync
import dev.openidea.goide.semantic.GoIdentifiers
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.net.URI
import java.nio.file.Path
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.ListSelectionModel
import javax.swing.table.AbstractTableModel

private enum class GenerationScope(val wire: String, private val label: String) {
    EMPTY("empty", "Empty test"), FUNCTION("function", "Current function or method"), FILE("file", "Current file"), PACKAGE("package", "Current package");
    override fun toString() = label
}
private enum class GenerationPackage(val wire: String, private val label: String) {
    SAME("same", "Same package"), EXTERNAL("external", "External test package");
    override fun toString() = label
}

/** Native generation drafts only. The service owns snapshots, semantic inspection and Preview. */
internal class GoGenerateTestsDialog(
    project: Project,
    initialInspection: JsonObject,
    draft: JsonObject? = null,
    private val inspect: (String, String, (JsonObject) -> Unit, (String) -> Unit) -> Unit,
) : DialogWrapper(project) {
    private var inspection = initialInspection.deepCopy()
    private val scope = ComboBox(GenerationScope.entries.toTypedArray()).apply {
        selectedItem = GenerationScope.entries.firstOrNull { it.wire == (draft?.genString("scope")?.ifBlank { null } ?: inspection.genString("scope")) } ?: GenerationScope.FUNCTION
    }
    private val packageMode = ComboBox(GenerationPackage.entries.toTypedArray()).apply {
        selectedItem = GenerationPackage.entries.firstOrNull { it.wire == (draft?.genString("packageMode")?.ifBlank { null } ?: inspection.genString("packageMode")) } ?: GenerationPackage.SAME
    }
    private val emptyName = JBTextField(draft?.genString("emptyName")?.ifBlank { null } ?: "TestSomething")
    private val destination = JBTextField(draft?.genString("destinationPath").orEmpty())
    private val packageDescription = JBLabel()
    private val defaultDestination = JBLabel()
    private val status = JTextArea(3, 50).apply { isEditable = false; lineWrap = true; wrapStyleWord = true; isOpaque = false }
    private val rowDrafts = linkedMapOf<String, JsonObject>()
    private var displayedMode = inspection.genString("packageMode")
    private var sequence = 0L
    private var loading = false
    private var failure: String? = null
    private var ready = false
    private val tableModel = object : AbstractTableModel() {
        var rows = emptyList<GoGenerateTestsRow>()
        override fun getRowCount() = rows.size
        override fun getColumnCount() = 5
        override fun getColumnName(column: Int) = listOf("Generate", "Declaration", "Test name", "Concrete type arguments", "Status")[column]
        override fun getColumnClass(columnIndex: Int): Class<*> = if (columnIndex == 0) java.lang.Boolean::class.java else String::class.java
        override fun isCellEditable(rowIndex: Int, columnIndex: Int) = !loading && !packagePrivate(rows[rowIndex]) &&
            (columnIndex == 0 || columnIndex == 2 || columnIndex == 3 && rows[rowIndex].needsTypes)
        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any = rows[rowIndex].let {
            when (columnIndex) {
                0 -> it.selected; 1 -> it.declaration(); 2 -> it.testName; 3 -> it.typeArgumentsText
                else -> listOfNotNull(if (packagePrivate(it)) "Package private" else null, if (it.existing) "Existing test" else null).joinToString(" · ")
            }
        }
        override fun setValueAt(value: Any?, rowIndex: Int, columnIndex: Int) {
            val row = rows[rowIndex]
            when (columnIndex) { 0 -> row.selected = value == true && !packagePrivate(row); 2 -> row.testName = value?.toString().orEmpty(); 3 -> row.typeArgumentsText = value?.toString().orEmpty() }
            fireTableRowsUpdated(rowIndex, rowIndex)
        }
        fun replace(rows: List<GoGenerateTestsRow>) { this.rows = rows; fireTableDataChanged() }
    }
    private val table = JBTable(tableModel).apply {
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        putClientProperty("terminateEditOnFocusLost", true)
        autoResizeMode = JBTable.AUTO_RESIZE_LAST_COLUMN
    }
    private val cards = JPanel(CardLayout())
    private val retry = JButton("Refresh").apply { addActionListener { requestInspection() } }
    private val panel = JPanel(BorderLayout(0, 12))

    init {
        draft?.getAsJsonArray("rows")?.forEach {
            val row = it.asJsonObject
            rowDrafts[row.genString("packageMode") + ":" + row.genString("symbolId")] = row.deepCopy()
        }
        title = "Generate Tests"
        setOKButtonText("Preview")
        init()
        applyInspection(inspection)
        ready = true
        scope.addActionListener { if (ready) requestInspection() }
        packageMode.addActionListener { if (ready) requestInspection() }
        if (scopeValue() != inspection.genString("scope") || modeValue() != inspection.genString("packageMode")) requestInspection()
    }

    override fun getPreferredFocusedComponent(): JComponent = if (scopeValue() == "empty") emptyName else table

    override fun createCenterPanel(): JComponent = panel.apply {
        preferredSize = Dimension(1080, 560)
        add(JPanel(GridBagLayout()).apply {
            fun row(index: Int, label: String, component: JComponent) {
                add(JBLabel(label), GridBagConstraints().apply { gridx = 0; gridy = index; anchor = GridBagConstraints.WEST; insets = Insets(3, 0, 3, 10) })
                add(component, GridBagConstraints().apply { gridx = 1; gridy = index; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL; insets = Insets(3, 0, 3, 0) })
            }
            row(0, "Generate:", scope)
            row(1, "Test package:", packageMode)
            row(2, "", packageDescription)
            row(3, "Destination file (optional):", destination)
            row(4, "", defaultDestination)
        }, BorderLayout.NORTH)
        cards.add(JPanel(BorderLayout()).apply {
            add(JBScrollPane(table), BorderLayout.CENTER)
            add(JBLabel("Select declarations and supply concrete types for generic functions or receivers. Existing tests are not selected by default."), BorderLayout.SOUTH)
        }, "declarations")
        cards.add(JPanel(BorderLayout(10, 0)).apply {
            add(JBLabel("Test name:"), BorderLayout.WEST)
            add(emptyName, BorderLayout.CENTER)
        }, "empty")
        add(cards, BorderLayout.CENTER)
        add(JPanel(BorderLayout(10, 5)).apply {
            add(JBLabel("An empty destination uses each source file's _test.go and retains its OS/architecture suffix."), BorderLayout.NORTH)
            add(status, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.RIGHT)).apply { add(retry) }, BorderLayout.SOUTH)
        }, BorderLayout.SOUTH)
        table.columnModel.getColumn(0).preferredWidth = 65
        table.columnModel.getColumn(1).preferredWidth = 400
        table.columnModel.getColumn(2).preferredWidth = 210
        table.columnModel.getColumn(3).preferredWidth = 230
        table.columnModel.getColumn(4).preferredWidth = 90
    }

    private fun scopeValue() = (scope.selectedItem as GenerationScope).wire
    private fun modeValue() = (packageMode.selectedItem as GenerationPackage).wire
    private fun packagePrivate(row: GoGenerateTestsRow) = displayedMode == "external" && row.symbol.get("exported")?.asBoolean != true
    private fun finishEditing(): Boolean = !table.isEditing || table.cellEditor.stopCellEditing()

    private fun rememberRows() {
        tableModel.rows.forEach { row ->
            rowDrafts[displayedMode + ":" + row.symbolId] = JsonObject().apply {
                addProperty("packageMode", displayedMode); addProperty("symbolId", row.symbolId)
                addProperty("selected", row.selected); addProperty("testName", row.testName); addProperty("typeArgumentsText", row.typeArgumentsText)
            }
        }
    }

    private fun requestInspection() {
        if (!finishEditing() || isDisposed) return
        rememberRows()
        val requestedScope = scopeValue()
        val requestedMode = modeValue()
        val ticket = ++sequence
        loading = true
        failure = null
        updateState()
        fun deliver(action: () -> Unit) {
            val runnable = Runnable { if (!isDisposed && ticket == sequence) action() }
            val application = ApplicationManager.getApplication()
            // This callback only updates this dialog's draft/widgets. A request may start
            // before showAndGet enters modality (for example when restoring another scope).
            if (application.isDispatchThread) runnable.run() else application.invokeLater(runnable, ModalityState.any())
        }
        try {
            inspect(requestedScope, requestedMode, { response ->
                deliver {
                    loading = false
                    if (response.genString("scope") != requestedScope || response.genString("packageMode") != requestedMode) {
                        failure = "Inspection returned a different generation scope. Refresh to try again."
                        updateState()
                    } else applyInspection(response)
                }
            }, { message -> deliver { loading = false; failure = message.ifBlank { "Test generation inspection failed. Refresh to try again." }; updateState() } })
        } catch (error: Exception) {
            deliver { loading = false; failure = error.message ?: "Test generation inspection failed."; updateState() }
        }
    }

    private fun applyInspection(response: JsonObject) {
        inspection = response.deepCopy()
        displayedMode = inspection.genString("packageMode")
        failure = null
        tableModel.replace(inspection.getAsJsonArray("symbols")?.map {
            val symbol = it.asJsonObject.deepCopy()
            val saved = rowDrafts[displayedMode + ":" + symbol.genString("symbolId")]
            val privateInExternal = displayedMode == "external" && symbol.get("exported")?.asBoolean != true
            GoGenerateTestsRow(symbol, !privateInExternal && (saved?.get("selected")?.asBoolean ?: (symbol.get("existingTest")?.asBoolean != true)),
                saved?.genString("testName") ?: symbol.genString("suggestedTestName"), saved?.genString("typeArgumentsText").orEmpty())
        }.orEmpty())
        val name = inspection.genString("packageName") + if (displayedMode == "external") "_test" else ""
        packageDescription.text = "Package: $name   ·   ${inspection.genString("packagePath")}"
        defaultDestination.text = "Default: " + displayPath(inspection.genString("defaultDestinationUri"))
        updateState()
    }

    private fun updateState() {
        (cards.layout as CardLayout).show(cards, if (scopeValue() == "empty") "empty" else "declarations")
        table.isEnabled = !loading && failure == null
        emptyName.isEnabled = !loading
        retry.isEnabled = !loading
        val conflicts = inspection.getAsJsonArray("conflicts")?.map { it.asJsonObject.genString("message") }.orEmpty()
        status.text = when {
            loading -> "Inspecting declarations…"
            failure != null -> failure
            conflicts.isNotEmpty() -> conflicts.joinToString("\n")
            scopeValue() != "empty" && tableModel.rows.isEmpty() -> "No declarations are available in this scope. Choose another scope or an empty test."
            else -> "Preview checks concrete Go types, destination conflicts and the current workspace before creating or changing files."
        }
        status.foreground = if (!loading && (failure != null || conflicts.isNotEmpty())) JBColor.RED else JBColor.foreground()
        isOKActionEnabled = !loading && failure == null && conflicts.isEmpty()
    }

    private fun destinationUri(): String {
        val value = destination.text.trim()
        if (value.isEmpty()) return ""
        val original = Path.of(URI(inspection.genString("defaultDestinationUri"))).parent
        val path = if (value.startsWith("file:")) Path.of(URI(value)) else Path.of(value)
        val resolved = (if (path.isAbsolute) path else original.resolve(path)).normalize()
        require(resolved.parent == original && resolved.fileName.toString().endsWith("_test.go")) {
            "Choose a _test.go file in the inspected package directory."
        }
        return GoDocumentSync.fileUri(resolved)
    }

    override fun doValidate(): ValidationInfo? {
        if (loading) return ValidationInfo("Wait for declaration inspection to finish.", scope)
        failure?.let { return ValidationInfo(it, scope) }
        inspection.getAsJsonArray("conflicts")?.firstOrNull()?.let { return ValidationInfo(it.asJsonObject.genString("message"), scope) }
        if (scopeValue() == "empty") {
            if (!validTestName(emptyName.text.trim())) return ValidationInfo("Enter a Go test name beginning with Test; TestMain is reserved.", emptyName)
        } else {
            val selected = tableModel.rows.filter { it.selected }
            if (selected.isEmpty()) return ValidationInfo("Select at least one declaration.", table)
            if (selected.any { !validTestName(it.testName.trim()) }) return ValidationInfo("Enter Go test names beginning with Test; TestMain is reserved.", table)
            if (selected.map { it.testName.trim() }.distinct().size != selected.size) return ValidationInfo("Selected test names must be unique.", table)
            for (row in selected) {
                val arguments = try { splitGoTestTypeArguments(row.typeArgumentsText) } catch (error: IllegalArgumentException) {
                    return ValidationInfo("${row.testName}: ${error.message}", table)
                }
                if (row.needsTypes && arguments.size != row.typeCount)
                    return ValidationInfo("${row.testName} needs ${row.typeCount} concrete type argument(s), separated by top-level commas.", table)
                if (!row.needsTypes && arguments.isNotEmpty()) return ValidationInfo("${row.testName} does not take concrete type arguments.", table)
            }
        }
        try { destinationUri() } catch (error: Exception) { return ValidationInfo(error.message ?: "Enter a valid destination file path.", destination) }
        return null
    }

    override fun doOKAction() { if (finishEditing() && !loading) super.doOKAction() }

    fun selection(): JsonObject {
        check(finishEditing())
        check(doValidate() == null) { "The generation draft is not ready for Preview." }
        return JsonObject().apply {
            addProperty("scope", scopeValue()); addProperty("packageMode", modeValue())
            addProperty("inspectionId", inspection.genString("inspectionId")); addProperty("configurationDigest", inspection.genString("configurationDigest"))
            add("selections", JsonArray().also { selections ->
                if (scopeValue() != "empty") tableModel.rows.filter { it.selected }.forEach { row ->
                    selections.add(JsonObject().apply {
                        addProperty("symbolId", row.symbolId); addProperty("testName", row.testName.trim())
                        add("typeArguments", JsonArray().also { values -> splitGoTestTypeArguments(row.typeArgumentsText).forEach(values::add) })
                    })
                }
            })
            if (scopeValue() == "empty") addProperty("emptyName", emptyName.text.trim())
            destinationUri().takeIf(String::isNotEmpty)?.let { addProperty("destinationUri", it) }
        }
    }

    fun draft(): JsonObject {
        finishEditing()
        rememberRows()
        return JsonObject().apply {
            addProperty("scope", scopeValue()); addProperty("packageMode", modeValue())
            addProperty("emptyName", emptyName.text); addProperty("destinationPath", destination.text)
            add("rows", JsonArray().also { rows -> rowDrafts.values.forEach { rows.add(it.deepCopy()) } })
        }
    }

    private fun validTestName(name: String) = GoIdentifiers.valid(name) && GoSourceFunctions.isTestName(name) && name != "TestMain"
    private fun displayPath(uri: String) = runCatching { Path.of(URI(uri)).toString() }.getOrDefault(uri)
}
