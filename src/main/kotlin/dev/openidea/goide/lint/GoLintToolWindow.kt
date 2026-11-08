package dev.openidea.goide.lint

import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import dev.openidea.goide.settings.GoLintConfigurable
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Path
import javax.swing.AbstractAction
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.KeyStroke
import javax.swing.table.AbstractTableModel

class GoLintToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val service = GoLintService.get(project)
        val tools = JComboBox(GoInspectionTool.entries.toTypedArray())
        val scopes = JComboBox<Any>()
        val run = JButton("Run")
        val cancel = JButton("Cancel")
        val refresh = JButton("Reload Modules")
        val settings = JButton("Settings…")
        val summary = JBLabel()
        val model = InspectionTableModel()
        val table = object : JBTable(model) {
            override fun getToolTipText(event: MouseEvent): String? {
                val viewRow = rowAtPoint(event.point)
                return if (viewRow < 0) null else model.issueAt(convertRowIndexToModel(viewRow))?.let {
                    "${it.message} — ${it.localPath ?: "Source not mapped: ${it.filename}"}"
                }
            }
        }.apply {
            emptyText.text = "Run Go Vet or golangci-lint to collect diagnostics."
            autoCreateRowSorter = true
            setShowGrid(false)
        }
        val console = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(tools); add(JBLabel("Scope:")); add(scopes); add(run); add(cancel); add(refresh); add(settings)
        }
        val panel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4)
            add(toolbar, BorderLayout.NORTH)
            add(JSplitPane(JSplitPane.VERTICAL_SPLIT, JBScrollPane(table), console.component).apply { resizeWeight = .65 }, BorderLayout.CENTER)
            add(summary, BorderLayout.SOUTH)
        }
        var updating = false
        var currentModules: List<GoInspectionModule>? = null
        var lastSequence = 0L
        var wasBusy = false
        val allModules = "All workspace modules"
        val subscription = service.listen { state ->
            updating = true
            if (state.busy && !wasBusy) console.clear()
            wasBusy = state.busy
            if (currentModules != state.modules) {
                currentModules = state.modules
                scopes.removeAllItems(); scopes.addItem(allModules)
                state.modules.forEach(scopes::addItem)
            }
            tools.selectedItem = state.tool
            scopes.selectedItem = state.modules.firstOrNull { it.directory == state.selectedDirectory } ?: allModules
            tools.isEnabled = !state.busy
            scopes.isEnabled = !state.busy && !state.refreshing
            run.isEnabled = state.modules.isNotEmpty() && !state.busy && !state.refreshing
            cancel.isEnabled = state.busy
            refresh.isEnabled = !state.busy && !state.refreshing
            settings.isEnabled = !state.busy
            summary.text = if (state.refreshing) "Reading Go workspace modules…" else state.message
            model.update(state.issues)
            state.log.filter { it.sequence > lastSequence }.forEach { entry ->
                console.print(entry.text, if (entry.error) ConsoleViewContentType.ERROR_OUTPUT else ConsoleViewContentType.NORMAL_OUTPUT)
                lastSequence = entry.sequence
            }
            updating = false
        }
        tools.addActionListener { if (!updating) (tools.selectedItem as? GoInspectionTool)?.let(service::selectTool) }
        scopes.addActionListener { if (!updating) service.selectModule((scopes.selectedItem as? GoInspectionModule)?.directory) }
        run.addActionListener { service.run() }
        cancel.addActionListener { service.cancel() }
        refresh.addActionListener { service.refreshModules() }
        settings.addActionListener { ShowSettingsUtil.getInstance().editConfigurable(project, GoLintConfigurable(project)) }
        fun navigate() {
            val row = table.selectedRow.takeIf { it >= 0 }?.let(table::convertRowIndexToModel) ?: return
            val issue = model.issueAt(row) ?: return
            GoInspectionNavigator.navigate(project, issue)
        }
        table.addMouseListener(object : MouseAdapter() { override fun mouseClicked(event: MouseEvent) { if (event.clickCount == 2) navigate() } })
        table.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "go.inspection.navigate")
        table.actionMap.put("go.inspection.navigate", object : AbstractAction() { override fun actionPerformed(event: ActionEvent) = navigate() })
        val content = ContentFactory.getInstance().createContent(panel, "Diagnostics", false)
        content.setDisposer { subscription.close(); console.dispose() }
        toolWindow.contentManager.addContent(content)
        service.refreshModules()
    }
}

object GoInspectionNavigator {
    fun navigate(project: Project, issue: GoInspectionIssue) {
        val path = issue.localPath ?: return
        if (issue.line <= 0) return
        ApplicationManager.getApplication().executeOnPooledThread {
            val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(Path.of(path)) ?: return@executeOnPooledThread
            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed) WriteIntentReadAction.run {
                    val document = FileDocumentManager.getInstance().getDocument(file)
                    val line = (issue.line - 1).coerceAtLeast(0).coerceAtMost((document?.lineCount ?: issue.line) - 1)
                    val column = if (document == null) 0 else {
                        val text = document.charsSequence.subSequence(document.getLineStartOffset(line), document.getLineEndOffset(line)).toString()
                        GoInspectionLocations.utf16Column(text, issue.byteColumn)
                    }
                    OpenFileDescriptor(project, file, line, column).navigate(true)
                }
            }
        }
    }
}

private class InspectionTableModel : AbstractTableModel() {
    private var issues: List<GoInspectionIssue> = emptyList()
    private val columns = arrayOf("Severity", "Linter", "Message", "File", "Line", "Column", "Module")
    fun update(value: List<GoInspectionIssue>) { if (issues !== value) { issues = value; fireTableDataChanged() } }
    fun issueAt(row: Int) = issues.getOrNull(row)
    override fun getRowCount() = issues.size
    override fun getColumnCount() = columns.size
    override fun getColumnName(column: Int) = columns[column]
    override fun getColumnClass(column: Int): Class<*> = if (column in 4..5) Int::class.javaObjectType else String::class.java
    override fun getValueAt(row: Int, column: Int): Any = issueAt(row)?.let {
        when (column) {
            0 -> it.severity
            1 -> it.linter
            2 -> it.message
            3 -> it.localPath ?: "${it.filename} (not mapped)"
            4 -> it.line
            5 -> it.byteColumn
            else -> it.moduleDirectory
        }
    } ?: ""
}

private fun showInspections(project: Project, action: () -> Unit) {
    val window = ToolWindowManager.getInstance(project).getToolWindow("Go Inspections")
    if (window == null) action() else window.activate(Runnable(action))
}

class RunGoVetInspectionAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(event: AnActionEvent) { event.presentation.isEnabledAndVisible = event.project != null }
    override fun actionPerformed(event: AnActionEvent) { event.project?.let { project -> showInspections(project) { GoLintService.get(project).run(GoInspectionTool.VET) } } }
}

class RunGolangciInspectionAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(event: AnActionEvent) { event.presentation.isEnabledAndVisible = event.project != null }
    override fun actionPerformed(event: AnActionEvent) { event.project?.let { project -> showInspections(project) { GoLintService.get(project).run(GoInspectionTool.GOLANGCI) } } }
}
