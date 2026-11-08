package dev.openidea.goide.refactor.signature

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.EdtTestUtil
import com.intellij.testFramework.LightPlatformTestCase
import dev.openidea.goide.lsp.GoDocumentSync
import dev.openidea.goide.settings.GoProjectSettings
import dev.openidea.goide.settings.GoToolchain
import java.nio.file.Files
import java.nio.file.Path

/** Actual native documents/VFS plus real disk inputs; no mocked document or settings service. */
class GoSignatureValidationTest : LightPlatformTestCase() {
    override fun runInDispatchThread() = false

    fun testUneditedDependencyChangingOnDiskInvalidatesTheWholeAnalyzedPlan() = fixture { root ->
        val main = root.resolve("main.go"); val dependency = root.resolve("dependency.go")
        Files.writeString(main, "package fixture\nfunc Target(a int) {}\n")
        Files.writeString(dependency, "package fixture\nvar Value = 1\n")
        val guard = GoSignatureValidation(project, plan(main, dependency), GoToolchain.configurationKey(project))
        guard.validateBackground()
        onEdt { guard.validateDocuments() }
        Files.writeString(dependency, "package fixture\nvar Value = 2\n")
        mustReject { guard.validateBackground() }
        assertTrue(Files.readString(main).contains("Target(a int)"))
    }

    fun testNativeDocumentMutationAfterValidationCannotReachCommit() = fixture { root ->
        val file = root.resolve("main.go"); Files.writeString(file, "package fixture\nvar Value = 1\n")
        val virtual = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file)!!
        val document = ReadAction.compute<Document, RuntimeException> { FileDocumentManager.getInstance().getDocument(virtual)!! }
        val value = plan(file)
        value.getAsJsonArray("preconditions")[0].asJsonObject.addProperty("documentStamp", document.modificationStamp)
        val guard = GoSignatureValidation(project, value, GoToolchain.configurationKey(project))
        guard.validateBackground()
        onEdt {
            WriteCommandAction.runWriteCommandAction(project) { document.setText("package fixture\nvar Value = 9\n") }
            mustReject { guard.validateDocuments() }
        }
        mustReject { guard.validateBackground() }
    }

    fun testBomAndCrLfUseNativeEditorTextAndSdkChangesInvalidateThePlan() = fixture { root ->
        val file = root.resolve("main.go"); Files.writeString(file, "\uFEFFpackage fixture\r\nvar Value = 1\r\n")
        val guard = GoSignatureValidation(project, plan(file), GoToolchain.configurationKey(project))
        guard.validateBackground()
        onEdt { guard.validateDocuments() }
        val settings = GoProjectSettings.get(project); val old = settings.goos
        try {
            settings.goos = if (old == "linux") "darwin" else "linux"
            mustReject { guard.validateBackground() }
            onEdt { mustReject { guard.validateDocuments() } }
        } finally { settings.goos = old }
    }

    fun testCreationOfPreviouslyAbsentModuleSumInvalidatesAnalysis() = fixture { root ->
        val file = root.resolve("go.sum")
        val guard = GoSignatureValidation(project, plan(file), GoToolchain.configurationKey(project))
        guard.validateBackground()
        Files.writeString(file, "changed module graph\n")
        mustReject { guard.validateBackground() }
    }

    fun testNewGoCallerInAnUneditedPackageInvalidatesDirectoryMembership() = fixture { root ->
        val a = Files.createDirectory(root.resolve("a")); val b = Files.createDirectory(root.resolve("b"))
        val target = a.resolve("api.go"); val unrelated = b.resolve("existing.go")
        Files.writeString(target, "package a\nfunc Target(a,b int) {}\n")
        Files.writeString(unrelated, "package b\nvar Existing = 1\n")
        val guard = GoSignatureValidation(project, plan(target, unrelated), GoToolchain.configurationKey(project))
        guard.validateBackground()
        Files.writeString(b.resolve("new.go"), "package b\nimport \"example/a\"\nfunc New(){a.Target(1,2)}\n")
        mustReject { guard.validateBackground() }
        assertTrue(Files.readString(target).contains("Target(a,b int)"))
    }

    fun testNativeCreationOfNewPackageDuringPreviewIsRejectedBeforeWrite() = fixture { root ->
        val target = root.resolve("api.go"); Files.writeString(target, "package fixture\nfunc Target(a,b int) {}\n")
        val guard = GoSignatureValidation(project, plan(target), GoToolchain.configurationKey(project))
        guard.validateBackground()
        val virtual = LocalFileSystem.getInstance().findFileByNioFile(root)!!
        onEdt { WriteCommandAction.runWriteCommandAction(project) {
            virtual.createChildDirectory(this, "newpackage")
            mustReject { guard.validateDocuments() }
        } }
        assertTrue(Files.readString(target).contains("Target(a,b int)"))
    }

    fun testOldDiskHashCannotBeCombinedWithNewVfsStamp() = fixture { root ->
        val target = root.resolve("api.go"); val dependency = root.resolve("dependency.go")
        Files.writeString(target, "package fixture\nfunc Target(a,b int) {}\n")
        Files.writeString(dependency, "package fixture\nvar Value = 1\n")
        val value = plan(target, dependency)
        val virtual = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(dependency)!!
        val modified = Files.getLastModifiedTime(dependency)
        var changed = false
        val guard = GoSignatureValidation(project, value, GoToolchain.configurationKey(project), readDisk = { path ->
            val oldBytes = Files.readAllBytes(path)
            if (path == dependency && !changed) {
                changed = true
                Files.writeString(dependency, "package fixture\nvar Value = 9\n")
                Files.setLastModifiedTime(dependency, java.nio.file.attribute.FileTime.fromMillis(modified.toMillis() + 5000))
                virtual.refresh(false, false)
            }
            oldBytes
        })
        mustReject { guard.validateBackground() }
        assertTrue("The actual external write must occur between hashing and VFS validation", changed)
        assertTrue(Files.readString(target).contains("Target(a,b int)"))
    }

    fun testAdditionalMainModuleRootsRequireValidatedConfigurationAndDirectoryInputs() = fixture { root ->
        val mod = root.resolve("go.mod"); val target = root.resolve("api.go")
        Files.writeString(mod, "module fixture\n\ngo 1.24.0\n"); Files.writeString(target, "package fixture\nfunc Target() {}\n")
        val value = plan(mod, target).apply { add("coverage", JsonObject().apply { add("modules", JsonArray().apply { add(root.toString()) }) }) }
        val guard = GoSignatureValidation(project, value, GoToolchain.configurationKey(project))
        mustReject { guard.additionalEditableRoots() }
        guard.validateBackground()
        assertEquals(setOf(root), guard.additionalEditableRoots())
    }

    private fun plan(vararg paths: Path) = JsonObject().apply {
        add("preconditions", JsonArray().apply { paths.forEach { path ->
            val exists = Files.exists(path); val bytes = if (exists) Files.readAllBytes(path) else byteArrayOf()
            add(JsonObject().apply {
                addProperty("uri", GoDocumentSync.fileUri(path)); addProperty("exists", exists)
                addProperty("diskSha256", GoSignatureValidation.sha256(bytes))
                addProperty("textSha256", GoSignatureValidation.sha256(GoSignatureValidation.canonicalText(bytes).toByteArray()))
            })
        } })
        add("directories", JsonArray().apply {
            paths.map { it.parent }.distinct().forEach { directory -> add(JsonObject().apply {
                addProperty("uri", GoDocumentSync.fileUri(directory))
                add("entries", JsonArray().apply { Files.newDirectoryStream(directory).use { entries ->
                    entries.map { child -> (if (Files.isDirectory(child)) "d:" else "f:") + child.fileName }.sorted().forEach(::add)
                } })
            }) }
        })
    }
    private fun fixture(action: (Path) -> Unit) {
        val root = Files.createTempDirectory("opengoland-signature-guard-").toRealPath()
        val disposable = Disposer.newDisposable("Go signature guard fixture")
        VfsRootAccess.allowRootAccess(disposable, root.toString())
        try { action(root) } finally {
            val virtual = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root)
            virtual?.children
            onEdt { WriteCommandAction.runWriteCommandAction(project) {
                FileDocumentManager.getInstance().unsavedDocuments.filter { doc -> FileDocumentManager.getInstance().getFile(doc)?.path?.startsWith(root.toString()) == true }
                    .forEach { FileDocumentManager.getInstance().saveDocument(it) }
                if (virtual?.isValid == true) virtual.delete(this)
            } }
            if (Files.exists(root)) Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
            Disposer.dispose(disposable)
        }
    }
    private fun mustReject(action: () -> Unit) {
        var rejected = false
        try { action() } catch (_: IllegalArgumentException) { rejected = true }
        assertTrue("A stale signature analysis must not be applied", rejected)
    }
    private fun onEdt(action: () -> Unit) = EdtTestUtil.runInEdtAndWait<RuntimeException> { WriteIntentReadAction.run(Runnable(action)) }
}
