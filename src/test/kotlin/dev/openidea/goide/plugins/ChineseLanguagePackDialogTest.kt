@file:OptIn(com.intellij.openapi.util.IntellijInternalApi::class)

package dev.openidea.goide.plugins

import com.intellij.ide.plugins.InstalledPluginsState
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.TransactionGuard
import com.intellij.openapi.application.TransactionGuardImpl
import com.intellij.openapi.application.impl.LaterInvocator
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.fileChooser.*
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.updateSettings.impl.UpdateSettings
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightPlatformTestCase
import com.intellij.testFramework.replaceService
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.UIUtil
import java.awt.Component
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.swing.JTextArea
import javax.swing.JTextField

/** Real native DialogWrapper/VFS/Disposer; worker and installer seams isolate lifecycle without changing its path. */
class ChineseLanguagePackDialogTest : LightPlatformTestCase() {
    override fun runInDispatchThread() = false

    fun testActionIsRegisteredInNativeHelpAndToolsMenus() {
        val manager = ActionManager.getInstance()
        val action = manager.getAction("OpenIDEA.InstallChineseLanguagePack")
        assertTrue(action is InstallChineseLanguagePackAction)
        for (id in listOf("HelpMenu", "ToolsMenu")) {
            val group = manager.getAction(id) as DefaultActionGroup
            assertTrue(id, group.getChildren(manager).any { it === action })
        }
    }

    fun testNonModalNativeHandoffKeepsAWriteSafePlatformContext() =
        verifyWriteSafeHandoff(false)

    fun testModalNativeHandoffKeepsItsOwnWriteSafeStateWithoutWaitingForTheDialogToClose() =
        verifyWriteSafeHandoff(true)

    private fun verifyWriteSafeHandoff(modal: Boolean) = withRoot { root ->
        val source = ChinesePackSource.load()
        val owner = Disposer.newDisposable()
        val invoked = CountDownLatch(1)
        val writeSafe = AtomicBoolean()
        val failure = AtomicReference<Throwable?>()
        val guard = TransactionGuard.getInstance()
        val entity = Any()
        var state = ModalityState.nonModal()
        val path = Files.writeString(root.resolve("owned-chinese-write-safety.txt"), "before")
        val virtual = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path))
        val document = ReadAction.compute<com.intellij.openapi.editor.Document, RuntimeException> {
            requireNotNull(FileDocumentManager.getInstance().getDocument(virtual))
        }
        val psi = ReadAction.compute<com.intellij.psi.PsiFile, RuntimeException> {
            requireNotNull(PsiManager.getInstance(project).findFile(virtual))
        }
        val dialog = edt { ChineseLanguagePackDialog(project, source, source.platformBuild,
            preparation(root), ChinesePackInstaller { _, _, _, _ ->
                try {
                    writeSafe.set(guard.isWritingAllowed)
                    if (writeSafe.get()) WriteCommandAction.runWriteCommandAction(project) {
                        document.setText("after 中文😀")
                        PsiDocumentManager.getInstance(project).commitDocument(document)
                    }
                } catch (error: Throwable) { failure.set(error) }
                finally { invoked.countDown() }
            }, owner) }
        try {
            edt {
                (guard as TransactionGuardImpl).performUserActivity {
                    if (modal) LaterInvocator.enterModal(entity)
                    state = ModalityState.current()
                    assertTrue("The actual starting user context must be write-safe", guard.isWritingAllowed)
                    dialog.performOKAction()
                }
            }
            val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (invoked.count > 0 && System.nanoTime() < end) {
                inState(state) { UIUtil.dispatchAllInvocationEvents() }
                Thread.sleep(5)
            }
            assertEquals("Handoff must execute while the originating modality is still active", 0L, invoked.count)
            assertTrue("Native installation cannot run in TransactionGuard's write-unsafe ANY context", writeSafe.get())
            assertNull("An ordinary platform PSI write must complete without a transaction violation", failure.get())
            assertEquals("after 中文😀", inState(state) { psi.text })
        } finally {
            inState(state) {
                if (!dialog.isDisposed) dialog.close(1)
                if (modal && LaterInvocator.getCurrentModalEntities().contains(entity)) LaterInvocator.leaveModal(entity)
                Disposer.dispose(owner)
            }
            // Keep the shared SDK file-document service free of this owned
            // physical fixture's dirty buffer before deleting its directory.
            edt { FileDocumentManager.getInstance().saveDocument(document) }
            assertFalse(edt { FileDocumentManager.getInstance().isDocumentUnsaved(document) })
        }
    }

    private fun <T> inState(state: ModalityState, action: () -> T): T {
        var value: T? = null
        ApplicationManager.getApplication().invokeAndWait({ WriteIntentReadAction.run { value = action() } }, state)
        @Suppress("UNCHECKED_CAST") return value as T
    }

    fun testBackgroundReadyResultUsesNativeVfsAndEdtHandoffAndRetainsItsArchive() = withRoot { root ->
        val source = ChinesePackSource.load()
        val callback = AtomicReference<Path?>()
        val owner = Disposer.newDisposable()
        val dialog = edt { ChineseLanguagePackDialog(project, source, source.platformBuild,
            preparation(root), ChinesePackInstaller { _, file, _, spec ->
                assertTrue(ApplicationManager.getApplication().isDispatchThread)
                assertEquals(source.pluginId, spec.pluginId)
                callback.set(file.toNioPath())
            }, owner) }
        try {
            edt { dialog.performOKAction() }
            await { callback.get() != null }
            assertTrue(Files.isRegularFile(callback.get()))
            assertTrue(edt { dialog.isDisposed })
            assertEquals(0, edt { dialog.exitCode })
        } finally { edt { if (!dialog.isDisposed) dialog.close(1); Disposer.dispose(owner) } }
    }

    fun testOwnerDisposalCancelsWorkerAndLateReadyCannotInstall() = withRoot { root ->
        val source = ChinesePackSource.load()
        val owner = Disposer.newDisposable()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val ready = AtomicReference<PreparedChinesePack?>(); val calls = AtomicInteger()
        val preparation = ChinesePackPreparation { _, _, _, _, _ ->
            assertFalse(ApplicationManager.getApplication().isDispatchThread)
            val value = prepared(root)
            ready.set(value); entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
            value // Deliberately late despite cancellation: controller must reject it.
        }
        val dialog = edt { ChineseLanguagePackDialog(project, source, source.platformBuild, preparation,
            ChinesePackInstaller { _, _, _, _ -> calls.incrementAndGet() }, owner) }
        try {
            edt { dialog.performOKAction() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            edt { Disposer.dispose(owner) }
            release.countDown()
            await { ready.get()?.let { !Files.exists(it.zip) } == true }
            assertEquals(0, calls.get())
            assertTrue(edt { dialog.isDisposed })
        } finally { release.countDown(); edt { if (!dialog.isDisposed) dialog.close(1); Disposer.dispose(owner) } }
    }

    fun testReadyBeforeQueuedEdtCancellationCannotBeHandedToInstaller() = withRoot { root ->
        val source = ChinesePackSource.load()
        val owner = Disposer.newDisposable()
        val entered = CountDownLatch(1); val produce = CountDownLatch(1)
        val held = CountDownLatch(1); val releaseEdt = CountDownLatch(1)
        val ready = AtomicReference<PreparedChinesePack?>(); val calls = AtomicInteger()
        val dialog = edt { ChineseLanguagePackDialog(project, source, source.platformBuild,
            ChinesePackPreparation { _, _, _, _, _ ->
                entered.countDown(); check(produce.await(5, TimeUnit.SECONDS))
                prepared(root).also(ready::set)
            }, ChinesePackInstaller { _, _, _, _ -> calls.incrementAndGet() }, owner) }
        try {
            edt { dialog.performOKAction() }; assertTrue(entered.await(5, TimeUnit.SECONDS))
            ApplicationManager.getApplication().invokeLater { held.countDown(); check(releaseEdt.await(5, TimeUnit.SECONDS)) }
            assertTrue(held.await(5, TimeUnit.SECONDS))
            ApplicationManager.getApplication().invokeLater { dialog.close(1) }
            produce.countDown()
            val pending = field<AtomicReference<PreparedChinesePack?>>(dialog, "pending")
            val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (pending.get() == null && System.nanoTime() < end) Thread.sleep(2)
            assertNotNull(pending.get())
            releaseEdt.countDown()
            await { ready.get()?.let { !Files.exists(it.zip) } == true }
            assertEquals(0, calls.get())
        } finally {
            produce.countDown(); releaseEdt.countDown()
            edt { if (!dialog.isDisposed) dialog.close(1); Disposer.dispose(owner) }
        }
    }

    fun testPreparationFailureAllowsAnExplicitRetryWithoutRevivingOldState() = withRoot { root ->
        val source = ChinesePackSource.load()
        val owner = Disposer.newDisposable()
        val attempts = AtomicInteger(); val calls = AtomicInteger()
        val dialog = edt { ChineseLanguagePackDialog(project, source, source.platformBuild,
            ChinesePackPreparation { _, _, _, _, _ ->
                if (attempts.incrementAndGet() == 1) throw IOException("Owned offline fixture")
                prepared(root)
            }, ChinesePackInstaller { _, _, _, _ -> calls.incrementAndGet() }, owner) }
        try {
            edt { dialog.performOKAction() }
            await { edt { field<JTextArea>(dialog, "status").text.contains("Owned offline fixture") && dialog.isOKActionEnabled } }
            assertFalse(edt { dialog.isDisposed })
            edt { dialog.performOKAction() }
            await { calls.get() == 1 }
            assertEquals(2, attempts.get())
        } finally { edt { if (!dialog.isDisposed) dialog.close(1); Disposer.dispose(owner) } }
    }

    fun testNativeCancellationNeverShowsAnErrorOrDeletesTheRetainedSource() = withRoot { root ->
        val source = ChinesePackSource.load()
        for (cancel in listOf<Exception>(ProcessCanceledException(), java.util.concurrent.CancellationException("owned cancellation"))) {
            val owner = Disposer.newDisposable()
            val result = AtomicReference<PreparedChinesePack?>()
            val invoked = CountDownLatch(1); val errors = AtomicInteger()
            val dialog = edt { ChineseLanguagePackDialog(project, source, source.platformBuild,
                ChinesePackPreparation { _, _, _, _, _ -> prepared(root).also(result::set) },
                ChinesePackInstaller { _, _, _, _ -> invoked.countDown(); throw cancel }, owner) }
            val previous = edt { TestDialogManager.setTestDialog { errors.incrementAndGet(); Messages.OK } }
            try {
                edt { dialog.performOKAction() }
                await { invoked.count == 0L }
                edt { UIUtil.dispatchAllInvocationEvents() }
                assertEquals(0, errors.get())
                assertTrue(Files.isRegularFile(result.get()!!.zip))
            } finally { edt { TestDialogManager.setTestDialog(previous); if (!dialog.isDisposed) dialog.close(1); Disposer.dispose(owner) } }
        }
    }

    fun testOwnerClosingDuringNativeInstallDoesNotShowAnErrorOrDeleteTheRetainedArchive() = withRoot { root ->
        val source = ChinesePackSource.load()
        val owner = Disposer.newDisposable()
        val result = AtomicReference<PreparedChinesePack?>()
        val invoked = CountDownLatch(1); val errors = AtomicInteger()
        val dialog = edt { ChineseLanguagePackDialog(project, source, source.platformBuild,
            ChinesePackPreparation { _, _, _, _, _ -> prepared(root).also(result::set) },
            ChinesePackInstaller { _, _, _, _ -> Disposer.dispose(owner); invoked.countDown(); throw IOException("Owned closing fixture") }, owner) }
        val previous = edt { TestDialogManager.setTestDialog { errors.incrementAndGet(); Messages.OK } }
        try {
            edt { dialog.performOKAction() }
            await { invoked.count == 0L }
            edt { UIUtil.dispatchAllInvocationEvents() }
            assertEquals(0, errors.get())
            assertTrue(Files.isRegularFile(result.get()!!.zip))
        } finally { edt { TestDialogManager.setTestDialog(previous); if (!dialog.isDisposed) dialog.close(1); Disposer.dispose(owner) } }
    }

    fun testWrongBuildDisablesContinueWithoutStartingPreparation() = withRoot { root ->
        val source = ChinesePackSource.load()
        val owner = Disposer.newDisposable()
        val calls = AtomicInteger()
        val dialog = edt { ChineseLanguagePackDialog(project, source, "261.26222.72",
            ChinesePackPreparation { _, _, _, _, _ -> calls.incrementAndGet(); prepared(root) },
            ChinesePackInstaller { _, _, _, _ -> fail("Wrong build must not install") }, owner) }
        try {
            assertFalse(edt { dialog.isOKActionEnabled })
            edt { dialog.performOKAction() }
            assertEquals(0, calls.get())
        } finally { edt { dialog.close(1); Disposer.dispose(owner) } }
    }

    fun testNativeFallbackWithoutPluginsPageConfirmsZipThenRejectsWrongBuild() = withRoot { root ->
        val xml = """<idea-plugin><id>com.intellij.zh</id><name>Owned Chinese build guard</name><version>999.0</version><vendor>Owned fixture</vendor><idea-version since-build="999" until-build="999.*"/></idea-plugin>"""
        val inner = ByteArrayOutputStream().also { out -> ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("META-INF/plugin.xml")); zip.write(xml.toByteArray()); zip.closeEntry()
        } }.toByteArray()
        val path = root.resolve("native-build-guard.zip")
        ZipOutputStream(Files.newOutputStream(path)).use { zip ->
            zip.putNextEntry(ZipEntry("fixture/lib/fixture.jar")); zip.write(inner); zip.closeEntry()
        }
        val virtual = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path))
        val source = ChinesePackSource.load()
        val result = PreparedChinesePack(path, root)
        val chosen = AtomicInteger(); val warnings = mutableListOf<String>()
        val factory = FileChooserFactory.getInstance()
        val disposable = Disposer.newDisposable()
        val pluginId = com.intellij.openapi.extensions.PluginId.getId("com.intellij.zh")
        val before = InstalledPluginsState.getInstance().wasInstalled(pluginId)
        edt {
            ApplicationManager.getApplication().replaceService(FileChooserFactory::class.java, object : FileChooserFactory() {
                override fun createFileChooser(d: FileChooserDescriptor, p: Project?, parent: Component?) = object : FileChooserDialog {
                    override fun choose(project: Project?, vararg toSelect: VirtualFile): Array<VirtualFile> {
                        assertEquals(1, toSelect.size); assertSame(virtual, toSelect.single()); chosen.incrementAndGet()
                        return arrayOf(virtual)
                    }
                }
                override fun createPathChooser(d: FileChooserDescriptor, p: Project?, parent: Component?) = factory.createPathChooser(d, p, parent)
                override fun createSaveFileDialog(d: FileSaverDescriptor, p: Project?) = factory.createSaveFileDialog(d, p)
                override fun createSaveFileDialog(d: FileSaverDescriptor, parent: Component) = factory.createSaveFileDialog(d, parent)
                override fun createFileTextField(d: FileChooserDescriptor, hidden: Boolean, parent: Disposable?) = factory.createFileTextField(d, hidden, parent)
                override fun installFileCompletion(field: JTextField, d: FileChooserDescriptor, hidden: Boolean, parent: Disposable?) = factory.installFileCompletion(field, d, hidden, parent)
            }, disposable)
            val settings = UpdateSettings.getInstance(); val allowed = settings.isThirdPartyPluginsAllowed
            val oldDialog = TestDialogManager.setTestDialog { message -> warnings += message; Messages.OK }
            try {
                settings.isThirdPartyPluginsAllowed = true
                NativeChinesePackInstaller().install(project, virtual, result, source)
            } finally {
                TestDialogManager.setTestDialog(oldDialog); settings.isThirdPartyPluginsAllowed = allowed; Disposer.dispose(disposable)
            }
        }
        result.close()
        assertEquals(1, chosen.get())
        assertTrue("The actual native build gate must reject 999 before mutation: $warnings", warnings.any { it.contains("999") })
        assertEquals(before, InstalledPluginsState.getInstance().wasInstalled(pluginId))
        assertTrue("Handoff retains the verified source even if a native flow rejects or cancels", Files.exists(path))
    }

    private fun preparation(root: Path) = ChinesePackPreparation { _, _, _, token, _ ->
        assertFalse(ApplicationManager.getApplication().isDispatchThread); token.check(); prepared(root)
    }

    private fun prepared(root: Path): PreparedChinesePack {
        val directory = Files.createTempDirectory(root, "owned-prepared-")
        val file = Files.writeString(directory.resolve("localization-zh.zip"), "lifecycle fixture only")
        return PreparedChinesePack(file, directory)
    }

    private fun withRoot(action: (Path) -> Unit) {
        val root = Files.createTempDirectory("native Chinese 中文😀 ")
        VfsRootAccess.allowRootAccess(testRootDisposable, root.toString())
        try { action(root) } finally { Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
    }

    private fun await(condition: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition() && System.nanoTime() < end) { edt { UIUtil.dispatchAllInvocationEvents() }; Thread.sleep(5) }
        assertTrue("Native dialog operation did not complete", condition())
    }
    private inline fun <reified T> field(instance: Any, name: String): T =
        instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(instance) as T
    private fun <T> edt(action: () -> T): T {
        var value: T? = null
        ApplicationManager.getApplication().invokeAndWait { WriteIntentReadAction.run { value = action() } }
        @Suppress("UNCHECKED_CAST") return value as T
    }
}
