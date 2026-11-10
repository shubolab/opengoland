package dev.openidea.goide.refactor

import com.intellij.history.LocalHistory
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.command.UndoConfirmationPolicy
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.EdtTestUtil
import com.intellij.testFramework.LightPlatformTestCase
import dev.openidea.goide.lsp.GoDocumentSync
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.RenameFile
import org.eclipse.lsp4j.ResourceOperation
import org.eclipse.lsp4j.SnippetTextEdit
import org.eclipse.lsp4j.TextDocumentEdit
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.jsonrpc.messages.Either
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** Real local VFS, native documents, Local History and the platform Undo providers. */
class GoWorkspaceEditNativeCaseTest : LightPlatformTestCase() {
    // The real filesystem setup/preload stays off the EDT. Only the native command/Undo runs there.
    override fun runInDispatchThread() = false

    fun testFileCaseRenameAndFollowingTextEditShareOneNativeUndoRedo() = exercise(directory = false)
    fun testDirectoryCaseRenameAndChildTextEditShareOneNativeUndoRedo() = exercise(directory = true)
    fun testNewSpellingMentionedFirstKeepsNativeIdentityAndOneUndoRedo() = exercise(directory = false, mentionNewFirst = true)

    private fun exercise(directory: Boolean, mentionNewFirst: Boolean = false) {
        val root = Files.createTempDirectory("opengoland-native-case-").toRealPath()
        val disposable = Disposer.newDisposable("native case rename event listener")
        VfsRootAccess.allowRootAccess(disposable, root.toString())
        var rootFile: VirtualFile? = null
        var document: Document? = null
        try {
            val old = root.resolve(if (directory) "pkg" else "name.go")
            val new = root.resolve(if (directory) "Pkg" else "Name.go")
            val originalText = "package fixture\nvar Value = 1\n"
            if (directory) Files.createDirectory(old)
            val oldChild = if (directory) old.resolve("model.go") else old
            val newChild = if (directory) new.resolve("model.go") else new
            Files.writeString(oldChild, originalText)
            val caseInsensitive = Files.exists(new)
            val changes = buildList {
                if (mentionNewFirst) add(Either.forLeft<TextDocumentEdit, ResourceOperation>(TextDocumentEdit(VersionedTextDocumentIdentifier(uri(newChild), null),
                    listOf(Either.forLeft<TextEdit, SnippetTextEdit>(TextEdit(Range(Position(1, 4), Position(1, 9)), "Middle"))))))
                add(resource(RenameFile(uri(old), uri(new))))
                add(Either.forLeft<TextDocumentEdit, ResourceOperation>(TextDocumentEdit(VersionedTextDocumentIdentifier(uri(newChild), null),
                    listOf(Either.forLeft<TextEdit, SnippetTextEdit>(TextEdit(Range(Position(1, 4), Position(1, if (mentionNewFirst) 10 else 9)), "Renamed"))))))
            }
            val value = WorkspaceEdit(changes)
            val aliases = GoWorkspaceEditCaseAliases.detect(value)
            assertEquals("The actual filesystem must decide whether this is a case alias", caseInsensitive, aliases.isNotEmpty())
            val node = GoEditNode(false, originalText.toByteArray(), originalText, oldChild)
            val initial = linkedMapOf(root to GoEditNode(true))
            if (mentionNewFirst && caseInsensitive) initial[newChild] = node
            initial[oldChild] = node
            if (directory) initial[old] = GoEditNode(true, origin = old)
            if (caseInsensitive) {
                initial[newChild] = node
                if (directory) initial[new] = initial.getValue(old)
            }
            if (mentionNewFirst && !caseInsensitive) {
                // Two case-sensitive spellings are different resources: editing
                // the not-yet-created target must be refused before mutation.
                kotlin.test.assertFailsWith<GoWorkspaceEditRejected> {
                    GoWorkspaceEditPlan.build(value, initial, { null }, aliases) { it.startsWith(root) }
                }
                assertEquals(originalText, Files.readString(oldChild))
                return
            }
            val plan = GoWorkspaceEditPlan.build(value, initial, { null }, aliases) { it.startsWith(root) }
            val local = LocalFileSystem.getInstance()
            val rootVirtual = local.refreshAndFindFileByNioFile(root)!!
            rootFile = rootVirtual
            val originalFile = local.refreshAndFindFileByNioFile(old)!!
            val originalChild = local.refreshAndFindFileByNioFile(oldChild)!!
            rootVirtual.children
            if (directory) originalFile.children
            val doc = ReadAction.compute<Document, RuntimeException> { FileDocumentManager.getInstance().getDocument(originalChild)!! }
            document = doc
            val history = LocalHistory.getInstance()
            var deletions = 0
            onEdt {
                ModuleRootModificationUtil.addContentRoot(module, rootVirtual.path)
                assertTrue("Native Local History must be available for file Undo", history.isEnabled)
                assertTrue("The test resource must be covered by native file Undo", history.isUnderControl(originalFile))
                project.messageBus.connect(disposable).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
                    override fun after(events: List<VFileEvent>) { deletions += events.count { it is VFileDeleteEvent } }
                })
                val undo = UndoManager.getInstance(project)
                WriteCommandAction.writeCommandAction(project).withName("Case rename with text").withGroupId(UUID.randomUUID().toString()).withGlobalUndo()
                    .withUndoConfirmationPolicy(UndoConfirmationPolicy.DO_NOT_REQUEST_CONFIRMATION).run<RuntimeException> {
                        GoWorkspaceEditNativeExecutor(this, mapOf(oldChild to doc)).execute(plan, aliases, GoWorkspaceEditJournal())
                    }
                assertEquals(new.fileName.toString(), originalFile.name)
                assertEquals("package fixture\nvar Renamed = 1\n", doc.text)
                assertEquals(originalChild, local.findFileByNioFile(newChild))
                assertEquals(originalChild, FileDocumentManager.getInstance().getFile(doc))
                assertTrue("The combined resource+document command must be globally undoable", undo.isUndoAvailable(null))

                undo.undo(null)
                assertEquals(old.fileName.toString(), originalFile.name)
                assertEquals(originalText, doc.text)
                assertEquals(originalChild, local.findFileByNioFile(oldChild))
                assertTrue(undo.isRedoAvailable(null))

                undo.redo(null)
                assertEquals(new.fileName.toString(), originalFile.name)
                assertEquals("package fixture\nvar Renamed = 1\n", doc.text)
                assertEquals(originalChild, local.findFileByNioFile(newChild))
                assertTrue(originalFile.isValid && originalChild.isValid)
                assertEquals("Case-only rename must never delete/overwrite a target", 0, deletions)
            }
        } finally {
            try {
            val virtual = rootFile
            val rootUrl = virtual?.url
            onEdt {
                PsiDocumentManager.getInstance(project).commitAllDocuments()
                WriteCommandAction.runWriteCommandAction(project) {
                    document?.let { FileDocumentManager.getInstance().saveDocument(it) }
                    if (virtual?.isValid == true) virtual.delete(this)
                }
                ModuleRootModificationUtil.updateModel(module) { model ->
                    model.contentEntries.filter { it.url == rootUrl }.forEach(model::removeContentEntry)
                }
            }
            if (Files.exists(root)) Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
            } finally { Disposer.dispose(disposable) }
        }
    }

    private fun onEdt(action: () -> Unit) = EdtTestUtil.runInEdtAndWait<RuntimeException> { WriteIntentReadAction.run(Runnable(action)) }
    private fun uri(path: Path) = GoDocumentSync.fileUri(path)
    private fun resource(value: ResourceOperation): Either<TextDocumentEdit, ResourceOperation> = Either.forRight(value)
}
