package com.redhat.devtools.lsp4ij

import com.google.gson.Gson
import com.intellij.ide.structureView.newStructureView.StructureViewComponent
import com.intellij.ide.util.FileStructurePopup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.testFramework.EdtTestUtil
import com.intellij.testFramework.LightPlatformTestCase
import com.redhat.devtools.lsp4ij.features.documentSymbol.DocumentSymbolData
import com.redhat.devtools.lsp4ij.client.features.LSPClientFeatures
import com.redhat.devtools.lsp4ij.server.definition.LanguageServerDefinition
import dev.openidea.goide.lsp.GoLanguageServerFactory
import dev.openidea.goide.lsp.GoPackageStructureRoot
import dev.openidea.goide.lsp.GoStructureViewModel
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.LanguageServer
import org.eclipse.lsp4j.services.TextDocumentService
import org.eclipse.lsp4j.services.WorkspaceService
import org.jetbrains.concurrency.Promise
import java.lang.reflect.Proxy
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.tree.TreePath

/** Exercises the constructor-captured target and the actual popup's asynchronous selection protocol. */
class GoStructurePopupSelectionTest : LightPlatformTestCase() {
    override fun runInDispatchThread() = false

    fun testColdPackagePopupSelectsTheMethodFromTheWarmDocumentSymbolCache() = fixture { f ->
        f.warmDocumentSymbols()
        f.selectPopup("Lookup", f.source.indexOf("Lookup") + 2)
    }

    fun testFullyColdPopupSelectsTheMethodAfterItsPackageReply() = fixture { f ->
        assertTrue(f.wrapper.documentRequests.isEmpty())
        f.selectPopup("Lookup", f.source.indexOf("Lookup") + 2)
    }

    fun testColdPackagePopupSelectsTheContainingMethodFromItsBody() = fixture { f ->
        f.warmDocumentSymbols()
        f.selectPopup("Lookup", f.source.indexOf("return value") + 4)
    }

    fun testReopenedPopupKeepsTheMethodSelectedWithANewColdModel() = fixture { f ->
        f.warmDocumentSymbols()
        f.selectPopup("Lookup", f.source.indexOf("Lookup") + 2, warmPackage = true)
        // A second native invocation constructs a new model, not the first popup's support/cache.
        f.selectPopup("Lookup", f.source.indexOf("Lookup") + 2)
    }

    fun testFullyColdGenericClientKeepsItsFlatMethodAndNativeSelection() = fixture(generic = true) { f ->
        f.selectPopup("(*Record).Lookup", f.source.indexOf("Lookup") + 2)
        assertEquals(0, f.wrapper.packageRequests.size)
    }

    fun testCapturedOriginExpiresOnDirtyEditAndDoesNotReviveAfterNativeUndo() = fixture { f ->
        f.withCapturedOrigin { _, origin ->
            assertTrue(origin.isValid)
            edt {
                WriteCommandAction.runWriteCommandAction(project) { f.documents.getValue("types.go").insertString(0, "// unsaved\n") }
                PsiDocumentManager.getInstance(project).commitDocument(f.documents.getValue("types.go"))
            }
            assertFalse("A captured popup target must not follow a different document version", origin.isValid)
            assertNull(origin.parent)
            edt {
                val nativeEditor = TextEditorProvider.getInstance().getTextEditor(f.editor)
                assertTrue(UndoManager.getInstance(project).isUndoAvailable(nativeEditor))
                UndoManager.getInstance(project).undo(nativeEditor)
                PsiDocumentManager.getInstance(project).commitDocument(f.documents.getValue("types.go"))
            }
            assertEquals(f.source, f.documents.getValue("types.go").text)
            assertFalse("Undo restores text but does not revive an earlier captured version", origin.isValid)
            assertNull(origin.parent)
        }
        f.selectPopup("Lookup", f.source.indexOf("Lookup") + 2)
    }

    fun testCapturedOriginExpiresWhenItsModelOrEditorIsDisposed() = fixture { f ->
        f.withCapturedOrigin { model, origin ->
            assertTrue(origin.isValid)
            edt { Disposer.dispose(model) }
            assertFalse(origin.isValid)
            assertNull(origin.parent)
        }
        f.withCapturedOrigin { _, origin ->
            assertTrue(origin.isValid)
            edt { EditorFactory.getInstance().releaseEditor(f.editor) }
            assertFalse(origin.isValid)
            assertNull(origin.parent)
        }
    }

    private inner class Fixture(val files: Map<String, PsiFile>, val documents: Map<String, Document>,
                                val editor: Editor, val wrapper: OwnedWrapper) {
        val file get() = files.getValue("types.go")
        val source get() = SOURCES.getValue("types.go")

        fun warmDocumentSymbols() {
            val future = read { LSPFileSupport.getSupport(file).documentSymbolSupport.getDocumentSymbols(DocumentSymbolParams(TextDocumentIdentifier(file.virtualFile.url))) }
            val symbols = future.get(10, TimeUnit.SECONDS)
            assertTrue(symbols.any { it.documentSymbol.name == "(*Record).Lookup" })
        }

        fun withCapturedOrigin(block: (GoStructureViewModel, PsiElement) -> Unit) {
            var model: GoStructureViewModel? = null
            var popup: FileStructurePopup? = null
            try {
                edt {
                    editor.caretModel.moveToOffset(source.indexOf("Lookup") + 2)
                    model = GoStructureViewModel(file, editor)
                    popup = FileStructurePopup(project, TextEditorProvider.getInstance().getTextEditor(editor), requireNotNull(model), null)
                }
                val origin = FileStructurePopup::class.java.getDeclaredField("myInitialElement").apply { isAccessible = true }.get(popup) as? PsiElement
                assertNotNull("The real popup constructor must capture a source target even before symbols load", origin)
                block(requireNotNull(model), requireNotNull(origin))
            } finally {
                edt {
                    popup?.let { if (!Disposer.isDisposed(it)) Disposer.dispose(it) }
                    model?.let { if (!Disposer.isDisposed(it)) Disposer.dispose(it) }
                }
            }
        }

        fun selectPopup(expectedName: String, offset: Int, warmPackage: Boolean = false) {
            var model: GoStructureViewModel? = null
            var popup: FileStructurePopup? = null
            val held = CompletableFuture<Any>()
            var initial: Any? = null
            var selected: TreePath? = null
            try {
                edt { editor.caretModel.moveToOffset(offset) }
                model = read { GoStructureViewModel(file, editor) }
                val root = model.root as GoPackageStructureRoot
                if (warmPackage) read { requireNotNull(root.support.getFeatureData(Unit)) }.get(10, TimeUnit.SECONDS)
                else wrapper.nextPackageReply = held
                edt {
                    popup = FileStructurePopup(project, TextEditorProvider.getInstance().getTextEditor(editor), requireNotNull(model), null)
                    requireNotNull(popup).createCenterPanel()
                    initial = FileStructurePopup::class.java.getDeclaredField("myInitialElement").apply { isAccessible = true }.get(popup)
                }
                val completion = CompletableFuture<TreePath?>()
                edt {
                    // This is exactly showWithResult's selection delegate. No native popup/window is opened.
                    val method = FileStructurePopup::class.java.getDeclaredMethod("rebuildAndSelect", Boolean::class.javaPrimitiveType,
                        Any::class.java, java.lang.Long::class.java).apply { isAccessible = true }
                    @Suppress("UNCHECKED_CAST")
                    val result = method.invoke(popup, false, initial, null) as Promise<TreePath?>
                    result.onSuccess { completion.complete(it) }.onError { completion.completeExceptionally(it) }
                }
                if (!warmPackage && root.isAvailable) {
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                    while (!root.support.validLSPFuture.let { it != null && !it.isDone } && System.nanoTime() < deadline) Thread.sleep(2)
                    assertNotNull("The real popup must start its own package load", root.support.validLSPFuture)
                    held.complete(wrapper.packageJson())
                }
                completion.get(15, TimeUnit.SECONDS)
                edt { selected = requireNotNull(popup).tree.selectionPath }
                val value = read { selected?.lastPathComponent?.let(StructureViewComponent::unwrapValue) as? DocumentSymbolData }
                assertNotNull("The actual native JTree must have a symbol selected; captured=$initial path=$selected", value)
                assertEquals("The native popup must select the method, not its receiver parent; captured=$initial path=$selected",
                    expectedName, value!!.documentSymbol.name)
                assertEquals(file.virtualFile, value.containingFile?.virtualFile)
                assertEquals(position(source, source.indexOf(expectedName.substringAfterLast('.'))), value.documentSymbol.selectionRange.start)
            } finally {
                held.complete(wrapper.packageJson())
                edt {
                    popup?.let { if (!Disposer.isDisposed(it)) Disposer.dispose(it) }
                    model?.let { if (!Disposer.isDisposed(it)) Disposer.dispose(it) }
                }
            }
        }
    }

    private fun fixture(generic: Boolean = false, block: (Fixture) -> Unit) {
        val directory = Files.createTempDirectory("open-goland-popup-selection-").toRealPath()
        val disposable = Disposer.newDisposable("owned popup source selection")
        VfsRootAccess.allowRootAccess(disposable, directory.toString())
        val accessor = LanguageServiceAccessor.getInstance(project)
        @Suppress("UNCHECKED_CAST")
        val started = LanguageServiceAccessor::class.java.getDeclaredField("startedServers").apply { isAccessible = true }
            .get(accessor) as MutableSet<LanguageServerWrapper>
        val previous = synchronized(started) { started.toList() }
        val definition = requireNotNull(LanguageServersRegistry.getInstance().getServerDefinition("openidea.gopls"))
        val wrapper = OwnedWrapper(project, definition, generic)
        var editor: Editor? = null
        try {
            val files = SOURCES.mapValues { (name, source) ->
                val path = directory.resolve(name); Files.writeString(path, source)
                val virtual = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path))
                read { requireNotNull(PsiManager.getInstance(project).findFile(virtual)) }
            }
            val documents = read { files.mapValues { requireNotNull(PsiDocumentManager.getInstance(project).getDocument(it.value)) } }
            wrapper.files = files
            synchronized(started) { started.clear(); started.add(wrapper) }
            read { files.values.forEach { LSPFileSupport.getSupport(it) } }
            edt { editor = EditorFactory.getInstance().createEditor(documents.getValue("types.go"), project) }
            block(Fixture(files, documents, requireNotNull(editor), wrapper))
        } finally {
            edt { editor?.takeUnless { it.isDisposed }?.let(EditorFactory.getInstance()::releaseEditor) }
            synchronized(started) { started.clear(); started.addAll(previous) }
            Disposer.dispose(wrapper); Disposer.dispose(disposable)
            directory.toFile().deleteRecursively(); LocalFileSystem.getInstance().refreshAndFindFileByNioFile(directory)
        }
    }

    private class OwnedWrapper(project: Project, definition: LanguageServerDefinition, generic: Boolean) : LanguageServerWrapper(project, definition) {
        lateinit var files: Map<String, PsiFile>
        val documentRequests = CopyOnWriteArrayList<String>()
        val packageRequests = CopyOnWriteArrayList<ExecuteCommandParams>()
        @Volatile var nextPackageReply: CompletableFuture<Any>? = null
        private val closed = AtomicBoolean()
        private val capabilities = ServerCapabilities().apply {
            setDocumentSymbolProvider(true)
            executeCommandProvider = ExecuteCommandOptions(if (generic) emptyList() else listOf(COMMAND))
        }
        private val features = (if (generic) LSPClientFeatures() else GoLanguageServerFactory().createClientFeatures()).also {
            it.setServerWrapper(this); it.setServerCapabilities(capabilities)
        }
        private val text = Proxy.newProxyInstance(TextDocumentService::class.java.classLoader, arrayOf(TextDocumentService::class.java)) { _, method, args ->
            when (method.name) {
                "documentSymbol" -> {
                    check(!ApplicationManager.getApplication().isDispatchThread) { "Document symbol RPC on EDT" }
                    val uri = (requireNotNull(args?.first()) as DocumentSymbolParams).textDocument.uri
                    val name = java.nio.file.Path.of(URI(uri)).fileName.toString()
                    documentRequests += name
                    CompletableFuture.completedFuture(symbols(name).map { Either.forRight<SymbolInformation, DocumentSymbol>(it) })
                }
                else -> null
            }
        } as TextDocumentService
        private val workspace = Proxy.newProxyInstance(WorkspaceService::class.java.classLoader, arrayOf(WorkspaceService::class.java)) { _, method, args ->
            if (method.name == "executeCommand") {
                check(!ApplicationManager.getApplication().isDispatchThread) { "Package RPC on EDT" }
                packageRequests += requireNotNull(args?.first()) as ExecuteCommandParams
                nextPackageReply?.also { nextPackageReply = null } ?: CompletableFuture.completedFuture(packageJson())
            } else null
        } as WorkspaceService
        private val remote = Proxy.newProxyInstance(LanguageServer::class.java.classLoader, arrayOf(LanguageServer::class.java)) { _, method, _ ->
            when (method.name) { "getTextDocumentService" -> text; "getWorkspaceService" -> workspace; else -> null }
        } as LanguageServer
        init {
            setField("serverCapabilities", capabilities); setField("initializeFuture", CompletableFuture.completedFuture<Void>(null))
            setField("languageServer", remote)
        }
        fun packageJson(): Any {
            fun row(symbol: DocumentSymbol, file: Int): Map<String, Any> = linkedMapOf<String, Any>("name" to symbol.name,
                "kind" to symbol.kind.value, "detail" to (symbol.detail ?: ""), "range" to symbol.range, "selectionRange" to symbol.selectionRange,
                "file" to file).apply { symbol.children?.let { put("children", it.map { child -> row(child, file) }) } }
            val record = symbols("types.go").first().apply {
                children = requireNotNull(children) + symbols("types.go").last().apply { name = "Lookup" }
            }
            val encoded = row(record, 0).toMutableMap()
            @Suppress("UNCHECKED_CAST")
            encoded["children"] = (encoded["children"] as List<Map<String, Any>>) + row(symbols("methods.go").single().apply { name = "Rename" }, 1)
            return Gson().toJsonTree(mapOf("PackageName" to "fixture", "Files" to listOf("types.go", "methods.go").map { files.getValue(it).virtualFile.url },
                "Symbols" to listOf(encoded)))
        }
        override fun start() {}
        override fun getInitializedServer(): CompletableFuture<LanguageServer> = CompletableFuture.completedFuture(remote)
        override fun getClientFeatures() = features
        override fun getServerStatus() = ServerStatus.started
        override fun isEnabled() = true
        override fun canOperate(file: VirtualFile) = true
        override fun getSyncOptions() = TextDocumentSyncOptions().apply { openClose = false; change = TextDocumentSyncKind.None }
        override fun dispose() {
            if (!closed.compareAndSet(false, true)) return
            openedDocuments.values.forEach { it.synchronizer?.let(Disposer::dispose) }
            openedDocuments.clear(); stopDispatcher(); features.dispose()
        }
        private fun setField(name: String, value: Any) = LanguageServerWrapper::class.java.getDeclaredField(name).apply { isAccessible = true }.set(this, value)
    }

    private fun <T> read(action: () -> T): T = ReadAction.compute<T, RuntimeException> { action() }
    private fun edt(action: () -> Unit) = EdtTestUtil.runInEdtAndWait<RuntimeException> { WriteIntentReadAction.run(Runnable(action)) }

    companion object {
        private const val COMMAND = "gopls.package_symbols"
        private val SOURCES = linkedMapOf("types.go" to "package fixture\ntype Record struct { Name string }\nvar _ = \"前😀\"\nfunc (r *Record) Lookup(value string) string {\n\treturn value\n}\n",
            "methods.go" to "package fixture\nfunc (r *Record) Rename(value string) {}\n")
        private fun position(source: String, offset: Int): Position = Position(source.take(offset).count { it == '\n' }, source.take(offset).substringAfterLast('\n').length)
        private fun symbols(file: String): List<DocumentSymbol> {
            val source = SOURCES.getValue(file)
            fun make(name: String, kind: SymbolKind, declarationStart: Int, end: Int, detail: String): DocumentSymbol {
                val selected = source.indexOf(name, declarationStart)
                return DocumentSymbol(name, kind, Range(position(source, declarationStart), position(source, end)),
                    Range(position(source, selected), position(source, selected + name.length))).apply { this.detail = detail }
            }
            if (file == "methods.go") return listOf(make("Rename", SymbolKind.Method, source.indexOf("func"), source.lastIndexOf('}') + 1, "func(value string)").apply { name = "(*Record).Rename" })
            val typeEnd = source.indexOf('}') + 1
            val record = make("Record", SymbolKind.Struct, source.indexOf("Record struct"), typeEnd, "struct{...}").apply {
                children = listOf(make("Name", SymbolKind.Field, source.indexOf("Name string"), source.indexOf("Name string") + "Name string".length, "string"))
            }
            val lookup = make("Lookup", SymbolKind.Method, source.indexOf("func"), source.lastIndexOf('}') + 1, "func(value string) string").apply { name = "(*Record).Lookup" }
            return listOf(record, lookup)
        }
    }
}
