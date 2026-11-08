package dev.openidea.goide.coverage

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
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Path
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.table.AbstractTableModel

class GoCoverageToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val service = GoCoverageService.get(project)
        val modules = JComboBox<GoCoverageModule>()
        val run = JButton("Run Coverage")
        val importButton = JButton("Import Profile…")
        val cancel = JButton("Cancel")
        val refresh = JButton("Reload Modules")
        val show = JBCheckBox("Show in Editor", true)
        val summary = JBLabel()
        val model = CoverageTableModel()
        val table = JBTable(model).apply { emptyText.text = "Run Go coverage or import a coverprofile." }
        val console = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(JBLabel("Module:")); add(modules); add(run); add(importButton); add(cancel); add(refresh); add(show)
        }
        val panel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4)
            add(toolbar, BorderLayout.NORTH)
            add(JSplitPane(JSplitPane.VERTICAL_SPLIT, JBScrollPane(table), console.component).apply { resizeWeight = .65 }, BorderLayout.CENTER)
            add(summary, BorderLayout.SOUTH)
        }
        var updating = false
        var lastSequence = 0L
        var wasBusy = false
        var currentModules: List<GoCoverageModule> = emptyList()
        val listener = service.listen { state ->
            updating = true
            if (state.busy && !wasBusy) console.clear()
            wasBusy = state.busy
            if (currentModules != state.modules) {
                currentModules = state.modules
                modules.removeAllItems()
                state.modules.forEach(modules::addItem)
            }
            modules.selectedItem = state.modules.firstOrNull { it.directory == state.selectedDirectory }
            modules.isEnabled = !state.busy && !state.refreshing
            run.isEnabled = state.modules.isNotEmpty() && !state.busy && !state.refreshing
            importButton.isEnabled = !state.busy
            cancel.isEnabled = state.taskId != null
            refresh.isEnabled = !state.busy && !state.refreshing
            show.isSelected = state.showInEditor
            show.isEnabled = state.report != null
            summary.text = if (state.refreshing) "Reading Go workspace modules…" else state.message
            summary.toolTipText = state.profilePath
            model.update(state.report, state.staleFiles)
            state.log.filter { it.sequence > lastSequence }.forEach { entry ->
                console.print(entry.text, if (entry.error) ConsoleViewContentType.ERROR_OUTPUT else ConsoleViewContentType.NORMAL_OUTPUT)
                lastSequence = entry.sequence
            }
            updating = false
        }
        modules.addActionListener { if (!updating) (modules.selectedItem as? GoCoverageModule)?.let { service.selectModule(it.directory) } }
        run.addActionListener { console.clear(); service.runSelected() }
        importButton.addActionListener { chooseProfile(project) }
        cancel.addActionListener { service.cancel() }
        refresh.addActionListener { service.refreshModules() }
        show.addActionListener { if (!updating) service.showInEditor(show.isSelected) }
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (event.clickCount != 2) return
                val row = table.selectedRow.takeIf { it >= 0 }?.let(table::convertRowIndexToModel) ?: return
                val file = model.fileAt(row) ?: return
                val path = file.localPath ?: return
                ApplicationManager.getApplication().executeOnPooledThread {
                    val virtual = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(Path.of(path)) ?: return@executeOnPooledThread
                    ApplicationManager.getApplication().invokeLater {
                        if (!project.isDisposed) WriteIntentReadAction.run {
                            OpenFileDescriptor(project, virtual, (file.blocks.firstOrNull()?.startLine ?: 1) - 1, 0).navigate(true)
                        }
                    }
                }
            }
        })
        val content = ContentFactory.getInstance().createContent(panel, "Statement Coverage", false)
        content.setDisposer { listener.close(); console.dispose() }
        toolWindow.contentManager.addContent(content)
        service.refreshModules()
    }
}

private class CoverageTableModel : AbstractTableModel() {
    private var report: GoCoverageReport? = null
    private var stale: Set<String> = emptySet()
    private val columns = arrayOf("File", "Covered Statements", "Statements", "Coverage", "Source")
    fun update(value: GoCoverageReport?, staleFiles: Set<String>) {
        if (report === value && stale == staleFiles) return
        report = value; stale = staleFiles; fireTableDataChanged()
    }
    fun fileAt(row: Int): GoCoverageFile? = report?.files?.getOrNull(row)
    override fun getRowCount() = report?.files?.size ?: 0
    override fun getColumnCount() = columns.size
    override fun getColumnName(column: Int) = columns[column]
    override fun getValueAt(row: Int, column: Int): Any {
        val file = fileAt(row) ?: return ""
        return when (column) {
            0 -> file.profilePath
            1 -> file.totals.coveredStatements
            2 -> file.totals.statements
            3 -> GoCoverageService.formatCoverage(file.totals.percent)
            else -> when {
                file.localPath == null -> "Not mapped to this workspace"
                file.localPath.replace('\\', '/') in stale -> "Changed since collection"
                else -> file.localPath
            }
        }
    }
}

private fun chooseProfile(project: Project) {
    val descriptor = FileChooserDescriptorFactory.singleFile().withTitle("Import Go Coverage Profile")
    FileChooser.chooseFile(descriptor, project, null) { file -> GoCoverageService.get(project).importProfile(Path.of(file.path)) }
}

private fun showCoverage(project: Project, action: () -> Unit) {
    val window = ToolWindowManager.getInstance(project).getToolWindow("Go Coverage")
    if (window == null) action() else window.activate(Runnable(action))
}

class RunGoCoverageAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(event: AnActionEvent) { event.presentation.isEnabledAndVisible = event.project != null }
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        showCoverage(project) { GoCoverageService.get(project).runSelected() }
    }
}

class ImportGoCoverageAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(event: AnActionEvent) { event.presentation.isEnabledAndVisible = event.project != null }
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        showCoverage(project) { chooseProfile(project) }
    }
}

class HideGoCoverageAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(event: AnActionEvent) { event.presentation.isEnabledAndVisible = event.project != null }
    override fun actionPerformed(event: AnActionEvent) { event.project?.let { GoCoverageService.get(it).showInEditor(false) } }
}
