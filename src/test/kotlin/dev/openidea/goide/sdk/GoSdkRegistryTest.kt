package dev.openidea.goide.sdk

import com.intellij.util.xmlb.XmlSerializer
import dev.openidea.goide.project.GoModuleSdk
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GoSdkRegistryTest {
    @Test fun registrationsRoundTripWithoutPersistingTheRelocatableDefault() = fixture { root ->
        val registry = GoSdkRegistry()
        val sdk = sdk(root)
        registry.register(sdk)
        registry.register(sdk.copy(version = "go1.26.10"))
        val serialized = XmlSerializer.serialize(registry.state)
        val restored = GoSdkRegistry()
        restored.loadState(XmlSerializer.deserialize(serialized, GoSdkRegistry.Data::class.java))
        assertEquals(listOf(GoSdkRegistry.Registration(sdk.executable.toString(), sdk.root.toString(), "go1.26.10")), restored.list())
        assertTrue(restored.list().none { it.executable.isEmpty() })
        restored.state.sdks.clear()
        assertEquals(1, restored.list().size, "Exported persistence state must not mutate live registrations")
    }

    @Test fun removingARegistrationDoesNotDeleteSdkFilesOrMutateOldSnapshots() = fixture { root ->
        val sdk = sdk(root)
        Files.createDirectories(sdk.executable.parent)
        Files.writeString(sdk.executable, "owned SDK fixture")
        val registry = GoSdkRegistry()
        registry.register(sdk)
        val before = registry.list()
        registry.remove(sdk.executable.toString())
        assertTrue(registry.list().isEmpty())
        assertEquals(1, before.size)
        assertEquals("owned SDK fixture", Files.readString(sdk.executable))
    }

    @Test fun stateChangesAreObservableAndSubscriptionIsRemovable() = fixture { root ->
        val registry = GoSdkRegistry()
        var changes = 0
        val subscription = registry.addListener { changes++ }
        registry.register(sdk(root))
        registry.remove(sdk(root).executable.toString())
        subscription.close()
        registry.register(sdk(root))
        assertEquals(2, changes)
    }

    @Test fun malformedPersistedEntriesCannotBecomeRelativeSdkRegistrations() = fixture { root ->
        val good = sdk(root)
        val state = GoSdkRegistry.Data().apply {
            sdks.add(GoSdkRegistry.Entry().apply { executable = "relative/go"; this.root = root.toString(); version = "go1.26.10" })
            sdks.add(GoSdkRegistry.Entry().apply { executable = good.executable.toString(); this.root = good.root.toString(); version = "go1.26.10" })
        }
        val registry = GoSdkRegistry()
        registry.loadState(state)
        assertEquals(listOf(good.executable.toString()), registry.list().map { it.executable })
    }

    private fun sdk(root: Path) = GoModuleSdk(root.resolve("SDK 中文 😀/bin/go"), root.resolve("SDK 中文 😀"), "go1.26.9")
    private fun fixture(action: (Path) -> Unit) {
        val root = Files.createTempDirectory("sdk registry ").toAbsolutePath()
        try { action(root) } finally { root.toFile().deleteRecursively() }
    }
}
