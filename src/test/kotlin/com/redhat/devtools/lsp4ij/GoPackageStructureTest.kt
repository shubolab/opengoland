package com.redhat.devtools.lsp4ij

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonArray
import com.intellij.ide.impl.OpenProjectTask
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.newStructureView.StructureViewComponent
import com.intellij.ide.structureView.newStructureView.TreeActionsOwner
import com.intellij.ide.structureView.newStructureView.TreeModelWrapper
import com.intellij.ide.util.treeView.smartTree.TreeElementWrapper
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ex.ProjectManagerEx
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.EdtTestUtil
import com.intellij.testFramework.LightPlatformTestCase
import com.redhat.devtools.lsp4ij.client.features.LSPClientFeatures
import com.redhat.devtools.lsp4ij.features.documentSymbol.DocumentSymbolData
import com.redhat.devtools.lsp4ij.features.documentSymbol.LSPDocumentSymbolStructureViewModel
import com.redhat.devtools.lsp4ij.server.definition.LanguageServerDefinition
import dev.openidea.goide.lsp.GoLanguageServerFactory
import dev.openidea.goide.lsp.GoPackageStructureRoot
import dev.openidea.goide.lsp.GoPackageSnapshot
import dev.openidea.goide.lsp.GoStructureControls
import dev.openidea.goide.lsp.GoStructureViewModel
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.LanguageServer
import org.eclipse.lsp4j.services.TextDocumentService
import org.eclipse.lsp4j.services.WorkspaceService
import java.lang.reflect.Proxy
import java.lang.management.ManagementFactory
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.LockSupport

/** Native SDK model/navigation with an owned in-memory gopls package-command peer. */
class GoPackageStructureTest : LightPlatformTestCase() {
    override fun runInDispatchThread() = false

    fun testAnUnsupportedPackageCommandRetainsDocumentSymbolsWithoutAnExecuteRequest() = fixture(supported = false) { f ->
        val rows = f.elements()
        assertTrue(rows.any { it.presentation.presentableText == "Record" })
        assertTrue(rows.any { it.presentation.presentableText == "AfterEmoji: int" })
        assertFalse("Other files must not be guessed when package_symbols is unsupported",
            rows.any { it.presentation.presentableText == "privateHelper()" })
        assertEquals(0, f.wrapper.packageRequests.size)
        assertFalse(f.wrapper.documentRequests.isEmpty())
    }

    fun testAnUnsupportedGenericClientRetainsItsExistingDocumentFeature() = fixture(supported = false, goFeature = false) { f ->
        assertEquals(setOf("Record", "AfterEmoji"), f.elements().map { it.presentation.presentableText }.toSet())
        assertEquals(0, f.wrapper.packageRequests.size)
        assertEquals(listOf("model.go"), f.wrapper.documentRequests.toList())
    }

    fun testUnsupportedAndNullPackageResultsRemainCachedDuringRepeatedEdtModelReads() {
        for (supported in listOf(false, true)) fixture(supported = supported) { f ->
            if (supported) f.wrapper.nextReply = CompletableFuture<Any>().also { it.complete(null) }
            val expected = f.elements().map { it.presentation.presentableText }
            assertFalse(expected.isEmpty())
            repeat(20) {
                edt { assertEquals("A completed fallback must not schedule another empty/rebuild loop", expected, f.model.root.children.map { it.presentation.presentableText }) }
            }
            assertEquals(if (supported) 1 else 0, f.wrapper.packageRequests.size)
            assertEquals(listOf("model.go"), f.wrapper.documentRequests.toList())
        }
    }

    fun testAClosedUnsavedPackageBufferIsOpenedAndSynchronizedBeforeTheCommand() = fixture { f ->
        val target = f.files.getValue("methods.go").virtualFile
        assertFalse(FileEditorManager.getInstance(project).openFiles.contains(target))
        assertFalse(f.wrapper.openedDocuments.keys.any { Path.of(it) == Path.of(target.path) })
        f.wrapper.nativeSync = true
        val changed = "// closed unsaved 前😀\n" + SOURCES.getValue("methods.go")
        f.write("methods.go", changed)
        assertTrue(FileDocumentManager.getInstance().unsavedDocuments.contains(f.documents.getValue("methods.go")))
        val snapshot = requireNotNull(f.query().get(10, TimeUnit.SECONDS))
        assertEquals("The real synchronizer must send the dirty closed buffer before executeCommand",
            changed, f.wrapper.openedAtCommand.single().getValue("methods.go"))
        assertEquals(2, snapshot.allElements.single { it.data.documentSymbol.name == "Lookup" }.data.documentSymbol.selectionRange.start.line)
        assertFalse(FileEditorManager.getInstance(project).openFiles.contains(target))
        f.assertDiskUnchanged()
    }

    fun testAStableCommandExceptionCachesItsFileFallbackWithoutAnEdtRetryLoop() = fixture { f ->
        f.wrapper.nextReply = CompletableFuture<Any>().also { it.completeExceptionally(IllegalStateException("owned package command failure")) }
        val expected = f.elements().map { it.presentation.presentableText }
        assertEquals(setOf("Record", "AfterEmoji: int"), expected.toSet())
        val failedGeneration = f.query()
        assertNull(failedGeneration.get(10, TimeUnit.SECONDS))
        repeat(20) {
            edt { assertEquals(expected, f.model.root.children.map { it.presentation.presentableText }) }
            assertSame(failedGeneration, f.query())
        }
        assertEquals(1, f.wrapper.packageRequests.size)
        assertEquals(listOf("model.go"), f.wrapper.documentRequests.toList())
    }

    fun testDisposeLinearizesBeforeALoadBlockedOnTheExactLifecycleMonitor() = fixture { f ->
        val root = f.packageRoot
        val lock = GoPackageStructureRoot::class.java.getDeclaredField("lifecycle").apply { isAccessible = true }.get(root)
        val result = AtomicReference<Array<StructureViewTreeElement>>()
        val failure = AtomicReference<Throwable>()
        val entered = CountDownLatch(1)
        val worker = Thread({
            entered.countDown()
            try { result.set(root.children) } catch (error: Throwable) { failure.set(error) }
        }, "owned package load before dispose").apply { isDaemon = true }
        try {
            synchronized(lock) {
                worker.start()
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                awaitBlockedOn(worker, lock)
                // The same public disposal is reentrant on lifecycle. No reverse
                // support→lifecycle lock is introduced by this scheduling test.
                root.dispose()
            }
            worker.join(10_000)
            assertFalse(worker.isAlive)
            failure.get()?.let { throw AssertionError("Disposed load failed", it) }
            assertTrue(requireNotNull(result.get()).isEmpty())
            assertEquals("A load released after disposal must not start its command", 0, f.wrapper.packageRequests.size)
            assertTrue(f.wrapper.documentRequests.isEmpty())
        } finally {
            if (worker.isAlive) { worker.interrupt(); worker.join(10_000) }
            assertFalse("No owned scheduling worker survives", worker.isAlive)
        }
    }

    fun testOwnedEdtFallbackWaitStopsOnDisposeAndInvalidationWithoutCancellingTheSharedDocumentFuture() {
        for (dispose in listOf(true, false)) fixture(supported = false) { f ->
            val sharedReply = CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>>()
            f.wrapper.nextDocumentReply = sharedReply
            val fallbackModel = LSPDocumentSymbolStructureViewModel(f.files.getValue("model.go"), f.editor)
            val entered = CountDownLatch(1)
            val exited = CountDownLatch(1)
            val indicator = AtomicReference<ProgressIndicator?>()
            val fallback = object : StructureViewTreeElement by fallbackModel.root {
                override fun getChildren(): Array<TreeElement> {
                    indicator.set(ProgressManager.getInstance().progressIndicator)
                    entered.countDown()
                    try { return fallbackModel.root.children } finally { exited.countDown() }
                }
            }
            val owned = GoPackageStructureRoot(f.files.getValue("model.go"), fallback) {}
            try {
                edt { assertTrue("The EDT schedules rather than waiting for the shared reply", owned.children.isEmpty()) }
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                f.awaitDocumentRequests(1)
                val shared = read { requireNotNull(LSPFileSupport.getSupport(f.files.getValue("model.go")).documentSymbolSupport.validLSPFuture) }
                assertFalse(shared.isDone)
                val progress = requireNotNull(indicator.get())
                assertFalse(progress.isCanceled)
                if (dispose) edt { Disposer.dispose(owned) }
                else f.write("methods.go", "// dependency changed 😀\n" + SOURCES.getValue("methods.go"))
                assertTrue("Only the owned waiting task must stop within the bound", exited.await(10, TimeUnit.SECONDS))
                assertTrue(progress.isCanceled)
                assertFalse("The underlying remote document-symbol request is shared", sharedReply.isCancelled)
                assertFalse("The upstream shared document future must remain usable", shared.isCancelled)
                assertFalse(shared.isDone)
                sharedReply.complete(symbols("model.go", SOURCES.getValue("model.go")).map { Either.forRight<SymbolInformation, DocumentSymbol>(it) })
                assertEquals(setOf("Record", "AfterEmoji"), shared.get(10, TimeUnit.SECONDS).map { it.documentSymbol.name }.toSet())
                assertEquals(setOf("Record", "AfterEmoji: int"), f.elements().map { it.presentation.presentableText }.toSet())
                assertEquals("Another model still consumes the same document result", 1, f.wrapper.documentRequests.size)
            } finally {
                sharedReply.complete(emptyList())
                edt { Disposer.dispose(owned); Disposer.dispose(fallbackModel) }
            }
        }
    }

    fun testAHalfSurrogatePositionRejectsTheEntirePackageGraphAndCachesFileFallback() = malformedPackage("surrogate")

    fun testAnOutOfLineColumnRejectsTheEntirePackageGraphInsteadOfClampingIt() = malformedPackage("column")

    private fun malformedPackage(mode: String) = fixture { f ->
        val json = (f.wrapper.packageJson() as JsonObject).deepCopy()
        val record = json.getAsJsonArray("Symbols").map { it.asJsonObject }.single { it.get("name").asString == "Record" }
        val method = record.getAsJsonArray("children").map { it.asJsonObject }.single { it.get("name").asString == "Lookup" }
        val source = SOURCES.getValue("methods.go")
        method.add("range", Gson().toJsonTree(Range(Position(0, 0), position(source, source.length))))
        val invalid = if (mode == "surrogate") {
            val offset = source.indexOf("😀") + 1
            Range(position(source, offset), position(source, offset + 2))
        } else Range(Position(1, 10_000), Position(2, 2))
        method.add("selectionRange", Gson().toJsonTree(invalid))
        f.wrapper.nextReply = CompletableFuture.completedFuture(json)
        val rows = f.elements()
        assertEquals("No valid prefix of a malformed package graph may leak into the file fallback ($mode)",
            setOf("Record", "AfterEmoji: int"), rows.map { it.presentation.presentableText }.toSet())
        assertNull(f.find("Lookup", rows))
        val generation = f.query()
        assertNull(generation.get(10, TimeUnit.SECONDS))
        repeat(10) { edt { assertEquals(rows.size, f.model.root.children.size) }; assertSame(generation, f.query()) }
        assertEquals(1, f.wrapper.packageRequests.size)
        assertEquals(listOf("model.go"), f.wrapper.documentRequests.toList())
    }

    fun testAuthoritativePackageGraphDecodesOmittedZeroForEachChildAndAvoidsDuplicateCurrentSymbols() = fixture { f ->
        val roots = f.elements()
        assertEquals("One package graph contains current and other files exactly once", 4, roots.size)
        val record = requireNotNull(f.find("Record", roots))
        val method = requireNotNull(f.find("Lookup", roots))
        val field = requireNotNull(f.find("Name", roots))
        assertEquals(f.files.getValue("model.go"), (record.value as DocumentSymbolData).containingFile)
        assertEquals("An omitted child file key means Files[0], not its parent's index1",
            f.files.getValue("methods.go"), (method.value as DocumentSymbolData).containingFile)
        assertEquals(f.files.getValue("model.go"), (field.value as DocumentSymbolData).containingFile)
        assertEquals("Lookup(value string) string", method.presentation.presentableText)
        assertEquals("methods.go", method.presentation.locationString)
        assertEquals(1, f.wrapper.packageRequests.size)
        assertTrue("Package mode must consume the authoritative graph rather than merge a second document result", f.wrapper.documentRequests.isEmpty())
        assertEquals(COMMAND, f.wrapper.packageRequests.single().command)
        assertEquals(f.files.getValue("model.go").virtualFile.url,
            Gson().toJsonTree(f.wrapper.packageRequests.single().arguments.single()).asJsonObject.get("URI").asString)
    }

    fun testNonexistentCgoCacheSlotsAreSkippedWithoutRenumberingSourceAndChildUris() = fixture { f ->
        val missing = f.root.parent.resolve("never-created-cgo-${f.root.fileName}").resolve("compiler 😀-d")
        assertFalse(Files.exists(missing))
        val raw = (f.wrapper.packageJson() as JsonObject).deepCopy()
        val originalFiles = raw.getAsJsonArray("Files").deepCopy()
        raw.add("Files", JsonArray().apply { add(missing.toUri().toASCIIString()); originalFiles.forEach(::add) })
        fun shift(symbol: JsonObject) {
            symbol.addProperty("file", (symbol.get("file")?.asInt ?: 0) + 1)
            symbol.getAsJsonArray("children")?.forEach { shift(it.asJsonObject) }
        }
        raw.getAsJsonArray("Symbols").forEach { shift(it.asJsonObject) }
        val internal = JsonObject().apply {
            addProperty("name", "_CgoCompilerOnly")
            addProperty("kind", SymbolKind.Class.value)
            // This is the raw omitted index0. It intentionally has no source
            // range: excluded compiler entries must be skipped before decoding
            // or resolving their nonexistent URI.
        }
        raw.getAsJsonArray("Symbols").add(internal)
        val record = raw.getAsJsonArray("Symbols").map { it.asJsonObject }.single { it.get("name").asString == "Record" }
        record.getAsJsonArray("children").add(internal.deepCopy())
        assertEquals(2, record.get("file").asInt)
        assertEquals(1, record.getAsJsonArray("children").map { it.asJsonObject }.single { it.get("name").asString == "Lookup" }.get("file").asInt)
        f.wrapper.nextReply = CompletableFuture.completedFuture(raw)
        val roots = f.elements()
        assertEquals(4, roots.size)
        assertNotNull(f.find("Record", roots))
        assertNull(f.find("_CgoCompilerOnly", roots))
        val method = requireNotNull(f.find("Lookup", roots))
        assertEquals(f.files.getValue("methods.go"), (method.value as DocumentSymbolData).containingFile)
        val snapshot = requireNotNull(f.query().get(10, TimeUnit.SECONDS))
        assertEquals(f.files.values.toSet(), snapshot.files.toSet())
        assertFalse(snapshot.allElements.any { it.data.documentSymbol.name.startsWith("_Cgo") })
        assertEquals("A valid source graph still needs no supplementary document request", 0, f.wrapper.documentRequests.size)
        assertEquals(1, f.wrapper.packageRequests.size)
        f.navigate(method, "methods.go", SOURCES.getValue("methods.go").indexOf("Lookup"))
        assertFalse("Excluded cache entries are not needed or created by decoding", Files.exists(missing))
        f.assertDiskUnchanged()
    }

    fun testOnlyCgoAuxiliaryFilesWithoutTheCurrentSourceUseAStableFileFallback() = fixture { f ->
        val missing = f.root.parent.resolve("never-created-cgo-only-${f.root.fileName}").resolve("cache-d")
        assertFalse(Files.exists(missing))
        val raw = JsonObject().apply {
            addProperty("PackageName", "fixture")
            add("Files", JsonArray().apply { add(missing.toUri().toASCIIString()) })
            add("Symbols", JsonArray().apply { add(JsonObject().apply { addProperty("name", "_CtypeOnly"); addProperty("kind", SymbolKind.Class.value) }) })
        }
        f.wrapper.nextReply = CompletableFuture.completedFuture(raw)
        val expected = f.elements().map { it.presentation.presentableText }
        assertEquals(setOf("Record", "AfterEmoji: int"), expected.toSet())
        val generation = f.query()
        assertNull(generation.get(10, TimeUnit.SECONDS))
        repeat(20) {
            edt { assertEquals(expected, f.model.root.children.map { it.presentation.presentableText }) }
            assertSame(generation, f.query())
        }
        assertEquals(1, f.wrapper.packageRequests.size)
        assertEquals(listOf("model.go"), f.wrapper.documentRequests.toList())
        assertFalse(Files.exists(missing))
    }

    fun testAMissingReceiverInTheCurrentFileRemainsVisibleAndNavigatesThroughTheNativeWrapper() {
        val source = SOURCES.getValue("model.go") + "var _ = \"前😀\"; func (r Missing) Lost() {}\n"
        fixture(sourceFiles = SOURCES + ("model.go" to source)) { f ->
            f.wrapper.documentOverrides = mapOf("model.go" to (symbols("model.go", source) + lost(source)))
            val rows = f.elements()
            val method = requireNotNull(f.find("(Missing).Lost", rows))
            assertTrue("An unresolved receiver must remain a flat node, not be attached to an unrelated Record", rows.contains(method))
            assertEquals(listOf("model.go"), f.wrapper.documentRequests.toList())
            assertEquals(1, f.wrapper.packageRequests.size)
            f.navigate(method, "model.go", source.indexOf("Lost"))
            f.elements()
            assertEquals("Stable reads must reuse the supplemented graph", listOf("model.go"), f.wrapper.documentRequests.toList())
            f.assertDiskUnchanged()
        }
    }

    fun testAMissingReceiverOnlyForeignFileIsLoadedInTheBackgroundAndKeepsItsOwnNavigationUri() {
        val source = "package fixture\nvar _ = \"前😀\"; func (r Missing) Lost() {}\n"
        fixture(sourceFiles = SOURCES + ("orphan.go" to source), preload = SOURCES.keys) { f ->
            val foreign = f.files.getValue("orphan.go")
            assertNull("The only-orphan file intentionally has no editor or cached document",
                read { PsiDocumentManager.getInstance(project).getCachedDocument(foreign) })
            assertFalse(FileEditorManager.getInstance(project).openFiles.contains(foreign.virtualFile))
            val raw = (f.wrapper.packageJson() as JsonObject).deepCopy()
            raw.getAsJsonArray("Files").add(foreign.virtualFile.url)
            // A real package_symbols response lists the file but omits its only
            // receiver method when its receiver declaration is missing.
            f.wrapper.nextReply = CompletableFuture.completedFuture(raw)
            f.wrapper.documentOverrides = mapOf("orphan.go" to listOf(lost(source)))
            val method = requireNotNull(f.find("(Missing).Lost"))
            assertEquals(foreign, (method.value as DocumentSymbolData).containingFile)
            assertEquals(listOf("orphan.go"), f.wrapper.documentRequests.toList())
            assertEquals(1, f.wrapper.packageRequests.size)
            f.navigate(method, "orphan.go", source.indexOf("Lost"))
            f.assertDiskUnchanged()
        }
    }

    fun testPartialDocumentSupplementRetainsKnownParentsAndCrossFileChildrenWithoutDuplicatingThem() {
        val source = SOURCES.getValue("model.go").replace("Name string", "Name string; Extra int") + "func (r Missing) Lost() {}\n"
        fixture(sourceFiles = SOURCES + ("model.go" to source)) { f ->
            val existing = symbols("model.go", source)
            val extra = source.indexOf("Extra int")
            val field = DocumentSymbol("Extra", SymbolKind.Field,
                Range(position(source, extra), position(source, extra + "Extra int".length)),
                Range(position(source, extra), position(source, extra + "Extra".length))).apply { detail = "int" }
            existing.single { it.name == "Record" }.children = existing.single { it.name == "Record" }.children + field
            f.wrapper.documentOverrides = mapOf("model.go" to (existing + lost(source)))
            val rows = f.elements()
            assertEquals(1, rows.count { (it.value as DocumentSymbolData).documentSymbol.name == "Record" })
            val record = requireNotNull(f.find("Record", rows))
            val members = read { record.children.map { it as StructureViewTreeElement } }
            assertEquals(setOf("Name", "Extra", "Lookup"), members.map { (it.value as DocumentSymbolData).documentSymbol.name }.toSet())
            assertEquals("Known parent/child keys must merge once", 3, members.size)
            val lookup = members.single { (it.value as DocumentSymbolData).documentSymbol.name == "Lookup" }
            assertEquals("The existing method keeps its foreign URI during recursive immutable merge",
                f.files.getValue("methods.go"), (lookup.value as DocumentSymbolData).containingFile)
            assertTrue(rows.contains(requireNotNull(f.find("(Missing).Lost", rows))))
            assertEquals(listOf("model.go"), f.wrapper.documentRequests.toList())
            assertEquals(1, f.wrapper.packageRequests.size)
            f.assertDiskUnchanged()
        }
    }

    fun testCrossFileMethodUsesTheActualSdkNavigationWrapperAndUtf16Selection() = fixture { f ->
        val method = requireNotNull(f.find("Lookup"))
        val expected = SOURCES.getValue("methods.go").indexOf("Lookup")
        assertEquals(expected, read { (method.value as DocumentSymbolData).textOffset })
        edt { StructureViewComponent.createWrapper(project, method, f.model).navigate(true) }
        edt {
            val selected = requireNotNull(FileEditorManager.getInstance(project).selectedTextEditor)
            assertSame(f.documents.getValue("methods.go"), selected.document)
            assertEquals("The emoji before the method name must not shift native navigation", expected, selected.caretModel.offset)
        }
        f.assertDiskUnchanged()
    }

    fun testPackageOriginAndPrivateFiltersComposeThroughTheNativeTreeActions() = fixture { f ->
        val current = f.visible(showPackage = false, showPrivate = true)
        assertEquals(setOf("Record", "Name", "AfterEmoji"), current.toSet())
        val all = f.visible(showPackage = true, showPrivate = true)
        assertEquals(setOf("Record", "Name", "Lookup", "AfterEmoji", "Plain", "privateHelper"), all.toSet())
        val publicOnly = f.visible(showPackage = true, showPrivate = false)
        assertFalse(publicOnly.contains("privateHelper"))
        assertTrue(publicOnly.containsAll(listOf("Record", "Lookup", "Plain")))
        val currentPublic = f.visible(showPackage = false, showPrivate = false)
        assertEquals(current.toSet(), currentPublic.toSet())
    }

    fun testCurrentFileFilteringRetainsTheForeignTypeAncestorOfACurrentMethod() = fixture(origin = "methods.go") { f ->
        assertEquals(setOf("Record", "Lookup", "Plain"), f.visible(showPackage = false, showPrivate = true).toSet())
        assertEquals("The owning type remains for navigation, without importing unrelated foreign fields",
            listOf("Lookup"), f.visibleChildren("Record", showPackage = false))
    }

    fun testUnchangedFutureIsReusedButATargetOnlyDirtyPrefixIssuesAFreshCommand() = fixture { f ->
        val first = f.query()
        val initial = requireNotNull(first.get(10, TimeUnit.SECONDS))
        assertSame(first, f.query())
        assertEquals(1, f.wrapper.packageRequests.size)
        val oldRange = initial.allElements.single { it.data.documentSymbol.name == "Lookup" }.data.documentSymbol.selectionRange
        val prefix = "// 未保存 😀\n"
        f.write("methods.go", prefix + SOURCES.getValue("methods.go"))
        val second = f.query()
        val fresh = requireNotNull(second.get(10, TimeUnit.SECONDS))
        assertNotSame(first, second)
        assertEquals(2, f.wrapper.packageRequests.size)
        val newRange = fresh.allElements.single { it.data.documentSymbol.name == "Lookup" }.data.documentSymbol.selectionRange
        assertEquals(oldRange.start.line + 1, newRange.start.line)
        assertSame(second, f.query())
        assertEquals("Stable reuse remains enabled after refresh", 2, f.wrapper.packageRequests.size)
        f.assertDiskUnchanged()
    }

    fun testALateReplyCannotPublishAfterTheOtherDocumentChanges() = fixture { f ->
        val held = UncancellableReply()
        val oldResult = f.wrapper.packageJson()
        f.wrapper.nextReply = held
        val original = f.query()
        f.awaitRequests(1)
        f.write("methods.go", "// moved 前😀\n" + SOURCES.getValue("methods.go"))
        assertTrue("The peer can deliver the original late payload", held.complete(oldResult))
        rejected(original)
        val fresh = f.query()
        val snapshot = requireNotNull(fresh.get(10, TimeUnit.SECONDS))
        assertEquals(2, f.wrapper.packageRequests.size)
        assertEquals(2, snapshot.allElements.single { it.data.documentSymbol.name == "Lookup" }.data.documentSymbol.selectionRange.start.line)
        assertSame(fresh, f.query())
    }

    fun testExplicitCancellationCannotInstallALateReplyOrPoisonTheNextGeneration() = fixture { f ->
        val held = UncancellableReply()
        val payload = f.wrapper.packageJson()
        f.wrapper.nextReply = held
        val first = f.query()
        f.awaitRequests(1)
        f.packageRoot.support.cancel()
        held.complete(payload)
        rejected(first)
        val fresh = f.query()
        assertNotSame(first, fresh)
        assertNotNull(fresh.get(10, TimeUnit.SECONDS))
        assertEquals(2, f.wrapper.packageRequests.size)
    }

    fun testDisposedModelDoesNotPublishAHeldReplyOrStartAnotherPackageRequest() = fixture { f ->
        val held = UncancellableReply()
        f.wrapper.nextReply = held
        val first = f.query()
        f.awaitRequests(1)
        edt { Disposer.dispose(f.model) }
        held.complete(f.wrapper.packageJson())
        rejected(first)
        assertTrue(read { f.packageRoot.children.isEmpty() })
        assertEquals(1, f.wrapper.packageRequests.size)
    }

    fun testDisposingAnOwnedRealProjectCancelsItsHeldPackageGeneration() {
        val location = Files.createTempDirectory("open-goland-owned-package-project-").toRealPath()
        var ownedProject: Project? = null
        try {
            ownedProject = requireNotNull(ProjectManagerEx.getInstanceEx().newProject(location, OpenProjectTask {
                isNewProject = true
                useDefaultProjectAsTemplate = false
                projectName = "owned package lifecycle"
                preloadServices = false
                createModule = false
            }))
            val owner = ownedProject
            fixture(ownerProject = owner) { f ->
                // A native Structure host owns the model under its project.
                // This independent project lets the test exercise that real
                // disposal tree without disposing LightPlatform's shared one.
                Disposer.register(owner, f.model)
                val held = UncancellableReply()
                val payload = f.wrapper.packageJson()
                f.wrapper.nextReply = held
                val pending = f.query()
                f.awaitRequests(1)
                edt { WriteAction.run<RuntimeException> { Disposer.dispose(owner) } }
                assertTrue(owner.isDisposed)
                held.complete(payload)
                rejected(pending)
                assertTrue(read { f.packageRoot.children.isEmpty() })
                assertEquals(1, f.wrapper.packageRequests.size)
            }
        } finally {
            ownedProject?.takeUnless { it.isDisposed }?.let { owner -> edt { WriteAction.run<RuntimeException> { Disposer.dispose(owner) } } }
            location.toFile().deleteRecursively()
        }
    }

    fun testCachedCurrentEditorSelectionUsesTheQueryFileAndLatestGraph() = fixture { f ->
        f.elements()
        val offset = SOURCES.getValue("model.go").indexOf("AfterEmoji") + 2
        edt { f.editor.caretModel.moveToOffset(offset) }
        val selected = read { f.model.currentEditorElement as? DocumentSymbolData }
        assertNotNull(selected)
        assertEquals("AfterEmoji", selected!!.documentSymbol.name)
        assertEquals(f.files.getValue("model.go"), selected.containingFile)
        assertEquals(1, f.wrapper.packageRequests.size)
    }

    fun testAConfigurationEpochWithAPendingGenerationCannotReturnThePreviousCurrentElement() = fixture { f ->
        f.elements()
        val oldMethod = requireNotNull(f.find("Lookup"))
        assertTrue(oldMethod.canNavigate())
        val offset = SOURCES.getValue("model.go").indexOf("AfterEmoji") + 2
        val leaf = read { requireNotNull(f.files.getValue("model.go").findElementAt(offset)) }
        val previous = read { requireNotNull(f.packageRoot.currentElement(leaf)) }
        val stamp = f.documents.getValue("model.go").modificationStamp
        val held = UncancellableReply()
        f.wrapper.nextReply = held
        f.wrapper.incrementModificationCount()
        assertFalse("An existing node must validate a configuration epoch before F2 is even requested", oldMethod.canNavigate())
        val next = f.query()
        f.awaitRequests(2)
        assertFalse(next.isDone)
        assertEquals(stamp, f.documents.getValue("model.go").modificationStamp)
        assertNull("A pending F2 must not return the old completed F1 graph", read { f.packageRoot.currentElement(leaf) })
        held.complete(f.wrapper.packageJson())
        next.get(10, TimeUnit.SECONDS)
        val current = read { requireNotNull(f.packageRoot.currentElement(leaf)) }
        assertNotSame(previous, current)
        assertEquals("AfterEmoji", (current as DocumentSymbolData).documentSymbol.name)
    }

    fun testIdenticalInitDeclarationsInDifferentFilesKeepDistinctNativeTreeKeys() = fixture { f ->
        val identical = "package fixture\nfunc init() {}\n"
        f.write("methods.go", identical)
        f.write("model.go", identical)
        f.wrapper.identicalInit = true
        val roots = f.elements()
        assertEquals(2, roots.size)
        val values = roots.map { it.value as DocumentSymbolData }
        assertEquals("The protocol symbols intentionally have equal names/kinds/ranges", values[0].documentSymbol, values[1].documentSymbol)
        assertFalse("A native key must include each declaration's file identity", values[0] == values[1])
        assertEquals(2, values.toHashSet().size)
        assertEquals(setOf(f.files.getValue("methods.go"), f.files.getValue("model.go")), values.map { it.containingFile }.toSet())
        assertEquals("The SDK tree must keep both rows instead of collapsing equal document DTOs",
            listOf("init", "init"), f.visible(showPackage = true, showPrivate = true))
    }

    fun testAnOldCrossFileNodeCannotNavigateAfterItsTargetDocumentChanges() = fixture { f ->
        val old = requireNotNull(f.find("Lookup"))
        assertTrue(old.canNavigate())
        edt { FileEditorManager.getInstance(project).openTextEditor(OpenFileDescriptor(project, f.files.getValue("model.go").virtualFile, 3), true) }
        f.write("methods.go", "// moved 😀\n" + SOURCES.getValue("methods.go"))
        assertFalse("A node must not turn a stale absolute range into a navigation", old.canNavigate())
        edt { StructureViewComponent.createWrapper(project, old, f.model).navigate(true) }
        edt {
            val selected = requireNotNull(FileEditorManager.getInstance(project).selectedTextEditor)
            assertSame(f.documents.getValue("model.go"), selected.document)
            assertEquals(3, selected.caretModel.offset)
        }
    }

    private inner class Fixture(
        val root: Path,
        val files: Map<String, PsiFile>,
        val documents: Map<String, Document>,
        val editor: Editor,
        val wrapper: OwnedWrapper,
        val origin: String,
        val ownerProject: Project,
        val initialSources: Map<String, String>
    ) {
        val model = GoStructureViewModel(files.getValue(origin), editor)
        val packageRoot get() = model.root as GoPackageStructureRoot
        fun query(): CompletableFuture<GoPackageSnapshot?> = read { requireNotNull(packageRoot.support.getFeatureData(Unit)) }
        fun elements() = read { model.root.children.map { it as StructureViewTreeElement } }
        fun awaitRequests(count: Int) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (wrapper.packageRequests.size < count && System.nanoTime() < deadline) Thread.sleep(5)
            assertEquals(count, wrapper.packageRequests.size)
        }
        fun awaitDocumentRequests(count: Int) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (wrapper.documentRequests.size < count && System.nanoTime() < deadline) Thread.sleep(5)
            assertEquals(count, wrapper.documentRequests.size)
        }
        private fun tree(showPackage: Boolean, showPrivate: Boolean): Pair<TreeModelWrapper, TreeElementWrapper> {
            // The SDK TreeModelWrapper owns/disposes its model. Give each host
            // its own real model instead of disposing the fixture's live one.
            val actual = GoStructureViewModel(files.getValue(origin), editor)
            val actualRoot = actual.root as GoPackageStructureRoot
            val active = mutableSetOf<String>()
            // Reverted filters are enabled internally when the visible Show toggle is off.
            if (!showPackage) active += actualRoot.filter.name
            if (!showPrivate) active += GoStructureControls.SHOW_PRIVATE_ID
            val owner = object : TreeActionsOwner {
                override fun isActionActive(name: String) = name in active
                override fun setActionActive(name: String, state: Boolean) { if (state) active += name else active -= name }
            }
            val wrapped = TreeModelWrapper(actual, owner)
            return wrapped to StructureViewComponent.createWrapper(ownerProject, actual.root, wrapped)
        }
        fun visible(showPackage: Boolean, showPrivate: Boolean): List<String> {
            val (wrapped, root) = tree(showPackage, showPrivate)
            fun visit(node: TreeElementWrapper): List<String> = node.children.filterIsInstance<TreeElementWrapper>().flatMap { child ->
                listOfNotNull((child.value as? StructureViewTreeElement)?.value.let { (it as? DocumentSymbolData)?.documentSymbol?.name }) + visit(child)
            }
            return try { read { visit(root) } } finally { edt { Disposer.dispose(wrapped) } }
        }
        fun visibleChildren(name: String, showPackage: Boolean): List<String> {
            val (wrapped, root) = tree(showPackage, true)
            return try { read {
                val parent = root.children.filterIsInstance<TreeElementWrapper>().single {
                    ((it.value as? StructureViewTreeElement)?.value as? DocumentSymbolData)?.documentSymbol?.name == name
                }
                parent.children.filterIsInstance<TreeElementWrapper>().map {
                    (((it.value as StructureViewTreeElement).value) as DocumentSymbolData).documentSymbol.name
                }
            } } finally { edt { Disposer.dispose(wrapped) } }
        }
        fun write(name: String, text: String) = edt {
            val document = documents.getValue(name)
            WriteCommandAction.runWriteCommandAction(ownerProject) { document.setText(text) }
            PsiDocumentManager.getInstance(ownerProject).commitDocument(document)
        }
        fun find(name: String, elements: List<StructureViewTreeElement> = elements()): StructureViewTreeElement? {
            for (element in elements) {
                if ((element.value as? DocumentSymbolData)?.documentSymbol?.name == name) return element
                find(name, read { element.children.map { it as StructureViewTreeElement } })?.let { return it }
            }
            return null
        }
        fun assertDiskUnchanged() {
            for ((name, text) in initialSources) assertEquals(name, text, Files.readString(root.resolve(name)))
        }
        fun navigate(element: StructureViewTreeElement, name: String, offset: Int) {
            edt { StructureViewComponent.createWrapper(ownerProject, element, model).navigate(true) }
            edt {
                val selected = requireNotNull(FileEditorManager.getInstance(ownerProject).selectedTextEditor)
                val target = PsiDocumentManager.getInstance(ownerProject).getCachedDocument(files.getValue(name))
                assertSame(target, selected.document)
                assertEquals(offset, selected.caretModel.offset)
            }
        }
    }

    private fun fixture(supported: Boolean = true, origin: String = "model.go", goFeature: Boolean = true,
                        ownerProject: Project = project, sourceFiles: Map<String, String> = SOURCES,
                        preload: Set<String> = sourceFiles.keys, block: (Fixture) -> Unit) {
        val root = Files.createTempDirectory("open-goland-package-structure-").toRealPath()
        val disposable = Disposer.newDisposable("owned package Structure")
        VfsRootAccess.allowRootAccess(disposable, root.toString())
        val accessor = LanguageServiceAccessor.getInstance(ownerProject)
        @Suppress("UNCHECKED_CAST")
        val started = LanguageServiceAccessor::class.java.getDeclaredField("startedServers").apply { isAccessible = true }
            .get(accessor) as MutableSet<LanguageServerWrapper>
        val previous = synchronized(started) { started.toList() }
        val definition = requireNotNull(LanguageServersRegistry.getInstance().getServerDefinition("openidea.gopls"))
        val wrapper = OwnedWrapper(ownerProject, definition, supported, goFeature)
        var fixture: Fixture? = null
        var editor: Editor? = null
        try {
            val files = sourceFiles.mapValues { (name, text) ->
                val path = root.resolve(name); Files.writeString(path, text)
                val virtual = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path))
                read { requireNotNull(PsiManager.getInstance(ownerProject).findFile(virtual)) }
            }
            val documents = read { files.filterKeys { it in preload }.mapValues { (_, file) -> requireNotNull(PsiDocumentManager.getInstance(ownerProject).getDocument(file)) } }
            wrapper.files = files; wrapper.documents = documents; wrapper.initialSources = sourceFiles
            synchronized(started) { started.clear(); started.add(wrapper) }
            read { files.filterKeys { it in preload }.values.forEach { LSPFileSupport.getSupport(it) } }
            lateinit var native: Editor
            edt { native = EditorFactory.getInstance().createEditor(documents.getValue(origin), ownerProject); editor = native }
            fixture = Fixture(root, files, documents, native, wrapper, origin, ownerProject, sourceFiles)
            block(fixture)
        } finally {
            edt {
                fixture?.let { Disposer.dispose(it.model) }
                editor?.takeUnless { it.isDisposed }?.let(EditorFactory.getInstance()::releaseEditor)
                if (!ownerProject.isDisposed) fixture?.files?.values?.forEach { FileEditorManager.getInstance(ownerProject).closeFile(it.virtualFile) }
            }
            synchronized(started) { started.clear(); started.addAll(previous) }
            Disposer.dispose(wrapper); Disposer.dispose(disposable)
            root.toFile().deleteRecursively(); LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root)
        }
    }

    private class OwnedWrapper(project: Project, definition: LanguageServerDefinition, supported: Boolean, goFeature: Boolean) : LanguageServerWrapper(project, definition) {
        lateinit var files: Map<String, PsiFile>
        lateinit var documents: Map<String, Document>
        lateinit var initialSources: Map<String, String>
        val packageRequests = CopyOnWriteArrayList<ExecuteCommandParams>()
        val documentRequests = CopyOnWriteArrayList<String>()
        val openedAtCommand = CopyOnWriteArrayList<Map<String, String>>()
        private val openedText = ConcurrentHashMap<String, String>()
        private val closed = AtomicBoolean()
        @Volatile var nextReply: CompletableFuture<Any>? = null
        @Volatile var nextDocumentReply: CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>>? = null
        @Volatile var nativeSync = false
        @Volatile var documentOverrides = emptyMap<String, List<DocumentSymbol>>()
        @Volatile var identicalInit = false
        val features: LSPClientFeatures = (if (goFeature) GoLanguageServerFactory().createClientFeatures() else LSPClientFeatures()).also { it.setServerWrapper(this) }
        private val capabilities = ServerCapabilities().apply {
            setDocumentSymbolProvider(true)
            executeCommandProvider = ExecuteCommandOptions(if (supported) listOf(COMMAND) else emptyList())
        }
        private val text = Proxy.newProxyInstance(TextDocumentService::class.java.classLoader, arrayOf(TextDocumentService::class.java)) { _, method, args ->
            when (method.name) {
                "documentSymbol" -> {
                    check(!ApplicationManager.getApplication().isDispatchThread) { "Document symbol RPC on EDT" }
                    val uri = (requireNotNull(args?.first()) as DocumentSymbolParams).textDocument.uri
                    val name = Path.of(URI(uri)).fileName.toString()
                    documentRequests += name
                    nextDocumentReply?.also { nextDocumentReply = null }
                        ?: CompletableFuture.completedFuture((documentOverrides[name] ?: symbols(name, text(name))).map { Either.forRight<SymbolInformation, DocumentSymbol>(it) })
                }
                "didOpen" -> {
                    val document = (requireNotNull(args?.first()) as DidOpenTextDocumentParams).textDocument
                    openedText[Path.of(URI(document.uri)).fileName.toString()] = document.text
                    null
                }
                "didClose", "didChange" -> null
                else -> null
            }
        } as TextDocumentService
        private val workspace = Proxy.newProxyInstance(WorkspaceService::class.java.classLoader, arrayOf(WorkspaceService::class.java)) { _, method, args ->
            if (method.name == "executeCommand") {
                check(!ApplicationManager.getApplication().isDispatchThread) { "Package symbol RPC on EDT" }
                val params = requireNotNull(args?.first()) as ExecuteCommandParams
                check(params.command == COMMAND)
                packageRequests += params
                openedAtCommand += openedText.toMap()
                nextReply?.also { nextReply = null } ?: CompletableFuture.completedFuture(packageJson())
            } else null
        } as WorkspaceService
        private val remote = Proxy.newProxyInstance(LanguageServer::class.java.classLoader, arrayOf(LanguageServer::class.java)) { _, method, _ ->
            when (method.name) { "getTextDocumentService" -> text; "getWorkspaceService" -> workspace; else -> null }
        } as LanguageServer
        init {
            features.setServerCapabilities(capabilities)
            setField("serverCapabilities", capabilities); setField("initializeFuture", CompletableFuture.completedFuture<Void>(null))
            setField("languageServer", remote)
        }
        private fun text(name: String) = ReadAction.compute<String, RuntimeException> { documents[name]?.text ?: initialSources.getValue(name) }
        fun packageJson(): Any {
            if (identicalInit) {
                fun init(index: Int) = linkedMapOf<String, Any>("name" to "init", "kind" to SymbolKind.Function.value,
                    "detail" to "func()", "range" to Range(Position(1, 0), Position(1, 14)),
                    "selectionRange" to Range(Position(1, 5), Position(1, 9))).apply { if (index != 0) put("file", index) }
                return Gson().toJsonTree(mapOf("PackageName" to "fixture", "Files" to listOf("methods.go", "model.go").map { files.getValue(it).virtualFile.url },
                    "Symbols" to listOf(init(0), init(1))))
            }
            val ordered = listOf("methods.go", "model.go", "private.go")
            fun row(symbol: DocumentSymbol, file: Int): MutableMap<String, Any> = linkedMapOf<String, Any>(
                "name" to symbol.name, "kind" to symbol.kind.value, "detail" to (symbol.detail ?: ""),
                "range" to symbol.range, "selectionRange" to symbol.selectionRange
            ).apply {
                if (file != 0) put("file", file)
                symbol.children?.takeIf { it.isNotEmpty() }?.let { put("children", it.map { row(it, file) }) }
            }
            val types = symbols("model.go", text("model.go")).map { row(it, 1) }
            val record = types.single { it["name"] == "Record" }
            @Suppress("UNCHECKED_CAST")
            val children = (record["children"] as? List<MutableMap<String, Any>>).orEmpty().toMutableList()
            val methods = symbols("methods.go", text("methods.go"))
            children += row(methods.single { it.kind == SymbolKind.Method }.apply { name = name.substringAfterLast('.') }, 0)
            record["children"] = children
            val all = types + methods.filter { it.kind != SymbolKind.Method }.map { row(it, 0) } + symbols("private.go", text("private.go")).map { row(it, 2) }
            return Gson().toJsonTree(mapOf("PackageName" to "fixture", "Files" to ordered.map { files.getValue(it).virtualFile.url }, "Symbols" to all))
        }
        override fun start() {}
        override fun getInitializedServer(): CompletableFuture<LanguageServer> = CompletableFuture.completedFuture(remote)
        override fun getClientFeatures() = features
        override fun getServerStatus() = ServerStatus.started
        override fun isEnabled() = true
        override fun canOperate(file: VirtualFile) = true
        override fun getSyncOptions() = TextDocumentSyncOptions().apply {
            openClose = nativeSync
            change = if (nativeSync) TextDocumentSyncKind.Incremental else TextDocumentSyncKind.None
        }
        override fun dispose() {
            if (!closed.compareAndSet(false, true)) return
            openedDocuments.values.forEach { it.synchronizer?.let(Disposer::dispose) }
            openedDocuments.clear(); stopDispatcher(); features.dispose()
        }
        private fun setField(name: String, value: Any) = LanguageServerWrapper::class.java.getDeclaredField(name).apply { isAccessible = true }.set(this, value)
    }

    private fun <T> read(action: () -> T): T = ReadAction.compute<T, RuntimeException> { action() }
    private fun edt(action: () -> Unit) = EdtTestUtil.runInEdtAndWait<RuntimeException> { WriteIntentReadAction.run(Runnable(action)) }
    private fun awaitBlockedOn(worker: Thread, lock: Any) {
        val bean = ManagementFactory.getThreadMXBean()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            val info = bean.getThreadInfo(worker.id)
            if (info?.threadState == Thread.State.BLOCKED && info.lockInfo?.identityHashCode == System.identityHashCode(lock) &&
                info.lockInfo?.className == lock.javaClass.name) return
            check(worker.isAlive) { "Owned worker exited before reaching lifecycle" }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1))
        }
        error("Worker did not block on exact lifecycle: ${bean.getThreadInfo(worker.id)}")
    }
    private fun rejected(future: CompletableFuture<*>) {
        val failure = runCatching { future.get(10, TimeUnit.SECONDS) }.exceptionOrNull()
        assertNotNull("A cancelled/stale result must not publish a normal snapshot", failure)
        val cause = if (failure is ExecutionException) failure.cause else failure
        assertTrue("Expected cancellation, got $failure", cause is CancellationException || cause is com.intellij.openapi.progress.ProcessCanceledException)
    }
    private class UncancellableReply : CompletableFuture<Any>() {
        override fun cancel(mayInterruptIfRunning: Boolean) = false
    }
    companion object {
        private const val COMMAND = "gopls.package_symbols"
        private val SOURCES = linkedMapOf(
            "methods.go" to "package fixture\nvar _ = \"前😀\"; func (r *Record) Lookup(value string) string { return value }\nfunc Plain() {}\n",
            "model.go" to "package fixture\ntype Record struct { Name string }\nvar _ = \"前😀\"; var AfterEmoji int\n",
            "private.go" to "package fixture\nfunc privateHelper() {}\n"
        )
        private fun position(source: String, offset: Int): Position {
            val prefix = source.substring(0, offset)
            return Position(prefix.count { it == '\n' }, prefix.substringAfterLast('\n').length)
        }
        private fun lost(source: String): DocumentSymbol {
            val start = source.indexOf("func (r Missing)")
            val name = source.indexOf("Lost", start)
            val end = source.indexOf('\n', name).takeIf { it >= 0 } ?: source.length
            return DocumentSymbol("(Missing).Lost", SymbolKind.Method, Range(position(source, start), position(source, end)),
                Range(position(source, name), position(source, name + "Lost".length))).apply { detail = "func()" }
        }
        private fun symbols(name: String, source: String): List<DocumentSymbol> {
            fun symbol(identifier: String, kind: SymbolKind, detail: String, declaration: String): DocumentSymbol {
                val start = source.indexOf(declaration); require(start >= 0)
                val offset = source.indexOf(identifier, start); require(offset >= 0)
                val end = source.indexOf('\n', offset).takeIf { it >= 0 } ?: source.length
                return DocumentSymbol(identifier, kind, Range(position(source, start), position(source, end)),
                    Range(position(source, offset), position(source, offset + identifier.length))).apply { this.detail = detail }
            }
            return when (name) {
                "model.go" -> listOf(symbol("Record", SymbolKind.Struct, "struct{...}", "Record struct").apply {
                    children = listOf(symbol("Name", SymbolKind.Field, "string", "Name string"))
                }, symbol("AfterEmoji", SymbolKind.Variable, "int", "AfterEmoji int"))
                "methods.go" -> listOf(symbol("Lookup", SymbolKind.Method, "func(value string) string", "func (r *Record)").apply { this.name = "(*Record).Lookup" }, symbol("Plain", SymbolKind.Function, "func()", "func Plain"))
                else -> listOf(symbol("privateHelper", SymbolKind.Function, "func()", "func privateHelper"))
            }
        }
    }
}
