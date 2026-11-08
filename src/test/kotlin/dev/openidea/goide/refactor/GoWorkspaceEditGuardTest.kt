package dev.openidea.goide.refactor

import dev.openidea.goide.lsp.GoSyncedDocument
import dev.openidea.goide.lsp.GoDocumentSync
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GoWorkspaceEditGuardTest {
    @Test fun typingUndoAndPreviewTimeChangesAreRejectedEvenWhenTextLooksTheSame() {
        val path = Path.of("/workspace/a.go")
        val synced = GoSyncedDocument(7, "same", 1)
        val captured = GoEditDocumentStamp(10, synced)
        GoWorkspaceEditGuard.validate(path, 10, "same", 10, "same", captured, synced)
        assertFailsWith<GoWorkspaceEditRejected> { GoWorkspaceEditGuard.validate(path, 10, "same", 11, "same", captured, synced) }
        assertFailsWith<GoWorkspaceEditRejected> { GoWorkspaceEditGuard.validate(path, 11, "same", 11, "same", captured, synced) }
        assertFailsWith<GoWorkspaceEditRejected> { GoWorkspaceEditGuard.validate(path, 10, "same", 10, "changed", captured, synced) }
    }

    @Test fun aRestartedClosedOrNewerLanguageServerDocumentCannotReuseAnOldRequest() {
        val path = Path.of("/workspace/a.go")
        val synced = GoSyncedDocument(7, "same", 1)
        val captured = GoEditDocumentStamp(10, synced)
        for (current in listOf(null, synced.copy(version = 8), synced.copy(session = 2), synced.copy(text = "different"))) {
            assertFailsWith<GoWorkspaceEditRejected> { GoWorkspaceEditGuard.validate(path, 10, "same", 10, "same", captured, current) }
        }
    }

    @Test fun advancingOneSuccessfulServerEditDoesNotAcceptUserChangesToAnotherFile() {
        val first = Path.of("/workspace/first.go")
        val other = Path.of("/workspace/other.go")
        val firstUri = GoDocumentSync.fileUri(first)
        val otherUri = GoDocumentSync.fileUri(other)
        val original = GoEditRequestSnapshot(mapOf(firstUri to GoEditDocumentStamp(10, null), otherUri to GoEditDocumentStamp(20, null)), changeSequence = 5)
        val advanced = GoWorkspaceEditGuard.acknowledge(original, mapOf(firstUri to 7), setOf(firstUri), mapOf(firstUri to GoEditDocumentStamp(11, null)))
        assertEquals(5L, advanced.changeSequence)
        assertEquals(11L, advanced.documents.getValue(firstUri).stamp)
        assertEquals(20L, advanced.documents.getValue(otherUri).stamp)
        assertFalse(GoWorkspaceEditGuard.unacknowledged(first, 7, advanced))
        assertTrue(GoWorkspaceEditGuard.unacknowledged(other, 6, advanced), "User edits during the first preview must remain unacknowledged")
        assertTrue(GoWorkspaceEditGuard.unacknowledged(first, 8, advanced), "Later typing in our own edited file must still be rejected")
    }

    @Test fun acknowledgedRenameMovesOnlyItsDocumentBaseline() {
        val old = GoDocumentSync.fileUri(Path.of("/workspace/old.go"))
        val new = GoDocumentSync.fileUri(Path.of("/workspace/new.go"))
        val other = GoDocumentSync.fileUri(Path.of("/workspace/other.go"))
        val original = GoEditRequestSnapshot(mapOf(old to GoEditDocumentStamp(10, null), other to GoEditDocumentStamp(20, null)), changeSequence = 5)
        val advanced = GoWorkspaceEditGuard.acknowledge(original, mapOf(old to 6, new to 7), setOf(old), mapOf(new to GoEditDocumentStamp(11, null)))
        assertFalse(old in advanced.documents)
        assertTrue(new in advanced.documents)
        assertEquals(original.documents.getValue(other), advanced.documents.getValue(other))
    }

    @Test fun closedDiskChangesAfterRequestOrAfterOwnedAcknowledgementAreRejected() {
        val path = Path.of("/workspace/closed.go")
        val uri = GoDocumentSync.fileUri(path)
        val request = GoEditRequestSnapshot(emptyMap(), capturedAtMillis = 1000, changeSequence = 5)
        val old = GoEditDiskStamp(3, "old-hash", 900, "original-inode")
        GoWorkspaceEditGuard.validateClosedDisk(path, old, request)
        assertFailsWith<GoWorkspaceEditRejected> { GoWorkspaceEditGuard.validateClosedDisk(path, old.copy(modifiedAtMillis = 1100), request) }
        val owned = old.copy(modifiedAtMillis = 1100)
        val advanced = request.copy(acceptedDisk = mapOf(uri to owned), acceptedChanges = mapOf(uri to 7))
        GoWorkspaceEditGuard.validateClosedDisk(path, owned, advanced)
        listOf(owned.copy(sha256 = "user-content"), owned.copy(size = 4), owned.copy(modifiedAtMillis = 1200), owned.copy(fileKey = "replacement-inode"), owned.copy(modifiedAtMillis = 800)).forEach {
            assertFailsWith<GoWorkspaceEditRejected> { GoWorkspaceEditGuard.validateClosedDisk(path, it, advanced) }
        }
    }
}
