package dev.openidea.goide.plugins

import com.intellij.ide.plugins.InstallFromDiskAction
import com.intellij.ide.plugins.PluginEnabler
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile

class InstallChineseLanguagePackAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project
        val application = ApplicationManager.getApplication()
        application.executeOnPooledThread {
            try {
                val source = ChinesePackSource.load()
                val build = ApplicationInfo.getInstance().build.asStringWithoutProductCode()
                application.invokeLater({
                    if (!application.isDisposed && project?.isDisposed != true)
                        ChineseLanguagePackDialog(project, source, build).show()
                }, ModalityState.any())
            } catch (error: Exception) {
                application.invokeLater({
                    if (!application.isDisposed && project?.isDisposed != true)
                        Messages.showErrorDialog(project, error.message ?: "无法准备语言包安装入口，请重试。", "中文语言包")
                }, ModalityState.any())
            }
        }
    }
}

internal fun interface ChinesePackInstaller {
    fun install(project: Project?, file: VirtualFile, prepared: PreparedChinesePack, source: ChinesePackSource)
}

/**
 * Pinned 261 bridge to an accessible, @Internal native action. With a null model,
 * the action creates/removes its own native plugin-manager session, including
 * when invoked from Help without a Plugins settings page. Its chooser confirms
 * the preselected ZIP and retains native policy/signature/build/dependency checks.
 */
@OptIn(com.intellij.openapi.util.IntellijInternalApi::class)
internal class NativeChinesePackInstaller : ChinesePackInstaller {
    override fun install(project: Project?, file: VirtualFile, prepared: PreparedChinesePack, source: ChinesePackSource) {
        check(ApplicationManager.getApplication().isDispatchThread)
        check(prepared.retainForNativeInstaller()) { "The prepared archive was already discarded." }
        val action = object : InstallFromDiskAction(null, PluginEnabler.HEADLESS, null) {}
        val context = SimpleDataContext.builder().add(CommonDataKeys.VIRTUAL_FILE, file)
            .add(CommonDataKeys.PROJECT, project).build()
        action.actionPerformed(AnActionEvent.createFromAnAction(action, null, ActionPlaces.UNKNOWN, context))
    }
}
