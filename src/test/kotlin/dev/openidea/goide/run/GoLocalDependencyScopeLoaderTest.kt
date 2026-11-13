package dev.openidea.goide.run

import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.util.SystemInfo
import com.intellij.testFramework.LightPlatformTestCase
import dev.openidea.goide.lsp.GoDocumentSync
import dev.openidea.goide.settings.GoToolchain
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Selected-SDK graph discovery, independent from the ordinary package-membership operation. */
class GoLocalDependencyScopeLoaderTest : LightPlatformTestCase() {
    override fun runInDispatchThread() = false

    fun testActiveNormalAndTestImportsExcludeUnusedReplacementDirectives() = Fixture().use { f ->
        val external = f.module("external", "example.test/external")
        val tests = f.module("tests", "example.test/tests")
        val unused = f.module("unused", "example.test/unused")
        f.manifest(listOf("example.test/external" to external, "example.test/tests" to tests, "example.test/unused" to unused))
        val source = f.write("main.go", "package sample\nimport _ \"example.test/external\"\n")
        f.write("main_test.go", "package sample_test\nimport _ \"example.test/tests\"\n")
        val snapshot = f.load(f.request(source))
        assertTrue(snapshot.diagnostics.toString(), snapshot.complete)
        assertEquals(setOf(external, tests), snapshot.roots)
        assertTrue(snapshot.watchedFileUris.contains(uri(external.resolve("go.mod"))))
        assertFalse(snapshot.watchedFileUris.contains(uri(unused.resolve("go.mod"))))
        snapshot.validateDisk()
        Files.writeString(external.resolve("go.mod"), "module example.test/external\n\ngo 1.26\n// changed\n")
        expectChanged { snapshot.validateDisk() }
    }

    fun testDirtyWorkspaceUseAndWorkspaceReplaceChooseActualLoadedRoots() = Fixture().use { f ->
        val fallback = f.module("fallback", "example.test/dependency")
        val workspace = f.module("workspace", "example.test/dependency")
        val override = f.module("override", "example.test/dependency")
        f.manifest(listOf("example.test/dependency" to fallback))
        val source = f.write("main.go", "package sample\nimport _ \"example.test/dependency\"\n")
        val work = Files.writeString(f.root.resolve("go.work"), "go 1.26\nuse (\n ./module\n ./workspace\n)\n")
        val settings = f.settings.copy(environment = f.settings.environment + "\nGOWORK=$work")
        val main = f.load(f.request(source, settings = settings))
        assertTrue(main.diagnostics.toString(), main.complete)
        assertEquals(emptySet<Path>(), main.roots)
        val dirty = "go 1.26\nuse ./module\nreplace example.test/dependency => ./override\n"
        val replacement = f.load(f.request(source, settings = settings, overlays = listOf(overlay(work, dirty))))
        assertTrue(replacement.diagnostics.toString(), replacement.complete)
        assertEquals(setOf(override), replacement.roots)
        assertTrue(replacement.watchedFileUris.contains(uri(work)))
        assertTrue(Files.readString(work).contains("./workspace"))
        assertFalse(replacement.roots.contains(workspace))
    }

    fun testDirtyImporterAndModfileUsePrivateViewWithOriginalRelativeReplacementMeaning() = Fixture().use { f ->
        val first = f.module("first", "example.test/dependency")
        val second = f.module("second", "example.test/dependency")
        f.manifest(listOf("example.test/dependency" to first))
        val source = f.write("main.go", "package sample\n")
        val mod = f.directory.resolve("go.mod")
        val dirtyMod = Files.readString(mod).replace("../first", "../second")
        val snapshot = f.load(f.request(source, overlays = listOf(
            overlay(source, "package sample\nimport _ \"example.test/dependency\"\n"), overlay(mod, dirtyMod))))
        assertTrue(snapshot.diagnostics.toString(), snapshot.complete)
        assertEquals(setOf(second), snapshot.roots)
        assertEquals("package sample\n", Files.readString(source))
        assertTrue(Files.readString(mod).contains("../first"))
    }

    fun testSelectedTagsChangeTheActiveDependencyGraph() = Fixture().use { f ->
        val alpha = f.module("alpha", "example.test/alpha")
        val beta = f.module("beta", "example.test/beta")
        f.manifest(listOf("example.test/alpha" to alpha, "example.test/beta" to beta))
        val source = f.write("main.go", "package sample\n")
        f.write("alpha.go", "//go:build alpha\n\npackage sample\nimport _ \"example.test/alpha\"\n")
        f.write("beta.go", "//go:build !alpha\n\npackage sample\nimport _ \"example.test/beta\"\n")
        val defaults = f.load(f.request(source))
        val selected = f.load(f.request(source, settings = f.settings.copy(buildTags = "alpha")))
        assertTrue(defaults.diagnostics.toString(), defaults.complete)
        assertTrue(selected.diagnostics.toString(), selected.complete)
        assertEquals(setOf(beta), defaults.roots)
        assertEquals(setOf(alpha), selected.roots)
    }

    fun testDirtyReplacementManifestRecomputesTheTransitiveSelectedVersion() = Fixture().use { f ->
        val external = f.module("external", "example.test/external")
        val first = f.module("first", "example.test/nested")
        val second = f.module("second", "example.test/nested")
        f.manifest(listOf("example.test/external" to external))
        Files.writeString(f.directory.resolve("go.mod"), Files.readString(f.directory.resolve("go.mod")) +
            "replace example.test/nested v0.0.1 => ../first\nreplace example.test/nested v0.0.2 => ../second\n")
        val mod = Files.writeString(external.resolve("go.mod"), "module example.test/external\n\ngo 1.26\nrequire example.test/nested v0.0.1\n")
        Files.writeString(external.resolve("dependency.go"), "package dependency\nimport _ \"example.test/nested\"\n")
        val source = f.write("main.go", "package sample\nimport _ \"example.test/external\"\n")
        val snapshot = f.load(f.request(source, overlays = listOf(overlay(mod, Files.readString(mod).replace("v0.0.1", "v0.0.2"))),
            buildFlags = "-mod=mod"))
        assertTrue(snapshot.diagnostics.toString(), snapshot.complete)
        assertEquals(setOf(external, second), snapshot.roots)
        assertTrue(snapshot.watchedFileUris.contains(uri(mod)))
        assertFalse(snapshot.roots.contains(first))
        assertTrue(Files.readString(mod).contains("v0.0.1"))
    }

    fun testVendorLocationsDoNotWatchTheUnusedOriginalReplacementTree() = Fixture().use { f ->
        val external = f.module("external", "example.test/external")
        f.manifest(listOf("example.test/external" to external))
        val source = f.write("main.go", "package sample\nimport _ \"example.test/external\"\n")
        f.runGo(listOf("mod", "vendor"))
        assertTrue(Files.isRegularFile(f.directory.resolve("vendor/example.test/external/dependency.go")))
        val snapshot = f.load(f.request(source))
        assertTrue(snapshot.diagnostics.toString(), snapshot.complete)
        assertEquals(emptySet<Path>(), snapshot.roots)
    }

    fun testIncompleteGraphCannotBecomeASuccessfulEmptyScope() = Fixture().use { f ->
        val source = f.write("main.go", "package sample\nimport _ \"example.test/missing\"\n")
        val snapshot = f.load(f.request(source))
        assertFalse(snapshot.complete)
        assertTrue(snapshot.diagnostics.toString(), snapshot.diagnostics.any { it.contains("example.test/missing") })
    }

    fun testUnrelatedOversizedDirtyDocumentDoesNotAffectDependencyDiscovery() = Fixture().use { f ->
        val external = f.module("external", "example.test/external")
        f.manifest(listOf("example.test/external" to external))
        val source = f.write("main.go", "package sample\nimport _ \"example.test/external\"\n")
        val readme = f.write("README.md", "An unrelated editor document\n")
        val marker = GoRunBuildOverlay(uri(readme), "", 1, null, oversized = true)
        val snapshot = f.load(f.request(source, overlays = listOf(marker)))
        assertTrue(snapshot.diagnostics.toString(), snapshot.complete)
        assertEquals(setOf(external), snapshot.roots)
        assertFalse(snapshot.watchedFileUris.contains(uri(readme)))
    }

    fun testOversizedEffectiveSourceAndGoEnvironmentFailExplicitly() = Fixture().use { f ->
        val source = f.write("main.go", "package sample\n")
        val environment = f.write("custom.env", "GOOS=darwin\n")
        val sourceRequest = f.request(source, overlays = listOf(GoRunBuildOverlay(uri(source), "", 1, null, oversized = true)))
        val environmentRequest = f.request(source, settings = f.settings.copy(environment = f.settings.environment + "\nGOENV=$environment"),
            overlays = listOf(GoRunBuildOverlay(uri(environment), "", 2, null, oversized = true)))
        for (request in listOf(sourceRequest, environmentRequest)) {
            try {
                f.load(request)
                fail("An oversized effective input must fail rather than using its saved bytes or an empty placeholder")
            } catch (failure: java.util.concurrent.ExecutionException) {
                assertTrue(failure.toString(), failure.cause is IllegalArgumentException)
                assertTrue(failure.toString(), failure.cause?.message.orEmpty().contains("size limit"))
            }
        }
    }

    fun testPreStartDisposalPreventsDependencyProcessCreation() = Fixture().use { f ->
        val source = f.write("main.go", "package sample\n")
        val loader = GoRunBuildContextLoader(project)
        val operation = loader.createDependencyScope(f.request(source))
        operation.dispose()
        operation.start()
        assertTrue(operation.future.isCancelled)
        loader.dispose()
    }

    private inner class Fixture : AutoCloseable {
        val root = Files.createTempDirectory("go-local-dependency-scope-").toRealPath()
        val directory = Files.createDirectory(root.resolve("module"))
        private val go = Path.of(requireNotNull(GoToolchain.resolve("go",
            System.getProperty("opengoland.testGo") ?: System.getenv("OPENGOLAND_GO_TEST").orEmpty())))
        val settings = GoToolchain.Settings(go.toString(), "", "", "", "", "", "0",
            "GOROOT=${go.toRealPath().parent.parent}\nGOENV=off\nGOTOOLCHAIN=local\nGOWORK=off\nGOPROXY=off\nGOSUMDB=off\nGOTELEMETRY=off\nGOFLAGS=")
        private val values = GoRunParameters(passParentEnvironment = false, environment = linkedMapOf(
            "GOCACHE" to (System.getenv("GOCACHE") ?: root.resolve("cache").toString()),
            "GOPATH" to root.resolve("gopath").toString(),
        ).apply { if (SystemInfo.isWindows) System.getenv("SystemRoot")?.let { put("SystemRoot", it) } })
        init { manifest(emptyList()) }
        fun write(name: String, text: String): Path = Files.writeString(directory.resolve(name), text)
        fun module(name: String, path: String): Path = Files.createDirectory(root.resolve(name)).also {
            Files.writeString(it.resolve("go.mod"), "module $path\n\ngo 1.26\n")
            Files.writeString(it.resolve("dependency.go"), "package dependency\n")
        }
        fun manifest(dependencies: List<Pair<String, Path>>) {
            write("go.mod", buildString {
                append("module example.test/loader\n\ngo 1.26\n")
                dependencies.forEach { (name, path) ->
                    append("require $name v0.0.0\nreplace $name => ../${path.fileName}\n")
                }
            })
        }
        fun request(source: Path, settings: GoToolchain.Settings = this.settings, overlays: List<GoRunBuildOverlay> = emptyList(), buildFlags: String = "") =
            GoRunBuildContextRequest(uri(source), directory.toString(), values.independentCopy().apply { this.buildFlags = buildFlags }, settings, overlays)
        fun load(request: GoRunBuildContextRequest): GoLocalDependencyScopeSnapshot {
            val before = inputs()
            val loader = GoRunBuildContextLoader(project)
            try {
                val operation = loader.createDependencyScope(request)
                operation.start()
                return operation.future.get(5, TimeUnit.SECONDS)
            } finally {
                loader.dispose()
                assertEquals("Dependency discovery must not write any source/module/workspace input", before, inputs())
            }
        }
        fun runGo(arguments: List<String>) {
            val handler = CapturingProcessHandler(GoToolchain.command(project, "go", arguments, settings)
                .withWorkDirectory(directory.toFile()).withEnvironment(values.environment))
            handler.setShouldDestroyProcessRecursively(true)
            try {
                val output = handler.runProcess(20_000, true)
                assertFalse(output.isTimeout || output.isCancelled)
                assertEquals(output.stderr, 0, output.exitCode)
            } finally { if (!handler.isProcessTerminated) handler.destroyProcess() }
        }
        private fun inputs(): Map<String, String> = Files.walk(root).use { paths -> paths.filter { path ->
            Files.isRegularFile(path) && !path.startsWith(root.resolve("cache")) && !path.startsWith(root.resolve("gopath"))
        }.sorted().toList().associate { path ->
            root.relativize(path).toString() to MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }
        } }
        override fun close() { Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }

    private fun uri(path: Path) = GoDocumentSync.fileUri(path)
    private fun overlay(path: Path, text: String) = GoRunBuildOverlay(uri(path), text, 1, null)
    private fun expectChanged(action: () -> Unit) {
        try { action(); fail("Changed dependency metadata must invalidate the published scope") }
        catch (_: IllegalArgumentException) {}
    }
}
