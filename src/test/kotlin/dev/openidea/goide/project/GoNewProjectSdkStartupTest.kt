package dev.openidea.goide.project

import com.intellij.configurationStore.StoreUtil
import com.intellij.configurationStore.runInAllowSaveMode
import com.intellij.ide.impl.OpenProjectTask
import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ex.ProjectManagerEx
import com.intellij.openapi.startup.InitProjectActivity
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.LightPlatformTestCase
import dev.openidea.goide.settings.GoProjectSettings
import dev.openidea.goide.settings.GoToolchain
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

/** Uses the real project opener and projectPreInit, including persistent reopen. */
class GoNewProjectSdkStartupTest : LightPlatformTestCase() {
    override fun runInDispatchThread() = false

    fun testExplicitSdkAndEnvironmentReachFirstProjectInitializationAndPersistentReopen() {
        val parent = Files.createTempDirectory("open-goland-sdk-startup-").toRealPath()
        val selected = requireNotNull(GoToolchain.resolve("go",
            System.getProperty("opengoland.testGo") ?: System.getenv("OPENGOLAND_GO_TEST").orEmpty()))
        val name = "sdk-startup-${UUID.randomUUID()}"
        val environment = "GOTOOLCHAIN=local\nGOPROXY=off\nOPENGOLAND_STARTUP_TOKEN=${UUID.randomUUID()}"
        val settings = GoNewProjectSettings(name, parent.toString(), "example.org/sdkstartup", selected, true, environment)
        val creator = GoNewProjectCreator()
        val observations = ConcurrentLinkedQueue<GoToolchain.Settings>()
        val registration = Disposer.newDisposable("owned Go project startup observer")
        var opened: Project? = null
        var reopened: Project? = null
        try {
            ExtensionPointName.create<InitProjectActivity>("com.intellij.projectPreInit").point.registerExtension(object : InitProjectActivity {
                override suspend fun run(project: Project) {
                    if (project.name == name) observations += GoToolchain.capture(project)
                }
            }, registration)
            val created = creator.generate(settings, { false })
            opened = edt { creator.open(created, null) { false } }
            val actual = requireNotNull(opened) { "The real project opener did not open the generated module" }
            assertTrue(actual.isOpen)
            assertEquals(1, observations.size)
            assertSelected(created.persistedExecutable, environment, observations.single())
            assertSelected(created.persistedExecutable, environment, GoToolchain.capture(actual))
            // Resolve through the real Run toolchain command path, not a copied setting alone.
            assertEquals(created.persistedExecutable, GoToolchain.command(actual, "go", listOf("version")).exePath)
            runInAllowSaveMode { StoreUtil.saveSettings(actual, true) }
            val file = created.directory.resolve(".idea/openidea-go.xml")
            assertTrue("The selected SDK must be durably stored", Files.isRegularFile(file))
            val persisted = Files.readString(file)
            assertTrue(persisted, persisted.contains("goExecutable"))
            close(actual)
            opened = null
            assertTrue(actual.isDisposed)
            reopened = edt { ProjectUtil.openOrImport(created.directory, OpenProjectTask.build()) }
            val again = requireNotNull(reopened)
            assertEquals(2, observations.size)
            assertSelected(created.persistedExecutable, environment, observations.last())
            assertSelected(created.persistedExecutable, environment, GoToolchain.capture(again))
            assertEquals(created.persistedExecutable, GoToolchain.command(again, "go", listOf("version")).exePath)
        } finally {
            reopened?.let { close(it) }
            opened?.let { close(it) }
            Disposer.dispose(registration)
            GoSdkProbe.deleteOwnedTree(parent)
        }
    }

    fun testAutomaticSdkIsInitializedWithoutAnyObserverBeforePublicationAndSurvivesReopen() {
        val parent = Files.createTempDirectory("open-goland-auto-startup-").toRealPath()
        val settings = GoNewProjectSettings("automatic-${UUID.randomUUID()}", parent.toString(),
            "example.org/autostartup", "", false, "GOTOOLCHAIN=local\nGOPROXY=off")
        val creator = GoNewProjectCreator()
        var opened: Project? = null
        var reopened: Project? = null
        try {
            val created = creator.generate(settings, { false })
            opened = edt { creator.open(created, null) { false } }
            val actual = requireNotNull(opened)
            // No projectPreInit observer or editor asked for this service. The
            // real beforeOpen barrier must have made it persistent already.
            val service = requireNotNull(actual.getServiceIfCreated(GoProjectSettings::class.java))
            assertEquals("", service.state.goExecutable)
            assertEquals(settings.environment, service.state.environment)
            assertEquals(created.sdk.executable.toString(), GoToolchain.command(actual, "go", listOf("version")).exePath)
            runInAllowSaveMode { StoreUtil.saveSettings(actual, true) }
            assertTrue(Files.isRegularFile(created.directory.resolve(".idea/openidea-go.xml")))
            close(actual)
            opened = null
            reopened = edt { ProjectUtil.openOrImport(created.directory, OpenProjectTask.build()) }
            val again = requireNotNull(reopened)
            assertEquals("", GoProjectSettings.get(again).goExecutable)
            assertEquals(settings.environment, GoProjectSettings.get(again).environment)
        } finally {
            reopened?.let { close(it) }
            opened?.let { close(it) }
            GoSdkProbe.deleteOwnedTree(parent)
        }
    }

    fun testAutomaticSelectionOverridesLoadedDefaultsAndLaterReloadsRemainAuthoritative() {
        val owner = project
        val service = GoProjectSettings(owner)
        val template = GoProjectSettings.Data().apply {
            goExecutable = "template-sdk"
            environment = "FROM_TEMPLATE=yes"
            buildTags = "retained-template-tag"
        }
        GoProjectSettings.stageNewProject(owner, "", "GOTOOLCHAIN=local")
        // This is the public persistent-state sequence used by ComponentStoreImpl.
        // The separate real-opener test proves the SDK invokes it for the service.
        service.loadState(template)
        service.initializeComponent()
        assertEquals("", service.state.goExecutable)
        assertEquals("GOTOOLCHAIN=local", service.state.environment)
        assertEquals("retained-template-tag", service.state.buildTags)
        val changed = GoProjectSettings.Data().apply {
            goExecutable = "later-explicit-sdk"
            environment = "AFTER_INITIALIZATION=yes"
        }
        service.loadState(changed)
        service.initializeComponent()
        assertEquals("Pending creation settings must be consumed once", changed.goExecutable, service.state.goExecutable)
        assertEquals(changed.environment, service.state.environment)
    }

    private fun assertSelected(executable: String, environment: String, actual: GoToolchain.Settings) {
        assertEquals("SDK must be selected before project pre-initialization/services", executable, actual.goExecutable)
        assertEquals("Environment must be selected before project pre-initialization/services", environment, actual.environment)
    }

    private fun close(project: Project) {
        if (!project.isDisposed) edt { assertTrue(ProjectManagerEx.getInstanceEx().forceCloseProject(project, false)) }
    }

    private fun <T> edt(action: () -> T): T {
        var result: T? = null
        ApplicationManager.getApplication().invokeAndWait { WriteIntentReadAction.run { result = action() } }
        @Suppress("UNCHECKED_CAST") return result as T
    }
}
