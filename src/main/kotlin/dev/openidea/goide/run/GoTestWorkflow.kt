package dev.openidea.goide.run

import java.math.BigDecimal
import java.math.RoundingMode

/** Client-side validation mirrors Go's documented duration/count domain; Go validates RE2 patterns. */
internal object GoTestWorkflow {
    private val part = Regex("([0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(ns|us|µs|μs|ms|s|m|h)")
    private val scales = mapOf("ns" to 1L, "us" to 1_000L, "µs" to 1_000L, "μs" to 1_000L,
        "ms" to 1_000_000L, "s" to 1_000_000_000L, "m" to 60_000_000_000L, "h" to 3_600_000_000_000L)

    fun validateDurationOrCount(text: String, label: String, allowZero: Boolean = false) {
        val count = text.takeIf { it.endsWith('x') }?.dropLast(1)?.toLongOrNull()
        if (count != null && (count > 0 || allowZero && count == 0L)) return
        if (allowZero && text in setOf("0", "+0", "-0")) return
        val negative = text.startsWith('-')
        val duration = if (text.startsWith('+') || negative) text.drop(1) else text
        var end = 0
        var nanos = BigDecimal.ZERO
        for (match in part.findAll(duration)) {
            if (match.range.first != end) break
            // time.ParseDuration truncates each component before summing it.
            nanos += (match.groupValues[1].toBigDecimal() * scales.getValue(match.groupValues[2]).toBigDecimal()).setScale(0, RoundingMode.DOWN)
            end = match.range.last + 1
        }
        require(duration.isNotEmpty() && end == duration.length && nanos <= Long.MAX_VALUE.toBigDecimal() &&
            (!negative || nanos == BigDecimal.ZERO) &&
            (nanos >= BigDecimal.ONE || allowZero && nanos >= BigDecimal.ZERO)) {
            "$label must be a Go duration such as 2s or 1m30s, or an iteration count such as 100x." +
                if (allowZero) " Zero disables minimization." else " Use a positive value."
        }
    }

    /** Workflow fields own their selectors. Custom test arguments retain their normal order. */
    fun rejectManagedFlags(arguments: List<String>, mode: GoTestMode) {
        val owned = when (mode) {
            GoTestMode.TEST -> setOf("json")
            GoTestMode.BENCHMARK -> setOf("json", "run", "bench", "benchtime", "benchmem", "fuzz", "fuzztime", "fuzzminimizetime")
            GoTestMode.FUZZ -> setOf("json", "run", "bench", "fuzz", "fuzztime", "fuzzminimizetime", "parallel")
        }
        for (token in arguments) {
            // -args marks the test binary's flags as well; managed selectors must
            // still not override the selected native workflow after that marker.
            if (!token.startsWith('-')) continue
            val name = token.trimStart('-').substringBefore('=').removePrefix("test.")
            require(name !in owned) { "The $mode framework manages -$name. Use its native configuration field." }
        }
    }
}
