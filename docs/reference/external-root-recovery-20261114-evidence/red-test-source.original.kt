package dev.openidea.goide.lsp

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.NewVirtualFile
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.LightPlatformTestCase
import org.eclipse.lsp4j.Unregistration
import org.eclipse.lsp4j.UnregistrationParams
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Actual NativePlatform/VFS contract; no editor, gopls or native fsnotifier claim. */
class GoExternalRootRecoveryVfsTest : LightPlatformTestCase() {
    override fun runInDispatchThread() = false

    fun testOwnedColdRootRecoversAfterTheWholeDirectoryIsRemovedAndRecreated() {
        val temporary = Files.createTempDirectory("owned root recreation 中文😀 ").toRealPath()
        val directory = Files.createDirectories(temporary.resolve("external/nested" )).parent
        val source = Files.writeString(directory.resolve("nested/original.go"), "package dependency\nfunc Original() {}\n")
        val lifetime = Disposer.newDisposable("owned root recovery VFS")
        VfsRootAccess.allowRootAccess(lifetime, temporary.toString())
        val local = LocalFileSystem.getInstance()
        val watches = GoExternalFileWatches(project)
        val events = CopyOnWriteArrayList<VFileEvent>()
        val changed = CountDownLatch(1)
        val deleted = CountDownLatch(1)
        val connection = project.messageBus.connect(lifetime)
        connection.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(values: List<VFileEvent>) {
                events.addAll(values)
                if (values.any { it is VFileContentChangeEvent && Path.of(it.path) == source }) changed.countDown()
                if (values.any { it is VFileDeleteEvent && Path.of(it.path) == directory }) deleted.countDown()
            }
        })
        try {
            assertNull(local.findFileByPathIfCached(source.toString()))
            watches.registerOwned("owned-root", setOf(directory)).get(10, TimeUnit.SECONDS)
            watches.requireCoverage("owned-root", setOf(directory)).get(10, TimeUnit.SECONDS)
            val originalRoot = requireNotNull(local.findFileByPathIfCached(directory.toString()))
            val cachedSource = requireNotNull(local.findFileByPathIfCached(source.toString()))
            val cachedParent = originalRoot.parent
            assertNull(FileDocumentManager.getInstance().getCachedDocument(cachedSource))

            // Positive control: the production refresh sees changes in this cold,
            // existing tree when supplied with the test app's missing dirty marker.
            Files.writeString(source, "package dependency\nfunc ChangedAndLonger() {}\n")
            Files.setLastModifiedTime(source, FileTime.fromMillis(cachedSource.timeStamp + 2000))
            (cachedSource as NewVirtualFile).markDirty()
            watches.requestRefresh().get(10, TimeUnit.SECONDS)
            assertTrue("The existing-root control must publish a real content change: $events", changed.await(10, TimeUnit.SECONDS))

            // Retain the original bytes in a different directory. Refresh only
            // through the same production owned-root path, never its parent.
            Files.move(directory, temporary.resolve("parked-original"))
            (originalRoot as NewVirtualFile).markDirtyRecursively()
            (cachedParent as NewVirtualFile).markDirty()
            watches.requestRefresh().get(10, TimeUnit.SECONDS)
            assertTrue("An owned root that disappeared must publish its real deletion: $events", deleted.await(10, TimeUnit.SECONDS))
            assertFalse("The removed root's old VirtualFile must no longer be valid", originalRoot.isValid)

            val recreatedSource = Files.createDirectories(directory.resolve("nested")).resolve("recreated.go")
            Files.writeString(recreatedSource, "package dependency\nfunc RecreatedWithNewIdentity() {}\n")
            cachedParent.markDirty()
            watches.requestRefresh().get(10, TimeUnit.SECONDS)
            val recovered = awaitCached(local, recreatedSource)
            assertNotNull("The production refresh must discover the same-path replacement root and its unopened new Go source: $events", recovered)
            assertTrue(requireNotNull(recovered).isValid)
            assertNotSame(originalRoot, requireNotNull(local.findFileByPathIfCached(directory.toString())))
            assertNull("Reacquiring the root must not create an editor document", FileDocumentManager.getInstance().getCachedDocument(recovered))
        } finally {
            watches.unregister(UnregistrationParams(listOf(Unregistration("owned-root", "workspace/didChangeWatchedFiles")))).get(10, TimeUnit.SECONDS)
            Disposer.dispose(watches)
            Disposer.dispose(lifetime)
            temporary.toFile().deleteRecursively()
            local.refreshAndFindFileByNioFile(temporary)
        }
    }

    private fun awaitCached(local: LocalFileSystem, path: Path): VirtualFile? {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            local.findFileByPathIfCached(path.toString())?.takeIf { it.isValid }?.let { return it }
            Thread.sleep(25)
        }
        return null
    }
}
