package dev.openidea.goide.engine

import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

class GoToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val engine = project.getService(EngineClient::class.java)
        val root = DefaultMutableTreeNode("Go Project")
        val model = DefaultTreeModel(root)
        val tree = Tree(model)
        val console = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
        val reload = JButton("Reload Project")
        val toolbar = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT)).apply { add(reload) }
        val panel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4)
            add(toolbar, BorderLayout.NORTH)
            add(JSplitPane(JSplitPane.VERTICAL_SPLIT, JBScrollPane(tree), console.component).apply { resizeWeight = .6 }, BorderLayout.CENTER)
        }
        fun reloadProject() {
            reload.isEnabled = false
            ApplicationManager.getApplication().executeOnPooledThread {
                engine.request("project/info").whenComplete { info, error ->
                    ApplicationManager.getApplication().invokeLater {
                        if (!project.isDisposed) {
                            reload.isEnabled = true
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
                                    val node = DefaultMutableTreeNode(if (version != null) "$name@$version" else name)
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
        reload.addActionListener { reloadProject() }
        val subscription = engine.listen { method, params ->
            if (method == "engine/log") ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed) console.print("${params.get("text").asString}\n", ConsoleViewContentType.SYSTEM_OUTPUT)
            }
        }
        val content = ContentFactory.getInstance().createContent(panel, "Modules & Toolchain", false)
        content.setDisposer { subscription.close(); console.dispose() }
        toolWindow.contentManager.addContent(content)
        reloadProject()
    }
}
