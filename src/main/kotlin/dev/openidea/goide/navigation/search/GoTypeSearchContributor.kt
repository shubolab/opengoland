package dev.openidea.goide.navigation.search

import com.intellij.icons.AllIcons
import com.intellij.lang.Language
import com.intellij.navigation.ChooseByNameContributorEx2
import com.intellij.navigation.GotoClassContributor
import com.intellij.navigation.NavigationItem
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.ex.DocumentEx
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFileWithId
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.FakePsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.Processor
import com.intellij.util.indexing.FindSymbolParameters
import com.intellij.util.indexing.IdFilter
import dev.openidea.goide.lang.GoLanguage
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import java.util.concurrent.CancellationException

/** The standard Go To Class/Type and Search Everywhere native contributor, not a separate popup. */
class GoTypeSearchContributor private constructor(private val index: (Project) -> GoTypeSearchIndex) :
    ChooseByNameContributorEx2, GotoClassContributor, DumbAware {
    constructor() : this({ GoTypeSearchService.get(it) })
    internal constructor(index: GoTypeSearchIndex) : this({ index })

    override fun processNames(processor: Processor<in String>, parameters: FindSymbolParameters) {
        run(parameters) { search, snapshot ->
            val seen = mutableSetOf<String>()
            for (entry in snapshot.entries()) {
                ProgressManager.checkCanceled()
                if (!accept(entry, snapshot, parameters)) continue
                if (seen.add(entry.name) && !processor.process(entry.name)) break
            }
            if (!snapshot.current()) throw ProcessCanceledException()
        }
    }
    override fun processNames(processor: Processor<in String>, scope: GlobalSearchScope, filter: IdFilter?) {
        val project = scope.project ?: return
        processNames(processor, FindSymbolParameters("", "", scope, filter))
    }
    override fun processElementsWithName(name: String, processor: Processor<in NavigationItem>, parameters: FindSymbolParameters) {
        run(parameters) { _, snapshot ->
            for (entry in snapshot.byName[name].orEmpty()) {
                ProgressManager.checkCanceled()
                if (accept(entry, snapshot, parameters) && !processor.process(snapshot.materialize(entry))) break
            }
            if (!snapshot.current()) throw ProcessCanceledException()
        }
    }
    private fun accept(entry: GoTypeEntry, snapshot: GoTypeSearchIndex.Snapshot, parameters: FindSymbolParameters): Boolean {
        val scope = parameters.searchScope
        val project = parameters.project
        val all = scope == GlobalSearchScope.allScope(project) || scope == GlobalSearchScope.everythingScope(project)
        val filter = parameters.idFilter
        // Canonical All Places includes the selected Go SDK/dependency catalog,
        // even before those sources have native library-index registrations.
        // The standard provider's id filter is a native-index optimization; it
        // can be empty while indexing and does not contain unregistered Go SDK
        // sources. It cannot narrow canonical All Places. Explicit scopes keep
        // their own contains/id-filter restrictions below.
        if (all) return true
        val projectOnly = scope == GlobalSearchScope.projectScope(project)
        // A reused All Places catalog can contain vendored/library sources
        // physically below a content root. Preserve the backend's workspace
        // membership instead of broadening the Project catalog's semantics.
        if (projectOnly && entry.origin != "project") return false
        val file = snapshot.file(entry) ?: return false
        if (!ReadAction.compute<Boolean, RuntimeException> { scope.contains(file) }) return false
        if (projectOnly) return true
        return filter == null || file !is VirtualFileWithId || filter.containsFileId(file.id)
    }
    private fun run(parameters: FindSymbolParameters, block: (GoTypeSearchIndex, GoTypeSearchIndex.Snapshot) -> Unit) {
        val project = parameters.project
        if (project.isDefault || project.isDisposed || ApplicationManager.getApplication().isDispatchThread) return
        var search: GoTypeSearchIndex? = null
        try {
            val current = index(project).also { search = it }
            block(current, current.catalog(parameters.searchScope))
        }
        catch (error: ProcessCanceledException) { throw error }
        catch (_: CancellationException) { throw ProcessCanceledException() }
        catch (error: Exception) {
            if (project.isDisposed) throw ProcessCanceledException()
            val message = error.message ?: "Go type search failed. Check Go build settings."
            search?.report(message) ?: report(project, message)
        }
    }
    override fun getQualifiedName(item: NavigationItem): String? = (item as? GoTypeSearchItem)?.qualifiedName
    override fun getQualifiedNameSeparator() = "."
    override fun getElementKind() = "type"
    override fun getElementKindsPluralized() = listOf("types")
    override fun getTabTitlePluralized() = "Types"
    override fun getElementLanguage(): Language = GoLanguage

    companion object {
        const val NOTIFICATION_GROUP = "open Goland Type Search"
        internal fun report(project: Project, message: String) {
            ApplicationManager.getApplication().invokeLater({
                if (!project.isDisposed) NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
                    .createNotification("Go Type Search", message, NotificationType.WARNING).notify(project)
            }, project.disposed)
        }
    }
}

/** A real named Go declaration represented by a validated native document/token range. */
internal class GoTypeSearchItem(
    private val entry: GoTypeEntry,
    private val file: PsiFile,
    private val document: Document,
    private val stamp: Long,
    private val sequence: Int?,
    private val selected: TextRange,
    private val current: () -> Boolean
) : FakePsiElement() {
    private val sourceFile = file.virtualFile
    val qualifiedName = entry.packagePath.takeIf(String::isNotEmpty)?.let { "$it.${entry.name}" } ?: entry.name
    override fun getName() = entry.name
    override fun getText() = entry.name
    override fun getTextRange() = selected
    override fun getTextOffset() = selected.startOffset
    override fun getTextLength() = entry.name.length
    override fun getContainingFile() = file
    override fun getParent() = file
    override fun getProject() = file.project
    override fun getManager() = file.manager
    override fun getLanguage(): Language = GoLanguage
    override fun getNavigationElement() = this
    override fun getPresentableText() = entry.name
    override fun getLocationString() = listOf(entry.packagePath, file.name).filter(String::isNotEmpty).joinToString(" · ")
    override fun getIcon(open: Boolean) = if (entry.kind == "interface") AllIcons.Nodes.Interface else AllIcons.Nodes.Class
    override fun isValid() = !project.isDisposed && current() && sourceFile.isValid && document.modificationStamp == stamp &&
        (document as? DocumentEx)?.modificationSequence == sequence
    override fun canNavigate() = isValid
    override fun canNavigateToSource() = isValid
    override fun navigate(requestFocus: Boolean) {
        val navigate = Runnable { if (isValid) OpenFileDescriptor(project, sourceFile, selected.startOffset).navigate(requestFocus) }
        if (ApplicationManager.getApplication().isDispatchThread) navigate.run()
        else ApplicationManager.getApplication().invokeLater(navigate, project.disposed)
    }

    companion object {
        internal fun strictRange(range: Range, document: Document, text: String): TextRange {
            fun offset(position: Position): Int {
                require(position.line in 0 until document.lineCount && position.character >= 0) { "Invalid Go type source position" }
                val start = document.getLineStartOffset(position.line); val end = document.getLineEndOffset(position.line)
                require(position.character <= end - start) { "A Go type source column is outside its line" }
                val offset = start + position.character
                require(offset <= text.length && !(offset > 0 && offset < text.length && Character.isLowSurrogate(text[offset]) && Character.isHighSurrogate(text[offset - 1]))) {
                    "A Go type source position splits a UTF-16 character"
                }
                return offset
            }
            val start = offset(range.start); val end = offset(range.end)
            require(start <= end) { "Reversed Go type source range" }
            return TextRange(start, end)
        }
    }
}
