package dev.openidea.goide.run

import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.EdtTestUtil
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.LightPlatformTestCase
import com.intellij.util.xmlb.XmlSerializerUtil
import dev.openidea.goide.settings.GoProjectSettings
import dev.openidea.goide.settings.GoToolchain
import dev.openidea.goide.debug.GoDebugTestSession
import org.jdom.Element
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Real native configurations and real Go process creation, rather than only a cached status check. */
class GoRunBuildContextLaunchTest : LightPlatformTestCase() {
    override fun runInDispatchThread() = false

    fun testAConfigurationProducedInAnActiveContextIsRecheckedBeforeActualRunAfterSettingsChange() = Fixture().use { f ->
        GoProjectSettings.get(project).goos = f.otherOs
        GoProjectSettings.get(project).goarch = "amd64"
        val config = f.produce(f.other, "TestOtherOnly")
        assertNotNull(config.sourceSelection)
        GoProjectSettings.get(project).goos = f.hostOs
        GoProjectSettings.get(project).goarch = f.hostArch
        val failure = runCatching { f.state(config).createProcess().also { f.handlers += it } }.exceptionOrNull()
        assertNotNull("An inactive precise selection must fail before creating its Go test target", failure)
        assertTrue(failure.toString(), failure!!.message.orEmpty().contains("excluded"))
        assertTrue("No Go test target handler may be created for the excluded source", f.handlers.isEmpty())
    }

    fun testActivePreciseRunActuallyExecutesWhileHandwrittenAndPackagePatternsKeepTheirMeaning() = Fixture().use { f ->
        val active = f.produce(f.host, "TestHost")
        val result = f.run(active)
        assertEquals(result.second, 0, result.first)
        assertTrue(result.second, result.second.contains("HOST_EXECUTED"))
        assertFalse(result.second, result.second.contains("OTHER_EXECUTED"))

        // A user-authored pattern has no source provenance. Go's own no-match result remains legal.
        val manual = f.configuration().apply { parameters.testPattern = "^TestOtherOnly$" }
        assertNull(manual.sourceSelectionFor(manual.parameters))
        val noMatch = f.run(manual)
        assertEquals(noMatch.second, 0, noMatch.first)
        assertFalse(noMatch.second, noMatch.second.contains("OTHER_EXECUTED"))

        val packageContext = edt {
            GoTestConfigurationProducer().createConfigurationFromContext(ConfigurationContext(f.host))?.configuration as? GoRunConfiguration
        }
        assertNotNull("The ordinary package entry must remain available", packageContext)
        assertNull(packageContext!!.sourceSelection)
        assertEquals("", packageContext.parameters.testPattern)
        val all = f.run(packageContext)
        assertEquals(all.second, 0, all.first)
        assertTrue(all.second, all.second.contains("HOST_EXECUTED"))
    }

    fun testNativeSourceProvenanceSurvivesCloneAndXmlButDoesNotConstrainAnEditedPattern() = Fixture().use { f ->
        val original = f.produce(f.host, "TestHost")
        val clone = original.clone()
        assertEquals(original.sourceSelection, clone.sourceSelectionFor(clone.parameters))
        val xml = Element("configuration")
        edt { clone.writeExternal(xml) }
        assertNotNull(xml.getChild("openidea-go").getChild("source-selection"))
        val reopened = f.configuration()
        edt { reopened.readExternal(xml) }
        assertEquals(original.sourceSelection, reopened.sourceSelectionFor(reopened.parameters))
        reopened.parameters.testPattern = "Test.*"
        assertNull(reopened.sourceSelectionFor(reopened.parameters))
        val free = Element("configuration")
        edt { reopened.writeExternal(free) }
        assertNull("A hand-edited free pattern must not persist the old exact source constraint", free.getChild("openidea-go").getChild("source-selection"))
    }

    fun testDebugRejectsAnExcludedPreciseSourceBeforeStartingItsConverterOrAdapter() = Fixture().use { f ->
        GoProjectSettings.get(project).goos = f.otherOs
        GoProjectSettings.get(project).goarch = "amd64"
        val config = f.produce(f.other, "TestOtherOnly")
        val capturedRunState = f.state(config)
        GoProjectSettings.get(project).goos = f.hostOs
        GoProjectSettings.get(project).goarch = f.hostArch
        val staleRun = runCatching { capturedRunState.createProcess().also { f.handlers += it } }.exceptionOrNull()
        assertTrue("A state captured before settings changed must be cancelled, not launch its old target: $staleRun",
            staleRun is com.intellij.openapi.progress.ProcessCanceledException)
        val tests = GoDebugTestSession(config)
        try {
            val rejected = runCatching { tests.prepare() }.exceptionOrNull()
            assertNotNull("Debug must apply the same exact-source build membership gate", rejected)
            assertTrue(rejected.toString(), rejected!!.message.orEmpty().contains("excluded"))
            assertNull("No test2json process may be created for an excluded source", GoDebugTestSession::class.java.getDeclaredField("converter").apply { isAccessible = true }.get(tests))
            assertNull("No adapter may be bound for an excluded source", GoDebugTestSession::class.java.getDeclaredField("adapter").apply { isAccessible = true }.get(tests))
        } finally { Disposer.dispose(tests) }
        assertTrue(f.handlers.isEmpty())
    }

    fun testAnExactSourceEntryPreservesTheOrdinaryRunsLegalOutputFlag() = Fixture().use { f ->
        val precise = f.produce(f.host, "TestHost")
        val manual = precise.clone().apply {
            sourceSelection = null
            parameters.buildFlags = com.intellij.util.execution.ParametersListUtil.join(listOf("-count=1", "-o", f.root.resolve("manual output.exe").toString()))
        }
        val ordinary = f.run(manual)
        assertEquals(ordinary.second, 0, ordinary.first)
        assertTrue(ordinary.second, ordinary.second.contains("HOST_EXECUTED"))
        assertTrue(Files.isRegularFile(f.root.resolve("manual output.exe")))
        precise.parameters.buildFlags = com.intellij.util.execution.ParametersListUtil.join(listOf("-count=1", "-o", f.root.resolve("precise output.exe").toString()))
        val exact = f.run(precise)
        assertEquals(exact.second, 0, exact.first)
        assertTrue(exact.second, exact.second.contains("HOST_EXECUTED"))
        assertTrue(Files.isRegularFile(f.root.resolve("precise output.exe")))
    }

    fun testAnExplicitRuntimeSelectorOverrideBecomesAManualSelectionInsteadOfRetainingAnUnrelatedSourceConstraint() = Fixture().use { f ->
        GoProjectSettings.get(project).goos = f.otherOs
        GoProjectSettings.get(project).goarch = "amd64"
        val original = f.produce(f.other, "TestOtherOnly")
        GoProjectSettings.get(project).goos = f.hostOs
        GoProjectSettings.get(project).goarch = f.hostArch
        for (arguments in listOf("-v -timeout 30s -run=^TestHost$", "-v -timeout 30s -args -test.run ^TestHost$")) {
            original.parameters.arguments = arguments
            val manual = original.clone().apply { sourceSelection = null }
            val legacy = f.run(manual)
            assertEquals(legacy.second, 0, legacy.first)
            assertTrue(legacy.second, legacy.second.contains("HOST_EXECUTED"))
            val selected = f.run(original)
            assertEquals(selected.second, 0, selected.first)
            assertTrue(selected.second, selected.second.contains("HOST_EXECUTED"))
            assertNull(original.sourceSelectionFor(original.parameters))
        }
        original.parameters.arguments = "-run ^TestHost$ -run=^TestOtherOnly$"
        assertNotNull("The final effective selector still matches the exact original source", original.sourceSelectionFor(original.parameters))
        val stillExact = runCatching { f.state(original).createProcess().also { f.handlers += it } }.exceptionOrNull()
        assertTrue(stillExact.toString(), stillExact?.message.orEmpty().contains("excluded"))
        original.parameters.arguments = "-run"
        assertNull(original.sourceSelectionFor(original.parameters))
        val invalid = f.run(original)
        assertFalse("A missing selector value must remain a real Go argv error", invalid.first == 0)
        assertTrue(invalid.second, invalid.second.contains("flag needs an argument"))
    }

    fun testContextConfigurationReuseDistinguishesTheSameFunctionNameInDifferentPlatformFiles() = Fixture().use { f ->
        val otherText = ReadAction.compute<String, RuntimeException> { f.other.text }
        Files.writeString(Path.of(f.other.virtualFile.path), otherText + "\nfunc TestPlatform(t *testing.T) {}\n")
        f.other.virtualFile.refresh(false, false)
        val path = f.root.resolve("platform_${f.hostOs}_test.go")
        Files.writeString(path, "package fixture\nimport \"testing\"\nfunc TestPlatform(t *testing.T) {}\n")
        val virtual = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path))
        val host = ReadAction.compute<PsiFile, RuntimeException> { requireNotNull(PsiManager.getInstance(project).findFile(virtual)) }
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        GoProjectSettings.get(project).goos = f.otherOs
        GoProjectSettings.get(project).goarch = "amd64"
        val old = f.produce(f.other, "TestPlatform")
        GoProjectSettings.get(project).goos = f.hostOs
        GoProjectSettings.get(project).goarch = f.hostArch
        val current = f.produce(host, "TestPlatform")
        assertEquals(old.parameters.testPattern, current.parameters.testPattern)
        assertFalse(old.sourceSelection!!.uri == current.sourceSelection!!.uri)
        edt {
            val anchor = requireNotNull(host.findElementAt(host.text.indexOf("TestPlatform") + 1))
            val context = ConfigurationContext(anchor)
            val producer = GoTestConfigurationProducer()
            assertFalse("Reusing the other platform's source token would reject this active same-name function at launch", producer.isConfigurationFromContext(old, context))
            assertTrue(producer.isConfigurationFromContext(current, context))
        }
    }

    fun testPreciseRunPreservesLegitimateModuleUpdatesInsteadOfRejectingItsOwnMetadataWrite() = Fixture().use { f ->
        val dependency = Files.createDirectory(f.root.resolve("dependency"))
        Files.writeString(dependency.resolve("go.mod"), "module example.test/dependency\n\ngo 1.26\n")
        Files.writeString(dependency.resolve("value.go"), "package dependency\nconst Value=37\n")
        Files.writeString(f.root.resolve("value.go"), "package fixture\nimport \"example.test/dependency\"\nvar Bound=dependency.Value\n")
        val module = f.root.resolve("go.mod")
        val original = "module example.org/exactentry\nrequire example.test/dependency v0.0.0\nreplace example.test/dependency => ./dependency\n"
        Files.writeString(module, original)
        // The gutter and its exact configuration must use the same explicitly
        // selected writable module mode. The gutter's owned alternate modfile
        // must nevertheless leave the source module untouched.
        GoProjectSettings.get(project).environment = GoProjectSettings.get(project).environment.replace("GOFLAGS=\n", "GOFLAGS=-mod=mod\n")
        f.host.virtualFile.parent.refresh(false, false)
        val precise = f.produce(f.host, "TestHost").apply { parameters.buildFlags = "-mod=mod -count=1" }
        assertEquals("Gutter membership must not write the source module", original, Files.readString(module))
        val manual = precise.clone().apply { sourceSelection = null }
        val ordinary = f.run(manual)
        assertEquals(ordinary.second, 0, ordinary.first)
        assertTrue(ordinary.second, ordinary.second.contains("HOST_EXECUTED"))
        assertFalse("Real Go Run legitimately updates the original module in -mod=mod mode", original == Files.readString(module))
        Files.writeString(module, original)
        requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(module)).refresh(false, false)
        val exact = f.run(precise)
        assertEquals(exact.second, 0, exact.first)
        assertTrue(exact.second, exact.second.contains("HOST_EXECUTED"))
        assertFalse("The precise Run must preserve Go's authorized module update", original == Files.readString(module))

        // Debug preparation also used to repeat the writable metadata query
        // after acquiring its source token. It must retain the same real roots
        // without modifying the user's module before the actual debug build.
        Files.writeString(module, original)
        requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(module)).refresh(false, false)
        val tests = GoDebugTestSession(precise)
        var converter: OSProcessHandler? = null
        try {
            tests.prepare()
            assertEquals(original, Files.readString(module))
            assertEquals(f.root.toString(), tests.launch.workingDirectory)
            converter = GoDebugTestSession::class.java.getDeclaredField("converter").apply { isAccessible = true }.get(tests) as OSProcessHandler
            assertNull(GoDebugTestSession::class.java.getDeclaredField("adapter").apply { isAccessible = true }.get(tests))
        } finally {
            Disposer.dispose(tests)
            converter?.let {
                assertTrue("The owned preparation converter must terminate", it.waitFor(5_000))
                assertFalse(it.process.isAlive)
                println("BUILD_CONTEXT_OWNED_CONVERTER_EXIT pid=${it.process.pid()} code=${it.exitCode} alive=${it.process.isAlive}")
            }
        }
    }

    private inner class Fixture : AutoCloseable {
        val root = Files.createTempDirectory("owned exact run 中文😀 ").toRealPath()
        private val lifetime = Disposer.newDisposable("exact source launch root")
        private val before = GoProjectSettings.Data().also { XmlSerializerUtil.copyBean(GoProjectSettings.get(project), it) }
        val handlers = mutableListOf<ProcessHandler>()
        val host: PsiFile
        val other: PsiFile
        val hostOs = if (com.intellij.openapi.util.SystemInfo.isWindows) "windows" else if (com.intellij.openapi.util.SystemInfo.isMac) "darwin" else "linux"
        val hostArch = if (System.getProperty("os.arch") in setOf("aarch64", "arm64")) "arm64" else "amd64"
        val otherOs = if (hostOs == "windows") "linux" else "windows"
        init {
            VfsRootAccess.allowRootAccess(lifetime, root.toString())
            Files.writeString(root.resolve("go.mod"), "module example.org/exactentry\n\ngo 1.26.10\n")
            Files.writeString(root.resolve("host_test.go"), "package fixture\nimport \"testing\"\nfunc TestHost(t *testing.T) { t.Log(\"HOST_EXECUTED\") }\n")
            Files.writeString(root.resolve("entry_${otherOs}_test.go"), "package fixture\nimport \"testing\"\nfunc TestOtherOnly(t *testing.T) { t.Log(\"OTHER_EXECUTED\") }\n")
            fun file(name: String): PsiFile {
                val virtual = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root.resolve(name)))
                return ReadAction.compute<PsiFile, RuntimeException> { requireNotNull(PsiManager.getInstance(project).findFile(virtual)) }
            }
            host = file("host_test.go"); other = file("entry_${otherOs}_test.go")
            val go = Path.of(requireNotNull(GoToolchain.resolve("go", requireNotNull(System.getProperty("opengoland.testGo")?.takeIf(String::isNotBlank) ?: System.getenv("OPENGOLAND_GO_TEST")?.takeIf(String::isNotBlank)) { "Set OPENGOLAND_GO_TEST to the selected runtime Go SDK" })) { "A selected Go SDK is required for actual build-context controls" }).toRealPath()
            GoProjectSettings.get(project).apply {
                goExecutable = go.toString(); goos = hostOs; goarch = hostArch; cgoEnabled = "0"; buildTags = ""
                environment = "GOENV=off\nGOFLAGS=\nGOWORK=off\nGOTOOLCHAIN=local\nGOPROXY=off\nGOSUMDB=off\nGOTELEMETRY=off\nGOMAXPROCS=2"
            }
            IndexingTestUtil.waitUntilIndexesAreReady(project)
        }
        fun configuration() = edt {
            (GoTestConfigurationType().configurationFactories.single().createTemplateConfiguration(project) as GoRunConfiguration).apply {
                parameters.workingDirectory = root.toString(); parameters.buildFlags = "-count=1"; parameters.arguments = "-v -timeout 30s"
            }
        }
        fun produce(file: PsiFile, name: String): GoRunConfiguration {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
            while (true) {
                val view = GoRunBuildContextService.get(project).lookup(file)
                if (view.status != GoRunBuildStatus.CHECKING) {
                    assertEquals(view.message, GoRunBuildStatus.ACTIVE, view.status)
                    break
                }
                check(System.nanoTime() < deadline) { "Build context never completed" }; Thread.sleep(10)
            }
            return edt {
                val element = ReadAction.compute<com.intellij.psi.PsiElement, RuntimeException> { requireNotNull(file.findElementAt(file.text.indexOf(name) + 1)) }
                (requireNotNull(GoTestConfigurationProducer().createConfigurationFromContext(ConfigurationContext(element))).configuration as GoRunConfiguration)
                    .apply { parameters.buildFlags = "-count=1"; parameters.arguments = "-v -timeout 30s" }
            }
        }
        fun state(config: GoRunConfiguration): GoCommandLineState {
            val executor = DefaultRunExecutor.getRunExecutorInstance()
            val environment = ExecutionEnvironmentBuilder(project, executor).runProfile(config).runner(GoProgramRunner()).build()
            return config.getState(executor, environment) as GoCommandLineState
        }
        fun run(config: GoRunConfiguration): Pair<Int?, String> {
            val handler = state(config).createProcess().also { handlers += it }
            val process = (handler as OSProcessHandler).process
            println("BUILD_CONTEXT_OWNED_TARGET_STARTED pid=${process.pid()}")
            val text = StringBuffer()
            handler.addProcessListener(object : ProcessAdapter() {
                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) { text.append(event.text) }
            })
            handler.startNotify()
            assertTrue("Owned actual Go target did not finish", handler.waitFor(120_000))
            assertFalse("Owned target process must be absent after its terminal notification", process.isAlive)
            println("BUILD_CONTEXT_OWNED_TARGET_EXIT pid=${process.pid()} code=${handler.exitCode} alive=${process.isAlive}")
            return handler.exitCode to text.toString()
        }
        override fun close() {
            handlers.forEach { if (!it.isProcessTerminated) { it.destroyProcess(); assertTrue(it.waitFor(5_000)) } }
            edt { FileDocumentManager.getInstance().saveAllDocuments() }
            XmlSerializerUtil.copyBean(before, GoProjectSettings.get(project))
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root)
            Disposer.dispose(lifetime)
        }
    }
    private fun <T> edt(action: () -> T): T {
        var result: T? = null
        EdtTestUtil.runInEdtAndWait<RuntimeException> { WriteIntentReadAction.run(Runnable { result = action() }) }
        @Suppress("UNCHECKED_CAST") return result as T
    }
}
