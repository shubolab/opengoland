package dev.openidea.goide.run

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GoRunParametersTest {
    @Test fun quotedArgumentsRemainDistinctWithoutAShell() {
        val values = GoRunParameters(target = "./cmd/server", arguments = "--name \"hello world\" '\$(touch /tmp/not-a-command)'", buildFlags = "-race")
        assertEquals(listOf("run", "-tags=integration,feature", "-race", "./cmd/server", "--name", "hello world", "\$(touch /tmp/not-a-command)"), values.commandArguments(GoRunKind.BUILD, "integration feature"))
    }
    @Test fun explicitTagsAndTestSelectionSurvivePlanCreation() {
        val values = GoRunParameters(target = "./...", buildFlags = "-tags=unit -count=1", testPattern = "^TestA$/^row\\[1\\]$", arguments = "-timeout 20s")
        assertEquals(listOf("test", "-json", "-tags=unit", "-count=1", "-run", "^TestA$/^row\\[1\\]$", "./...", "-timeout", "20s"), values.commandArguments(GoRunKind.TEST, "integration"))
    }
    @Test fun buildOnlySupportsOutputPathWithSpaces() {
        val values = GoRunParameters(target = ".", runAfterBuild = false, outputPath = "/tmp/my output/server")
        assertEquals(listOf("build", "-o", "/tmp/my output/server", "."), values.commandArguments(GoRunKind.BUILD, ""))
    }
    @Test fun invalidTargetsAndEnvironmentAreRejected() {
        assertFailsWith<IllegalArgumentException> { GoRunParameters(target = "-x").validate() }
        assertFailsWith<IllegalArgumentException> { GoRunParameters(environment = linkedMapOf("BAD=KEY" to "value")).validate() }
        assertFailsWith<IllegalArgumentException> { GoRunParameters(arguments = "one\u0000two").validate() }
    }
    @Test fun clonesDoNotShareEnvironmentAndGoPatternsAreQuoted() {
        val original = GoRunParameters(environment = linkedMapOf("KEY" to "one"))
        val copied = original.independentCopy(); copied.environment["KEY"] = "two"
        assertEquals("one", original.environment["KEY"])
        assertEquals("row\\[1\\]\\+a", GoRerunFailedTestsAction.goQuoteMeta("row[1]+a"))
    }

    @Test fun coverageRetainsPackageScopeFlagsAndQuotedArgumentsWithoutMutatingTheConfiguration() {
        val values = GoRunParameters(target = "./first/... ./second", workingDirectory = "/work/my project",
            buildFlags = "-race -count=1 -covermode=atomic", testPattern = "^TestCart$/^two_products$",
            arguments = "-timeout 30s -args -label='two products'", environment = linkedMapOf("PRICE" to "25"))
        val original = values.independentCopy()
        assertEquals(listOf("test", "-json", "-coverprofile=/tmp/profile with spaces.out", "-tags=unit,integration",
            "-race", "-count=1", "-covermode=atomic", "-run", "^TestCart$/^two_products$", "./first/...", "./second",
            "-timeout", "30s", "-args", "-label=two products"),
            values.commandArguments(GoRunKind.TEST, "unit integration", "/tmp/profile with spaces.out"))
        assertEquals(original, values)
    }

    @Test fun coverageOwnsItsProfileAndOnlySupportsTestConfigurations() {
        for (flag in listOf("-coverprofile=/tmp/override", "-coverprofile /tmp/override", "-test.coverprofile=/tmp/override")) {
            assertFailsWith<IllegalArgumentException> { GoRunParameters(buildFlags = flag).commandArguments(GoRunKind.TEST, "", "/tmp/ide.cover") }
            assertFailsWith<IllegalArgumentException> { GoRunParameters(arguments = flag).commandArguments(GoRunKind.TEST, "", "/tmp/ide.cover") }
        }
        assertFailsWith<IllegalArgumentException> { GoRunParameters().commandArguments(GoRunKind.BUILD, "", "/tmp/ide.cover") }
    }
}
