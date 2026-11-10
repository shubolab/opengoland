@file:OptIn(com.intellij.openapi.util.IntellijInternalApi::class)

package dev.openidea.goide.product

import com.intellij.ide.IdeBundle
import com.intellij.ide.plugins.InstallFromDiskAction
import com.intellij.ide.plugins.PluginManagerConfigurable
import com.intellij.ide.plugins.PluginManagerConfigurablePanel
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.ide.plugins.PluginManagementPolicy
import com.intellij.ide.plugins.PluginNode
import com.intellij.ide.plugins.marketplace.utils.MarketplaceCustomizationService
import com.intellij.ide.plugins.newui.PluginUpdatesService
import com.intellij.ide.ui.laf.IntelliJLaf
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.util.BuildNumber
import com.intellij.testFramework.EdtTestUtil
import com.intellij.testFramework.LightPlatformTestCase
import com.intellij.testFramework.replaceService
import com.intellij.util.ui.UIUtil
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.awt.Component
import java.awt.Container
import java.lang.reflect.Proxy
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.swing.JLabel
import javax.swing.LookAndFeel
import javax.swing.UIManager

/** Actual upstream configurable and compatibility gate; only HTTP URLs use an owned loopback fixture. */
class GoGeneralPluginsManagerTest : LightPlatformTestCase() {
    private lateinit var previousLaf: LookAndFeel
    private lateinit var http: HttpServer
    private val executor = Executors.newCachedThreadPool { runnable -> Thread(runnable, "owned-plugin-marketplace-http").apply { isDaemon = true } }
    private val requests = ConcurrentLinkedQueue<String>()
    private val nativeSearchRequest = CountDownLatch(1)

    override fun runInDispatchThread() = false

    override fun setUp() {
        super.setUp()
        http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.executor = executor
        http.createContext("/") { exchange ->
            requests += exchange.requestURI.toString()
            if (exchange.requestURI.path == "/api/search/plugins" && exchange.requestURI.rawQuery.orEmpty().contains("max=10000")) nativeSearchRequest.countDown()
            val body = if (exchange.requestURI.path.endsWith("extensions.json") || exchange.requestURI.path.contains("prices")) "{}" else "[]"
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            try {
                exchange.requestBody.close()
                exchange.responseHeaders.set("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } finally { exchange.close() }
        }
        http.start()
        val url = "http://127.0.0.1:${http.address.port}"
        edt {
            previousLaf = UIManager.getLookAndFeel()
            UIManager.setLookAndFeel(IntelliJLaf())
            ApplicationManager.getApplication().replaceService(MarketplaceCustomizationService::class.java, object : MarketplaceCustomizationService {
                override fun getPluginManagerUrl() = url
                override fun getPluginDownloadUrl() = "$url/plugin/download"
                override fun getPluginsListUrl() = "$url/plugins/list"
                override fun getPluginHomepageUrl(pluginId: PluginId) = "$url/plugin/${pluginId.idString}"
            }, testRootDisposable)
        }
    }

    override fun tearDown() {
        try {
            edt { UIManager.setLookAndFeel(previousLaf) }
            http.stop(0)
            executor.shutdownNow()
            assertTrue("Owned loopback HTTP executor did not stop", executor.awaitTermination(5, TimeUnit.SECONDS))
        } finally { super.tearDown() }
    }

    fun testNativeConfigurableConstructsMarketplaceAndInstalled() = configurable { configurable, panel ->
        assertNotNull("The upstream Marketplace model must be constructed", field(panel, "myMarketplaceTab"))
        assertNotNull(field(panel, "myInstalledTab"))
        val labels = descendants(configurable.topComponent).filterIsInstance<JLabel>().mapNotNull { it.text }
        assertTrue("Native Marketplace header missing: $labels", labels.contains(IdeBundle.message("plugin.manager.tab.marketplace")))
        assertTrue("Native Installed header missing: $labels", labels.contains(IdeBundle.message("plugin.manager.tab.installed")))
    }

    fun testStandardMarketplaceAndTagSearchEntriesReachTheNativeMarketplace() {
        configurable(afterUi = {
            assertTrue("The native Marketplace search index did not reach the controlled HTTP endpoint", nativeSearchRequest.await(5, TimeUnit.SECONDS))
            assertTrue("The real IC/261 compatibility build must be sent unchanged: $requests", requests.any {
                it.startsWith("/api/search/plugins?") && it.contains("max=10000") && it.contains("build=IC-261.26222.65")
            })
        }) { configurable, _ ->
            configurable.openMarketplaceTab("tag:ordinary-fixture")
            assertTrue(configurable.isMarketplaceTabShowing)
            assertFalse(configurable.isInstalledTabShowing)
            configurable.enableSearch("tag:ordinary-fixture")?.run()
            assertTrue(configurable.isMarketplaceTabShowing)
        }
        edt {
            val panel = PluginManagerConfigurablePanel()
            try {
                panel.openMarketplaceTab("tag:ordinary-fixture")
                panel.init(null)
                assertTrue("Pre-init requests must retain the native Marketplace destination", panel.isMarketplaceTabShowing)
                assertNotNull(field(panel, "myMarketplaceTab"))
            } finally { panel.dispose(); UIUtil.dispatchAllInvocationEvents() }
        }
    }

    fun testUpstreamUpdateServiceIsSubscribedAndDisposalRemovesIt() {
        var service: PluginUpdatesService? = null
        configurable { _, panel ->
            service = field(panel, "myPluginUpdatesService") as PluginUpdatesService
            assertEquals("The removed local empty-update shim must not survive", PluginUpdatesService::class.java, service!!.javaClass)
            assertTrue("Upstream shared update subscription missing", subscriptions().contains(service))
        }
        edt { assertFalse("Disposed native panel retained its update subscription", subscriptions().contains(service)) }
    }

    fun testRealPlatformCompatibilityRejects232AndAccepts261() {
        val current = BuildNumber.fromString("IC-261.26222.65")!!
        val old = PluginNode(PluginId.getId("example.fixture.old-plugin")).apply {
            name = "Old build fixture"; sinceBuild = "232"; untilBuild = "232.*"
        }
        val compatible = PluginNode(PluginId.getId("example.fixture.compatible-plugin")).apply {
            name = "Compatible build fixture"; sinceBuild = "261"; untilBuild = "261.*"
        }
        assertNotNull("An old plugin must still be rejected by the actual core gate", PluginManagerCore.checkBuildNumberCompatibility(old, current))
        assertNull("A compatible build must pass the original core gate", PluginManagerCore.checkBuildNumberCompatibility(compatible, current))
        assertFalse(PluginManagerCore.isCompatible(old))
        assertTrue(PluginManagerCore.isCompatible(compatible))
    }

    fun testNativeDiskInstallRepositoryProxyAndUpdateGearRemainAvailable() = configurable { configurable, panel ->
        val actions = PluginManagerConfigurablePanel::class.java.getDeclaredMethod("createGearActions").apply { isAccessible = true }.invoke(panel) as DefaultActionGroup
        val children = flatten(actions)
        assertTrue("Native local-disk installer missing", children.any { it is InstallFromDiskAction })
        assertTrue(children.any { it.javaClass.simpleName == "ManagePluginRepositoriesAction" })
        assertTrue(children.any { it.javaClass.simpleName == "OpenHttpProxyConfigurableAction" })
        assertEquals(PluginManagementPolicy.getInstance().isPluginAutoUpdateAllowed(), children.any { it.javaClass.simpleName == "UpdatePluginsAutomaticallyToggleAction" })
        var callback: Any? = null
        val sink = Proxy.newProxyInstance(DataSink::class.java.classLoader, arrayOf(DataSink::class.java)) { _, method, args ->
            assertEquals("set", method.name)
            if (args!![0] == PluginManagerConfigurable.PLUGIN_INSTALL_CALLBACK_DATA_KEY) callback = args[1]
            null
        } as DataSink
        (configurable.topComponent as UiDataProvider).uiDataSnapshot(sink)
        assertNotNull("Actual disk-install callback missing", callback)
    }

    fun testOriginalApplyResetCancelAndDisposalRemainUsableWithBothPages() = configurable { configurable, panel ->
        assertFalse(configurable.isModified)
        configurable.reset()
        configurable.apply()
        configurable.cancel()
        assertFalse(configurable.isModified)
        assertNotNull(field(panel, "myMarketplaceTab"))
        assertNotNull(field(panel, "myInstalledTab"))
    }

    private fun configurable(afterUi: () -> Unit = {}, body: (PluginManagerConfigurable, PluginManagerConfigurablePanel) -> Unit) {
        lateinit var configurable: PluginManagerConfigurable
        var job: Job? = null
        try {
            edt {
                configurable = PluginManagerConfigurable()
                assertNotNull(configurable.createComponent())
                val panel = field(configurable, "myPanel") as PluginManagerConfigurablePanel
                job = (field(panel, "myCoroutineScope") as CoroutineScope).coroutineContext[Job]
                UIUtil.dispatchAllInvocationEvents()
                body(configurable, panel)
            }
            afterUi()
        } finally {
            edt { configurable.disposeUIResources(); UIUtil.dispatchAllInvocationEvents() }
            runBlocking { withTimeout(5000) { job?.join() } }
        }
    }

    private fun subscriptions() = PluginUpdatesService::class.java.getDeclaredField("SERVICES").apply { isAccessible = true }.get(null) as List<*>
    private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
    private fun descendants(root: Component): List<Component> = listOf(root) + if (root is Container) root.components.flatMap(::descendants) else emptyList()
    private fun flatten(group: ActionGroup): List<AnAction> = group.getChildren(null).flatMap { listOf(it) + if (it is ActionGroup) flatten(it) else emptyList() }
    private fun edt(action: () -> Unit) = EdtTestUtil.runInEdtAndWait<RuntimeException> { WriteIntentReadAction.run(Runnable(action)) }
}
