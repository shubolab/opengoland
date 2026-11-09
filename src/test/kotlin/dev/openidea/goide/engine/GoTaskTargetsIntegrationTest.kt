package dev.openidea.goide.engine

import com.google.gson.JsonObject
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.openapi.util.Key
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.EdtTestUtil
import com.intellij.testFramework.HeavyPlatformTestCase
import dev.openidea.goide.settings.GoProjectSettings
import dev.openidea.goide.settings.GoToolchain
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Real native ProcessHandler -> actual engine RPC -> selected SDK, with no network dependency. */
class GoTaskTargetsIntegrationTest : HeavyPlatformTestCase() {
    override fun runInDispatchThread() = false
    override fun isCreateDirectoryBasedProject() = true
    override fun getProjectDirOrFile(isDirectoryBased: Boolean): Path =
        super.getProjectDirOrFile(true).resolve("模块任务 中文 😀")

    fun testEditingASubmoduleFileTidiesThatModuleAndRetainsTheOtherModule() = fixture { state ->
        val inspection = state.inspect()
        val selected = inspection.targets.single { it.id == inspection.defaultTargetId }
        assertEquals(state.root.resolve("one").toString(), selected.cwd)
        val output = state.run(inspection, selected)
        assertEquals(output.text, 0, output.code)
        assertTrue(output.text, output.text.contains("Working directory: ${selected.cwd}"))
        assertFalse(Files.readString(state.root.resolve("one/go.mod")).contains("require example.org/drop"))
        assertTrue("A Tidy from one/source.go must not silently tidy the other member", Files.readString(state.root.resolve("two/go.mod")).contains("require example.org/drop"))
        assertEquals(WORK, Files.readString(state.root.resolve("go.work")))
    }

    fun testAChangedManifestRejectsTheFrozenSelectionBeforeExecutingTidy() = fixture { state ->
        val inspection = state.inspect()
        val selected = inspection.targets.single { it.id == inspection.defaultTargetId }
        Files.writeString(state.root.resolve("one/go.mod"), state.module("one") + "\n// edited after choosing the target\n")
        val output = state.run(inspection, selected)
        assertTrue(output.text, output.code != 0)
        assertTrue(output.text, output.text.contains("changed", ignoreCase = true) || output.text.contains("stale", ignoreCase = true))
        assertFalse("A rejected selection must not start the Go command", output.text.contains("Working directory:"))
        assertTrue(Files.readString(state.root.resolve("one/go.mod")).contains("require example.org/drop"))
    }

    fun testANativeDirtyManifestDuringPreparationCancelsThePausedTaskWithoutWritingDisk() = fixture { state ->
        val inspection = state.inspect()
        val selected = inspection.targets.single { it.id == inspection.defaultTargetId }
        val path = state.root.resolve("one/go.mod")
        val original = Files.readString(path)
        val virtual = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!
        val document = ReadAction.compute<Document, RuntimeException> { FileDocumentManager.getInstance().getDocument(virtual)!! }
        try {
            val output = state.run(inspection, selected) {
                WriteCommandAction.runWriteCommandAction(project) { document.insertString(document.textLength, "\n// 未保存 SDK 准备期间 😀\n") }
            }
            assertTrue(output.text, output.code != 0)
            assertTrue(output.text, output.text.contains("unsaved", ignoreCase = true))
            assertFalse("No SDK task step may begin after the native document guard rejected it", output.text.contains("Working directory:"))
            assertEquals("A rejected native edit must leave the original module file on disk", original, Files.readString(path))
            assertTrue(document.text.contains("未保存 SDK 准备期间 😀"))
        } finally {
            EdtTestUtil.runInEdtAndWait<RuntimeException> {
                WriteCommandAction.runWriteCommandAction(project) { document.setText(original) }
                FileDocumentManager.getInstance().saveDocument(document)
            }
        }
    }

    private data class Result(val code: Int, val text: String)
    private inner class Fixture(val root: Path, val engine: EngineClient, val settings: GoToolchain.Settings, val environment: Map<String, String>) {
        fun module(name: String) = "module example.org/$name\n\ngo 1.24.0\n\nrequire example.org/drop v0.0.0\n\nreplace example.org/drop => ../dependency\n"
        fun inspect(): GoTaskTargetInspection {
            val params = JsonObject().apply {
                addProperty("command", "tidy"); addProperty("contextPath", root.resolve("one/source.go").toString())
                add("env", JsonObject().also { env -> environment.forEach { (key, value) -> env.addProperty(key, value) } })
            }
            return GoTaskTargetsCodec.decode(engine.request("project/taskTargets", params, settings, 90).get(90, TimeUnit.SECONDS).asJsonObject, "tidy")
        }
        fun run(inspection: GoTaskTargetInspection, selected: GoTaskTarget, afterStart: () -> Unit = {}): Result {
            lateinit var guard: GoTaskDocumentGuard
            EdtTestUtil.runInEdtAndWait<RuntimeException> { guard = GoTaskDocumentGuard.capture(project, listOf(root.toString())).including(inspection) }
            val handler = GoTaskProcessHandler(project, "tidy", selected.cwd, settings, environment,
                inspection.selection(selected), selected.moduleRoots, guard)
            val text = StringBuffer()
            handler.addProcessListener(object : ProcessAdapter() {
                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) { text.append(event.text) }
            })
            try {
                EdtTestUtil.runInEdtAndWait<RuntimeException> { handler.startNotify(); afterStart() }
                assertTrue("The actual task must reach a terminal state: $text", handler.waitFor(90_000))
                return Result(requireNotNull(handler.exitCode), text.toString())
            } finally {
                if (!handler.isProcessTerminated) {
                    handler.destroyProcess()
                    assertTrue("Owned task did not stop", handler.waitFor(15_000))
                }
            }
        }
    }

    private fun fixture(action: (Fixture) -> Unit) {
        val previous = System.getProperty("openidea.workspace")
        val repository = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
        System.setProperty("openidea.workspace", repository.toString())
        val root = Files.createDirectories(Path.of(requireNotNull(project.basePath))).toRealPath()
        val owned = Disposer.newDisposable("task scope fixture")
        VfsRootAccess.allowRootAccess(owned, root.toString())
        val engine = project.getService(EngineClient::class.java)
        try {
            val projectSettings = GoProjectSettings.get(project)
            projectSettings.goExecutable = requireNotNull(GoToolchain.resolve("go"))
            projectSettings.environment = "GOWORK=${root.resolve("go.work")}\nGOPROXY=off\nGOTOOLCHAIN=local\nGOTELEMETRY=off"
            val settings = GoToolchain.capture(project)
            val state = Fixture(root, engine, settings, GoToolchain.command(project, "go", emptyList(), settings).environment.toMap())
            Files.writeString(root.resolve("go.work"), WORK)
            listOf("one", "two").forEach { name ->
                val module = Files.createDirectories(root.resolve(name))
                Files.writeString(module.resolve("go.mod"), state.module(name))
                Files.writeString(module.resolve("source.go"), "package fixture\nfunc Value() int { return 1 }\n")
            }
            val dependency = Files.createDirectories(root.resolve("dependency"))
            Files.writeString(dependency.resolve("go.mod"), "module example.org/drop\n\ngo 1.24.0\n")
            Files.writeString(dependency.resolve("source.go"), "package drop\n")
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root)!!.refresh(false, true)
            action(state)
        } finally {
            engine.dispose()
            Disposer.dispose(owned)
            if (previous == null) System.clearProperty("openidea.workspace") else System.setProperty("openidea.workspace", previous)
        }
    }
    companion object { private const val WORK = "go 1.24.0\n\nuse (\n    ./one\n    ./two\n)\n" }
}
