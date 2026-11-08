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
                    if (test.isNotEmpty()) {
                        val selector = test.split('/').joinToString("/") { "^${quote(it)}$" }
                        if (test.substringBefore('/').startsWith("Benchmark")) {
                            testMode = GoTestMode.BENCHMARK
                            benchmarkPattern = selector
                            testPattern = ""
                        } else {
                            // A discovered fuzz failure is reproduced from its saved
                            // seed corpus, never by starting new mutation workers.
                            testMode = GoTestMode.TEST
                            testPattern = selector
                        }
                    } else testPattern = ""
                    buildFlags = withoutExecutionSelectors(buildFlags)
                    arguments = withoutExecutionSelectors(arguments)
                }
                add(GoTestRetry(if (test.isEmpty()) "Retry $pkg" else "$test in $pkg", values))
            }
        }
    }

    private fun withoutExecutionSelectors(text: String): String {
        val values = ParametersListUtil.parse(text, false, true)
        val result = mutableListOf<String>()
        var index = 0
        while (index < values.size) {
            val value = values[index++]
            val name = value.trimStart('-').substringBefore('=').removePrefix("test.")
            when {
                value.startsWith('-') && name in setOf("run", "bench", "benchtime", "fuzz", "fuzztime", "fuzzminimizetime") ->
                    if ('=' !in value && index < values.size) index++
                value.startsWith('-') && name == "benchmem" -> Unit
                else -> result += value
            }
        }
        return ParametersListUtil.join(result)
    }

    fun quote(value: String) = buildString {
        value.forEach { character -> if (character in "\\.+*?()|[]{}^$") append('\\'); append(character) }
    }
}
