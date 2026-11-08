package dev.openidea.goide.lifecycle

import com.intellij.ide.ApplicationInitializedListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.serviceAsync
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.net.HttpConfigurable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Product startup workaround for IC-261.26222.65's JBCefApp.Holder initialization.
 *
 * The pinned platform awaits applicationInitializedListener children before returning
 * from loadApp. Do not replace this with applicationActivity, a project activity,
 * invokeLater, or a detached launch: editor restoration could then win the race.
 */
class StandaloneProxyWarmup internal constructor(private val context: ProxyWarmupContext) : ApplicationInitializedListener {
    constructor() : this(PlatformProxyWarmupContext())

    override suspend fun execute() {
        // The plugin also runs in development and may be installed in another IDE.
        // Its platform workaround belongs exclusively to our independently packaged product.
        if (context.vendorName() != "OpenGoland") return
        withContext(Dispatchers.IO) {
            context.started()
            // Await complete service initialization (including initializeComponent/state load).
            // No proxy values are inspected, edited, reset, or logged here.
            context.initializeProxy()
            context.completed()
        }
        // Failures and cancellation intentionally propagate to the platform startup scope.
    }
}

internal interface ProxyWarmupContext {
    fun vendorName(): String?
    fun started()
    suspend fun initializeProxy()
    fun completed()
}

private class PlatformProxyWarmupContext : ProxyWarmupContext {
    override fun vendorName(): String? = System.getProperty("idea.vendor.name")
    override fun started() {
        Logger.getInstance(StandaloneProxyWarmup::class.java).info("open Goland proxy warmup: starting before application starter")
    }
    override suspend fun initializeProxy() {
        ApplicationManager.getApplication().serviceAsync<HttpConfigurable>()
    }
    override fun completed() {
        Logger.getInstance(StandaloneProxyWarmup::class.java).info("open Goland proxy warmup: completed before application starter")
    }
}
