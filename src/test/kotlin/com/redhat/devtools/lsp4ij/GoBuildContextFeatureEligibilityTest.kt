package com.redhat.devtools.lsp4ij

import com.intellij.ide.impl.OpenProjectTask
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
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
import com.intellij.util.xmlb.XmlSerializerUtil
import com.redhat.devtools.lsp4ij.client.features.LSPClientFeatures
import com.redhat.devtools.lsp4ij.server.definition.LanguageServerDefinition
import dev.openidea.goide.lsp.GoLanguageServerFactory
import dev.openidea.goide.run.GoRunBuildContextService
import dev.openidea.goide.run.GoRunBuildStatus
import dev.openidea.goide.settings.GoProjectSettings
import dev.openidea.goide.settings.GoToolchain
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionContext
import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.ClientCapabilities
import org.eclipse.lsp4j.Command
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SemanticTokens
import org.eclipse.lsp4j.SemanticTokensLegend
import org.eclipse.lsp4j.SemanticTokensParams
import org.eclipse.lsp4j.SemanticTokensWithRegistrationOptions
import org.eclipse.lsp4j.ServerCapabilities
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentSyncKind
import org.eclipse.lsp4j.TextDocumentSyncOptions
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.eclipse.lsp4j.services.LanguageServer
import org.eclipse.lsp4j.services.TextDocumentService
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The registered project service and selected SDK really classify the physical/dirty Go files.
 * LSP4IJ's actual accessor then applies before/after feature filters to an owned started wrapper.
 * Only protocol replies/initialization are controlled: this is not gopls, desktop acceptance,
 * or proof that a daemon restart automatically clears a feature support's cached empty result.
 */
class GoBuildContextFeatureEligibilityTest : LightPlatformTestCase() {
    override fun runInDispatchThread() = false

    fun testCheckingThenActualSdkActiveIsSelectedAgainThroughTheRealProviderFilters() = fixture { f ->
        val checking = mutableListOf<CompletableFuture<List<LanguageServerItem>>>()
        write {
            // The native write lock prevents the real loader's background ReadAction
            // capture from completing before the first two provider queries.
            GoRunBuildContextService.settingsChanged(f.owner)
            assertEquals(GoRunBuildStatus.CHECKING, GoRunBuildContextService.get(f.owner).lookup(f.host).status)
            for (kind in Kind.entries) {
                assertFalse("Direct SDK leaf checks must also honor CHECKING", f.leafSupported(f.host, kind))
                checking += f.providers(f.host, kind)
            }
            assertEquals(GoRunBuildStatus.CHECKING, GoRunBuildContextService.get(f.owner).lookup(f.host).status)
            assertTrue("Both before filters must inspect the actual registered started wrapper", f.beforeExisting.get() >= 2)
            assertTrue(f.wrapper.requests.isEmpty())
        }
        checking.forEach { assertTrue("The before filter must reject its CHECKING generation", it.get(10, TimeUnit.SECONDS).isEmpty()) }
        f.await(f.host, GoRunBuildStatus.ACTIVE)
        for (kind in Kind.entries) {
            assertTrue(f.leafSupported(f.host, kind))
            assertEquals(1, f.dispatch(f.host, kind).get(10, TimeUnit.SECONDS))
        }
        assertEquals(setOf("semanticTokensFull", "codeAction"), f.wrapper.requests.map { it.first }.toSet())
    }

    fun testIgnoredFileHasNoSemanticOrActionRpcWhileAnotherActiveFileStillUsesTheProvider() = fixture { f ->
        f.await(f.extra, GoRunBuildStatus.IGNORED)
        f.await(f.host, GoRunBuildStatus.ACTIVE)
        repeat(3) {
            for (kind in Kind.entries) {
                assertFalse(f.leafSupported(f.extra, kind))
                assertEquals(0, f.dispatch(f.extra, kind).get(10, TimeUnit.SECONDS))
            }
        }
        assertTrue("An excluded source must not send the requests which need its package", f.wrapper.requests.isEmpty())
        for (kind in Kind.entries) assertEquals(1, f.dispatch(f.host, kind).get(10, TimeUnit.SECONDS))
        assertTrue(f.wrapper.requests.all { it.second == f.host.virtualFile.url })
        assertTrue(f.beforeExisting.get() > 0)
    }

    fun testMetadataFailureIsNotIgnoredAndTheSelectedProviderStillPropagatesItsRealFailure() = fixture { f ->
        f.await(f.host, GoRunBuildStatus.ACTIVE)
        // Fail real selected-SDK metadata without changing the server's settings
        // epoch: this is a module-parse failure, not an excluded-file decision.
        f.addFile("go.mod", "module\n")
        GoRunBuildContextService.get(f.owner).retry(f.host)
        f.await(f.host, GoRunBuildStatus.ERROR)
        f.wrapper.failure = ResponseErrorException(ResponseError(ResponseErrorCode.InternalError,
            "owned provider failure must remain visible", null))
        for (kind in Kind.entries) {
            assertTrue("Metadata ERROR is not an exclusion even for direct SDK consumers", f.leafSupported(f.host, kind))
            assertEquals(1, f.providers(f.host, kind).get(10, TimeUnit.SECONDS).size)
            val error = runCatching { f.dispatch(f.host, kind).get(10, TimeUnit.SECONDS) }.exceptionOrNull()
            assertTrue("A real RPC failure must not become a successful empty result", error is ExecutionException)
            assertSame(f.wrapper.failure, error?.cause)
        }
        assertEquals(2, f.wrapper.requests.size)
    }

    fun testActualSdkInvalidGoFileDoesNotBecomeAnExcludedFeature() = fixture { f ->
        val invalid = f.addFile("invalid.go", "package fixture\nimport (\n")
        f.await(invalid, GoRunBuildStatus.INVALID)
        for (kind in Kind.entries) assertEquals(1, f.dispatch(invalid, kind).get(10, TimeUnit.SECONDS))
        assertTrue(f.wrapper.requests.all { it.second == invalid.virtualFile.url })
    }

    fun testAfterInitializationRechecksTagsAndTheSameWrapperRecoversWhenTheFileBecomesActive() = fixture { f ->
        f.tags("native_extra")
        f.await(f.extra, GoRunBuildStatus.ACTIVE)
        f.wrapper.initializeFeatures()
        f.wrapper.holdInitialization()
        val requestsBefore = f.wrapper.requests.size
        val beforeChecks = f.beforeExisting.get()
        val held = Kind.entries.map { f.providers(f.extra, it) }
        assertTrue("Both real accessor calls must enter getInitializedServer", f.wrapper.awaitHeldInitializationEntries())
        assertTrue(held.none { it.isDone })
        assertTrue(f.beforeExisting.get() >= beforeChecks + 2)
        assertTrue("The real accessor must be waiting for initialized servers", f.wrapper.initializationRequests.get() >= 2)

        f.tags("")
        f.await(f.extra, GoRunBuildStatus.IGNORED)
        f.wrapper.releaseInitialization()
        held.forEach { assertTrue("After filter must not use the old active decision", it.get(10, TimeUnit.SECONDS).isEmpty()) }
        assertEquals(requestsBefore, f.wrapper.requests.size)
        assertTrue(f.afterExisting.get() >= 2)

        f.tags("native_extra")
        f.await(f.extra, GoRunBuildStatus.ACTIVE)
        for (kind in Kind.entries) assertEquals(1, f.dispatch(f.extra, kind).get(10, TimeUnit.SECONDS))
        assertEquals(requestsBefore + 2, f.wrapper.requests.size)
    }

    fun testDirtyBuildConstraintAndActualUndoInvalidateMembershipWithoutSavingTheSource() = fixture { f ->
        f.await(f.extra, GoRunBuildStatus.IGNORED)
        val disk = Files.readString(f.root.resolve("extra_test.go"))
        val document = read { requireNotNull(FileDocumentManager.getInstance().getDocument(f.extra.virtualFile)) }
        var editor: Editor? = null
        try {
            edt {
                editor = EditorFactory.getInstance().createEditor(document, f.owner)
                WriteCommandAction.runWriteCommandAction(f.owner) {
                    document.replaceString(0, TAGGED.indexOf('\n'), "//go:build !native_extra")
                }
                PsiDocumentManager.getInstance(f.owner).commitDocument(document)
            }
            assertEquals(disk, Files.readString(f.root.resolve("extra_test.go")))
            f.await(f.extra, GoRunBuildStatus.ACTIVE)
            for (kind in Kind.entries) assertEquals(1, f.dispatch(f.extra, kind).get(10, TimeUnit.SECONDS))
            val issued = f.wrapper.requests.size
            edt {
                UndoManager.getInstance(f.owner).undo(TextEditorProvider.getInstance().getTextEditor(requireNotNull(editor)))
                PsiDocumentManager.getInstance(f.owner).commitDocument(document)
                assertEquals(TAGGED, document.text)
            }
            f.await(f.extra, GoRunBuildStatus.IGNORED)
            for (kind in Kind.entries) assertEquals(0, f.dispatch(f.extra, kind).get(10, TimeUnit.SECONDS))
            assertEquals(issued, f.wrapper.requests.size)
            assertEquals(disk, Files.readString(f.root.resolve("extra_test.go")))
        } finally {
            edt { editor?.let { if (!it.isDisposed) EditorFactory.getInstance().releaseEditor(it) } }
        }
    }

    fun testRapidNativeSettingsEpochsCannotLeaveTheFinalActiveFileDisabledByAnOldIgnoredLoad() = fixture { f ->
        write {
            // Each first lookup schedules a real, coalesced loader; while its
            // capture is held by the native write lock the next settings epoch
            // invalidates it. No fabricated snapshot is injected into the service.
            for (tag in listOf("", "native_extra", "", "native_extra")) {
                GoProjectSettings.get(f.owner).buildTags = tag
                GoRunBuildContextService.settingsChanged(f.owner)
                assertEquals(GoRunBuildStatus.CHECKING, GoRunBuildContextService.get(f.owner).lookup(f.extra).status)
            }
        }
        f.await(f.extra, GoRunBuildStatus.ACTIVE)
        // Metadata can be active before the old gopls connection is restarted.
        for (kind in Kind.entries) assertTrue(f.providers(f.extra, kind).get(10, TimeUnit.SECONDS).isEmpty())
        f.wrapper.initializeFeatures()
        for (kind in Kind.entries) assertEquals(1, f.dispatch(f.extra, kind).get(10, TimeUnit.SECONDS))
    }

    fun testActiveMetadataCannotUseAnOldServerEnvironmentUntilItsInitializeEpochIsReplaced() = fixture { f ->
        f.await(f.host, GoRunBuildStatus.ACTIVE)
        for (kind in Kind.entries) assertEquals(1, f.dispatch(f.host, kind).get(10, TimeUnit.SECONDS))
        val issued = f.wrapper.requests.size
        edt {
            GoProjectSettings.get(f.owner).environment += "\nOWNED_FEATURE_EPOCH=changed"
            GoRunBuildContextService.settingsChanged(f.owner)
        }
        f.await(f.host, GoRunBuildStatus.ACTIVE)
        for (kind in Kind.entries) {
            assertFalse("Direct SDK consumers cannot bypass the initialization epoch", f.leafSupported(f.host, kind))
            assertEquals(0, f.dispatch(f.host, kind).get(10, TimeUnit.SECONDS))
        }
        assertEquals("Active membership alone cannot authorize a server with old initialize options", issued, f.wrapper.requests.size)
        f.wrapper.initializeFeatures()
        for (kind in Kind.entries) {
            assertTrue(f.leafSupported(f.host, kind))
            assertEquals(1, f.dispatch(f.host, kind).get(10, TimeUnit.SECONDS))
        }
    }

    fun testDelveOnlySettingsChangeDoesNotRequireReinitializingAnUnrelatedGoplsServer() = fixture { f ->
        f.await(f.host, GoRunBuildStatus.ACTIVE)
        val initialized = f.wrapper.initializationSnapshots.get()
        edt {
            GoProjectSettings.get(f.owner).delveExecutable = f.root.resolve("unused-debugger-selection").toString()
            GoRunBuildContextService.settingsChanged(f.owner)
        }
        f.await(f.host, GoRunBuildStatus.ACTIVE)
        for (kind in Kind.entries) assertEquals(1, f.dispatch(f.host, kind).get(10, TimeUnit.SECONDS))
        assertEquals("Delve is not part of gopls's initialization epoch", initialized, f.wrapper.initializationSnapshots.get())
    }

    fun testDisposedOwnedProjectCannotReviveAProviderHeldDuringInitialization() {
        val location = Files.createTempDirectory("owned-go-feature-project-").toRealPath()
        val owner = requireNotNull(ProjectManagerEx.getInstanceEx().newProject(location, OpenProjectTask {
            isNewProject = true; useDefaultProjectAsTemplate = false
            projectName = "owned Go feature eligibility"; preloadServices = false; createModule = false
        }))
        try {
            fixture(owner) { f ->
                f.await(f.host, GoRunBuildStatus.ACTIVE)
                f.wrapper.holdInitialization()
                val held = Kind.entries.map { f.providers(f.host, it) }
                assertTrue(held.none { it.isDone })
                write { Disposer.dispose(owner) }
                assertTrue(owner.isDisposed)
                read {
                    assertFalse(f.wrapper.features.semanticTokensFeature.isEnabled(f.host))
                    assertFalse(f.wrapper.features.codeActionFeature.isEnabled(f.host))
                }
                f.wrapper.releaseInitialization()
                held.forEach { pending ->
                    val value = runCatching { pending.get(10, TimeUnit.SECONDS) }
                    if (value.isSuccess) assertTrue(value.getOrThrow().isEmpty())
                    else {
                        var cause = value.exceptionOrNull()
                        while (cause is ExecutionException && cause.cause != null) cause = cause.cause
                        assertTrue("Project closure may cancel, never revive, its provider", cause is java.util.concurrent.CancellationException ||
                            cause is com.intellij.openapi.progress.ProcessCanceledException)
                    }
                }
                assertTrue(f.wrapper.requests.isEmpty())
            }
        } finally {
            if (!owner.isDisposed) write { Disposer.dispose(owner) }
            location.toFile().deleteRecursively()
        }
    }

    private enum class Kind { SEMANTIC, ACTION }

    private inner class Fixture(val owner: Project, val root: Path, val wrapper: OwnedWrapper,
                                val lifetime: Disposable, val accessor: LanguageServiceAccessor) {
        val beforeExisting = AtomicInteger()
        val afterExisting = AtomicInteger()
        val host = addFile("host.go", "package fixture\nconst Live = 1\n")
        val extra = addFile("extra_test.go", TAGGED)

        fun leafSupported(file: PsiFile, kind: Kind): Boolean = read {
            when (kind) {
                Kind.SEMANTIC -> wrapper.features.semanticTokensFeature.isSemanticTokensSupported(file)
                Kind.ACTION -> wrapper.features.codeActionFeature.isCodeActionSupported(file)
            }
        }

        fun addFile(name: String, text: String): PsiFile {
            val path = root.resolve(name)
            Files.writeString(path, text)
            val virtual = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path))
            return read {
                requireNotNull(PsiManager.getInstance(owner).findFile(virtual)).also {
                    requireNotNull(PsiDocumentManager.getInstance(owner).getDocument(it))
                }
            }
        }

        fun providers(file: PsiFile, kind: Kind): CompletableFuture<List<LanguageServerItem>> = read {
            accessor.getLanguageServers(file, { features ->
                if (features === wrapper.features) beforeExisting.incrementAndGet()
                when (kind) {
                    Kind.SEMANTIC -> features.semanticTokensFeature.isEnabled(file)
                    Kind.ACTION -> features.codeActionFeature.isEnabled(file)
                }
            }, { features ->
                if (features === wrapper.features) afterExisting.incrementAndGet()
                when (kind) {
                    Kind.SEMANTIC -> features.semanticTokensFeature.isSupported(file)
                    Kind.ACTION -> features.codeActionFeature.isSupported(file)
                }
            })
        }

        fun dispatch(file: PsiFile, kind: Kind): CompletableFuture<Int> = providers(file, kind).thenCompose { selected ->
            if (selected.isEmpty()) CompletableFuture.completedFuture(0)
            else {
                check(selected.size == 1) { "Only this owned started provider may handle the fixture" }
                // The actual accessor has selected the owned wrapper above;
                // only its remote endpoint is a protocol helper, not real gopls.
                val id = TextDocumentIdentifier(file.virtualFile.url)
                val rpc: CompletableFuture<*> = when (kind) {
                    Kind.SEMANTIC -> wrapper.remote.textDocumentService.semanticTokensFull(SemanticTokensParams(id))
                    Kind.ACTION -> wrapper.remote.textDocumentService.codeAction(CodeActionParams(id,
                        Range(Position(0, 0), Position(0, 0)), CodeActionContext(emptyList())))
                }
                rpc.thenApply { 1 }
            }
        }

        fun tags(value: String) = edt {
            GoProjectSettings.get(owner).buildTags = value
            GoRunBuildContextService.settingsChanged(owner)
        }

        fun await(file: PsiFile, expected: GoRunBuildStatus) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
            while (true) {
                val view = read { GoRunBuildContextService.get(owner).lookup(file) }
                if (view.status != GoRunBuildStatus.CHECKING) {
                    assertEquals("${file.name}: ${view.message}", expected, view.status)
                    return
                }
                check(System.nanoTime() < deadline) { "Selected Go SDK did not classify ${file.name}" }
                Thread.sleep(10)
            }
        }
    }

    private fun fixture(ownerProject: Project = project, action: (Fixture) -> Unit) {
        val root = Files.createTempDirectory("Go feature package 中文😀 ").toRealPath()
        val lifetime = Disposer.newDisposable("owned Go package-feature scope")
        VfsRootAccess.allowRootAccess(lifetime, root.toString())
        val settings = GoProjectSettings.get(ownerProject)
        val previousSettings = GoProjectSettings.Data().also { XmlSerializerUtil.copyBean(settings, it) }
        val accessor = LanguageServiceAccessor.getInstance(ownerProject)
        @Suppress("UNCHECKED_CAST")
        val started = LanguageServiceAccessor::class.java.getDeclaredField("startedServers").apply { isAccessible = true }
            .get(accessor) as MutableSet<LanguageServerWrapper>
        val previous = synchronized(started) { started.toList() }
        val definition = requireNotNull(LanguageServersRegistry.getInstance().getServerDefinition("openidea.gopls"))
        val wrapper = OwnedWrapper(ownerProject, definition)
        try {
            val selected = requireNotNull(System.getProperty("opengoland.testGo")?.takeIf(String::isNotBlank)
                ?: System.getenv("OPENGOLAND_GO_TEST")?.takeIf(String::isNotBlank)) {
                "Select the actual runtime Go SDK using OPENGOLAND_GO_TEST; no fallback or SDK copy"
            }
            val go = Path.of(requireNotNull(GoToolchain.resolve("go", selected))).toRealPath()
            settings.apply {
                goExecutable = go.toString(); buildTags = ""; goos = ""; goarch = ""; cgoEnabled = "0"
                environment = "GOENV=off\nGOFLAGS=\nGOTOOLCHAIN=local\nGOWORK=off\nGOPROXY=off\nGOSUMDB=off\nGOTELEMETRY=off\nGOMAXPROCS=2"
            }
            wrapper.initializeFeatures()
            GoRunBuildContextService.settingsChanged(ownerProject)
            Files.writeString(root.resolve("go.mod"), "module example.org/featureeligibility\n\ngo 1.24.0\n")
            val fixture = Fixture(ownerProject, root, wrapper, lifetime, accessor)
            synchronized(started) { started.clear(); started.add(wrapper) }
            action(fixture)
        } finally {
            if (!ownerProject.isDisposed) {
                XmlSerializerUtil.copyBean(previousSettings, settings)
                GoRunBuildContextService.settingsChanged(ownerProject)
            }
            synchronized(started) {
                started.clear()
                if (!ownerProject.isDisposed) started.addAll(previous)
            }
            if (!Disposer.isDisposed(wrapper)) Disposer.dispose(wrapper)
            Disposer.dispose(lifetime)
            root.toFile().deleteRecursively()
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root)
        }
    }

    private class OwnedWrapper(project: Project, definition: LanguageServerDefinition) : LanguageServerWrapper(project, definition) {
        val features: LSPClientFeatures = GoLanguageServerFactory().createClientFeatures()
        val requests = CopyOnWriteArrayList<Pair<String, String>>()
        val initializationRequests = AtomicInteger()
        val initializationSnapshots = AtomicInteger()
        @Volatile var failure: ResponseErrorException? = null
        @Volatile private var heldInitialization: CompletableFuture<LanguageServer>? = null
        @Volatile private var heldInitializationEntries: CountDownLatch? = null
        private val capabilities = ServerCapabilities().apply {
            codeActionProvider = Either.forLeft(true)
            semanticTokensProvider = SemanticTokensWithRegistrationOptions().apply {
                legend = SemanticTokensLegend(listOf("variable"), emptyList())
                full = Either.forLeft(true)
            }
        }
        private val text = Proxy.newProxyInstance(TextDocumentService::class.java.classLoader,
            arrayOf(TextDocumentService::class.java)) { proxy, method, supplied ->
            val args = supplied.orEmpty()
            when (method.name) {
                "semanticTokensFull" -> {
                    requests += method.name to (args[0] as SemanticTokensParams).textDocument.uri
                    failure?.let { CompletableFuture.failedFuture<SemanticTokens>(it) }
                        ?: CompletableFuture.completedFuture(SemanticTokens(listOf(0, 0, 1, 0, 0)))
                }
                "codeAction" -> {
                    requests += method.name to (args[0] as CodeActionParams).textDocument.uri
                    failure?.let { CompletableFuture.failedFuture<List<Either<Command, CodeAction>>>(it) }
                        ?: CompletableFuture.completedFuture(listOf(Either.forRight<Command, CodeAction>(CodeAction("owned action"))))
                }
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args.firstOrNull()
                "toString" -> "owned package-feature protocol helper"
                else -> null
            }
        } as TextDocumentService
        val remote = Proxy.newProxyInstance(LanguageServer::class.java.classLoader,
            arrayOf(LanguageServer::class.java)) { proxy, method, supplied ->
            when (method.name) {
                "getTextDocumentService" -> text
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === supplied?.firstOrNull()
                "toString" -> "owned package-feature server"
                else -> null
            }
        } as LanguageServer
        init {
            features.setServerWrapper(this); features.setServerCapabilities(capabilities)
            setField("serverCapabilities", capabilities)
            setField("initializeFuture", CompletableFuture.completedFuture<Void>(null))
            setField("languageServer", remote)
        }
        fun initializeFeatures() {
            features.initializeParams(InitializeParams().apply { capabilities = ClientCapabilities() })
            initializationSnapshots.incrementAndGet()
        }
        fun holdInitialization() {
            check(heldInitialization == null)
            heldInitializationEntries = CountDownLatch(2)
            heldInitialization = CompletableFuture()
        }
        fun awaitHeldInitializationEntries() = requireNotNull(heldInitializationEntries).await(10, TimeUnit.SECONDS)
        fun releaseInitialization() {
            val pending = heldInitialization
            heldInitialization = null
            pending?.complete(remote)
        }
        override fun start() {}
        override fun getInitializedServer(): CompletableFuture<LanguageServer> {
            val pending = heldInitialization
            initializationRequests.incrementAndGet()
            if (pending != null) heldInitializationEntries?.countDown()
            return pending ?: CompletableFuture.completedFuture(remote)
        }
        override fun getClientFeatures() = features
        override fun getServerStatus() = ServerStatus.started
        override fun isEnabled() = true
        override fun canOperate(file: VirtualFile) = true
        override fun getSyncOptions() = TextDocumentSyncOptions().apply { openClose = false; change = TextDocumentSyncKind.None }
        override fun dispose() {
            heldInitialization?.cancel(false)
            heldInitialization = null
            openedDocuments.values.forEach { it.synchronizer?.let(Disposer::dispose) }
            openedDocuments.clear(); stopDispatcher(); features.dispose()
        }
        private fun setField(name: String, value: Any) {
            LanguageServerWrapper::class.java.getDeclaredField(name).apply { isAccessible = true }.set(this, value)
        }
    }

    private fun <T> read(action: () -> T): T = ReadAction.compute<T, RuntimeException> { action() }
    private fun edt(action: () -> Unit) = EdtTestUtil.runInEdtAndWait<RuntimeException> { WriteIntentReadAction.run(Runnable(action)) }
    private fun write(action: () -> Unit) = edt { WriteAction.run<RuntimeException> { action() } }

    companion object {
        private const val TAGGED = "//go:build native_extra\n\npackage fixture\nimport \"testing\"\nfunc TestExtra(t *testing.T) {}\n"
    }
}
