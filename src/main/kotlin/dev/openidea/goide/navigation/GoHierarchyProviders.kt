package dev.openidea.goide.navigation

import com.intellij.ide.hierarchy.CallHierarchyBrowserBase
import com.intellij.ide.hierarchy.HierarchyBrowser
import com.intellij.ide.hierarchy.HierarchyBrowserBaseEx
import com.intellij.ide.hierarchy.HierarchyNodeDescriptor
import com.intellij.ide.hierarchy.HierarchyProvider
import com.intellij.ide.hierarchy.HierarchyTreeStructure
import com.intellij.ide.hierarchy.TypeHierarchyBrowserBase
import com.intellij.ide.util.treeView.NodeDescriptor
import com.intellij.lang.Language
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.redhat.devtools.lsp4ij.LSPIJUtils
import com.redhat.devtools.lsp4ij.LanguageServersRegistry
import com.redhat.devtools.lsp4ij.client.indexing.ProjectIndexingManager
import com.redhat.devtools.lsp4ij.features.LSPPsiElement
import com.redhat.devtools.lsp4ij.features.callHierarchy.*
import com.redhat.devtools.lsp4ij.features.hierarchy.LSPHierarchyItemPsiElement
import com.redhat.devtools.lsp4ij.features.hierarchy.LSPHierarchyNodeDescriptor
import com.redhat.devtools.lsp4ij.features.typeHierarchy.*
import dev.openidea.goide.lang.GoLanguage
import dev.openidea.goide.lang.GoTokens
import org.eclipse.lsp4j.CallHierarchyItem
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.TypeHierarchyItem

/** Keeps native hierarchy actions on the Go/LSP4IJ path, including Base On This Node. */
class GoTypeHierarchyProvider : HierarchyProvider {
    override fun getTarget(dataContext: DataContext): PsiElement? = hierarchyTarget(dataContext)
    override fun createHierarchyBrowser(target: PsiElement): HierarchyBrowser {
        val nodes = GoHierarchyNodes()
        return GoTypeHierarchyBrowser(nodes.root(target), nodes)
    }
    override fun browserActivated(hierarchyBrowser: HierarchyBrowser) {
        (hierarchyBrowser as HierarchyBrowserBaseEx).changeView(TypeHierarchyBrowserBase.getSubtypesHierarchyType())
    }
}

class GoCallHierarchyProvider : HierarchyProvider {
    override fun getTarget(dataContext: DataContext): PsiElement? = hierarchyTarget(dataContext)
    override fun createHierarchyBrowser(target: PsiElement): HierarchyBrowser {
        val nodes = GoHierarchyNodes()
        return GoCallHierarchyBrowser(nodes.root(target), nodes)
    }
    override fun browserActivated(hierarchyBrowser: HierarchyBrowser) {
        (hierarchyBrowser as HierarchyBrowserBaseEx).changeView(CallHierarchyBrowserBase.getCallerType())
    }
}

private fun hierarchyTarget(context: DataContext): PsiElement? {
    val file = context.getData(CommonDataKeys.PSI_FILE) ?: return null
    val editor = context.getData(CommonDataKeys.EDITOR) ?: return null
    if (file.language != GoLanguage || !file.isValid || file.project.isDisposed || editor.isDisposed ||
        ProjectIndexingManager.isIndexingAll() || !LanguageServersRegistry.getInstance().isFileSupported(file)) return null
    val documents = PsiDocumentManager.getInstance(file.project)
    val document = editor.document
    if (documents.getCachedDocument(file) !== document || !documents.isCommitted(document)) return null
    val offset = editor.caretModel.offset
    if (offset !in 0..document.textLength) return null
    fun identifier(element: PsiElement?) = element?.takeIf { it.node?.elementType == GoTokens.IDENTIFIER && it.text != "_" }
    val leaf = identifier(file.findElementAt(offset))
        ?: if (offset > 0) identifier(file.findElementAt(offset - 1))?.takeIf { it.textRange.endOffset == offset } else null
    leaf ?: return null
    val stamp = document.modificationStamp
    val identifier = leaf.text
    return object : LSPPsiElement(file, leaf.textRange) {
        override fun getLanguage(): Language = GoLanguage
        override fun getName() = identifier
        override fun getText() = identifier
        override fun isValid() = file.isValid && !file.project.isDisposed && document.modificationStamp == stamp
    }
}

/** Marker ownership follows a browser; disposal leaves a frozen snapshot for the SDK's rebase handoff. */
private class GoHierarchyNodes {
    private val nodes = mutableListOf<GoHierarchyNode>()
    private var closed = false

    fun root(target: PsiElement): GoHierarchyNode {
        if (!target.isValid) throw ProcessCanceledException()
        val file = target.containingFile ?: throw ProcessCanceledException()
        val document = PsiDocumentManager.getInstance(file.project).getCachedDocument(file) ?: throw ProcessCanceledException()
        val range = if (target is LSPHierarchyItemPsiElement && target !is GoHierarchyNode) {
            target.range?.let { LSPIJUtils.toTextRange(it, document, false) } ?: throw ProcessCanceledException()
        } else target.textRange
        return create(file, document, range, range, target.text, (target as? GoHierarchyNode)?.kind)
    }

    fun item(element: PsiElement, selection: Range, declaration: Range, name: String, kind: SymbolKind): GoHierarchyNode {
        val file = element.containingFile ?: throw ProcessCanceledException()
        val documents = PsiDocumentManager.getInstance(file.project)
        // Descriptor construction is performed by the platform's background hierarchy model.
        val document = documents.getCachedDocument(file) ?: run {
            check(!ApplicationManager.getApplication().isDispatchThread) { "Hierarchy targets must be loaded in the background" }
            documents.getDocument(file)
        } ?: throw ProcessCanceledException()
        val range = LSPIJUtils.toTextRange(selection, document, false) ?: throw ProcessCanceledException()
        val declarationRange = LSPIJUtils.toTextRange(declaration, document, false) ?: throw ProcessCanceledException()
        return create(file, document, range, declarationRange, name, kind)
    }

    private fun create(file: PsiFile, document: Document, range: TextRange, declaration: TextRange, name: String, kind: SymbolKind?): GoHierarchyNode {
        if (range.isEmpty || range.startOffset < 0 || range.endOffset > document.textLength) throw ProcessCanceledException()
        val node = GoHierarchyNode(file, document, document.createRangeMarker(range), document.createRangeMarker(declaration), name, kind)
        synchronized(this) {
            if (closed) node.freeze() else nodes += node
        }
        return node
    }

    @Synchronized fun dispose() {
        closed = true
        nodes.forEach(GoHierarchyNode::freeze)
        nodes.clear()
    }
}

private class GoHierarchyNode(
    private val file: PsiFile,
    private val document: Document,
    private var marker: RangeMarker?,
    private var declarationMarker: RangeMarker?,
    name: String,
    val kind: SymbolKind?
) : LSPHierarchyItemPsiElement(file, null, name) {
    private val spelling = document.getText(requireNotNull(marker).textRange)
    private var frozenRange = requireNotNull(marker).textRange
    private var frozenDeclaration = requireNotNull(declarationMarker).textRange
    private var frozenStamp = document.modificationStamp
    private var frozenValid = true

    override fun getLanguage(): Language = GoLanguage
    @Synchronized override fun getTextRange(): TextRange = marker?.takeIf { it.isValid }?.textRange ?: frozenRange
    override fun getTextOffset() = textRange.startOffset
    override fun getStartOffsetInParent() = textRange.startOffset
    override fun getTextLength() = textRange.length
    override fun getRange(): Range {
        val current = textRange
        return Range(LSPIJUtils.toPosition(current.startOffset, document), LSPIJUtils.toPosition(current.endOffset, document))
    }
    @Synchronized private fun declarationRange(): Range {
        val current = declarationMarker?.takeIf { it.isValid }?.textRange ?: frozenDeclaration
        return Range(LSPIJUtils.toPosition(current.startOffset, document), LSPIJUtils.toPosition(current.endOffset, document))
    }
    @Synchronized fun current(item: TypeHierarchyItem): TypeHierarchyItem {
        if (!isValid) throw ProcessCanceledException()
        return TypeHierarchyItem(item.name, item.kind, item.uri, declarationRange(), range).apply {
            detail = item.detail; tags = item.tags; data = item.data
        }
    }
    @Synchronized fun current(item: CallHierarchyItem): CallHierarchyItem {
        if (!isValid) throw ProcessCanceledException()
        return CallHierarchyItem(item.name, item.kind, item.uri, declarationRange(), range).apply {
            detail = item.detail; tags = item.tags; data = item.data
        }
    }
    @Synchronized override fun isValid(): Boolean {
        if (!file.isValid || file.project.isDisposed) return false
        val current = marker
        if (current != null && !current.isValid || current == null && (!frozenValid || document.modificationStamp != frozenStamp)) return false
        if (declarationMarker?.isValid == false) return false
        val range = textRange
        return range.endOffset <= document.textLength && document.getText(range) == spelling
    }

    override fun isEquivalentTo(another: PsiElement?): Boolean = another is GoHierarchyNode &&
        file.project === another.file.project && file.virtualFile == another.file.virtualFile && kind == another.kind && name == another.name && textRange == another.textRange

    @Synchronized fun freeze() {
        frozenValid = isValid
        marker?.let {
            if (it.isValid) frozenRange = it.textRange
            frozenStamp = document.modificationStamp
            it.dispose()
            marker = null
        }
        declarationMarker?.let {
            if (it.isValid) frozenDeclaration = it.textRange
            it.dispose()
            declarationMarker = null
        }
    }
}

private fun recursiveNode(parent: NodeDescriptor<*>?, element: PsiElement): Boolean {
    var ancestor = parent
    while (ancestor != null) {
        if (ancestor is LSPHierarchyNodeDescriptor<*> && ancestor.hierarchyItem != null &&
            ancestor.psiElement?.isEquivalentTo(element) == true) return true
        ancestor = ancestor.parentDescriptor
    }
    return false
}

private class GoTypeHierarchyDescriptor(project: Project, parent: NodeDescriptor<*>?, element: PsiElement, item: TypeHierarchyItem?) :
    LSPTypeHierarchyNodeDescriptor(project, parent, element, item) {
    private val recursive = item != null && recursiveNode(parent, element)
    init { if (recursive) cachedChildren = emptyArray<Any>() }
    override fun update(): Boolean {
        val changed = super.update()
        if (recursive) { myHighlightedText.ending.addText(" (recursive)"); myName = myHighlightedText.text }
        return changed
    }
}

private class GoCallHierarchyDescriptor(project: Project, parent: NodeDescriptor<*>?, element: PsiElement, item: CallHierarchyItem?) :
    LSPCallHierarchyNodeDescriptor(project, parent, element, item) {
    private val recursive = item != null && recursiveNode(parent, element)
    init { if (recursive) cachedChildren = emptyArray<Any>() }
    override fun update(): Boolean {
        val changed = super.update()
        if (recursive) { myHighlightedText.ending.addText(" (recursive)"); myName = myHighlightedText.text }
        return changed
    }
}

private class GoTypeHierarchyBrowser(target: PsiElement, private val nodes: GoHierarchyNodes) : LSPTypeHierarchyBrowser(target) {
    override fun isInterface(psiElement: PsiElement) = (psiElement as? GoHierarchyNode)?.kind == SymbolKind.Interface
    override fun createHierarchyTreeStructure(type: String, psiElement: PsiElement): HierarchyTreeStructure? = when (type) {
        TypeHierarchyBrowserBase.getSubtypesHierarchyType() -> object : LSPTypeHierarchySubtypesTreeStructure(psiElement.project, psiElement) {
            override fun createHierarchyNodeDescriptor(project: Project, parent: NodeDescriptor<*>?, element: PsiElement, item: TypeHierarchyItem?): LSPHierarchyNodeDescriptor<*> =
                GoTypeHierarchyDescriptor(project, parent, if (item == null) element else nodes.item(element, item.selectionRange, item.range, item.name, item.kind), item)
            override fun buildChildren(descriptor: HierarchyNodeDescriptor, file: PsiFile, item: TypeHierarchyItem?, result: MutableList<LSPHierarchyNodeDescriptor<*>>) {
                super.buildChildren(descriptor, file, if (item == null) null else (descriptor.psiElement as? GoHierarchyNode)?.current(item) ?: item, result)
            }
        }
        TypeHierarchyBrowserBase.getSupertypesHierarchyType() -> object : LSPTypeHierarchySupertypesTreeStructure(psiElement.project, psiElement) {
            override fun createHierarchyNodeDescriptor(project: Project, parent: NodeDescriptor<*>?, element: PsiElement, item: TypeHierarchyItem?): LSPHierarchyNodeDescriptor<*> =
                GoTypeHierarchyDescriptor(project, parent, if (item == null) element else nodes.item(element, item.selectionRange, item.range, item.name, item.kind), item)
            override fun buildChildren(descriptor: HierarchyNodeDescriptor, file: PsiFile, item: TypeHierarchyItem?, result: MutableList<LSPHierarchyNodeDescriptor<*>>) {
                super.buildChildren(descriptor, file, if (item == null) null else (descriptor.psiElement as? GoHierarchyNode)?.current(item) ?: item, result)
            }
        }
        else -> null
    }
    override fun dispose() { nodes.dispose(); super.dispose() }
}

private class GoCallHierarchyBrowser(target: PsiElement, private val nodes: GoHierarchyNodes) : LSPCallHierarchyBrowser(target) {
    override fun createHierarchyTreeStructure(type: String, psiElement: PsiElement): HierarchyTreeStructure? = when (type) {
        CallHierarchyBrowserBase.getCallerType() -> object : LSPCallHierarchyIncomingCallsTreeStructure(psiElement.project, psiElement) {
            override fun createHierarchyNodeDescriptor(project: Project, parent: NodeDescriptor<*>?, element: PsiElement, item: CallHierarchyItem?): LSPHierarchyNodeDescriptor<*> =
                GoCallHierarchyDescriptor(project, parent, if (item == null) element else nodes.item(element, item.selectionRange, item.range, item.name, item.kind), item)
            override fun buildChildren(descriptor: HierarchyNodeDescriptor, file: PsiFile, item: CallHierarchyItem?, result: MutableList<LSPHierarchyNodeDescriptor<*>>) {
                super.buildChildren(descriptor, file, if (item == null) null else (descriptor.psiElement as? GoHierarchyNode)?.current(item) ?: item, result)
            }
        }
        CallHierarchyBrowserBase.getCalleeType() -> object : LSPCallHierarchyOutgoingCallsTreeStructure(psiElement.project, psiElement) {
            override fun createHierarchyNodeDescriptor(project: Project, parent: NodeDescriptor<*>?, element: PsiElement, item: CallHierarchyItem?): LSPHierarchyNodeDescriptor<*> =
                GoCallHierarchyDescriptor(project, parent, if (item == null) element else nodes.item(element, item.selectionRange, item.range, item.name, item.kind), item)
            override fun buildChildren(descriptor: HierarchyNodeDescriptor, file: PsiFile, item: CallHierarchyItem?, result: MutableList<LSPHierarchyNodeDescriptor<*>>) {
                super.buildChildren(descriptor, file, if (item == null) null else (descriptor.psiElement as? GoHierarchyNode)?.current(item) ?: item, result)
            }
        }
        else -> null
    }
    override fun dispose() { nodes.dispose(); super.dispose() }
}
