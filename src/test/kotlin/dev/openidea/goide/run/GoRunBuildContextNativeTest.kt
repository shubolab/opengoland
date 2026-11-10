package dev.openidea.goide.run

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.execution.lineMarker.RunLineMarkerProvider
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.LightPlatformTestCase
import com.intellij.testFramework.EdtTestUtil
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.util.xmlb.XmlSerializerUtil
import dev.openidea.goide.settings.GoProjectSettings
import dev.openidea.goide.settings.GoToolchain
import java.nio.file.Files
import java.nio.file.Path
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Actual native entry matrix against authoritative selected-Go metadata; no desktop proof. */
class GoRunBuildContextNativeTest : LightPlatformTestCase() {
    override fun runInDispatchThread() = false

    fun testRunnableEntryMustBelongToTheSelectedBuildContext() {
        val evidence = System.getProperty("opengoland.buildContextEvidence")?.takeIf(String::isNotBlank)
        val output = evidence?.let { Files.createDirectories(Path.of(it)) }
            ?: Files.createTempDirectory("owned-build-context-evidence-")
        val root = Files.createTempDirectory("build context 中文😀 ").toRealPath()
        val lifetime = Disposer.newDisposable("owned build context PSI")
        VfsRootAccess.allowRootAccess(lifetime, root.toString())
        val before = GoProjectSettings.Data().also { XmlSerializerUtil.copyBean(GoProjectSettings.get(project), it) }
        val go = Path.of(requireNotNull(GoToolchain.resolve("go", requireNotNull(System.getProperty("opengoland.testGo")?.takeIf(String::isNotBlank) ?: System.getenv("OPENGOLAND_GO_TEST")?.takeIf(String::isNotBlank)) { "Set OPENGOLAND_GO_TEST to the selected runtime Go SDK" })) { "A selected Go SDK is required for actual build-context controls" }).toRealPath()
        val nativeOs = if (com.intellij.openapi.util.SystemInfo.isWindows) "windows" else if (com.intellij.openapi.util.SystemInfo.isMac) "darwin" else "linux"
        val nativeArch = if (System.getProperty("os.arch") in setOf("aarch64", "arm64")) "arm64" else "amd64"
        val rows = mutableListOf<Map<String, Any?>>()
        val commands = mutableListOf<Map<String, Any?>>()
        val unexpected = mutableListOf<String>()
        val missingActive = mutableListOf<String>()
        val sources = linkedMapOf(
            "host_test.go" to "package fixture\nimport \"testing\"\nfunc TestHost(t *testing.T) { t.Log(\"HOST_EXECUTED\") }\n",
            "entry_windows_test.go" to "package fixture\nimport \"testing\"\nfunc TestWindowsOnly(t *testing.T) { t.Log(\"WINDOWS_EXECUTED\") }\n",
            "alpha_test.go" to "//go:build alpha\n\npackage fixture\nimport \"testing\"\nfunc TestAlphaOnly(t *testing.T) { t.Log(\"ALPHA_EXECUTED\") }\n",
            "beta_test.go" to "//go:build beta\n\npackage fixture\nimport \"testing\"\nfunc TestBetaOnly(t *testing.T) { t.Log(\"BETA_EXECUTED\") }\n"
        )
        val names = listOf("TestHost", "TestWindowsOnly", "TestAlphaOnly", "TestBetaOnly")
        try {
            Files.writeString(root.resolve("go.mod"), "module example.org/buildcontext\n\ngo 1.26.10\n")
            sources.forEach { (name, text) -> Files.writeString(root.resolve(name), text) }
            val files = sources.keys.map { name ->
                val virtual = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root.resolve(name)))
                ReadAction.compute<PsiFile, RuntimeException> { requireNotNull(PsiManager.getInstance(project).findFile(virtual)) }
            }
            IndexingTestUtil.waitUntilIndexesAreReady(project)
            for ((label, os, tags) in listOf(Triple("darwin-default", "darwin", ""), Triple("darwin-alpha", "darwin", "alpha"),
                Triple("darwin-beta", "darwin", "beta"), Triple("windows-default", "windows", ""))) {
                GoProjectSettings.get(project).apply {
                    goExecutable = go.toString(); goos = os; goarch = if (os == nativeOs) nativeArch else if (os == "windows") "amd64" else "arm64"
                    cgoEnabled = "0"; buildTags = tags
                    environment = "GOENV=off\nGOFLAGS=\nGOTOOLCHAIN=local\nGOWORK=off\nGOPROXY=off\nGOSUMDB=off\nGOTELEMETRY=off\nGOMAXPROCS=2\nGOCACHE=${output.resolve("owned-go-cache")}"
                }
                val settings = GoToolchain.capture(project)
                fun execute(arguments: List<String>): Map<String, Any?> {
                    val command = GoToolchain.command(project, "go", arguments, settings).withWorkingDirectory(root)
                        .withCharset(StandardCharsets.UTF_8)
                    val process = CapturingProcessHandler(command).apply { setShouldDestroyProcessRecursively(true) }
                    val data = process.runProcess(120_000, true)
                    assertFalse("Owned Go command timed out", data.isTimeout)
                    assertFalse(data.isCancelled)
                    assertTrue("Owned Go child must exit", process.waitFor(5_000))
                    return linkedMapOf("label" to label, "argv" to (listOf(command.exePath) + arguments), "cwd" to root.toString(),
                        "pid" to process.process.pid(), "exitCode" to data.exitCode, "stdout" to data.stdout, "stderr" to data.stderr).also(commands::add)
                }
                val flags = if (tags.isEmpty()) emptyList() else listOf("-tags=$tags")
                val metadata = execute(listOf("list", "-e", "-json=ImportPath,TestGoFiles,XTestGoFiles,IgnoredGoFiles,Error") + flags + ".")
                assertEquals(metadata["stderr"].toString(), 0, metadata["exitCode"])
                val json = JsonParser.parseString(metadata["stdout"].toString()).asJsonObject
                assertFalse(json.toString(), json.has("Error"))
                val active = json.getAsJsonArray("TestGoFiles").map { it.asString }.toSet()
                for ((index, file) in files.withIndex()) {
                    val name = names[index]
                    val anchor = ReadAction.compute<PsiElement, RuntimeException> {
                        requireNotNull(file.findElementAt(file.text.indexOf(name) + 1))
                    }
                    val membership = awaitMembership(file)
                    var configuration: GoRunConfiguration? = null
                    var markerPresent = false
                    var enabled = false
                    var actionTexts = emptyList<String>()
                    onEdt {
                        val produced = GoTestConfigurationProducer().createConfigurationFromContext(ConfigurationContext(anchor))
                        configuration = produced?.configuration as? GoRunConfiguration
                        markerPresent = GoRunLineMarkerContributor().getInfo(anchor) != null
                        if (markerPresent) {
                            val document = requireNotNull(PsiDocumentManager.getInstance(project).getDocument(file))
                            val editor = EditorFactory.getInstance().createEditor(document, project)
                            try {
                                val data = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).add(CommonDataKeys.EDITOR, editor)
                                    .add(CommonDataKeys.VIRTUAL_FILE, file.virtualFile).build()
                                val marker = requireNotNull(RunLineMarkerProvider().getLineMarkerInfo(anchor))
                                val actions = requireNotNull(marker.createGutterRenderer()?.popupMenuActions).getChildren(null)
                                val events = actions.map { action -> AnActionEvent.createFromAnAction(action, null, ActionPlaces.UNKNOWN, data).also(action::update) }
                                enabled = events.all { it.presentation.isEnabledAndVisible }
                                actionTexts = events.map { it.presentation.text.orEmpty() }
                            } finally { EditorFactory.getInstance().releaseEditor(editor) }
                        }
                    }
                    val fileActive = file.name in active
                    assertEquals("$label/$name: ${membership.message}",
                        if (fileActive) GoRunBuildStatus.ACTIVE else GoRunBuildStatus.IGNORED, membership.status)
                    rows += linkedMapOf("label" to label, "goos" to os, "tags" to tags, "file" to file.name,
                        "name" to name, "goListFileActive" to fileActive, "goListActiveFiles" to active,
                        "markerPresent" to markerPresent, "producerPresent" to (configuration != null),
                        "bothNativePopupActionsEnabled" to enabled, "actionTexts" to actionTexts,
                        "pattern" to configuration?.parameters?.testPattern, "membershipStatus" to membership.status.name,
                        "sourceOrigin" to configuration?.sourceSelection?.name)
                    if (!fileActive && (configuration != null || enabled)) unexpected += "$label/$name"
                    if (fileActive && (!markerPresent || configuration == null || !enabled)) missingActive += "$label/$name"
                    if (os == nativeOs && tags.isEmpty() && name in listOf("TestHost", "TestWindowsOnly") && configuration != null && enabled) {
                        // Perform the actual platform popup child; SDK's unit guard registers the configuration but does not launch it.
                        // Then independently execute that exact selected command with the same selected Go/settings on BGT.
                        val selected = invokeNativeGoGutter(file, anchor, DefaultRunExecutor.EXECUTOR_ID, root.toString())
                        assertEquals("^$name$", selected.testPattern)
                        val result = execute(selected.commandArguments(GoRunKind.TEST, settings.buildTags))
                        val text = result["stdout"].toString()
                        assertEquals(text, 0, result["exitCode"])
                        if (fileActive) assertTrue(text, text.contains(if (name == "TestHost") "HOST_EXECUTED" else "WINDOWS_EXECUTED"))
                        else {
                            assertFalse(text, text.contains("WINDOWS_EXECUTED"))
                            assertFalse(text, text.contains("\"Test\":\"TestWindowsOnly\""))
                            assertTrue(text, text.contains("no tests to run"))
                        }
                    }
                }
            }
            val report = linkedMapOf("phase" to "entry-gate-matrix", "rows" to rows, "commands" to commands, "unexpectedRunnableInactiveEntries" to unexpected, "missingActiveEntries" to missingActive,
                "nativeExecutionScope" to "Real native producer/popup update+perform; actual Go subprocess independently consumes registered parameters. No native desktop assertion.",
                "fixtureSources" to sources, "goMod" to Files.readString(root.resolve("go.mod")),
                "selectedGo" to go.toString(), "selectedGoSha256" to hash(Files.readAllBytes(go)),
                "runtimeResources" to listOf(GoRunLineMarkerContributor::class.java, GoTestConfigurationProducer::class.java, GoSourceFunctions::class.java,
                    GoRunBuildContexts::class.java, GoRunBuildContextLoader::class.java, GoCommandLineState::class.java).map { type ->
                    val resource = "/" + type.name.replace('.', '/') + ".class"
                    mapOf("name" to type.name, "resource" to type.getResource(resource)?.toString(),
                        "resourceSha256" to hash(requireNotNull(type.getResourceAsStream(resource)).use { it.readAllBytes() }),
                        "codeSource" to type.protectionDomain?.codeSource?.location?.toString(), "classLoader" to type.classLoader.toString())
                })
            Files.writeString(output.resolve("matrix.json"), GsonBuilder().setPrettyPrinting().create().toJson(report))
            assertTrue("Inactive files expose enabled exact-function entrypoints: $unexpected", unexpected.isEmpty())
            assertTrue("Active entries were lost: $missingActive", missingActive.isEmpty())
        } finally {
            XmlSerializerUtil.copyBean(before, GoProjectSettings.get(project))
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root)
            Disposer.dispose(lifetime)
            if (evidence == null) Files.walk(output).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    private fun awaitMembership(file: PsiFile): GoRunBuildView {
        val contexts = GoRunBuildContextService.get(project)
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(45)
        while (true) {
            val state = contexts.lookup(file)
            if (state.status != GoRunBuildStatus.CHECKING) return state
            if (System.nanoTime() > deadline) error("No completed build context for ${file.name}")
            Thread.sleep(10)
        }
    }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun onEdt(action: () -> Unit) = EdtTestUtil.runInEdtAndWait<RuntimeException> { WriteIntentReadAction.run(Runnable(action)) }
}
