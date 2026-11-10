package dev.openidea.goide.run

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.DocumentEx
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.EdtTestUtil
import com.intellij.testFramework.LightPlatformTestCase
import com.intellij.util.xmlb.XmlSerializerUtil
import dev.openidea.goide.lsp.GoDocumentSync
import dev.openidea.goide.settings.GoProjectSettings
import dev.openidea.goide.settings.GoToolchain
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Deterministic queue controls around the actual service; no metadata process or fake editor action. */
class GoRunBuildContextServiceTest : LightPlatformTestCase() {
    override fun runInDispatchThread() = false

    fun testLookupCoalescesAndDoesNotCaptureDocumentsOrStartMetadataInlineOnEdt() = Fixture().use { f ->
        onEdt { repeat(200) { assertEquals(GoRunBuildStatus.CHECKING, f.contexts.lookup(f.file).status) } }
        assertEquals(0, f.source.operations.size)
        assertEquals(1, f.queue.size)
        f.queue.next()
        val op = f.source.operations.single()
        assertFalse(op.createdOnEdt)
        assertFalse(op.startedOnEdt)
        assertEquals(1, op.starts)
        op.future.complete(f.snapshot(active = true)); f.queue.next()
        onEdt { repeat(200) { assertEquals(GoRunBuildStatus.ACTIVE, f.contexts.lookup(f.file).status) } }
        assertEquals(1, f.source.operations.size)
        assertEquals(0, f.queue.size)
    }

    fun testLateActiveReplyCannotReplaceTheNewSettingsIgnoredSnapshot() = Fixture().use { f ->
        f.contexts.lookup(f.file); f.queue.next()
        val old = f.source.operations.single()
        GoProjectSettings.get(project).buildTags = "new-context"
        assertEquals(GoRunBuildStatus.CHECKING, f.contexts.lookup(f.file).status)
        assertTrue(old.disposed)
        f.queue.next()
        val current = f.source.operations.last()
        assertEquals("new-context", current.request.settings.buildTags)
        old.future.complete(f.snapshot(active = true)); f.queue.next()
        assertEquals(GoRunBuildStatus.CHECKING, f.contexts.lookup(f.file).status)
        current.future.complete(f.snapshot(active = false)); f.queue.next()
        assertEquals(GoRunBuildStatus.IGNORED, f.contexts.lookup(f.file).status)
    }

    fun testPhysicalDocumentEditAndActualUndoCannotReuseAnEarlierPendingGeneration() = Fixture().use { f ->
        val document = ReadAction.compute<com.intellij.openapi.editor.Document, RuntimeException> {
            requireNotNull(FileDocumentManager.getInstance().getDocument(f.file.virtualFile))
        }
        val original = document.text
        val sequence = (document as DocumentEx).modificationSequence
        f.contexts.lookup(f.file); f.queue.next()
        val old = f.source.operations.single()
        onEdt {
            val editor = EditorFactory.getInstance().createEditor(document, project)
            try {
                WriteCommandAction.runWriteCommandAction(project) { document.insertString(0, "// dirty 中文😀\n") }
                UndoManager.getInstance(project).undo(TextEditorProvider.getInstance().getTextEditor(editor))
                assertEquals(original, document.text)
                assertTrue(document.modificationSequence > sequence)
            } finally { EditorFactory.getInstance().releaseEditor(editor) }
        }
        assertTrue(old.disposed)
        f.contexts.lookup(f.file); f.queue.next()
        val current = f.source.operations.last()
        assertNotSame(old, current)
        old.future.complete(f.snapshot(active = true)); f.queue.next()
        assertEquals(GoRunBuildStatus.CHECKING, f.contexts.lookup(f.file).status)
        current.future.complete(f.snapshot(active = true)); f.queue.next()
        assertEquals(GoRunBuildStatus.ACTIVE, f.contexts.lookup(f.file).status)
    }

    fun testDisposalBetweenOperationCreationAndRegistrationNeverStartsIt() = Fixture().use { f ->
        f.source.onCreate = { Disposer.dispose(f.contexts) }
        f.contexts.lookup(f.file); f.queue.next()
        val op = f.source.operations.single()
        assertTrue(op.disposed)
        assertEquals(0, op.starts)
        assertEquals(GoRunBuildStatus.ERROR, f.contexts.lookup(f.file).status)
    }

    fun testErrorsAreNotIgnoredAndAnExplicitRetryCanRecover() = Fixture().use { f ->
        f.contexts.lookup(f.file); f.queue.next()
        f.source.operations.single().future.completeExceptionally(IllegalStateException("Selected Go metadata failed")); f.queue.next()
        assertEquals(GoRunBuildStatus.ERROR, f.contexts.lookup(f.file).status)
        assertEquals(1, f.source.operations.size)
        f.contexts.retry(f.file); f.queue.next()
        f.source.operations.last().future.complete(f.snapshot(active = true)); f.queue.next()
        assertEquals(GoRunBuildStatus.ACTIVE, f.contexts.lookup(f.file).status)
    }

    fun testInvalidWinsOverActiveWhilePackageDiagnosticsDoNotEraseKnownMembership() = Fixture().use { f ->
        f.contexts.lookup(f.file); f.queue.next()
        f.source.operations.single().future.complete(f.snapshot(active = true).copy(invalidSourceUris = setOf(f.uri), diagnostics = listOf("InvalidGoFiles")))
        f.queue.next()
        assertEquals(GoRunBuildStatus.INVALID, f.contexts.lookup(f.file).status)
        f.contexts.retry(f.file); f.queue.next()
        f.source.operations.last().future.complete(f.snapshot(active = true).copy(diagnostics = listOf("Other package source error"))); f.queue.next()
        assertEquals(GoRunBuildStatus.ACTIVE, f.contexts.lookup(f.file).status)
    }

    fun testManualAndPackagePatternsDoNotInheritAnUnrelatedSourceSelection() = Fixture().use { f ->
        val source = GoRunSourceSelection(f.uri, "TestEntry", GoTestMode.TEST, "^TestEntry$", f.directory.toString())
        val exact = GoRunParameters(workingDirectory = f.directory.toString(), testPattern = "^TestEntry$")
        assertTrue(source.applies(exact))
        assertFalse(source.applies(exact.copy(testPattern = ".*")))
        assertFalse(source.applies(exact.copy(testPattern = "")))
        assertFalse(source.applies(exact.copy(target = "./...")))
        assertFalse(source.applies(exact.copy(testMode = GoTestMode.BENCHMARK)))
        assertTrue(source.containsEntry(FIXTURE))
        assertFalse(source.containsEntry(FIXTURE.replace("TestEntry", "TestRenamed")))
        assertTrue(source.applies(exact.copy(arguments = "-v -run ^TestEntry$ -timeout 20s")))
        assertTrue(source.applies(exact.copy(arguments = "-run ^Other$ --run=^TestEntry$")))
        assertFalse(source.applies(exact.copy(arguments = "-run=^Other$")))
        assertFalse(source.applies(exact.copy(arguments = "-run")))
        assertFalse(source.applies(exact.copy(arguments = "-args -test.run=^Other$")))
        assertTrue(source.applies(exact.copy(arguments = "-args -run custom-binary-value")))
        assertTrue(source.applies(exact.copy(arguments = "-- -test.run=^Other$")))
        assertFalse(source.applies(exact.copy(arguments = "-custom opaque -run ^TestEntry$")))
    }

    fun testLaunchPreflightDoesNotTrustTheEarlierActiveGutterSnapshot() = Fixture().use { f ->
        f.contexts.lookup(f.file); f.queue.next()
        f.source.operations.single().future.complete(f.snapshot(active = true)); f.queue.next()
        assertEquals(GoRunBuildStatus.ACTIVE, f.contexts.lookup(f.file).status)
        val pending = CompletableFuture.supplyAsync { f.contexts.verify(f.selection, f.values, GoToolchain.capture(project)) }
        f.queue.awaitNext()
        val ownPreflight = f.source.operations.last()
        assertEquals(2, f.source.operations.size)
        ownPreflight.future.complete(f.snapshot(active = false)); f.queue.next()
        val failure = runCatching { pending.get(5, TimeUnit.SECONDS) }.exceptionOrNull()
        assertNotNull("An earlier active gutter must not authorize a newly ignored exact selection", failure)
        assertTrue(failure.toString(), failure!!.cause?.message.orEmpty().contains("excluded"))
        assertTrue(ownPreflight.disposed)
    }

    fun testPreparedTokenIsInvalidAfterARealEditAndUndoEvenWhenTextReturnsToTheOriginal() = Fixture().use { f ->
        val pending = CompletableFuture.supplyAsync { f.contexts.verify(f.selection, f.values, GoToolchain.capture(project)) }
        f.queue.awaitNext()
        f.source.operations.single().future.complete(f.snapshot(active = true)); f.queue.next()
        val token = pending.get(5, TimeUnit.SECONDS)
        try {
            token.checkBeforeLaunch()
            val document = ReadAction.compute<com.intellij.openapi.editor.Document, RuntimeException> {
                requireNotNull(FileDocumentManager.getInstance().getDocument(f.file.virtualFile))
            }
            onEdt {
                val editor = EditorFactory.getInstance().createEditor(document, project)
                try {
                    WriteCommandAction.runWriteCommandAction(project) { document.insertString(0, "// transient 中文😀\n") }
                    UndoManager.getInstance(project).undo(TextEditorProvider.getInstance().getTextEditor(editor))
                    assertEquals(FIXTURE, document.text)
                } finally { EditorFactory.getInstance().releaseEditor(editor) }
            }
            assertNotNull("Undo must not revive a prepared source token", runCatching { token.checkCurrent() }.exceptionOrNull())
        } finally { token.dispose() }
    }

    fun testCancellingAHeldLaunchPreflightDisposesOnlyItsOperationAndCannotPublishALateToken() = Fixture().use { f ->
        val cancelled = AtomicBoolean()
        val pending = CompletableFuture.supplyAsync { f.contexts.verify(f.selection, f.values, GoToolchain.capture(project), cancelled = cancelled::get) }
        f.queue.awaitNext()
        val ownPreflight = f.source.operations.single()
        cancelled.set(true)
        val failure = runCatching { pending.get(5, TimeUnit.SECONDS) }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(ownPreflight.disposed)
        ownPreflight.future.complete(f.snapshot(active = true)); f.queue.next()
        assertTrue(pending.isCompletedExceptionally)
    }

    fun testDirtyExplicitMetadataBufferInvalidatesAKnownWatchedInputWithANonstandardFileName() = Fixture().use { f ->
        val path = f.directory.resolve("custom.mod")
        Files.writeString(path, "module example.org/custom\n\ngo 1.26.10\n")
        val file = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path))
        val document = ReadAction.compute<com.intellij.openapi.editor.Document, RuntimeException> {
            requireNotNull(FileDocumentManager.getInstance().getDocument(file))
        }
        f.contexts.lookup(f.file); f.queue.next()
        val uri = GoDocumentSync.fileUri(path)
        f.source.operations.single().future.complete(f.snapshot(active = true).copy(watchedFileUris = setOf(f.uri, uri)))
        f.queue.next()
        assertEquals(GoRunBuildStatus.ACTIVE, f.contexts.lookup(f.file).status)
        onEdt { WriteCommandAction.runWriteCommandAction(project) { document.setText("module example.org/custom\n\ngo 1.26.10\n// dirty 中文😀\n") } }
        assertEquals("Known Go metadata remains watched even when the filename is not go.mod", GoRunBuildStatus.CHECKING, f.contexts.lookup(f.file).status)
        f.queue.next()
        assertEquals(document.text, f.source.operations.last().request.overlays.single { it.uri == uri }.text)
    }

    fun testNativeVendorDirectoryCreationAndKnownWholeDirectoryChangesInvalidateWithoutWatchingEveryAncestorFile() = Fixture().use { f ->
        val virtualDirectory = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(f.directory))
        // Refresh events describe changes to an already known directory snapshot.
        // Merely discovering an uncached child need not publish a creation event.
        ReadAction.run<RuntimeException> { virtualDirectory.children }
        f.contexts.lookup(f.file); f.queue.next()
        f.source.operations.single().future.complete(f.snapshot(active = true)); f.queue.next()
        val unrelated = f.directory.resolve("notes.txt")
        Files.writeString(unrelated, "An ordinary sibling text file is not Go build metadata")
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(unrelated)
        assertEquals(GoRunBuildStatus.ACTIVE, f.contexts.lookup(f.file).status)

        val vendor = Files.createDirectory(f.directory.resolve("vendor"))
        virtualDirectory.refresh(false, false)
        assertEquals("A new native vendor directory changes the Go build context", GoRunBuildStatus.CHECKING, f.contexts.lookup(f.file).status)
        f.queue.next()
        f.source.operations.last().future.complete(f.snapshot(active = true).copy(watchedAllDirectoryUris = setOf(GoDocumentSync.fileUri(vendor))))
        f.queue.next()
        assertEquals(GoRunBuildStatus.ACTIVE, f.contexts.lookup(f.file).status)
        val virtualVendor = requireNotNull(LocalFileSystem.getInstance().findFileByNioFile(vendor))
        ReadAction.run<RuntimeException> { virtualVendor.children }
        val created = vendor.resolve("modules.txt")
        Files.writeString(created, "# owned newly created vendor metadata\n")
        virtualVendor.refresh(false, false)
        assertEquals("Whole vendor directory guards include newly created children", GoRunBuildStatus.CHECKING, f.contexts.lookup(f.file).status)
    }

    fun testAPlatformMoveEventIntoThePackageChecksItsNewPathAsWellAsTheOldPath() = Fixture().use { f ->
        val elsewhere = Files.createDirectory(f.directory.resolve("elsewhere"))
        val incoming = elsewhere.resolve("incoming_test.go")
        Files.writeString(incoming, "package fixture\n")
        val virtual = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(incoming))
        val destination = requireNotNull(LocalFileSystem.getInstance().findFileByNioFile(f.directory))
        f.contexts.lookup(f.file); f.queue.next()
        f.source.operations.single().future.complete(f.snapshot(active = true)); f.queue.next()
        assertEquals(GoRunBuildStatus.ACTIVE, f.contexts.lookup(f.file).status)
        // Exercise the actual SDK event's distinct old/new paths through the
        // project's subscribed topic. This controlled event does not move the
        // disk file or notify the application's PSI filesystem mutation listener.
        val moved = VFileMoveEvent(this, virtual, destination)
        assertFalse(moved.oldPath == moved.newPath)
        onEdt { com.intellij.openapi.application.WriteAction.run<RuntimeException> {
            project.messageBus.syncPublisher(VirtualFileManager.VFS_CHANGES).after(listOf(moved))
        } }
        assertEquals("A Go file entering the package cannot keep an old membership snapshot", GoRunBuildStatus.CHECKING, f.contexts.lookup(f.file).status)
    }

    private inner class Fixture : AutoCloseable {
        val directory = Files.createTempDirectory("owned context lifecycle 中文😀 ").toRealPath()
        private val lifetime = Disposer.newDisposable("owned source entry roots")
        private val before = GoProjectSettings.Data().also { XmlSerializerUtil.copyBean(GoProjectSettings.get(project), it) }
        val file: PsiFile
        val uri = GoDocumentSync.fileUri(directory.resolve("entry_test.go"))
        val queue = Queue()
        val source = ControlledSource()
        val contexts: GoRunBuildContexts
        val selection get() = GoRunSourceSelection(uri, "TestEntry", GoTestMode.TEST, "^TestEntry$", directory.toString())
        val values get() = GoRunParameters(workingDirectory = directory.toString(), testPattern = "^TestEntry$")
        init {
            VfsRootAccess.allowRootAccess(lifetime, directory.toString())
            Files.writeString(directory.resolve("entry_test.go"), FIXTURE)
            val virtual = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(directory.resolve("entry_test.go")))
            file = ReadAction.compute<PsiFile, RuntimeException> { requireNotNull(PsiManager.getInstance(project).findFile(virtual)) }
            contexts = GoRunBuildContexts(project, source, refresh = {}, executor = queue)
        }
        fun snapshot(active: Boolean) = GoRunBuildContextSnapshot(uri, FIXTURE, "example.org/context", GoDocumentSync.fileUri(directory),
            if (active) setOf(uri) else emptySet(), if (active) emptySet() else setOf(uri), setOf(uri), setOf(GoDocumentSync.fileUri(directory)), {})
        override fun close() {
            Disposer.dispose(contexts)
            onEdt { FileDocumentManager.getInstance().saveAllDocuments() }
            XmlSerializerUtil.copyBean(before, GoProjectSettings.get(project))
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(directory)
            Disposer.dispose(lifetime)
        }
    }
    private class Queue : Executor {
        private val pending = ConcurrentLinkedQueue<Runnable>()
        val size get() = pending.size
        override fun execute(command: Runnable) { pending += command }
        fun next() { check(!ApplicationManager.getApplication().isDispatchThread); requireNotNull(pending.poll()) { "No scheduled background step" }.run() }
        fun awaitNext() {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (pending.isEmpty()) { check(System.nanoTime() < deadline) { "No source verification scheduled" }; Thread.sleep(5) }
            next()
        }
    }
    private class ControlledSource : GoRunBuildContextSource {
        val operations = mutableListOf<Operation>()
        var onCreate: (() -> Unit)? = null
        override fun create(request: GoRunBuildContextRequest): GoRunBuildContextOperation {
            val op = Operation(request).also(operations::add); onCreate?.invoke(); return op
        }
        override fun dispose() { operations.forEach { it.dispose() } }
    }
    private class Operation(val request: GoRunBuildContextRequest) : GoRunBuildContextOperation {
        override val future = CompletableFuture<GoRunBuildContextSnapshot>()
        val createdOnEdt = ApplicationManager.getApplication().isDispatchThread
        var startedOnEdt = false
        var starts = 0
        var disposed = false
        override fun start() { startedOnEdt = ApplicationManager.getApplication().isDispatchThread; if (!disposed) starts++ }
        // A queued server reply can still arrive after cancellation; only the real service may publish it.
        override fun dispose() { disposed = true }
    }
    private fun onEdt(action: () -> Unit) = EdtTestUtil.runInEdtAndWait<RuntimeException> { WriteIntentReadAction.run(Runnable(action)) }
    companion object { private const val FIXTURE = "package fixture\nimport \"testing\"\nfunc TestEntry(t *testing.T) {}\n" }
}
