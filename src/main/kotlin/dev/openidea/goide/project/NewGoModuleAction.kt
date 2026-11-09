package dev.openidea.goide.project

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction

/** Convenience entry into the same native New Project wizard as File and Welcome. */
class NewGoModuleAction : DumbAwareAction() {
    override fun actionPerformed(event: AnActionEvent) = GoNewProjectAction().actionPerformed(event)
}
