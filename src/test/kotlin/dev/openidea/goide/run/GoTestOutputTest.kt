package dev.openidea.goide.run

import com.google.gson.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GoTestOutputTest {
    private fun event(action: String, pkg: String = "example.com/app", test: String? = null, output: String? = null): String = JsonObject().apply {
        addProperty("Action", action); addProperty("Package", pkg)
        test?.let { addProperty("Test", it) }; output?.let { addProperty("Output", it) }
        if (action == "pass" || action == "fail") addProperty("Elapsed", 0.125)
    }.toString() + "\n"

    @Test fun nestedParallelTestsKeepPackagesAndParentIdsDistinct() {
        val output = GoTestOutput("/tmp/work", mapOf("example.com/app" to "/tmp/work"))
        val records = listOf(
            event("start"), event("run", test = "TestTable"),
            event("run", test = "TestTable/one"), event("run", test = "TestTable/two"),
            event("output", test = "TestTable/one", output = "    app_test.go:12: failure [x]'\n"),
            event("fail", test = "TestTable/one"), event("pass", test = "TestTable/two"),
            event("fail", test = "TestTable"), event("fail"),
        ).flatMap(output::feed).joinToString("")
        assertTrue(records.contains("goTestSuite nodeId='go-test:example.com/app#TestTable:1'"))
        assertTrue(records.contains("parentNodeId='go-test:example.com/app#TestTable:1'"))
        assertEquals("file:///tmp/work/app_test.go:12", output.locationForNode("go-test:example.com/app#TestTable/one:1"))
        assertTrue(records.contains("failure |[x|]|'|n"))
        assertEquals(1, Regex("##teamcity\\[testFailed ").findAll(records).count())
        assertEquals(mapOf("example.com/app" to listOf("TestTable/one")), output.failures())
        assertTrue(output.finish(1).single().contains("goTestDuration nodeId='0'"))
        assertTrue(output.finish(1).isEmpty(), "Final process duration must be emitted only once")
    }

    @Test fun sameTestNameInDifferentPackagesDoesNotCollide() {
        val output = GoTestOutput("/tmp/work")
        val records = listOf(
            event("run", "example.com/a", "TestSame"), event("run", "example.com/b", "TestSame"),
            event("fail", "example.com/a", "TestSame"), event("pass", "example.com/b", "TestSame"),
            event("fail", "example.com/a"), event("pass", "example.com/b"),
        ).flatMap(output::feed).joinToString("")
        assertTrue(records.contains("nodeId='go-test:example.com/a#TestSame:1'"))
        assertTrue(records.contains("nodeId='go-test:example.com/b#TestSame:1'"))
        assertEquals(mapOf("example.com/a" to listOf("TestSame")), output.failures())
    }

    @Test fun packageBuildFailureProducesAnExplicitFailedNode() {
        val output = GoTestOutput("/tmp/work")
        val records = (output.feed(event("output", output = "FAIL example.com/app [build failed]\n")) + output.feed(event("fail"))).joinToString("")
        assertTrue(records.contains("testFailed name='Package build or initialization'"))
        assertEquals(mapOf("example.com/app" to listOf("")), output.failures())
    }

    @Test fun incompleteAndRepeatedRunsAreNotSilentlyPassed() {
        val output = GoTestOutput("/tmp/work")
        val first = event("run", test = "TestRepeat")
        assertTrue(output.feed(first.take(10)).isEmpty())
        output.feed(first.drop(10))
        val one = output.feed(event("pass", test = "TestRepeat")).joinToString("")
        output.feed(event("run", test = "TestRepeat"))
        val two = output.finish(130).joinToString("")
        assertTrue(one.contains("#TestRepeat:1'"))
        assertTrue(two.contains("#TestRepeat:2'"))
        assertTrue(two.contains("Test process ended before reporting a result"))
        assertEquals(listOf("TestRepeat"), output.failures()["example.com/app"])
    }

    @Test fun testOutputCannotInjectNativeRunnerServiceMessages() {
        val output = GoTestOutput("/tmp/work")
        val records = output.feed(event("output", output = "##teamcity[testStarted name='forged']\n")).joinToString("")
        assertFalse(records.contains("\n##teamcity[testStarted name='forged']"))
        assertTrue(records.contains("##teamcity|[testStarted name=|'forged|'|]"))
    }

    @Test fun aLaterParentFailureDoesNotDisappearBehindAnEarlierAttemptChildFailure() {
        val output = GoTestOutput("/tmp/work")
        val records = listOf(
            event("run", test = "TestRepeat"), event("run", test = "TestRepeat/child"),
            event("fail", test = "TestRepeat/child"), event("fail", test = "TestRepeat"),
            event("run", test = "TestRepeat"), event("run", test = "TestRepeat/child"),
            event("pass", test = "TestRepeat/child"), event("fail", test = "TestRepeat"), event("fail"),
        ).flatMap(output::feed).joinToString("")
        assertEquals(1, Regex("nodeId='go-test:example.com/app#TestRepeat:2:parent-result' parentNodeId=").findAll(records).count())
        assertEquals(3, Regex("##teamcity\\[goTestLeafResult ").findAll(records).count())
        assertTrue(records.contains("nodeId='go-test:example.com/app#TestRepeat/child:2' parentNodeId='go-test:example.com/app#TestRepeat:2'"))
        assertEquals(mapOf("example.com/app" to listOf("TestRepeat")), output.failures(), "A direct parent failure needs a whole-parent retry")
    }

    @Test fun slashNamesCloseVirtualSuitesWithoutInventingFailuresAcrossAttempts() {
        val output = GoTestOutput("/tmp/work")
        val records = buildList {
            repeat(2) {
                addAll(output.feed(event("run", test = "TestPath")))
                addAll(output.feed(event("run", test = "TestPath/dir/file")))
                addAll(output.feed(event("pass", test = "TestPath/dir/file")))
                addAll(output.feed(event("pass", test = "TestPath")))
            }
            addAll(output.feed(event("pass")))
            addAll(output.finish(0))
        }.joinToString("")
        assertFalse(records.contains("testFailed"), records)
        assertEquals(2, Regex("##teamcity\\[goTestLeafResult ").findAll(records).count())
        assertEquals(2, Regex("##teamcity\\[testSuiteFinished name='dir'").findAll(records).count())
        assertTrue(records.contains("nodeId='go-test:example.com/app#TestPath/dir:2' parentNodeId='go-test:example.com/app#TestPath:2'"))
        assertTrue(output.failures().isEmpty())
    }

    @Test fun realTestsSharingVirtualPathNamesRetainResultsAndFailuresInEitherOrder() {
        for (pathFirst in listOf(true, false)) for (failing in listOf(true, false)) {
            val output = GoTestOutput("/tmp/work")
            val paths = if (pathFirst) listOf("TestPath/dir/file", "TestPath/dir") else listOf("TestPath/dir", "TestPath/dir/file")
            val records = buildList {
                addAll(output.feed(event("run", test = "TestPath")))
                for (name in paths) {
                    addAll(output.feed(event("run", test = name)))
                    addAll(output.feed(event(if (failing && name == "TestPath/dir") "fail" else "pass", test = name)))
                }
                addAll(output.feed(event(if (failing) "fail" else "pass", test = "TestPath")))
                addAll(output.feed(event(if (failing) "fail" else "pass")))
                addAll(output.finish(if (failing) 1 else 0))
            }.joinToString("")
            assertEquals(2, Regex("##teamcity\\[goTestLeafResult ").findAll(records).count(), records)
            assertEquals(if (failing) 1 else 0, Regex("##teamcity\\[testFailed ").findAll(records).count(), records)
            assertEquals(if (failing) mapOf("example.com/app" to listOf("TestPath/dir")) else emptyMap(), output.failures())
        }
    }

    @Test fun mutationFailureUsesItsSavedCorpusOnlyAfterTheMatchingTestFails() {
        val output = GoTestOutput("/tmp/work", mapOf("example.com/app" to "/tmp/work", "example.com/other" to "/tmp/other"))
        output.feed(event("run", test = "FuzzDecode"))
        output.feed(event("run", pkg = "example.com/other", test = "FuzzDecode"))
        output.feed(event("output", test = "FuzzDecode", output = "    Failing input written to testdata/fuzz/FuzzDecode/0123456789abcdef\n"))
        assertTrue(output.failures().isEmpty(), "An output hint alone is not a failure")
        output.feed(event("fail", test = "FuzzDecode"))
        output.feed(event("fail", pkg = "example.com/other", test = "FuzzDecode"))
        output.feed(event("fail")); output.feed(event("fail", pkg = "example.com/other"))
        assertEquals(mapOf("example.com/app" to listOf("FuzzDecode/0123456789abcdef"),
            "example.com/other" to listOf("FuzzDecode")), output.failures())
    }

    @Test fun corpusFailureHintsMustMatchThePackageTestAndAnUnambiguousExactPath() {
        val invalid = listOf(
            "testdata/fuzz/FuzzOther/0123456789abcdef",
            "testdata/fuzz/FuzzDecode/../0123456789abcdef",
            "/tmp/other/testdata/fuzz/FuzzDecode/0123456789abcdef",
            "testdata/fuzz/FuzzDecode/seed_name",
        )
        for (path in invalid) {
            val output = GoTestOutput("/tmp/work", mapOf("example.com/app" to "/tmp/work"))
            output.feed(event("run", test = "FuzzDecode"))
            output.feed(event("output", test = "FuzzDecode", output = "    Failing input written to $path\n"))
            output.feed(event("fail", test = "FuzzDecode"))
            assertEquals(listOf("FuzzDecode"), output.failures()["example.com/app"], path)
        }
        for (metadata in listOf(emptyMap(), mapOf("example.com/app" to "/tmp/work"))) {
            val output = GoTestOutput("/tmp/work", metadata)
            output.feed(event("run", test = "FuzzDecode"))
            for (name in listOf("0123456789abcdef", "fedcba9876543210"))
                output.feed(event("output", test = "FuzzDecode", output = "    Failing input written to testdata/fuzz/FuzzDecode/$name\n"))
            output.feed(event("fail", test = "FuzzDecode"))
            assertEquals(listOf("FuzzDecode"), output.failures()["example.com/app"], "Absent metadata or ambiguous hints must not guess")
        }
    }

    @Test fun savedCorpusHintsDoNotInventAConfirmedFailureOrDuplicateAnExistingChild() {
        val hint = event("output", test = "FuzzDecode", output = "    Failing input written to testdata/fuzz/FuzzDecode/0123456789abcdef\n")
        val missing = GoTestOutput("/tmp/work", mapOf("example.com/app" to "/tmp/work"))
        missing.feed(event("run", test = "FuzzDecode")); missing.feed(hint); missing.finish(1)
        assertEquals(listOf("FuzzDecode"), missing.failures()["example.com/app"], "Missing terminal result must not confirm a saved case")
        val passed = GoTestOutput("/tmp/work", mapOf("example.com/app" to "/tmp/work"))
        passed.feed(event("run", test = "FuzzDecode")); passed.feed(hint); passed.feed(event("pass", test = "FuzzDecode")); passed.finish(0)
        assertTrue(passed.failures().isEmpty())
        val nested = GoTestOutput("/tmp/work", mapOf("example.com/app" to "/tmp/work"))
        nested.feed(event("run", test = "FuzzDecode")); nested.feed(hint)
        nested.feed(event("run", test = "FuzzDecode/0123456789abcdef"))
        nested.feed(event("fail", test = "FuzzDecode/0123456789abcdef")); nested.feed(event("fail", test = "FuzzDecode")); nested.finish(1)
        assertEquals(listOf("FuzzDecode/0123456789abcdef"), nested.failures()["example.com/app"])
    }

    @Test fun stoppedRunsIgnoreLatePassAndPackagePassWhileKeepingEarlierConfirmedResults() {
        val output = GoTestOutput("/tmp/work", mapOf("example.com/app" to "/tmp/work"))
        val before = listOf(event("run", test = "TestPassed"), event("pass", test = "TestPassed"),
            event("run", test = "TestFailed"), event("fail", test = "TestFailed"),
            event("run", test = "FuzzDecode")).flatMap(output::feed).joinToString("")
        output.requestCancellation()
        val after = listOf(event("pass", test = "FuzzDecode"), event("pass")).flatMap(output::feed).joinToString("") + output.finish(1).joinToString("")
        assertTrue(before.contains("#TestPassed:1' outcome='pass'"), before)
        assertTrue(after.contains("testIgnored name='FuzzDecode'"), after)
        assertFalse(after.contains("outcome='pass'"), after)
        assertEquals(listOf("TestFailed"), output.failures()["example.com/app"])
        val benchmark = GoTestOutput("/tmp/work")
        benchmark.feed(event("run", test = "BenchmarkEcho")); benchmark.requestCancellation()
        val benchmarkEvents = benchmark.feed(event("pass")).joinToString("")
        assertTrue(benchmarkEvents.contains("testIgnored name='BenchmarkEcho'"), benchmarkEvents)
        assertFalse(benchmarkEvents.contains("outcome='pass'"), benchmarkEvents)
    }

    @Test fun stoppedMutationDoesNotTurnItsSavedCorpusHintIntoARetry() {
        for (terminal in listOf("pass", "fail")) {
            val output = GoTestOutput("/tmp/work", mapOf("example.com/app" to "/tmp/work"))
            output.feed(event("run", test = "FuzzDecode"))
            output.feed(event("output", test = "FuzzDecode", output = "    Failing input written to testdata/fuzz/FuzzDecode/0123456789abcdef\n"))
            output.requestCancellation()
            val records = output.feed(event(terminal, test = "FuzzDecode")).joinToString("") + output.feed(event(terminal)).joinToString("")
            assertTrue(output.failures().isEmpty(), records)
            assertTrue(records.contains("outcome='skip'"), records)
            assertFalse(records.contains("outcome='pass'"), records)
        }
    }
}
