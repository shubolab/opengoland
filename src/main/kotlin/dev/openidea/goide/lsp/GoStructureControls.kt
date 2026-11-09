package dev.openidea.goide.lsp

import com.intellij.icons.AllIcons
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.util.FileStructureFilter
import com.intellij.ide.util.treeView.smartTree.ActionPresentation
import com.intellij.ide.util.treeView.smartTree.ActionPresentationData
import com.intellij.ide.util.treeView.smartTree.Filter
import com.intellij.ide.util.treeView.smartTree.Sorter
import com.intellij.ide.util.treeView.smartTree.TreeActionWithDefaultState
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.openapi.actionSystem.Shortcut
import com.redhat.devtools.lsp4ij.features.documentSymbol.DocumentSymbolData
import org.eclipse.lsp4j.SymbolKind

/** Go-specific actions consumed by both native Structure hosts; no source access in UI callbacks. */
object GoStructureControls {
    const val SHOW_PRIVATE_ID = "OPENGOLAND_GO_SHOW_PRIVATE_MEMBERS"
    const val EXPORTABILITY_ID = "OPENGOLAND_GO_EXPORTABILITY"

    fun filters(): Array<Filter> = arrayOf(PrivateMembers)

    // Comparator precedence is significant: alphabetizing must not mix visibility groups.
    fun sorters(): Array<Sorter> = arrayOf(Exportability, Sorter.ALPHA_SORTER)

    private object PrivateMembers : FileStructureFilter, TreeActionWithDefaultState {
        override fun getName() = SHOW_PRIVATE_ID
        override fun getPresentation(): ActionPresentation =
            ActionPresentationData("Show Private Members", "Include unexported Go declarations", AllIcons.Nodes.C_private)
        override fun getCheckBoxText() = "Show Private Members"
        override fun getShortcut(): Array<Shortcut> = emptyArray()
        override fun isReverted() = true
        // Preserve the existing all-members view. This is our product default,
        // not an assertion about commercial GoLand's first-launch preferences.
        override fun isEnabledByDefault() = true
        override fun isVisible(treeNode: TreeElement) = visibility(treeNode) != Visibility.PRIVATE
    }

    private object Exportability : Sorter {
        override fun getName() = EXPORTABILITY_ID
        override fun isVisible() = true
        override fun getPresentation(): ActionPresentation =
            ActionPresentationData("By Exportability", "Show exported Go declarations first", AllIcons.ObjectBrowser.VisibilitySort)
        override fun getComparator(): Comparator<TreeElement> = Comparator { first, second ->
            visibility(first).rank.compareTo(visibility(second).rank)
        }
    }

    // Unknown nodes remain visible. Giving them a distinct rank also keeps the
    // comparator transitive instead of declaring an unknown equal to every node.
    private enum class Visibility(val rank: Int) { EXPORTED(0), UNKNOWN(1), PRIVATE(2) }

    private fun visibility(element: TreeElement): Visibility {
        val data = (element as? StructureViewTreeElement)?.value as? DocumentSymbolData ?: return Visibility.UNKNOWN
        val symbol = data.documentSymbol
        if (symbol.kind !in declarationKinds) return Visibility.UNKNOWN
        val name = if (symbol.kind == SymbolKind.Method) symbol.name.substringAfterLast('.') else symbol.name
        if (!goIdentifier(name)) return Visibility.UNKNOWN
        // Go uses category Lu, not Java's wider Other_Uppercase property. Work
        // in code points so supplementary-plane exported names are handled too.
        return if (Character.getType(name.codePointAt(0)) == Character.UPPERCASE_LETTER.toInt()) Visibility.EXPORTED else Visibility.PRIVATE
    }

    private fun goIdentifier(name: String): Boolean {
        if (name.isEmpty()) return false
        var offset = 0
        while (offset < name.length) {
            val codePoint = name.codePointAt(offset)
            val category = Character.getType(codePoint)
            val letter = category == Character.UPPERCASE_LETTER.toInt() || category == Character.LOWERCASE_LETTER.toInt() ||
                category == Character.TITLECASE_LETTER.toInt() || category == Character.MODIFIER_LETTER.toInt() ||
                category == Character.OTHER_LETTER.toInt()
            if (codePoint != '_'.code && !letter && (offset == 0 || category != Character.DECIMAL_DIGIT_NUMBER.toInt())) return false
            offset += Character.charCount(codePoint)
        }
        return true
    }

    private val declarationKinds = setOf(SymbolKind.Class, SymbolKind.Struct, SymbolKind.Interface, SymbolKind.Function,
        SymbolKind.Method, SymbolKind.Constructor, SymbolKind.Field, SymbolKind.Variable, SymbolKind.Constant)
}
