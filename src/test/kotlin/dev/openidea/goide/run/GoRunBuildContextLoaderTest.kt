package dev.openidea.goide.run

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.SystemInfo
import com.intellij.testFramework.LightPlatformTestCase
import dev.openidea.goide.lsp.GoDocumentSync
import dev.openidea.goide.process.GoOwnedTestExecutable
import dev.openidea.goide.settings.GoToolchain
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Actual selected Go metadata, including dirty views; no editor, engine or language server fallback. */
class GoRunBuildContextLoaderTest : LightPlatformTestCase() {
    override fun runInDispatchThread() = false

    fun testSelectedSdkTagsOsCgoAndPerRunEnvironmentChooseActualSourceMembers() = Fixture().use { f ->
        f.write("base.go", "package sample\n")
        val host = f.write("host_test.go", "//go:build !alpha && !beta\n\npackage sample\n")
        val alpha = f.write("alpha_test.go", "//go:build alpha\n\npackage sample\n")
        val beta = f.write("beta_test.go", "//go:build beta\n\npackage sample\n")
        val win = f.write("only_windows_test.go", "package sample\n")
        val cgo = f.write("native.go", "package sample\nimport \"C\"\n")
        for ((tags, os) in listOf("" to "darwin", "alpha" to "darwin", "beta" to "darwin", "" to "windows")) {
            val settings = f.settings.copy(buildTags = tags, goos = os)
            val snapshot = f.load(f.request(host, settings = settings))
            assertEquals(uri(host) in snapshot.activeSourceUris, tags.isEmpty())
            assertEquals(tags == "alpha", uri(alpha) in snapshot.activeSourceUris)
            assertEquals(tags == "beta", uri(beta) in snapshot.activeSourceUris)
            assertEquals(os == "windows", uri(win) in snapshot.activeSourceUris)
            assertTrue(uri(cgo) in snapshot.ignoredSourceUris)
        }
        val enabled = f.load(f.request(cgo, values = GoRunParameters(environment = linkedMapOf("CGO_ENABLED" to "1"))))
        assertTrue(uri(cgo) in enabled.activeSourceUris)
        val override = f.load(f.request(alpha, settings = f.settings.copy(buildTags = "beta", goos = "darwin"), values = GoRunParameters(buildFlags = "-tags=alpha", environment = linkedMapOf("GOOS" to "windows"))))
        assertTrue(uri(alpha) in override.activeSourceUris)
        assertTrue(uri(win) in override.activeSourceUris)
        val legacy = f.load(f.request(alpha, values = GoRunParameters(buildFlags = "-count=1", arguments = "-tags=alpha -timeout 20s")))
        assertTrue(uri(alpha) in legacy.activeSourceUris)
        for (debug in listOf(false, true)) {
            val binaryFlags = f.load(f.request(alpha, values = GoRunParameters(arguments = "-args -tags=alpha"), debug = debug))
            assertTrue(uri(alpha) in binaryFlags.ignoredSourceUris)
        }
        val ownEnvironment = linkedMapOf("GOCACHE" to f.root.resolve("cache").toString(), "GOPATH" to f.root.resolve("gopath").toString())
        if (SystemInfo.isWindows) System.getenv("SystemRoot")?.let { ownEnvironment["SystemRoot"] = it }
        val isolated = f.load(f.request(host, values = GoRunParameters(passParentEnvironment = false, environment = ownEnvironment)))
        assertTrue(uri(host) in isolated.activeSourceUris)
    }

    fun testDirtyConstraintsNewFilesAndManifestsNeverWriteTheOriginalInputs() = Fixture().use { f ->
        val source = f.write("tagged_test.go", "//go:build alpha\n\npackage sample\n")
        val before = f.tree()
        val text = "//go:build !alpha\n\npackage sample\nvar 前 = \"😀\"\n"
        val active = f.load(f.request(source, overlays = listOf(GoRunBuildOverlay(uri(source), text, 7, 11))))
        assertTrue(uri(source) in active.activeSourceUris); assertEquals(text, active.sourceText)
        val fresh = f.directory.resolve("new_test.go")
        val freshText = "package sample\nvar 新 = \"前😀\"\n"
        val created = f.load(f.request(fresh, overlays = listOf(GoRunBuildOverlay(uri(fresh), freshText, 8, 12))))
        assertTrue(uri(fresh) in created.activeSourceUris)
        assertEquals(freshText, created.sourceText)
        val mod = f.directory.resolve("go.mod")
        val changedModule = f.load(f.request(source, overlays = listOf(GoRunBuildOverlay(uri(source), text, 7, 11),
            GoRunBuildOverlay(uri(mod), "module example.test/dirty\n\ngo 1.26\n", 9, 14))))
        assertEquals("example.test/dirty", changedModule.packagePath)
        assertEquals(before, f.tree())
        val backing = f.write("backing.txt", text)
        val manifest = f.write("source-overlay.json", JsonObject().apply {
            add("Replace", JsonObject().apply { addProperty(source.toString(), backing.toString()) })
        }.toString())
        val fromFile = f.load(f.request(source, values = GoRunParameters(buildFlags = "-overlay=source-overlay.json")))
        assertTrue(uri(source) in fromFile.activeSourceUris)
        assertEquals(text, fromFile.sourceText)
        assertTrue(uri(manifest) in fromFile.watchedFileUris && uri(backing) in fromFile.watchedFileUris)
        val nativeWins = f.load(f.request(source, values = GoRunParameters(buildFlags = "-overlay=source-overlay.json"),
            overlays = listOf(GoRunBuildOverlay(uri(source), "//go:build alpha\n\npackage sample\n", 10, 15))))
        assertTrue(uri(source) in nativeWins.ignoredSourceUris)
        // These earlier guards predate the two new non-Go support files. Such
        // unrelated files do not change package membership.
        active.validateDisk(); created.validateDisk(); changedModule.validateDisk()
        Files.writeString(source, "package changed\n")
        expectFailure { active.validateDisk() }
        Files.writeString(fresh, freshText)
        expectFailure { created.validateDisk() }
    }

    fun testInvalidMembersTakePriorityButDiagnosticsDoNotEraseKnownMembership() = Fixture().use { f ->
        val good = f.write("good_test.go", "package sample\n")
        val bad = f.write("bad_test.go", "package\n")
        val invalid = f.load(f.request(bad))
        assertTrue(uri(bad) in invalid.invalidSourceUris)
        assertFalse(uri(bad) in invalid.activeSourceUris)
        assertTrue(invalid.diagnostics.isNotEmpty())
        val active = f.load(f.request(good))
        assertTrue(uri(good) in active.activeSourceUris)
        assertTrue(active.diagnostics.isNotEmpty())
        Files.delete(good); Files.delete(bad)
        val excluded = f.write("excluded_test.go", "//go:build never\n\npackage sample\n")
        val ignored = f.load(f.request(excluded))
        assertTrue(uri(excluded) in ignored.ignoredSourceUris)
        assertTrue(ignored.diagnostics.any { it.contains("build constraints exclude all Go files") })
    }

    fun testModModAndUserModfileUseOwnedCopiesWithoutChangingRelativeReplacementMeaning() = Fixture().use { f ->
        val dependency = f.directory.resolve("dependency"); Files.createDirectories(dependency)
        Files.writeString(dependency.resolve("go.mod"), "module example.test/dependency\n\ngo 1.26\n")
        Files.writeString(dependency.resolve("dep.go"), "package dependency\nconst Value=1\n")
        val source = f.write("sample.go", "package sample\nimport \"example.test/dependency\"\nvar Value=dependency.Value\n")
        val content = "module example.test/original\nrequire example.test/dependency v0.0.0\nreplace example.test/dependency => ./dependency\n"
        Files.writeString(f.directory.resolve("go.mod"), content)
        val custom = f.write("custom.mod", content.replace("example.test/original", "example.test/custom"))
        val before = f.tree()
        val normal = f.load(f.request(source, values = GoRunParameters(buildFlags = "-mod=mod")))
        assertEquals("example.test/original", normal.packagePath)
        assertEquals(mapOf("example.test/original" to f.directory.toString()), normal.packageMetadata.directories)
        assertEquals(listOf(dev.openidea.goide.coverage.GoCoverageModule("example.test/original", f.directory.toString())), normal.packageMetadata.modules)
        assertTrue(uri(source) in normal.activeSourceUris)
        val alternate = f.load(f.request(source, values = GoRunParameters(buildFlags = "-mod=mod -modfile=custom.mod")))
        assertEquals("example.test/custom", alternate.packagePath)
        assertEquals(mapOf("example.test/custom" to f.directory.toString()), alternate.packageMetadata.directories)
        assertEquals(listOf(dev.openidea.goide.coverage.GoCoverageModule("example.test/custom", f.directory.toString())), alternate.packageMetadata.modules)
        assertTrue("Operation-owned modfiles must not leak their staging path into coverage roots", alternate.packageMetadata.modules.none { it.directory.contains("open-goland-go-build-context-") })
        assertTrue(runCatching { (alternate.packageMetadata.directories as MutableMap<String, String>).clear() }.exceptionOrNull() is UnsupportedOperationException)
        assertTrue(uri(source) in alternate.activeSourceUris)
        assertTrue(uri(custom) in alternate.watchedFileUris)
        assertEquals(before, f.tree())
        Files.writeString(f.directory.resolve("go.mod"), content.replace("module example.test/original\n", "module example.test/original\n\ngo 1.26\n"))
        f.runGo(listOf("mod", "vendor"), f.directory, f.settings)
        val vendor = f.load(f.request(source))
        assertTrue(uri(source) in vendor.activeSourceUris)
        assertTrue(vendor.diagnostics.toString(), vendor.diagnostics.isEmpty())
        assertTrue(uri(f.directory.resolve("vendor/modules.txt")) in vendor.watchedFileUris)
    }

    fun testDirtyWorkspaceUseAndVendorViewKeepRealRootsAndOriginalSums() = Fixture().use { f ->
        val one = Files.createDirectory(f.directory.resolve("one")); val two = Files.createDirectory(f.directory.resolve("two"))
        Files.writeString(one.resolve("go.mod"), "module example.test/one\n\ngo 1.26\n")
        Files.writeString(one.resolve("one.go"), "package one\n")
        Files.writeString(two.resolve("go.mod"), "module example.test/two\n\ngo 1.26\nrequire example.test/dependency v0.0.0\n")
        val dependency = Files.createDirectory(f.directory.resolve("dependency"))
        Files.writeString(dependency.resolve("go.mod"), "module example.test/dependency\n\ngo 1.26\n")
        Files.writeString(dependency.resolve("dep.go"), "package dependency\nconst Value=1\n")
        val source = two.resolve("two_test.go"); Files.writeString(source, "package two\nimport \"example.test/dependency\"\nvar Value=dependency.Value\n")
        val work = f.write("go.work", "go 1.26\nuse ./one\nreplace example.test/dependency => ./dependency\n")
        val settings = f.settings.copy(environment = f.settings.environment.replace("GOWORK=off", "GOWORK=$work"))
        expectFutureFailure { f.load(f.request(source, directory = two, settings = settings)) }
        val overlay = GoRunBuildOverlay(uri(work), "go 1.26\nuse (\n ./one\n ./two\n)\nreplace example.test/dependency => ./dependency\n", 17, 31)
        val before = f.tree()
        val snapshot = f.load(f.request(source, directory = two, settings = settings, overlays = listOf(overlay)))
        assertEquals("example.test/two", snapshot.packagePath)
        assertEquals(mapOf("example.test/two" to two.toString()), snapshot.packageMetadata.directories)
        assertEquals(listOf(dev.openidea.goide.coverage.GoCoverageModule("example.test/two", two.toString())), snapshot.packageMetadata.modules)
        assertTrue(snapshot.diagnostics.toString(), snapshot.diagnostics.isEmpty())
        assertTrue(uri(source) in snapshot.activeSourceUris)
        assertEquals(before, f.tree())
        snapshot.validateDisk()
        assertTrue(uri(one.resolve("go.mod")) in snapshot.watchedFileUris)
        val invalidMember = GoRunBuildOverlay(uri(one.resolve("go.mod")), "not a valid module file\n", 18, 32)
        expectFutureFailure { f.load(f.request(source, directory = two, settings = settings, overlays = listOf(overlay, invalidMember))) }
        // Build a real owned vendor directory using Go itself; the loader must
        // preserve native default workspace-vendor selection in its private view.
        Files.writeString(work, overlay.text)
        f.runGo(listOf("work", "vendor"), f.directory, settings)
        val vendored = f.tree()
        val vendorSnapshot = f.load(f.request(source, directory = two, settings = settings))
        assertTrue(uri(source) in vendorSnapshot.activeSourceUris)
        assertTrue(vendorSnapshot.diagnostics.toString(), vendorSnapshot.diagnostics.isEmpty())
        assertEquals(vendored, f.tree())
        expectFutureFailure { f.load(f.request(source, directory = two, settings = settings, values = GoRunParameters(buildFlags = "-mod=mod"))) }
    }

    fun testWorkspaceMissingChecksumsMayUpdateOnlyItsOwnedAlternateSum() = Fixture().use { f ->
        val module = Files.createDirectory(f.directory.resolve("module"))
        Files.writeString(module.resolve("go.mod"), "module example.test/main\n\ngo 1.26\nrequire example.test/remote v1.0.0\n")
        val source = module.resolve("main.go")
        Files.writeString(source, "package main\nimport \"example.test/remote\"\nvar Value=remote.Value\n")
        val work = f.write("go.work", "go 1.26\nuse ./module\n")
        val proxy = Files.createDirectories(f.root.resolve("代理 proxy😀/example.test/remote/@v"))
        val dependency = "module example.test/remote\n\ngo 1.26\n"
        Files.writeString(proxy.resolve("v1.0.0.mod"), dependency)
        Files.writeString(proxy.resolve("v1.0.0.info"), "{\"Version\":\"v1.0.0\",\"Time\":\"2024-01-01T00:00:00Z\"}")
        java.util.zip.ZipOutputStream(Files.newOutputStream(proxy.resolve("v1.0.0.zip"))).use { zip ->
            for ((name, text) in mapOf("go.mod" to dependency, "remote.go" to "package remote\nconst Value=1\n")) {
                zip.putNextEntry(java.util.zip.ZipEntry("example.test/remote@v1.0.0/$name"))
                zip.write(text.toByteArray(Charsets.UTF_8)); zip.closeEntry()
            }
        }
        val cache = f.root.resolve("empty-module-cache")
        f.ownedModuleCaches.add(cache)
        val proxyRoot = proxy.parent.parent.parent
        val env = f.write("fallback.env", "GOOS=darwin\n")
        val windows = module.resolve("only_windows.go"); Files.writeString(windows, "package main\n")
        val settings = f.settings.copy(environment = f.settings.environment.replace("GOWORK=off", "GOWORK=$work")
            .replace("GOPROXY=off", "GOPROXY=${proxyRoot.toUri()}").replace("GOENV=off", "GOENV=$env") + "\nGOMODCACHE=$cache")
        assertFalse(Files.exists(cache))
        val snapshot = f.load(f.request(source, directory = module, settings = settings, values = f.isolatedValues(),
            overlays = listOf(GoRunBuildOverlay(uri(env), "GOOS=windows\n", 60, 80))))
        assertTrue("Fallback editor commands must retain the same captured Go environment", uri(windows) in snapshot.activeSourceUris)
        assertTrue(uri(source) in snapshot.activeSourceUris)
        assertTrue(snapshot.diagnostics.toString(), snapshot.diagnostics.isEmpty())
        assertEquals(mapOf("example.test/main" to module.toString()), snapshot.packageMetadata.directories)
        assertEquals(listOf(dev.openidea.goide.coverage.GoCoverageModule("example.test/main", module.toString())), snapshot.packageMetadata.modules)
        assertTrue(Files.isRegularFile(cache.resolve("cache/download/example.test/remote/@v/v1.0.0.ziphash")))
        assertFalse("Original workspace checksum file must remain absent", Files.exists(f.directory.resolve("go.work.sum")))
        assertFalse(Files.exists(module.resolve("go.sum")))
        snapshot.validateDisk()
    }

    fun testDirectoryAliasesAndSourceOrMembershipChangesInvalidateCompletedSnapshots() = Fixture().use { f ->
        val source = f.write("sample_test.go", "package sample\n")
        val alias = f.root.resolve("包 alias😀")
        if (SystemInfo.isWindows) {
            val command = ProcessBuilder("cmd.exe", "/c", "mklink", "/J", alias.toString(), f.directory.toString()).redirectErrorStream(true).start()
            assertTrue(command.waitFor(5, TimeUnit.SECONDS)); assertEquals(0, command.exitValue())
        } else Files.createSymbolicLink(alias, f.directory)
        val aliasSource = alias.resolve(source.fileName)
        val snapshot = f.load(f.request(aliasSource, directory = alias))
        assertTrue(uri(aliasSource) in snapshot.activeSourceUris)
        snapshot.validateDisk()
        val unrelated = f.root.resolve("unrelated notes.txt")
        Files.writeString(unrelated, "unrelated ancestor entry")
        snapshot.validateDisk()
        Files.delete(unrelated)
        snapshot.validateDisk()
        f.write("added_test.go", "package sample\n")
        expectFailure { snapshot.validateDisk() }
        Files.delete(alias)
    }

    fun testCreationAndPreStartDisposalAreMemoryOnlyAndActiveCancelOwnsOnlyItsProcesses() = Fixture().use { f ->
        val source = f.write("source.go", "package sample\n")
        val helper = GoOwnedTestExecutable.compile(f.directory.resolve(GoOwnedTestExecutable.name("owned-go")), HELPER)
        val settings = f.settings.copy(goExecutable = helper.toString(), environment = f.settings.environment + "\nOWNED_LOADER=${f.directory}")
        val loader = GoRunBuildContextLoader(project)
        var before: GoRunBuildContextOperation? = null
        ApplicationManager.getApplication().invokeAndWait {
            before = loader.create(f.request(source, settings = settings))
            before!!.dispose()
        }
        val registered = GoRunBuildContextLoader::class.java.getDeclaredField("operations").apply { isAccessible = true }.get(loader) as Set<*>
        assertTrue("A disposed never-started operation must release its captured overlays", registered.isEmpty())
        val cancelledBeforeStart = loader.create(f.request(source, settings = settings))
        cancelledBeforeStart.future.cancel(false)
        assertTrue(registered.isEmpty())
        before!!.start()
        assertTrue(before!!.future.isCancelled)
        assertFalse(Files.exists(f.directory.resolve("owned.json")))
        val operation = loader.create(f.request(source, settings = settings))
        val worker = f.workers.submit { operation.start() }
        val record = f.directory.resolve("owned.json")
        f.awaitFile(record)
        val owned = JsonParser.parseString(Files.readString(record)).asJsonObject.getAsJsonArray("pids").map { it.asLong }
        f.ownedPids += owned
        assertEquals(2, owned.size)
        assertTrue(owned.all { ProcessHandle.of(it).orElseThrow().isAlive })
        val start = System.nanoTime()
        ApplicationManager.getApplication().invokeAndWait { operation.dispose(); loader.dispose() }
        assertTrue("Cancellation must not wait for pipe/process cleanup on EDT", System.nanoTime() - start < TimeUnit.MILLISECONDS.toNanos(500))
        assertTrue(operation.future.isCancelled)
        worker.get(5, TimeUnit.SECONDS)
        assertTrue(owned.all { ProcessHandle.of(it).isEmpty || !ProcessHandle.of(it).orElseThrow().isAlive })
        assertFalse(Files.exists(f.directory.resolve("reply-released")))
    }

    fun testMalformedAndUnknownMetadataNeverBecomeIgnoredOrActive() = Fixture().use { f ->
        val source = f.write("source.go", "package sample\n")
        val helper = GoOwnedTestExecutable.compile(f.directory.resolve(GoOwnedTestExecutable.name("owned-go")), HELPER)
        for (mode in listOf("invalid-json", "unknown", "contradiction", "outside")) {
            val settings = f.settings.copy(goExecutable = helper.toString(), environment = f.settings.environment + "\nOWNED_LOADER=${f.directory}\nOWNED_MODE=$mode")
            expectFutureFailure { f.load(f.request(source, settings = settings)) }
        }
    }

    fun testDirtyCustomModOverlayAndGoEnvironmentUseTheirCapturedInputs() = Fixture().use { f ->
        val source = f.write("source.go", "package sample\n")
        val custom = f.write("custom.mod", "module example.test/disk\n\ngo 1.26\n")
        val customText = "module example.test/dirtycustom\n\ngo 1.26\n"
        val changed = f.load(f.request(source, values = GoRunParameters(buildFlags = "-modfile=custom.mod"),
            overlays = listOf(GoRunBuildOverlay(uri(custom), customText, 41, 51))))
        assertEquals("example.test/dirtycustom", changed.packagePath)
        changed.validateDisk()
        val tagged = f.write("tagged_test.go", "//go:build alpha\n\npackage sample\n")
        val off = f.write("off.txt", "//go:build alpha\n\npackage sample\n")
        val on = f.write("on.txt", "package sample\nvar 前 = \"😀\"\n")
        fun manifest(backing: Path) = JsonObject().apply { add("Replace", JsonObject().apply { addProperty(tagged.toString(), backing.toString()) }) }.toString()
        val overlay = f.write("chosen.json", manifest(off))
        val dirtyOverlay = f.load(f.request(tagged, values = GoRunParameters(buildFlags = "-overlay=chosen.json"),
            overlays = listOf(GoRunBuildOverlay(uri(overlay), manifest(on), 42, 52))))
        assertTrue(uri(tagged) in dirtyOverlay.activeSourceUris)
        dirtyOverlay.validateDisk()
        val environment = f.write("selected.env", "GOOS=darwin\n")
        val windows = f.write("only_windows_test.go", "package sample\n")
        val settings = f.settings.copy(environment = f.settings.environment.replace("GOENV=off", "GOENV=$environment"))
        val disk = f.load(f.request(windows, settings = settings, values = f.isolatedValues()))
        assertTrue(uri(windows) in disk.ignoredSourceUris)
        val dirtyEnv = f.load(f.request(windows, settings = settings, values = f.isolatedValues(),
            overlays = listOf(GoRunBuildOverlay(uri(environment), "GOOS=windows\n", 43, 53))))
        assertTrue(uri(windows) in dirtyEnv.activeSourceUris)
        assertTrue(uri(environment) in dirtyEnv.watchedFileUris)
        dirtyEnv.validateDisk() // no guard may retain a deleted private staging file
        System.getenv("GOOS")?.takeIf(String::isNotBlank)?.let { inherited ->
            val opposite = if (inherited == "windows") "darwin" else "windows"
            val parentWins = f.load(f.request(windows, settings = settings,
                overlays = listOf(GoRunBuildOverlay(uri(environment), "GOOS=$opposite\n", 44, 54))))
            assertEquals("Explicit process environment takes precedence over the Go environment file", inherited == "windows", uri(windows) in parentWins.activeSourceUris)
        }
        Unit // JUnit 3 discovers a void method, not a nullable-Unit expression body.
    }

    fun testRunMetadataProjectsOnlyExecutionControlsAndPreservesBinaryBoundaries() = Fixture().use { f ->
        val source = f.write("source.go", "package sample\n")
        for (control in listOf("-c", "-c=true", "-c=false", "-c=0", "-o output", "-o=output", "-exec wrapper", "-exec=wrapper")) {
            for (inToolFlags in listOf(false, true)) {
                val values = if (inToolFlags) GoRunParameters(buildFlags = control) else GoRunParameters(arguments = control)
                val snapshot = f.load(f.request(source, values = values))
                assertTrue(control, uri(source) in snapshot.activeSourceUris)
                expectFutureFailure { f.load(f.request(source, values = values, debug = true)) }
            }
        }
        val tagValue = f.load(f.request(source, values = GoRunParameters(buildFlags = "-tags -o")))
        assertTrue("A value slot must not be mistaken for an output option", uri(source) in tagValue.activeSourceUris)
        for (missing in listOf("-o", "-exec", "-c=invalid")) for (inToolFlags in listOf(false, true)) {
            val values = if (inToolFlags) GoRunParameters(buildFlags = missing) else GoRunParameters(arguments = missing)
            expectFutureFailure { f.load(f.request(source, values = values)) }
        }
        val tagged = f.write("tagged_test.go", "//go:build alpha\n\npackage sample\n")
        for (boundary in listOf("-args", "--args", "--")) {
            val snapshot = f.load(f.request(tagged, values = GoRunParameters(arguments = "$boundary -o -tags=alpha")))
            assertTrue("Binary-only arguments must not become compile flags", uri(tagged) in snapshot.ignoredSourceUris)
        }
        // Invalid byte-order-marked metadata must stay invalid, not be repaired
        // by the IDE's Go-source text normalization.
        Files.writeString(f.directory.resolve("go.mod"), "\uFEFFmodule example.test/bom\n\ngo 1.26\n")
        expectFutureFailure { f.load(f.request(source)) }
    }

    private fun expectFailure(action: () -> Unit) { try { action(); fail("A changed or malformed Go input must be rejected") } catch (_: IllegalArgumentException) {} }
    private fun expectFutureFailure(action: () -> Unit) { try { action(); fail("Failed metadata must not become source membership") } catch (_: ExecutionException) {} }

    private inner class Fixture : AutoCloseable {
        val root = Files.createTempDirectory("go-build-").toRealPath()
        val directory = Files.createDirectory(root.resolve("工作 目录😀"))
        val workers = Executors.newCachedThreadPool()
        val ownedPids = mutableListOf<Long>()
        val ownedModuleCaches = mutableListOf<Path>()
        private val loads = com.google.gson.JsonArray()
        private val go = Path.of(requireNotNull(GoToolchain.resolve("go", System.getProperty("opengoland.testGo") ?: System.getenv("OPENGOLAND_GO_TEST").orEmpty())))
        private val sdkHash = hash(go)
        private val sdkRoot = go.toRealPath().parent.parent
        private val sdkTreeBefore = sdkTrees.computeIfAbsent(sdkRoot) { treeHash(it) }
        val settings = GoToolchain.Settings(go.toString(), "", "", "", "", "", "0",
            "GOROOT=${go.toRealPath().parent.parent}\nGOENV=off\nGOTOOLCHAIN=local\nGOWORK=off\nGOPROXY=off\nGOSUMDB=off\nGOTELEMETRY=off\nGOFLAGS=")
        init { write("go.mod", "module example.test/loader\n\ngo 1.26\n") }
        fun write(name: String, text: String): Path = directory.resolve(name).also { Files.writeString(it, text) }
        fun isolatedValues() = GoRunParameters(passParentEnvironment = false, environment = linkedMapOf(
            "GOCACHE" to root.resolve("owned-cache").toString(), "GOPATH" to root.resolve("owned-gopath").toString(),
        ).apply { if (SystemInfo.isWindows) System.getenv("SystemRoot")?.let { put("SystemRoot", it) } })
        fun request(source: Path, directory: Path = this.directory, values: GoRunParameters = GoRunParameters(),
                    settings: GoToolchain.Settings = this.settings, overlays: List<GoRunBuildOverlay> = emptyList(), debug: Boolean = false) =
            GoRunBuildContextRequest(uri(source), directory.toString(), values, settings, overlays, debug)
        fun load(request: GoRunBuildContextRequest): GoRunBuildContextSnapshot {
            val before = tree()
            val loader = GoRunBuildContextLoader(project)
            val operation = loader.create(request)
            val receipt = JsonObject().apply {
                addProperty("sourceUri", request.sourceUri); addProperty("workingDirectory", request.workingDirectory)
                addProperty("debug", request.debug); addProperty("buildFlags", request.values.buildFlags)
                addProperty("arguments", request.values.arguments); addProperty("passParentEnvironment", request.values.passParentEnvironment)
                addProperty("configuredGoExecutable", request.settings.goExecutable)
                addProperty("syntheticMetadataProgram", request.settings.goExecutable != settings.goExecutable)
                addProperty("projectBuildTags", request.settings.buildTags); addProperty("projectGoos", request.settings.goos)
            }
            try {
                operation.start()
                val result = operation.future.get(5, TimeUnit.SECONDS)
                receipt.addProperty("status", "authoritative-membership")
                receipt.addProperty("packagePath", result.packagePath)
                receipt.add("active", JsonParser.parseString(com.google.gson.Gson().toJson(result.activeSourceUris)))
                receipt.add("ignored", JsonParser.parseString(com.google.gson.Gson().toJson(result.ignoredSourceUris)))
                receipt.add("invalid", JsonParser.parseString(com.google.gson.Gson().toJson(result.invalidSourceUris)))
                receipt.add("diagnostics", JsonParser.parseString(com.google.gson.Gson().toJson(result.diagnostics)))
                return result
            } catch (failure: Throwable) {
                receipt.addProperty("status", "exceptional-not-ignored")
                receipt.addProperty("failure", failure.toString().take(4096))
                throw failure
            } finally {
                loader.dispose()
                val unchanged = before == tree()
                receipt.addProperty("originalProjectTreeUnchanged", unchanged)
                loads.add(receipt)
                assertTrue("Metadata must not write the original Go project or module/sum files", unchanged)
            }
        }
        fun runGo(arguments: List<String>, directory: Path, settings: GoToolchain.Settings) {
            val handler = CapturingProcessHandler(GoToolchain.command(project, "go", arguments, settings).withWorkDirectory(directory.toFile()))
            handler.setShouldDestroyProcessRecursively(true)
            try {
                val output = handler.runProcess(20_000, true)
                assertFalse(output.isTimeout || output.isCancelled)
                assertEquals(output.stderr, 0, output.exitCode)
            } finally { if (!handler.isProcessTerminated) handler.destroyProcess() }
        }
        fun tree(): Map<String, String> = Files.walk(directory).use { all -> all.filter(Files::isRegularFile).sorted().toList().associate { directory.relativize(it).toString() to hash(it) } }
        fun awaitFile(path: Path) {
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (!Files.isRegularFile(path) && System.nanoTime() < until) Thread.sleep(5)
            assertTrue("Owned loader process did not publish $path", Files.isRegularFile(path))
        }
        override fun close() {
            workers.shutdownNow(); assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS))
            for (cache in ownedModuleCaches) {
                require(cache.normalize().startsWith(root) && cache != root)
                runGo(listOf("clean", "-modcache"), directory, settings.copy(environment = settings.environment + "\nGOMODCACHE=$cache"))
            }
            assertEquals("The selected SDK binary is immutable", sdkHash, hash(go))
            val sdkTreeAfter = treeHash(sdkRoot)
            assertEquals("No regular-file byte in the selected SDK may change", sdkTreeBefore, sdkTreeAfter)
            assertTrue(ownedPids.all { ProcessHandle.of(it).isEmpty || !ProcessHandle.of(it).orElseThrow().isAlive })
            System.getProperty("opengoland.buildContextLoaderEvidence")?.let { output ->
                val bytes = requireNotNull(GoRunBuildContextLoader::class.java.getResourceAsStream("GoRunBuildContextLoader.class")).use { it.readAllBytes() }
                val report = JsonObject().apply {
                    addProperty("test", getName()); addProperty("selectedGo", go.toString()); addProperty("selectedGoSha256", sdkHash)
                    addProperty("sdkRoot", sdkRoot.toString()); addProperty("sdkTreeBefore", sdkTreeBefore); addProperty("sdkTreeAfter", sdkTreeAfter)
                    addProperty("loaderClassResource", GoRunBuildContextLoader::class.java.getResource("GoRunBuildContextLoader.class").toString())
                    addProperty("loaderClassSha256", MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
                    add("loads", loads); add("recordedOwnedPids", JsonParser.parseString(ownedPids.toString()))
                    addProperty("recordedOwnedPidsAbsent", true); addProperty("actualEngineOrLspUsed", false)
                }
                val target = Files.createDirectories(Path.of(output))
                Files.writeString(target.resolve(java.util.UUID.randomUUID().toString() + ".json"), report.toString())
            }
            Files.walk(root).use { all -> all.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
    private fun uri(path: Path) = GoDocumentSync.fileUri(path)
    private fun hash(path: Path) = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }
    private companion object {
        val sdkTrees = java.util.concurrent.ConcurrentHashMap<Path, String>()
        fun treeHash(root: Path): String {
            val digest = MessageDigest.getInstance("SHA-256")
            Files.walk(root).use { all -> all.filter(Files::isRegularFile).sorted().forEach { path ->
                digest.update(root.relativize(path).toString().toByteArray(Charsets.UTF_8)); digest.update(0)
                digest.update(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))); digest.update(0)
            } }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
        val HELPER = """
            package main
            import("encoding/json";"fmt";"os";"os/exec";"os/signal";"path/filepath";"time";"syscall")
            func main(){
                dir:=os.Getenv("OWNED_LOADER")
                if len(os.Args)>1&&os.Args[1]=="child"{for{time.Sleep(time.Second)}}
                if len(os.Args)>1&&os.Args[1]=="env"{fmt.Println(`{"GOENV":"off","GOFLAGS":"","GO111MODULE":"","GOWORK":"off"}`);return}
                if len(os.Args)<2||os.Args[1]!="list"{panic("unexpected owned command")}
                mode:=os.Getenv("OWNED_MODE")
                switch mode{
                case "invalid-json":fmt.Println("not-json");return
                case "unknown":fmt.Printf(`{"Dir":%q,"ImportPath":"example.test/loader"}`,dir);return
                case "contradiction":fmt.Printf(`{"Dir":%q,"ImportPath":"example.test/loader","GoFiles":["source.go"],"IgnoredGoFiles":["source.go"]}`,dir);return
                case "outside":fmt.Printf(`{"Dir":%q,"ImportPath":"example.test/loader","GoFiles":["source.go"]}`,filepath.Dir(dir));return
                }
                child:=exec.Command(os.Args[0],"child");child.Stdout=os.Stdout;child.Stderr=os.Stderr
                if err:=child.Start();err!=nil{panic(err)}
                signals:=make(chan os.Signal,1);signal.Notify(signals,os.Interrupt,syscall.SIGTERM)
                data,_:=json.Marshal(map[string]any{"pids":[]int{os.Getpid(),child.Process.Pid}})
                tmp:=filepath.Join(dir,"owned.json.tmp");os.WriteFile(tmp,data,0600);os.Rename(tmp,filepath.Join(dir,"owned.json"))
                go func(){<-signals;child.Process.Kill();child.Wait();os.Exit(0)}()
                deadline:=time.Now().Add(60*time.Second)
                for{if time.Now().After(deadline){child.Process.Kill();child.Wait();return};time.Sleep(10*time.Millisecond)}
            }
        """.trimIndent()
    }
}
