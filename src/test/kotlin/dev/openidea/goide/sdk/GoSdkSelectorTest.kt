package dev.openidea.goide.sdk

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.impl.LaterInvocator
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.EdtTestUtil
import com.intellij.testFramework.LightPlatformTestCase
import com.intellij.util.ui.UIUtil
import dev.openidea.goide.project.GoModuleSdk
import dev.openidea.goide.project.GoSdkProbe
import java.awt.Component
import java.awt.Container
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.ThreadPoolExecutor
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel

class GoSdkSelectorTest : LightPlatformTestCase() {
    override fun runInDispatchThread() = false

    fun testDefaultVerificationRunsOffEdtAndKeepsTheRelocatableEmptySelection() = controlled { fixture ->
        val selector = fixture.create()
        val call = fixture.next()
        onEdt {
            assertFalse(selector.isReady)
            assertEquals("", selector.configuredExecutable)
            assertTrue(labels(selector).any { it.contains("Checking") })
        }
        call.answer.complete(sdk("bundled"))
        await { selector.isReady }
        onEdt {
            assertEquals("", selector.configuredExecutable)
            assertNull(selector.validationMessage)
            val combo = components(selector.component).filterIsInstance<JComboBox<*>>().single()
            assertTrue(combo.selectedItem.toString().contains("Bundled / automatic — go1.26.10"))
            assertTrue(labels(selector).any { it.contains(sdk("bundled").root.toString()) })
        }
        assertTrue(fixture.registry.list().isEmpty())
    }

    fun testLongSdkPathCannotPushTheComboArrowOrActionButtonsOutsideSettings() = controlled { fixture ->
        val long = sdk("sdks/" + "very-long-location-segment-".repeat(7) + "go")
        fixture.registry.register(long)
        val selector = fixture.create(long.executable.toString())
        fixture.next().answer.complete(long)
        await { selector.isReady }
        onEdt {
            val combo = components(selector.component).filterIsInstance<JComboBox<*>>().single()
            assertTrue("The SDK path is long enough to exercise overflow", combo.selectedItem.toString().length > 120)
            assertTrue("The combo preferred width must remain bounded", combo.preferredSize.width in 1..520)
            assertEquals("A bounded combo must still be compressible with its parent", 0, combo.minimumSize.width)
            components(selector.component).filterIsInstance<JLabel>().forEach { label ->
                assertTrue("A long SDK detail must not expand the full Settings form", label.preferredSize.width <= 520)
            }
            assertTrue(components(selector.component).filterIsInstance<JButton>().all { it.isVisible && it.preferredSize.width > 0 })
        }
    }

    fun testInvalidConfiguredSdkStaysVisibleAndNeverFallsBack() = controlled { fixture ->
        val invalid = Path.of(project.basePath!!).resolve("missing SDK/bin/go").toString()
        val selector = fixture.create(invalid)
        fixture.next().answer.completeExceptionally(IOException("The selected SDK is missing"))
        await { selector.validationMessage?.contains("missing") == true }
        onEdt {
            assertFalse(selector.isReady)
            assertEquals(invalid, selector.configuredExecutable)
            assertTrue(components(selector.component).filterIsInstance<JComboBox<*>>().single().selectedItem.toString().contains(invalid))
        }
        assertTrue("No automatic fallback probe may hide the explicit invalid selection", fixture.requests.isEmpty())
        assertTrue(fixture.registry.list().isEmpty())
    }

    fun testBackgroundVerificationCanEnableTheSelectorInsideNativeDialogModality() = controlled { fixture ->
        val selector = fixture.create()
        val call = fixture.next()
        onEdt {
            LaterInvocator.enterModal(fixture.owner)
            try {
                call.answer.complete(sdk("bundled"))
                fixture.barrier()
                UIUtil.dispatchAllInvocationEvents()
                assertTrue("A native modal Settings/New Project dialog must receive its verified SDK reply", selector.isReady)
                assertNull(selector.validationMessage)
            } finally { LaterInvocator.leaveModal(fixture.owner) }
        }
    }

    fun testVerificationDoesNotRewriteAnExistingShimIntoAnUnappliedSettingsEdit() = controlled { fixture ->
        val shim = Path.of(project.basePath!!).resolve("SDK shim/go").toString()
        val selector = fixture.create(shim)
        fixture.next().answer.complete(sdk("actual SDK"))
        await { selector.isReady }
        onEdt { assertEquals(shim, selector.configuredExecutable) }
    }

    fun testResetSuppressesLateAddRegistrationAndUsesOnlyTheCurrentGeneration() = controlled { fixture ->
        val selector = fixture.create()
        fixture.next().answer.complete(sdk("bundled"))
        await { selector.isReady }
        onEdt { selector.addLocalSdk(sdk("old local").root) }
        val old = fixture.next()
        onEdt { selector.configuredExecutable = "" }
        val reset = fixture.next()
        assertTrue(old.cancelled())
        reset.answer.complete(sdk("bundled"))
        await { selector.isReady }
        old.answer.complete(sdk("old local"))
        fixture.barrier()
        onEdt { assertEquals("", selector.configuredExecutable); assertTrue(selector.isReady) }
        assertTrue("A stale Add completion must not register its SDK", fixture.registry.list().isEmpty())
    }

    fun testAddRegistersOnlyAfterSuccessAndRemoveRetainsTheSelectionAndFilesContract() = controlled { fixture ->
        val selector = fixture.create()
        fixture.next().answer.complete(sdk("bundled")); await { selector.isReady }
        val added = sdk("local Unicode 中文 😀")
        onEdt { selector.addLocalSdk(added.root) }
        val call = fixture.next()
        assertTrue(fixture.registry.list().isEmpty())
        call.answer.complete(added)
        await { selector.isReady }
        assertEquals(added.executable.toString(), fixture.registry.list().single().executable)
        onEdt {
            assertEquals(added.executable.toString(), selector.configuredExecutable)
            button(selector, "Remove registration").doClick()
            assertEquals(added.executable.toString(), selector.configuredExecutable)
            assertTrue(selector.isReady)
        }
        assertTrue(fixture.registry.list().isEmpty())
        // Actual file preservation is checked by GoSdkRegistryTest; this test
        // exercises the real Swing Remove listener and retained current choice.
    }

    fun testFailedAddAndDisposedSelectorCannotPublishRegistrationOrCallbacks() = controlled { fixture ->
        val selector = fixture.create()
        fixture.next().answer.complete(sdk("bundled")); await { selector.isReady }
        onEdt { selector.addLocalSdk(sdk("broken").root) }
        fixture.next().answer.completeExceptionally(IOException("Not a complete SDK"))
        await { selector.validationMessage?.contains("complete SDK") == true }
        assertTrue(fixture.registry.list().isEmpty())
        var notifications = 0
        onEdt { selector.addStateListener { notifications++ }; selector.addLocalSdk(sdk("late").root) }
        val late = fixture.next()
        onEdt { Disposer.dispose(fixture.owner) }
        val before = notifications
        assertTrue(late.cancelled())
        late.answer.complete(sdk("late")); fixture.barrier()
        assertEquals(before, notifications)
        assertTrue(fixture.registry.list().isEmpty())
    }

    fun testDownloadButtonIgnoresStaleAndDisposedInstallCallbacks() = controlled { fixture ->
        val selector = fixture.create()
        fixture.next().answer.complete(sdk("bundled")); await { selector.isReady }
        onEdt { button(selector, "Download…").doClick(); selector.configuredExecutable = sdk("chosen").executable.toString() }
        val chosen = fixture.next(); chosen.answer.complete(sdk("chosen")); await { selector.isReady }
        onEdt { fixture.installs.removeAt(0)(sdk("stale downloaded")) }
        assertTrue(fixture.registry.list().isEmpty())
        onEdt {
            button(selector, "Download…").doClick()
            Disposer.dispose(fixture.owner)
            fixture.installs.removeAt(0)(sdk("disposed downloaded"))
        }
        assertTrue(fixture.registry.list().isEmpty())
        assertEquals(sdk("chosen").executable.toString(), selector.configuredExecutable)
    }

    fun testRealSelectedSdkReportsItsActualVersionAndRootFromABackgroundProbe() {
        val owner = Disposer.newDisposable()
        val executor = Executors.newSingleThreadExecutor()
        val requested = System.getenv("OPENGOLAND_GO_TEST")?.takeIf { it.isNotBlank() } ?: "go"
        var identity: GoModuleSdk? = null
        lateinit var selector: GoSdkSelector
        try {
            onEdt { selector = GoSdkSelector(project, owner, requested, GoSdkRegistry(), GoSdkSelector.Verifier { configured, cancelled ->
                assertFalse(ApplicationManager.getApplication().isDispatchThread)
                GoSdkProbe.inspect(configured, cancelled).also { identity = it }
            }, executor) { _, _ -> error("No download requested") } }
            await { selector.isReady || selector.validationMessage?.startsWith("Checking") == false }
            val actual = requireNotNull(identity) { selector.validationMessage.orEmpty() }
            onEdt {
                assertTrue(selector.isReady)
                assertEquals(requested, selector.configuredExecutable)
                assertTrue(labels(selector).any { it.contains(actual.version) && it.contains(actual.root.toString()) })
            }
        } finally {
            onEdt { Disposer.dispose(owner) }
            executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    private inner class Fixture {
        val owner = Disposer.newDisposable()
        val registry = GoSdkRegistry()
        val executor = Executors.newCachedThreadPool() as ThreadPoolExecutor
        val requests = LinkedBlockingQueue<Call>()
        val all = CopyOnWriteArrayList<Call>()
        val installs = mutableListOf<(GoModuleSdk) -> Unit>()
        fun create(initial: String = ""): GoSdkSelector {
            lateinit var selector: GoSdkSelector
            onEdt { selector = GoSdkSelector(project, owner, initial, registry, GoSdkSelector.Verifier { configured, cancelled ->
                assertFalse(ApplicationManager.getApplication().isDispatchThread)
                val call = Call(configured, cancelled); all.add(call); requests.put(call)
                // Model an already-dispatched operation whose reply can arrive
                // even after interruption. Publication must use generation checks.
                while (true) {
                    try { return@Verifier call.answer.get(10, TimeUnit.SECONDS) }
                    catch (_: InterruptedException) { }
                }
                @Suppress("UNREACHABLE_CODE") error("unreachable")
            }, executor) { _, installed -> installs.add(installed) } }
            return selector
        }
        fun next() = requireNotNull(requests.poll(5, TimeUnit.SECONDS)) { "Background SDK verification did not start" }
        fun barrier() {
            val expected = all.count { it.answer.isDone }.toLong()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (executor.completedTaskCount < expected && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue("All dispatched reply handlers must finish before the EDT publication barrier", executor.completedTaskCount >= expected)
            onEdt { }
        }
        fun close() {
            onEdt { Disposer.dispose(owner) }
            all.forEach { it.answer.completeExceptionally(java.util.concurrent.CancellationException()) }
            executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }
    private class Call(val configured: String, val cancelled: () -> Boolean) { val answer = CompletableFuture<GoModuleSdk>() }
    private fun controlled(action: (Fixture) -> Unit) { val fixture = Fixture(); try { action(fixture) } finally { fixture.close() } }
    private fun sdk(name: String) = Path.of(project.basePath!!).resolve(name).let {
        GoModuleSdk(it.resolve(if (System.getProperty("os.name").startsWith("Windows")) "bin/go.exe" else "bin/go"), it, "go1.26.10")
    }
    private fun button(selector: GoSdkSelector, label: String) = components(selector.component).filterIsInstance<JButton>().single { it.text == label }
    private fun labels(selector: GoSdkSelector) = components(selector.component).filterIsInstance<JLabel>().mapNotNull { it.text }
    private fun components(component: Component): List<Component> = listOf(component) + if (component is Container) component.components.flatMap(::components) else emptyList()
    private fun await(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (System.nanoTime() < deadline) {
            var done = false; onEdt { done = predicate() }
            if (done) return
            Thread.sleep(10)
        }
        fail("SDK selector did not reach its expected state")
    }
    private fun onEdt(action: () -> Unit) = EdtTestUtil.runInEdtAndWait<RuntimeException> { action() }
}
