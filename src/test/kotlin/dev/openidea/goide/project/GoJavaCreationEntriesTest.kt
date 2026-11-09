package dev.openidea.goide.project

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.Constraints
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ex.ActionManagerEx
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.testFramework.EdtTestUtil
import com.intellij.testFramework.LightPlatformTestCase

/** Actual SDK action registration and group membership, without opening any project wizard. */
class GoJavaCreationEntriesTest : LightPlatformTestCase() {
    override fun runInDispatchThread() = false

    fun testAutomaticCustomizationRemovesOnlyCanonicalJavaCreationIds() {
        val manager = ActionManager.getInstance()
        onEdt {
            for (id in JAVA_IDS) assertNull("The canonical Java creation action $id must not remain in the Go product", manager.getAction(id))
            for (id in PRESERVED_IDS) assertNotNull("Open, Clone and Go entry $id must remain", manager.getAction(id))
            for (id in GoNewProjectActionCustomizer.HOST_ACTION_IDS) assertTrue(manager.getAction(id) is GoNewProjectAction)
        }
    }

    fun testUnregisterRemovesEveryCanonicalGroupReferenceAndRetainsOtherActions() {
        val manager = ActionManager.getInstance() as ActionManagerEx
        onEdt {
            val registrar = manager.asActionRuntimeRegistrar()
            val originalGoHosts = GoNewProjectActionCustomizer.HOST_ACTION_IDS.associateWith { requireNotNull(manager.getAction(it)) }
            val preserved = PRESERVED_IDS.associateWith { requireNotNull(manager.getAction(it)) }
            val groups = JAVA_GROUP_IDS.associateWith { requireNotNull(manager.getAction(it) as? DefaultActionGroup) }
            // Capture actual platform Java creators in the RED lane; the repaired
            // automatic startup has already removed them, so reintroduce owned
            // sentinels to exercise the same public registration/group contract.
            val previous = JAVA_IDS.associateWith { manager.getAction(it) }
            val added = JAVA_IDS.associateWith { object : DumbAwareAction("Owned $it") {
                override fun actionPerformed(event: com.intellij.openapi.actionSystem.AnActionEvent) = Unit
            } }
            try {
                for ((id, action) in added) {
                    if (manager.getAction(id) != null) registrar.unregisterAction(id)
                    registrar.registerAction(id, action)
                    for (group in groups.values) registrar.addToGroup(group, action, Constraints.LAST)
                }
                for (group in groups.values) assertTrue(added.values.all { candidate -> group.getChildren(null).any { it === candidate } })
                val expected = groups.mapValues { (_, group) -> group.getChildren(null).filterNot { it in added.values } }
                GoNewProjectActionCustomizer().install(registrar)
                for (id in JAVA_IDS) assertNull("Unregistration must remove the ID $id", manager.getAction(id))
                for ((id, group) in groups) {
                    val children = group.getChildren(null).toList()
                    assertFalse("Unregistration must remove all old action objects from $id", children.any { it in added.values })
                    sameActions(expected.getValue(id), children)
                }
                for ((id, action) in preserved) assertSame("Unrelated action $id was replaced", action, manager.getAction(id))
            } finally {
                for (id in JAVA_IDS) if (manager.getAction(id) != null) registrar.unregisterAction(id)
                // The GREEN lane restores no Java creators. A baseline failure
                // restores its original real actions without leaking sentinels.
                for ((id, action) in previous) if (action != null) registrar.registerAction(id, action)
                for ((id, action) in originalGoHosts) if (manager.getAction(id) !== action) registrar.replaceAction(id, action)
            }
        }
    }

    fun testRepeatedCustomizationPreservesGoOpenCloneObjectsAndMenuReferences() {
        val manager = ActionManager.getInstance() as ActionManagerEx
        onEdt {
            val objects = PRESERVED_IDS.associateWith { requireNotNull(manager.getAction(it)) }
            val groups = (JAVA_GROUP_IDS + listOf("NewProjectOrModuleGroup", "WelcomeScreen.DefaultNewProjectActionGroup"))
                .associateWith { requireNotNull(manager.getAction(it) as? ActionGroup) }
            val children = groups.mapValues { (_, group) -> group.getChildren(null).toList() }
            val registrar = manager.asActionRuntimeRegistrar()
            try {
                repeat(2) { GoNewProjectActionCustomizer().install(registrar) }
                for ((id, action) in objects) assertSame("Repeated customization replaced $id", action, manager.getAction(id))
                for ((id, group) in groups) sameActions(children.getValue(id), group.getChildren(null).toList())
            } finally {
                for ((id, action) in objects) if (manager.getAction(id) !== action) registrar.replaceAction(id, action)
            }
        }
    }

    private fun sameActions(expected: List<AnAction>, actual: List<AnAction>) {
        assertEquals("Unrelated group children must keep their order and identity", expected.size, actual.size)
        expected.zip(actual).forEach { (before, after) -> assertSame(before, after) }
    }
    private fun onEdt(action: () -> Unit) = EdtTestUtil.runInEdtAndWait<RuntimeException> { action() }

    companion object {
        // Independent assertions must not derive their expected removal list
        // from the implementation's constant.
        private val JAVA_IDS = listOf("NewProject", "ImportProject", "NewModule", "ImportModule", "NewModuleInGroup")
        private val JAVA_GROUP_IDS = listOf("JavaNewProjectGroup", "JavaNewProjectOrModuleGroup", "NewGroup1")
        private val PRESERVED_IDS = listOf("WelcomeScreen.CreateNewProject", "WelcomeScreen.DefaultNewProjectAction", "OpenIDEA.NewGoModule",
            "OpenFile", "WelcomeScreen.OpenProject", "ProjectFromVersionControl", "Vcs.VcsClone")
    }
}
