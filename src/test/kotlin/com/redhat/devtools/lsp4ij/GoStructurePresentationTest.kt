package com.redhat.devtools.lsp4ij

import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.newStructureView.StructureViewComponent
import com.intellij.ide.util.treeView.NodeRenderer
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
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
import com.redhat.devtools.lsp4ij.features.documentSymbol.LSPDocumentSymbolStructureViewModel
import com.redhat.devtools.lsp4ij.server.definition.LanguageServerDefinition
import dev.openidea.goide.lsp.GoLanguageServerFactory
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.LanguageServer
import org.eclipse.lsp4j.services.TextDocumentService
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.Icon
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode

/** Actual LSP Structure model and the SDK wrapper/renderer used by File Structure. */
class GoStructurePresentationTest : LightPlatformTestCase() {
    override fun runInDispatchThread() = false

    fun testNativeStructureRendererShowsGoSignaturesAndNestedFieldTypes() = fixture { f ->
        val elements = f.elements()
        val function = elements.first { (it.value as DocumentSymbolData).documentSymbol.name == "Use" }
        val method = elements.first { (it.value as DocumentSymbolData).documentSymbol.name == "Box.Read" }
        val box = elements.first { it.presentation.presentableText == "Box" }
        val field = read { box.children.single() as StructureViewTreeElement }
        assertEquals("Use(s string) (int, error)", function.presentation.presentableText)
        assertEquals("Box.Read() string", method.presentation.presentableText)
        assertEquals("Name: string", field.presentation.presentableText)
        assertNull("The signature must not be duplicated as secondary text", function.presentation.locationString)
        assertTrue(f.render(function).first.contains("Use(s string) (int, error)"))
        assertTrue(f.render(method).first.contains("Box.Read() string"))
        assertTrue(f.render(field).first.contains("Name: string"))
        assertNull("A struct's synthetic body is not a displayed type suffix", box.presentation.locationString)
        assertFalse(f.render(box).first.contains("struct"))
        val number = elements.first { (it.value as DocumentSymbolData).documentSymbol.name == "Number" }
        assertEquals("Number: int64", number.presentation.presentableText)
        assertTrue(f.render(number).first.contains("Number: int64"))
        val reader = elements.first { it.presentation.presentableText == "Reader" }
        assertNull(reader.presentation.locationString)
        assertFalse(f.render(reader).first.contains("interface"))
        assertTrue(f.render(read { reader.children.single() as StructureViewTreeElement }).first.contains("Read() string"))
        for (element in listOf(function, method, box, field)) {
            val data = element.value as DocumentSymbolData
            assertEquals("Symbol kind icons must stay delegated to the original client feature",
                f.wrapper.features.documentSymbolFeature.getIcon(data.documentSymbol, f.file, false), data.getIcon(false))
            assertEquals("Keep the SDK's original visibility/icon decorations",
                LSPDocumentSymbolStructureViewModel.LSPDocumentSymbolViewElement(data).presentation.getIcon(false),
                element.presentation.getIcon(false))
            assertNotNull("The actual SDK renderer must receive the symbol icon", f.render(element).second)
        }
    }

    fun testNativeStructureNavigationKeepsTheSelectionRangeAfterUnicodeText() = fixture { f ->
        val function = f.elements().first { (it.value as DocumentSymbolData).documentSymbol.name == "Use" }
        val symbol = function.value as DocumentSymbolData
        val expected = SOURCE.indexOf("Use")
        assertEquals(expected, read { symbol.textOffset })
        assertEquals(expected, read { symbol.selectionTextRange!!.startOffset })
        assertTrue("The selection range differs from the full function declaration",
            read { requireNotNull(symbol.textRange).startOffset < expected })
        val stamp = f.document.modificationStamp
        edt {
            FileEditorManager.getInstance(project).openTextEditor(OpenFileDescriptor(project, f.file.virtualFile, 0), true)
            // Use the same SDK popup wrapper as the renderer, not a direct offset setter.
            val wrapper = StructureViewComponent.createWrapper(project, function, f.model)
            assertTrue(wrapper.canNavigate())
            wrapper.navigate(true)
        }
        edt {
            val editor = requireNotNull(FileEditorManager.getInstance(project).selectedTextEditor)
            assertEquals(expected, editor.caretModel.offset)
        }
        assertEquals(stamp, f.document.modificationStamp)
        assertEquals(SOURCE, Files.readString(f.path))
    }

    fun testDirtyDocumentSymbolsRefreshDetailsWithoutDisablingStableCaching() = fixture { f ->
        assertTrue(f.render(f.elements().first { (it.value as DocumentSymbolData).documentSymbol.name == "Use" }).first.contains("Use(s string)"))
        f.elements()
        assertEquals(1, f.wrapper.requestTexts.size)
        val changed = SOURCE.replace("Use(s string)", "Use(s []string)")
        edt {
            WriteCommandAction.runWriteCommandAction(project) { f.document.setText(changed) }
            PsiDocumentManager.getInstance(project).commitDocument(f.document)
        }
        val updated = f.elements().first { (it.value as DocumentSymbolData).documentSymbol.name == "Use" }
        assertTrue(f.render(updated).first.contains("Use(s []string)"))
        assertEquals(listOf(SOURCE, changed), f.wrapper.requestTexts.toList())
        f.elements()
        assertEquals("Repeated unchanged Structure reads retain the upstream document cache", 2, f.wrapper.requestTexts.size)
        assertEquals("Presentation must not save the native document", SOURCE, Files.readString(f.path))
    }

    fun testDefaultClientsKeepTheirExistingNameOnlyStructurePresentation() = fixture(go = false) { f ->
        val function = f.elements().first { it.presentation.presentableText == "Use" }
        assertNull(function.presentation.locationString)
        val rendered = f.render(function).first
        assertTrue(rendered.contains("Use"))
        assertFalse(rendered.contains("func(s string)"))
    }

    fun testNativeSecondaryPresentationRetainsUnknownDetailsAndHandlesMissingDetails() = fixture { f ->
        f.wrapper.unknownDetails = true
        val elements = f.elements()
        val function = elements.first { (it.value as DocumentSymbolData).documentSymbol.name == "Use" }
        assertEquals("Use", function.presentation.presentableText)
        assertEquals("unrecognized signature", function.presentation.locationString)
        assertTrue(f.render(function).first.contains("unrecognized signature"))
        val box = elements.first { (it.value as DocumentSymbolData).documentSymbol.name == "Box" }
        val field = read { box.children.single() as StructureViewTreeElement }
        assertEquals("Name", field.presentation.presentableText)
        assertNull(field.presentation.locationString)
        assertTrue(f.render(field).first.contains("Name"))
    }

    private inner class Fixture(val file: PsiFile, val document: Document, val path: java.nio.file.Path, val wrapper: OwnedWrapper) {
        val model = LSPDocumentSymbolStructureViewModel(file, null)
        fun elements(): List<StructureViewTreeElement> = read { model.root.children.map { it as StructureViewTreeElement } }
        fun render(element: StructureViewTreeElement): Pair<String, Icon?> {
            val node = StructureViewComponent.createWrapper(project, element, model)
            read { node.update() }
            var result: Pair<String, Icon?>? = null
            edt {
                val renderer = NodeRenderer()
                renderer.getTreeCellRendererComponent(JTree(), DefaultMutableTreeNode(node), false, false, true, 0, false)
                result = renderer.getCharSequence(false).toString() to renderer.icon
            }
            return requireNotNull(result)
        }
    }

    private fun fixture(go: Boolean = true, block: (Fixture) -> Unit) {
        val root = Files.createTempDirectory("open-goland-structure-presentation-").toRealPath()
        val disposable = Disposer.newDisposable("owned Structure presentation")
        VfsRootAccess.allowRootAccess(disposable, root.toString())
        val accessor = LanguageServiceAccessor.getInstance(project)
        @Suppress("UNCHECKED_CAST")
        val started = LanguageServiceAccessor::class.java.getDeclaredField("startedServers").apply { isAccessible = true }
            .get(accessor) as MutableSet<LanguageServerWrapper>
        val previous = synchronized(started) { started.toList() }
        val definition = requireNotNull(LanguageServersRegistry.getInstance().getServerDefinition("openidea.gopls"))
        val wrapper = OwnedWrapper(project, definition, if (go) GoLanguageServerFactory().createClientFeatures() else LSPClientFeatures())
        var fixture: Fixture? = null
        try {
            val path = root.resolve("source.go"); Files.writeString(path, SOURCE)
            val virtual = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path))
            val file = read { requireNotNull(PsiManager.getInstance(project).findFile(virtual)) }
            val document = read { requireNotNull(PsiDocumentManager.getInstance(project).getDocument(file)) }
            wrapper.document = document
            synchronized(started) { started.clear(); started.add(wrapper) }
            read { LSPFileSupport.getSupport(file) }
            fixture = Fixture(file, document, path, wrapper)
            block(fixture)
        } finally {
            edt {
                fixture?.let { FileEditorManager.getInstance(project).closeFile(it.file.virtualFile); Disposer.dispose(it.model) }
            }
            synchronized(started) { started.clear(); started.addAll(previous) }
            Disposer.dispose(wrapper)
            Disposer.dispose(disposable)
            root.toFile().deleteRecursively()
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root)
        }
    }

    private class OwnedWrapper(project: Project, definition: LanguageServerDefinition, val features: LSPClientFeatures) : LanguageServerWrapper(project, definition) {
        lateinit var document: Document
        var unknownDetails = false
        val requestTexts = CopyOnWriteArrayList<String>()
        private val capabilities = ServerCapabilities().apply { setDocumentSymbolProvider(true) }
        private val text = Proxy.newProxyInstance(TextDocumentService::class.java.classLoader, arrayOf(TextDocumentService::class.java)) { _, method, _ ->
            when (method.name) {
                "documentSymbol" -> {
                    val source = ReadAction.compute<String, RuntimeException> { document.text }
                    requestTexts += source
                    val symbols = symbols(source)
                    if (unknownDetails) {
                        symbols.first { it.name == "Use" }.detail = "unrecognized signature"
                        symbols.first { it.name == "Box" }.children.single().detail = null
                    }
                    CompletableFuture.completedFuture(symbols.map { Either.forRight<SymbolInformation, DocumentSymbol>(it) })
                }
                "didOpen", "didClose", "didChange" -> null
                else -> null
            }
        } as TextDocumentService
        private val remote = Proxy.newProxyInstance(LanguageServer::class.java.classLoader, arrayOf(LanguageServer::class.java)) { _, method, _ ->
            if (method.name == "getTextDocumentService") text else null
        } as LanguageServer
        init {
            features.setServerWrapper(this); features.setServerCapabilities(capabilities)
            setField("serverCapabilities", capabilities); setField("initializeFuture", CompletableFuture.completedFuture<Void>(null))
            setField("languageServer", remote)
        }
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

    private fun <T> read(action: () -> T): T = ReadAction.compute<T, RuntimeException> { action() }
    private fun edt(action: () -> Unit) = EdtTestUtil.runInEdtAndWait<RuntimeException> { WriteIntentReadAction.run(Runnable(action)) }
    companion object {
        private const val SOURCE = "package fixture\ntype Box struct { Name string }\ntype Number int64\ntype Reader interface { Read() string }\nvar _ = \"前😀\"; func Use(s string) (int, error) { return 0, nil }\nfunc (b Box) Read() string { return b.Name }\n"
        private fun position(text: String, offset: Int): Position {
            val prefix = text.substring(0, offset)
            return Position(prefix.count { it == '\n' }, prefix.substringAfterLast('\n').length)
        }
        private fun symbols(source: String): List<DocumentSymbol> {
            fun symbol(name: String, identifier: String, kind: SymbolKind, detail: String, start: Int): DocumentSymbol {
                val offset = source.indexOf(identifier, start)
                val end = source.indexOf('\n', offset).let { if (it < 0) source.length else it }
                return DocumentSymbol(name, kind, Range(position(source, start), position(source, end)),
                    Range(position(source, offset), position(source, offset + identifier.length))).apply { this.detail = detail }
            }
            val box = symbol("Box", "Box", SymbolKind.Struct, "struct", source.indexOf("type Box"))
            box.children = listOf(symbol("Name", "Name", SymbolKind.Field, "string", source.indexOf("Name string")))
            val use = symbol("Use", "Use", SymbolKind.Function,
                if (source.contains("Use(s []string)")) "func(s []string) (int, error)" else "func(s string) (int, error)", source.indexOf("func Use"))
            val method = symbol("Box.Read", "Read", SymbolKind.Method, "func() string", source.indexOf("func (b Box)"))
            val number = symbol("Number", "Number", SymbolKind.Class, "int64", source.indexOf("type Number"))
            val reader = symbol("Reader", "Reader", SymbolKind.Interface, "interface{...}", source.indexOf("type Reader"))
            reader.children = listOf(symbol("Read", "Read", SymbolKind.Method, "func() string", source.indexOf("Read() string")))
            return listOf(box, number, reader, use, method)
        }
    }
}
