package dev.openidea.goide.plugins

import com.google.gson.JsonParser
import com.intellij.openapi.application.PathManager
import com.intellij.util.net.HttpConfigurable
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.atomic.AtomicInteger
import java.util.jar.JarFile
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.swing.SwingUtilities
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/** Public metadata only; no JetBrains translation resources are distributed with the IDE. */
internal data class ChinesePackSource(
    val platformBuild: String, val ideaVersion: String, val downloadUrl: URI,
    val archiveBytes: Long, val archiveSha256: String, val archiveEntry: String,
    val jarBytes: Long, val jarSha256: String, val descriptorEntry: String,
    val descriptorBytes: Long, val descriptorSha256: String, val pluginId: String,
    val pluginName: String, val pluginVersion: String, val dependencies: Set<String>,
    val locale: String, val sourcePage: URI, val licensePage: URI,
    val dataOffset: Long, val compressedBytes: Long, val compressedSha256: String,
) {
    companion object {
        fun load(): ChinesePackSource {
            backgroundOnly()
            val bytes = requireNotNull(ChinesePackSource::class.java.getResourceAsStream("/plugins/chinese-pack-source.json")).use { it.readAllBytes() }
            val data = JsonParser.parseString(String(bytes, Charsets.UTF_8)).asJsonObject
            require(data["schema"].asInt == 1 && !data["redistributedByProduct"].asBoolean)
            fun text(name: String) = data[name].asString
            return ChinesePackSource(text("platformBuild"), text("ideaVersion"), URI(text("downloadUrl")),
                data["archiveBytes"].asLong, text("archiveSha256"), text("archiveEntry"),
                data["jarBytes"].asLong, text("jarSha256"), text("descriptorEntry"),
                data["descriptorBytes"].asLong, text("descriptorSha256"), text("pluginId"), text("pluginName"),
                text("pluginVersion"), data.getAsJsonArray("dependencies").map { it.asString }.toSet(),
                text("locale"), URI(text("sourcePage")), URI(text("licensePage")),
                data["dataOffset"].asLong, data["compressedBytes"].asLong, text("compressedSha256")).also {
                require(it.archiveBytes in 1..2_000_000_000 && it.jarBytes in 1..64L * 1024 * 1024)
                require(listOf(it.archiveSha256, it.jarSha256, it.descriptorSha256, it.compressedSha256).all { hash -> hash.matches(Regex("[0-9a-f]{64}")) })
                require(data["compressionMethod"].asInt == 8 && data["compressionFlags"].asInt == 0)
                require(it.dataOffset >= 0 && it.compressedBytes in 1..64L * 1024 * 1024 && it.dataOffset <= it.archiveBytes - it.compressedBytes)
                require(it.downloadUrl.toString() == "https://download.jetbrains.com/idea/idea-" + it.ideaVersion + ".win.zip")
            }
        }
    }
}

internal data class ChinesePackProgress(val message: String, val completed: Long = 0, val total: Long? = null)

/** Invalidate immediately; closing only this operation's network handles stays off the EDT. */
internal class ChinesePackCancellation {
    private var cancelled = false
    private val resources = LinkedHashSet<() -> Unit>()
    @Synchronized fun isCancelled() = cancelled
    fun check() { if (isCancelled() || Thread.currentThread().isInterrupted) throw CancellationException("已取消语言包准备。") }
    fun attach(close: () -> Unit): AutoCloseable {
        val accepted = synchronized(this) {
            if (cancelled || Thread.currentThread().isInterrupted) false else { resources.add(close); true }
        }
        if (!accepted) {
            if (SwingUtilities.isEventDispatchThread()) ForkJoinPool.commonPool().execute { runCatching(close) }
            else runCatching(close)
            throw CancellationException("已取消语言包准备。")
        }
        return AutoCloseable { synchronized(this) { resources.remove(close) } }
    }
    fun cancel() {
        val close = synchronized(this) { if (cancelled) return; cancelled = true; resources.toList().also { resources.clear() } }
        val action = Runnable { close.forEach { runCatching(it) } }
        if (SwingUtilities.isEventDispatchThread()) ForkJoinPool.commonPool().execute(action) else action.run()
    }
}

/** Native startup scripts may need the ZIP after exit; never delete an adopted archive. */
internal class PreparedChinesePack internal constructor(val zip: Path, private val directory: Path) : AutoCloseable {
    private val state = AtomicInteger() // 0 prepared, 1 retained for native startup, 2 discarded
    // Retain BEFORE native handoff: startup commands are written before callbacks.
    fun retainForNativeInstaller(): Boolean = state.compareAndSet(0, 1) || state.get() == 1
    override fun close() {
        if (state.compareAndSet(0, 2)) {
            val cleanup = Runnable { deleteOwnedDirectory(directory) }
            if (SwingUtilities.isEventDispatchThread()) ForkJoinPool.commonPool().execute(cleanup) else cleanup.run()
        }
    }
}

internal fun interface ChinesePackTransport {
    fun read(source: ChinesePackSource, cancellation: ChinesePackCancellation, consume: (InputStream) -> Unit)
}

internal class OfficialChinesePackTransport(
    private val connect: (URI) -> HttpURLConnection = { HttpConfigurable.getInstance().openHttpConnection(it.toString()) },
) : ChinesePackTransport {
    override fun read(source: ChinesePackSource, cancellation: ChinesePackCancellation, consume: (InputStream) -> Unit) {
        backgroundOnly()
        var uri = source.downloadUrl
        repeat(8) {
            cancellation.check()
            requireOfficialDownload(uri)
            val connection = connect(uri).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000; readTimeout = 60_000
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("Range", "bytes=" + source.dataOffset + "-" + (source.dataOffset + source.compressedBytes - 1))
            }
            cancellation.attach { connection.disconnect() }.use {
                try {
                    val code = connection.responseCode
                    if (code in setOf(301, 302, 303, 307, 308)) {
                        uri = uri.resolve(connection.getHeaderField("Location") ?: throw IOException("The official download returned an invalid redirect."))
                    } else {
                        val expectedRange = "bytes " + source.dataOffset + "-" + (source.dataOffset + source.compressedBytes - 1) + "/" + source.archiveBytes
                        if (code != HttpURLConnection.HTTP_PARTIAL || connection.getHeaderField("Content-Range") != expectedRange ||
                            connection.contentLengthLong != source.compressedBytes ||
                            connection.getHeaderField("Content-Encoding")?.let { !it.equals("identity", true) } == true)
                            throw IOException("官方服务器未返回匹配的数据，请重试或选择完整的官方 ZIP 安装包。")
                        connection.inputStream.use { input -> cancellation.attach { input.close() }.use { consume(input) } }
                        return
                    }
                } finally { connection.disconnect() }
            }
        }
        throw IOException("The official download redirected too many times.")
    }

    internal fun requireOfficialDownload(uri: URI) {
        val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
        if (uri.scheme != "https" || uri.rawUserInfo != null || uri.fragment != null || uri.port !in setOf(-1, 443) ||
            !(host == "download.jetbrains.com" || host.endsWith(".jetbrains.com") || host.endsWith(".jetbrains.com.cn")))
            throw IOException("The official download redirected outside its trusted HTTPS hosts.")
    }
}

internal fun interface ChinesePackPreparation {
    fun prepare(source: ChinesePackSource, localArchive: Path?, actualBuild: String,
        cancellation: ChinesePackCancellation, progress: (ChinesePackProgress) -> Unit): PreparedChinesePack
}

internal class ChinesePackPreparer(
    private val transport: ChinesePackTransport = OfficialChinesePackTransport(),
    private val temporaryRoot: () -> Path = { Path.of(PathManager.getSystemPath()).resolve("open-goland-language-packs") },
) : ChinesePackPreparation {
    override fun prepare(source: ChinesePackSource, localArchive: Path?, actualBuild: String,
        cancellation: ChinesePackCancellation, progress: (ChinesePackProgress) -> Unit): PreparedChinesePack {
        backgroundOnly()
        cancellation.check()
        if (actualBuild != source.platformBuild) throw IOException("This Chinese pack matches platform build " + source.platformBuild + "; this IDE uses $actualBuild.")
        val parent = temporaryRoot()
        Files.createDirectories(parent)
        val directory = Files.createTempDirectory(parent, "chinese-" + source.platformBuild + "-")
        var complete = false
        try {
            val jar = directory.resolve("localization-zh.jar")
            if (localArchive == null) {
                val compressed = directory.resolve("official-language-pack.deflate")
                transport.read(source, cancellation) { input ->
                    copyVerified(input, compressed, source.compressedBytes, source.compressedSha256, cancellation) { done ->
                        progress(ChinesePackProgress("正在下载并校验语言包…", done, source.compressedBytes))
                    }
                }
                cancellation.check()
                val inflater = Inflater(true)
                try {
                    Files.newInputStream(compressed).use { input ->
                        InflaterInputStream(input, inflater, 64 * 1024).use { decoded ->
                            copyVerified(decoded, jar, source.jarBytes, source.jarSha256, cancellation) { done ->
                                progress(ChinesePackProgress("正在验证语言包…", done, source.jarBytes))
                            }
                            if (!inflater.finished() || inflater.bytesRead != source.compressedBytes)
                                throw IOException("The language-pack compressed data is incomplete or has unexpected trailing data.")
                        }
                    }
                } finally { inflater.end() }
                Files.delete(compressed)
            } else {
                val archive = directory.resolve("official-idea.zip")
                if (!Files.isRegularFile(localArchive, NOFOLLOW_LINKS)) throw IOException("Choose the downloaded official ZIP file, not a link or directory.")
                Files.newInputStream(localArchive).use { input -> cancellation.attach { input.close() }.use {
                    copyVerified(input, archive, source.archiveBytes, source.archiveSha256, cancellation) { done ->
                        progress(ChinesePackProgress("正在校验本地官方安装包…", done, source.archiveBytes))
                    }
                } }
                progress(ChinesePackProgress("正在准备中文语言包…"))
                extract(source, archive, jar, cancellation)
                Files.delete(archive)
            }
            validateDescriptor(source, jar, cancellation)
            val zip = directory.resolve("localization-zh.zip")
            ZipOutputStream(Files.newOutputStream(zip)).use { output ->
                output.putNextEntry(ZipEntry("localization-zh/lib/localization-zh.jar").apply { time = 0 })
                Files.newInputStream(jar).use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        cancellation.check()
                        val count = input.read(buffer); if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                }
                output.closeEntry()
            }
            cancellation.check()
            Files.delete(jar)
            complete = true
            return PreparedChinesePack(zip, directory)
        } finally { if (!complete) deleteOwnedDirectory(directory) }
    }

    private fun copyVerified(input: InputStream, target: Path, expectedBytes: Long, expectedHash: String,
        cancellation: ChinesePackCancellation, progress: (Long) -> Unit) {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        Files.newOutputStream(target).use { output ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                cancellation.check()
                val count = input.read(buffer); if (count < 0) break
                total += count
                if (total > expectedBytes) throw IOException("The language-pack data exceeds its verified size.")
                digest.update(buffer, 0, count); output.write(buffer, 0, count)
                progress(total)
            }
        }
        if (total != expectedBytes || hex(digest.digest()) != expectedHash)
            throw IOException("语言包大小或 SHA-256 校验失败，请重试或使用匹配版本的官方安装包。")
    }

    private fun extract(source: ChinesePackSource, archive: Path, jar: Path, cancellation: ChinesePackCancellation) {
        var found = false
        var count = 0
        ZipFile.builder().setPath(archive).get().use { input ->
            val entries = input.entries
            while (entries.hasMoreElements()) {
                cancellation.check()
                if (++count > 100_000) throw IOException("The official archive contains too many entries.")
                val entry = entries.nextElement()
                if (!entry.name.lowercase(Locale.ROOT).startsWith("plugins/localization-zh/")) continue
                val raw = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(entry.rawName)).toString()
                safeEntry(raw)
                val type = entry.unixMode and 61440
                if (raw != entry.name || entry.isUnixSymlink || type !in setOf(0, 32768, 16384) || !input.canReadEntryData(entry))
                    throw IOException("The language pack contains a link, encrypted or ambiguous ZIP entry.")
                if (entry.isDirectory) continue
                if (entry.name != source.archiveEntry || found) throw IOException("The language pack has unexpected or duplicate files.")
                if (entry.size != source.jarBytes) throw IOException("The Chinese language pack size does not match the official file.")
                found = true
                val digest = MessageDigest.getInstance("SHA-256"); var total = 0L
                input.getInputStream(entry).use { stream -> Files.newOutputStream(jar).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        cancellation.check()
                        val size = stream.read(buffer); if (size < 0) break
                        total += size
                        if (total > source.jarBytes) throw IOException("The language pack exceeded its declared size.")
                        digest.update(buffer, 0, size); output.write(buffer, 0, size)
                    }
                } }
                if (total != source.jarBytes || hex(digest.digest()) != source.jarSha256) throw IOException("The Chinese language pack SHA-256 verification failed.")
            }
        }
        if (!found) throw IOException("The official archive does not contain the matching Chinese language pack.")
    }

    private fun validateDescriptor(source: ChinesePackSource, jar: Path, cancellation: ChinesePackCancellation) {
        JarFile(jar.toFile()).use { input ->
            var entries = 0; var bytes = 0L
            val names = HashSet<String>()
            val values = input.entries()
            while (values.hasMoreElements()) {
                cancellation.check()
                val value = values.nextElement()
                safeEntry(value.name)
                if (!names.add(value.name) || ++entries > 20_000 || value.name.endsWith(".class") || value.size < 0 || value.size > 64L * 1024 * 1024)
                    throw IOException("The language pack contains unexpected code, duplicate or oversized resources.")
                bytes += value.size
                if (bytes > 512L * 1024 * 1024) throw IOException("The language pack resources exceed the size limit.")
            }
            val entry = input.getJarEntry(source.descriptorEntry) ?: throw IOException("The language pack descriptor is missing.")
            if (entry.size != source.descriptorBytes || entry.size > 64 * 1024) throw IOException("Unexpected language pack descriptor size.")
            val xml = input.getInputStream(entry).use { it.readNBytes(source.descriptorBytes.toInt() + 1) }
            if (xml.size.toLong() != source.descriptorBytes || hex(MessageDigest.getInstance("SHA-256").digest(xml)) != source.descriptorSha256)
                throw IOException("The language pack descriptor SHA-256 verification failed.")
            val factory = DocumentBuilderFactory.newInstance().apply {
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
                isXIncludeAware = false; isExpandEntityReferences = false
            }
            val root = factory.newDocumentBuilder().parse(ByteArrayInputStream(xml)).documentElement
            fun elements(element: Element): List<Element> = (0 until element.childNodes.length).mapNotNull { element.childNodes.item(it) as? Element }
            fun children(element: Element, name: String) = elements(element).filter { it.tagName == name }
            fun text(name: String) = children(root, name).singleOrNull()?.textContent?.trim()
            val version = children(root, "idea-version").singleOrNull()
            val dependencies = children(root, "depends")
            val extensions = children(root, "extensions").flatMap(::elements)
            if (root.tagName != "idea-plugin" || text("id") != source.pluginId || text("name") != source.pluginName ||
                text("version") != source.pluginVersion || version?.getAttribute("since-build") != source.platformBuild ||
                version?.getAttribute("until-build") != source.platformBuild ||
                dependencies.map { it.textContent.trim() }.toSet() != source.dependencies || dependencies.any { it.getAttribute("optional") == "true" } ||
                extensions.size != 1 || extensions.single().tagName != "languageBundle" || extensions.single().getAttribute("locale") != source.locale)
                throw IOException("The Chinese language pack identity, build or dependencies do not match this IDE.")
        }
    }

    private fun safeEntry(name: String) {
        val parts = name.removeSuffix("/").split('/')
        if (name.isEmpty() || name.length > 4096 || name.startsWith('/') || name.any { it <= '\u001f' || it == '\\' || it == ':' } ||
            parts.any { it.isEmpty() || it == "." || it == ".." || it.endsWith('.') || it.endsWith(' ') })
            throw IOException("Unsafe Chinese language pack archive path.")
    }
}

private fun backgroundOnly() { check(!SwingUtilities.isEventDispatchThread()) { "Chinese language pack I/O must run in the background." } }
private fun hex(bytes: ByteArray) = java.util.HexFormat.of().formatHex(bytes)
private fun deleteOwnedDirectory(directory: Path) {
    Files.walkFileTree(directory, object : SimpleFileVisitor<Path>() {
        override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult { Files.delete(file); return FileVisitResult.CONTINUE }
        override fun postVisitDirectory(dir: Path, error: IOException?): FileVisitResult { if (error != null) throw error; Files.delete(dir); return FileVisitResult.CONTINUE }
    })
}
