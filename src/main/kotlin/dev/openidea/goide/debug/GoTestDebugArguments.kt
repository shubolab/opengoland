package dev.openidea.goide.debug

/** go test rewrites test flags before launching its binary; Delve only does the compilation. */
internal data class GoTestDebugArguments(val buildFlags: List<String>, val arguments: List<String>) {
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
