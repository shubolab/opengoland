package dev.openidea.goide.run.corpus

import com.intellij.openapi.util.io.ByteArraySequence
import com.intellij.testFramework.LightVirtualFile
import dev.openidea.goide.run.GoTestOutput
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class GoFuzzCorpusTest {
    @Test fun contentDetectionRequiresBothTheGoVersionHeaderAndAnActualCorpusPathWithoutReadingTheFile() {
        val detector = GoFuzzCorpusFileTypeDetector()
        fun file(path: String) = object : LightVirtualFile("817e8c7d02cb3556", GoFuzzCorpusFileType.INSTANCE, "") {
            override fun getPath() = path
            override fun contentsToByteArray(): ByteArray = error("Detection must consume only the supplied prefix")
            override fun getInputStream(): java.io.InputStream = error("Detection must not read the file")
        }
        val bytes = ByteArraySequence("go test fuzz v1\n[]byte(\"中文😀\")\n".toByteArray())
        val path = "/workspace/pkg/testdata/fuzz/FuzzDecode/817e8c7d02cb3556"
        assertSame(GoFuzzCorpusFileType.INSTANCE, detector.detect(file(path), bytes, null))
        assertSame(GoFuzzCorpusFileType.INSTANCE, detector.detect(file(path), bytes, "go test fuzz v1\r\n[]byte(\"data\")"))
        for (wrong in listOf("/workspace/pkg/817e8c7d02cb3556", "/workspace/pkg/testdata/FuzzDecode/817e8c7d02cb3556",
            "/workspace/pkg/testdata/fuzz/Fuzzdecode/817e8c7d02cb3556", "/workspace/pkg/testdata/fuzz/notFuzz/817e8c7d02cb3556"))
            assertNull(detector.detect(file(wrong), bytes, "go test fuzz v1\n"))
        for (wrong in listOf("go test fuzz v2\n", "\ufeffgo test fuzz v1\n", "go test fuzz v1", "go test fuzz v1-more\n", "plain text\n"))
            assertNull(detector.detect(file(path), bytes, wrong))
    }

    @Test fun replayPatternIsAnchoredAtBothGoSlashComponentsAndNeverUsesMutationMode() {
        val corpus = GoFuzzCorpusPath.fromPath("/workspace/pkg/testdata/fuzz/Fuzz分析/case-1.json")!!
        assertEquals(Path.of("/workspace/pkg"), corpus.packageDirectory)
        assertEquals("Fuzz分析", corpus.fuzzFunction)
        assertEquals("^Fuzz分析$/^case-1\\.json$", corpus.testPattern)
        assertTrue(corpus.canReplayExactly)
        for (name in listOf("seed_name", "seed name", "seed#0", "case[1]+.json")) {
            val unsupported = GoFuzzCorpusPath.fromPath("/workspace/pkg/testdata/fuzz/Fuzz分析/$name")!!
            assertFalse(unsupported.canReplayExactly)
            assertFailsWith<IllegalArgumentException> { unsupported.testPattern }
        }
        assertTrue(GoFuzzCorpusPath.hasHeader("go test fuzz v1\n"))
        assertTrue(GoFuzzCorpusPath.hasHeader("go test fuzz v1\r\n"))
        assertFalse(GoFuzzCorpusPath.hasHeader("go test fuzz v1\r"))
    }

    @Test fun consoleLinksUseThePreloadedPackageDirectoryAndCorrectNativeUtf16Offsets() {
        val directory = "/workspace/子目录😀/nested"
        val path = "testdata/fuzz/FuzzDecode/817e8c7d02cb3556"
        val line = "\tFailing input written to $path\r\n"
        val filter = GoFuzzCorpusConsoleFilter(directory)
        val result = assertNotNull(filter.applyFilter(line, 500 + line.length))
        assertEquals(500 + line.indexOf(path), result.highlightStartOffset)
        assertEquals(500 + line.indexOf(path) + path.length, result.highlightEndOffset)
        val hyperlink = result.hyperlinkInfo as GoFuzzCorpusHyperlink
        assertEquals(Path.of(directory).resolve(path), hyperlink.path)
        assertEquals(hyperlink.path, GoFuzzCorpusConsoleFilter(directory).applyFilter(
            "Failing input written to ${Path.of(directory).resolve(path)}\n", 1000)?.hyperlinkInfo?.let { (it as GoFuzzCorpusHyperlink).path })
        assertNull(filter.applyFilter("unrelated output $path\n", 1000))
    }

    @Test fun ambiguousPackagesUnknownMetadataAndEscapingPathsNeverGuessACorpusLink() {
        assertEquals("/workspace/pkg", GoTestOutput("/wrong/process/cwd", mapOf("example/pkg" to "/workspace/pkg")).corpusPackageDirectory())
        assertEquals("/workspace/pkg", GoTestOutput("/wrong/process/cwd", mapOf("example/pkg" to "/workspace/pkg", "example/pkg_test" to "/workspace/pkg")).corpusPackageDirectory())
        assertNull(GoTestOutput("/wrong/process/cwd").corpusPackageDirectory())
        assertNull(GoTestOutput("/wrong/process/cwd", mapOf("one" to "/workspace/one", "two" to "/workspace/two")).corpusPackageDirectory())
        val line = "Failing input written to testdata/fuzz/FuzzDecode/817e8c7d02cb3556\n"
        assertNull(GoFuzzCorpusConsoleFilter(null).applyFilter(line, line.length))
        for (raw in listOf("../other/testdata/fuzz/FuzzDecode/817e8c7d02cb3556", "/other/testdata/fuzz/FuzzDecode/817e8c7d02cb3556",
            "testdata/fuzz/FuzzDecode/../817e8c7d02cb3556", "testdata/fuzz/notFuzz/817e8c7d02cb3556"))
            assertNull(GoFuzzCorpusConsoleFilter("/workspace/pkg").applyFilter("Failing input written to $raw\n", 1000))
    }
}
