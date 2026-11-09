package dev.openidea.goide.coverage

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class GoCoverageProfileTest {
    private val root = Path.of("coverage fixture").toAbsolutePath().normalize()
    private val app = root.resolve("app")
    private val second = root.resolve("second module")
    private val modules = listOf(GoCoverageModule("example.com/app", app.toString()), GoCoverageModule("example.com/app/sub", second.toString()))

    @Test fun statementWeightedTotalsAndLongestModulePrefixMatchRealProfileSemantics() {
        val report = GoCoverageProfile.parse("""mode: atomic
            example.com/app/main.go:3.12,7.2 3 1
            example.com/app/main.go:9.12,10.2 1 0
            example.com/app/sub/api.go:2.12,4.2 2 1
        """.trimIndent(), modules)
        assertEquals(6L, report.totals.statements)
        assertEquals(5L, report.totals.coveredStatements)
        assertEquals(100.0 * 5 / 6, report.totals.percent)
        assertEquals(second.resolve("api.go").toString(), report.files.first { it.profilePath.endsWith("api.go") }.localPath)
    }

    @Test fun duplicateBlocksMergeWithoutDoubleCountingStatements() {
        for (mode in listOf("set", "count", "atomic")) {
            val report = GoCoverageProfile.parse("mode: $mode\nexample.com/app/a.go:2.1,3.1 4 0\nexample.com/app/a.go:2.1,3.1 4 2\n", modules)
            assertEquals(4L, report.totals.statements)
            assertEquals(4L, report.totals.coveredStatements)
            assertEquals(if (mode == "set") 1L else 2L, report.files.single().blocks.single().count)
        }
    }

    @Test fun endColumnsAndMixedBlocksProduceAccurateLineStates() {
        val report = GoCoverageProfile.parse("mode: count\nexample.com/app/a.go:2.1,4.1 1 1\nexample.com/app/a.go:3.6,3.15 1 0\nexample.com/app/a.go:5.1,5.3 0 0\n", modules)
        assertEquals(mapOf(2 to GoLineCoverage.COVERED, 3 to GoLineCoverage.PARTIAL), report.files.single().lines())
    }

    @Test fun unknownPathsAndTraversalNeverMapToAnUnrelatedLocalFile() {
        assertNull(GoCoverageProfile.resolvePath("example.com/other/a.go", modules, app.toString()))
        assertNull(GoCoverageProfile.resolvePath("example.com/app/../../outside.go", modules))
        assertNull(GoCoverageProfile.resolvePath("C:/elsewhere/a.go", modules))
        assertNull(GoCoverageProfile.resolvePath(root.resolve("outside/a.go").toString(), modules))
        assertEquals(app.resolve("a.go").toString(), GoCoverageProfile.resolvePath("a.go", modules, app.toString()))
    }

    @Test fun malformedAndConflictingProfilesFailClearly() {
        assertFailsWith<IllegalArgumentException> { GoCoverageProfile.parse("mode: branches\n", modules) }
        assertFailsWith<IllegalArgumentException> { GoCoverageProfile.parse("mode: set\nwrong\n", modules) }
        assertFailsWith<IllegalArgumentException> { GoCoverageProfile.parse("mode: set\nexample.com/app/a.go:3.1,2.1 1 1", modules) }
        assertFailsWith<IllegalArgumentException> { GoCoverageProfile.parse("mode: set\nexample.com/app/a.go:2.1,3.1 1 0\nexample.com/app/a.go:2.1,3.1 2 1", modules) }
    }
}
