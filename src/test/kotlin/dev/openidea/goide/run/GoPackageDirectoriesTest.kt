package dev.openidea.goide.run

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNull
import dev.openidea.goide.coverage.GoCoverageModule

class GoPackageDirectoriesTest {
    @Test fun concatenatedGoListPackagesRetainTheirOwnDirectories() {
        val metadata = GoPackageDirectories.parse("""{"ImportPath":"example.com/app/a","Dir":"/work/a"}
            {"ImportPath":"example.com/app/b","Dir":"/work/b"}
            {"ImportPath":"incomplete","Error":{"Err":"invalid package"}}
        """)
        assertEquals(mapOf("example.com/app/a" to "/work/a", "example.com/app/b" to "/work/b"), metadata)
        val output = GoTestOutput("/work", metadata)
        val records = output.feed("""{"Action":"run","Package":"example.com/app/b","Test":"TestX"}
            {"Action":"output","Package":"example.com/app/b","Test":"TestX","Output":"    shared_test.go:17: failure\n"}
            {"Action":"fail","Package":"example.com/app/b","Test":"TestX"}
        """.trimIndent() + "\n").joinToString("")
        assertEquals("file:///work/b/shared_test.go:17", output.locationForNode("go-test:example.com/app/b#TestX:1"))
        assertFalse(records.contains("file:///work/shared_test.go"))
    }

    @Test fun unavailableOrAmbiguousMetadataDoesNotProduceMisleadingSourceLinks() {
        val metadata = GoPackageDirectories.parse("""{"ImportPath":"same","Dir":"/work/a"}{"ImportPath":"same","Dir":"/work/b"}""")
        assertTrue(metadata.isEmpty())
        val output = GoTestOutput("/work", metadata)
        val records = output.feed("""{"Action":"output","Package":"same","Test":"TestX","Output":"    shared_test.go:17: failure\n"}
            {"Action":"fail","Package":"same","Test":"TestX"}
        """.trimIndent() + "\n").joinToString("")
        assertNull(output.locationForNode("go-test:same#TestX:1"))
        assertFalse(records.contains("file://"))
    }

    @Test fun coverageModulesUseRealReplacementRootsAndRetainFileScopePackages() {
        val metadata = GoPackageDirectories.parseMetadata("""
            {"ImportPath":"example.com/app/a","Dir":"/work/app/a","Module":{"Path":"example.com/app","Dir":"/work/app"}}
            {"ImportPath":"example.com/lib","Dir":"/work/replacement","Module":{"Path":"example.com/lib","Dir":"/cache/old","Replace":{"Dir":"/work/replacement"}}}
            {"ImportPath":"example.com/lib/child","Dir":"/work/replacement/child","Module":{"Path":"example.com/lib","Replace":{"Dir":"/work/replacement"}}}
            {"ImportPath":"command-line-arguments","Dir":"/work/files"}
        """.trimIndent())
        assertEquals(listOf(GoCoverageModule("example.com/app", "/work/app"),
            GoCoverageModule("example.com/lib", "/work/replacement"), GoCoverageModule("command-line-arguments", "/work/files")), metadata.modules)
        assertEquals("/work/replacement/child", metadata.directories["example.com/lib/child"])
    }

    @Test fun ambiguousModuleRootsCannotInventCoverageMappings() {
        val metadata = GoPackageDirectories.parseMetadata("""
            {"ImportPath":"example.com/dup/a","Dir":"/work/one/a","Module":{"Path":"example.com/dup","Dir":"/work/one"}}
            {"ImportPath":"example.com/dup/b","Dir":"/work/two/b","Module":{"Path":"example.com/dup","Dir":"/work/two"}}
        """.trimIndent())
        assertTrue(metadata.modules.isEmpty())
        assertEquals(2, metadata.directories.size)
    }
}
