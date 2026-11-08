package dev.openidea.goide.profile

import com.google.gson.Gson
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoProfileModelTest {
    @Test fun requestPreservesQuotedOptionsEnvironmentAndConcretePackage() {
        val request = GoProfileOptions(GoProfileKind.MEMORY, GoProfileMode.TEST, "./目录 with spaces", "^TestCart/two products$",
            "-race '-ldflags=-X main.name=a b' -timeout 30s").request("session", "/project", mapOf("GOOS" to "darwin", "MULTILINE" to "a\nb"))
        assertEquals("profile", request["command"].asString)
        assertEquals(listOf("-race", "-ldflags=-X main.name=a b", "-timeout", "30s"), request.getAsJsonArray("args").map { it.asString })
        assertEquals("./目录 with spaces", request.getAsJsonObject("profile")["target"].asString)
        assertEquals("a\nb", request.getAsJsonObject("env")["MULTILINE"].asString)
        assertFailsWith<IllegalArgumentException> { GoProfileOptions(target = "./...").request("x", "/project", emptyMap()) }
    }
    @Test fun exactRecordedPathsAndLongestTrimpathModuleMapToRealSources() {
        val root = Files.createTempDirectory("opengoland-profile-sources")
        try {
            val first = Files.createDirectories(root.resolve("first"))
            val nested = Files.createDirectories(root.resolve("nested"))
            val source = Files.writeString(nested.resolve("函数 with spaces.go"), "package example\nfunc Work() {}\n")
            val modules = listOf(GoProfileModule("example.org/mod", first.toString()), GoProfileModule("example.org/mod/nested", nested.toString()))
            assertEquals(source, GoProfileSources.resolve(GoProfileHotspot(file = source.toString(), line = 2), modules))
            assertEquals(source, GoProfileSources.resolve(GoProfileHotspot(file = "example.org/mod/nested/函数 with spaces.go", line = 2), modules))
            assertNull(GoProfileSources.resolve(GoProfileHotspot(file = "/a-different-machine/函数 with spaces.go", line = 2), modules))
            assertNull(GoProfileSources.resolve(GoProfileHotspot(file = "example.org/mod/nested/../../escape.go", line = 2), modules))
            assertNull(GoProfileSources.resolve(GoProfileHotspot(file = source.toString(), line = 0), modules))
            assertNull(GoProfileSources.resolve(GoProfileHotspot(file = source.toString(), line = Long.MAX_VALUE), modules))
            Files.writeString(first.resolve("函数 with spaces.go"), "package other\n")
            val ambiguous = listOf(GoProfileModule("example.org/mod/nested", first.toString()), GoProfileModule("example.org/mod/nested", nested.toString()))
            assertNull(GoProfileSources.resolve(GoProfileHotspot(file = "example.org/mod/nested/函数 with spaces.go", line = 2), ambiguous))
        } finally { root.toFile().deleteRecursively() }
    }
    @Test fun protobufDerivedReportKeepsMissingSourcesUnavailableAndValuesSortable() {
        val report = Gson().fromJson("""{"path":"cpu.pprof","sampleTypes":[{"type":"cpu","unit":"nanoseconds","total":37000000}],"sampleIndex":0,"sampleCount":3,"rows":[{"name":"0xabc","line":0,"flat":37000000,"cumulative":37000000,"flatPercent":100,"cumulativePercent":100}],"warnings":[],"functionCount":1}""", GoProfileReport::class.java)
        assertEquals(37000000L, report.rows.single().flat)
        assertNull(GoProfileSources.resolve(report.rows.single(), emptyList()))
        assertEquals("37.00 ms", GoProfileUnits.format(report.metric!!.total, report.metric!!.unit))
        assertEquals("1.00 MiB", GoProfileUnits.format(1048576, "bytes"))
        assertTrue(report.rows.single().flatPercent == 100.0)
    }
}
