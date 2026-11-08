package dev.openidea.goide.refactor.signature

import com.google.gson.JsonParser
import dev.openidea.goide.lsp.GoSyncedDocument
import dev.openidea.goide.refactor.GoEditNode
import dev.openidea.goide.refactor.GoWorkspaceEditPlan
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoSignatureModelTest {
    @Test fun reorderedExistingAndNewRowsKeepSeparateStableIdentities() {
        val model = GoSignatureTableModel(listOf(GoSignatureRow("a", 0, "a", "int"), GoSignatureRow("b", 1, "b", "string")), false)
        model.move(1, -1)
        val added = model.add()
        model.setValueAt("context.Context", added, 1)
        model.setValueAt("context.Background()", added, 3)
        val wire = model.json()
        assertEquals(listOf(1, 0), wire.take(2).map { it.asJsonObject.get("oldIndex").asInt })
        assertTrue(wire[2].asJsonObject.get("oldIndex").isJsonNull)
        assertEquals("context.Background()", wire[2].asJsonObject.getAsJsonObject("defaultArgument").string("text"))
        assertEquals("a", wire[1].asJsonObject.string("rowId"))
    }
    @Test fun emptyAddedDefaultsMeanZeroButRetainedRowsDoNotSilentlyReplaceValues() {
        assertEquals("zero", GoSignatureRow.new(false).json(false).getAsJsonObject("defaultArgument").string("kind"))
        assertEquals("zero", GoSignatureRow.new(true).json(true).getAsJsonObject("bodyDefault").string("kind"))
        assertFalse(GoSignatureRow("old", 0, "v", "string").json(false).has("defaultArgument"))
        val parsed = GoSignatureRow.parse(JsonParser.parseString("""{"rowId":"old","oldIndex":0,"name":"v","type":"int","variadic":true}""").asJsonObject, false)
        assertEquals(0, parsed.oldIndex); assertTrue(parsed.variadic); assertEquals("int", parsed.type)
    }
    @Test fun wireCodecKeepsRealVersionAndUtf16ColumnsThroughNativePlanner() {
        val before = "/* 😀 */ Target(1)\n"
        val path = Path.of("/workspace/main.go")
        val plan = JsonParser.parseString("""{"workspaceEdit":{"documentChanges":[{"textDocument":{"uri":"file:///workspace/main.go","version":19},"edits":[{"range":{"start":{"line":0,"character":9},"end":{"line":0,"character":15}},"newText":"Renamed"}]}]}}""").asJsonObject
        val edit = GoSignatureCodec.workspaceEdit(plan)
        assertEquals(19, edit.documentChanges.single().left.textDocument.version)
        val result = GoWorkspaceEditPlan.build(edit, mapOf(path to GoEditNode(false, before.toByteArray(), before, path)),
            { GoSyncedDocument(19, before, 7) }) { true }
        assertEquals("/* 😀 */ Renamed(1)\n", result.after.getValue(path).text)
    }
    @Test fun absentWireVersionRemainsUnversioned() {
        val plan = JsonParser.parseString("""{"workspaceEdit":{"documentChanges":[{"textDocument":{"uri":"file:///workspace/main.go","version":null},"edits":[]}]}}""").asJsonObject
        assertNull(GoSignatureCodec.workspaceEdit(plan).documentChanges.single().left.textDocument.version)
    }
}
