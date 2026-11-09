package dev.openidea.goide.sdk

import dev.openidea.goide.project.GoModuleSdk
import dev.openidea.goide.project.GoSdkProbe
import dev.openidea.goide.settings.GoToolchain
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPOutputStream
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GoSdkDownloadsTest {
    private val host = GoSdkHost("darwin", "arm64")
    private val version = "go1.27.3"
    private data class Entry(val name: String, val bytes: ByteArray = byteArrayOf(), val mode: Int = 420, val type: Byte = '0'.code.toByte())

    @Test fun catalogueSelectsOnlyStableExactNativeArchivesAndSortsNumerically() {
        val bytes = ("[" + release("go1.9.7") + "," + release("go1.27.3") + "," + release("go1.28rc1", stable = false) + "," +
            release("go1.26.10", os = "windows", arch = "amd64", extension = "zip") + "]").toByteArray()
        val result = GoSdkDownloads(host = host).parseCatalogue(bytes, host)
        assertEquals(listOf("go1.27.3", "go1.9.7"), result.map { it.version })
        assertTrue(result.all { it.uri.host == "go.dev" && it.uri.scheme == "https" })
        listOf(
            "[${release(version, filename = "../escape.tar.gz")}]",
            "[${release(version, fileVersion = "go1.1")}]",
            "[${release(version, hash = "bad")}]",
            "[${release(version)},${release(version)}]",
        ).forEach { assertFailsWith<IOException> { GoSdkDownloads(host = host).parseCatalogue(it.toByteArray(), host) } }
    }

    @Test fun officialTransportRejectsInsecureForeignCredentialAndPathRedirects() {
        listOf(GoSdkDownloads.CATALOGUE, URI("https://go.dev/dl/go1.27.3.darwin-arm64.tar.gz"),
            URI("https://dl.google.com/go/go1.27.3.windows-amd64.zip")).forEach(OfficialGoSdkTransport::validate)
        listOf("http://go.dev/dl/go1.27.3.darwin-arm64.tar.gz", "https://evil.example/go/go1.27.3.darwin-arm64.tar.gz",
            "https://user:password@go.dev/dl/go1.27.3.darwin-arm64.tar.gz", "https://go.dev/dl/nested/go1.27.3.darwin-arm64.tar.gz",
            "https://dl.google.com/go/go1.27.3.darwin-arm64.tar.gz?token=secret", "https://go.dev/dl/go1.27.3.darwin-arm64.tar.gz#fragment")
            .forEach { assertFailsWith<IOException> { OfficialGoSdkTransport.validate(URI(it)) } }
    }

    @Test fun checksumAndExactSizeAreVerifiedBeforeAnyProbeOrPublication() = fixture { root ->
        val bytes = tar()
        var probes = 0
        val downloads = downloader(bytes) { _, _ -> probes++; error("Unverified archive reached the SDK probe") }
        val valid = archive(bytes)
        listOf(valid.copy(sha256 = "0".repeat(64)), valid.copy(size = bytes.size + 1L), valid.copy(size = bytes.size - 1L)).forEachIndexed { index, value ->
            val target = root.resolve("sdk$index")
            assertFailsWith<IOException> { downloads.install(value, target, GoSdkDownloadCancellation()) }
            assertFalse(Files.exists(target)); assertNoStaging(root)
        }
        assertEquals(0, probes)
    }

    @Test fun tarAndZipPublishOnlyAfterBothIdentityChecksAndKeepExecutableBits() = fixture { root ->
        for (windows in listOf(false, true)) {
            val selectedHost = if (windows) GoSdkHost("windows", "amd64") else host
            val bytes = if (windows) zip(standardEntries("go.exe")) else tar()
            val checked = mutableListOf<Path>()
            val downloads = downloader(bytes, selectedHost) { executable, cancellation ->
                cancellation.check(); checked.add(executable)
                assertTrue(Files.isRegularFile(executable))
                identity(executable)
            }
            val target = root.resolve(if (windows) "Windows 中文😀" else "macOS 中文😀")
            val sdk = downloads.install(archive(bytes, selectedHost), target, GoSdkDownloadCancellation())
            assertEquals(target.toRealPath(), sdk.root)
            assertEquals(2, checked.size)
            assertTrue(checked.first().toString().contains(".open-goland-sdk-"))
            assertEquals(sdk.executable, checked.last())
            if (!windows && !System.getProperty("os.name").startsWith("Windows")) assertTrue(Files.isExecutable(sdk.executable))
            assertEquals("runtime", Files.readString(target.resolve("src/runtime/runtime.go")))
            assertNoStaging(root)
        }
    }

    @Test fun traversalAliasesLinksSpecialFilesAndDuplicateEntriesAreRejected() = fixture { root ->
        val invalid = listOf(
            listOf(Entry("go/../escaped")), listOf(Entry("/outside")), listOf(Entry("go/C:/escape")),
            listOf(Entry("other/bin/go")), listOf(Entry("go/a"), Entry("go/A")),
            listOf(Entry("go/bin/go", mode = 41471)), // Unix symbolic link in ZIP central directory.
        )
        invalid.forEachIndexed { index, entries ->
            val bytes = zip(standardEntries("go.exe") + entries)
            val selected = GoSdkHost("windows", "amd64")
            val downloads = downloader(bytes, selected) { _, _ -> error("Unsafe archive reached verification") }
            assertFailsWith<IOException>("Unsafe ZIP case $index: ${entries.map { it.name }}") { downloads.install(archive(bytes, selected), root.resolve("bad$index"), GoSdkDownloadCancellation()) }
            assertFalse(Files.exists(root.resolve("bad$index"))); assertNoStaging(root)
        }
        for (type in listOf('1', '2', '6')) {
            val bytes = tar(standardEntries() + Entry("go/unsafe", type = type.code.toByte()))
            assertFailsWith<IOException> { downloader(bytes).install(archive(bytes), root.resolve("tar$type"), GoSdkDownloadCancellation()) }
            assertFalse(Files.exists(root.resolve("tar$type")))
        }
        for ((index, name) in listOf("/go/absolute", "go/../escaped", "go\\escape", "go/C:/escape").withIndex()) {
            val bytes = tar(standardEntries() + Entry(name))
            assertFailsWith<IOException> { downloader(bytes).install(archive(bytes), root.resolve("tar-path$index"), GoSdkDownloadCancellation()) }
            assertFalse(Files.exists(root.resolve("tar-path$index")))
        }
        assertFalse(Files.exists(root.resolve("escaped")))
        val backslash = zip(standardEntries("go.exe") + Entry("go/escape"))
        val original = "go/escape".toByteArray()
        for (offset in 0..backslash.size - original.size) {
            if (original.indices.all { backslash[offset + it] == original[it] }) backslash[offset + 2] = '\\'.code.toByte()
        }
        val selected = GoSdkHost("windows", "amd64")
        assertFailsWith<IOException> { downloader(backslash, selected).install(archive(backslash, selected), root.resolve("backslash"), GoSdkDownloadCancellation()) }
    }

    @Test fun extractionEntryAndAggregateBoundsRefuseAuthenticatedArchiveBombs() = fixture { root ->
        val bytes = tar()
        val limits = listOf(
            GoSdkDownloads.Limits(entryBytes = 2), GoSdkDownloads.Limits(unpackedBytes = 4), GoSdkDownloads.Limits(entries = 1),
        )
        limits.forEachIndexed { index, limit ->
            val downloads = GoSdkDownloads(transport(bytes), { _, _ -> error("Oversized archive reached verification") }, limit, host)
            assertFailsWith<IOException> { downloads.install(archive(bytes), root.resolve("limit$index"), GoSdkDownloadCancellation()) }
            assertNoStaging(root)
        }
    }

    @Test fun zipEntryCrcIsCheckedEvenWhenTheWholeArchiveHashMatches() = fixture { root ->
        val bytes = zip(standardEntries("go.exe"))
        val central = (0 until bytes.size - 4).first { bytes[it] == 0x50.toByte() && bytes[it + 1] == 0x4b.toByte() &&
            bytes[it + 2] == 1.toByte() && bytes[it + 3] == 2.toByte() }
        bytes[central + 16] = (bytes[central + 16].toInt() xor 1).toByte()
        val selected = GoSdkHost("windows", "amd64")
        val downloads = downloader(bytes, selected) { _, _ -> error("Invalid ZIP CRC reached verification") }
        val target = root.resolve("bad-crc")
        assertFailsWith<IOException> { downloads.install(archive(bytes, selected), target, GoSdkDownloadCancellation()) }
        assertFalse(Files.exists(target)); assertNoStaging(root)
    }

    @Test fun existingAndConcurrentDestinationsAreNeverOverwritten() = fixture { root ->
        val bytes = tar()
        val target = Files.createDirectory(root.resolve("existing"))
        Files.writeString(target.resolve("mine"), "preserve")
        assertFailsWith<IOException> { downloader(bytes).install(archive(bytes), target, GoSdkDownloadCancellation()) }
        val concurrent = root.resolve("concurrent")
        val downloads = downloader(bytes) { executable, _ ->
            Files.createDirectory(concurrent); Files.writeString(concurrent.resolve("mine"), "concurrent")
            identity(executable)
        }
        assertFailsWith<IOException> { downloads.install(archive(bytes), concurrent, GoSdkDownloadCancellation()) }
        assertEquals("preserve", Files.readString(target.resolve("mine")))
        assertEquals("concurrent", Files.readString(concurrent.resolve("mine")))
        assertNoStaging(root)
    }

    @Test fun invalidVersionOrFinalProbeFailureNeverReturnsOrLeavesAnInstallation() = fixture { root ->
        val bytes = tar()
        var probes = 0
        val downloads = downloader(bytes) { executable, _ ->
            probes++
            if (probes == 2) throw IOException("Actual final probe failed")
            identity(executable)
        }
        val target = root.resolve("failure")
        assertFailsWith<IOException> { downloads.install(archive(bytes), target, GoSdkDownloadCancellation()) }
        assertEquals(2, probes); assertFalse(Files.exists(target)); assertNoStaging(root)
        val wrong = downloader(bytes) { executable, _ -> identity(executable).copy(version = "go1.1") }
        assertFailsWith<IOException> { wrong.install(archive(bytes), root.resolve("wrong"), GoSdkDownloadCancellation()) }
        assertFalse(Files.exists(root.resolve("wrong")))
    }

    @Test fun rollbackPreservesUnexpectedNestedAdditionsAndChangedOwnedBytes() = fixture { root ->
        val bytes = tar()
        val target = root.resolve("modified")
        var probes = 0
        val downloads = downloader(bytes) { executable, _ ->
            if (++probes == 2) {
                Files.writeString(target.resolve("src/runtime/runtime.go"), "user changed this")
                Files.writeString(target.resolve("src/runtime/user.go"), "user addition")
                throw IOException("final verification refused")
            }
            identity(executable)
        }
        assertFailsWith<IOException> { downloads.install(archive(bytes), target, GoSdkDownloadCancellation()) }
        assertEquals("user changed this", Files.readString(target.resolve("src/runtime/runtime.go")))
        assertEquals("user addition", Files.readString(target.resolve("src/runtime/user.go")))
        assertFalse(Files.exists(target.resolve("bin/go")))
        assertNoStaging(root)
    }

    @Test fun rollbackPreservesAReplacedNestedParentEvenWhenItsFileHasTheSameBytes() = fixture { root ->
        val bytes = tar()
        val target = root.resolve("replacement")
        var probes = 0
        val downloads = downloader(bytes) { executable, _ ->
            if (++probes == 2) {
                Files.move(target.resolve("src/runtime"), target.resolve("src/kept-original"))
                Files.createDirectory(target.resolve("src/runtime"))
                Files.writeString(target.resolve("src/runtime/runtime.go"), "runtime")
                throw IOException("final probe rejected")
            }
            identity(executable)
        }
        assertFailsWith<IOException> { downloads.install(archive(bytes), target, GoSdkDownloadCancellation()) }
        assertEquals("runtime", Files.readString(target.resolve("src/runtime/runtime.go")))
        assertEquals("runtime", Files.readString(target.resolve("src/kept-original/runtime.go")))
        assertFalse(Files.exists(target.resolve("bin/go")))
        assertNoStaging(root)
    }

    @Test fun nativeFileIdentityTracksHardLinksAndSamePathFileAndDirectoryReplacement() = fixture { root ->
        // On Windows JBR this exercises the real FileIdInfo fallback when NIO
        // fileKey is absent. It is not skipped on Windows and uses no symlink privilege.
        val file = Files.writeString(root.resolve("original"), "same bytes")
        val linked = Files.createLink(root.resolve("hardlink"), file)
        val key = GoSdkDownloads.fileIdentity(file)
        assertEquals(key, GoSdkDownloads.fileIdentity(file))
        assertEquals(key, GoSdkDownloads.fileIdentity(linked))
        Files.move(file, root.resolve("old-file")); Files.writeString(file, "same bytes")
        assertTrue(key != GoSdkDownloads.fileIdentity(file))
        val directory = Files.createDirectory(root.resolve("directory"))
        val directoryKey = GoSdkDownloads.fileIdentity(directory)
        Files.move(directory, root.resolve("old-directory")); Files.createDirectory(directory)
        assertTrue(directoryKey != GoSdkDownloads.fileIdentity(directory))
    }

    @Test fun replacementAtFinalProbeCannotBeReturnedAsOurInstalledSdk() = fixture { root ->
        val bytes = tar()
        val target = root.resolve("final-replacement")
        var probes = 0
        val downloads = downloader(bytes) { executable, _ ->
            if (++probes == 2) {
                Files.move(target, root.resolve("moved-sdk"))
                Files.createDirectories(target.resolve("bin"))
                Files.writeString(target.resolve("bin/go"), "foreign binary")
            }
            identity(executable)
        }
        assertFailsWith<IOException> { downloads.install(archive(bytes), target, GoSdkDownloadCancellation()) }
        assertEquals("foreign binary", Files.readString(target.resolve("bin/go")))
        assertEquals("binary", Files.readString(root.resolve("moved-sdk/bin/go")))
        assertNoStaging(root)
    }

    @Test fun paxPathsAreValidatedBeforeTheTarLibraryCanNormalizeThem() = fixture { root ->
        for ((index, name) in listOf("/go/absolute", "go/../escape", "go\\escape").withIndex()) {
            val value = "path=$name\n"
            var length = value.toByteArray().size + 2
            while (length != value.toByteArray().size + length.toString().length + 1) length = value.toByteArray().size + length.toString().length + 1
            val data = "$length $value".toByteArray()
            val bytes = ByteArrayOutputStream().also { output -> TarArchiveOutputStream(GZIPOutputStream(output)).use { tar ->
                tar.putArchiveEntry(TarArchiveEntry("PaxHeaders/path", 'x'.code.toByte()).apply { size = data.size.toLong() })
                tar.write(data); tar.closeArchiveEntry()
                tar.putArchiveEntry(TarArchiveEntry("go/placeholder").apply { size = 0 }); tar.closeArchiveEntry()
            } }.toByteArray()
            assertFailsWith<IOException> { downloader(bytes).install(archive(bytes), root.resolve("pax$index"), GoSdkDownloadCancellation()) }
            assertFalse(Files.exists(root.resolve("pax$index"))); assertNoStaging(root)
        }
    }

    @Test fun oversizedPaxMetadataIsRejectedBeforeTheTarReaderAllocatesItsContents() = fixture { root ->
        val bytes = ByteArrayOutputStream().also { output ->
            TarArchiveOutputStream(GZIPOutputStream(output)).use { tar ->
                val data = ByteArray(65_537) { 'a'.code.toByte() }
                tar.putArchiveEntry(TarArchiveEntry("PaxHeaders/go", 'x'.code.toByte()).apply { size = data.size.toLong() })
                tar.write(data); tar.closeArchiveEntry()
            }
        }.toByteArray()
        val error = assertFailsWith<IOException> { downloader(bytes).install(archive(bytes), root.resolve("metadata"), GoSdkDownloadCancellation()) }
        assertTrue(error.message.orEmpty().contains("metadata size limit"))
        assertFalse(Files.exists(root.resolve("metadata"))); assertNoStaging(root)
    }

    @Test fun cancellationClosesTheOwnedBlockedDownloadAndCleansItsStaging() = fixture { root ->
        val entered = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val cancellation = GoSdkDownloadCancellation()
        val downloads = GoSdkDownloads(object : GoSdkDownloadTransport {
            override fun open(uri: URI, cancellation: GoSdkDownloadCancellation) = object : InputStream() {
                override fun read(): Int { entered.countDown(); if (!closed.await(5, TimeUnit.SECONDS)) error("Cancellation did not close the stream"); throw IOException("closed") }
                override fun close() { closed.countDown() }
            }
        }, host = host)
        val target = root.resolve("cancelled")
        val worker = Thread { try { downloads.install(archive(tar()), target, cancellation) } catch (error: Throwable) { failure.set(error) } }
        try {
            worker.start(); assertTrue(entered.await(5, TimeUnit.SECONDS)); cancellation.cancel(); worker.join(5_000)
            assertFalse(worker.isAlive); assertTrue(closed.count == 0L)
            assertTrue(failure.get() is IOException || failure.get() is CancellationException)
            assertFalse(Files.exists(target)); assertNoStaging(root)
        } finally { cancellation.cancel(); worker.join(5_000) }
    }

    @Test fun cancellationDuringFinalProbeRollsBackWithoutRegistering() = fixture { root ->
        val bytes = tar()
        var probes = 0
        val downloads = downloader(bytes) { executable, cancellation ->
            if (++probes == 2) cancellation.cancel()
            identity(executable)
        }
        val target = root.resolve("cancelled-final")
        assertFailsWith<CancellationException> { downloads.install(archive(bytes), target, GoSdkDownloadCancellation()) }
        assertFalse(Files.exists(target)); assertNoStaging(root)
    }

    @Test fun edtCancellationSignalsImmediatelyWithoutWaitingForABlockedResourceClose() {
        val cancellation = GoSdkDownloadCancellation()
        val closing = CountDownLatch(1)
        val release = CountDownLatch(1)
        cancellation.attach { closing.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)) }
        try {
            SwingUtilities.invokeAndWait { cancellation.cancel(); assertTrue(cancellation.isCancelled()) }
            assertTrue(closing.await(5, TimeUnit.SECONDS))
            // invokeAndWait has returned although close is still blocked.
            assertEquals(1L, release.count)
        } finally { release.countDown() }
    }

    @Test fun realSelectedGoBinaryIsProbedAtBothLocationsWithoutChangingItsSource() = fixture { root ->
        val configured = System.getProperty("opengoland.testGo") ?: System.getenv("OPENGOLAND_GO_TEST").orEmpty()
        val executable = GoToolchain.resolve("go", configured) ?: error("A real Go SDK is required for the SDK installation identity test.")
        val selected = GoSdkProbe.inspect(executable)
        val binary = Files.readAllBytes(selected.executable)
        val selectedHost = GoSdkHost.current()
        val entries = listOf(Entry("go/bin/${selected.executable.fileName}", binary, 493), Entry("go/src/runtime/runtime.go", "runtime".toByteArray()),
            Entry("go/pkg/tool/fixture", "SDK root discovery requires this directory".toByteArray()))
        val bytes = if (selectedHost.os == "windows") zip(entries) else tar(entries)
        val downloads = GoSdkDownloads(transport(bytes), host = selectedHost)
        val value = archive(bytes, selectedHost).copy(version = selected.version,
            filename = "${selected.version}.${selectedHost.os}-${selectedHost.arch}.${if (selectedHost.os == "windows") "zip" else "tar.gz"}")
        val installed = downloads.install(value, root.resolve("real SDK 中文😀"), GoSdkDownloadCancellation())
        assertEquals(selected.version, installed.version)
        assertEquals(hash(binary), hash(Files.readAllBytes(installed.executable)))
        assertEquals(hash(binary), hash(Files.readAllBytes(selected.executable)))
        assertTrue(installed.root != selected.root)
    }

    private fun release(version: String, stable: Boolean = true, os: String = host.os, arch: String = host.arch, extension: String = "tar.gz",
                        filename: String = "$version.$os-$arch.$extension", fileVersion: String = version, hash: String = "a".repeat(64)) =
        """{"version":"$version","stable":$stable,"files":[{"version":"$fileVersion","os":"$os","arch":"$arch","kind":"archive","filename":"$filename","size":100,"sha256":"$hash"}]}"""
    private fun identity(executable: Path) = GoModuleSdk(executable.toRealPath(), executable.parent.parent.toRealPath(), version)
    private fun archive(bytes: ByteArray, selectedHost: GoSdkHost = host) = GoSdkArchive(version, selectedHost,
        "$version.${selectedHost.os}-${selectedHost.arch}.${if (selectedHost.os == "windows") "zip" else "tar.gz"}", hash(bytes), bytes.size.toLong())
    private fun transport(bytes: ByteArray) = object : GoSdkDownloadTransport {
        override fun open(uri: URI, cancellation: GoSdkDownloadCancellation): InputStream { cancellation.check(); return ByteArrayInputStream(bytes) }
    }
    private fun downloader(bytes: ByteArray, selectedHost: GoSdkHost = host, verify: (Path, GoSdkDownloadCancellation) -> GoModuleSdk = { executable, _ -> identity(executable) }) =
        GoSdkDownloads(transport(bytes), verify, host = selectedHost)
    private fun standardEntries(binary: String = "go") = listOf(Entry("go/bin/$binary", "binary".toByteArray(), 493), Entry("go/src/runtime/runtime.go", "runtime".toByteArray()))
    private fun tar(entries: List<Entry> = standardEntries()): ByteArray = ByteArrayOutputStream().also { output ->
        TarArchiveOutputStream(GZIPOutputStream(output)).use { archive -> entries.forEach { value ->
            val entry = TarArchiveEntry(value.name, value.type, true).apply { size = if (value.type == '0'.code.toByte()) value.bytes.size.toLong() else 0; mode = value.mode
                if (value.type == '1'.code.toByte() || value.type == '2'.code.toByte()) linkName = "outside" }
            archive.putArchiveEntry(entry); if (entry.isFile) archive.write(value.bytes); archive.closeArchiveEntry()
        } }
    }.toByteArray()
    private fun zip(entries: List<Entry>): ByteArray = ByteArrayOutputStream().also { output ->
        ZipArchiveOutputStream(output).use { archive -> entries.forEach { value ->
            val entry = ZipArchiveEntry(value.name).apply { unixMode = value.mode }
            archive.putArchiveEntry(entry); archive.write(value.bytes); archive.closeArchiveEntry()
        } }
    }.toByteArray()
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun assertNoStaging(root: Path) = assertTrue(Files.list(root).use { paths -> paths.noneMatch { it.fileName.toString().startsWith(".open-goland-sdk-") } })
    private fun fixture(test: (Path) -> Unit) {
        val root = Files.createTempDirectory("open-goland-sdk-download-")
        try { test(root.toRealPath()) } finally { GoSdkProbe.deleteOwnedTree(root) }
    }
}
