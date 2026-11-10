@file:OptIn(com.intellij.openapi.util.IntellijInternalApi::class)

package dev.openidea.goide.product

import com.google.gson.JsonParser
import com.intellij.ide.plugins.marketplace.PluginSearchResult
import com.intellij.ide.plugins.marketplace.utils.MarketplaceCustomizationService
import com.intellij.ide.plugins.newui.PluginUiModel
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.extensions.PluginId
import com.intellij.testFramework.EdtTestUtil
import com.intellij.testFramework.LightPlatformTestCase
import com.intellij.testFramework.replaceService
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.lang.reflect.InvocationTargetException
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.swing.JPanel
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/** Same native executor/controller and parser as the local Marketplace text search. */
class GoCompatibleMarketplaceSearchTest : LightPlatformTestCase() {
    private lateinit var server: HttpServer
    private val requests = ConcurrentLinkedQueue<String>()
    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "owned-compatible-marketplace").apply { isDaemon = true }
    }

    override fun runInDispatchThread() = false

    override fun setUp() {
        super.setUp()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = executor
        server.createContext("/") { exchange ->
            requests += exchange.requestURI.toString()
            // Always return all three records, even for all=false: the actual
            // platform parser must still remove the alternate-host-only result.
            val bytes = THREE_CANDIDATES.toByteArray(Charsets.UTF_8)
            try {
                exchange.requestBody.close()
                exchange.responseHeaders.set("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } finally { exchange.close() }
        }
        server.start()
        val url = "http://127.0.0.1:${server.address.port}"
        ApplicationManager.getApplication().replaceService(MarketplaceCustomizationService::class.java,
            object : MarketplaceCustomizationService {
                override fun getPluginManagerUrl() = url
                override fun getPluginDownloadUrl() = "$url/plugin/download"
                override fun getPluginsListUrl() = "$url/plugins/list"
                override fun getPluginHomepageUrl(pluginId: PluginId) = "$url/plugin/${pluginId.idString}"
            }, testRootDisposable)
    }

    override fun tearDown() {
        try {
            server.stop(0)
            executor.shutdownNow()
            assertTrue("Owned Marketplace HTTP executor did not stop", executor.awaitTermination(5, TimeUnit.SECONDS))
        } finally { super.tearDown() }
    }

    fun testNativeSearchKeepsCompatibleFreeAndPaidWithoutAlternateIdeCandidates() {
        val models = search()
        assertEquals(setOf("owned.compatible.free", "owned.compatible.paid"), models.map { it.pluginId.idString }.toSet())
        assertFalse(models.single { it.pluginId.idString.endsWith("free") }.isPaid)
        assertTrue("Compatible paid plugins keep their own native paid status", models.single { it.pluginId.idString.endsWith("paid") }.isPaid)
        val request = requests.single { it.startsWith("/api/search/plugins?") }
        assertTrue(request, request.contains("all=false"))
        assertTrue("Use the actual platform build; do not spoof a product code: $request", request.contains("build=IC-261.26222.65"))
        assertTrue(request, request.contains("max=10000"))
        assertTrue(request, request.contains("search=owned-fixture"))
        assertTrue(models.all { it.suggestedCommercialIde == null && !it.requiresUpgrade })
    }

    fun testNativeSuggestedIdeBannerIsNotVisibleForAnyReturnedSearchResult() {
        val models = search()
        edt {
            val type = Class.forName("com.intellij.ide.plugins.newui.SuggestedIdeBanner")
            val banner = type.getDeclaredConstructor().apply { isAccessible = true }.newInstance() as JPanel
            val suggest = type.getMethod("suggestIde", String::class.java, PluginId::class.java)
            assertFalse("The allocated native banner starts hidden", banner.isVisible)
            // Positive control for native visibility, without invoking its action.
            suggest.invoke(banner, "IU", PluginId.getId("owned.iu-only"))
            assertTrue("A genuine host-upgrade model would display this native banner", banner.isVisible)
            suggest.invoke(banner, null, null)
            for (model in models) {
                suggest.invoke(banner, model.suggestedCommercialIde, model.pluginId)
                assertFalse("Native search returned a visible host-upgrade banner for ${model.pluginId}", banner.isVisible)
            }
        }
    }

    fun testRuntimeReceiptBindsTheSingleBooleanDeltaAndUnchangedCoroutineAbi() {
        val type = Class.forName("com.intellij.ide.plugins.newui.PluginModelAsyncOperationsExecutor\$performMarketplaceSearch\$2")
        val loader = type.classLoader
        val marker = requireNotNull(loader.getResourceAsStream("META-INF/open-goland-compatible-marketplace.json"))
            .use { JsonParser.parseString(String(it.readAllBytes(), Charsets.UTF_8)).asJsonObject }
        assertEquals("source-correlated-jvm-boolean-delta", marker["productionMode"].asString)
        assertFalse("This patch must not be reported as recompiled Kotlin", marker["recompiled"].asBoolean)
        val delta = marker.getAsJsonObject("classDelta")
        assertEquals(3517, delta["fileOffset"].asInt)
        assertEquals(51, delta["bytecodeOffset"].asInt)
        assertEquals("04", delta["beforeHex"].asString)
        assertEquals("03", delta["afterHex"].asString)
        val entry = type.name.replace('.', '/') + ".class"
        val bytes = requireNotNull(loader.getResourceAsStream(entry)).use { it.readAllBytes() }
        assertEquals(4135, bytes.size)
        assertEquals("b63a21608656460238801ee6ff003c7eb4a0c9fb5559956c61254ce9b03f9cbf", java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
    }

    private fun search(): List<PluginUiModel> = runBlocking {
        withTimeout(10_000) { nativeSearch("search=owned-fixture").also { assertNull(it.error) }.getPlugins() }
    }

    // Kotlin's internal object is public in the pinned JVM ABI. Invoke precisely
    // the suspend executor called by MarketplacePluginsTabSearchResultPanel,
    // keeping the real UiPluginManager -> DefaultController -> HTTP parser chain.
    private suspend fun nativeSearch(query: String): PluginSearchResult = suspendCoroutine { continuation ->
        val type = Class.forName("com.intellij.ide.plugins.newui.PluginModelAsyncOperationsExecutor")
        try {
            val result = type.getMethod("performMarketplaceSearch", String::class.java, Continuation::class.java)
                .invoke(type.getField("INSTANCE").get(null), query, continuation)
            if (result !== COROUTINE_SUSPENDED) continuation.resume(result as PluginSearchResult)
        } catch (failure: InvocationTargetException) { continuation.resumeWithException(failure.cause ?: failure) }
    }

    private fun edt(action: () -> Unit) = EdtTestUtil.runInEdtAndWait<RuntimeException> { WriteIntentReadAction.run(Runnable(action)) }

    private companion object {
        val THREE_CANDIDATES = """[
          {"xmlId":"owned.compatible.free","id":"101","updateId":"1001","name":"Owned free compatible"},
          {"xmlId":"owned.compatible.paid","id":"102","name":"Owned paid compatible","paid":true,
           "nearestUpdate":{"id":"1002","xmlId":"owned.compatible.paid","products":["idea_ce"],"isCompatible":true}},
          {"xmlId":"owned.iu-only","id":"103","name":"Owned alternate host only",
           "nearestUpdate":{"id":"1003","xmlId":"owned.iu-only","products":["idea"],"isCompatible":false}}
        ]"""
    }
}
