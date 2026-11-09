package com.redhat.devtools.lsp4ij

import com.intellij.codeInsight.TargetElementUtil
import com.intellij.codeInsight.TargetElementUtilBase
import com.intellij.codeInsight.navigation.GotoImplementationHandler
import com.intellij.codeInsight.navigation.GotoTargetHandler
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.project.Project
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.EdtTestUtil
import com.intellij.testFramework.LightPlatformTestCase
import com.redhat.devtools.lsp4ij.client.features.LSPClientFeatures
import com.redhat.devtools.lsp4ij.server.definition.LanguageServerDefinition
import dev.openidea.goide.lang.GoLanguage
import dev.openidea.goide.navigation.GoTargetElementEvaluator
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.LanguageServer
import org.eclipse.lsp4j.services.TextDocumentService
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/** Standard platform delegates and the 261 symbol bridge; never invokes an LSP action directly. */
class GoStandardNavigationTest : LightPlatformTestCase() {
    override fun runInDispatchThread() = false

    fun testStandardImplementationDelegateDispatchesTheIdentifierAndReturnsTheRealTarget() = fixture { f ->
        f.caret("Reader")
        var data: GotoTargetHandler.GotoData? = null
        edt { data = GotoImplementationHandler().getSourceAndTargetElements(f.editor, f.file) }
        assertNotNull("The standard action must reach its definitions search", data)
        val completed = requireNotNull(data)
        val target = completed.targets.single()
        assertEquals(f.valueOffset, target.textRange.startOffset)
        assertEquals("Value", target.text)
        assertTrue("A lexical query origin must not be counted as an implementation", target.textRange != completed.source.textRange)
        assertEquals(listOf("implementation"), f.wrapper.requests.map { it.first })
        assertEquals(position(f.readerOffset), (f.wrapper.requests.single().second as ImplementationParams).position)
    }

    fun testCurrentTypeActionSymbolBridgeBuildsASingleTargetNavigationRequest() = fixture { f ->
        f.caret("Current")
        val result = read { typeResult(f.file, f.editor.caretModel.offset) }
        assertNotNull("The actual 261 handler's symbol/evaluator bridge must produce action data", result)
        assertEquals("SingleTarget", requireNotNull(result).javaClass.simpleName)
        val requestor = result.javaClass.getMethod("getRequestor").invoke(result)
        assertEquals(f.readerOffset, requestOffset(requestor))
        assertEquals(f.file.virtualFile, requestFile(requestor))
        assertEquals(listOf("typeDefinition"), f.wrapper.requests.map { it.first })
    }

    fun testCurrentTypeActionBuildsAllChooserTargetsForAnAmbiguousServerReply() = fixture { f ->
        f.wrapper.multipleTypes = true
        f.caret("Current")
        val result = requireNotNull(read { typeResult(f.file, f.editor.caretModel.offset) })
        assertEquals("MultipleTargets", result.javaClass.simpleName)
        val targets = result.javaClass.getMethod("getTargets").invoke(result) as List<*>
        assertEquals(2, targets.size)
        val offsets = targets.map { item ->
            val target = requireNotNull(item)
            assertNotNull(target.javaClass.getMethod("getPresentation").invoke(target))
            requestOffset(target.javaClass.getMethod("getRequestor").invoke(target))
        }
        assertEquals(setOf(f.readerOffset, f.valueOffset), offsets.toSet())
        assertEquals(listOf("typeDefinition"), f.wrapper.requests.map { it.first })
    }

    fun testOnlyIdentifierTokensBecomeTargetsAndUtf16BoundariesArePreserved() = fixture { f ->
        val text = f.editor.document.text
        for (offset in listOf(text.indexOf("package") + 2, text.indexOf("//") + 5,
            text.indexOf("interface") + 2, text.indexOf('"') + 3, text.indexOf("{}") + 1,
            text.indexOf("\n\n") + 1, text.indexOf("var _") + 4, text.length)) {
            assertNull("Non-identifier offset $offset must not invent a target", f.target(offset))
        }
        val unicode = text.indexOf("𐐀名字")
        for (offset in listOf(unicode, unicode + 1, unicode + 2, unicode + 4)) {
            val target = requireNotNull(f.target(offset))
            assertEquals("𐐀名字", target.text)
            assertEquals(unicode, target.textRange.startOffset)
            assertEquals(unicode + 4, target.textRange.endOffset)
        }
        assertNull(f.target(text.indexOf("\t\t ") + 2))
        assertTrue("Target discovery alone must not query a server", f.wrapper.requests.isEmpty())
    }

    fun testQueryOriginBecomesInvalidAfterAnUnsavedDocumentEdit() = fixture { f ->
        val target = requireNotNull(f.target(f.readerOffset + 1))
        assertTrue(target.isValid)
        f.write { f.editor.document.insertString(0, "// unsaved 中文😀\n") }
        assertFalse("A stale query origin must not retain the former token location", target.isValid)
        assertTrue(f.wrapper.requests.isEmpty())
    }

    fun testHeldStandardTypeResolutionKeepsItsOriginalQueryWhenTheCaretMoves() = fixture { f ->
        f.caret("Current")
        val offset = read { f.editor.caretModel.offset }
        val held = f.wrapper.holdTypeReply()
        val pool = Executors.newSingleThreadExecutor()
        try {
            val pending = pool.submit<Any?> { read { typeResult(f.file, offset) } }
            assertTrue(f.wrapper.typeRequested.await(10, TimeUnit.SECONDS))
            f.caret("Value")
            held.complete(f.wrapper.typeReply())
            val result = requireNotNull(pending.get(10, TimeUnit.SECONDS))
            assertEquals("SingleTarget", result.javaClass.simpleName)
            assertEquals(f.readerOffset, requestOffset(result.javaClass.getMethod("getRequestor").invoke(result)))
            assertEquals(position(offset), (f.wrapper.requests.single().second as TypeDefinitionParams).position)
        } finally {
            held.complete(f.wrapper.typeReply()); pool.shutdownNow(); pool.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    fun testCancellingHeldStandardResolutionExitsBeforeConstructingANavigationResult() = fixture { f ->
        f.caret("Current")
        val offset = read { f.editor.caretModel.offset }
        val held = f.wrapper.holdTypeReply()
        val indicator = EmptyProgressIndicator()
        val pool = Executors.newSingleThreadExecutor()
        try {
            val pending = pool.submit<Any?> {
                ProgressManager.getInstance().runProcess(Computable { read { typeResult(f.file, offset) } }, indicator)
            }
            assertTrue(f.wrapper.typeRequested.await(10, TimeUnit.SECONDS))
            indicator.cancel()
            val error = try { pending.get(10, TimeUnit.SECONDS); null } catch (e: ExecutionException) { e }
            assertNotNull("Cancellation must not complete with a navigation result", error)
            val causes = generateSequence(requireNotNull(error) as Throwable?) { it.cause }.toList()
            assertTrue("Actual standard provider cancellation must remain cancellation: $causes", causes.any { it is ProcessCanceledException })
        } finally {
            held.complete(f.wrapper.typeReply()); pool.shutdownNow(); pool.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    fun testHeldStandardReadResolutionSerializesAQueuedDocumentWrite() = fixture { f ->
        f.caret("Current")
        val offset = read { f.editor.caretModel.offset }
        val origin = requireNotNull(f.target(offset))
        val held = f.wrapper.holdTypeReply()
        val pool = Executors.newFixedThreadPool(2)
        val writerEntered = CountDownLatch(1)
        val writerFinished = CountDownLatch(1)
        try {
            val pending = pool.submit<Any?> { read { typeResult(f.file, offset) } }
            assertTrue(f.wrapper.typeRequested.await(10, TimeUnit.SECONDS))
            val write = pool.submit {
                f.write { writerEntered.countDown(); f.editor.document.insertString(0, "// queued edit\n") }
                writerFinished.countDown()
            }
            // WriteCommandAction cannot enter while the real resolution holds
            // read access. No concurrent source mutation is manufactured here.
            assertFalse(writerEntered.await(100, TimeUnit.MILLISECONDS))
            assertFalse(writerFinished.await(100, TimeUnit.MILLISECONDS))
            held.complete(f.wrapper.typeReply())
            assertNotNull(pending.get(10, TimeUnit.SECONDS))
            write.get(10, TimeUnit.SECONDS)
            assertFalse(origin.isValid)
            assertTrue(f.editor.document.text.startsWith("// queued edit\n"))
        } finally {
            held.complete(f.wrapper.typeReply()); pool.shutdownNow(); pool.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    fun testActualTypeActionToleratesAnEditorDisposedWhileTheReplyIsHeld() = fixture { f ->
        f.caret("Current")
        val held = f.wrapper.holdTypeReply()
        val pool = Executors.newSingleThreadExecutor()
        try {
            val dispose = pool.submit {
                check(f.wrapper.typeRequested.await(10, TimeUnit.SECONDS))
                ApplicationManager.getApplication().invokeLater({
                    EditorFactory.getInstance().releaseEditor(f.editor)
                    held.complete(f.wrapper.typeReply())
                }, ModalityState.any())
            }
            edt {
                val action = Class.forName("com.intellij.codeInsight.navigation.actions.GotoTypeDeclarationAction").getConstructor().newInstance()
                val handler = action.javaClass.getDeclaredMethod("getHandler").apply { isAccessible = true }.invoke(action)
                    as com.intellij.codeInsight.CodeInsightActionHandler
                assertEquals("GotoTypeDeclarationHandler2", handler.javaClass.simpleName)
                handler.invoke(project, f.editor, f.file)
            }
            dispose.get(10, TimeUnit.SECONDS)
            assertTrue(f.editor.isDisposed)
            assertEquals(listOf("typeDefinition"), f.wrapper.requests.map { it.first })
        } finally {
            held.complete(f.wrapper.typeReply()); pool.shutdownNow(); pool.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    private inner class Fixture(val file: PsiFile, val editor: Editor, val wrapper: OwnedWrapper) {
        val readerOffset = identifierOffset("Reader")
        val valueOffset = identifierOffset("Value")
        fun caret(name: String) = edt { editor.caretModel.moveToOffset(identifierOffset(name) + 1) }
        fun target(offset: Int): PsiElement? = read {
            TargetElementUtil.getInstance().findTargetElement(editor, TargetElementUtil.REFERENCED_ELEMENT_ACCEPTED, offset)
        }
        fun write(action: () -> Unit) = edt {
            WriteCommandAction.runWriteCommandAction(project, Runnable(action))
            PsiDocumentManager.getInstance(project).commitDocument(editor.document)
        }
    }

    private fun fixture(block: (Fixture) -> Unit) {
        val root = Files.createTempDirectory("open-goland-standard-navigation-").toRealPath()
        val disposable = Disposer.newDisposable("owned standard navigation")
        VfsRootAccess.allowRootAccess(disposable, root.toString())
        val extension = TargetElementUtilBase.TARGET_ELEMENT_EVALUATOR
        val explicit = System.getProperty("openidea.testNavigationEvaluator") == "true"
        val evaluator = GoTargetElementEvaluator()
        if (explicit) extension.addExplicitExtension(GoLanguage, evaluator)
        val accessor = LanguageServiceAccessor.getInstance(project)
        @Suppress("UNCHECKED_CAST")
        val started = LanguageServiceAccessor::class.java.getDeclaredField("startedServers").apply { isAccessible = true }
            .get(accessor) as MutableSet<LanguageServerWrapper>
        val previous = synchronized(started) { started.toList() }
        val definition = requireNotNull(LanguageServersRegistry.getInstance().getServerDefinition("openidea.gopls"))
        val wrapper = OwnedWrapper(project, definition)
        var editor: Editor? = null
        try {
            val path = root.resolve("source.go")
            Files.writeString(path, SOURCE)
            val virtual = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path))
            val file = read { requireNotNull(PsiManager.getInstance(project).findFile(virtual)) }
            val document = read { requireNotNull(PsiDocumentManager.getInstance(project).getDocument(file)) }
            wrapper.uri = virtual.url
            synchronized(started) { started.clear(); started.add(wrapper) }
            lateinit var native: Editor
            edt { native = EditorFactory.getInstance().createEditor(document, project); editor = native }
            block(Fixture(file, native, wrapper))
        } finally {
            synchronized(started) { started.clear(); started.addAll(previous) }
            edt { editor?.takeUnless { it.isDisposed }?.let(EditorFactory.getInstance()::releaseEditor) }
            Disposer.dispose(wrapper)
            if (explicit) extension.removeExplicitExtension(GoLanguage, evaluator)
            Disposer.dispose(disposable)
            root.toFile().deleteRecursively()
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root)
        }
    }

    private class OwnedWrapper(project: Project, definition: LanguageServerDefinition) : LanguageServerWrapper(project, definition) {
        lateinit var uri: String
        var multipleTypes = false
        private var heldType: CompletableFuture<Either<List<Location>, List<LocationLink>>>? = null
        val typeRequested = CountDownLatch(1)
        val requests = CopyOnWriteArrayList<Pair<String, Any>>()
        private val features = LSPClientFeatures().also { it.setServerWrapper(this) }
        private val capabilities = ServerCapabilities().apply {
            setImplementationProvider(true); setTypeDefinitionProvider(true); setDefinitionProvider(true)
        }
        private val text = Proxy.newProxyInstance(TextDocumentService::class.java.classLoader, arrayOf(TextDocumentService::class.java)) { _, method, args ->
            when (method.name) {
                "implementation", "typeDefinition", "definition" -> {
                    requests += method.name to requireNotNull(args?.firstOrNull())
                    if (method.name == "typeDefinition") {
                        typeRequested.countDown()
                        heldType?.let { return@newProxyInstance it }
                    }
                    val values = when (method.name) {
                        "implementation" -> listOf(location("Value"))
                        "typeDefinition" -> if (multipleTypes) listOf(location("Reader"), location("Value")) else listOf(location("Reader"))
                        else -> listOf(location("Ordinary"))
                    }
                    CompletableFuture.completedFuture(Either.forLeft<List<Location>, List<LocationLink>>(values))
                }
                "didOpen", "didClose", "didChange" -> null
                else -> null
            }
        } as TextDocumentService
        private val remote = Proxy.newProxyInstance(LanguageServer::class.java.classLoader, arrayOf(LanguageServer::class.java)) { _, method, _ ->
            if (method.name == "getTextDocumentService") text else null
        } as LanguageServer
        init {
            features.setServerCapabilities(capabilities)
            setField("serverCapabilities", capabilities)
            setField("initializeFuture", CompletableFuture.completedFuture<Void>(null)); setField("languageServer", remote)
        }
        private fun location(name: String): Location {
            val offset = identifierOffset(name)
            return Location(uri, Range(position(offset), position(offset + name.length)))
        }
        fun holdTypeReply(): CompletableFuture<Either<List<Location>, List<LocationLink>>> = CompletableFuture<Either<List<Location>, List<LocationLink>>>().also { heldType = it }
        fun typeReply(): Either<List<Location>, List<LocationLink>> = Either.forLeft(listOf(location("Reader")))
        override fun start() {}
        override fun getInitializedServer(): CompletableFuture<LanguageServer> = CompletableFuture.completedFuture(remote)
        override fun getClientFeatures() = features
        override fun getServerStatus() = ServerStatus.started
        override fun isEnabled() = true
        override fun canOperate(file: VirtualFile) = true
        override fun getSyncOptions() = TextDocumentSyncOptions().apply { openClose = false; change = TextDocumentSyncKind.None }
        override fun dispose() {
            openedDocuments.values.forEach { it.synchronizer?.let(Disposer::dispose) }
            openedDocuments.clear(); stopDispatcher(); features.dispose()
        }
        private fun setField(name: String, value: Any) {
            LanguageServerWrapper::class.java.getDeclaredField(name).apply { isAccessible = true }.set(this, value)
        }
    }

    private fun typeResult(file: PsiFile, offset: Int): Any? {
        // This is the exact delegate invoked by GotoTypeDeclarationHandler2 in
        // SDK 261. The older findSymbolTypes test helper follows another path.
        val data = Class.forName("com.intellij.codeInsight.navigation.impl.GttdKt")
            .getMethod("gotoTypeDeclaration", PsiFile::class.java, Int::class.javaPrimitiveType).invoke(null, file, offset) ?: return null
        return data.javaClass.getMethod("result").invoke(data)
    }
    private fun request(requestor: Any): Any = read { requireNotNull(Class.forName("com.intellij.codeInsight.navigation.impl.NavigationRequestor")
        .getMethod("navigationRequest").invoke(requestor)) }
    private fun requestOffset(requestor: Any): Int = (request(requestor).javaClass.getMethod("getOffsetMarker")
        .invoke(request(requestor)) as RangeMarker).startOffset
    private fun requestFile(requestor: Any): VirtualFile = request(requestor).javaClass.getMethod("getFile")
        .invoke(request(requestor)) as VirtualFile
    private fun <T> read(action: () -> T): T = ReadAction.compute<T, RuntimeException> { action() }
    private fun edt(action: () -> Unit) = EdtTestUtil.runInEdtAndWait<RuntimeException> { WriteIntentReadAction.run(Runnable(action)) }

    companion object {
        private const val SOURCE = "package fixture\n\n// 中文😀 Reader in a comment\ntype Reader interface { Read() string }\ntype Value struct{}\nfunc (Value) Read() string { return \"Reader text\" }\nvar Current Reader = Value{}\nvar _ Reader = Value{}\nvar 𐐀名字 int\n\t\t \nfunc Ordinary() {}\nfunc Caller() { Ordinary() }\n"
        private fun identifierOffset(name: String) = when (name) {
            "Reader", "Value" -> SOURCE.indexOf("type $name") + 5
            else -> SOURCE.indexOf(name)
        }
        private fun position(offset: Int): Position {
            val prefix = SOURCE.substring(0, offset)
            return Position(prefix.count { it == '\n' }, prefix.substringAfterLast('\n').length)
        }
    }
}
