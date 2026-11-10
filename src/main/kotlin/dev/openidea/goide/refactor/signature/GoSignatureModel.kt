package dev.openidea.goide.refactor.signature

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import dev.openidea.goide.refactor.GoEditUsage
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.ResourceOperation
import org.eclipse.lsp4j.SnippetTextEdit
import org.eclipse.lsp4j.TextDocumentEdit
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.jsonrpc.messages.Either
import java.util.UUID

internal data class GoSignatureRow(
    val rowId: String, val oldIndex: Int?, var name: String, var type: String,
    var variadic: Boolean = false, var defaultExpression: String = "",
) {
    fun json(result: Boolean): JsonObject = JsonObject().apply {
        addProperty("rowId", rowId)
        add("oldIndex", oldIndex?.let { com.google.gson.JsonPrimitive(it) } ?: JsonNull.INSTANCE)
        addProperty("name", name); addProperty("type", type.trim()); addProperty("variadic", variadic)
        if (oldIndex == null || defaultExpression.isNotBlank()) add(if (result) "bodyDefault" else "defaultArgument", expression(defaultExpression))
    }
    companion object {
        fun new(result: Boolean) = GoSignatureRow("new-${UUID.randomUUID()}", null, if (result) "" else "value", "int")
        fun parse(value: JsonObject, result: Boolean) = GoSignatureRow(value.string("rowId"), value.get("oldIndex")?.takeUnless { it.isJsonNull }?.asInt,
            value.string("name"), value.string("type"), value.bool("variadic"),
            value.getAsJsonObject(if (result) "bodyDefault" else "defaultArgument")?.string("text").orEmpty())
        fun expression(text: String) = JsonObject().apply {
            addProperty("kind", if (text.isBlank()) "zero" else "expression")
            if (text.isNotBlank()) addProperty("text", text.trim())
        }
    }
}

internal data class GoSignatureInspection(val wire: JsonObject) {
    val name = wire.string("name")
    val parameters = wire.array("parameters").map { GoSignatureRow.parse(it.asJsonObject, false) }
    val results = wire.array("results").map { GoSignatureRow.parse(it.asJsonObject, true) }
    val symbolId = wire.string("symbolId")
    val configurationDigest = wire.string("configurationDigest")
}

internal object GoSignatureCodec {
    fun workspaceEdit(plan: JsonObject): WorkspaceEdit {
        val edits = plan.getAsJsonObject("workspaceEdit") ?: error("The signature planner returned no workspace edit")
        return WorkspaceEdit(edits.array("documentChanges").map { item ->
            val change = item.asJsonObject
            val document = change.getAsJsonObject("textDocument") ?: error("Only signature text edits are supported")
            val version = document.get("version")?.takeUnless { it.isJsonNull }?.asInt
            val text = change.array("edits").map { entry ->
                val edit = entry.asJsonObject
                Either.forLeft<TextEdit, SnippetTextEdit>(TextEdit(range(edit.getAsJsonObject("range")), edit.string("newText")))
            }
            Either.forLeft<TextDocumentEdit, ResourceOperation>(TextDocumentEdit(VersionedTextDocumentIdentifier(document.string("uri"), version), text))
        })
    }
    fun usages(plan: JsonObject): List<GoEditUsage> = plan.array("usages").map { entry ->
        val usage = entry.asJsonObject
        val start = range(usage.getAsJsonObject("range")).start
        GoEditUsage(usage.string("uri"), start.line, start.character, usage.string("kind"), usage.string("message"))
    }
    fun conflicts(value: JsonObject): List<String> = value.array("conflicts").map { entry ->
        val conflict = entry.asJsonObject
        val start = conflict.getAsJsonObject("range")?.getAsJsonObject("start")
        val location = conflict.string("uri").substringAfterLast('/') + (start?.let { ":${it.get("line").asInt + 1}:${it.get("character").asInt + 1}" } ?: "")
        "$location  ${conflict.string("message")}"
    }
    private fun range(value: JsonObject): Range = Range(position(value.getAsJsonObject("start")), position(value.getAsJsonObject("end")))
    private fun position(value: JsonObject) = Position(value.get("line").asInt, value.get("character").asInt)
}

internal fun JsonObject.string(name: String): String = get(name)?.takeUnless { it.isJsonNull }?.asString.orEmpty()
internal fun JsonObject.bool(name: String): Boolean = get(name)?.takeUnless { it.isJsonNull }?.asBoolean ?: false
internal fun JsonObject.array(name: String): JsonArray = get(name)?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
