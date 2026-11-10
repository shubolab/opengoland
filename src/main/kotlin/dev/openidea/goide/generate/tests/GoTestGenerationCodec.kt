package dev.openidea.goide.generate.tests

import com.google.gson.JsonObject
import dev.openidea.goide.refactor.signature.array
import dev.openidea.goide.refactor.signature.string
import org.eclipse.lsp4j.CreateFile
import org.eclipse.lsp4j.CreateFileOptions
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.ResourceOperation
import org.eclipse.lsp4j.SnippetTextEdit
import org.eclipse.lsp4j.TextDocumentEdit
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.jsonrpc.messages.Either

internal object GoTestGenerationCodec {
    fun workspaceEdit(plan: JsonObject): WorkspaceEdit {
        val edit = requireNotNull(plan.getAsJsonObject("workspaceEdit")) { "The test planner returned no workspace edit" }
        return WorkspaceEdit(edit.array("documentChanges").map { entry ->
            val change = entry.asJsonObject
            if (change.has("kind")) {
                require(change.string("kind") == "create") { "Unexpected Go test resource operation" }
                Either.forRight<TextDocumentEdit, ResourceOperation>(CreateFile(change.string("uri"), CreateFileOptions(false, false)))
            } else {
                val document = requireNotNull(change.getAsJsonObject("textDocument")) { "Missing generated test document" }
                val version = document.get("version")?.takeUnless { it.isJsonNull }?.asInt
                val edits = change.array("edits").map { value ->
                    val item = value.asJsonObject
                    Either.forLeft<TextEdit, SnippetTextEdit>(TextEdit(range(item.getAsJsonObject("range")), item.string("newText")))
                }
                Either.forLeft<TextDocumentEdit, ResourceOperation>(TextDocumentEdit(VersionedTextDocumentIdentifier(document.string("uri"), version), edits))
            }
        })
    }

    fun firstEditedUri(plan: JsonObject): String? = plan.getAsJsonObject("workspaceEdit")?.array("documentChanges")
        ?.firstOrNull { it.asJsonObject.has("textDocument") }?.asJsonObject?.getAsJsonObject("textDocument")?.string("uri")

    private fun range(value: JsonObject) = Range(position(value.getAsJsonObject("start")), position(value.getAsJsonObject("end")))
    private fun position(value: JsonObject) = Position(value.get("line").asInt, value.get("character").asInt)
}
