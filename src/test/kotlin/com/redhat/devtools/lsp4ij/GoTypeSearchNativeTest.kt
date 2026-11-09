package com.redhat.devtools.lsp4ij

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.ide.util.gotoByName.ChooseByNamePopup
import com.intellij.ide.util.gotoByName.GotoClassModel2
import com.intellij.ide.util.gotoByName.DefaultChooseByNameItemProvider
import com.intellij.ide.actions.searcheverywhere.ClassSearchEverywhereContributor
import com.intellij.ide.actions.GotoClassPresentationUpdater
import com.intellij.lang.IdeLanguageCustomization
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.navigation.ChooseByNameContributor
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.util.ProgressIndicatorBase
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileWithId
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.EdtTestUtil
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.LightPlatformTestCase
import com.intellij.testFramework.replaceService
import com.intellij.util.Processor
import com.intellij.util.indexing.FindSymbolParameters
import com.intellij.util.indexing.IdFilter
import com.intellij.ide.impl.OpenProjectTask
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ex.ProjectManagerEx
import dev.openidea.goide.engine.EngineClient
import com.redhat.devtools.lsp4ij.features.workspaceSymbol.LSPWorkspaceGotoClassContributor
import dev.openidea.goide.lsp.GoDocumentSync
import dev.openidea.goide.navigation.search.*
import dev.openidea.goide.settings.GoProjectSettings
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Real GotoClassModel2 + ChooseByNamePopup item provider, with an owned uncapped backend peer. */
class GoTypeSearchNativeTest : LightPlatformTestCase() {
    override fun runInDispatchThread() = false

    fun testActualStandardSearchHostUsesTheGoIdePrimaryLanguageTitle() = fixture { f ->
        val owner = Disposer.newDisposable("owned primary language service control")
        try {
            val primary = if (java.lang.Boolean.getBoolean("openidea.primaryLanguageBaseline")) IdeLanguageCustomization() else GoIdeLanguageCustomization()
            edt {
                ApplicationManager.getApplication().replaceService(IdeLanguageCustomization::class.java, primary, owner)
                val event = AnActionEvent.createFromDataContext(ActionPlaces.UNKNOWN, null, SimpleDataContext.getProjectContext(project))
                val host = ClassSearchEverywhereContributor(event)
                try {
                    assertEquals("Types", host.groupName)
                    assertEquals("types", host.fullGroupName)
                    assertEquals("Type", GotoClassPresentationUpdater.getActionTitle())
                    assertEquals("A product title must not replace native Go declaration lookup", listOf("PilotRecord"),
                        f.popup.calcPopupElements("PilotRecord", true).filterIsInstance<GoTypeSearchItem>().map { it.name })
                } finally { Disposer.dispose(host) }
            }
        } finally { edt { Disposer.dispose(owner) } }
    }

    fun testPublishedAllPlacesCatalogAlsoServesTheStrictProjectScope() = fixture { f ->
        assertEquals(listOf("SdkBuffer"), f.search("SdkBuffer").map { it.name })
        assertEquals(listOf("PilotRecord"), ReadAction.compute<List<GoTypeSearchItem>, RuntimeException> { f.search("PilotRecord", everywhere = false) }.map { it.name })
        assertTrue(f.search("SdkBuffer", everywhere = false).isEmpty())
        assertEquals("A validated superset must not be rebuilt for a narrower native scope", 1, f.source.requests.size)
    }

    fun testUnrelatedUnsavedGoDocumentDoesNotDiscardAPublishedCatalog() = fixture { f ->
        val path = f.base.resolve("unrelated.go")
        Files.writeString(path, "package unrelated\ntype UnrelatedOne int\n")
        val file = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path))
        val document = ReadAction.compute<Document, RuntimeException> { requireNotNull(FileDocumentManager.getInstance().getDocument(file)) }
        edt { WriteCommandAction.runWriteCommandAction(project) { document.insertString(document.textLength, "// unsaved one\n") } }
        val item = f.search("PilotRecord").single()
        edt { WriteCommandAction.runWriteCommandAction(project) { document.insertString(document.textLength, "// unsaved two\n") } }
        assertEquals(listOf("PilotRecord"), f.search("PilotRecord").map { it.name })
        assertTrue("After background alias classification, an unrelated editor cannot discard this declaration", item.canNavigate())
        assertEquals("The backend excludes unrelated overlays; the native cache must do so too", 1, f.source.requests.size)
    }

    fun testUnrelatedGoVfsCreationDoesNotDiscardAPublishedCatalog() = fixture { f ->
        val parent = requireNotNull(LocalFileSystem.getInstance().findFileByNioFile(f.base))
        parent.children // Establish real cached membership before the native refresh.
        val path = f.base.resolve("unrelated-vfs.go")
        val delivered = CountDownLatch(1)
        val connection = project.messageBus.connect()
        connection.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                if (events.any { Path.of(it.path) == path }) delivered.countDown()
            }
        })
        try {
            val item = f.search("PilotRecord").single()
            Files.writeString(path, "package unrelated\ntype ForeignRecord int\n")
            VfsUtil.markDirtyAndRefresh(false, true, true, parent)
            assertTrue("The real native refresh must deliver this VFS change", delivered.await(10, TimeUnit.SECONDS))
            assertEquals(listOf("PilotRecord"), f.search("PilotRecord").map { it.name })
            assertTrue("After background physical membership classification, the unrelated VFS change cannot discard this target", item.canNavigate())
            assertEquals(1, f.source.requests.size)
        } finally { connection.disconnect() }
    }

    fun testProjectScopeDoesNotAcquireDependencyRowsInsideAContentRootFromTheSuperset() = fixture { f ->
        f.source.dependencyFiles = setOf("other.go")
        assertTrue(ReadAction.compute<Boolean, RuntimeException> { GlobalSearchScope.projectScope(project).contains(f.files.getValue("other.go")) })
        assertEquals(listOf("OnlyOther"), f.search("OnlyOther").map { it.name })
        assertTrue(f.search("OnlyOther", everywhere = false).isEmpty())
        assertEquals(1, f.source.requests.size)
    }

    fun testRecordedMetadataAndNewWorkspaceDirectoriesStillInvalidateTheCatalog() = fixture { f ->
        val metadata = f.root.resolve("go.mod")
        Files.writeString(metadata, "module example.org/probe\n\ngo 1.24.0\n")
        val file = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(metadata))
        val document = ReadAction.compute<Document, RuntimeException> { requireNotNull(FileDocumentManager.getInstance().getDocument(file)) }
        val before = f.search("PilotRecord").single()
        edt { WriteCommandAction.runWriteCommandAction(project) { document.insertString(document.textLength, "// context changed\n") } }
        assertFalse(before.canNavigate())
        val afterMetadata = f.search("PilotRecord").single()
        assertEquals(2, f.source.requests.size)
        Files.createDirectory(f.root.resolve("new-package"))
        LocalFileSystem.getInstance().refreshFiles(listOf(requireNotNull(LocalFileSystem.getInstance().findFileByNioFile(f.root))), false, true, null)
        edt { }
        assertFalse("New package membership is never silently ignored", afterMetadata.canNavigate())
        assertEquals(listOf("PilotRecord"), f.search("PilotRecord").map { it.name })
        assertEquals(3, f.source.requests.size)
    }

    fun testAnInitiallyUnobservedSymlinkBufferIsClassifiedBeforeCachedNamesCanBeReused() = fixture { f ->
        val link = f.base.resolve("late-buffer-alias")
        Files.createSymbolicLink(link, f.root)
        val alias = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(link.resolve("types.go")))
        val old = f.search("PilotRecord").single()
        val document = ReadAction.compute<Document, RuntimeException> { requireNotNull(FileDocumentManager.getInstance().getDocument(alias)) }
        val dirty = "// 新缓冲区 😀\n" + f.initial.getValue("types.go").replace("PilotRecord", "AliasPilot")
        edt { WriteCommandAction.runWriteCommandAction(project) { document.setText(dirty) } }
        assertEquals(listOf("AliasPilot"), f.search("AliasPilot").map { it.name })
        assertFalse(old.canNavigate())
        assertEquals(2, f.source.requests.size)
        assertEquals(f.initial.getValue("types.go"), Files.readString(f.paths.getValue("types.go")))
    }

    fun testDisposalDuringUnknownPathClassificationCannotStartAnotherCatalog() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val finished = CountDownLatch(1)
        try {
            fixture(pathResolver = { path ->
                assertFalse(ApplicationManager.getApplication().isDispatchThread)
                entered.countDown()
                try { check(release.await(10, TimeUnit.SECONDS)); GoTypeCatalogCodec.physicalPath(Path.of(path)).toString() }
                finally { finished.countDown() }
            }) { f ->
                val path = f.base.resolve("unrelated-pending.go")
                Files.writeString(path, "package unrelated\n")
                val file = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path))
                val document = ReadAction.compute<Document, RuntimeException> { requireNotNull(FileDocumentManager.getInstance().getDocument(file)) }
                val old = f.search("PilotRecord").single()
                edt { WriteCommandAction.runWriteCommandAction(project) { document.insertString(document.textLength, "// changed\n") } }
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                assertFalse("Unknown physical membership pauses old navigation", old.canNavigate())
                edt { Disposer.dispose(f.index) }
                release.countDown()
                assertTrue(finished.await(10, TimeUnit.SECONDS))
                assertFalse(old.canNavigate())
                assertEquals(1, f.source.requests.size)
            }
        } finally { release.countDown(); assertTrue(finished.await(10, TimeUnit.SECONDS)) }
    }

    fun testActualNativeClassProviderFindsColdGoTypesAndUnboundedNames() = fixture { f ->
        assertTrue("No Go editor is open", FileEditorManager.getInstance(project).openFiles.none { it.extension == "go" })
        val result = f.popupSearch("PilotRecord")
        assertEquals(listOf("PilotRecord"), result.map { it.name })
        assertEquals(listOf("Callback"), f.search("Callback").map { it.name })
        assertEquals(listOf("BoxAlias"), f.search("BoxAlias").map { it.name })
        assertEquals(listOf("Needle242"), f.search("Needle242").map { it.name })
        assertTrue(f.search("OrdinaryFunction").isEmpty())
        val names = f.model.getNames(true).toSet()
        assertEquals(243, names.count { it.startsWith("Needle") })
        assertEquals("Adjacent native name/item phases reuse the complete catalog", 1, f.source.requests.size)
    }

    fun testTheStandardProviderOwnsFuzzyAndQualifiedNameMatching() = fixture { f ->
        assertEquals(listOf("PilotRecord"), f.search("PR").map { it.name })
        assertEquals(listOf("PilotRecord"), f.search("probe.PilotRecord").map { it.name })
        val item = f.search("BoxAlias").single()
        assertEquals("example.org/probe.BoxAlias", f.contributor.getQualifiedName(item))
    }

    fun testTheNativeClassModelKeepsOtherLanguageContributorsAlongsideGoTypes() {
        val foreign = object : NavigationItem {
            override fun getName() = "ForeignRecord"
            override fun getPresentation() = object : com.intellij.navigation.ItemPresentation {
                override fun getPresentableText() = name
                override fun getLocationString() = "another native contributor"
                override fun getIcon(unused: Boolean): javax.swing.Icon? = null
            }
            override fun navigate(requestFocus: Boolean) {}
            override fun canNavigate() = true
            override fun canNavigateToSource() = false
        }
        val provider = object : ChooseByNameContributor, com.intellij.openapi.project.DumbAware {
            override fun getNames(project: Project, includeNonProjectItems: Boolean) = arrayOf("ForeignRecord")
            override fun getItemsByName(name: String, pattern: String, project: Project, includeNonProjectItems: Boolean): Array<NavigationItem> =
                if (name == foreign.name) arrayOf(foreign) else emptyArray()
        }
        fixture(listOf(provider)) { f ->
            edt {
                assertEquals(listOf(foreign), f.popup.calcPopupElements("ForeignRecord", true))
                assertEquals(listOf("PilotRecord"), f.popup.calcPopupElements("PilotRecord", true).filterIsInstance<GoTypeSearchItem>().map { it.name })
            }
        }
    }

    fun testNativeProjectAllPlacesAndExplicitScopeAndIdFilterRemainDistinct() = fixture { f ->
        assertEquals(listOf("SdkBuffer"), f.search("SdkBuffer").map { it.name })
        assertTrue(f.search("SdkBuffer", everywhere = false).isEmpty())
        val scope = ReadAction.compute<GlobalSearchScope, RuntimeException> { GlobalSearchScope.fileScope(project, f.files.getValue("other.go")) }
        val names = mutableListOf<String>()
        f.model.processNames(Processor { names.add(it); true }, FindSymbolParameters("", "", scope))
        assertEquals(setOf("OnlyOther"), names.toSet())
        val rejected = object : IdFilter() { override fun containsFileId(id: Int) = false }
        names.clear()
        f.model.processNames(Processor { names.add(it); true }, FindSymbolParameters("", "", scope, rejected))
        assertTrue(names.isEmpty())
        assertEquals("An All Places snapshot serves Project scope without another metadata build", 1,
            f.source.requests.map { it.includeExternal }.distinct().size)
    }

    fun testNativeContributorRespectsProcessorStopWithoutTruncatingItsCatalog() = fixture { f ->
        val parameters = FindSymbolParameters("", "", GlobalSearchScope.allScope(project))
        var count = 0
        f.contributor.processNames(Processor { count++; false }, parameters)
        assertEquals(1, count)
        assertEquals(243, f.model.getNames(true).count { it.startsWith("Needle") })
    }

    fun testActualNativeNamedTargetNavigatesAfterChineseAndSurrogateText() = fixture { f ->
        val item = f.search("模型").single()
        assertTrue(item.canNavigateToSource())
        edt { item.navigate(true) }
        edt {
            val editor = requireNotNull(FileEditorManager.getInstance(project).selectedTextEditor)
            assertSame(f.document("types.go"), editor.document)
            assertEquals(f.text("types.go").indexOf("模型"), editor.caretModel.offset)
        }
        assertEquals(f.initial.getValue("types.go"), Files.readString(f.paths.getValue("types.go")))
    }

    fun testDirtyPhysicalAliasOverlayAndNativeUndoKeepTheActualDocument() = fixture { f ->
        val link = f.base.resolve("alias")
        Files.createSymbolicLink(link, f.root)
        val alias = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(link.resolve("types.go")))
        val document = ReadAction.compute<Document, RuntimeException> { requireNotNull(FileDocumentManager.getInstance().getDocument(alias)) }
        lateinit var editor: com.intellij.openapi.editor.Editor
        edt { editor = EditorFactory.getInstance().createEditor(document, project) }
        try {
            val dirty = "// unsaved 前😀\n" + f.initial.getValue("types.go").replace("PilotRecord", "DirtyPilot")
            edt {
                WriteCommandAction.runWriteCommandAction(project) { document.setText(dirty) }
                PsiDocumentManager.getInstance(project).commitDocument(document)
            }
            val item = f.search("DirtyPilot").single()
            assertEquals(GoDocumentSync.fileUri(f.paths.getValue("types.go")),
                f.source.requests.last().overlays.single { it.get("text").asString == dirty }.get("uri").asString)
            edt { item.navigate(true) }
            edt {
                val target = requireNotNull(FileEditorManager.getInstance(project).selectedTextEditor)
                assertSame(document, target.document)
                assertEquals(dirty.indexOf("DirtyPilot"), target.caretModel.offset)
                val native = TextEditorProvider.getInstance().getTextEditor(editor)
                assertTrue(UndoManager.getInstance(project).isUndoAvailable(native))
                UndoManager.getInstance(project).undo(native)
                PsiDocumentManager.getInstance(project).commitDocument(document)
            }
            assertFalse("A target captured before Undo must not revive", item.canNavigate())
            assertEquals(listOf("PilotRecord"), f.search("PilotRecord").map { it.name })
            assertEquals(f.initial.getValue("types.go"), Files.readString(f.paths.getValue("types.go")))
        } finally { edt { if (!editor.isDisposed) EditorFactory.getInstance().releaseEditor(editor) } }
    }

    fun testHeldCatalogRejectsDirtyChangesAndTheNextNativeQueryUsesNewSource() = fixture { f ->
        val held = UncancellableReply()
        f.source.held = held
        val result = CompletableFuture<List<GoTypeSearchItem>>()
        val worker = Thread({ runCatching {
            // The asynchronous catalog boundary is tested outside the SDK's
            // native name-enumeration read lock; the next lookup uses its real provider.
            val snapshot = f.index.catalog(GlobalSearchScope.allScope(project))
            snapshot.byName["PilotRecord"].orEmpty().map(snapshot::materialize)
        }.fold(result::complete, result::completeExceptionally) }, "owned-go-type-held")
        worker.start()
        try {
            assertTrue(f.source.started.await(10, TimeUnit.SECONDS))
            val old = requireNotNull(f.source.captured)
            val document = f.document("types.go")
            edt {
                WriteCommandAction.runWriteCommandAction(project) { document.setText(f.initial.getValue("types.go").replace("PilotRecord", "ChangedPilot")) }
                PsiDocumentManager.getInstance(project).commitDocument(document)
            }
            held.complete(old)
            val delivered = runCatching { result.get(10, TimeUnit.SECONDS) }.getOrNull().orEmpty()
            assertTrue("An old reply must never produce an old navigable result", delivered.isEmpty())
            assertEquals(listOf("ChangedPilot"), f.search("ChangedPilot").map { it.name })
            assertFalse(f.source.canceled.isEmpty())
        } finally { f.source.captured?.let(held::complete) ?: held.cancel(false); worker.join(10_000); assertFalse(worker.isAlive) }
    }

    fun testCancellingNativeMatchingCancelsOnlyTheOwnedCatalogOperation() = fixture { f ->
        val held = UncancellableReply(); f.source.held = held
        val progress = ProgressIndicatorBase()
        val worker = Thread({ runCatching {
            ProgressManager.getInstance().runProcess(Runnable { f.search("PilotRecord") }, progress)
        } }, "owned-go-type-cancel")
        worker.start()
        try {
            assertTrue(f.source.started.await(10, TimeUnit.SECONDS))
            progress.cancel()
            worker.join(10_000); assertFalse(worker.isAlive)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (f.source.canceled.isEmpty() && System.nanoTime() < deadline) Thread.sleep(5)
            assertEquals(setOf(f.source.requests.single().operation), f.source.canceled.toSet())
            held.complete(requireNotNull(f.source.captured))
            assertEquals(listOf("PilotRecord"), f.search("PilotRecord").map { it.name })
        } finally { f.source.captured?.let(held::complete) ?: held.cancel(false); worker.join(10_000); assertFalse(worker.isAlive) }
    }

    fun testMalformedUtf16DoesNotClampOrPublishAnInvalidTypeTarget() = fixture { f ->
        f.source.badUtf16 = true
        assertTrue(f.search("模型").isEmpty())
        assertTrue(f.problems.any { it.contains("UTF-16") || it.contains("mismatched") || it.contains("source") })
    }

    fun testSettingsAndIndexDisposalMakeExistingTargetsNonNavigable() = fixture { f ->
        val item = f.search("PilotRecord").single()
        val settings = GoProjectSettings.get(project); val previous = settings.goos
        try {
            edt { settings.goos = if (previous == "windows") "linux" else "windows" }
            assertFalse(item.canNavigate())
        } finally { edt { settings.goos = previous } }
        val current = f.search("PilotRecord").single()
        edt { Disposer.dispose(f.index) }
        assertFalse(current.canNavigate())
    }

    fun testDisposalDuringAliasPreparationCannotStartACatalogRequest() = fixture { f ->
        val release = CountDownLatch(1); f.source.preparation = release
        val done = CompletableFuture<Any?>()
        val worker = Thread({ runCatching { f.index.catalog(GlobalSearchScope.allScope(project)) }.fold(done::complete, done::completeExceptionally) }, "owned-go-type-prepare")
        worker.start()
        try {
            assertTrue(f.source.preparing.await(10, TimeUnit.SECONDS))
            edt { Disposer.dispose(f.index) }
            release.countDown()
            assertTrue(f.source.released.await(10, TimeUnit.SECONDS))
            worker.join(10_000); assertFalse(worker.isAlive)
            assertTrue("Disposal before source preparation finishes must prevent the owned RPC", f.source.requests.isEmpty())
        } finally { release.countDown(); worker.join(10_000); assertFalse(worker.isAlive) }
    }

    fun testProjectDisposalDuringAliasPreparationCannotStartACatalogRequest() {
        val base = Files.createTempDirectory("open-goland-type-project-dispose-").toRealPath()
        var owner: Project? = null
        var worker: Thread? = null
        val release = CountDownLatch(1)
        try {
            owner = requireNotNull(ProjectManagerEx.getInstanceEx().newProject(base,
                OpenProjectTask { isNewProject = true; useDefaultProjectAsTemplate = false; projectName = "owned Go type disposal"; preloadServices = false; createModule = false }))
            val project = requireNotNull(owner)
            val peer = CatalogPeer(emptyMap()).apply { preparation = release }
            val index = GoTypeSearchIndex(project, peer) { peer.problems += it }
            Disposer.register(project, index)
            worker = Thread({ runCatching { index.catalog(GlobalSearchScope.allScope(project)) } }, "owned-go-type-project-prepare").apply { start() }
            assertTrue(peer.preparing.await(10, TimeUnit.SECONDS))
            edt { WriteAction.run<RuntimeException> { Disposer.dispose(project) } }
            release.countDown()
            assertTrue(peer.released.await(10, TimeUnit.SECONDS))
            worker.join(10_000); assertFalse(worker.isAlive)
            assertTrue(peer.requests.isEmpty())
        } finally {
            release.countDown(); worker?.join(10_000); assertTrue(worker?.isAlive != true)
            owner?.takeUnless { it.isDisposed }?.let { edt { WriteAction.run<RuntimeException> { Disposer.dispose(it) } } }
            base.toFile().deleteRecursively()
        }
    }

    fun testPreCancellationDoesNotCreateAnEngineOrResolveTheInvalidToolchain() {
        val settings = GoProjectSettings.get(project); val previous = settings.goExecutable
        val source = GoEngineTypeCatalogSource(project)
        try {
            edt { settings.goExecutable = "/owned/cancelled/sdk-must-not-be-resolved" }
            val input = GoTypeSearchInput.capture(project)
            val before = project.getServiceIfCreated(EngineClient::class.java)
            val operation = "owned-pre-cancel-type-search"
            edt { source.cancel(operation) }
            lateinit var result: CompletableFuture<JsonObject>
            edt { result = source.load(input, true, operation) }
            assertTrue("Pre-cancel completes before tool/filesystem work", result.isCancelled || result.isCompletedExceptionally)
            assertTrue(runCatching { result.get() }.exceptionOrNull() is java.util.concurrent.CancellationException)
            assertSame(before, project.getServiceIfCreated(EngineClient::class.java))
        } finally { source.dispose(); edt { settings.goExecutable = previous } }
    }

    private inner class Fixture(val base: Path, val root: Path, val paths: Map<String, Path>, val files: Map<String, VirtualFile>,
                                val initial: Map<String, String>, val source: CatalogPeer, val index: GoTypeSearchIndex) {
        val problems get() = source.problems
        val contributor = GoTypeSearchContributor(index)
        lateinit var model: GotoClassModel2
        lateinit var popup: ChooseByNamePopup
        fun document(name: String) = ReadAction.compute<Document, RuntimeException> {
            requireNotNull(FileDocumentManager.getInstance().getDocument(files.getValue(name)))
        }
        fun text(name: String) = ReadAction.compute<String, RuntimeException> { document(name).text }
        fun search(pattern: String, everywhere: Boolean = true): List<GoTypeSearchItem> {
            val items = mutableListOf<Any>()
            DefaultChooseByNameItemProvider(null).filterElements(popup, pattern, everywhere,
                ProgressManager.getInstance().progressIndicator ?: ProgressIndicatorBase(), Processor { items += it; true })
            if (!source.badUtf16) assertTrue("Unexpected Go type indexing problem: $problems", problems.isEmpty())
            return items.filterIsInstance<GoTypeSearchItem>()
        }
        fun popupSearch(pattern: String): List<GoTypeSearchItem> {
            var result = emptyList<GoTypeSearchItem>()
            edt { result = popup.calcPopupElements(pattern, true).filterIsInstance<GoTypeSearchItem>() }
            return result
        }
    }

    private fun fixture(additionalContributors: List<ChooseByNameContributor> = emptyList(),
                        pathResolver: (String) -> String = { GoTypeCatalogCodec.physicalPath(Path.of(it)).toString() },
                        block: (Fixture) -> Unit) {
        val base = Files.createTempDirectory("open-goland-type-search-").toRealPath()
        val root = base.resolve("project").apply { Files.createDirectory(this) }
        val external = base.resolve("sdk").apply { Files.createDirectory(this) }
        val disposable = Disposer.newDisposable("owned native Go type search")
        VfsRootAccess.allowRootAccess(disposable, base.toString())
        val initial = linkedMapOf("types.go" to "package probe\ntype PilotRecord struct { Value int }\ntype Callback func(int) string\ntype Box[T any] struct { Value T }\ntype BoxAlias[T any] = Box[T]\nvar _ = \"前😀\"; type 模型 struct{}\nfunc OrdinaryFunction() {}\n",
            "many.go" to "package probe\n" + (0..242).joinToString("") { "type Needle${it.toString().padStart(3, '0')} struct{}\n" },
            "other.go" to "package probe\ntype OnlyOther int\n", "sdk.go" to "package sdk\ntype SdkBuffer struct{}\n")
        val paths = initial.mapValues { (name, text) -> (if (name == "sdk.go") external else root).resolve(name).also { Files.writeString(it, text) } }
        val files = paths.mapValues { requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(it.value)) }
        edt { ModuleRootModificationUtil.addContentRoot(module, root.toString()) }
        val peer = CatalogPeer(paths)
        val index = GoTypeSearchIndex(project, peer, pathResolver) { peer.problems += it }
        Disposer.register(disposable, index)
        var fixture: Fixture? = null
        val previous = project.getUserData(ChooseByNamePopup.CURRENT_SEARCH_PATTERN)
        try {
            val actual = Fixture(base, root, paths, files, initial, peer, index)
            fixture = actual
            val provider = if (java.lang.Boolean.getBoolean("openidea.typeSearchBaseline")) LSPWorkspaceGotoClassContributor() else actual.contributor
            ExtensionTestUtil.maskExtensions(ChooseByNameContributor.CLASS_EP_NAME, listOf(provider) + additionalContributors, disposable)
            // GotoClassModel2 captures contributors in its constructor; build it after the real EP is installed.
            lateinit var popup: ChooseByNamePopup
            edt { actual.model = GotoClassModel2(project); popup = ChooseByNamePopup.createPopup(project, actual.model, null as com.intellij.psi.PsiElement?, "") }
            actual.popup = popup
            block(actual)
        } finally {
            edt {
                fixture?.let { Disposer.dispose(it.popup) }
                FileEditorManager.getInstance(project).openFiles.filter { it.path.startsWith(base.toString()) }.forEach { FileEditorManager.getInstance(project).closeFile(it) }
                project.putUserData(ChooseByNamePopup.CURRENT_SEARCH_PATTERN, previous)
                if (!Disposer.isDisposed(index)) Disposer.dispose(index)
                ModuleRootModificationUtil.updateModel(module) { rootModel ->
                    rootModel.contentEntries.firstOrNull { it.url == com.intellij.openapi.vfs.VfsUtilCore.pathToUrl(root.toString()) }?.let(rootModel::removeContentEntry)
                }
            }
            Disposer.dispose(disposable)
            base.toFile().deleteRecursively(); LocalFileSystem.getInstance().refreshAndFindFileByNioFile(base)
        }
    }

    private class CatalogPeer(val paths: Map<String, Path>) : GoTypeCatalogSource {
        data class Request(val operation: String, val includeExternal: Boolean, val overlays: List<JsonObject>)
        val requests = CopyOnWriteArrayList<Request>(); val canceled = CopyOnWriteArrayList<String>(); val problems = CopyOnWriteArrayList<String>()
        val started = CountDownLatch(1)
        val preparing = CountDownLatch(1); val released = CountDownLatch(1)
        @Volatile var preparation: CountDownLatch? = null
        @Volatile var held: CompletableFuture<JsonObject>? = null
        @Volatile var captured: JsonObject? = null
        @Volatile var badUtf16 = false
        @Volatile var dependencyFiles = emptySet<String>()
        override fun prepare(input: GoTypeSearchInput) {
            preparing.countDown()
            preparation?.let { check(it.await(15, TimeUnit.SECONDS)) { "Owned alias preparation was not released" } }
            input.prepareAliases()
        }
        override fun load(input: GoTypeSearchInput, includeExternal: Boolean, operationId: String): CompletableFuture<JsonObject> {
            check(!com.intellij.openapi.application.ApplicationManager.getApplication().isDispatchThread)
            val overlays = input.requestWire().getAsJsonArray("overlays").map { it.asJsonObject }
            requests += Request(operationId, includeExternal, overlays)
            val guards = JsonArray(); val types = JsonArray(); val dirs = mutableSetOf<Path>()
            paths.filterKeys { includeExternal || it != "sdk.go" && it !in dependencyFiles }.forEach { (name, path) ->
                val uri = GoDocumentSync.fileUri(path)
                val disk = Files.readAllBytes(path)
                val overlay = overlays.firstOrNull { it.get("uri").asString == uri }
                val text = overlay?.get("text")?.asString ?: GoTypeCatalogCodec.text(disk)
                guards.add(JsonObject().apply {
                    addProperty("uri", uri); addProperty("exists", true); addProperty("diskSha256", GoTypeCatalogCodec.hash(disk))
                    addProperty("textSha256", GoTypeCatalogCodec.hash(text.toByteArray()))
                    overlay?.get("documentStamp")?.let { add("documentStamp", it) }
                })
                dirs.add(path.parent)
                Regex("\\btype\\s+([\\p{L}\\p{N}_]+)").findAll(text).forEach { match ->
                    val type = match.groupValues[1]; val start = match.groups[1]!!.range.first
                    val end = text.indexOf('\n', start).takeIf { it >= 0 } ?: text.length
                    fun pos(offset: Int) = JsonObject().apply {
                        addProperty("line", text.take(offset).count { it == '\n' }); addProperty("character", text.take(offset).substringAfterLast('\n').length)
                    }
                    fun range(a: Int, b: Int) = JsonObject().apply { add("start", pos(a)); add("end", pos(b)) }
                    types.add(JsonObject().apply {
                        addProperty("name", type); addProperty("uri", uri); addProperty("packagePath", if (name == "sdk.go") "stdlib/sdk" else "example.org/probe")
                        addProperty("packageName", if (name == "sdk.go") "sdk" else "probe")
                        addProperty("kind", if (type == "Callback") "function" else if (type == "BoxAlias") "alias" else "struct")
                        addProperty("origin", if (name == "sdk.go") "sdk" else if (name in dependencyFiles) "dependency" else "project")
                        add("range", range(start, end)); add("selectionRange", if (badUtf16 && type == "模型") range(text.indexOf("😀") + 1, text.indexOf("😀") + 2) else range(start, start + type.length))
                    })
                }
            }
            val response = JsonObject().apply {
                addProperty("catalogId", operationId); addProperty("configurationDigest", "owned-test-configuration")
                add("types", types); add("sourceGuards", guards)
                add("directories", JsonArray().apply { dirs.forEach { dir -> add(JsonObject().apply {
                    addProperty("uri", GoDocumentSync.fileUri(dir)); add("entries", JsonArray().apply { GoTypeCatalogCodec.directoryEntries(dir).forEach(::add) })
                }) } })
                add("coverage", JsonObject().apply {
                    addProperty("complete", true)
                    add("modules", JsonArray().apply { paths.filterKeys { it != "sdk.go" && it !in dependencyFiles }.values.map { it.parent }.distinct().forEach { add(it.toString()) } })
                }); add("issues", JsonArray())
            }
            captured = response; started.countDown()
            return held?.also { held = null } ?: CompletableFuture.completedFuture(response)
        }
        override fun cancel(operationId: String) { canceled += operationId }
        override fun release(operationId: String) { released.countDown() }
    }
    private class UncancellableReply : CompletableFuture<JsonObject>() { override fun cancel(mayInterruptIfRunning: Boolean) = false }
    private fun edt(action: () -> Unit) = EdtTestUtil.runInEdtAndWait<RuntimeException> { WriteIntentReadAction.run(Runnable(action)) }
}
