package dev.openidea.goide.plugins

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import javax.swing.SwingUtilities

class ChineseLanguagePackTest {
    @Test fun pinnedMetadataSeparatesRemoteRangeAndLocalFullArchiveAuthentication() {
        val source = ChinesePackSource.load()
        assertEquals("261.26222.65", source.platformBuild)
        assertEquals(1_591_745_512, source.archiveBytes)
        assertEquals(1_076_967_468, source.dataOffset)
        assertEquals(3_572_072, source.compressedBytes)
        assertEquals(13_274_509, source.jarBytes)
        assertEquals(setOf("com.intellij.modules.platform"), source.dependencies)
        assertEquals("com.intellij.zh", source.pluginId)
        assertEquals("zh-CN", source.locale)
        assertNotEquals(source.compressedSha256, source.archiveSha256)
    }

    @Test fun fixedCompressedRangeReconstructsTheExactJarAndNothingElse() = Fixture().use { f ->
        val threads = mutableListOf<Boolean>()
        val prepared = f.preparer().prepare(f.source, null, BUILD, ChinesePackCancellation()) { threads += SwingUtilities.isEventDispatchThread() }
        assertTrue(threads.isNotEmpty()); assertTrue(threads.none { it })
        assertArrayEquals(f.jar, preparedJar(prepared))
        assertEquals(1, Files.list(prepared.zip.parent).use { it.count() }.toInt())
        prepared.close()
        assertFalse(Files.exists(prepared.zip))
        f.emptyCache()
    }

    @Test fun localFullZipIsVerifiedAndNeverModifiedOrDeleted() = Fixture().use { f ->
        val prepared = f.preparer().prepare(f.source, f.archive, BUILD, ChinesePackCancellation()) {}
        assertArrayEquals(f.jar, preparedJar(prepared))
        assertEquals(f.source.archiveSha256, hash(Files.readAllBytes(f.archive)))
        prepared.close()
        assertTrue(Files.isRegularFile(f.archive)); assertTrue(Files.isRegularFile(f.sentinel)); f.emptyCache()
    }

    @Test fun fullArchiveHashMismatchRejectsBeforeExtraction() = Fixture().use { f ->
        val changed = Files.readAllBytes(f.archive).apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        Files.write(f.archive, changed)
        fails<IOException> { f.preparer().prepare(f.source, f.archive, BUILD, ChinesePackCancellation()) {} }
        assertArrayEquals(changed, Files.readAllBytes(f.archive)); f.emptyCache()
    }

    @Test fun compressedHashMismatchAndTruncationCannotReachNativeInstallation() = Fixture().use { f ->
        fails<IOException> { f.preparer().prepare(f.source.copy(compressedSha256 = "0".repeat(64)), null, BUILD, ChinesePackCancellation()) {} }
        fails<IOException> { f.preparer(f.compressed.dropLast(1).toByteArray()).prepare(f.source, null, BUILD, ChinesePackCancellation()) {} }
        fails<IOException> { f.preparer(f.compressed + 1).prepare(f.source, null, BUILD, ChinesePackCancellation()) {} }
        f.emptyCache()
    }

    @Test fun inflatedLimitAndTrailingDeflateDataAreRejected() = Fixture().use { f ->
        fails<IOException> { f.preparer().prepare(f.source.copy(jarBytes = f.jar.size.toLong() - 1), null, BUILD, ChinesePackCancellation()) {} }
        val trailing = f.compressed + 1
        fails<IOException> { f.preparer(trailing).prepare(f.source.copy(compressedBytes = trailing.size.toLong(), compressedSha256 = hash(trailing)), null, BUILD, ChinesePackCancellation()) {} }
        f.emptyCache()
    }

    @Test fun authenticatedButWrongDescriptorIdentityBuildDependenciesAndCodeAreRejected() {
        listOf(
            descriptor().replace("com.intellij.zh", "fixture.other"),
            descriptor().replace("until-build=\"$BUILD\"", "until-build=\"999.1\""),
            descriptor().replace("com.intellij.modules.platform", "fixture.missing"),
            descriptor().replace("locale=\"zh-CN\"", "locale=\"ja\"")
        ).forEach { xml -> Fixture(xml = xml).use { f ->
            fails<IOException> { f.preparer().prepare(f.source, f.archive, BUILD, ChinesePackCancellation()) {} }; f.emptyCache()
        } }
        Fixture(code = true).use { f ->
            fails<IOException> { f.preparer().prepare(f.source, null, BUILD, ChinesePackCancellation()) {} }; f.emptyCache()
        }
    }

    @Test fun zipSlipDuplicateAndSymlinkEntriesAreRejectedWithoutTouchingOutsideFiles() {
        listOf(
            Extra("plugins/localization-zh/../../escape.jar", byteArrayOf(1), 0),
            Extra(ENTRY, byteArrayOf(1), 0),
            Extra("plugins/localization-zh/link", "outside".toByteArray(), 41471)
        ).forEach { extra -> Fixture(extra = extra).use { f ->
            fails<IOException> { f.preparer().prepare(f.source, f.archive, BUILD, ChinesePackCancellation()) {} }
            assertTrue(Files.isRegularFile(f.sentinel)); assertFalse(Files.exists(f.root.resolve("escape.jar"))); f.emptyCache()
        } }
    }

    @Test fun cancellationDuringTransferRemovesOnlyItsOwnPartialDirectory() = Fixture().use { f ->
        val cancellation = ChinesePackCancellation()
        fails<CancellationException> {
            f.preparer().prepare(f.source, null, BUILD, cancellation) { cancellation.cancel() }
        }
        assertTrue(Files.isRegularFile(f.archive)); assertTrue(Files.isRegularFile(f.sentinel)); f.emptyCache()
    }

    @Test fun wrongPlatformBuildRejectsBeforeFilesystemOrNetworkWork() = Fixture().use { f ->
        val touched = AtomicBoolean()
        val preparer = ChinesePackPreparer(ChinesePackTransport { _, _, _ -> touched.set(true) }, { touched.set(true); f.cache })
        fails<IOException> { preparer.prepare(f.source, null, "261.26222.72", ChinesePackCancellation()) {} }
        assertFalse(touched.get())
    }

    @Test fun retentionBeforeNativeHandoffSurvivesCloseAndCannotReviveDiscardedArchive() = Fixture().use { f ->
        val first = f.preparer().prepare(f.source, null, BUILD, ChinesePackCancellation()) {}
        assertTrue(first.retainForNativeInstaller()); first.close()
        assertTrue(Files.isRegularFile(first.zip))
        val discarded = f.preparer().prepare(f.source, null, BUILD, ChinesePackCancellation()) {}
        discarded.close()
        assertFalse(discarded.retainForNativeInstaller())
        assertFalse(Files.exists(discarded.zip))
    }

    @Test fun cancellationOnEdtDoesNotWaitForBlockingNetworkClose() {
        val token = ChinesePackCancellation()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        token.attach { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        try {
            val start = System.nanoTime()
            SwingUtilities.invokeAndWait { token.cancel() }
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1000)
            assertTrue(token.isCancelled()); assertTrue(entered.await(2, TimeUnit.SECONDS))
        } finally { release.countDown() }
    }

    @Test fun exactHttp206RangeIsRequiredAndHttp200NeverSilentlyDownloadsTheWholeIde() = Fixture().use { f ->
        val expected = "bytes " + f.source.dataOffset + "-" + (f.source.dataOffset + f.source.compressedBytes - 1) + "/" + f.source.archiveBytes
        var connection = Connection(f.source.downloadUrl.toURL(), f.compressed, 206, expected, f.compressed.size.toLong(), null)
        val transport = OfficialChinesePackTransport { connection }
        val received = ByteArrayOutputStream()
        transport.read(f.source, ChinesePackCancellation()) { it.copyTo(received) }
        assertArrayEquals(f.compressed, received.toByteArray())
        assertEquals("bytes=" + f.source.dataOffset + "-" + (f.source.dataOffset + f.source.compressedBytes - 1), connection.getRequestProperty("Range"))
        listOf(
            Connection(f.source.downloadUrl.toURL(), f.compressed, 200, expected, f.compressed.size.toLong(), null),
            Connection(f.source.downloadUrl.toURL(), f.compressed, 206, expected + "0", f.compressed.size.toLong(), null),
            Connection(f.source.downloadUrl.toURL(), f.compressed, 206, expected, f.compressed.size.toLong() + 1, null),
            Connection(f.source.downloadUrl.toURL(), f.compressed, 206, expected, f.compressed.size.toLong(), "gzip")
        ).forEach {
            connection = it
            val consumed = AtomicBoolean()
            fails<IOException> { transport.read(f.source, ChinesePackCancellation()) { consumed.set(true) } }
            assertFalse(consumed.get())
        }
    }

    @Test fun redirectsMustStayOnOfficialHttpsHosts() {
        val transport = OfficialChinesePackTransport()
        listOf("https://download.jetbrains.com/idea/test", "https://download-cdn.clf.jetbrains.com.cn/path?signature=transient")
            .forEach { transport.requireOfficialDownload(URI(it)) }
        listOf("http://download.jetbrains.com/a", "https://download.jetbrains.com.evil.test/a", "https://user@download.jetbrains.com/a", "https://download.jetbrains.com:80/a")
            .forEach { value -> fails<IOException> { transport.requireOfficialDownload(URI(value)) } }
    }

    @Test fun preparationCannotPerformOwnIoOnTheEdt() = Fixture().use { f ->
        var failure: Throwable? = null
        SwingUtilities.invokeAndWait { failure = runCatching { f.preparer().prepare(f.source, null, BUILD, ChinesePackCancellation()) {} }.exceptionOrNull() }
        assertTrue(failure is IllegalStateException); assertFalse(Files.exists(f.cache))
    }

    private class Connection(url: URL, private val bytes: ByteArray, private val code: Int,
        private val range: String, private val length: Long, private val encoding: String?) : HttpURLConnection(url) {
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false
        override fun getResponseCode() = code
        override fun getInputStream() = ByteArrayInputStream(bytes)
        override fun getContentLengthLong() = length
        override fun getHeaderField(name: String?): String? = when (name) { "Content-Range" -> range; "Content-Encoding" -> encoding; else -> null }
    }

    private data class Extra(val name: String, val bytes: ByteArray, val mode: Int)

    private class Fixture(xml: String = descriptor(), code: Boolean = false, extra: Extra? = null) : AutoCloseable {
        val root: Path = Files.createTempDirectory("chinese pack 中文😀 ")
        val cache = root.resolve("cache")
        val sentinel = Files.writeString(root.resolve("user-file.txt"), "keep")
        val jar = zip(listOf(Extra("META-INF/plugin.xml", xml.toByteArray(), 0),
            Extra(if (code) "fixture/Code.class" else "messages/Fixture.properties", "fixture=中文😀".toByteArray(), 0)))
        val full = zip(listOf(Extra(ENTRY, jar, 0)) + listOfNotNull(extra))
        val archive = Files.write(root.resolve("official.zip"), full)
        val compressed: ByteArray
        val source: ChinesePackSource
        init {
            ZipFile.builder().setPath(archive).get().use { file ->
                val entry = file.getEntry(ENTRY)
                compressed = full.copyOfRange(entry.dataOffset.toInt(), (entry.dataOffset + entry.compressedSize).toInt())
                source = ChinesePackSource(BUILD, "2026.1.4", URI("https://download.jetbrains.com/idea/idea-2026.1.4.win.zip"),
                    full.size.toLong(), hash(full), ENTRY, jar.size.toLong(), hash(jar), "META-INF/plugin.xml",
                    xml.toByteArray().size.toLong(), hash(xml.toByteArray()), "com.intellij.zh", NAME, BUILD,
                    setOf("com.intellij.modules.platform"), "zh-CN", URI("https://www.jetbrains.com/idea/download/"),
                    URI("https://www.jetbrains.com/legal/docs/toolbox/user/"), entry.dataOffset, compressed.size.toLong(), hash(compressed))
            }
        }
        fun preparer(body: ByteArray = compressed) = ChinesePackPreparer(ChinesePackTransport { _, token, consume ->
            assertFalse(SwingUtilities.isEventDispatchThread()); token.check(); consume(ByteArrayInputStream(body))
        }, { cache })
        fun emptyCache() { if (Files.exists(cache)) assertEquals(0, Files.list(cache).use { it.count() }.toInt()) }
        override fun close() { Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
    }

    companion object {
        private const val BUILD = "261.26222.65"
        private const val NAME = "Chinese (Simplified) Language Pack / 中文语言包"
        private const val ENTRY = "plugins/localization-zh/lib/localization-zh.jar"
        private fun descriptor() = """<idea-plugin><id>com.intellij.zh</id><name>$NAME</name><version>$BUILD</version><idea-version since-build="$BUILD" until-build="$BUILD"/><depends>com.intellij.modules.platform</depends><extensions defaultExtensionNs="com.intellij"><languageBundle locale="zh-CN"/></extensions></idea-plugin>"""
        private fun hash(bytes: ByteArray) = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
        private fun zip(entries: List<Extra>): ByteArray {
            val output = ByteArrayOutputStream()
            ZipArchiveOutputStream(output).use { zip -> entries.forEach { value ->
                zip.putArchiveEntry(ZipArchiveEntry(value.name).apply { if (value.mode != 0) unixMode = value.mode })
                zip.write(value.bytes); zip.closeArchiveEntry()
            } }
            return output.toByteArray()
        }
        private fun preparedJar(prepared: PreparedChinesePack): ByteArray = java.util.zip.ZipFile(prepared.zip.toFile()).use { zip ->
            assertEquals(1, zip.size())
            zip.getInputStream(zip.getEntry("localization-zh/lib/localization-zh.jar")).use { it.readAllBytes() }
        }
        private inline fun <reified T : Throwable> fails(action: () -> Unit) {
            val failure = runCatching(action).exceptionOrNull()
            assertTrue("Expected " + T::class.java.simpleName + ", got " + failure, T::class.java.isInstance(failure))
        }
    }
}
