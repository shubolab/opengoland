package dev.openidea.goide.engine

import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.toolbarLayout.ToolbarLayoutStrategy
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.openapi.fileEditor.FileDocumentManager
import java.awt.BorderLayout
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

private data class GoModuleTreeNode(val label: String, val directory: String) {
    override fun toString() = label
}

class GoToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val engine = project.getService(EngineClient::class.java)
        val root = DefaultMutableTreeNode("Go Project")
        val model = DefaultTreeModel(root)
        val tree = Tree(model)
        val console = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
        val disposed = AtomicBoolean()
        val generation = AtomicLong()
        val reloading = AtomicBoolean()
        lateinit var toolbar: ActionToolbar
        val panel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4)
            add(JSplitPane(JSplitPane.VERTICAL_SPLIT, JBScrollPane(tree), console.component).apply { resizeWeight = .6 }, BorderLayout.CENTER)
        }
        fun reloadProject() {
            if (disposed.get() || project.isDisposed) return
            val current = generation.incrementAndGet()
            reloading.set(true)
            toolbar.updateActionsAsync()
            ApplicationManager.getApplication().executeOnPooledThread {
                if (disposed.get() || project.isDisposed || generation.get() != current) return@executeOnPooledThread
                engine.request("project/info").whenComplete { info, error ->
                    ApplicationManager.getApplication().invokeLater {
                        if (!disposed.get() && !project.isDisposed && generation.get() == current) {
                            reloading.set(false)
                            toolbar.updateActionsAsync()
                            if (error != null) console.print("${error.cause?.message ?: error.message}\n", ConsoleViewContentType.ERROR_OUTPUT)
                            else {
                                root.removeAllChildren()
                                val data = info.asJsonObject
                                root.userObject = data.get("name")?.asString ?: "Go Project"
                                val environment = data.getAsJsonObject("environment")
                                val sdk = DefaultMutableTreeNode("Go SDK ${environment?.get("GOVERSION")?.asString.orEmpty()}")
                                environment?.get("GOROOT")?.let { sdk.add(DefaultMutableTreeNode(it.asString)) }
                                root.add(sdk)
                                val modules = DefaultMutableTreeNode("Modules")
                                data.getAsJsonArray("modules")?.forEach { value ->
                                    val module = value.asJsonObject
                                    val name = module.get("Path")?.asString ?: "Module"
                                    val version = module.get("Version")?.asString
                                    val label = if (version != null) "$name@$version" else name
                                    val directory = module.get("Dir")?.takeUnless { it.isJsonNull }?.asString
                                    val node = DefaultMutableTreeNode(if (directory != null && module.get("Main")?.asBoolean == true) GoModuleTreeNode(label, directory) else label)
                                    module.get("Dir")?.let { node.add(DefaultMutableTreeNode(it.asString)) }
                                    module.get("Replace")?.asJsonObject?.get("Dir")?.let { node.add(DefaultMutableTreeNode("Local replacement: ${it.asString}")) }
                                    modules.add(node)
                                }
                                root.add(modules)
                                data.get("workspace")?.takeUnless { it.isJsonNull }?.let { root.add(DefaultMutableTreeNode("Workspace: ${it.asString}")) }
                                data.getAsJsonArray("warnings")?.forEach { console.print("${it.asString}\n", ConsoleViewContentType.ERROR_OUTPUT) }
                                model.reload(); tree.expandRow(0)
                                console.print("Go project refreshed.\n", ConsoleViewContentType.SYSTEM_OUTPUT)
                            }
                        }
                    }
                }
            }
        }
        fun textAction(text: String, description: String, enabled: () -> Boolean = { true }, perform: () -> Unit): DumbAwareAction =
            object : DumbAwareAction(text, description, null) {
                init { templatePresentation.putClientProperty(ActionUtil.SHOW_TEXT_IN_TOOLBAR, true) }
                override fun getActionUpdateThread() = ActionUpdateThread.BGT
                override fun update(event: AnActionEvent) {
                    event.presentation.isVisible = !disposed.get() && !project.isDisposed
                    event.presentation.isEnabled = !disposed.get() && !project.isDisposed && enabled()
                }
                override fun actionPerformed(event: AnActionEvent) {
                    if (!disposed.get() && !project.isDisposed && enabled()) perform()
                }
            }
        val actions = DefaultActionGroup()
        actions.add(textAction("Reload Project", "Refresh Go modules and toolchain information", { !reloading.get() }) { reloadProject() })
        listOf("tidy" to "Mod Tidy", "download" to "Download", "vendor" to "Vendor", "workSync" to "Workspace Sync").forEach { (command, label) ->
            actions.add(textAction(label, "Run for the selected Go module, or choose a workspace target") {
                WriteIntentReadAction.run {
                    FileDocumentManager.getInstance().saveAllDocuments()
                    // Read the tree selection, not the overflow popup's focus/data context.
                    val selected = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? GoModuleTreeNode
                    project.getService(GoTaskTargetsService::class.java).invokeAtPath(command, "Go $label", selected?.directory)
                }
            })
        }
        toolbar = ActionManager.getInstance().createActionToolbar("OpenGoland.Go.Modules", actions, true).apply {
            targetComponent = tree
            layoutStrategy = ToolbarLayoutStrategy.AUTOLAYOUT_STRATEGY
            setReservePlaceAutoPopupIcon(true)
        }
        panel.add(toolbar.component, BorderLayout.NORTH)
        val connection = project.messageBus.connect()
        connection.subscribe(GoTaskTargetsService.REFRESH_TOPIC, GoTaskRefreshListener { reloadProject() })
        val subscription = engine.listen { method, params ->
            if (method == "engine/log") ApplicationManager.getApplication().invokeLater {
                if (!disposed.get() && !project.isDisposed) console.print("${params.get("text").asString}\n", ConsoleViewContentType.SYSTEM_OUTPUT)
            }
        }
        val content = ContentFactory.getInstance().createContent(panel, "Modules & Toolchain", false)
        content.setDisposer { disposed.set(true); generation.incrementAndGet(); connection.disconnect(); subscription.close(); console.dispose() }
        toolWindow.contentManager.addContent(content)
        reloadProject()
    }
}
