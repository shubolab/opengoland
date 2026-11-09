package com.redhat.devtools.lsp4ij

import com.intellij.ide.hierarchy.HierarchyBrowserBaseEx
import com.intellij.ide.hierarchy.HierarchyBrowserManager
import com.intellij.ide.hierarchy.CallHierarchyBrowserBase
import com.intellij.ide.hierarchy.TypeHierarchyBrowserBase
import com.intellij.ide.hierarchy.LanguageCallHierarchy
import com.intellij.ide.hierarchy.LanguageTypeHierarchy
import com.intellij.ide.hierarchy.actions.BrowseHierarchyActionBase
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.actionSystem.impl.Utils
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
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
import com.intellij.testFramework.PlatformTestUtil
import com.redhat.devtools.lsp4ij.client.features.LSPClientFeatures
import com.redhat.devtools.lsp4ij.features.callHierarchy.LSPCallHierarchyBrowser
import com.redhat.devtools.lsp4ij.features.hierarchy.LSPHierarchyNodeDescriptor
import com.redhat.devtools.lsp4ij.features.typeHierarchy.LSPTypeHierarchyBrowser
import com.redhat.devtools.lsp4ij.server.definition.LanguageServerDefinition
import dev.openidea.goide.lang.GoLanguage
import dev.openidea.goide.navigation.GoTypeHierarchyProvider
import dev.openidea.goide.navigation.GoCallHierarchyProvider
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.services.LanguageServer
import org.eclipse.lsp4j.services.TextDocumentService
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath

/** Actual SDK hierarchy trees and registered Base On actions, with an owned in-memory server. */
class GoHierarchyWorkflowTest : LightPlatformTestCase() {
    override fun runInDispatchThread() = false

    fun testTypeBaseOnSelectedNodeUsesItsRealPositionThroughTheActualTreeContext() = typeBaseOn("")

    fun testTypeBaseOnAfterAnUnsavedUnicodePrefixUsesTheLiveTargetPosition() = typeBaseOn("// unsaved 前😀\n")

    private fun typeBaseOn(prefix: String) = fixture { f ->
        f.caret("Reader")
        val provider = requireNotNull(LanguageTypeHierarchy.INSTANCE.forLanguage(GoLanguage))
        val target = requireNotNull(read { provider.getTarget(f.editorContext()) })
        lateinit var browser: LSPTypeHierarchyBrowser
        edt { browser = BrowseHierarchyActionBase.createAndAddToPanel(project, provider, target) as LSPTypeHierarchyBrowser }
        val tree = f.select(browser, "Value")
        if (prefix.isNotEmpty()) f.write { f.editor.document.insertString(0, prefix) }
        f.wrapper.requests.clear()
        edt {
            val context = Utils.createAsyncDataContext(tree)
            assertSame("The context must come from the actual hierarchy component", browser, context.getData(HierarchyBrowserBaseEx.HIERARCHY_BROWSER))
            val selected = requireNotNull(context.getData(CommonDataKeys.PSI_ELEMENT))
            assertEquals("Value", selected.text)
            assertEquals(GoLanguage, selected.containingFile.language)
            val resolved = BrowseHierarchyActionBase.findProvider(LanguageTypeHierarchy.INSTANCE, selected, selected.containingFile, context)
            assertTrue("The actual selected Go item must choose the Go provider", resolved is GoTypeHierarchyProvider)
            assertEquals(GoLanguage, selected.language)
            println("TYPE_BASE_ON selectedLanguage=${selected.language.id} provider=${resolved?.javaClass?.name}")
            val action = requireNotNull(ActionManager.getInstance().getAction("TypeHierarchyBase.BaseOnThisType"))
            val event = AnActionEvent.createFromAnAction(action, null, ActionPlaces.TYPE_HIERARCHY_VIEW_POPUP, context)
            action.update(event)
            assertTrue("Base On This Type must be visible for a different selected node", event.presentation.isVisible)
            assertTrue("Base On This Type must be enabled for a different selected node", event.presentation.isEnabled)
            action.actionPerformed(event)
        }
        f.await("A new type base must issue a prepare request") { f.wrapper.requests.any { it.first == "prepareTypeHierarchy" } }
        val params = f.wrapper.requests.first { it.first == "prepareTypeHierarchy" }.second as TypeHierarchyPrepareParams
        assertEquals("Rebasing must prepare at the live Value identifier, not the start of its file", position(offset("Value") + prefix.length, f.editor.document.text), params.position)
        assertEquals("A native unsaved hierarchy edit must not save its document", SOURCE, Files.readString(java.nio.file.Path.of(f.file.virtualFile.path)))
    }

    fun testCallBaseOnSelectedNodeUsesItsRealPositionThroughTheActualTreeContext() = fixture { f ->
        f.caret("Leaf")
        val provider = requireNotNull(LanguageCallHierarchy.INSTANCE.forLanguage(GoLanguage))
        val target = requireNotNull(read { provider.getTarget(f.editorContext()) })
        lateinit var browser: LSPCallHierarchyBrowser
        edt { browser = BrowseHierarchyActionBase.createAndAddToPanel(project, provider, target) as LSPCallHierarchyBrowser }
        val tree = f.select(browser, "Caller")
        f.wrapper.requests.clear()
        edt {
            val context = Utils.createAsyncDataContext(tree)
            assertSame(browser, context.getData(HierarchyBrowserBaseEx.HIERARCHY_BROWSER))
            val selected = requireNotNull(context.getData(CommonDataKeys.PSI_ELEMENT))
            assertEquals("Caller", selected.text)
            val resolved = BrowseHierarchyActionBase.findProvider(LanguageCallHierarchy.INSTANCE, selected, selected.containingFile, context)
            assertTrue("The selected item must not be dispatched to the SDK ANY LSP provider", resolved is GoCallHierarchyProvider)
            assertEquals(GoLanguage, selected.language)
            println("CALL_BASE_ON selectedLanguage=${selected.language.id} provider=${resolved?.javaClass?.name}")
            val action = requireNotNull(ActionManager.getInstance().getAction("CallHierarchy.BaseOnThisMethod"))
            val event = AnActionEvent.createFromAnAction(action, null, ActionPlaces.CALL_HIERARCHY_VIEW_POPUP, context)
            action.update(event)
            assertTrue("Base On This Method must be visible for the selected caller", event.presentation.isVisible)
            assertTrue("Base On This Method must be enabled for the selected caller", event.presentation.isEnabled)
            action.actionPerformed(event)
        }
        f.await("A new call base must issue a prepare request") { f.wrapper.requests.any { it.first == "prepareCallHierarchy" } }
        val params = f.wrapper.requests.first { it.first == "prepareCallHierarchy" }.second as CallHierarchyPrepareParams
        assertEquals("Rebasing must prepare at the selected Caller identifier", position(offset("Caller")), params.position)
    }

    fun testHierarchyTargetsUseTheGoIdentifierAtTheCaretRatherThanThePrecedingWhitespace() = fixture { f ->
        val provider = requireNotNull(LanguageTypeHierarchy.INSTANCE.forLanguage(GoLanguage))
        val start = offset("Reader")
        for (caret in listOf(start, start + 1, start + "Reader".length)) {
            edt { f.editor.caretModel.moveToOffset(caret) }
            val target = requireNotNull(read { provider.getTarget(f.editorContext()) })
            assertEquals("Identifier start/middle/end must select the same Go name", "Reader", target.text)
            assertEquals(start, target.textRange.startOffset)
        }
        for (caret in listOf(SOURCE.indexOf("type") + 1, start - 1, SOURCE.indexOf("前😀") + 1, SOURCE.length)) {
            edt { f.editor.caretModel.moveToOffset(caret) }
            assertNull("Comments, strings, whitespace and EOF must not manufacture hierarchy roots", read { provider.getTarget(f.editorContext()) })
        }
        assertTrue("Target discovery must not start a language-server request", f.wrapper.requests.isEmpty())
    }

    fun testTheNativeCallBrowserSwitchesFromCallersToCalleesUsingTheSelectedBase() = fixture { f ->
        f.caret("Caller")
        val provider = requireNotNull(LanguageCallHierarchy.INSTANCE.forLanguage(GoLanguage))
        val target = requireNotNull(read { provider.getTarget(f.editorContext()) })
        lateinit var browser: LSPCallHierarchyBrowser
        edt {
            browser = BrowseHierarchyActionBase.createAndAddToPanel(project, provider, target) as LSPCallHierarchyBrowser
            browser.changeView(CallHierarchyBrowserBase.getCalleeType())
        }
        f.select(browser, "Leaf")
        val outgoing = f.wrapper.requests.filter { it.first == "callHierarchyOutgoingCalls" }
        assertFalse("The callee tree must consume the outgoing request, not a caller cache", outgoing.isEmpty())
        assertTrue(outgoing.any { (it.second as CallHierarchyOutgoingCallsParams).item.name == "Caller" })
    }

    fun testTheNativeTypeBrowserSwitchesToSupertypesUsingTheSelectedBase() = fixture { f ->
        f.caret("Value")
        val provider = requireNotNull(LanguageTypeHierarchy.INSTANCE.forLanguage(GoLanguage))
        val target = requireNotNull(read { provider.getTarget(f.editorContext()) })
        lateinit var browser: LSPTypeHierarchyBrowser
        edt {
            browser = BrowseHierarchyActionBase.createAndAddToPanel(project, provider, target) as LSPTypeHierarchyBrowser
            browser.changeView(TypeHierarchyBrowserBase.getSupertypesHierarchyType())
        }
        f.select(browser, "Reader")
        val supers = f.wrapper.requests.filter { it.first == "typeHierarchySupertypes" }
        assertFalse(supers.isEmpty())
        assertTrue(supers.any { (it.second as TypeHierarchySupertypesParams).item.name == "Value" })
    }

    fun testExpandingAnExistingDirtyCallNodeSendsBothLiveRangesAndPreservesOpaqueData() = fixture { f ->
        f.caret("Leaf")
        val provider = requireNotNull(LanguageCallHierarchy.INSTANCE.forLanguage(GoLanguage))
        val target = requireNotNull(read { provider.getTarget(f.editorContext()) })
        lateinit var browser: LSPCallHierarchyBrowser
        edt { browser = provider.createHierarchyBrowser(target) as LSPCallHierarchyBrowser }
        try {
            // A visible async tree may prefetch a node's children to decide its leaf
            // state. Use the very same SDK tree structure without a UI model here
            // so the dirty edit deterministically precedes the first expansion.
            val factory = browser.javaClass.getDeclaredMethod("createHierarchyTreeStructure", String::class.java, com.intellij.psi.PsiElement::class.java).apply { isAccessible = true }
            val structure = read { factory.invoke(browser, CallHierarchyBrowserBase.getCallerType(), target) as com.intellij.ide.hierarchy.HierarchyTreeStructure }
            val prepared = read { structure.getChildElements(structure.baseDescriptor).single() as LSPHierarchyNodeDescriptor<*> }
            val caller = read { structure.getChildElements(prepared).single() as LSPHierarchyNodeDescriptor<*> }
            val prefix = "/* dirty 前😀 */ "
            f.write { f.editor.document.insertString(SOURCE.indexOf("func Caller"), prefix) }
            f.wrapper.requests.clear()
            read { structure.getChildElements(caller) }
            val item = (f.wrapper.requests.single { it.first == "callHierarchyIncomingCalls" }.second as CallHierarchyIncomingCallsParams).item
            assertEquals(position(offset("Caller") + prefix.length, f.editor.document.text), item.selectionRange.start)
            assertEquals(position(SOURCE.indexOf("func Caller") + prefix.length, f.editor.document.text), item.range.start)
            assertTrue("Declaration and name ranges must remain distinct", item.range != item.selectionRange)
            assertEquals(mapOf("proof" to "Caller"), item.data)
            assertEquals("fixture", item.detail)
        } finally { edt { Disposer.dispose(browser) } }
    }

    fun testRecursiveCallNodesStopAtTheRepeatedAncestorAndRemainEquivalent() = cycle("Self", 3, 1)

    fun testMutuallyRecursiveCallNodesStopAtTheRepeatedAncestor() = cycle("Even", 4, 2)

    private fun cycle(name: String, depth: Int, incomingCount: Int) = fixture { f ->
        f.caret(name)
        val provider = requireNotNull(LanguageCallHierarchy.INSTANCE.forLanguage(GoLanguage))
        lateinit var browser: LSPCallHierarchyBrowser
        edt { browser = BrowseHierarchyActionBase.createAndAddToPanel(project, provider, requireNotNull(provider.getTarget(f.editorContext()))) as LSPCallHierarchyBrowser }
        val tree = f.select(browser, name, depth)
        edt {
            val context = Utils.createAsyncDataContext(tree)
            val repeated = (tree.selectionPath.lastPathComponent as DefaultMutableTreeNode).userObject as LSPHierarchyNodeDescriptor<*>
            assertTrue("A repeated ancestor must be a terminal native hierarchy node", requireNotNull(repeated.cachedChildren).isEmpty())
            assertTrue("The visible label must explain the terminal cycle", repeated.toString().contains("recursive"))
            val selected = requireNotNull(context.getData(CommonDataKeys.PSI_ELEMENT))
            var ancestor = repeated.parentDescriptor
            var equivalent = false
            while (ancestor != null) {
                if (ancestor is LSPHierarchyNodeDescriptor<*> && ancestor.hierarchyItem != null && ancestor.psiElement?.isEquivalentTo(selected) == true) equivalent = true
                ancestor = ancestor.parentDescriptor
            }
            assertTrue("Same symbol ancestors need semantic equivalence, not fake PSI identity", equivalent)
        }
        assertEquals(incomingCount, f.wrapper.requests.count { it.first == "callHierarchyIncomingCalls" })
    }

    fun testMutuallyCompatibleInterfacePathsTerminateAtTheRepeatedType() = fixture { f ->
        f.wrapper.typeCycle = true
        f.caret("Reader")
        val provider = requireNotNull(LanguageTypeHierarchy.INSTANCE.forLanguage(GoLanguage))
        lateinit var browser: LSPTypeHierarchyBrowser
        edt { browser = BrowseHierarchyActionBase.createAndAddToPanel(project, provider, requireNotNull(provider.getTarget(f.editorContext()))) as LSPTypeHierarchyBrowser }
        val tree = f.select(browser, "Reader", 4)
        edt {
            val repeated = (tree.selectionPath.lastPathComponent as DefaultMutableTreeNode).userObject as LSPHierarchyNodeDescriptor<*>
            assertTrue(requireNotNull(repeated.cachedChildren).isEmpty())
            assertTrue(repeated.toString().contains("recursive"))
        }
        assertEquals(2, f.wrapper.requests.count { it.first == "typeHierarchySubtypes" })
    }

    fun testDisposingTheBrowserDoesNotReviveAnInvalidatedNodeAtAnIdenticalReplacement() = fixture { f ->
        f.caret("Reader")
        val provider = requireNotNull(LanguageTypeHierarchy.INSTANCE.forLanguage(GoLanguage))
        lateinit var browser: LSPTypeHierarchyBrowser
        edt { browser = BrowseHierarchyActionBase.createAndAddToPanel(project, provider, requireNotNull(provider.getTarget(f.editorContext()))) as LSPTypeHierarchyBrowser }
        val tree = f.select(browser, "Value")
        lateinit var selected: com.intellij.psi.PsiElement
        edt { selected = requireNotNull(Utils.createAsyncDataContext(tree).getData(CommonDataKeys.PSI_ELEMENT)) }
        val start = SOURCE.indexOf("type Value")
        val end = SOURCE.indexOf('\n', start) + 1
        val replacement = SOURCE.substring(start, end)
        f.write {
            f.editor.document.deleteString(start, end)
            f.editor.document.insertString(start, replacement)
        }
        assertFalse("Deleting the actual target invalidates its old marker even if identical text is reinserted", read { selected.isValid })
        edt { Disposer.dispose(browser) }
        assertFalse("The Base On disposal handoff must not revive an invalid marker", read { selected.isValid })
    }

    private inner class Fixture(val file: PsiFile, val editor: Editor, val wrapper: OwnedWrapper) {
        fun caret(name: String) = edt { editor.caretModel.moveToOffset(offset(name) + 1) }
        fun write(action: () -> Unit) = edt {
            WriteCommandAction.runWriteCommandAction(project, Runnable(action))
            PsiDocumentManager.getInstance(project).commitDocument(editor.document)
        }
        fun editorContext(): DataContext = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project).add(CommonDataKeys.PSI_FILE, file)
            .add(CommonDataKeys.EDITOR, editor).build()

        fun select(browser: HierarchyBrowserBaseEx, name: String, minimumDepth: Int = 1): JTree {
            lateinit var tree: JTree
            val getter = HierarchyBrowserBaseEx::class.java.getDeclaredMethod("getCurrentTree").apply { isAccessible = true }
            await("The actual async hierarchy tree must load $name") {
                var path: TreePath? = null
                edt {
                    tree = getter.invoke(browser) as JTree
                    for (row in 0 until tree.rowCount) {
                        val candidate = tree.getPathForRow(row) ?: continue
                        val descriptor = (candidate.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? LSPHierarchyNodeDescriptor<*>
                        if (descriptor?.psiElement?.text != name || candidate.pathCount < minimumDepth) tree.expandRow(row)
                    }
                    path = (0 until tree.rowCount).mapNotNull(tree::getPathForRow).firstOrNull {
                        val node = it.lastPathComponent as? DefaultMutableTreeNode
                        val descriptor = node?.userObject as? LSPHierarchyNodeDescriptor<*>
                        descriptor?.psiElement?.text == name && it.pathCount >= minimumDepth
                    }
                    path?.let { tree.selectionPath = it }
                }
                path != null
            }
            return tree
        }

        fun await(message: String, condition: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12)
            while (!condition() && System.nanoTime() < deadline) {
                edt { PlatformTestUtil.dispatchAllEventsInIdeEventQueue() }
                Thread.sleep(10)
            }
            assertTrue(message + "; requests=" + wrapper.requests.map { it.first }, condition())
        }
    }

    private fun fixture(block: (Fixture) -> Unit) {
        val root = Files.createTempDirectory("open-goland-hierarchy-").toRealPath()
        val disposable = Disposer.newDisposable("owned hierarchy workflow")
        VfsRootAccess.allowRootAccess(disposable, root.toString())
        val explicit = System.getProperty("openidea.testGoHierarchyProviders") == "true"
        val typeProvider = GoTypeHierarchyProvider()
        val callProvider = GoCallHierarchyProvider()
        if (explicit) {
            LanguageTypeHierarchy.INSTANCE.addExplicitExtension(GoLanguage, typeProvider)
            LanguageCallHierarchy.INSTANCE.addExplicitExtension(GoLanguage, callProvider)
        }
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
            edt { native = EditorFactory.getInstance().createEditor(document, project); editor = native; HierarchyBrowserManager.getInstance(project) }
            edt { PlatformTestUtil.dispatchAllEventsInIdeEventQueue() }
            block(Fixture(file, native, wrapper))
        } finally {
            edt {
                HierarchyBrowserManager.getInstance(project).contentManager?.removeAllContents(true)
                editor?.takeUnless { it.isDisposed }?.let(EditorFactory.getInstance()::releaseEditor)
            }
            synchronized(started) { started.clear(); started.addAll(previous) }
            Disposer.dispose(wrapper)
            if (explicit) {
                LanguageTypeHierarchy.INSTANCE.removeExplicitExtension(GoLanguage, typeProvider)
                LanguageCallHierarchy.INSTANCE.removeExplicitExtension(GoLanguage, callProvider)
            }
            Disposer.dispose(disposable)
            root.toFile().deleteRecursively()
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root)
        }
    }

    private class OwnedWrapper(project: Project, definition: LanguageServerDefinition) : LanguageServerWrapper(project, definition) {
        lateinit var uri: String
        var typeCycle = false
        val requests = CopyOnWriteArrayList<Pair<String, Any>>()
        private val features = LSPClientFeatures().also { it.setServerWrapper(this) }
        private val capabilities = ServerCapabilities().apply { setTypeHierarchyProvider(true); setCallHierarchyProvider(true) }
        private val text = Proxy.newProxyInstance(TextDocumentService::class.java.classLoader, arrayOf(TextDocumentService::class.java)) { _, method, args ->
            val params = args?.firstOrNull()
            if (method.name in setOf("prepareTypeHierarchy", "typeHierarchySubtypes", "typeHierarchySupertypes", "prepareCallHierarchy", "callHierarchyIncomingCalls", "callHierarchyOutgoingCalls")) {
                check(!ApplicationManager.getApplication().isDispatchThread) { "Hierarchy RPC must run off the event dispatch thread" }
            }
            when (method.name) {
                "prepareTypeHierarchy" -> {
                    requests += method.name to requireNotNull(params)
                    val name = if ((params as TypeHierarchyPrepareParams).position == position(offset("Reader"))) "Reader"
                        else if (params.position == position(offset("Value"))) "Value" else null
                    CompletableFuture.completedFuture(name?.let { listOf(type(it)) } ?: emptyList<TypeHierarchyItem>())
                }
                "typeHierarchySubtypes" -> {
                    requests += method.name to requireNotNull(params)
                    val name = (params as TypeHierarchySubtypesParams).item.name
                    CompletableFuture.completedFuture(when {
                        name == "Reader" -> listOf(type(if (typeCycle) "Mirror" else "Value"))
                        name == "Mirror" && typeCycle -> listOf(type("Reader"))
                        else -> emptyList()
                    })
                }
                "typeHierarchySupertypes" -> {
                    requests += method.name to requireNotNull(params)
                    CompletableFuture.completedFuture(if ((params as TypeHierarchySupertypesParams).item.name == "Value") listOf(type("Reader")) else emptyList())
                }
                "prepareCallHierarchy" -> {
                    requests += method.name to requireNotNull(params)
                    val name = listOf("Leaf", "Caller", "Self", "Even", "Odd").find {
                        (params as CallHierarchyPrepareParams).position == position(offset(it))
                    }
                    CompletableFuture.completedFuture(name?.let { listOf(call(it)) } ?: emptyList<CallHierarchyItem>())
                }
                "callHierarchyIncomingCalls" -> {
                    requests += method.name to requireNotNull(params)
                    val caller = when ((params as CallHierarchyIncomingCallsParams).item.name) {
                        "Leaf" -> "Caller"; "Self" -> "Self"; "Even" -> "Odd"; "Odd" -> "Even"; else -> null
                    }
                    val values = caller?.let { listOf(CallHierarchyIncomingCall(call(it), listOf(range(it)))) } ?: emptyList()
                    CompletableFuture.completedFuture(values)
                }
                "callHierarchyOutgoingCalls" -> {
                    requests += method.name to requireNotNull(params)
                    val values = if ((params as CallHierarchyOutgoingCallsParams).item.name == "Caller")
                        listOf(CallHierarchyOutgoingCall(call("Leaf"), listOf(range("Leaf")))) else emptyList()
                    CompletableFuture.completedFuture(values)
                }
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
        private fun type(name: String) = TypeHierarchyItem(name, if (name == "Value") SymbolKind.Class else SymbolKind.Interface, uri, declaration(name), range(name)).apply { detail = "fixture"; data = mapOf("proof" to name) }
        private fun call(name: String) = CallHierarchyItem(name, SymbolKind.Function, uri, declaration(name), range(name)).apply { detail = "fixture"; data = mapOf("proof" to name) }
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
        private fun setField(name: String, value: Any) = LanguageServerWrapper::class.java.getDeclaredField(name).apply { isAccessible = true }.set(this, value)
    }

    private fun <T> read(action: () -> T): T = ReadAction.compute<T, RuntimeException> { action() }
    private fun edt(action: () -> Unit) = EdtTestUtil.runInEdtAndWait<RuntimeException> { WriteIntentReadAction.run(Runnable(action)) }

    companion object {
        private const val SOURCE = "package fixture\n\ntype  Reader interface { Read() string }\ntype Mirror interface { Read() string }\ntype Value struct{}\nfunc (Value) Read() string { return \"前😀\" }\nfunc Leaf() {}\nfunc Caller() { Leaf() }\nfunc Self() { Self() }\nfunc Even() { Odd() }\nfunc Odd() { Even() }\n"
        private fun offset(name: String) = when (name) {
            "Reader", "Value", "Mirror" -> SOURCE.indexOf("type " + if (name == "Reader") " Reader" else name) + if (name == "Reader") 6 else 5
            else -> SOURCE.indexOf("func $name") + 5
        }
        private fun range(name: String) = Range(position(offset(name)), position(offset(name) + name.length))
        private fun declaration(name: String): Range {
            val start = SOURCE.lastIndexOf('\n', offset(name)) + 1
            val end = SOURCE.indexOf('\n', offset(name)).let { if (it < 0) SOURCE.length else it }
            return Range(position(start), position(end))
        }
        private fun position(offset: Int, text: String = SOURCE): Position {
            val prefix = text.substring(0, offset)
            return Position(prefix.count { it == '\n' }, prefix.substringAfterLast('\n').length)
        }
    }
}
