package dev.openidea.goide.refactor

import org.eclipse.lsp4j.RenameFile
import org.eclipse.lsp4j.WorkspaceEdit
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

internal data class GoCaseRenameAlias(val old: Path, val new: Path, val originalName: String = old.fileName.toString())

/** Filesystem identity checks run only in background preparation, never in capture or commit. */
internal object GoWorkspaceEditCaseAliases {
    fun sameSpelling(first: Path, second: Path): Boolean = first.toString() == second.toString()
    fun detect(edit: WorkspaceEdit): Set<GoCaseRenameAlias> = edit.documentChanges.orEmpty().mapNotNull { entry ->
        val rename = if (entry.isRight) entry.right as? RenameFile else null
        rename?.let {
            val old = GoWorkspaceEditPlan.path(it.oldUri)
            val new = GoWorkspaceEditPlan.path(it.newUri)
            actualEntryName(old, new)?.let { name -> GoCaseRenameAlias(old, new, name) }
        }
    }.toSet()

    fun isCaseAlias(old: Path, new: Path): Boolean = actualEntryName(old, new) != null

    fun actualEntryName(old: Path, new: Path): String? {
        if (sameSpelling(old, new) || old.fileName == null || new.fileName == null) return null
        val oldName = old.fileName.toString()
        val newName = new.fileName.toString()
        if (oldName == newName || !oldName.equals(newName, ignoreCase = true)) return null
        if (!Files.exists(old, LinkOption.NOFOLLOW_LINKS) || !Files.exists(new, LinkOption.NOFOLLOW_LINKS)) return null
        if (Files.isSymbolicLink(old) || Files.isSymbolicLink(new)) return null
        val oldParent = old.parent ?: return null
        val newParent = new.parent ?: return null
        if (!samePhysicalFile(oldParent, newParent) || !samePhysicalFile(old, new)) return null
        // Hard links in a case-sensitive directory can share an inode while being two real
        // entries. A case alias has exactly one case-folding directory entry, not two names.
        val matching = Files.newDirectoryStream(oldParent).use { entries ->
            entries.filter { it.fileName.toString().equals(oldName, ignoreCase = true) }.toList()
        }
        return matching.singleOrNull()?.takeIf { Files.isSameFile(it, old) }?.fileName?.toString()
    }

    private fun samePhysicalFile(first: Path, second: Path): Boolean {
        if (!Files.isSameFile(first, second)) return false
        if (first != second || sameSpelling(first, second)) return true
        // Windows' provider short-circuits isSameFile on case-folded Path.equals.
        // Read real file identities before trusting alternate spellings; a
        // case-sensitive Windows directory can contain two distinct resources.
        val left = Files.readAttributes(first, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).fileKey()
        val right = Files.readAttributes(second, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).fileKey()
        return left != null && left == right
    }

    /** Keep the first source spelling for each identity group until its ordered rename occurs. */
    fun logicalTree(initial: Map<Path, GoEditNode>, aliases: Set<GoCaseRenameAlias>): Map<Path, GoEditNode> {
        // WindowsPath.equals folds case, so a Map may already contain just the
        // NEW spelling when it was mentioned first. Physical alias detection
        // supplies the actual original entry name; preserve that spelling and
        // document origin before processing the ordered transaction.
        val collapsed = aliases.filter { it.old == it.new && !sameSpelling(it.old, it.new) }
            .distinctBy { it.old }.sortedBy { it.old.nameCount }
        if (collapsed.isNotEmpty()) {
            var nodes = initial
            collapsed.forEach { alias ->
                val parent = nodes.keys.firstOrNull { it == alias.old.parent } ?: alias.old.parent
                val original = parent.resolve(alias.originalName)
                val directory = nodes[alias.old]?.directory == true
                fun canonical(path: Path): Path =
                    if (path == alias.old || directory && path.startsWith(alias.old)) original.resolve(alias.old.relativize(path)) else path
                nodes = nodes.entries.associateTo(linkedMapOf()) { (path, node) ->
                    canonical(path) to node.copy(origin = node.origin?.let(::canonical))
                }
            }
            return nodes
        }
        val parents = linkedMapOf<Path, Path>()
        val order = mutableMapOf<Path, Int>()
        fun representative(path: Path): Path {
            val parent = parents.getOrPut(path) { order[path] = order.size; path }
            if (parent == path) return path
            return representative(parent).also { parents[path] = it }
        }
        aliases.forEach { alias ->
            val source = representative(alias.old)
            val target = representative(alias.new)
            if (source != target) {
                if (order.getValue(source) < order.getValue(target)) parents[target] = source else parents[source] = target
            }
        }
        val duplicateRoots = parents.keys.filter { representative(it) != it }
        return initial.filterKeys { path -> duplicateRoots.none(path::startsWith) }
    }

    /** Resolve only aliases established by physical identity, including new children of an aliased folder. */
    fun resolve(path: Path, tree: Map<Path, GoEditNode>, initial: Map<Path, GoEditNode>, aliases: Set<GoCaseRenameAlias>): Path {
        tree.keys.firstOrNull { sameSpelling(it, path) }?.let { return it }
        val members = aliases.flatMap { listOf(it.old, it.new) }.distinctBy { it.toString() }.sortedByDescending { it.nameCount }
        for (member in members) {
            if (path != member && !(initial[member]?.directory == true && path.startsWith(member))) continue
            val live = tree.keys.filter { candidate -> candidate.toString().equals(member.toString(), ignoreCase = true) }.singleOrNull()
                ?: continue
            return live.resolve(member.relativize(path))
        }
        return path
    }
}
