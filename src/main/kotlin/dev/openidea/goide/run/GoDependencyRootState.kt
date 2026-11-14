package dev.openidea.goide.run

import com.intellij.openapi.application.ApplicationManager
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime

/** Only an exact recovery-target state transition requests a bounded metadata retry. */
internal class GoDependencyRootChanged(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/** An exact selected dependency target, not a directory watch or package-graph success. */
internal class GoDependencyRootState private constructor(
    val path: Path,
    private val root: Entry?,
    private val module: Entry?,
    private val attributesReader: (Path) -> BasicFileAttributes?,
) {
    val isDirectory: Boolean get() = root?.directory == true && !root.symbolicLink

    /** Background-only: stat this exact root and, if present, its exact go.mod. No parent scan. */
    fun isCurrent(): Boolean = capture(path, attributesReader) == this

    override fun equals(other: Any?): Boolean = other is GoDependencyRootState &&
        path == other.path && root == other.root && module == other.module

    override fun hashCode(): Int = 31 * (31 * path.hashCode() + (root?.hashCode() ?: 0)) + (module?.hashCode() ?: 0)

    override fun toString(): String = "GoDependencyRootState(path=$path, isDirectory=$isDirectory)"

    private data class Entry(
        val key: Any?, val created: FileTime, val modified: FileTime, val bytes: Long,
        val directory: Boolean, val symbolicLink: Boolean,
    )

    companion object {
        fun capture(path: Path, attributesReader: (Path) -> BasicFileAttributes? = ::nativeAttributes): GoDependencyRootState {
            check(ApplicationManager.getApplication()?.isDispatchThread != true) { "Go dependency roots must be checked in background" }
            require(path.isAbsolute) { "A selected Go dependency root must be absolute" }
            val target = path.normalize()
            val root = attributesReader(target)?.let(::entry)
            val module = if (root?.directory == true || root?.symbolicLink == true) attributesReader(target.resolve("go.mod"))?.let(::entry) else null
            return GoDependencyRootState(target, root, module, attributesReader)
        }

        private fun entry(attributes: BasicFileAttributes) = attributes.let {
            Entry(it.fileKey(), it.creationTime(), it.lastModifiedTime(), it.size(), it.isDirectory, it.isSymbolicLink)
        }

        private fun nativeAttributes(path: Path): BasicFileAttributes? = try {
            Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (_: NoSuchFileException) { null } catch (_: NotDirectoryException) { null }
    }
}
