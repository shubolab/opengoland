package dev.openidea.goide.debug

/** go test rewrites test flags before launching its binary; Delve only does the compilation. */
internal data class GoTestDebugArguments(val buildFlags: List<String>, val arguments: List<String>) {
    internal data class Selector(val declared: Boolean, val value: String? = null, val uncertain: Boolean = false)
    companion object {
        private val booleanTestFlags = setOf("benchmem", "failfast", "fullpath", "short", "v")
        private val valueTestFlags = setOf(
            "bench", "benchtime", "blockprofile", "blockprofilerate", "count", "coverprofile", "cpu", "cpuprofile",
            "fuzz", "fuzztime", "fuzzminimizetime", "list", "memprofile", "memprofilerate", "mutexprofile",
            "mutexprofilefraction", "outputdir", "parallel", "run", "shuffle", "skip", "timeout", "trace",
        )
        private val booleanBuildFlags = setOf("a", "n", "x", "asan", "cover", "linkshared", "modcacherw", "msan", "race", "trimpath", "work")
        private val valueBuildFlags = setOf(
            "C", "asmflags", "buildmode", "compiler", "covermode", "coverpkg", "debug-actiongraph", "debug-runtime-trace",
            "debug-trace", "gcflags", "gccgoflags", "installsuffix", "ldflags", "mod", "modfile", "overlay", "p", "pgo",
            "pkgdir", "tags", "toolexec", "vet",
        )

        /** Only the metadata view changes; the ordinary Run argv is never rewritten. */
        fun runMetadataFlags(flags: List<String>): List<String> = buildList {
            var index = 0
            while (index < flags.size) {
                val token = flags[index++]
                if (token in setOf("-args", "--args", "--")) {
                    add(token); addAll(flags.subList(index, flags.size)); break
                }
                val name = token.trimStart('-').substringBefore('=')
                if (!token.startsWith('-') || name !in setOf("c", "o", "exec")) {
                    add(token)
                    // A known flag's value may itself start with a dash. Keep
                    // its existing value slot instead of treating it as -o/-c.
                    if (token.startsWith('-') && '=' !in token && name.removePrefix("test.") in valueTestFlags + valueBuildFlags && index < flags.size)
                        add(flags[index++])
                    continue
                }
                if (name == "c") {
                    if ('=' in token) require(token.substringAfter('=') in setOf("1", "t", "T", "TRUE", "true", "True", "0", "f", "F", "FALSE", "false", "False")) { "Invalid Go -c boolean value" }
                } else if ('=' !in token) {
                    require(index < flags.size) { "Go flag $token requires a value" }
                    index++
                }
            }
        }

        /**
         * Identify explicit runtime selector declarations with the same existing
         * flag normalization. Opaque custom arguments are not a new Go parser:
         * when they mix with a selector, release stale source provenance rather
         * than rejecting a potentially legitimate handwritten selection.
         */
        fun explicitSelector(arguments: List<String>, selector: String): Selector {
            require(selector in setOf("run", "bench", "fuzz"))
            fun potential(input: List<String>, normalized: Boolean): Boolean {
                var binary = false
                for (token in input) {
                    // The normalized pass below consumes known value slots;
                    // a literal "--" can itself be an invalid flag value.
                    if (token == "--") { if (!normalized) break else continue }
                    if (token in setOf("-args", "--args")) { binary = true; continue }
                    if (!token.startsWith('-')) continue
                    val name = token.trimStart('-').substringBefore('=')
                    if (name == "test.$selector" || !normalized && !binary && name == selector) return true
                }
                return false
            }
            val normalized = try { create(emptyList(), runMetadataFlags(arguments)).arguments }
                catch (_: IllegalArgumentException) { return Selector(potential(arguments, false), uncertain = true) }
            val declared = potential(normalized, true)
            if (!declared) return Selector(false)
            var result: String? = null
            var index = 0
            while (index < normalized.size) {
                val token = normalized[index++]
                if (token == "--") break
                if (!token.startsWith('-') || !token.trimStart('-').startsWith("test.")) return Selector(true, uncertain = true)
                val name = token.trimStart('-').substringBefore('=').removePrefix("test.")
                if (name !in booleanTestFlags + valueTestFlags) return Selector(true, uncertain = true)
                if (name in valueTestFlags) {
                    val value = if ('=' in token) token.substringAfter('=') else normalized.getOrNull(index++) ?: return Selector(true, uncertain = true)
                    if (name == selector) result = value
                }
            }
            return Selector(result != null, result)
        }

        fun create(buildFlags: List<String>, arguments: List<String>, testPattern: String = ""): GoTestDebugArguments =
            create(buildFlags, arguments, testPattern, false)

        fun create(buildFlags: List<String>, arguments: List<String>, testPattern: String, framed: Boolean): GoTestDebugArguments {
            val build = mutableListOf<String>()
            val runtime = mutableListOf<String>()
            var coverageProfile = false

            fun consume(input: List<String>, defaultToBuild: Boolean) {
                var index = 0
                while (index < input.size) {
                    val token = input[index++]
                    if (token == "-args" || token == "--args" || token == "--") {
                        require(!framed || token == "--" || input.subList(index, input.size).takeWhile { it != "--" }.none {
                            it.trimStart('-').substringBefore('=') in setOf("v", "test.v")
                        }) { "Debug test results manage -test.v; put verbosity flags before -args." }
                        if (token == "--") runtime.add(token)
                        runtime.addAll(input.subList(index, input.size))
                        return
                    }
                    val flag = token.trimStart('-').substringBefore('=')
                    val name = flag.removePrefix("test.")
                    val isFlag = token.startsWith('-') && token != "-"
                    val hasValue = '=' in token
                    if (isFlag && name in booleanTestFlags + valueTestFlags) {
                        if (framed && name == "v") continue
                        runtime.add("-test.$name" + if (hasValue) "=" + token.substringAfter('=') else "")
                        if (name in valueTestFlags && !hasValue) {
                            require(index < input.size) { "Go test flag $token requires a value." }
                            runtime.add(input[index++])
                        }
                        if (name == "coverprofile") coverageProfile = true
                    } else {
                        require(!isFlag || flag !in setOf("c", "o", "exec", "json")) {
                            "Go Test Debug manages -$flag itself; remove it from this configuration."
                        }
                        val isBuildFlag = isFlag && flag in booleanBuildFlags + valueBuildFlags + "buildvcs"
                        val destination = if (defaultToBuild || isBuildFlag) build else runtime
                        destination.add(token)
                        if (isFlag && flag in valueBuildFlags && !hasValue) {
                            require(index < input.size) { "Go build flag $token requires a value." }
                            destination.add(input[index++])
                        }
                    }
                }
            }

            consume(buildFlags, true)
            if (testPattern.isNotBlank()) runtime.addAll(listOf("-test.run", testPattern))
            consume(arguments, false)
            // Unlike profiles used only at runtime, coverage also needs compiler instrumentation.
            if (coverageProfile) build.add("-cover")
            return GoTestDebugArguments(build.toList(), if (framed) listOf("-test.v=test2json") + runtime else runtime.toList())
        }
    }
}
