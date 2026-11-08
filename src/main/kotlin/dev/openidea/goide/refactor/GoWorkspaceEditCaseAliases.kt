package dev.openidea.goide.refactor

import org.eclipse.lsp4j.RenameFile
import org.eclipse.lsp4j.WorkspaceEdit
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

internal data class GoCaseRenameAlias(val old: Path, val new: Path, val originalName: String = old.fileName.toString())

/** Filesystem identity checks run only in background preparation, never in capture or commit. */
internal object GoWorkspaceEditCaseAliases {
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
        if (old == new || old.fileName == null || new.fileName == null) return null
        val oldName = old.fileName.toString()
        val newName = new.fileName.toString()
        if (oldName == newName || !oldName.equals(newName, ignoreCase = true)) return null
        if (!Files.exists(old, LinkOption.NOFOLLOW_LINKS) || !Files.exists(new, LinkOption.NOFOLLOW_LINKS)) return null
        if (Files.isSymbolicLink(old) || Files.isSymbolicLink(new)) return null
        val oldParent = old.parent ?: return null
        val newParent = new.parent ?: return null
        if (!Files.isSameFile(oldParent, newParent) || !Files.isSameFile(old, new)) return null
        // Hard links in a case-sensitive directory can share an inode while being two real
        // entries. A case alias has exactly one case-folding directory entry, not two names.
        val matching = Files.newDirectoryStream(oldParent).use { entries ->
            entries.filter { it.fileName.toString().equals(oldName, ignoreCase = true) }.toList()
        }
        return matching.singleOrNull()?.takeIf { Files.isSameFile(it, old) }?.fileName?.toString()
    }

    /** Keep the first source spelling for each identity group until its ordered rename occurs. */
    fun logicalTree(initial: Map<Path, GoEditNode>, aliases: Set<GoCaseRenameAlias>): Map<Path, GoEditNode> {
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
        if (path in tree) return path
        val members = aliases.flatMap { listOf(it.old, it.new) }.distinct().sortedByDescending { it.nameCount }
        for (member in members) {
            if (path != member && !(initial[member]?.directory == true && path.startsWith(member))) continue
            val live = tree.keys.filter { candidate -> candidate.toString().equals(member.toString(), ignoreCase = true) }.singleOrNull()
                ?: continue
            return live.resolve(member.relativize(path))
        }
        return path
    }
}
