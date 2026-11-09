package dev.openidea.goide.project

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.ex.ActionManagerEx
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ide.util.projectWizard.AbstractNewProjectDialog
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.LightPlatformTestCase
import com.intellij.util.ui.UIUtil
import dev.openidea.goide.settings.GoProjectSettings
import dev.openidea.goide.settings.GoToolchain
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JRootPane
import javax.swing.JButton
import javax.swing.JLabel
import java.awt.Container
import org.jetbrains.org.objectweb.asm.ClassReader
import org.jetbrains.org.objectweb.asm.ClassVisitor
import org.jetbrains.org.objectweb.asm.MethodVisitor
import org.jetbrains.org.objectweb.asm.Opcodes

/** Actual SDK action registry/native wizard controls and verified real-Go creation. */
class GoNewProjectTest : LightPlatformTestCase() {
    override fun runInDispatchThread() = false

    fun testPluginRegistersBothStandardNewProjectHostsAndTheWelcomeDelegate() {
        val manager = ActionManager.getInstance()
        for (id in GoNewProjectActionCustomizer.HOST_ACTION_IDS)
            assertTrue("The actual standard host $id must be our Go wizard action", manager.getAction(id) is GoNewProjectAction)
        val group = manager.getAction("WelcomeScreen.DefaultNewProjectActionGroup") as ActionGroup
        val children = group.getChildren(null)
        assertEquals(1, children.size)
        assertSame(manager.getAction(GoNewProjectActionCustomizer.WELCOME_ACTION_ID), children.single())
    }

    fun testMissingStandardWelcomeIdIsRegisteredWithoutAnOverrideError() {
        val manager = ActionManager.getInstance()
        val originals = GoNewProjectActionCustomizer.HOST_ACTION_IDS.associateWith { requireNotNull(manager.getAction(it)) }
        edt {
            try {
                manager.unregisterAction(GoNewProjectActionCustomizer.WELCOME_ACTION_ID)
                assertNull(manager.getAction(GoNewProjectActionCustomizer.WELCOME_ACTION_ID))
                GoNewProjectActionCustomizer().install((manager as ActionManagerEx).asActionRuntimeRegistrar())
                for (id in originals.keys) assertTrue(manager.getAction(id) is GoNewProjectAction)
                val group = manager.getAction("WelcomeScreen.DefaultNewProjectActionGroup") as ActionGroup
                assertSame(manager.getAction(GoNewProjectActionCustomizer.WELCOME_ACTION_ID), group.getChildren(null).single())
            } finally {
                for ((id, action) in originals) {
                    if (manager.getAction(id) == null) manager.registerAction(id, action) else manager.replaceAction(id, action)
                }
            }
        }
    }

    fun testNativeGoModulesPanelUsesCachedSdkValidationAndIndependentNameLocationModuleControls() {
        val lifetime = Disposer.newDisposable("native Go wizard panel")
        val parent = Files.createTempDirectory("open Goland 新项目 😀 ").toRealPath()
        try {
            val step = edt { GoNewProjectStep({ project }, lifetime, {}).settingsStep().also { it.createPanel() } }
            waitFor { step.sdkSelector.isReady }
            edt {
                step.nameField.text = "project 中文 😀"
                step.locationField.text = parent.toString()
                step.moduleField.text = "example.com/newproject"
                assertEquals("Go", step.templatePresentation.text)
                assertTrue(step.checkValid())
                assertTrue(step.actionButton.isEnabled)
                val captured = step.snapshot()
                assertEquals(parent.resolve("project 中文 😀"), captured.directory())
                assertEquals("", captured.configuredExecutable)
                assertTrue(captured.createExample)
                step.moduleField.text = "module with whitespace"
                assertFalse(step.checkValid())
                assertFalse(step.actionButton.isEnabled)
                step.moduleField.text = "example.com/newproject"
                step.nameField.text = "../outside"
                assertFalse(step.checkValid())
                assertFalse(step.actionButton.isEnabled)
            }
        } finally {
            edt { Disposer.dispose(lifetime) }
            Files.delete(parent)
        }
    }

    fun testNativeDialogAcceptsInitialSdkIntentAfterItsEagerBaseConstructor() {
        val executable = testGo()
        val environment = "GOPROXY=https://proxy.golang.org,direct"
        edt {
            val dialog = GoNewProjectDialog()
            assertEquals(com.intellij.openapi.project.ProjectBundle.message("dialog.title.new.project"), dialog.title)
            // The SDK's headless DialogWrapper peer does not attach its content
            // to a window; the real action-list selection requires a root pane.
            val root = JRootPane()
            val pairField = AbstractNewProjectDialog::class.java.getDeclaredField("myPair").apply { isAccessible = true }
            val nativePanel = (pairField.get(dialog) as com.intellij.openapi.util.Pair<*, *>).first as javax.swing.JComponent
            root.contentPane.add(nativePanel)
            try {
                dialog.initialize(project, executable, environment)
                var selected: GoNewProjectSettingsStep? = null
                assertTrue(dialog.setSelectedAction { action ->
                    (action is GoNewProjectSettingsStep).also { if (it) selected = action as GoNewProjectSettingsStep }
                })
                assertSame(project, dialog.ownerProject)
                val values = requireNotNull(selected).snapshot()
                assertEquals(executable, values.configuredExecutable)
                assertEquals(environment, values.environment)
            } finally { dialog.close(DialogWrapper.CANCEL_EXIT_CODE); root.contentPane.removeAll() }
        }
    }

    fun testNativeFooterCancelClosesWithoutCreatingAndKeepsTheEnvironmentLabelCompact() {
        val parent = Files.createTempDirectory("open Goland cancel UI ").toRealPath()
        val missingParent = parent.resolve("must-not-be-created")
        val openBefore = ProjectManager.getInstance().openProjects.toList()
        val executable = testGo()
        var dialog: GoNewProjectDialog? = null
        var rootPane: JRootPane? = null
        try {
            val step = edt {
                val native = GoNewProjectDialog().also { dialog = it; it.initialize(project, executable, "") }
                val pairField = AbstractNewProjectDialog::class.java.getDeclaredField("myPair").apply { isAccessible = true }
                val panel = (pairField.get(native) as com.intellij.openapi.util.Pair<*, *>).first as javax.swing.JComponent
                rootPane = JRootPane().also { it.contentPane.add(panel) }
                var selected: GoNewProjectSettingsStep? = null
                assertTrue(native.setSelectedAction { action ->
                    (action is GoNewProjectSettingsStep).also { if (it) selected = action as GoNewProjectSettingsStep }
                })
                requireNotNull(selected)
            }
            waitFor { step.sdkSelector.isReady }
            edt {
                val native = requireNotNull(dialog)
                step.locationField.text = missingParent.toString()
                step.nameField.text = "cancelled-project"
                assertTrue(step.checkValid())
                val controls = descendants(requireNotNull(rootPane).contentPane)
                val labels = controls.filterIsInstance<JLabel>().map { it.text }
                assertTrue(labels.contains("Environment:"))
                assertFalse(labels.any { it?.startsWith("Environment (") == true })
                assertTrue(step.environmentField.toolTipText.contains("NAME=value"))
                assertTrue(step.environmentField.toolTipText.contains("one per line"))
                val cancel = controls.filterIsInstance<JButton>().single { it.text == com.intellij.CommonBundle.getCancelButtonText() }
                assertTrue(cancel.isEnabled)
                assertFalse(native.isDisposed)
                cancel.doClick()
                assertTrue("Cancel must close the actual native DialogWrapper", native.isDisposed)
                assertEquals(DialogWrapper.CANCEL_EXIT_CODE, native.exitCode)
                assertFalse(native.isOK)
            }
            assertFalse("Cancel must not create a destination or its parent", Files.exists(missingParent))
            assertEquals(0L, Files.list(parent).use { it.count() })
            assertEquals(openBefore, ProjectManager.getInstance().openProjects.toList())
        } finally {
            edt {
                dialog?.let { if (!it.isDisposed) it.close(DialogWrapper.CANCEL_EXIT_CODE) }
                rootPane?.contentPane?.removeAll()
            }
            GoSdkProbe.deleteOwnedTree(parent)
        }
    }

    fun testSettingsStepUsesTheProductionSafeActionGroupSnapshot() {
        // The SDK suppresses getChildren(null)'s production error in unit mode.
        // Verify the actually compiled call boundary as well as the native UI path.
        val calls = mutableListOf<Pair<String, String>>()
        val type = GoNewProjectStep::class.java
        val resource = "/${type.name.replace('.', '/')}.class"
        type.getResourceAsStream(resource).use { input ->
            assertNotNull(input)
            ClassReader(requireNotNull(input)).accept(object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor? {
                    if (!name.startsWith("settingsStep")) return null
                    return object : MethodVisitor(Opcodes.ASM9) {
                        override fun visitMethodInsn(opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean) {
                            calls += name to descriptor
                        }
                    }
                }
            }, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        }
        assertTrue("Use the already initialized native children without null action events", calls.any { it.first == "getChildActionsOrStubs" })
        assertFalse("The production-only null-event logger must never be reached", calls.any {
            it.first == "getChildren" && it.second.startsWith("(Lcom/intellij/openapi/actionSystem/AnActionEvent;")
        })
    }

    fun testBeforeInitKeepsAutomaticSdkRelocatableAndLeavesWindowChoiceToThePlatform() {
        val sdk = actualSdk()
        val values = settings(Path.of(System.getProperty("user.home")), "", environment = "GOPROXY=https://proxy.golang.org,direct")
        val created = GoNewProjectCreator.Created(values.directory(), values, sdk)
        val creator = GoNewProjectCreator { _, _ -> error("Options must not open a project") }
        val state = GoProjectSettings.get(project)
        val priorGo = state.goExecutable
        val priorEnvironment = state.environment
        try {
            val options = creator.openOptions(created, project)
            assertSame(project, options.projectToClose)
            assertFalse(options.forceOpenInNewFrame)
            assertFalse(options.forceReuseFrame)
            assertTrue(options.isNewProject)
            assertTrue(options.isProjectCreatedWithWizard)
            assertTrue(options.runConfigurators)
            requireNotNull(options.beforeInit).invoke(project)
            assertEquals(priorGo, state.goExecutable)
            project.getService(GoProjectSettings::class.java).initializeComponent()
            assertEquals("", state.goExecutable)
            assertEquals(values.environment, state.environment)
            val explicit = created.copy(settings = values.copy(configuredExecutable = sdk.executable.toString()))
            requireNotNull(creator.openOptions(explicit, null).beforeInit).invoke(project)
            project.getService(GoProjectSettings::class.java).initializeComponent()
            assertEquals(sdk.executable.toString(), state.goExecutable)
        } finally { state.goExecutable = priorGo; state.environment = priorEnvironment }
    }

    fun testRealGenerationCompletesAndPassesItsTestBeforeAnyProjectIsOpened() {
        val parent = Files.createTempDirectory("open Goland verified project 😀 ").toRealPath()
        val opens = AtomicInteger()
        var openedPath: Path? = null
        val creator = GoNewProjectCreator { path, options ->
            assertTrue(ApplicationManager.getApplication().isDispatchThread)
            assertNotNull(options.beforeInit)
            openedPath = path; opens.incrementAndGet(); project
        }
        try {
            val created = creator.generate(settings(parent, testGo()), { false })
            assertEquals(0, opens.get())
            assertTrue(Files.readString(created.directory.resolve("go.mod")).contains("module example.com/wizard"))
            assertTrue(Files.isRegularFile(created.directory.resolve("greeting_test.go")))
            val tests = GoModuleProcesses.run(created.sdk.executable.toString(), listOf("test", "-count=1", "./..."), created.directory, { false })
            assertTrue(tests.stdout, tests.stdout.contains("ok") && !tests.stdout.contains("(cached)"))
            val disposedOwner = Proxy.newProxyInstance(Project::class.java.classLoader, arrayOf(Project::class.java)) { proxy, method, args ->
                when (method.name) { "isDisposed" -> true; "hashCode" -> System.identityHashCode(proxy); "equals" -> proxy === args?.firstOrNull(); else -> null }
            } as Project
            assertNull(edt { creator.open(created, disposedOwner) { false } })
            assertNull(edt { creator.open(created, project) { true } })
            assertEquals(0, opens.get())
            assertTrue("Completed files survive cancellation/owner disposal", Files.isRegularFile(created.directory.resolve("go.mod")))
            assertSame(project, edt { creator.open(created, project) { false } })
            assertEquals(1, opens.get())
            assertEquals(created.directory, openedPath)
        } finally { GoSdkProbe.deleteOwnedTree(parent) }
    }

    fun testCancellationDuringRealGoModInitNeverPublishesTheDestinationOrOpensAProject() {
        val parent = Files.createTempDirectory("open Goland cancel wizard ").toRealPath()
        val cancelled = AtomicBoolean()
        val values = settings(parent, testGo())
        val creator = GoNewProjectCreator { _, _ -> error("Cancelled generation must not open a project") }
        try {
            var failed = false
            try {
                creator.generate(values, cancelled::get) { if (it.contains("creating new go.mod")) cancelled.set(true) }
            } catch (_: CancellationException) { failed = true }
            assertTrue("The real go mod init output must reach the cancellation gate", cancelled.get())
            assertTrue(failed)
            assertFalse(Files.exists(values.directory()))
            assertEquals(0L, Files.list(parent).use { it.count() })
        } finally { GoSdkProbe.deleteOwnedTree(parent) }
    }

    fun testInvalidSdkAndExistingDestinationCannotBePublished() {
        val parent = Files.createTempDirectory("open Goland invalid wizard ").toRealPath()
        val creator = GoNewProjectCreator { _, _ -> error("Invalid generation must not open a project") }
        try {
            val invalid = settings(parent.resolve("missing parent"), parent.resolve("missing-sdk").toString())
            try { creator.generate(invalid, { false }); fail("Invalid SDK was accepted") } catch (_: IllegalArgumentException) { }
            assertFalse(Files.exists(invalid.directory().parent))
            val existing = settings(parent, testGo())
            Files.createDirectory(existing.directory())
            Files.writeString(existing.directory().resolve("keep.txt"), "unchanged")
            try { creator.generate(existing, { false }); fail("An existing destination was accepted") } catch (_: java.io.IOException) { }
            assertEquals("unchanged", Files.readString(existing.directory().resolve("keep.txt")))
        } finally { GoSdkProbe.deleteOwnedTree(parent) }
    }

    private fun descendants(container: Container): List<java.awt.Component> = container.components.flatMap {
        listOf(it) + if (it is Container) descendants(it) else emptyList()
    }
    private fun settings(parent: Path, sdk: String, environment: String = "") =
        GoNewProjectSettings("project 中文 😀", parent.toString(), "example.com/wizard", sdk, true, environment)
    private fun testGo() = requireNotNull(GoToolchain.resolve("go",
        System.getProperty("opengoland.testGo") ?: System.getenv("OPENGOLAND_GO_TEST").orEmpty()))
    private fun actualSdk() = GoSdkProbe.inspect(testGo())
    private fun <T> edt(action: () -> T): T {
        var value: T? = null
        ApplicationManager.getApplication().invokeAndWait { WriteIntentReadAction.run { value = action() } }
        @Suppress("UNCHECKED_CAST") return value as T
    }
    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (!edt { condition() } && System.nanoTime() < deadline) {
            edt { UIUtil.dispatchAllInvocationEvents() }; Thread.sleep(10)
        }
        assertTrue("The native SDK selector did not reach its verified state", edt { condition() })
    }
}
