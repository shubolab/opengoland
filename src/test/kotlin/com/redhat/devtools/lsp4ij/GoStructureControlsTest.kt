package com.redhat.devtools.lsp4ij

import com.intellij.ide.structureView.StructureViewModelBase
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.newStructureView.TreeActionWrapper
import com.intellij.ide.structureView.newStructureView.TreeActionsOwner
import com.intellij.ide.structureView.newStructureView.TreeModelWrapper
import com.intellij.ide.util.FileStructureFilter
import com.intellij.ide.util.FileStructurePopup
import com.intellij.ide.util.PropertiesComponent
import com.intellij.ide.util.treeView.smartTree.*
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.testFramework.EdtTestUtil
import com.intellij.testFramework.LightPlatformTestCase
import com.intellij.ui.components.JBCheckBox
import com.redhat.devtools.lsp4ij.client.features.LSPClientFeatures
import com.redhat.devtools.lsp4ij.features.documentSymbol.DocumentSymbolData
import com.redhat.devtools.lsp4ij.server.definition.LanguageServerDefinition
import dev.openidea.goide.lang.GoLanguage
import dev.openidea.goide.lsp.GoStructureControls
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.services.LanguageServer
import java.awt.Component
import java.awt.Container
import java.lang.reflect.Proxy
import java.util.concurrent.CompletableFuture

/** Actual SDK action/filter/sorter pipeline and the File Structure checkbox host. */
class GoStructureControlsTest : LightPlatformTestCase() {
    override fun runInDispatchThread() = false

    fun testNativePrivateActionHidesOnlyKnownUnexportedSymbolsAndKeepsUnknownContainers() = fixture { f ->
        val values = listOf(f.symbol("privateFunction"), f.symbol("PublicFunction"), f.symbol("(*PublicType).privateMethod", SymbolKind.Method),
            f.symbol("(*privateType).PublicMethod", SymbolKind.Method), f.symbol("privateField", SymbolKind.Field),
            f.symbol("PublicField", SymbolKind.Field), f.symbol("unknown-constraint", SymbolKind.Field), f.unknown("container"))
        val owner = Actions()
        val filter = GoStructureControls.filters().single()
        val action = TreeActionWrapper(filter, owner)
        edt {
            val event = event(action)
            assertTrue("The native reverted action starts with private members visible", action.isSelected(event))
            action.setSelected(event, false)
            assertFalse(action.isSelected(event))
        }
        assertEquals(setOf(GoStructureControls.SHOW_PRIVATE_ID), owner.active)
        assertEquals(listOf("PublicFunction", "(*privateType).PublicMethod", "PublicField", "unknown-constraint", "container"), f.names(values, owner))
        edt { action.setSelected(event(action), true) }
        assertEquals(values.map { it.presentation.presentableText }, f.names(values, owner))
    }

    fun testGoExportabilityUsesUnicodeCodePointsAndLuRatherThanJavaOtherUppercase() = fixture { f ->
        val values = listOf(f.symbol("𐐀Exported"), f.symbol("𐐨private"), f.symbol("Ωmega"), f.symbol("名称"),
            f.symbol("ǅtitlecase"), f.symbol("_Private"), f.symbol("ⅣOtherUppercase"), f.symbol("namespace", SymbolKind.Namespace))
        val filter = GoStructureControls.filters().single()
        assertTrue(Character.isUpperCase("Ⅳ".codePointAt(0)))
        assertEquals(listOf("𐐀Exported", "Ωmega", "ⅣOtherUppercase", "namespace"), values.filter { filter.isVisible(it) }.map { it.presentation.presentableText })
        val sorter = GoStructureControls.sorters().first()
        @Suppress("UNCHECKED_CAST") val comparator = sorter.comparator as Comparator<TreeElement>
        assertTrue("A non-Lu Other_Uppercase symbol must not be classified as an exported Go declaration",
            comparator.compare(f.symbol("Zed"), f.symbol("ⅣOtherUppercase")) < 0)
    }

    fun testNativeSmartTreeAppliesExportPriorityThenAlphabeticalWhileUnsortedTiesKeepSourceOrder() = fixture { f ->
        val values = listOf(f.symbol("zPrivate"), f.symbol("Zed"), f.unknown("container"), f.symbol("Alpha"), f.symbol("aPrivate"))
        val owner = Actions()
        assertEquals(listOf("zPrivate", "Zed", "container", "Alpha", "aPrivate"), f.names(values, owner))
        val export = TreeActionWrapper(GoStructureControls.sorters().first(), owner)
        edt { export.setSelected(event(export), true) }
        assertEquals(listOf("Zed", "Alpha", "container", "zPrivate", "aPrivate"), f.names(values, owner))
        val alphabetical = TreeActionWrapper(GoStructureControls.sorters().last(), owner)
        edt { alphabetical.setSelected(event(alphabetical), true) }
        assertEquals(listOf("Alpha", "Zed", "container", "aPrivate", "zPrivate"), f.names(values, owner))
        edt { export.setSelected(event(export), false) }
        assertEquals(listOf("Alpha", "aPrivate", "container", "Zed", "zPrivate"), f.names(values, owner))
    }

    fun testUnknownNodesRemainVisibleAndTheExportComparatorHasATransitiveTotalRank() = fixture { f ->
        val values = listOf(f.symbol("Public"), f.symbol("private"), f.unknown("container"),
            f.symbol("unknown-constraint", SymbolKind.Field), f.symbol("T", SymbolKind.TypeParameter))
        val filter = GoStructureControls.filters().single()
        assertTrue(values.drop(2).all { filter.isVisible(it) })
        @Suppress("UNCHECKED_CAST") val comparator = GoStructureControls.sorters().first().comparator as Comparator<TreeElement>
        for (a in values) for (b in values) for (c in values) {
            val ab = comparator.compare(a, b)
            val bc = comparator.compare(b, c)
            if (ab <= 0 && bc <= 0) assertTrue("Unknowns cannot compare equal to both visibility ranks", comparator.compare(a, c) <= 0)
            assertEquals(-Integer.signum(comparator.compare(b, a)), Integer.signum(ab))
        }
    }

    fun testActualFileStructurePopupShowsAndPersistsThePrivateMembersCheckboxWithCorrectReversal() = fixture { f ->
        val property = TreeStructureUtil.getPropertyName(GoStructureControls.SHOW_PRIVATE_ID)
        val props = PropertiesComponent.getInstance()
        val previous = props.getValue(property)
        var first: FileStructurePopup? = null
        var second: FileStructurePopup? = null
        try {
            edt {
                props.unsetValue(property)
                first = f.popup()
                val box = checkbox(requireNotNull(first).createCenterPanel())
                assertTrue("Preserve the existing all-members view as our independent default", box.isSelected)
                box.doClick()
                assertFalse(box.isSelected)
                assertEquals("false", props.getValue(property))
                val model = FileStructurePopup::class.java.getDeclaredField("myTreeModelWrapper").apply { isAccessible = true }
                    .get(first) as TreeModelWrapper
                assertEquals("Unchecked Show Private Members applies the hide-private filter", 1, model.filters.size)
                Disposer.dispose(requireNotNull(first)); first = null
                second = f.popup()
                val reopened = checkbox(requireNotNull(second).createCenterPanel())
                assertFalse("Native popup persistence restores the user's choice", reopened.isSelected)
                reopened.doClick()
                assertTrue(reopened.isSelected)
            }
        } finally {
            edt {
                first?.let(Disposer::dispose); second?.let(Disposer::dispose)
                if (previous == null) props.unsetValue(property) else props.setValue(property, previous)
            }
        }
    }

    fun testOnlyTheGoReferenceControlsAreExposedWithStableNativeActionIds() {
        val filter = GoStructureControls.filters().single()
        assertTrue(filter is FileStructureFilter)
        assertTrue(filter is TreeActionWithDefaultState)
        assertTrue(filter.isReverted)
        assertEquals("Show Private Members", (filter as FileStructureFilter).checkBoxText)
        assertEquals(0, filter.shortcut.size)
        val sorters = GoStructureControls.sorters()
        assertEquals(listOf(GoStructureControls.EXPORTABILITY_ID, Sorter.getAlphaSorterId()), sorters.map { it.name })
        assertEquals("By Exportability", sorters.first().presentation.text)
        assertSame(Sorter.ALPHA_SORTER, sorters.last())
        assertFalse("Do not import the unrelated LSP Hide Kind menu into the Go model", filter.name.contains("FIELDS"))
    }

    private inner class Fixture(val file: PsiFile, val editor: Editor, val wrapper: OwnedWrapper, val item: LanguageServerItem) {
        fun symbol(name: String, kind: SymbolKind = SymbolKind.Function): StructureViewTreeElement {
            val range = Range(Position(0, 0), Position(0, 1))
            val data = DocumentSymbolData(DocumentSymbol(name, kind, range, range), file, item)
            return requireNotNull(wrapper.features.documentSymbolFeature.getStructureViewTreeElement(data))
        }
        fun unknown(name: String): StructureViewTreeElement = Node(name, "unknown")
        fun model(values: List<StructureViewTreeElement>) = object : StructureViewModelBase(file, editor, Node("root", file, values)) {
            override fun getFilters() = GoStructureControls.filters()
            override fun getSorters() = GoStructureControls.sorters()
        }
        fun names(values: List<StructureViewTreeElement>, owner: Actions): List<String?> {
            val model = model(values)
            val wrapped = TreeModelWrapper(model, owner)
            return try {
                read {
                    val tree = SmartTreeStructure(project, wrapped)
                    tree.getChildElements(tree.rootElement).map { node ->
                        val element = (node as TreeElementWrapper).value
                        element.presentation.presentableText
                    }
                }
            } finally { edt { Disposer.dispose(wrapped) } }
        }
        fun popup() = FileStructurePopup(project, TextEditorProvider.getInstance().getTextEditor(editor),
            model(listOf(symbol("private"), symbol("Public"))), null)
    }

    private class Node(private val label: String, private val data: Any, private val children: List<StructureViewTreeElement> = emptyList()) : StructureViewTreeElement {
        override fun getValue() = data
        override fun getPresentation(): ItemPresentation = object : ItemPresentation {
            override fun getPresentableText() = label
            override fun getIcon(unused: Boolean) = null
        }
        override fun getChildren(): Array<TreeElement> = children.toTypedArray()
        override fun navigate(requestFocus: Boolean) {}
        override fun canNavigate() = false
        override fun canNavigateToSource() = false
    }

    private class Actions : TreeActionsOwner {
        val active = mutableSetOf<String>()
        override fun setActionActive(name: String, state: Boolean) { if (state) active += name else active -= name }
        override fun isActionActive(name: String) = name in active
    }

    private fun fixture(block: (Fixture) -> Unit) {
        val file = read { PsiFileFactory.getInstance(project).createFileFromText("controls.go", GoLanguage, "package controls\n") }
        val document = read { requireNotNull(PsiDocumentManager.getInstance(project).getDocument(file)) }
        val definition = requireNotNull(LanguageServersRegistry.getInstance().getServerDefinition("openidea.gopls"))
        val wrapper = OwnedWrapper(project, definition)
        val item = LanguageServerItem(wrapper.remote, wrapper)
        lateinit var editor: Editor
        edt { editor = EditorFactory.getInstance().createEditor(document, project) }
        try { block(Fixture(file, editor, wrapper, item)) }
        finally { edt { if (!editor.isDisposed) EditorFactory.getInstance().releaseEditor(editor) }; Disposer.dispose(wrapper) }
    }

    private class OwnedWrapper(project: Project, definition: LanguageServerDefinition) : LanguageServerWrapper(project, definition) {
        val features = LSPClientFeatures().also { it.setServerWrapper(this) }
        val remote = Proxy.newProxyInstance(LanguageServer::class.java.classLoader, arrayOf(LanguageServer::class.java)) { _, _, _ -> null } as LanguageServer
        override fun start() {}
        override fun getInitializedServer(): CompletableFuture<LanguageServer> = CompletableFuture.completedFuture(remote)
        override fun getClientFeatures() = features
        override fun dispose() { stopDispatcher(); features.dispose() }
    }

    private fun checkbox(root: Component): JBCheckBox {
        fun walk(component: Component): Sequence<Component> = sequence {
            yield(component)
            if (component is Container) for (child in component.components) yieldAll(walk(child))
        }
        return walk(root).filterIsInstance<JBCheckBox>().single { it.text.startsWith("Show Private Members") }
    }
    private fun event(action: TreeActionWrapper) = AnActionEvent.createFromAnAction(action, null, ActionPlaces.STRUCTURE_VIEW_POPUP, DataContext.EMPTY_CONTEXT)
    private fun <T> read(action: () -> T): T = ReadAction.compute<T, RuntimeException> { action() }
    private fun edt(action: () -> Unit) = EdtTestUtil.runInEdtAndWait<RuntimeException> { WriteIntentReadAction.run(Runnable(action)) }
}
