package dev.openidea.goide.generate.tests

import com.google.gson.JsonObject
import com.intellij.psi.TokenType
import dev.openidea.goide.lang.GoLexer
import dev.openidea.goide.lang.GoTokens

/** Split the UI's concrete type list without splitting generic, function, array or struct types. */
internal fun splitGoTestTypeArguments(text: String): List<String> {
    val result = mutableListOf<String>()
    val closing = ArrayDeque<String>()
    var start = 0
    var hasType = false
    val lexer = GoLexer()
    lexer.start(text)
    while (lexer.tokenType != null) {
        val type = lexer.tokenType
        val value = text.substring(lexer.tokenStart, lexer.tokenEnd)
        if (type != TokenType.WHITE_SPACE && type != GoTokens.COMMENT) {
            if (type == GoTokens.STRING)
                require(value.length >= 2 && value.last() == value.first()) { "Close every quoted literal in the concrete type arguments." }
            when (value) {
                "(" -> closing.addLast(")")
                "[" -> closing.addLast("]")
                "{" -> closing.addLast("}")
                ")", "]", "}" -> require(closing.removeLastOrNull() == value) { "Balance brackets in the concrete type arguments." }
            }
            if (value == "," && closing.isEmpty()) {
                require(hasType) { "Enter a concrete type between commas." }
                result += text.substring(start, lexer.tokenStart).trim()
                start = lexer.tokenEnd
                hasType = false
            } else hasType = true
        } else if (type == GoTokens.COMMENT && value.startsWith("/*")) {
            require(value.endsWith("*/")) { "Close the comment in the concrete type arguments." }
        }
        lexer.advance()
    }
    require(closing.isEmpty()) { "Balance brackets in the concrete type arguments." }
    if (hasType) result += text.substring(start).trim()
    return result
}

internal data class GoGenerateTestsRow(
    val symbol: JsonObject,
    var selected: Boolean,
    var testName: String,
    var typeArgumentsText: String,
) {
    val symbolId: String get() = symbol.genString("symbolId")
    val needsTypes: Boolean get() = symbol.get("needsTypeArguments")?.asBoolean == true
    val existing: Boolean get() = symbol.get("existingTest")?.asBoolean == true
    val typeCount: Int get() = symbol.getAsJsonArray("typeParameters")?.size() ?: 0

    fun declaration(): String {
        val receiver = symbol.genString("receiver").takeIf(String::isNotBlank)?.let { "($it)." }.orEmpty()
        val typeParameters = symbol.getAsJsonArray("typeParameters")?.joinToString(", ", "[", "]") {
            it.asJsonObject.genString("name") + " " + it.asJsonObject.genString("constraint")
        }?.takeUnless { it == "[]" }.orEmpty()
        fun parameters(key: String) = symbol.getAsJsonArray(key)?.joinToString(", ") {
            val value = it.asJsonObject
            val type = value.genString("type")
            // go/types represents ...T as []T. Strip exactly that outer slice for display.
            val displayedType = if (value.get("variadic")?.asBoolean == true) "..." + type.removePrefix("[]") else type
            listOf(value.genString("name"), displayedType)
                .filter(String::isNotBlank).joinToString(" ")
        }.orEmpty()
        val results = parameters("results").takeIf(String::isNotBlank)?.let { " ($it)" }.orEmpty()
        return receiver + symbol.genString("name") + typeParameters + "(" + parameters("parameters") + ")" + results
    }
}

internal fun JsonObject.genString(name: String): String = get(name)?.takeUnless { it.isJsonNull }?.asString.orEmpty()
