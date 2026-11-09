package dev.openidea.goide.refactor

import dev.openidea.goide.lsp.GoDocumentSync
import dev.openidea.goide.lsp.GoSyncedDocument
import org.eclipse.lsp4j.CreateFile
import org.eclipse.lsp4j.CreateFileOptions
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
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GoWorkspaceEditCaseAliasesTest {
    private val root = Path.of("/workspace").toAbsolutePath().normalize()

    @Test fun samePhysicalCaseOnlyFileRenamePreservesItsVersionedDocumentAndNeverOverwrites() {
        val old = root.resolve("name.go")
        val new = root.resolve("Name.go")
        val node = GoEditNode(false, "old".toByteArray(), "old", old)
        val initial = tree(old to node, new to node)
        for (options in listOf(null, RenameFileOptions(false, true), RenameFileOptions(true, true))) {
            val value = WorkspaceEdit(listOf(resource(RenameFile(uri(old), uri(new), options)), document(new, 7, "old", "new")))
            val plan = build(value, initial, setOf(GoCaseRenameAlias(old, new))) { if (it == old) GoSyncedDocument(7, "old", 4) else null }
            val rename = plan.steps.filterIsInstance<GoEditStep.Rename>().single()
            assertTrue(rename.caseOnly)
            assertFalse(rename.overwrite)
            assertFalse(plan.steps.any { it is GoEditStep.Delete })
            assertFalse(plan.after.keys.any { GoWorkspaceEditCaseAliases.sameSpelling(it, old) })
            assertEquals("new", GoWorkspaceEditPlan.text(plan.after.getValue(new)))
            assertEquals(old.toString(), plan.steps.filterIsInstance<GoEditStep.Text>().single().origin.toString())
        }
    }

    @Test fun directoryCaseOnlyRenameDeduplicatesChildrenAndRetainsClosedDiskOriginForFollowingEdit() {
        val old = root.resolve("pkg")
        val new = root.resolve("Pkg")
        val source = old.resolve("child.go")
        val target = new.resolve("child.go")
        val directory = GoEditNode(true, origin = old)
        val file = GoEditNode(false, "old".toByteArray(), "old", source, GoEditDiskStamp(3, "hash", 1, "inode"), true)
        val initial = tree(old to directory, new to directory, source to file, target to file)
        val value = WorkspaceEdit(listOf(resource(RenameFile(uri(old), uri(new))), document(target, 0, "old", "new")))
        val plan = build(value, initial, setOf(GoCaseRenameAlias(old, new)))
        assertFalse(plan.after.keys.any { spelledUnder(it, old) })
        assertEquals("new", GoWorkspaceEditPlan.text(plan.after.getValue(target)))
        assertEquals(source.toString(), plan.steps.filterIsInstance<GoEditStep.Text>().single().origin.toString())
        assertTrue(plan.steps.filterIsInstance<GoEditStep.Rename>().single().caseOnly)
    }

    @Test fun repeatedAndReversedCaseOnlyRenamesKeepOneLogicalResource() {
        val first = root.resolve("name.go")
        val second = root.resolve("NAME.go")
        val third = root.resolve("Name.go")
        val node = GoEditNode(false, "old".toByteArray(), "old", first)
        val initial = tree(first to node, second to node, third to node)
        val aliases = linkedSetOf(GoCaseRenameAlias(first, second), GoCaseRenameAlias(second, third), GoCaseRenameAlias(third, first))
        val value = WorkspaceEdit(listOf(resource(RenameFile(uri(first), uri(second))), resource(RenameFile(uri(second), uri(third))),
            resource(RenameFile(uri(third), uri(first))), document(first, null, "old", "new")))
        val plan = build(value, initial, aliases)
        assertEquals(setOf(first.toString()), plan.after.filterValues { !it.directory }.keys.map { it.toString() }.toSet())
        assertEquals("new", GoWorkspaceEditPlan.text(plan.after.getValue(first)))
        assertEquals(3, plan.steps.filterIsInstance<GoEditStep.Rename>().size)
        assertTrue(plan.steps.filterIsInstance<GoEditStep.Rename>().all { it.caseOnly && !it.overwrite })
    }

    @Test fun anAliasUsedBeforeRenameCannotPretendCreateHasAnEmptyTarget() {
        val old = root.resolve("name.go")
        val new = root.resolve("Name.go")
        val node = GoEditNode(false, "old".toByteArray(), "old", old)
        val initial = tree(old to node, new to node)
        val aliases = setOf(GoCaseRenameAlias(old, new))
        val unsafe = WorkspaceEdit(listOf(resource(CreateFile(uri(new))), resource(RenameFile(uri(old), uri(new)))))
        assertFailsWith<GoWorkspaceEditRejected> { build(unsafe, initial, aliases) }
        val ignored = WorkspaceEdit(listOf(resource(CreateFile(uri(new), CreateFileOptions(false, true))), resource(RenameFile(uri(old), uri(new)))))
        assertEquals(1, build(ignored, initial, aliases).steps.size)
    }

    @Test fun aNewChildAddressedThroughDirectoryAliasMovesWithItsParent() {
        val old = root.resolve("pkg")
        val new = root.resolve("Pkg")
        val child = new.resolve("new.go")
        val node = GoEditNode(true, origin = old)
        val value = WorkspaceEdit(listOf(resource(CreateFile(uri(child))), document(child, null, "", "new"), resource(RenameFile(uri(old), uri(new)))))
        val plan = build(value, tree(old to node, new to node), setOf(GoCaseRenameAlias(old, new)))
        assertEquals("new", GoWorkspaceEditPlan.text(plan.after.getValue(child)))
        assertFalse(plan.after.keys.any { spelledUnder(it, old) })
    }

    @Test fun mentioningNewCaseFirstPreservesOriginalVersionedOriginAndStillRenamesOnce() {
        val old = root.resolve("name.go")
        val new = root.resolve("Name.go")
        val node = GoEditNode(false, "old".toByteArray(), "old", old)
        // Windows Map<Path> retains the first NEW key even when OLD is inserted
        // later. Its live source spelling must come from proven entry identity.
        val initial = tree(new to node.copy(origin = new), old to node)
        val edit = WorkspaceEdit(listOf(document(new, 7, "old", "mid"),
            resource(RenameFile(uri(old), uri(new))), document(new, null, "mid", "end")))
        val plan = build(edit, initial, setOf(GoCaseRenameAlias(old, new))) {
            if (it.toString() == old.toString()) GoSyncedDocument(7, "old", 4) else null
        }
        val rename = plan.steps.filterIsInstance<GoEditStep.Rename>().single()
        assertTrue(rename.caseOnly)
        assertFalse(rename.overwrite)
        assertEquals(old.toString(), rename.old.toString())
        assertEquals(new.toString(), rename.new.toString())
        assertEquals(old.toString(), plan.steps.filterIsInstance<GoEditStep.Text>().first().origin.toString())
        assertEquals(setOf(new.toString()), plan.after.filterValues { !it.directory }.keys.map { it.toString() }.toSet())
        assertEquals("end", GoWorkspaceEditPlan.text(plan.after.getValue(new)))
    }

    @Test fun equalContentOrSharedOriginWithoutPhysicalAliasProofIsStillARealCollision() {
        val old = root.resolve("name.go")
        val new = root.resolve("Name.go")
        val shared = GoEditNode(false, "same".toByteArray(), "same", old)
        assertFailsWith<GoWorkspaceEditRejected> { build(WorkspaceEdit(listOf(resource(RenameFile(uri(old), uri(new))))), tree(old to shared, new to shared), emptySet()) }
    }

    @Test fun actualTemporaryFilesystemUsesOneDirectoryEntryAndPreservesIdentityAcrossCaseChanges() {
        val root = Files.createTempDirectory("opengoland-case-identity-").toRealPath()
        try {
            val old = Files.writeString(root.resolve("name.go"), "old")
            val new = root.resolve("Name.go")
            val caseInsensitive = Files.exists(new)
            if (caseInsensitive) {
                assertTrue(Files.isSameFile(old, new))
                assertEquals("name.go", GoWorkspaceEditCaseAliases.actualEntryName(old, new))
            } else assertFalse(GoWorkspaceEditCaseAliases.isCaseAlias(old, new))
            val key = Files.readAttributes(old, BasicFileAttributes::class.java).fileKey()
            Files.move(old, new, StandardCopyOption.ATOMIC_MOVE)
            assertEquals(listOf("Name.go"), Files.list(root).use { it.map { p -> p.fileName.toString() }.toList() })
            assertEquals(key, Files.readAttributes(new, BasicFileAttributes::class.java).fileKey())
            assertEquals("old", Files.readString(new))
            val folder = Files.createDirectory(root.resolve("pkg"))
            Files.writeString(folder.resolve("child.go"), "child")
            val renamed = root.resolve("Pkg")
            if (caseInsensitive) assertTrue(GoWorkspaceEditCaseAliases.isCaseAlias(folder, renamed))
            val childKey = Files.readAttributes(folder.resolve("child.go"), BasicFileAttributes::class.java).fileKey()
            Files.move(folder, renamed, StandardCopyOption.ATOMIC_MOVE)
            assertEquals(childKey, Files.readAttributes(renamed.resolve("child.go"), BasicFileAttributes::class.java).fileKey())
            assertEquals("child", Files.readString(renamed.resolve("child.go")))
        } finally { Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }

    @Test fun twoHardLinkNamesAreNotOneCaseOnlyDirectoryEntry() {
        val root = Files.createTempDirectory("opengoland-case-hardlink-").toRealPath()
        try {
            val first = Files.writeString(root.resolve("name.go"), "old")
            val other = Files.createLink(root.resolve("another.go"), first)
            assertTrue(Files.isSameFile(first, other))
            assertFalse(GoWorkspaceEditCaseAliases.isCaseAlias(first, other))
            val upper = root.resolve("Name.go")
            if (!Files.exists(upper)) {
                Files.createLink(upper, first)
                assertTrue(Files.isSameFile(first, upper))
                assertFalse(GoWorkspaceEditCaseAliases.isCaseAlias(first, upper))
            }
        } finally { Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }

    private fun tree(vararg entries: Pair<Path, GoEditNode>) = mapOf(root.root to GoEditNode(true), root to GoEditNode(true)) + entries
    private fun spelledUnder(path: Path, parent: Path) = path.toString() == parent.toString() ||
        path.toString().startsWith(parent.toString() + java.io.File.separator)
    private fun build(value: WorkspaceEdit, initial: Map<Path, GoEditNode>, aliases: Set<GoCaseRenameAlias>, synced: (Path) -> GoSyncedDocument? = { null }) =
        GoWorkspaceEditPlan.build(value, initial, synced, aliases) { it.startsWith(root) }
    private fun uri(path: Path) = GoDocumentSync.fileUri(path)
    private fun resource(value: ResourceOperation): Either<TextDocumentEdit, ResourceOperation> = Either.forRight(value)
    private fun document(path: Path, version: Int?, old: String, new: String): Either<TextDocumentEdit, ResourceOperation> =
        Either.forLeft(TextDocumentEdit(VersionedTextDocumentIdentifier(uri(path), version), listOf(TextEdit(Range(Position(0, 0), Position(0, old.length)), new))))
}
