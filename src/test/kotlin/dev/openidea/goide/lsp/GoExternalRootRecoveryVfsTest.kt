package dev.openidea.goide.lsp

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Actual NativePlatform/VFS contract; no editor, gopls or native fsnotifier claim. */
class GoExternalRootRecoveryVfsTest : LightPlatformTestCase() {
    override fun runInDispatchThread() = false

    fun testOwnedColdRootRecoversAfterTheWholeDirectoryIsRemovedAndRecreated() {
        val temporary = Files.createTempDirectory("owned root recreation 中文😀 ").toRealPath()
        val directory = Files.createDirectories(temporary.resolve("external/nested")).parent
        val source = Files.writeString(directory.resolve("nested/original.go"), "package dependency\nfunc Original() {}\n")
        val lifetime = Disposer.newDisposable("owned root recovery VFS")
        VfsRootAccess.allowRootAccess(lifetime, temporary.toString())
        val local = LocalFileSystem.getInstance()
        val removedScope = CountDownLatch(1)
        val recoveredScope = CountDownLatch(1)
        val transitions = CopyOnWriteArrayList<Set<Path>>()
        val watches = GoExternalFileWatches(project, rootsChanged = { paths ->
            check(!ApplicationManager.getApplication().isDispatchThread)
            transitions.add(paths)
            if (transitions.size == 1) removedScope.countDown() else recoveredScope.countDown()
            CompletableFuture.completedFuture(null)
        })
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
            assertTrue("The missing directory must enqueue reconciliation", removedScope.await(10, TimeUnit.SECONDS))
            assertNotNull("An invalid root is not actual watch coverage", runCatching {
                watches.requireCoverage("owned-root", setOf(directory)).get(10, TimeUnit.SECONDS)
            }.exceptionOrNull())

            val recreatedSource = Files.createDirectories(directory.resolve("nested")).resolve("recreated.go")
            Files.writeString(recreatedSource, "package dependency\nfunc RecreatedWithNewIdentity() {}\n")
            cachedParent.markDirty()
            watches.requestRefresh().get(10, TimeUnit.SECONDS)
            val recovered = awaitCached(local, recreatedSource)
            assertNotNull("The production refresh must discover the same-path replacement root and its unopened new Go source: $events", recovered)
            assertTrue(requireNotNull(recovered).isValid)
            assertNotSame(originalRoot, requireNotNull(local.findFileByPathIfCached(directory.toString())))
            assertNull("Reacquiring the root must not create an editor document", FileDocumentManager.getInstance().getCachedDocument(recovered))
            assertTrue("The newly primed incarnation must enqueue reconciliation", recoveredScope.await(10, TimeUnit.SECONDS))
            assertEquals(listOf(setOf(directory), setOf(directory)), transitions.toList())
            watches.requireCoverage("owned-root", setOf(directory)).get(10, TimeUnit.SECONDS)
        } finally {
            watches.unregister(UnregistrationParams(listOf(Unregistration("owned-root", "workspace/didChangeWatchedFiles")))).get(10, TimeUnit.SECONDS)
            Disposer.dispose(watches)
            Disposer.dispose(lifetime)
            temporary.toFile().deleteRecursively()
            local.refreshAndFindFileByNioFile(temporary)
        }
    }

    fun testFastReplacementPrimesNewChildrenEvenWhileTheOldVirtualRootIsValid() {
        val temporary = Files.createTempDirectory("owned root fast replacement ").toRealPath()
        val directory = Files.createDirectories(temporary.resolve("external/nested")).parent
        val source = Files.writeString(directory.resolve("nested/old.go"), "package dependency\nfunc Old() {}\n")
        val lifetime = Disposer.newDisposable("owned fast root replacement VFS")
        VfsRootAccess.allowRootAccess(lifetime, temporary.toString())
        val local = LocalFileSystem.getInstance()
        val reconciled = CountDownLatch(1)
        val watches = GoExternalFileWatches(project, rootsChanged = { paths ->
            assertEquals(setOf(directory), paths)
            reconciled.countDown()
            CompletableFuture.completedFuture(null)
        })
        try {
            watches.registerOwned("owned-root", setOf(directory)).get(10, TimeUnit.SECONDS)
            val oldRoot = requireNotNull(local.findFileByPathIfCached(directory.toString()))
            assertNotNull(local.findFileByPathIfCached(source.toString()))
            val replacement = directory.resolve("brand/new/recreated.go")
            ReadAction.run<RuntimeException> {
                // Hold only the test's VFS read generation while making this
                // tiny NIO swap, so a timer cannot invalidate the positive control.
                Files.move(directory, temporary.resolve("parked-original"))
                Files.createDirectories(replacement.parent)
                Files.writeString(replacement, "package dependency\nfunc NewIncarnation() {}\n")
                assertFalse(Files.isSameFile(directory, temporary.resolve("parked-original")))
                assertTrue("The replacement branch must not depend on an invalid VirtualFile", oldRoot.isValid)
                (oldRoot as NewVirtualFile).markDirtyRecursively()
            }
            watches.requestRefresh().get(10, TimeUnit.SECONDS)
            val recovered = awaitCached(local, replacement)
            assertNotNull("New children of a different directory incarnation must be primed", recovered)
            assertNull(FileDocumentManager.getInstance().getCachedDocument(requireNotNull(recovered)))
            assertTrue("The replacement must enqueue reconciliation after priming", reconciled.await(10, TimeUnit.SECONDS))
            watches.requireCoverage("owned-root", setOf(directory)).get(10, TimeUnit.SECONDS)
        } finally {
            watches.unregister(UnregistrationParams(listOf(Unregistration("owned-root", "workspace/didChangeWatchedFiles")))).get(10, TimeUnit.SECONDS)
            Disposer.dispose(watches)
            Disposer.dispose(lifetime)
            temporary.toFile().deleteRecursively()
            local.refreshAndFindFileByNioFile(temporary)
        }
    }

    fun testMovedVirtualRootDoesNotRefreshTheParkedTreeOutsideItsFixedPath() {
        val temporary = Files.createTempDirectory("owned root VFS move ").toRealPath()
        val directory = Files.createDirectory(temporary.resolve("external"))
        val source = Files.writeString(directory.resolve("dependency.go"), "package dependency\nfunc Original() {}\n")
        val lifetime = Disposer.newDisposable("owned moved root VFS")
        VfsRootAccess.allowRootAccess(lifetime, temporary.toString())
        val local = LocalFileSystem.getInstance()
        val reconciled = CountDownLatch(1)
        val events = CopyOnWriteArrayList<VFileEvent>()
        val connection = project.messageBus.connect(lifetime)
        connection.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(values: List<VFileEvent>) { events.addAll(values) }
        })
        val watches = GoExternalFileWatches(project, rootsChanged = { paths ->
            assertEquals("Only the registered fixed path may be reconciled", setOf(directory), paths)
            reconciled.countDown()
            CompletableFuture.completedFuture(null)
        })
        try {
            watches.registerOwned("owned-root", setOf(directory)).get(10, TimeUnit.SECONDS)
            val oldRoot = requireNotNull(local.findFileByPathIfCached(directory.toString()))
            val cachedSource = requireNotNull(local.findFileByPathIfCached(source.toString()))
            ApplicationManager.getApplication().invokeAndWait {
                WriteAction.run<RuntimeException> { oldRoot.rename(this, "parked") }
            }
            assertTrue("A VFS move keeps this positive control alive", oldRoot.isValid)
            assertEquals(temporary.resolve("parked"), Path.of(oldRoot.path))
            assertFalse(Path.of(oldRoot.path) == directory)

            // Drain the fixed-root transition first, so an older queued refresh
            // cannot race the later parked-only mutation. This uses only the
            // production owned-root refresh, never a parent or global refresh.
            watches.requestRefresh().get(10, TimeUnit.SECONDS)
            assertTrue(reconciled.await(10, TimeUnit.SECONDS))
            awaitRefreshIdle(watches, directory)
            val cachedStamp = cachedSource.timeStamp
            val parkedSource = Path.of(cachedSource.path)
            assertTrue(parkedSource.startsWith(temporary.resolve("parked")))
            Files.writeString(parkedSource, "package dependency\nfunc ParkedChangedAndLonger() {}\n")
            Files.setLastModifiedTime(parkedSource, FileTime.fromMillis(cachedStamp + 2000))
            (cachedSource as NewVirtualFile).markDirty()
            events.clear()
            watches.requestRefresh().get(10, TimeUnit.SECONDS)
            awaitRefreshIdle(watches, directory)
            assertFalse("The fixed-path owner must not publish parked-tree content changes: $events", events.any {
                it is VFileContentChangeEvent && Path.of(it.path) == parkedSource
            })
            assertEquals("The parked source must remain unrefreshed by this owner", cachedStamp, cachedSource.timeStamp)
            assertTrue(oldRoot.isValid)
            assertNull(FileDocumentManager.getInstance().getCachedDocument(cachedSource))
        } finally {
            watches.unregister(UnregistrationParams(listOf(Unregistration("owned-root", "workspace/didChangeWatchedFiles")))).get(10, TimeUnit.SECONDS)
            Disposer.dispose(watches)
            Disposer.dispose(lifetime)
            temporary.toFile().deleteRecursively()
            local.refreshAndFindFileByNioFile(temporary)
        }
    }

    private fun awaitRefreshIdle(watches: GoExternalFileWatches, directory: Path) {
        // Waiting only: assertions above exercise real VFS state/events. The
        // requestRefresh future covers enqueueing, not asynchronous completion.
        val pending = GoExternalFileWatches::class.java.getDeclaredField("refreshPending").apply { isAccessible = true }
            .get(watches) as AtomicBoolean
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (pending.get() && System.nanoTime() < deadline) Thread.sleep(25)
        assertFalse("The corresponding production refresh must have fully completed", pending.get())
        watches.registerOwned("owned-root", setOf(directory)).get(10, TimeUnit.SECONDS)
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
