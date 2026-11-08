package dev.openidea.goide.profile

import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Path
import java.util.Locale
import javax.swing.AbstractAction
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.KeyStroke
import javax.swing.RowFilter
import javax.swing.event.DocumentEvent
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableRowSorter

class GoProfileToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val service = GoProfileService.get(project)
        val modules = JComboBox<GoProfileModule>()
        val kind = JComboBox(GoProfileKind.entries.toTypedArray())
        val mode = JComboBox(GoProfileMode.entries.toTypedArray())
        val target = JBTextField(".", 18)
        val pattern = JBTextField("", 18).apply { toolTipText = "Test or benchmark regular expression; empty runs all." }
        val arguments = JBTextField("", 25).apply { toolTipText = "Go test options, for example -race -benchtime=2s. SDK, tags and environment come from Settings > Go." }
        val run = JButton("Generate Profile")
        val cancel = JButton("Cancel")
        val importButton = JButton("Import…")
        val refresh = JButton("Reload Modules")
        val metrics = JComboBox<GoProfileMetric>()
        val sort = JComboBox(arrayOf("Flat hotspots", "Cumulative hotspots"))
        val search = JBTextField("", 25)
        val summary = JBLabel()
        val model = ProfileTableModel()
        val table = JBTable(model).apply { emptyText.text = "Generate a CPU, memory, block or mutex profile, or import pprof." }
        val sorter = TableRowSorter(model)
        table.rowSorter = sorter
        val numberRenderer = object : DefaultTableCellRenderer() {
            override fun setValue(value: Any?) {
                horizontalAlignment = RIGHT
                text = if (value is Long) GoProfileUnits.format(value, model.report?.metric?.unit.orEmpty()) else value?.toString().orEmpty()
            }
        }
        table.columnModel.getColumn(1).cellRenderer = numberRenderer
        table.columnModel.getColumn(2).cellRenderer = numberRenderer
        val percentRenderer = object : DefaultTableCellRenderer() {
            override fun setValue(value: Any?) { horizontalAlignment = RIGHT; text = if (value is Number) String.format(Locale.ROOT, "%.2f%%", value.toDouble()) else "" }
        }
        table.columnModel.getColumn(3).cellRenderer = percentRenderer
        table.columnModel.getColumn(4).cellRenderer = percentRenderer
        val console = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
        val controls = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(JPanel(FlowLayout(FlowLayout.LEFT)).apply { add(JBLabel("Module:")); add(modules); add(kind); add(mode); add(run); add(cancel); add(importButton); add(refresh) })
            add(JPanel(FlowLayout(FlowLayout.LEFT)).apply { add(JBLabel("Package:")); add(target); add(JBLabel("Pattern:")); add(pattern); add(JBLabel("Go test options:")); add(arguments) })
            add(JPanel(FlowLayout(FlowLayout.LEFT)).apply { add(JBLabel("Metric:")); add(metrics); add(sort); add(JBLabel("Filter:")); add(search); add(JBLabel("Enter or double-click opens source")) })
        }
        val panel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4); add(controls, BorderLayout.NORTH)
            add(JSplitPane(JSplitPane.VERTICAL_SPLIT, JBScrollPane(table), console.component).apply { resizeWeight = .7 }, BorderLayout.CENTER)
            add(summary, BorderLayout.SOUTH)
        }
        var updating = false
        var lastSequence = 0L
        var wasBusy = false
        var currentModules: List<GoProfileModule> = emptyList()
        var currentMetrics: List<GoProfileMetric> = emptyList()
        val listener = service.listen { state ->
            updating = true
            if (state.busy && !wasBusy && state.log.isEmpty()) console.clear()
            wasBusy = state.busy
            if (currentModules != state.modules) { currentModules = state.modules; modules.removeAllItems(); state.modules.forEach(modules::addItem) }
            modules.selectedItem = state.modules.firstOrNull { it.directory == state.selectedDirectory }
            val reportMetrics = state.report?.sampleTypes.orEmpty()
            if (currentMetrics != reportMetrics) { currentMetrics = reportMetrics; metrics.removeAllItems(); reportMetrics.forEach(metrics::addItem) }
            metrics.selectedIndex = state.report?.sampleIndex ?: -1
            listOf(modules, kind, mode, target, pattern, arguments, refresh).forEach { it.isEnabled = !state.busy && !state.refreshing }
            run.isEnabled = !state.busy && !state.refreshing && state.modules.isNotEmpty()
            importButton.isEnabled = !state.busy
            cancel.isEnabled = state.taskId != null
            metrics.isEnabled = !state.busy && reportMetrics.isNotEmpty()
            sort.isEnabled = !state.busy && state.report != null
            summary.text = if (state.refreshing) "Reading Go workspace modules…" else state.message
            summary.toolTipText = state.profilePath
            model.update(state.report)
            state.log.filter { it.sequence > lastSequence }.forEach { entry -> console.print(entry.text, if (entry.error) ConsoleViewContentType.ERROR_OUTPUT else ConsoleViewContentType.NORMAL_OUTPUT); lastSequence = entry.sequence }
            updating = false
        }
        modules.addActionListener { if (!updating) (modules.selectedItem as? GoProfileModule)?.let { service.selectModule(it.directory) } }
        metrics.addActionListener { if (!updating && metrics.selectedIndex >= 0) service.selectMetric(metrics.selectedIndex) }
        sort.addActionListener { if (!updating) service.sortByCumulative(sort.selectedIndex == 1) }
        run.addActionListener {
            console.clear()
            service.runSelected(GoProfileOptions(kind.selectedItem as GoProfileKind, mode.selectedItem as GoProfileMode, target.text.trim(), pattern.text, arguments.text))
        }
        cancel.addActionListener { service.cancel() }
        refresh.addActionListener { service.refreshModules() }
        importButton.addActionListener { chooseProfile(project) }
        search.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(event: DocumentEvent) {
                val query = search.text.lowercase(Locale.ROOT)
                sorter.rowFilter = object : RowFilter<ProfileTableModel, Int>() {
                    override fun include(entry: Entry<out ProfileTableModel, out Int>): Boolean = query.isBlank() ||
                        listOf(entry.getStringValue(0), entry.getStringValue(5)).any { it.lowercase(Locale.ROOT).contains(query) }
                }
            }
        })
        fun navigate() {
            val selected = table.selectedRow.takeIf { it >= 0 }?.let(table::convertRowIndexToModel) ?: return
            val row = model.rowAt(selected) ?: return
            val moduleSnapshot = service.state.modules
            ApplicationManager.getApplication().executeOnPooledThread {
                val path = GoProfileSources.resolve(row, moduleSnapshot)
                val file = path?.let { LocalFileSystem.getInstance().refreshAndFindFileByNioFile(it) }
                if (file == null) service.showMessage("Source unavailable: ${row.file ?: row.name}. The recorded source path could not be mapped to this workspace.")
                else ApplicationManager.getApplication().invokeLater {
                    if (!project.isDisposed) WriteIntentReadAction.run { OpenFileDescriptor(project, file, row.line.toInt() - 1, 0).navigate(true) }
                }
            }
        }
        table.addMouseListener(object : MouseAdapter() { override fun mouseClicked(event: MouseEvent) { if (event.clickCount == 2) navigate() } })
        table.inputMap.put(KeyStroke.getKeyStroke("ENTER"), "openProfileSource")
        table.actionMap.put("openProfileSource", object : AbstractAction() { override fun actionPerformed(event: java.awt.event.ActionEvent) = navigate() })
        val content = ContentFactory.getInstance().createContent(panel, "pprof Hotspots", false)
        content.setDisposer { listener.close(); console.dispose() }; toolWindow.contentManager.addContent(content)
        service.refreshModules()
    }
}

private class ProfileTableModel : AbstractTableModel() {
    var report: GoProfileReport? = null; private set
    private val columns = arrayOf("Function", "Flat", "Cumulative", "Flat %", "Cumulative %", "Recorded Source")
    fun update(value: GoProfileReport?) { if (report === value) return; report = value; fireTableDataChanged() }
    fun rowAt(row: Int) = report?.rows?.getOrNull(row)
    override fun getRowCount() = report?.rows?.size ?: 0
    override fun getColumnCount() = columns.size
    override fun getColumnName(column: Int) = columns[column]
    override fun getColumnClass(column: Int): Class<*> = when (column) { 1, 2 -> java.lang.Long::class.java; 3, 4 -> java.lang.Double::class.java; else -> String::class.java }
    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val row = rowAt(rowIndex) ?: return ""
        return when (columnIndex) { 0 -> row.name; 1 -> row.flat; 2 -> row.cumulative; 3 -> row.flatPercent; 4 -> row.cumulativePercent; else -> row.file?.let { "$it:${row.line}" } ?: "No source in profile" }
    }
}

private fun chooseProfile(project: Project) {
    FileChooser.chooseFile(FileChooserDescriptorFactory.singleFile().withTitle("Import Go pprof Profile"), project, null) { file -> GoProfileService.get(project).importProfile(Path.of(file.path)) }
}
class ShowGoProfilesAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(event: AnActionEvent) { event.presentation.isEnabledAndVisible = event.project != null }
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        ToolWindowManager.getInstance(project).getToolWindow("Go Profiles")?.activate(null)
    }
}
class ImportGoProfileAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(event: AnActionEvent) { event.presentation.isEnabledAndVisible = event.project != null }
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val window = ToolWindowManager.getInstance(project).getToolWindow("Go Profiles")
        if (window == null) chooseProfile(project) else window.activate(Runnable { chooseProfile(project) })
    }
}
