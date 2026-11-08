package dev.openidea.goide.lint

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoLintParserTest {
    @Test fun jsonFromBothOfficialSchemaVersionsKeepsLinterSeverityAndPosition() = withModule { directory ->
        val file = directory.resolve("中文文件.go")
        Files.writeString(file, "package fixture\n")
        // v1.64.8 uses Replacement; v2.15.0 uses SuggestedFixes. Unknown additions must not break diagnostics.
        for (extra in listOf("\"Replacement\": {\"NewLines\": [\"fixed\"]}", "\"SuggestedFixes\": []")) {
            val json = """{"Issues":[{"FromLinter":"govet","Text":"bad {format} \"quoted\"","Severity":"error","Pos":{"Filename":"中文文件.go","Offset":42,"Line":3,"Column":17},$extra}],"Report":{"Linters":[]}}"""
            val issue = GoLintParser.golangci(json, directory.toString()).single()
            assertEquals("govet", issue.linter)
            assertEquals("error", issue.severity)
            assertEquals(3, issue.line)
            assertEquals(17, issue.byteColumn)
            assertEquals(file.toRealPath().toString(), issue.localPath)
        }
        assertTrue(GoLintParser.golangci("""{"Issues":[],"Report":{}}""", directory.toString()).isEmpty())
        assertTrue(GoLintParser.golangci("""{"Issues":null}""", directory.toString()).isEmpty())
        assertFailsWith<IllegalArgumentException> { GoLintParser.golangci("""{"error":"invalid configuration"}""", directory.toString()) }
    }

    @Test fun vetJsonMixedWithPackageHeadersAndMultipleObjectsIsParsed() = withModule { directory ->
        val file = Files.writeString(directory.resolve("main.go"), "package fixture\n")
        val escapedPath = file.toString().replace("\\", "\\\\")
        val text = """
            # example.com/a
            # [example.com/a]
            {"example.com/a":{"printf":[{"posn":"$escapedPath:2:18","message":"bad {format} and \"quote\""}]}}
            # example.com/b
            {"example.com/b":{"assign":[{"posn":"$escapedPath:7:4","message":"self-assignment"}]}}
        """.trimIndent()
        val issues = GoLintParser.vet(text, directory.toString())
        assertEquals(listOf("printf", "assign"), issues.map { it.linter })
        assertEquals(listOf(2, 7), issues.map { it.line })
        assertTrue(issues.all { it.localPath == file.toRealPath().toString() })
    }

    @Test fun textVetLocationsAndUnmappedWindowsPathsDoNotLoseColumns() = withModule { directory ->
        Files.writeString(directory.resolve("main.go"), "package fixture\n")
        val issues = GoLintParser.vet("# example.com/a\n./main.go:3:15: bad format\n./main.go:4: compiler error\nC:\\source\\main.go:8:5: windows position\n", directory.toString())
        assertEquals(listOf(3, 4, 8), issues.map { it.line })
        assertEquals(listOf(15, 0, 5), issues.map { it.byteColumn })
        assertEquals("C:\\source\\main.go", issues.last().filename)
        assertNull(issues.last().localPath)
    }

    @Test fun sameNameFilesAreResolvedOnlyInTheSelectedModuleAndAmbiguityIsRefused() = withModule { root ->
        val first = Files.createDirectory(root.resolve("first"))
        val second = Files.createDirectory(root.resolve("second"))
        val firstFile = Files.writeString(first.resolve("same.go"), "package first\n")
        val secondFile = Files.writeString(second.resolve("same.go"), "package second\n")
        assertEquals(firstFile.toRealPath().toString(), GoLintParser.vet("same.go:2:1: first", first.toString()).single().localPath)
        assertEquals(secondFile.toRealPath().toString(), GoLintParser.vet("same.go:2:1: second", second.toString()).single().localPath)
        assertNull(GoInspectionLocations.resolve("same.go", first.toString(), listOf(second.toString())))
        assertNull(GoInspectionLocations.resolve("missing.go", first.toString()))
    }

    @Test fun goByteColumnsConvertToUtf16WithoutSplittingChineseOrEmoji() {
        val text = "var 中文 = \"😀\"; fmt.Printf(\"%d\", \"wrong\")"
        val target = text.indexOf("fmt")
        val byteColumn = text.substring(0, target).toByteArray(Charsets.UTF_8).size + 1
        assertEquals(target, GoInspectionLocations.utf16Column(text, byteColumn))
        assertEquals(0, GoInspectionLocations.utf16Column(text, 0))
        assertEquals(text.length, GoInspectionLocations.utf16Column(text, 999))
        assertEquals(0, GoInspectionLocations.utf16Column("😀x", 3))
        assertEquals(2, GoInspectionLocations.utf16Column("😀x", 5))
    }

    private fun withModule(action: (Path) -> Unit) {
        val directory = Files.createTempDirectory("openidea-lint-parser-").toRealPath()
        try { action(directory) } finally { Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }
}
