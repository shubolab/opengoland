package com.redhat.devtools.lsp4ij

import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.StructureViewModel
import com.intellij.ide.structureView.newStructureView.StructureViewComponent
import com.intellij.ide.util.FileStructurePopup
import com.intellij.ide.util.treeView.NodeRenderer
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.ide.util.treeView.smartTree.TreeElementWrapper
import com.intellij.ide.structureView.newStructureView.TreeModelWrapper
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.project.Project
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
import com.redhat.devtools.lsp4ij.features.documentSymbol.LSPDocumentSymbolStructureViewFactory
import com.redhat.devtools.lsp4ij.server.definition.LanguageServerDefinition
import dev.openidea.goide.lsp.GoLanguageServerFactory
import dev.openidea.goide.lsp.GoStructureViewFactory
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.LanguageServer
import org.eclipse.lsp4j.services.TextDocumentService
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath

/** Real Structure factory/model, native popup state, renderer, tree selection and navigation. */
class GoStructureGroupingTest : LightPlatformTestCase() {
    override fun runInDispatchThread() = false

    fun testActualFileStructurePopupGroupsPointerGenericPrimitiveAndFunctionTypeMethods() = fixture { f ->
        val roots = f.roots()
        assertFalse(roots.any { name(it) == "(*Record).Lookup" })
        assertEquals(setOf("名字", "callback", "(*Record).Lookup"), children(f.type("Record")).map(::name).toSet())
        assertEquals("Lookup(key string) string", children(f.type("Record")).single { name(it).endsWith("Lookup") }.presentation.presentableText)
        assertEquals("Count() int64", children(f.type("Counter")).single().presentation.presentableText)
        assertEquals("ItemValue(key K) V", children(f.type("Table")).single().presentation.presentableText)
        assertEquals("Callback: func(int) string", f.type("Callback").presentation.presentableText)
        assertEquals("Text() string", children(f.type("Callback")).single().presentation.presentableText)
        assertEquals("读() string", children(f.type("盒子")).single().presentation.presentableText)
        // Exercise the actual popup's action-state wrapper, not all raw model filters.
        lateinit var popup: FileStructurePopup
        lateinit var wrapped: TreeModelWrapper
        edt {
            popup = FileStructurePopup(project, TextEditorProvider.getInstance().getTextEditor(f.editor), f.model, null)
            popup.createCenterPanel()
            wrapped = FileStructurePopup::class.java.getDeclaredField("myTreeModelWrapper").apply { isAccessible = true }.get(popup) as TreeModelWrapper
        }
        try {
            val root = StructureViewComponent.createWrapper(project, f.model.root, wrapped)
            val record = read { root.children.filterIsInstance<TreeElementWrapper>().single { name(it.value) == "Record" } }
            val method = read { record.children.filterIsInstance<TreeElementWrapper>().single { name(it.value).endsWith("Lookup") } }
            assertEquals("Lookup(key string) string", render(method))
            assertTrue("Native row text no longer repeats the receiver", !render(method).contains("(*Record)"))
            val original = f.wrapper.replies.single().first { it.name == "Record" }
            assertEquals("Grouping must not add methods to cached protocol DTOs", listOf("名字", "callback"), original.children.map { it.name })
            assertTrue(f.wrapper.replies.single().any { it.name == "(*Record).Lookup" })
        } finally { edt { Disposer.dispose(popup) } }
    }

    fun testGroupedMethodKeepsOriginalValueRangeIconAndNativeSelectionNavigation() = fixture { f ->
        val method = children(f.type("Record")).single { name(it).endsWith("Lookup") }
        val data = method.value as DocumentSymbolData
        val original = read { LSPFileSupport.getSupport(f.file).documentSymbolSupport.validLSPFuture!!.getNow(null).single { it.documentSymbol.name == "(*Record).Lookup" } }
        assertSame("Do not substitute fake grouped PSI or mutate the data parent", original, data)
        assertSame(f.file, data.parent)
        assertEquals(SOURCE.indexOf("Lookup"), data.textOffset)
        assertEquals(data.clientFeatures.documentSymbolFeature.getStructureViewTreeElement(data)!!.presentation.getIcon(false), method.presentation.getIcon(false))
        val stamp = f.document.modificationStamp
        edt {
            FileEditorManager.getInstance(project).openTextEditor(OpenFileDescriptor(project, f.file.virtualFile, 0), true)
            StructureViewComponent.createWrapper(project, method, f.model).navigate(true)
        }
        edt { assertEquals(SOURCE.indexOf("Lookup"), FileEditorManager.getInstance(project).selectedTextEditor!!.caretModel.offset) }
        assertEquals(stamp, f.document.modificationStamp)
        assertEquals(SOURCE, Files.readString(f.path))
    }

    fun testActualStructureTreeCanSelectTheOriginalMethodOutsideItsReceiverDeclaration() = fixture { f ->
        val method = children(f.type("Record")).single { name(it).endsWith("Lookup") }
        val data = method.value as DocumentSymbolData
        edt { f.editor.caretModel.moveToOffset(SOURCE.indexOf("Lookup") + 2) }
        val current = read { f.model.currentEditorElement }
        assertEquals(data, current)
        lateinit var view: StructureViewComponent
        val selected = AtomicReference<TreePath>()
        val error = AtomicReference<Throwable>()
        val completed = CountDownLatch(1)
        edt {
            view = StructureViewComponent(null, f.model, project, true)
            view.disableStoreState()
            view.select(current, false).onSuccess { selected.set(it); completed.countDown() }
                .onError { error.set(it); completed.countDown() }
        }
        try {
            assertTrue("The actual async tree must find the method through its grouped parent", completed.await(12, TimeUnit.SECONDS))
            error.get()?.let { throw AssertionError("Native tree selection failed", it) }
            val path = requireNotNull(selected.get())
            assertSame(data, StructureViewComponent.unwrapValue(path.lastPathComponent))
            assertTrue(path.path.any { (StructureViewComponent.unwrapValue(it) as? DocumentSymbolData)?.documentSymbol?.name == "Record" })
        } finally { edt { Disposer.dispose(view) } }
    }

    fun testDirtyRefreshAndNativeUndoRegroupWithoutChangingTheDocumentCachePolicy() = fixture { f ->
        f.type("Record")
        val count = f.wrapper.requestTexts.size
        f.roots()
        assertEquals(count, f.wrapper.requestTexts.size)
        val changed = SOURCE.replace("Lookup(", "Search(")
        edt {
            WriteCommandAction.runWriteCommandAction(project) { f.document.setText(changed) }
            PsiDocumentManager.getInstance(project).commitDocument(f.document)
        }
        assertTrue(children(f.type("Record")).any { name(it) == "(*Record).Search" })
        assertFalse(f.roots().any { name(it) == "(*Record).Search" })
        assertEquals(count + 1, f.wrapper.requestTexts.size)
        edt {
            val editor = TextEditorProvider.getInstance().getTextEditor(f.editor)
            assertTrue(UndoManager.getInstance(project).isUndoAvailable(editor))
            UndoManager.getInstance(project).undo(editor)
            PsiDocumentManager.getInstance(project).commitDocument(f.document)
        }
        assertEquals(SOURCE, f.document.text)
        assertTrue(children(f.type("Record")).any { name(it) == "(*Record).Lookup" })
        assertEquals(count + 2, f.wrapper.requestTexts.size)
        f.roots()
        assertEquals(count + 2, f.wrapper.requestTexts.size)
        assertEquals(SOURCE, Files.readString(f.path))
    }

    fun testAmbiguousAliasesMalformedAndOtherFileReceiversRemainVisible() = fixture { f ->
        f.wrapper.ambiguous = true
        val roots = f.roots()
        assertEquals(2, roots.count { name(it) == "Record" })
        val names = roots.map(::name)
        assertTrue("Ambiguous same-client type declarations cannot absorb a method", "(*Record).Lookup" in names)
        assertTrue("An alias is not guessed to be the receiver's canonical type", "(Alias).AliasMethod" in names)
        assertTrue("A declaration in another file must not cause the current method to disappear", "(Elsewhere).CrossFile" in names)
        for (name in MALFORMED) assertTrue("Malformed receiver form must stay visible: $name", name in names)
        assertTrue("Ordinary functions remain at the file root", "Spare" in names)
        assertTrue("An ordinary function cannot be mistaken for a named function type", "(Spare).Lookup" in names)
        assertEquals("Unambiguous receivers elsewhere in the same result still group", "Count() int64", children(f.type("Counter")).single().presentation.presentableText)
    }

    fun testInvalidUtf16SelectionCannotAbsorbOrLoseTheMethod() = fixture { f ->
        f.wrapper.invalidSelection = true
        val roots = f.roots()
        assertTrue(roots.any { name(it) == "(*Record).Lookup" })
        assertEquals(listOf("名字", "callback"), children(f.type("Record")).map(::name))
        assertEquals("Count() int64", children(f.type("Counter")).single().presentation.presentableText)
    }

    fun testGenericLspClientsKeepTheOriginalFlatResult() = fixture(go = false) { f ->
        val roots = f.roots()
        assertTrue(roots.any { name(it) == "(*Record).Lookup" })
        assertEquals(listOf("名字", "callback"), children(f.type("Record")).map(::name))
        assertEquals("Callback", f.type("Callback").presentation.presentableText)
        assertEquals(f.wrapper.replies.single().size, roots.size)
    }

    fun testColdAndWarmEditorPositionCallbacksNeverStartAnRpcOnTheEdt() = fixture { f ->
        assertTrue(f.wrapper.requestTexts.isEmpty())
        edt {
            f.editor.caretModel.moveToOffset(SOURCE.indexOf("Lookup") + 2)
            val origin = f.model.currentEditorElement as? com.intellij.psi.PsiElement
            assertNotNull("A cold callback supplies an inert source origin without waiting for a server", origin)
            assertTrue(origin!!.isValid)
            assertSame(f.file, origin.containingFile)
            assertFalse("The source origin is not a declaration to navigate", (origin as com.intellij.pom.Navigatable).canNavigate())
        }
        assertTrue(f.wrapper.requestTexts.isEmpty())
        f.roots()
        val requests = f.wrapper.requestTexts.size
        edt {
            val current = f.model.currentEditorElement as DocumentSymbolData
            assertEquals("(*Record).Lookup", current.documentSymbol.name)
        }
        assertEquals(requests, f.wrapper.requestTexts.size)
    }

    private inner class Fixture(val file: PsiFile, val document: Document, val path: java.nio.file.Path,
                                val editor: Editor, val model: StructureViewModel, val wrapper: OwnedWrapper) {
        fun roots() = read { model.root.children.filterIsInstance<StructureViewTreeElement>() }
        fun type(name: String) = roots().single { name(it) == name }
    }

    private fun fixture(go: Boolean = true, block: (Fixture) -> Unit) {
        val root = Files.createTempDirectory("open-goland-receiver-structure-").toRealPath()
        val disposable = Disposer.newDisposable("owned receiver Structure")
        VfsRootAccess.allowRootAccess(disposable, root.toString())
        val accessor = LanguageServiceAccessor.getInstance(project)
        @Suppress("UNCHECKED_CAST")
        val started = LanguageServiceAccessor::class.java.getDeclaredField("startedServers").apply { isAccessible = true }.get(accessor) as MutableSet<LanguageServerWrapper>
        val previous = synchronized(started) { started.toList() }
        val definition = requireNotNull(LanguageServersRegistry.getInstance().getServerDefinition("openidea.gopls"))
        val wrapper = OwnedWrapper(project, definition, if (go) GoLanguageServerFactory().createClientFeatures() else LSPClientFeatures())
        var editor: Editor? = null
        var model: StructureViewModel? = null
        var file: PsiFile? = null
        try {
            val path = root.resolve("source.go"); Files.writeString(path, SOURCE)
            Files.writeString(root.resolve("other.go"), "package fixture\ntype Elsewhere struct{}\n")
            val virtual = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path))
            file = read { requireNotNull(PsiManager.getInstance(project).findFile(virtual)) }
            val document = read { requireNotNull(PsiDocumentManager.getInstance(project).getDocument(file)) }
            wrapper.document = document
            synchronized(started) { started.clear(); started.add(wrapper) }
            read { LSPFileSupport.getSupport(file) }
            edt { editor = EditorFactory.getInstance().createEditor(document, project) }
            val builder = read {
                val factory = if (java.lang.Boolean.getBoolean("openidea.testUpstreamStructure")) LSPDocumentSymbolStructureViewFactory() else GoStructureViewFactory()
                factory.getStructureViewBuilder(file) as com.intellij.ide.structureView.TreeBasedStructureViewBuilder
            }
            model = read { builder.createStructureViewModel(editor) }
            block(Fixture(file, document, path, requireNotNull(editor), model, wrapper))
        } finally {
            edt {
                file?.let { FileEditorManager.getInstance(project).closeFile(it.virtualFile) }
                model?.let { if (!Disposer.isDisposed(it)) Disposer.dispose(it) }
                editor?.let { if (!it.isDisposed) EditorFactory.getInstance().releaseEditor(it) }
            }
            synchronized(started) { started.clear(); started.addAll(previous) }
            Disposer.dispose(wrapper); Disposer.dispose(disposable)
            root.toFile().deleteRecursively(); LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root)
        }
    }

    private class OwnedWrapper(project: Project, definition: LanguageServerDefinition, val features: LSPClientFeatures) : LanguageServerWrapper(project, definition) {
        lateinit var document: Document
        var ambiguous = false
        var invalidSelection = false
        val requestTexts = CopyOnWriteArrayList<String>()
        val replies = CopyOnWriteArrayList<List<DocumentSymbol>>()
        private val capabilities = ServerCapabilities().apply { setDocumentSymbolProvider(true) }
        private val text = Proxy.newProxyInstance(TextDocumentService::class.java.classLoader, arrayOf(TextDocumentService::class.java)) { _, method, _ ->
            when (method.name) {
                "documentSymbol" -> {
                    check(!ApplicationManager.getApplication().isDispatchThread) { "Structure RPC must not run on the EDT" }
                    val source = ReadAction.compute<String, RuntimeException> { document.text }
                    requestTexts += source
                    val symbols = symbols(source).toMutableList()
                    if (ambiguous) {
                        symbols += symbols.first { it.name == "Record" }
                        val original = symbols.first { it.name == "(*Record).Lookup" }
                        symbols += (MALFORMED + "(Spare).Lookup").map { DocumentSymbol(it, SymbolKind.Method, original.range, original.selectionRange).apply { detail = original.detail } }
                    }
                    if (invalidSelection) {
                        val middle = source.indexOf("😀") + 1
                        symbols.first { it.name == "(*Record).Lookup" }.selectionRange = Range(position(source, middle), position(source, middle + 1))
                    }
                    replies += symbols
                    CompletableFuture.completedFuture(symbols.map { Either.forRight<SymbolInformation, DocumentSymbol>(it) })
                }
                "didOpen", "didClose", "didChange" -> null
                else -> null
            }
        } as TextDocumentService
        private val remote = Proxy.newProxyInstance(LanguageServer::class.java.classLoader, arrayOf(LanguageServer::class.java)) { _, method, _ -> if (method.name == "getTextDocumentService") text else null } as LanguageServer
        init {
            features.setServerWrapper(this); features.setServerCapabilities(capabilities)
            setField("serverCapabilities", capabilities); setField("initializeFuture", CompletableFuture.completedFuture<Void>(null)); setField("languageServer", remote)
        }
        override fun start() {}
        override fun getInitializedServer(): CompletableFuture<LanguageServer> = CompletableFuture.completedFuture(remote)
        override fun getClientFeatures() = features
        override fun getServerStatus() = ServerStatus.started
        override fun isEnabled() = true
        override fun canOperate(file: VirtualFile) = true
        override fun getSyncOptions() = TextDocumentSyncOptions().apply { openClose = false; change = TextDocumentSyncKind.None }
        override fun dispose() { openedDocuments.values.forEach { it.synchronizer?.let(Disposer::dispose) }; openedDocuments.clear(); stopDispatcher(); features.dispose() }
        private fun setField(name: String, value: Any) { LanguageServerWrapper::class.java.getDeclaredField(name).apply { isAccessible = true }.set(this, value) }
    }

    private fun children(element: StructureViewTreeElement) = read { element.children.filterIsInstance<StructureViewTreeElement>() }
    private fun render(node: TreeElementWrapper): String {
        read { node.update() }
        var result = ""
        edt { val renderer = NodeRenderer(); renderer.getTreeCellRendererComponent(JTree(), DefaultMutableTreeNode(node), false, false, true, 0, false); result = renderer.getCharSequence(false).toString() }
        return result
    }
    private fun <T> read(action: () -> T): T = ReadAction.compute<T, RuntimeException> { action() }
    private fun edt(action: () -> Unit) = EdtTestUtil.runInEdtAndWait<RuntimeException> { WriteIntentReadAction.run(Runnable(action)) }
    companion object {
        private val MALFORMED = listOf("Record.Lookup", "(**Record).Lookup", "(Record[]).Lookup", "(pkg.Record).Lookup", "(Record[T, T]).Lookup", "(Record).Lookup.extra")
        private const val SOURCE = "package fixture\ntype Record struct {\n 名字 string\n callback Callback\n}\nvar _ = \"前😀\"; func (r *Record) Lookup(key string) string { return key }\ntype Counter int64\nfunc (c Counter) Count() int64 { return int64(c) }\ntype Table[K comparable, V any] map[K]V\nfunc (t *Table[K, V]) ItemValue(key K) V { return (*t)[key] }\ntype Callback func(int) string\nfunc (c Callback) Text() string { return c(0) }\ntype Alias = Record\nfunc (a Alias) AliasMethod() {}\nfunc (e Elsewhere) CrossFile() {}\nfunc Spare() {}\ntype 盒子 struct{}\nfunc (v *盒子) 读() string { return \"\" }\n"
        private fun name(element: TreeElement) = ((element as StructureViewTreeElement).value as DocumentSymbolData).documentSymbol.name
        private fun position(text: String, offset: Int): Position { val prefix = text.substring(0, offset); return Position(prefix.count { it == '\n' }, prefix.substringAfterLast('\n').length) }
        private fun symbols(source: String): List<DocumentSymbol> {
            fun type(name: String, kind: SymbolKind, detail: String): DocumentSymbol {
                val start = source.indexOf("type $name") + 5
                val end = if (name == "Record") source.indexOf('}', start) + 1 else source.indexOf('\n', start)
                return DocumentSymbol(name, kind, Range(position(source, start), position(source, end)), Range(position(source, start), position(source, start + name.length))).apply { this.detail = detail }
            }
            fun field(name: String, detail: String): DocumentSymbol {
                val start = source.indexOf(" $name ") + 1; val end = source.indexOf('\n', start)
                return DocumentSymbol(name, SymbolKind.Field, Range(position(source, start), position(source, end)), Range(position(source, start), position(source, start + name.length))).apply { this.detail = detail }
            }
            fun method(receiver: String, name: String, detail: String): DocumentSymbol {
                val start = source.indexOf("func (") .let { source.indexOf(") $name(", it) }.let { source.lastIndexOf("func (", it) }
                val selected = source.indexOf(") $name(", start) + 2; val end = source.indexOf('\n', start)
                return DocumentSymbol("($receiver).$name", SymbolKind.Method, Range(position(source, start), position(source, end)), Range(position(source, selected), position(source, selected + name.length))).apply { this.detail = detail }
            }
            val lookup = if (source.contains(" Lookup(")) "Lookup" else "Search"
            val record = type("Record", SymbolKind.Struct, "struct{...}").apply { children = listOf(field("名字", "string"), field("callback", "Callback")) }
            val spareStart = source.indexOf("func Spare"); val spareName = source.indexOf("Spare", spareStart)
            val spare = DocumentSymbol("Spare", SymbolKind.Function, Range(position(source, spareStart), position(source, source.indexOf('\n', spareStart))), Range(position(source, spareName), position(source, spareName + 5))).apply { detail = "func()" }
            // Deliberately put methods before declarations; never rely on backend source order.
            return listOf(method("*Record", lookup, "func(key string) string"), method("Counter", "Count", "func() int64"),
                record, type("Counter", SymbolKind.Class, "int64"), type("Table", SymbolKind.Class, "map[K]V"), method("*Table[K, V]", "ItemValue", "func(key K) V"),
                type("Callback", SymbolKind.Function, "func(int) string"), method("Callback", "Text", "func() string"), type("Alias", SymbolKind.Class, "Record"),
                method("Alias", "AliasMethod", "func()"), method("Elsewhere", "CrossFile", "func()"), spare, type("盒子", SymbolKind.Struct, "struct{}"), method("*盒子", "读", "func() string"))
        }
    }
}
