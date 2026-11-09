@file:OptIn(com.intellij.openapi.util.IntellijInternalApi::class)

package dev.openidea.goide.product

import com.intellij.ide.IdeBundle
import com.intellij.ide.ui.laf.IntelliJLaf
import com.intellij.ide.plugins.InstallFromDiskAction
import com.intellij.ide.plugins.PluginManagerConfigurable
import com.intellij.ide.plugins.PluginManagerConfigurablePanel
import com.intellij.ide.plugins.newui.PluginUpdatesService
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.testFramework.LightPlatformTestCase
import com.intellij.util.ui.UIUtil
import java.awt.Component
import java.awt.Container
import java.util.concurrent.atomic.AtomicBoolean
import java.lang.reflect.Proxy
import javax.swing.JLabel
import javax.swing.LookAndFeel
import javax.swing.UIManager

/** Real OSS plugin configurable/Installed page. No browser, remote peer or UI replacement. */
class GoLocalPluginsManagerTest : LightPlatformTestCase() {
    private lateinit var previousLaf: LookAndFeel

    override fun setUp() {
        super.setUp()
        previousLaf = UIManager.getLookAndFeel()
        UIManager.setLookAndFeel(IntelliJLaf())
    }

    override fun tearDown() {
        try {
            UIManager.setLookAndFeel(previousLaf)
        } finally {
            super.tearDown()
        }
    }

    fun testNativeConfigurableContainsInstalledAndNeverConstructsMarketplace() = configurable { configurable, panel ->
        assertTrue(configurable.isInstalledTabShowing)
        assertFalse(configurable.isMarketplaceTabShowing)
        assertNull(field(panel, "myMarketplaceTab"))
        val labels = descendants(configurable.topComponent).filterIsInstance<JLabel>().mapNotNull { it.text }
        assertTrue("Native Installed header missing: $labels", labels.contains(IdeBundle.message("plugin.manager.tab.installed")))
        assertFalse("Marketplace header remains: $labels", labels.contains(IdeBundle.message("plugin.manager.tab.marketplace")))
    }

    fun testMarketplaceAndTagSearchEntryPointsStayOnInstalled() = configurable { configurable, panel ->
        configurable.openMarketplaceTab("tag:local-test")
        assertTrue(configurable.isInstalledTabShowing)
        assertFalse(configurable.isMarketplaceTabShowing)
        configurable.enableSearch("tag:local-test")?.run()
        assertTrue(configurable.isInstalledTabShowing)
        assertNull(field(panel, "myMarketplaceTab"))
    }

    fun testPreInitializationMarketplaceRequestCannotCreateRemoteTab() {
        val panel = PluginManagerConfigurablePanel()
        try {
            panel.openMarketplaceTab("tag:local-test")
            panel.init(null)
            assertTrue(panel.isInstalledTabShowing)
            assertFalse(panel.isMarketplaceTabShowing)
            assertNull(field(panel, "myMarketplaceTab"))
        } finally {
            panel.dispose()
            UIUtil.dispatchAllInvocationEvents()
        }
    }

    fun testUpdatesStayDetachedAndDisposalDropsTheirQueuedDelivery() = configurable { _, panel ->
        val local = field(panel, "myPluginUpdatesService") as PluginUpdatesService
        val services = PluginUpdatesService::class.java.getDeclaredField("SERVICES").apply { isAccessible = true }.get(null) as List<*>
        assertFalse("The local page subscribed to shared remote updates", services.contains(local))
        val delivered = AtomicBoolean()
        local.calculateUpdates { updates ->
            assertNotNull(updates)
            assertTrue(updates.isEmpty())
            delivered.set(true)
        }
        UIUtil.dispatchAllInvocationEvents()
        assertTrue("Installed's local update callback must complete", delivered.get())
        val afterDispose = AtomicBoolean()
        local.calculateUpdates { afterDispose.set(true) }
        local.dispose()
        UIUtil.dispatchAllInvocationEvents()
        assertFalse("Disposed local page delivered a queued update", afterDispose.get())
        assertFalse(services.contains(local))
    }

    fun testLocalDiskInstallCallbackAndNativeGearRemainAvailable() = configurable { configurable, panel ->
        val actions = PluginManagerConfigurablePanel::class.java.getDeclaredMethod("createGearActions").apply { isAccessible = true }
            .invoke(panel) as DefaultActionGroup
        val children = flatten(actions)
        assertTrue("Native disk installer is missing", children.any { it is InstallFromDiskAction })
        assertFalse(children.any { it.javaClass.simpleName == "ManagePluginRepositoriesAction" })
        assertFalse(children.any { it.javaClass.simpleName == "UpdatePluginsAutomaticallyToggleAction" })
        var callback: Any? = null
        val sink = Proxy.newProxyInstance(DataSink::class.java.classLoader, arrayOf(DataSink::class.java)) { _, method, args ->
            assertEquals("set", method.name)
            if (args!![0] == PluginManagerConfigurable.PLUGIN_INSTALL_CALLBACK_DATA_KEY) callback = args[1]
            null
        } as DataSink
        (configurable.topComponent as UiDataProvider).uiDataSnapshot(sink)
        assertNotNull("Native disk-install callback was removed", callback)
    }

    fun testOriginalNativeApplyResetCancelAndDisposalRemainUsable() = configurable { configurable, panel ->
        assertFalse(configurable.isModified)
        configurable.reset()
        configurable.apply()
        configurable.cancel()
        assertFalse(configurable.isModified)
        assertNull(field(panel, "myMarketplaceTab"))
    }

    private fun configurable(body: (PluginManagerConfigurable, PluginManagerConfigurablePanel) -> Unit) {
        assertEquals("true", System.getProperty("opengoland.plugins.local.only"))
        val configurable = PluginManagerConfigurable()
        try {
            assertNotNull(configurable.createComponent())
            UIUtil.dispatchAllInvocationEvents()
            body(configurable, panel(configurable))
        } finally {
            configurable.disposeUIResources()
            UIUtil.dispatchAllInvocationEvents()
        }
    }

    private fun panel(configurable: PluginManagerConfigurable) = field(configurable, "myPanel") as PluginManagerConfigurablePanel
    private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
    private fun descendants(root: Component): List<Component> = listOf(root) + if (root is Container) root.components.flatMap(::descendants) else emptyList()
    private fun flatten(group: ActionGroup): List<AnAction> = group.getChildren(null).flatMap { listOf(it) + if (it is ActionGroup) flatten(it) else emptyList() }
}
