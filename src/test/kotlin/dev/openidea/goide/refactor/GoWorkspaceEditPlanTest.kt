package dev.openidea.goide.refactor

import dev.openidea.goide.lsp.GoDocumentSync
import dev.openidea.goide.lsp.GoSyncedDocument
import org.eclipse.lsp4j.AnnotatedTextEdit
import org.eclipse.lsp4j.ChangeAnnotation
import org.eclipse.lsp4j.CreateFile
import org.eclipse.lsp4j.CreateFileOptions
import org.eclipse.lsp4j.DeleteFile
import org.eclipse.lsp4j.DeleteFileOptions
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.RenameFile
import org.eclipse.lsp4j.RenameFileOptions
import org.eclipse.lsp4j.ResourceOperation
import org.eclipse.lsp4j.TextDocumentEdit
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.jsonrpc.messages.Either
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GoWorkspaceEditPlanTest {
    private val root = Path.of("/workspace")
    private val file = root.resolve("a.go")

    @Test fun utf16PositionsSupportEmojiChineseAndEveryLspLineEnding() {
        val text = "中文😀x\r\nsecond\rthird\n"
        assertEquals("中文OKx\r\nsecond\rthird\n", GoWorkspaceEditPlan.applyText(text, listOf(edit(0, 2, 0, 4, "OK"))))
        assertEquals(text.indexOf("second") + 2, GoWorkspaceEditPlan.offset(text, Position(1, 2)))
        assertEquals(text.indexOf("third") + 3, GoWorkspaceEditPlan.offset(text, Position(2, 3)))
        assertEquals(text.length, GoWorkspaceEditPlan.offset(text, Position(3, 0)))
        listOf(Position(-1, 0), Position(0, -1), Position(0, 3), Position(0, 6), Position(4, 0)).forEach {
            assertFailsWith<GoWorkspaceEditRejected> { GoWorkspaceEditPlan.offset(text, it) }
        }
    }

    @Test fun samePositionInsertionsPreserveWireOrderAndMayPrecedeOneReplacement() {
        val edits = listOf(edit(0, 1, 0, 1, "A"), edit(0, 1, 0, 1, "B"), edit(0, 1, 0, 3, "X"))
        assertEquals("aABXd", GoWorkspaceEditPlan.applyText("abcd", edits))
        assertEquals("aABbcd", GoWorkspaceEditPlan.applyText("abcd", edits.take(2)))
        assertFailsWith<GoWorkspaceEditRejected> { GoWorkspaceEditPlan.applyText("abcd", listOf(edits.last(), edits.first())) }
        assertFailsWith<GoWorkspaceEditRejected> { GoWorkspaceEditPlan.applyText("abcd", listOf(edit(0, 0, 0, 3, "x"), edit(0, 2, 0, 4, "y"))) }
        assertFailsWith<GoWorkspaceEditRejected> { GoWorkspaceEditPlan.applyText("abcd", listOf(edit(0, 3, 0, 1, "x"))) }
        assertEquals("XY", GoWorkspaceEditPlan.applyText("abcd", listOf(edit(0, 2, 0, 4, "Y"), edit(0, 0, 0, 2, "X"))))
    }

    @Test fun versionedEditsRequireActualMirrorVersionAndText() {
        val tree = initial(file to node("old"))
        val value = WorkspaceEdit(listOf(document(file, 7, listOf(edit(0, 0, 0, 3, "new")))))
        val plan = build(value, tree) { GoSyncedDocument(7, "old", 42) }
        assertEquals("new", GoWorkspaceEditPlan.text(plan.after.getValue(file)))
        assertFailsWith<GoWorkspaceEditRejected> { build(value, tree) { GoSyncedDocument(6, "old", 42) } }
        assertFailsWith<GoWorkspaceEditRejected> { build(value, tree) { null } }
        assertFailsWith<GoWorkspaceEditRejected> { build(value, tree) { GoSyncedDocument(7, "different", 42) } }
        assertEquals("old", GoWorkspaceEditPlan.text(tree.getValue(file)), "Planning must leave its source snapshot untouched")
    }

    @Test fun repeatedVersionedEditsDoNotInventAnIntermediateLspVersionIncludingAcrossRename() {
        val tree = initial(file to node("old"))
        for (secondVersion in listOf(7, 8)) {
            val value = WorkspaceEdit(listOf(document(file, 7, listOf(edit(0, 0, 0, 3, "new"))),
                document(file, secondVersion, listOf(edit(0, 0, 0, 3, "end")))))
            assertFailsWith<GoWorkspaceEditRejected> { build(value, tree) { GoSyncedDocument(7, "old", 42) } }
        }
        val moved = root.resolve("moved.go")
        val renamed = WorkspaceEdit(listOf(document(file, 7, listOf(edit(0, 0, 0, 3, "new"))),
            resource(RenameFile(uri(file), uri(moved))), document(moved, 8, listOf(edit(0, 0, 0, 3, "end")))))
        assertFailsWith<GoWorkspaceEditRejected> { build(renamed, tree) { GoSyncedDocument(7, "old", 42) } }
    }

    @Test fun goplsClosedVersionZeroRequiresAnExplicitUnchangedDiskBaseline() {
        val disk = GoEditDiskStamp(3, "sha-of-old", 1000, "file-key")
        val closed = node("old").copy(disk = disk, closedOnDisk = true)
        val value = WorkspaceEdit(listOf(document(file, 0, listOf(edit(0, 0, 0, 3, "new")))))
        val plan = build(value, initial(file to closed))
        assertEquals("new", GoWorkspaceEditPlan.text(plan.after.getValue(file)))
        assertFailsWith<GoWorkspaceEditRejected> { build(value, initial(file to closed.copy(closedOnDisk = false))) }
        assertFailsWith<GoWorkspaceEditRejected> { build(value, initial(file to closed.copy(disk = null))) }
        assertFailsWith<GoWorkspaceEditRejected> { build(value, initial(file to closed.copy(text = "buf"))) }
        val nonzero = WorkspaceEdit(listOf(document(file, 7, listOf(edit(0, 0, 0, 3, "new")))))
        assertFailsWith<GoWorkspaceEditRejected> { build(nonzero, initial(file to closed)) }
        // An actual open overlay can also legitimately start at version 0; its mirror remains authoritative.
        assertEquals("new", GoWorkspaceEditPlan.text(build(value, initial(file to node("old"))) { GoSyncedDocument(0, "old", 42) }.after.getValue(file)))
    }

    @Test fun aClosedVersionZeroFollowsItsOriginalDiskIdentityAcrossFolderRename() {
        val folder = root.resolve("old")
        val old = folder.resolve("a.go")
        val target = root.resolve("new")
        val closed = node("old", old).copy(disk = GoEditDiskStamp(3, "sha-of-old", 1000, "file-key"), closedOnDisk = true)
        val value = WorkspaceEdit(listOf(resource(RenameFile(uri(folder), uri(target))), document(target.resolve("a.go"), 0, listOf(edit(0, 0, 0, 3, "new")))))
        assertEquals("new", GoWorkspaceEditPlan.text(build(value, initial(folder to GoEditNode(true), old to closed)).after.getValue(target.resolve("a.go"))))
    }

    @Test fun createTextRenameAcrossDirectoriesAndDeleteAreAppliedInProtocolOrder() {
        val created = root.resolve("new.go")
        val moved = root.resolve("nested/pkg/moved.go")
        val removed = root.resolve("remove.bin")
        val tree = initial(removed to GoEditNode(false, byteArrayOf(0, 1, 2), origin = removed))
        val value = WorkspaceEdit(listOf(
            resource(CreateFile(uri(created))), document(created, null, listOf(edit(0, 0, 0, 0, "package fixture\n"))),
            resource(RenameFile(uri(created), uri(moved))), resource(DeleteFile(uri(removed))),
        ))
        val plan = build(value, tree)
        assertFalse(created in plan.after)
        assertFalse(removed in plan.after)
        assertEquals("package fixture\n", GoWorkspaceEditPlan.text(plan.after.getValue(moved)))
        assertTrue(plan.after.getValue(root.resolve("nested/pkg")).directory)
        assertTrue(plan.steps.any { it is GoEditStep.Rename && it.old == created && it.new == moved })
        assertTrue(tree.containsKey(removed))
    }

    @Test fun folderRenamePreservesDescendantsAndTheirOriginalVersionedDocumentIdentity() {
        val folder = root.resolve("old")
        val original = folder.resolve("a.go")
        val target = root.resolve("deep/new")
        val renamed = target.resolve("a.go")
        val tree = initial(folder to GoEditNode(true), original to node("before", original))
        val value = WorkspaceEdit(listOf(resource(RenameFile(uri(folder), uri(target))), document(renamed, 4, listOf(edit(0, 0, 0, 6, "after")))))
        val plan = build(value, tree) { path -> if (path == original) GoSyncedDocument(4, "before", 1) else null }
        assertFalse(folder in plan.after)
        assertFalse(original in plan.after)
        assertEquals(original, plan.after.getValue(renamed).origin)
        assertEquals("after", GoWorkspaceEditPlan.text(plan.after.getValue(renamed)))
        assertFailsWith<GoWorkspaceEditRejected> { build(WorkspaceEdit(listOf(resource(RenameFile(uri(folder), uri(folder.resolve("inside")))))), tree) }
    }

    @Test fun resourceOptionsHonorOverwriteBeforeIgnoreAndRecursiveDelete() {
        val target = root.resolve("b.go")
        val tree = initial(file to node("source"), target to node("target", target))
        val ignored = build(WorkspaceEdit(listOf(resource(CreateFile(uri(file), CreateFileOptions(false, true))))), tree)
        assertTrue(ignored.steps.isEmpty())
        val replaced = build(WorkspaceEdit(listOf(resource(CreateFile(uri(file), CreateFileOptions(true, true))))), tree)
        assertEquals("", GoWorkspaceEditPlan.text(replaced.after.getValue(file)))
        val renamed = build(WorkspaceEdit(listOf(resource(RenameFile(uri(file), uri(target), RenameFileOptions(true, true))))), tree)
        assertEquals("source", GoWorkspaceEditPlan.text(renamed.after.getValue(target)))
        val directory = root.resolve("pkg")
        val nested = directory.resolve("file.go")
        val folders = initial(directory to GoEditNode(true), nested to node("data", nested))
        val failure = assertFailsWith<GoWorkspaceEditRejected> { build(WorkspaceEdit(listOf(resource(DeleteFile(uri(directory))))), folders) }
        assertEquals(0, failure.failedChange)
        val deleted = build(WorkspaceEdit(listOf(resource(DeleteFile(uri(directory), DeleteFileOptions(true, false))))), folders)
        assertFalse(deleted.after.keys.any { it.startsWith(directory) })
        assertTrue(build(WorkspaceEdit(listOf(resource(DeleteFile(uri(root.resolve("missing")), DeleteFileOptions(false, true))))), folders).steps.isEmpty())
    }

    @Test fun textPayloadsSupportBothSdkAndLsp4ijListRepresentationsAndForceAnnotatedPreview() {
        val change = AnnotatedTextEdit(Range(Position(0, 0), Position(0, 3)), "new", "confirm")
        val value = WorkspaceEdit(listOf(document(file, null, listOf(change)))).apply {
            changeAnnotations = mapOf("confirm" to ChangeAnnotation("API rename").apply { needsConfirmation = true })
        }
        assertTrue(build(value, initial(file to node("old"))).requiresConfirmation)
        val text = value.documentChanges.single().left
        @Suppress("UNCHECKED_CAST")
        text.edits = listOf(Either.forLeft<TextEdit, String>(TextEdit(Range(Position(0, 0), Position(0, 3)), "new"))) as List<TextEdit>
        assertEquals("new", GoWorkspaceEditPlan.text(build(value, initial(file to node("old"))).after.getValue(file)))
        @Suppress("UNCHECKED_CAST")
        text.edits = listOf(Either.forRight<TextEdit, String>("unsupported snippet")) as List<TextEdit>
        assertFailsWith<GoWorkspaceEditRejected> { build(value, initial(file to node("old"))) }
    }

    @Test fun invalidUriMixedFormatsAndOutOfWorkspaceResourcesAreRejectedBeforeMutation() {
        assertFailsWith<GoWorkspaceEditRejected> { GoWorkspaceEditPlan.path("untitled:go") }
        assertFailsWith<GoWorkspaceEditRejected> { GoWorkspaceEditPlan.path("file:///workspace/a.go?query=1") }
        val value = WorkspaceEdit(mapOf(uri(file) to listOf(edit(0, 0, 0, 0, "x")))).apply { documentChanges = listOf(resource(CreateFile(uri(root.resolve("new.go"))))) }
        assertFailsWith<GoWorkspaceEditRejected> { build(value, initial(file to node("old"))) }
        assertFailsWith<GoWorkspaceEditRejected> { build(WorkspaceEdit(listOf(resource(CreateFile("file:///outside/new.go")))), initial()) }
    }

    private fun build(value: WorkspaceEdit, tree: Map<Path, GoEditNode>, sync: (Path) -> GoSyncedDocument? = { null }) = GoWorkspaceEditPlan.build(value, tree, sync) { it.startsWith(root) }
    private fun initial(vararg entries: Pair<Path, GoEditNode>): Map<Path, GoEditNode> = mapOf(Path.of("/") to GoEditNode(true), root to GoEditNode(true)) + entries
    private fun node(text: String, origin: Path = file) = GoEditNode(false, text.toByteArray(), text, origin)
    private fun uri(path: Path) = GoDocumentSync.fileUri(path)
    private fun edit(sl: Int, sc: Int, el: Int, ec: Int, text: String) = TextEdit(Range(Position(sl, sc), Position(el, ec)), text)
    private fun resource(operation: ResourceOperation): Either<TextDocumentEdit, ResourceOperation> = Either.forRight(operation)
    private fun document(path: Path, version: Int?, edits: List<TextEdit>): Either<TextDocumentEdit, ResourceOperation> = Either.forLeft(TextDocumentEdit(VersionedTextDocumentIdentifier(uri(path), version), edits))
}
