package dev.openidea.goide.refactor

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSplitPane

internal class GoWorkspaceEditPreviewDialog(project: Project, title: String, entries: List<GoEditPreview>, usages: List<GoEditUsage> = emptyList()) : DialogWrapper(project) {
    private val items = entries
    private val list = JBList(entries)
    private val diff = DiffManager.getInstance().createRequestPanel(project, disposable, null)
    private val usageList = JBList(usages)
    init {
        this.title = title
        setOKButtonText("Apply")
        setCancelButtonText("Cancel")
        init()
        list.addListSelectionListener { if (!it.valueIsAdjusting) showSelected() }
        usageList.addListSelectionListener {
            if (!it.valueIsAdjusting) usageList.selectedValue?.let { usage ->
                val path = runCatching { GoWorkspaceEditPlan.path(usage.uri) }.getOrNull()
                val index = items.indexOfFirst { it.path == path }
                if (index >= 0) list.selectedIndex = index
            }
        }
        if (items.isNotEmpty()) list.selectedIndex = 0
    }
    override fun createCenterPanel(): JComponent = JPanel(BorderLayout()).apply {
        preferredSize = Dimension(1000, 650)
        val navigation = JBTabbedPane().apply {
            addTab("Files (${items.size})", JBScrollPane(list))
            if (usageList.model.size > 0) addTab("Usages (${usageList.model.size})", JBScrollPane(usageList))
        }
        add(JSplitPane(JSplitPane.HORIZONTAL_SPLIT, navigation, diff.component).apply { resizeWeight = .3 }, BorderLayout.CENTER)
    }
    private fun showSelected() {
        val item = list.selectedValue ?: return
        val factory = DiffContentFactory.getInstance()
        val type = FileTypeManager.getInstance().getFileTypeByFileName(item.path.fileName.toString())
        diff.setRequest(SimpleDiffRequest(item.toString(), factory.create(item.before, type), factory.create(item.after, type), "Before", "After").apply {
            putUserData(DiffUserDataKeys.FORCE_READ_ONLY, true)
        })
    }
}
