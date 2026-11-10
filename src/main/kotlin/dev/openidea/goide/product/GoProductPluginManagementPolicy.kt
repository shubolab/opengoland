package dev.openidea.goide.product

import com.intellij.ide.plugins.DefaultPluginManagementPolicy
import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.ide.plugins.PluginManagementPolicy
import com.intellij.openapi.diagnostic.Logger

/**
 * These two components ship together with open Goland. Their public API patches are
 * versioned as part of the product and must not be replaced by an ordinary plugin update.
 * All other plugin decisions, including compatibility and user-plugin updates, stay native.
 */
class GoProductPluginManagementPolicy : PluginManagementPolicy by DefaultPluginManagementPolicy() {
    private val defaults = DefaultPluginManagementPolicy()

    override fun isUpgradeAllowed(localDescriptor: IdeaPluginDescriptor?, remoteDescriptor: IdeaPluginDescriptor?): Boolean =
        replacementAllowed(localDescriptor, remoteDescriptor) && defaults.isUpgradeAllowed(localDescriptor, remoteDescriptor)

    override fun isDowngradeAllowed(localDescriptor: IdeaPluginDescriptor?, remoteDescriptor: IdeaPluginDescriptor?): Boolean =
        replacementAllowed(localDescriptor, remoteDescriptor) && defaults.isDowngradeAllowed(localDescriptor, remoteDescriptor)

    override fun canInstallPlugin(descriptor: IdeaPluginDescriptor?): Boolean =
        replacementAllowed(descriptor) && defaults.canInstallPlugin(descriptor)

    private fun replacementAllowed(vararg descriptors: IdeaPluginDescriptor?): Boolean {
        val fixed = descriptors.firstOrNull { it?.pluginId?.idString in FIXED_COMPONENT_IDS } ?: return true
        LOG.debug("${fixed.name} (${fixed.pluginId}) is an internal component and updates with open Goland. " +
                  "Install a newer open Goland release to update this component.")
        return false
    }

    private companion object {
        val LOG = Logger.getInstance(GoProductPluginManagementPolicy::class.java)
        val FIXED_COMPONENT_IDS = setOf("dev.openidea.go", "com.redhat.devtools.lsp4ij")
    }
}
