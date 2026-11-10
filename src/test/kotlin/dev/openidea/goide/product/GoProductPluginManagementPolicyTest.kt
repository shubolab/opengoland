@file:OptIn(com.intellij.openapi.util.IntellijInternalApi::class)

package dev.openidea.goide.product

import com.intellij.ide.plugins.DefaultPluginManagementPolicy
import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.ide.plugins.InstalledPluginsState
import com.intellij.ide.plugins.InstalledPluginsTableModel
import com.intellij.ide.plugins.PluginEnabler
import com.intellij.ide.plugins.PluginInstallCallbackData
import com.intellij.ide.plugins.PluginInstallOperation
import com.intellij.ide.plugins.PluginInstaller
import com.intellij.ide.plugins.PluginManagementPolicy
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.ide.plugins.PluginNode
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.updateSettings.impl.PluginDownloader
import com.intellij.openapi.updateSettings.impl.UpdateChecker
import com.intellij.openapi.updateSettings.impl.UpdateSettings
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.EdtTestUtil
import com.intellij.testFramework.LightPlatformTestCase
import com.intellij.testFramework.replaceService
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Consumer
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.swing.JComponent

/** Real native policy consumers; synthetic candidates never download or install an update. */
class GoProductPluginManagementPolicyTest : LightPlatformTestCase() {
    override fun runInDispatchThread() = false

    fun testRegisteredServiceProtectsOnlyTheTwoFixedProductComponents() {
        val policy = productPolicy()
        val defaults = DefaultPluginManagementPolicy()
        for (id in FIXED_IDS) {
            val installed = requireNotNull(PluginManagerCore.getPlugin(PluginId.getId(id)))
            val newer = candidate(id)
            assertFalse(policy.isUpgradeAllowed(installed, newer))
            assertFalse(policy.isDowngradeAllowed(installed, newer))
            assertFalse(policy.canInstallPlugin(newer))
            assertEquals("Current internal components remain enabled", defaults.canEnablePlugin(installed), policy.canEnablePlugin(installed))
        }
        val ordinary = candidate("example.ordinary-user-plugin")
        assertEquals(defaults.canInstallPlugin(ordinary), policy.canInstallPlugin(ordinary))
        assertTrue(policy.isUpgradeAllowed(ordinary, candidate(ordinary.pluginId.idString)))
    }

    fun testActualUpdateCheckerRejectsProtectedUpgradeThatDefaultPolicyWouldOffer() {
        for (id in FIXED_IDS) {
            val remote = candidate(id)
            val defaultResult = withDefaultPolicy { nativeUpdateCandidates(remote) }
            assertTrue("The unprotected native update gate must offer the compatible newer $id", defaultResult.containsKey(remote.pluginId))
            val result = nativeUpdateCandidates(remote)
            assertFalse("The registered product policy must keep the private API implementation fixed", result.containsKey(remote.pluginId))
        }
    }

    fun testNativeUpdaterRetainsOrdinaryCandidatesAndCoreCompatibilityRejection() {
        val compatible = candidate("example.ordinary-user-plugin")
        assertTrue(nativeUpdateCandidates(compatible).containsKey(compatible.pluginId))
        val old = candidate("example.old-platform-plugin").apply { sinceBuild = "232"; untilBuild = "232.*" }
        assertNotNull(PluginManagerCore.checkBuildNumberCompatibility(old, ApplicationInfo.getInstance().build))
        assertFalse(nativeUpdateCandidates(old).containsKey(old.pluginId))
    }

    fun testNativeRemoteInstallOperationStopsBeforeDownloaderForFixedIds() {
        productPolicy() // In the baseline lane, fail before any artifact/network operation.
        for (id in FIXED_IDS) {
            val operation = PluginInstallOperation(listOf(candidate(id)), emptyList(), PluginEnabler.HEADLESS, EmptyProgressIndicator())
            operation.run()
            assertFalse(operation.isSuccess)
            assertFalse(operation.isRestartRequired)
            assertEmpty(operation.pendingDynamicPluginInstalls)
            assertEmpty(operation.installedDependentPlugins)
        }
    }

    fun testNativeDiskInstallerRejectsFixedArtifactWithoutInvokingInstallCallback() {
        productPolicy() // Keep the baseline lane from installing a real replacement.
        val directory = Files.createTempDirectory("owned-internal-plugin ")
        val artifact = directory.resolve("component.jar")
        val calls = AtomicInteger()
        val warnings = AtomicInteger()
        try {
            ZipOutputStream(Files.newOutputStream(artifact)).use { zip ->
                zip.putNextEntry(ZipEntry("META-INF/plugin.xml"))
                zip.write("<idea-plugin><id>com.redhat.devtools.lsp4ij</id><name>Internal replacement fixture</name><version>99999.0</version><vendor>Owned test fixture</vendor><idea-version since-build=\"261\" until-build=\"261.*\"/></idea-plugin>".toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            edt {
                val settings = UpdateSettings.getInstance()
                val oldAllowed = settings.isThirdPartyPluginsAllowed
                val oldDialog = TestDialogManager.setTestDialog { message ->
                    assertTrue(message, message.contains("Internal replacement fixture"))
                    warnings.incrementAndGet()
                    Messages.OK
                }
                try {
                    // Isolated test-only consent, restored immediately; never written to product profiles.
                    settings.isThirdPartyPluginsAllowed = true
                    val method = PluginInstaller::class.java.getDeclaredMethod("installFromDisk", InstalledPluginsTableModel::class.java,
                        PluginEnabler::class.java, java.nio.file.Path::class.java, com.intellij.openapi.project.Project::class.java,
                        JComponent::class.java, Consumer::class.java).apply { isAccessible = true }
                    method.invoke(null, InstalledPluginsTableModel(project), PluginEnabler.HEADLESS, artifact, project, null,
                        Consumer<PluginInstallCallbackData> { calls.incrementAndGet() })
                } finally {
                    TestDialogManager.setTestDialog(oldDialog)
                    settings.isThirdPartyPluginsAllowed = oldAllowed
                }
            }
            assertEquals("The native disk path must show its policy rejection", 1, warnings.get())
            assertEquals(0, calls.get())
            assertTrue(Files.isRegularFile(artifact))
        } finally {
            Files.deleteIfExists(artifact)
            Files.deleteIfExists(directory)
        }
    }

    fun testDefaultDiskAutoUpdateEnableAndNullDecisionsRemainDelegated() {
        val policy = PluginManagementPolicy.getInstance()
        val defaults = DefaultPluginManagementPolicy()
        assertEquals(defaults.isInstallFromDiskAllowed(), policy.isInstallFromDiskAllowed())
        assertEquals(defaults.isPluginAutoUpdateAllowed(), policy.isPluginAutoUpdateAllowed())
        assertEquals(defaults.canEnablePlugin(null), policy.canEnablePlugin(null))
        assertEquals(defaults.canInstallPlugin(null), policy.canInstallPlugin(null))
        assertEquals(defaults.isUpgradeAllowed(null, null), policy.isUpgradeAllowed(null, null))
        assertEquals(defaults.isDowngradeAllowed(null, null), policy.isDowngradeAllowed(null, null))
    }

    private fun productPolicy(): PluginManagementPolicy = PluginManagementPolicy.getInstance().also {
        assertEquals("The product descriptor must register the policy service", GoProductPluginManagementPolicy::class.java, it.javaClass)
    }

    private fun candidate(id: String) = PluginNode(PluginId.getId(id)).apply {
        name = "Owned compatible candidate $id"
        version = "99999.0"
        sinceBuild = "261"
        untilBuild = "261.*"
        isIncomplete = false
    }

    private fun nativeUpdateCandidates(remote: PluginNode): Map<PluginId, PluginDownloader> {
        val updates = mutableMapOf<PluginId, PluginDownloader>()
        val downloader = PluginDownloader.createDownloader(remote, null, ApplicationInfo.getInstance().build)
        UpdateChecker.checkAndPrepareToInstall(downloader, InstalledPluginsState.getInstance(), updates,
            ApplicationInfo.getInstance().build, EmptyProgressIndicator())
        return updates
    }

    private fun <T> withDefaultPolicy(action: () -> T): T {
        val disposable = Disposer.newDisposable("owned-native-default-policy-control")
        ApplicationManager.getApplication().replaceService(PluginManagementPolicy::class.java, DefaultPluginManagementPolicy(), disposable)
        return try { action() } finally { Disposer.dispose(disposable) }
    }

    private fun edt(action: () -> Unit) = EdtTestUtil.runInEdtAndWait<RuntimeException> { WriteIntentReadAction.run(Runnable(action)) }

    private companion object {
        val FIXED_IDS = listOf("dev.openidea.go", "com.redhat.devtools.lsp4ij")
    }
}
