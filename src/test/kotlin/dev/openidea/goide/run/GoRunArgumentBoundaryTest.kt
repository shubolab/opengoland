package dev.openidea.goide.run

import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.testFramework.EdtTestUtil
import com.intellij.testFramework.LightPlatformTestCase
import com.intellij.util.xmlb.XmlSerializerUtil
import dev.openidea.goide.debug.GoDebugLaunch
import dev.openidea.goide.settings.GoProjectSettings
import dev.openidea.goide.settings.GoToolchain
import java.awt.Component
import java.awt.Container
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JLabel

/** The existing native form and the real production plans both retain Go test's explicit -args contract. */
class GoRunArgumentBoundaryTest : LightPlatformTestCase() {
    override fun runInDispatchThread() = false

    fun testNativeTestArgumentsAndProductionRunDebugPlansPreserveBuildFlagsVersusCustomBinaryFlags() {
        val root = Files.createTempDirectory("owned arguments 中文😀 ").toRealPath()
        val before = GoProjectSettings.Data().also { XmlSerializerUtil.copyBean(GoProjectSettings.get(project), it) }
        try {
            Files.writeString(root.resolve("go.mod"), "module example.org/argumentboundary\n\ngo 1.26.10\n")
            Files.writeString(root.resolve("flags_test.go"), "package fixture\nimport (\"flag\"; \"testing\")\nvar customTags=flag.String(\"tags\",\"default\",\"owned custom binary flag\")\nfunc TestBinaryFlag(t *testing.T) { t.Log(\"BINARY_TAGS=\"+*customTags) }\n")
            Files.writeString(root.resolve("special_test.go"), "//go:build special\n\npackage fixture\nimport \"testing\"\nfunc TestSpecial(t *testing.T) { t.Log(\"BUILD_TAG_EXECUTED\") }\n")
            GoProjectSettings.get(project).apply {
                goExecutable = Path.of(requireNotNull(GoToolchain.resolve("go", requireNotNull(System.getProperty("opengoland.testGo")?.takeIf(String::isNotBlank) ?: System.getenv("OPENGOLAND_GO_TEST")?.takeIf(String::isNotBlank)) { "Set OPENGOLAND_GO_TEST to the selected runtime Go SDK" })) { "A selected Go SDK is required for actual build-context controls" }).toRealPath().toString()
                goos = ""; goarch = ""; cgoEnabled = "0"; buildTags = ""
                environment = "GOENV=off\nGOFLAGS=\nGOWORK=off\nGOTOOLCHAIN=local\nGOPROXY=off\nGOSUMDB=off\nGOTELEMETRY=off\nGOMAXPROCS=2"
            }
            val configuration = edt {
                (GoTestConfigurationType().configurationFactories.single().createTemplateConfiguration(project) as GoRunConfiguration).apply {
                    parameters = GoRunParameters(workingDirectory = root.toString(), buildFlags = "-count=1", arguments = "-v -timeout 30s")
                }
            }
            edt {
                val editor = GoRunSettingsEditor(GoRunKind.TEST)
                try {
                    val component = editor.component
                    val texts = labels(component)
                    assertTrue(texts.toString(), "Test arguments:" in texts)
                    assertTrue(texts.toString(), "Go tool arguments:" in texts)
                    assertFalse(texts.toString(), "Program arguments:" in texts)
                    editor.resetFrom(configuration)
                    editor.applyTo(configuration)
                    assertEquals("-v -timeout 30s", configuration.parameters.arguments)
                } finally { com.intellij.openapi.util.Disposer.dispose(editor) }
            }
            val settings = GoToolchain.capture(project)
            fun execute(arguments: List<String>, executable: String = "go"): String {
                val command = GoToolchain.command(project, "go", arguments, settings).withWorkDirectory(root.toFile()).withCharset(Charsets.UTF_8)
                if (executable != "go") command.withExePath(executable)
                val process = CapturingProcessHandler(command).apply { setShouldDestroyProcessRecursively(true) }
                val result = process.runProcess(120_000, true)
                assertFalse("Owned argument probe timed out", result.isTimeout)
                assertFalse(result.isCancelled)
                assertTrue(process.waitFor(5_000))
                assertEquals(result.stdout + result.stderr, 0, result.exitCode)
                return result.stdout + result.stderr
            }
            for ((label, arguments, tagged, binaryValue) in listOf(
                Probe("bare-build-flag", "-v -timeout 30s -tags=special", true, "default"),
                Probe("explicit-binary-flag", "-v -timeout 30s -args -tags=中文😀", false, "中文😀"),
            )) {
                val values = configuration.parameters.copy(arguments = arguments)
                val runPlan = values.commandArguments(GoRunKind.TEST, settings.buildTags)
                assertEquals("The production Run path must retain the user's explicit boundary", arguments.contains("-args"), "-args" in runPlan)
                val run = execute(runPlan)
                assertEquals(run, tagged, run.contains("BUILD_TAG_EXECUTED"))
                assertTrue(run, run.contains("BINARY_TAGS=$binaryValue"))
                val debug = GoDebugLaunch.fromRun(GoRunKind.TEST, values, root.toString())
                assertEquals(debug.toString(), tagged, debug.buildFlags.any { it == "-tags=special" })
                assertFalse(debug.toString(), debug.buildFlags.any { it.startsWith("-count") || it.startsWith("-timeout") })
                val binary = root.resolve(label + if (com.intellij.openapi.util.SystemInfo.isWindows) ".exe" else "")
                execute(listOf("test", "-c", "-o", binary.toString()) + debug.buildFlags + ".")
                val output = execute(debug.arguments, binary.toString())
                assertEquals(output, tagged, output.contains("BUILD_TAG_EXECUTED"))
                assertTrue(output, output.contains("BINARY_TAGS=$binaryValue"))
            }
        } finally {
            edt { FileDocumentManager.getInstance().saveAllDocuments() }
            XmlSerializerUtil.copyBean(before, GoProjectSettings.get(project))
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
    private data class Probe(val label: String, val arguments: String, val tagged: Boolean, val value: String)
    private fun labels(component: Component): List<String> = (if (component is JLabel) listOf(component.text.orEmpty()) else emptyList()) +
        if (component is Container) component.components.flatMap(::labels) else emptyList()
    private fun <T> edt(action: () -> T): T {
        var result: T? = null
        EdtTestUtil.runInEdtAndWait<RuntimeException> { WriteIntentReadAction.run(Runnable { result = action() }) }
        @Suppress("UNCHECKED_CAST") return result as T
    }
}
