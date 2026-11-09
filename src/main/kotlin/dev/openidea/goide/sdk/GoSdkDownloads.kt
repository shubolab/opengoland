package dev.openidea.goide.sdk

import com.google.gson.JsonParser
import com.intellij.util.net.HttpConfigurable
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinNT
import dev.openidea.goide.project.GoModuleSdk
import dev.openidea.goide.project.GoSdkProbe
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.FilterInputStream
import java.io.PushbackInputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.ForkJoinPool
import java.util.zip.CRC32
import java.util.zip.GZIPInputStream
import javax.swing.SwingUtilities

internal data class GoSdkHost(val os: String, val arch: String) {
    companion object {
        fun current(): GoSdkHost {
            val os = System.getProperty("os.name").lowercase(Locale.ROOT)
            val arch = when (System.getProperty("os.arch").lowercase(Locale.ROOT)) {
                "aarch64", "arm64" -> "arm64"
                "amd64", "x86_64" -> "amd64"
                else -> throw IOException("Go SDK downloads are unavailable for this processor.")
            }
            return GoSdkHost(when {
                os.startsWith("mac") || os.startsWith("darwin") -> "darwin"
                os.startsWith("windows") -> "windows"
                os.startsWith("linux") -> "linux"
                else -> throw IOException("Go SDK downloads are unavailable for this operating system.")
            }, arch)
        }
    }
}

internal data class GoSdkArchive(val version: String, val host: GoSdkHost, val filename: String, val sha256: String, val size: Long) {
    override fun toString() = version.removePrefix("go")
    val uri: URI get() = URI("https://go.dev/dl/$filename")
}

internal data class GoSdkDownloadProgress(val message: String, val completed: Long = 0, val total: Long? = null)

/** Cancellation closes only this operation's network streams/connections. */
internal class GoSdkDownloadCancellation {
    private var cancelled = false
    private val resources = LinkedHashSet<() -> Unit>()
    @Synchronized fun isCancelled() = cancelled
    fun check() { if (isCancelled() || Thread.currentThread().isInterrupted) throw CancellationException("Go SDK download cancelled.") }
    fun cancel() {
        val close = synchronized(this) { cancelled = true; resources.toList().also { resources.clear() } }
        val release = Runnable { close.forEach { runCatching(it) } }
        // URLConnection.disconnect / InputStream.close can block. Cancellation
        // and disposal must never wait for network cleanup on the EDT.
        if (SwingUtilities.isEventDispatchThread()) ForkJoinPool.commonPool().execute(release) else release.run()
    }
    fun attach(close: () -> Unit): AutoCloseable {
        synchronized(this) { check(); resources.add(close) }
        return AutoCloseable { synchronized(this) { resources.remove(close) } }
    }
}

internal interface GoSdkDownloadTransport {
    fun open(uri: URI, cancellation: GoSdkDownloadCancellation): InputStream
}

/** The catalogue and archives are the official Go distribution, never an executable installer. */
internal class GoSdkDownloads(
    private val transport: GoSdkDownloadTransport = OfficialGoSdkTransport,
    private val verify: (Path, GoSdkDownloadCancellation) -> GoModuleSdk = { executable, cancellation ->
        GoSdkProbe.inspect(executable.toString(), cancellation::isCancelled)
    },
    private val limits: Limits = Limits(),
    private val host: GoSdkHost = GoSdkHost.current(),
) {
    internal data class Limits(val archiveBytes: Long = 512L * 1024 * 1024, val unpackedBytes: Long = 2L * 1024 * 1024 * 1024,
                               val entryBytes: Long = 256L * 1024 * 1024, val entries: Int = 100_000)

    fun catalogue(host: GoSdkHost, cancellation: GoSdkDownloadCancellation): List<GoSdkArchive> {
        backgroundOnly(); cancellation.check()
        val bytes = transport.open(CATALOGUE, cancellation).use { input ->
            val output = ByteArrayOutputStream()
            cancellation.attach(input::close).use {
                copy(input, cancellation, 16L * 1024 * 1024) { buffer, count -> output.write(buffer, 0, count) }
            }
            output.toByteArray()
        }
        return parseCatalogue(bytes, host)
    }

    internal fun parseCatalogue(bytes: ByteArray, host: GoSdkHost): List<GoSdkArchive> {
        val text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        try {
            val root = JsonParser.parseString(text)
            require(root.isJsonArray) { "Go returned no download catalogue." }
            val result = mutableListOf<GoSdkArchive>()
            for (item in root.asJsonArray) {
                val release = item.asJsonObject
                if (release.get("stable")?.asBoolean != true) continue
                val version = release.get("version").asString
                if (!VERSION.matches(version)) continue
                for (value in release.getAsJsonArray("files")) {
                    val file = value.asJsonObject
                    if (file.get("kind")?.asString != "archive" || file.get("os")?.asString != host.os || file.get("arch")?.asString != host.arch) continue
                    val suffix = if (host.os == "windows") "zip" else "tar.gz"
                    val filename = file.get("filename").asString
                    val hash = file.get("sha256").asString
                    val size = file.get("size").asLong
                    require(file.get("version")?.asString == version && filename == "$version.${host.os}-${host.arch}.$suffix" &&
                        HASH.matches(hash) && size in 1..limits.archiveBytes) { "Go returned an invalid SDK archive entry." }
                    result += GoSdkArchive(version, host, filename, hash, size)
                }
            }
            require(result.map { it.filename }.toSet().size == result.size && result.map { it.version }.toSet().size == result.size) { "Go returned duplicate SDK archive entries." }
            return result.sortedWith(compareByDescending<GoSdkArchive> { versionParts(it.version)[0] }
                .thenByDescending { versionParts(it.version)[1] }.thenByDescending { versionParts(it.version)[2] })
        } catch (error: RuntimeException) {
            throw IOException("Unable to read the official Go SDK catalogue: ${error.message}", error)
        }
    }

    fun install(archive: GoSdkArchive, destination: Path, cancellation: GoSdkDownloadCancellation,
                progress: (GoSdkDownloadProgress) -> Unit = {}): GoModuleSdk {
        backgroundOnly(); cancellation.check()
        require(destination.isAbsolute && destination.normalize().parent != null) { "Choose an absolute, new Go SDK directory." }
        require(archive.host == host && VERSION.matches(archive.version) && HASH.matches(archive.sha256) && archive.size in 1..limits.archiveBytes &&
            archive.filename == "${archive.version}.${archive.host.os}-${archive.host.arch}.${if (archive.host.os == "windows") "zip" else "tar.gz"}") { "Invalid Go SDK archive." }
        val requested = destination.normalize()
        if (Files.exists(requested, NOFOLLOW_LINKS)) throw IOException("SDK location already exists. Choose a new directory: $requested")
        Files.createDirectories(requested.parent)
        val parent = requested.parent.toRealPath()
        val parentKey = fileIdentity(parent)
        val target = parent.resolve(requested.fileName)
        val staging = Files.createTempDirectory(parent, ".open-goland-sdk-")
        val stagingKey = try { fileIdentity(staging) } catch (error: Exception) { runCatching { Files.delete(staging) }; throw error }
        var ownedFiles = emptyMap<Path, OwnedFile>()
        var ownedDirectories = emptyMap<Path, Any>()
        var targetKey: Any? = null
        var reserved = false
        var complete = false
        try {
            val compressed = staging.resolve("download")
            val digest = MessageDigest.getInstance("SHA-256")
            var received = 0L
            progress(GoSdkDownloadProgress("Downloading ${archive.version}…", 0, archive.size))
            transport.open(archive.uri, cancellation).use { input ->
                cancellation.attach(input::close).use { Files.newOutputStream(compressed, CREATE_NEW, WRITE).use { output ->
                    copy(input, cancellation, archive.size) { buffer, count ->
                        output.write(buffer, 0, count); digest.update(buffer, 0, count); received += count
                        progress(GoSdkDownloadProgress("Downloading ${archive.version}…", received, archive.size))
                    }
                } }
            }
            if (received != archive.size) throw IOException("The Go SDK download was incomplete (expected ${archive.size} bytes, received $received).")
            if (digest.digest().joinToString("") { "%02x".format(it) } != archive.sha256) throw IOException("Go SDK checksum verification failed. Download again.")
            cancellation.check()
            progress(GoSdkDownloadProgress("Extracting verified Go SDK…"))
            val unpack = Files.createDirectory(staging.resolve("unpack"))
            ownedFiles = extract(compressed, archive.filename.endsWith(".zip"), unpack, cancellation)
            val root = unpack.resolve("go")
            val executableName = if (archive.host.os == "windows") "go.exe" else "go"
            val executable = root.resolve("bin").resolve(executableName)
            progress(GoSdkDownloadProgress("Verifying Go SDK…"))
            val checked = verify(executable, cancellation)
            validateIdentity(checked, root, archive)
            cancellation.check()
            ownedDirectories = Files.walk(root).use { paths -> paths.filter { Files.isDirectory(it, NOFOLLOW_LINKS) }
                .map { root.relativize(it) to fileIdentity(it) }.toList().toMap() }
            if (parent.toRealPath() != parent || fileIdentity(parent) != parentKey)
                throw IOException("The SDK parent directory changed during download. Choose the location again.")
            // createDirectory reserves the destination exclusively. No move replaces
            // an existing SDK, including one created while this download was running.
            Files.createDirectory(target); reserved = true; targetKey = fileIdentity(target)
            Files.list(root).use { files -> files.forEach { source ->
                cancellation.check()
                val path = target.resolve(source.fileName)
                Files.move(source, path)
            } }
            cancellation.check()
            val installed = verify(target.resolve("bin").resolve(executableName), cancellation)
            validateIdentity(installed, target, archive)
            cancellation.check()
            if (parent.toRealPath() != parent || fileIdentity(parent) != parentKey || fileIdentity(target) != targetKey)
                throw IOException("The SDK location changed during verification. Choose the location again.")
            complete = true
            return installed
        } finally {
            try {
                if (runCatching { fileIdentity(staging) == stagingKey }.getOrDefault(false)) GoSdkProbe.deleteOwnedTree(staging)
            } finally {
                if (reserved && !complete) {
                    rollback(target, targetKey, ownedFiles, ownedDirectories)
                }
            }
        }
    }

    private fun validateIdentity(sdk: GoModuleSdk, root: Path, archive: GoSdkArchive) {
        if (sdk.root != root.toRealPath() || sdk.version != archive.version || sdk.executable.parent != sdk.root.resolve("bin"))
            throw IOException("The downloaded SDK does not report the selected Go version and installation directory.")
    }

    private data class OwnedFile(val key: Any, val size: Long, val hash: ByteArray)

    /** Preserve changes/additions at every depth, including a replaced parent directory. */
    private fun rollback(target: Path, targetKey: Any?, files: Map<Path, OwnedFile>, directories: Map<Path, Any>) {
        fun sameDirectory(path: Path, key: Any?) = key != null && runCatching {
            val value = attributes(path)
            value.isDirectory && fileIdentity(path) == key
        }.getOrDefault(false)
        fun parentsOwned(relative: Path): Boolean {
            if (!sameDirectory(target, targetKey)) return false
            var parent = relative.parent
            while (parent != null) {
                if (!directories.containsKey(parent) || !sameDirectory(target.resolve(parent), directories[parent])) return false
                parent = parent.parent
            }
            return true
        }
        files.forEach { (relative, owned) -> runCatching {
            if (parentsOwned(relative)) {
                val file = target.resolve(relative)
                val value = attributes(file)
                if (value.isRegularFile && value.size() == owned.size && owned.key == fileIdentity(file) &&
                    Files.newInputStream(file).use { input ->
                        val digest = MessageDigest.getInstance("SHA-256")
                        val buffer = ByteArray(64 * 1024)
                        while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
                        digest.digest().contentEquals(owned.hash)
                    }) Files.delete(file)
            }
        } }
        directories.keys.sortedByDescending { it.nameCount }.forEach { relative -> runCatching {
            if (relative.toString().isNotEmpty() && parentsOwned(relative) && sameDirectory(target.resolve(relative), directories[relative]))
                Files.delete(target.resolve(relative)) // Non-empty directories, including unrelated additions, are preserved.
        } }
        runCatching { if (sameDirectory(target, targetKey)) Files.delete(target) }
    }

    private fun extract(archive: Path, zip: Boolean, root: Path, cancellation: GoSdkDownloadCancellation): Map<Path, OwnedFile> {
        val paths = HashSet<String>()
        val owned = mutableMapOf<Path, OwnedFile>()
        var entries = 0
        var total = 0L
        fun entry(name: String, directory: Boolean, size: Long, mode: Int, expectedCrc: Long? = null, open: () -> InputStream) {
            cancellation.check()
            if (++entries > limits.entries) throw IOException("The Go SDK archive contains too many entries.")
            val relative = safeEntry(name, directory)
            val identity = Normalizer.normalize(relative, Normalizer.Form.NFC).lowercase(Locale.ROOT)
            if (!paths.add(identity)) throw IOException("Duplicate Go SDK archive path: $name")
            val target = root.resolve(relative)
            if (directory) { Files.createDirectories(target); return }
            if (size !in 0..limits.entryBytes || size > limits.unpackedBytes - total) throw IOException("The Go SDK archive exceeds the extraction size limit.")
            Files.createDirectories(target.parent)
            val crc = CRC32()
            val digest = MessageDigest.getInstance("SHA-256")
            var actual = 0L
            open().use { input -> Files.newOutputStream(target, CREATE_NEW, WRITE).use { output ->
                copy(input, cancellation, minOf(limits.entryBytes, limits.unpackedBytes - total)) { buffer, count ->
                    output.write(buffer, 0, count); crc.update(buffer, 0, count); digest.update(buffer, 0, count); actual += count
                }
            } }
            if (actual != size) throw IOException("Truncated Go SDK archive entry: $name")
            if (expectedCrc != null && crc.value != expectedCrc) throw IOException("Invalid Go SDK archive entry checksum: $name")
            total += actual
            if (mode and 73 != 0 && Files.getFileStore(target).supportsFileAttributeView("posix"))
                Files.setPosixFilePermissions(target, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE))
            owned[root.resolve("go").relativize(target)] = OwnedFile(fileIdentity(target), actual, digest.digest())
        }
        if (zip) {
            ZipFile.builder().setPath(archive).get().use { input ->
                val values = input.entries
                while (values.hasMoreElements()) {
                    val value = values.nextElement()
                    // ZIP readers can normalize DOS backslashes. Validate the
                    // encoded name as well, before trusting a normalized alias.
                    val rawName = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(value.rawName)).toString()
                    safeEntry(rawName, value.isDirectory)
                    if (rawName != value.name) throw IOException("Ambiguous Go SDK archive path.")
                    val type = value.unixMode and 61440
                    if (value.isUnixSymlink || type !in setOf(0, 32768, 16384) || !input.canReadEntryData(value))
                        throw IOException("Links, special or encrypted entries are not allowed in Go SDK archives.")
                    entry(value.name, value.isDirectory, value.size, value.unixMode, value.crc) { input.getInputStream(value) }
                }
            }
        } else {
            val bounded = object : FilterInputStream(GZIPInputStream(Files.newInputStream(archive))) {
                var count = 0L
                fun counted(size: Int): Int {
                    if (size > 0) count += size
                    if (count > limits.unpackedBytes + limits.entries.toLong() * 2048) throw IOException("The Go SDK tar stream exceeds the extraction size limit.")
                    return size
                }
                override fun read() = super.read().also { if (it >= 0) counted(1) }
                override fun read(bytes: ByteArray, offset: Int, length: Int) = counted(super.read(bytes, offset, length))
                override fun skip(length: Long): Long {
                    val bytes = ByteArray(8192); var remaining = length
                    while (remaining > 0) { val size = read(bytes, 0, minOf(remaining, bytes.size.toLong()).toInt()); if (size < 0) break; remaining -= size }
                    return length - remaining
                }
            }
            val replay = PushbackInputStream(bounded, 64 * 1024 + 512)
            object : TarArchiveInputStream(replay, "UTF-8") {
                override fun readRecord(): ByteArray? {
                    val record = super.readRecord() ?: return null
                    if (record[156] == 'x'.code.toByte() || record[156] == 'g'.code.toByte()) {
                        val encodedSize = String(record, 124, 12, StandardCharsets.US_ASCII).trim('\u0000', ' ')
                        val size = encodedSize.toLongOrNull(8) ?: throw IOException("Invalid Go SDK tar metadata size.")
                        if (size !in 0..65_536) throw IOException("The Go SDK archive exceeds the metadata size limit.")
                        val padded = ((size + 511) / 512 * 512).toInt()
                        val contents = replay.readNBytes(padded)
                        if (contents.size != padded) throw IOException("Truncated Go SDK tar metadata.")
                        validatePax(contents.copyOf(size.toInt()))
                        replay.unread(contents)
                    }
                    if (record.any { it != 0.toByte() } && record[156].toInt() in setOf(0, '0'.code, '5'.code)) {
                        fun field(start: Int, size: Int): String {
                            val end = (start until start + size).firstOrNull { record[it] == 0.toByte() } ?: start + size
                            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                                .decode(ByteBuffer.wrap(record, start, end - start)).toString()
                        }
                        val prefix = field(345, 155)
                        val name = listOf(prefix, field(0, 100)).filter(String::isNotEmpty).joinToString("/")
                        safeEntry(name, record[156] == '5'.code.toByte() || name.endsWith('/'))
                    }
                    return record
                }
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    currentEntry?.let { entry ->
                        if ((entry.isPaxHeader || entry.isGlobalPaxHeader || entry.isGNULongNameEntry || entry.isGNULongLinkEntry) && entry.size > 64 * 1024)
                            throw IOException("The Go SDK archive exceeds the metadata size limit.")
                    }
                    return super.read(bytes, offset, length)
                }
                override fun getLongNameData(): ByteArray {
                    if ((currentEntry?.size ?: 0) > 4096) throw IOException("The Go SDK archive path is too long.")
                    return super.getLongNameData().also { data ->
                        val name = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(data)).toString().trimEnd('\u0000')
                        safeEntry(name, name.endsWith('/'))
                    }
                }
            }.use { input ->
                while (true) {
                    val value = input.nextTarEntry ?: break
                    if (value.linkFlag.toInt() !in setOf(0, '0'.code, '5'.code) || (!value.isDirectory && !value.isFile) ||
                        value.isSymbolicLink || value.isLink || value.isSparse || !value.isCheckSumOK)
                        throw IOException("Links, special or invalid entries are not allowed in Go SDK archives.")
                    // Closing an entry must not close the enclosing tar stream.
                    entry(value.name, value.isDirectory, value.size, value.mode) { object : InputStream() {
                        override fun read() = input.read()
                        override fun read(bytes: ByteArray, offset: Int, length: Int) = input.read(bytes, offset, length)
                    } }
                }
            }
        }
        if (entries == 0 || !Files.isDirectory(root.resolve("go/src/runtime"), NOFOLLOW_LINKS)) throw IOException("The archive does not contain a complete Go SDK.")
        return owned
    }

    private fun safeEntry(name: String, directory: Boolean): String {
        if (name.isEmpty() || name.length > 4096 || name.any { it <= '\u001f' || it == '\\' || it == ':' } || name.startsWith('/'))
            throw IOException("Unsafe Go SDK archive path.")
        val value = if (directory) name.removeSuffix("/") else name
        val parts = value.split('/')
        if (parts.first() != "go" || parts.any { it.isEmpty() || it == "." || it == ".." || it.endsWith('.') || it.endsWith(' ') ||
                Regex("(?i)(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\\..*)?").matches(it) })
            throw IOException("Unsafe Go SDK archive path: $name")
        return value
    }

    private fun validatePax(bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val space = (offset until minOf(bytes.size, offset + 12)).firstOrNull { bytes[it] == ' '.code.toByte() }
                ?: throw IOException("Invalid Go SDK tar metadata.")
            val size = String(bytes, offset, space - offset, StandardCharsets.US_ASCII).toIntOrNull()
                ?: throw IOException("Invalid Go SDK tar metadata length.")
            if (size <= space - offset + 2 || size > bytes.size - offset || bytes[offset + size - 1] != '\n'.code.toByte())
                throw IOException("Invalid Go SDK tar metadata length.")
            val record = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, space + 1, offset + size - space - 2)).toString()
            val key = record.substringBefore('=')
            if (key == "path" || key == "linkpath") {
                val name = record.substringAfter('=', "")
                safeEntry(name, name.endsWith('/'))
            }
            offset += size
        }
    }

    private fun copy(input: InputStream, cancellation: GoSdkDownloadCancellation, limit: Long, consume: (ByteArray, Int) -> Unit) {
        val buffer = ByteArray(64 * 1024)
        var size = 0L
        while (true) {
            cancellation.check()
            val count = input.read(buffer)
            if (count < 0) break
            size += count
            if (size > limit) throw IOException("The Go SDK download or archive exceeds its declared size limit.")
            consume(buffer, count)
        }
    }

    companion object {
        // The official default response lists currently supported stable releases.
        // Historical archives remain usable through Add Local SDK, not this list.
        internal val CATALOGUE = URI("https://go.dev/dl/?mode=json")
        private val VERSION = Regex("go[1-9][0-9]*\\.[0-9]+(?:\\.[0-9]+)?")
        private val HASH = Regex("[0-9a-f]{64}")
        private fun versionParts(version: String) = version.removePrefix("go").split('.').map { it.toInt() }.let { it + List(3 - it.size) { 0 } }
        private fun backgroundOnly() = check(!SwingUtilities.isEventDispatchThread()) { "Go SDK downloads must run in the background." }
        private fun attributes(path: Path) = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)

        /** NIO Windows attributes often omit fileKey; use the native volume + file ID instead. */
        internal fun fileIdentity(path: Path): Any {
            val value = attributes(path)
            if (!value.isDirectory && !value.isRegularFile) throw IOException("The SDK location contains a link or unsupported file.")
            value.fileKey()?.let { return it }
            if (!System.getProperty("os.name").startsWith("Windows")) throw IOException("This filesystem cannot safely identify SDK files. Choose a local disk.")
            val api = Kernel32.INSTANCE
            val filename = path.toAbsolutePath().toString().let {
                if (it.startsWith("\\\\?\\")) it else if (it.startsWith("\\\\")) "\\\\?\\UNC\\" + it.removePrefix("\\\\") else "\\\\?\\$it"
            }
            val handle = api.CreateFile(filename, WinNT.FILE_READ_ATTRIBUTES,
                WinNT.FILE_SHARE_READ or WinNT.FILE_SHARE_WRITE or WinNT.FILE_SHARE_DELETE, null, WinNT.OPEN_EXISTING,
                WinNT.FILE_FLAG_BACKUP_SEMANTICS or WinNT.FILE_FLAG_OPEN_REPARSE_POINT, null)
            if (handle == WinBase.INVALID_HANDLE_VALUE) throw IOException("Cannot identify the SDK location (Windows error ${api.GetLastError()}).")
            try {
                val information = WinBase.FILE_ID_INFO()
                // Microsoft FILE_ID_INFO = ULONGLONG volume + FILE_ID_128 (24 bytes).
                check(information.size() == 24) { "Unsupported Windows file identity layout." }
                if (!api.GetFileInformationByHandleEx(handle, 18, information.pointer, WinDef.DWORD(information.size().toLong())))
                    throw IOException("This filesystem cannot safely identify SDK files (Windows error ${api.GetLastError()}). Choose a local disk.")
                information.read()
                return WindowsFileIdentity(information.VolumeSerialNumber, information.FileId.Identifier.joinToString("") { "%02x".format(it.toByte()) })
            } finally { api.CloseHandle(handle) }
        }
        private data class WindowsFileIdentity(val volume: Long, val identifier: String)
    }
}

internal object OfficialGoSdkTransport : GoSdkDownloadTransport {
    override fun open(uri: URI, cancellation: GoSdkDownloadCancellation): InputStream {
        var current = uri
        repeat(5) {
            cancellation.check()
            validate(current)
            val connection = HttpConfigurable.getInstance().openHttpConnection(current.toASCIIString())
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Accept-Encoding", "identity")
            val registration = cancellation.attach(connection::disconnect)
            try {
                val code = connection.responseCode
                if (code in setOf(301, 302, 303, 307, 308)) {
                    val location = connection.getHeaderField("Location") ?: throw IOException("The Go download redirect has no location.")
                    current = current.resolve(location)
                    validate(current)
                    registration.close(); connection.disconnect()
                } else {
                    if (code != 200) throw IOException("The official Go download server returned HTTP $code.")
                    val input = connection.inputStream
                    return object : InputStream() {
                        override fun read() = input.read()
                        override fun read(bytes: ByteArray, offset: Int, length: Int) = input.read(bytes, offset, length)
                        override fun close() { try { input.close() } finally { registration.close(); connection.disconnect() } }
                    }
                }
            } catch (error: Exception) {
                registration.close(); connection.disconnect(); cancellation.check(); throw error
            }
        }
        throw IOException("The official Go download server redirected too many times.")
    }

    internal fun validate(uri: URI) {
        val catalogue = uri == GoSdkDownloads.CATALOGUE
        val path = uri.path.orEmpty()
        val filename = path.substringAfterLast('/')
        val archive = uri.rawQuery == null && when (uri.host) {
            "go.dev" -> path == "/dl/$filename"
            "dl.google.com" -> path == "/go/$filename"
            else -> false
        } && Regex("go[0-9]+\\.[0-9]+(?:\\.[0-9]+)?\\.[a-z0-9]+-[a-z0-9]+\\.(?:tar\\.gz|zip)").matches(filename)
        if (uri.scheme != "https" || uri.userInfo != null || uri.fragment != null || uri.port !in setOf(-1, 443) || (!catalogue && !archive))
            throw IOException("Only official HTTPS Go SDK downloads are allowed.")
    }
}
