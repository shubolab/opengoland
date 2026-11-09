package dev.openidea.goide.project

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.Constraints
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ex.ActionRuntimeRegistrar
import com.intellij.openapi.actionSystem.impl.ActionConfigurationCustomizer
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.impl.welcomeScreen.NewWelcomeScreen
import dev.openidea.goide.settings.GoProjectSettings

/** The standard Welcome and File > New Project hosts share this native wizard. */
class GoNewProjectAction : DumbAwareAction("New Project…") {
    init { templatePresentation.isApplicationScope = true }
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(event: AnActionEvent) { NewWelcomeScreen.updateNewProjectIconIfWelcomeScreen(event) }
    override fun actionPerformed(event: AnActionEvent) {
        val settings = GoProjectSettings.get(event.project ?: ProjectManager.getInstance().defaultProject)
        val executable = settings.goExecutable
        val environment = settings.environment
        GoNewProjectDialog().apply { initialize(event.project, executable, environment) }.show()
    }
}

/** Standard host IDs vary across platform distributions; replace existing actions and register missing ones. */
class GoNewProjectActionCustomizer : ActionConfigurationCustomizer, ActionConfigurationCustomizer.LightCustomizeStrategy {
    override suspend fun customize(actionRegistrar: ActionRuntimeRegistrar) {
        install(actionRegistrar)
    }

    internal fun install(registrar: ActionRuntimeRegistrar) {
        for (id in HOST_ACTION_IDS) {
            val action = GoNewProjectAction()
            if (registrar.getActionOrStub(id) != null) registrar.replaceAction(id, action)
            else {
                registrar.registerAction(id, action)
                if (id == FILE_ACTION_ID) {
                    (registrar.getActionOrStub("NewProjectOrModuleGroup") as? DefaultActionGroup)?.let {
                        registrar.addToGroup(it, action, Constraints.FIRST)
                    }
                }
            }
        }
    }

    companion object {
        internal const val WELCOME_ACTION_ID = "WelcomeScreen.CreateNewProject"
        internal const val FILE_ACTION_ID = "WelcomeScreen.DefaultNewProjectAction"
        internal val HOST_ACTION_IDS = listOf(WELCOME_ACTION_ID, FILE_ACTION_ID)
    }
}
