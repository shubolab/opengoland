package dev.openidea.goide.lsp

import com.google.gson.JsonParser
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentContentChangeEvent
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.messages.NotificationMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import java.nio.file.Path

class GoDocumentSyncTest {
    private val uri = "file:///tmp/Go%20workspace/中文.go"
    private fun notification(method: String, params: Any) = NotificationMessage().apply { this.method = method; this.params = params }
    private fun open(sync: GoDocumentSync, session: Long, text: String, version: Int = 1) =
        sync.observe(session, notification("textDocument/didOpen", DidOpenTextDocumentParams(TextDocumentItem(uri, "go", version, text))))
    private fun change(range: Range?, text: String) = TextDocumentContentChangeEvent().apply { this.range = range; this.text = text }

    @Test fun versionsMirrorRealSequentialUtf16Notifications() {
        val sync = GoDocumentSync()
        val session = sync.startSession()
        open(sync, session, "a👋b\n第二行\r\n")
        sync.observe(session, notification("textDocument/didChange", DidChangeTextDocumentParams(
            VersionedTextDocumentIdentifier(uri, 2), listOf(
                change(Range(Position(0, 3), Position(0, 4)), "你好"),
                change(Range(Position(0, 5), Position(0, 5)), "!"),
                change(Range(Position(1, 1), Position(1, 2)), "三"),
            ))))
        assertEquals(GoSyncedDocument(2, "a👋你好!\n第三行\r\n", session), sync.snapshot(uri))
        sync.observe(session, notification("textDocument/didChange", DidChangeTextDocumentParams(
            VersionedTextDocumentIdentifier(uri, 7), listOf(change(null, "package 新包\n")))))
        assertEquals("package 新包\n", sync.snapshot(uri)?.text)
        assertEquals(7, sync.snapshot(uri)?.version)
    }

    @Test fun closingAndOldSessionsCannotLeaveOrOverwriteVersions() {
        val sync = GoDocumentSync()
        val old = sync.startSession()
        open(sync, old, "old")
        val current = sync.startSession()
        assertNull(sync.snapshot(uri))
        open(sync, current, "new")
        open(sync, old, "stale")
        sync.stopSession(old)
        assertEquals("new", sync.snapshot(uri)?.text)
        sync.observe(current, notification("textDocument/didClose", DidCloseTextDocumentParams(TextDocumentIdentifier(uri))))
        assertNull(sync.snapshot(uri))
        open(sync, current, "again")
        sync.stopSession(current)
        assertNull(sync.snapshot(uri))
    }

    @Test fun invalidOrOutOfOrderChangesInvalidateTheMirrorInsteadOfClamping() {
        val sync = GoDocumentSync()
        val session = sync.startSession()
        open(sync, session, "one\n")
        sync.observe(session, notification("textDocument/didChange", DidChangeTextDocumentParams(
            VersionedTextDocumentIdentifier(uri, 2), listOf(change(Range(Position(0, 9), Position(0, 9)), "bad")))))
        assertNull(sync.snapshot(uri))
        open(sync, session, "new", 4)
        sync.observe(session, notification("textDocument/didChange", DidChangeTextDocumentParams(
            VersionedTextDocumentIdentifier(uri, 4), listOf(change(null, "stale")))))
        assertNull(sync.snapshot(uri))
    }

    @Test fun jsonParametersAndEscapedUrisUseTheSameDocumentKey() {
        val sync = GoDocumentSync()
        val session = sync.startSession()
        sync.observe(session, notification("textDocument/didOpen", JsonParser.parseString("""
            {"textDocument":{"uri":"file:///tmp/Go%20workspace/中文.go","languageId":"go","version":3,"text":"hello 👋"}}
        """)))
        assertEquals("hello 👋", sync.snapshot("file:///tmp/Go%20workspace/%E4%B8%AD%E6%96%87.go")?.text)
        assertEquals(3, sync.snapshot(uri)?.version)
        sync.dispose()
        assertNull(sync.snapshot(uri))
    }

    @Test fun fileUriNormalizationIsLexicalEscapedAndIndependentOfFileExistence() {
        val path = Path.of("/tmp/不存在/../Go workspace/中文#100%.go")
        val expected = "file:///tmp/Go%20workspace/%E4%B8%AD%E6%96%87%23100%25.go"
        assertEquals(expected, GoDocumentSync.fileUri(path))
        assertEquals(expected, GoDocumentSync.canonicalUri("FILE:///tmp/Go%20workspace/中文%23100%25.go"))
        assertEquals(expected, GoDocumentSync.canonicalUri("file://localhost/tmp/Go%20workspace/%E4%B8%AD%E6%96%87%23100%25.go"))
        // Even an existing directory gets no stat-derived trailing slash.
        assertEquals("file:///tmp", GoDocumentSync.canonicalUri("file:///tmp"))
        assertEquals("file:///tmp", GoDocumentSync.canonicalUri("file:///tmp/"))
    }

    @Test fun disposedTrackerCannotBeReactivatedByALateConnectionProvider() {
        val sync = GoDocumentSync()
        sync.dispose()
        val late = sync.startSession()
        open(sync, late, "must not resurrect")
        assertNull(sync.snapshot(uri))
    }

    @Test fun duplicateOpensAndSplitSurrogateChangesRefuseUnverifiableVersions() {
        val sync = GoDocumentSync()
        val session = sync.startSession()
        open(sync, session, "first", 4)
        open(sync, session, "stale duplicate", 1)
        assertNull(sync.snapshot(uri))
        open(sync, session, "👋", 5)
        sync.observe(session, notification("textDocument/didChange", DidChangeTextDocumentParams(
            VersionedTextDocumentIdentifier(uri, 6), listOf(change(Range(Position(0, 1), Position(0, 2)), "broken")))))
        assertNull(sync.snapshot(uri))
        assertFailsWith<IllegalArgumentException> {
            GoDocumentSync.applyChanges("👋", listOf(change(Range(Position(0, 0), Position(0, 1)), "broken")))
        }
    }
}
