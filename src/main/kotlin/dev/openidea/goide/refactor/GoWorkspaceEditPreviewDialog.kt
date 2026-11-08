package dev.openidea.goide.refactor

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSplitPane

internal class GoWorkspaceEditPreviewDialog(project: Project, title: String, entries: List<GoEditPreview>) : DialogWrapper(project) {
    private val items = entries
    private val list = JBList(entries)
    private val diff = DiffManager.getInstance().createRequestPanel(project, disposable, null)
    init {
        this.title = title
        setOKButtonText("Apply")
        setCancelButtonText("Cancel")
        init()
        list.addListSelectionListener { if (!it.valueIsAdjusting) showSelected() }
        if (items.isNotEmpty()) list.selectedIndex = 0
    }
    override fun createCenterPanel(): JComponent = JPanel(BorderLayout()).apply {
        preferredSize = Dimension(1000, 650)
        add(JSplitPane(JSplitPane.HORIZONTAL_SPLIT, JBScrollPane(list), diff.component).apply { resizeWeight = .3 }, BorderLayout.CENTER)
    }
    private fun showSelected() {
        val item = list.selectedValue ?: return
        val factory = DiffContentFactory.getInstance()
        diff.setRequest(SimpleDiffRequest(item.toString(), factory.create(item.before), factory.create(item.after), "Before", "After"))
    }
}
