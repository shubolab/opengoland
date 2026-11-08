package dev.openidea.goide.run

import com.intellij.util.execution.ParametersListUtil

data class GoTestRetry(val name: String, val parameters: GoRunParameters)

/** Go splits -run at slash boundaries; one retry per failed path avoids matching unrelated sibling combinations. */
object GoTestRerunPlan {
    fun create(failures: Map<String, List<String>>, original: GoRunParameters): List<GoTestRetry> = buildList {
        failures.forEach { (pkg, names) ->
            val selected = if ("" in names) listOf("") else names.distinct().filter { parent -> names.none { it.startsWith("$parent/") } }
            selected.forEach { test ->
                val values = original.independentCopy().apply {
                    target = ParametersListUtil.join(pkg)
                    testPattern = if (test.isEmpty()) "" else test.split('/').joinToString("/") { "^${quote(it)}$" }
                    buildFlags = withoutRunSelector(buildFlags)
                    arguments = withoutRunSelector(arguments)
                }
                add(GoTestRetry(if (test.isEmpty()) "Retry $pkg" else "$test in $pkg", values))
            }
        }
    }

    private fun withoutRunSelector(text: String): String {
        val values = ParametersListUtil.parse(text, false, true)
        val result = mutableListOf<String>()
        var index = 0
        while (index < values.size) {
            val value = values[index++]
            when {
                value == "-run" || value == "-test.run" -> if (index < values.size) index++
                value.startsWith("-run=") || value.startsWith("-test.run=") -> Unit
                else -> result += value
            }
        }
        return ParametersListUtil.join(result)
    }

    fun quote(value: String) = buildString {
        value.forEach { character -> if (character in "\\.+*?()|[]{}^$") append('\\'); append(character) }
    }
}
