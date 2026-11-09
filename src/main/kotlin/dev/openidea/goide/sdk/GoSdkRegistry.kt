package dev.openidea.goide.sdk

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import dev.openidea.goide.project.GoModuleSdk
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

/** Registrations remember a verified local SDK, not ownership of its files. Reverify before use. */
@Service(Service.Level.APP)
@State(name = "OpenGolandGoSdks", storages = [Storage("open-goland-go-sdks.xml")])
class GoSdkRegistry : PersistentStateComponent<GoSdkRegistry.Data> {
    class Data { var sdks: MutableList<Entry> = mutableListOf() }
    class Entry {
        var executable = ""
        var root = ""
        var version = ""
    }
    data class Registration(val executable: String, val root: String, val version: String)

    private var registrations = emptyList<Registration>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /** Only copies in-memory state. Loading and saving are owned by the platform state store. */
    @Synchronized fun list(): List<Registration> = registrations.toList()

    @Synchronized override fun getState() = Data().also { state ->
        state.sdks = registrations.map { sdk -> Entry().apply {
            executable = sdk.executable; root = sdk.root; version = sdk.version
        } }.toMutableList()
    }

    override fun loadState(state: Data) {
        val restored = state.sdks.mapNotNull { entry ->
            val executable = runCatching { Path.of(entry.executable) }.getOrNull()
            val root = runCatching { Path.of(entry.root) }.getOrNull()
            if (executable?.isAbsolute != true || root?.isAbsolute != true || entry.version.isBlank()) null
            else Registration(executable.normalize().toString(), root.normalize().toString(), entry.version)
        }.distinctBy { Path.of(it.executable) }
        synchronized(this) { registrations = restored }
        changed()
    }

    /** Callers must obtain this identity through GoSdkProbe or the verified downloader. No IO here. */
    internal fun register(sdk: GoModuleSdk): Registration {
        require(sdk.executable.isAbsolute && sdk.root.isAbsolute && sdk.version.isNotBlank()) { "A verified absolute Go SDK is required" }
        val value = Registration(sdk.executable.normalize().toString(), sdk.root.normalize().toString(), sdk.version)
        synchronized(this) {
            registrations = registrations.filterNot { Path.of(it.executable) == sdk.executable.normalize() } + value
        }
        changed()
        return value
    }

    /** Removes only the registration; never removes or changes SDK files. */
    fun remove(executable: String) {
        val path = runCatching { Path.of(executable) }.getOrNull() ?: return
        val removed = synchronized(this) {
            val next = registrations.filterNot { Path.of(it.executable) == path }
            (next != registrations).also { registrations = next }
        }
        if (removed) changed()
    }

    internal fun addListener(listener: () -> Unit): AutoCloseable {
        listeners.add(listener)
        return AutoCloseable { listeners.remove(listener) }
    }
    private fun changed() { listeners.forEach { it() } }

    companion object {
        fun getInstance(): GoSdkRegistry = ApplicationManager.getApplication().getService(GoSdkRegistry::class.java)
    }
}
