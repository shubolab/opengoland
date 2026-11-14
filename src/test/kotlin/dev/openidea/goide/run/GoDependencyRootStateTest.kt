package dev.openidea.goide.run

import junit.framework.TestCase
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.util.UUID

/** Exact attribute-reader contract on every platform; no symlink privilege or skipped Windows test. */
class GoDependencyRootStateTest : TestCase() {
    private val root = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize().resolve("root-state-controlled")

    fun testMissingTargetOnlyStatsTheExactRoot() {
        val reads = mutableListOf<Path>()
        val state = GoDependencyRootState.capture(root) { reads.add(it); null }
        assertFalse(state.isDirectory)
        assertEquals(listOf(root), reads)
        reads.clear()
        assertTrue(state.isCurrent())
        assertEquals(listOf(root), reads)
    }

    fun testUnchangedLinkDetectsItsModuleAppearingWithoutClaimingDirectoryCoverage() {
        val reads = mutableListOf<Path>()
        val entries = mutableMapOf<Path, BasicFileAttributes>(root to Attributes(symbolic = true))
        val state = GoDependencyRootState.capture(root) { reads.add(it); entries[it] }
        assertFalse("A lexical link is not proof of a physical directory lease", state.isDirectory)
        reads.clear()
        assertTrue(state.isCurrent())
        assertEquals("A pending link checks only itself and its exact go.mod", listOf(root, root.resolve("go.mod")), reads)
        entries[root.resolve("go.mod")] = Attributes(key = "new-module", bytes = 40)
        reads.clear()
        assertFalse("The unchanged link must notice its previously absent module appearing", state.isCurrent())
        assertEquals(listOf(root, root.resolve("go.mod")), reads)
        assertFalse("Only a fresh metadata/physical-path revalidation may upgrade this interest", state.isDirectory)
    }

    fun testDirectoryAndExactModuleChangesUseAtMostTwoStats() {
        val reads = mutableListOf<Path>()
        val entries = mutableMapOf<Path, BasicFileAttributes>(root to Attributes(directory = true))
        val state = GoDependencyRootState.capture(root) { reads.add(it); entries[it] }
        assertTrue(state.isDirectory)
        assertEquals(listOf(root, root.resolve("go.mod")), reads)
        entries[root.resolve("go.mod")] = Attributes(key = "new-module", bytes = 30)
        reads.clear()
        assertFalse(state.isCurrent())
        assertEquals(listOf(root, root.resolve("go.mod")), reads)
    }

    fun testSamePathDirectoryIdentityReplacementInvalidatesTheObservation() {
        var attributes: BasicFileAttributes = Attributes(directory = true, key = "first")
        val reads = mutableListOf<Path>()
        val state = GoDependencyRootState.capture(root) { reads.add(it); if (it == root) attributes else null }
        attributes = Attributes(directory = true, key = "replacement")
        reads.clear()
        assertFalse(state.isCurrent())
        assertEquals(listOf(root, root.resolve("go.mod")), reads)
    }

    fun testEqualityIgnoresReaderIdentityButEachObservationReusesItsOwnReader() {
        var first: BasicFileAttributes = Attributes(directory = true)
        val left = GoDependencyRootState.capture(root) { if (it == root) first else null }
        val right = GoDependencyRootState.capture(root) { if (it == root) Attributes(directory = true) else null }
        assertEquals(left, right)
        assertEquals(left.hashCode(), right.hashCode())
        first = Attributes(directory = true, key = "changed-only-in-first-reader")
        assertFalse(left.isCurrent())
        assertTrue(right.isCurrent())
    }

    fun testAccessFailureIsNotConvertedIntoAConfirmedMissingTarget() {
        var inaccessible = false
        val state = GoDependencyRootState.capture(root) {
            if (inaccessible) throw AccessDeniedException(it.toString())
            if (it == root) Attributes(directory = true) else null
        }
        inaccessible = true
        try { state.isCurrent(); fail("An unknown IO failure must not become a missing-root observation") }
        catch (_: AccessDeniedException) {}
    }

    fun testNativeReaderRecordsAnAbsentExactPathWithoutCreatingIt() {
        val absent = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()
            .resolve("root-state-absent-${UUID.randomUUID()}").resolve("dep")
        assertFalse(Files.exists(absent))
        val state = GoDependencyRootState.capture(absent)
        assertFalse(state.isDirectory)
        assertTrue(state.isCurrent())
        assertFalse(Files.exists(absent))
    }

    private data class Attributes(
        val directory: Boolean = false,
        val symbolic: Boolean = false,
        val key: String = "same-entry",
        val bytes: Long = 0,
        val modified: Long = 1,
    ) : BasicFileAttributes {
        override fun lastModifiedTime(): FileTime = FileTime.fromMillis(modified)
        override fun lastAccessTime(): FileTime = FileTime.fromMillis(1)
        override fun creationTime(): FileTime = FileTime.fromMillis(1)
        override fun isRegularFile(): Boolean = !directory && !symbolic
        override fun isDirectory(): Boolean = directory
        override fun isSymbolicLink(): Boolean = symbolic
        override fun isOther(): Boolean = false
        override fun size(): Long = bytes
        override fun fileKey(): Any = key
    }
}
