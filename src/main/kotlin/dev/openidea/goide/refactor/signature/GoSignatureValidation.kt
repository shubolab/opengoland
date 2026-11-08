package dev.openidea.goide.refactor.signature

import com.google.gson.JsonObject
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VFileProperty
import dev.openidea.goide.lsp.GoDocumentSync
import dev.openidea.goide.refactor.GoWorkspaceEditValidation
import dev.openidea.goide.settings.GoToolchain
import java.net.URI
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/** Checks the analyzed dependency snapshot as well as the edited files. No source writes. */
internal class GoSignatureValidation(private val project: Project, plan: JsonObject, private val configuration: List<String>,
                                     private val readDisk: (Path) -> ByteArray = Files::readAllBytes) : GoWorkspaceEditValidation {
    private data class Expected(val uri: String, val path: Path, val exists: Boolean, val disk: String, val text: String,
                                val stamp: Long?, val session: Long?, val version: Int?)
    private data class Checked(val expected: Expected, val file: VirtualFile?, val fileStamp: Long?, val document: Document?, val documentStamp: Long?)
    private data class Directory(val path: Path, val entries: List<String>)
    private data class CheckedDirectory(val expected: Directory, val file: VirtualFile)
    private val expected = plan.array("preconditions").map { item ->
        val value = item.asJsonObject
        val uri = value.string("uri")
        val parsed = URI(uri)
        require(parsed.scheme == "file" && parsed.authority.isNullOrEmpty() && parsed.fragment == null && parsed.query == null) { "Invalid analyzed source URI" }
        val lsp = value.get("lsp")?.takeIf { it.isJsonObject }?.asJsonObject
        Expected(uri, Path.of(parsed).toAbsolutePath().normalize(), value.get("exists")?.asBoolean ?: true,
            value.string("diskSha256"), value.string("textSha256"), value.get("documentStamp")?.takeUnless { it.isJsonNull }?.asLong,
            lsp?.get("session")?.asLong, lsp?.get("version")?.asInt)
    }
    @Volatile private var checked = emptyList<Checked>()
    private val directories = plan.array("directories").map { entry ->
        val value = entry.asJsonObject
        val parsed = URI(value.string("uri"))
        require(parsed.scheme == "file" && parsed.authority.isNullOrEmpty() && parsed.query == null && parsed.fragment == null) { "Invalid analyzed directory URI" }
        Directory(Path.of(parsed).toAbsolutePath().normalize(), value.array("entries").map { it.asString }.sorted())
    }
    @Volatile private var checkedDirectories = emptyList<CheckedDirectory>()
    private val mainModules = plan.getAsJsonObject("coverage")?.array("modules")?.map { entry ->
        val path = Path.of(entry.asString)
        require(path.isAbsolute) { "An analyzed Go module must have an absolute path" }
        path.normalize()
    }?.toSet().orEmpty()
    init {
        require(expected.isNotEmpty()) { "The signature plan has no analyzed-source preconditions" }
        require(directories.isNotEmpty()) { "The signature plan has no package-directory preconditions" }
        mainModules.forEach { module ->
            require(directories.any { it.path == module } && expected.any { it.path == module.resolve("go.mod") }) { "An analyzed Go module lacks configuration preconditions: $module" }
        }
    }
    override fun additionalEditableRoots(): Set<Path> {
        require(checked.size == expected.size && checkedDirectories.size == directories.size) { "The analyzed workspace has not been validated" }
        return mainModules
    }

    override fun validateBackground() {
        checkConfiguration()
        val nextDirectories = directories.map { entry ->
            val directory = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(entry.path)
                ?: error("An analyzed package directory disappeared: ${entry.path}")
            directory.refresh(false, false)
            require(nativeEntries(directory) == entry.entries) { "Package files are refreshing: ${entry.path}. Run Change Signature again." }
            require(Files.isDirectory(entry.path) && diskEntries(entry.path) == entry.entries) {
                "Package files or directories changed since analysis: ${entry.path}. Run Change Signature again."
            }
            directory.refresh(false, false)
            require(directory.isValid && nativeEntries(directory) == entry.entries) { "An analyzed directory changed while it was being validated: ${entry.path}" }
            CheckedDirectory(entry, directory)
        }
        val next = expected.map { entry ->
            checkOpen()
            val exists = Files.exists(entry.path)
            require(exists == entry.exists) { "An analyzed file was created or removed: ${entry.path}. Run Change Signature again." }
            val file = if (exists) LocalFileSystem.getInstance().refreshAndFindFileByNioFile(entry.path) else null
            file?.refresh(false, false)
            val fileStamp = file?.modificationStamp
            val attributes = if (exists) Files.readAttributes(entry.path, BasicFileAttributes::class.java) else null
            val snapshot = ReadAction.compute<Triple<Document?, Long?, String?>, RuntimeException> {
                val document = file?.let { FileDocumentManager.getInstance().getCachedDocument(it) }
                Triple(document, document?.modificationStamp, document?.text)
            }
            val disk = if (exists) readDisk(entry.path) else byteArrayOf()
            require(!exists || sha256(disk) == entry.disk) { "An analyzed file changed on disk: ${entry.path}. Run Change Signature again." }
            val after = if (Files.exists(entry.path)) Files.readAttributes(entry.path, BasicFileAttributes::class.java) else null
            require((attributes == null && after == null) || attributes != null && after != null && attributes.size() == after.size() &&
                attributes.lastModifiedTime() == after.lastModifiedTime() && attributes.fileKey() == after.fileKey()) { "An analyzed file changed while reading it: ${entry.path}" }
            file?.refresh(false, false)
            require(file == null || file.isValid && file.modificationStamp == fileStamp) { "An analyzed VFS file changed while reading it: ${entry.path}" }
            ReadAction.run<RuntimeException> {
                val current = file?.let { FileDocumentManager.getInstance().getCachedDocument(it) }
                require(current === snapshot.first && current?.modificationStamp == snapshot.second) { "An editor changed while reading its baseline: ${entry.path}" }
            }
            entry.stamp?.let { require(snapshot.second == it) { "An editor changed since analysis: ${entry.path}. Run Change Signature again." } }
            val text = snapshot.third ?: canonicalText(disk)
            require(sha256(text.toByteArray(Charsets.UTF_8)) == entry.text) { "An analyzed document changed: ${entry.path}. Run Change Signature again." }
            checkSync(entry)
            Checked(entry, file, fileStamp, snapshot.first, snapshot.second)
        }
        checkConfiguration()
        checked = next
        checkedDirectories = nextDirectories
    }

    override fun validateDocuments() {
        checkConfiguration()
        require(checked.size == expected.size) { "The analyzed snapshot has not been validated" }
        require(checkedDirectories.size == directories.size) { "The package directories have not been validated" }
        checkedDirectories.forEach { baseline ->
            require(baseline.file.isValid && baseline.file.isDirectory && nativeEntries(baseline.file) == baseline.expected.entries) {
                "Package files changed before commit: ${baseline.expected.path}"
            }
        }
        checked.forEach { baseline ->
            val file = baseline.file
            require(file == null || file.isValid && file.modificationStamp == baseline.fileStamp) { "An analyzed VFS file changed: ${baseline.expected.path}" }
            val document = file?.let { FileDocumentManager.getInstance().getCachedDocument(it) }
            require(document === baseline.document && document?.modificationStamp == baseline.documentStamp) { "An analyzed editor changed before commit: ${baseline.expected.path}" }
            checkSync(baseline.expected)
        }
    }
    private fun checkSync(entry: Expected) {
        if (entry.session == null) return
        val current = GoDocumentSync.get(project).snapshot(entry.uri)
        require(current?.session == entry.session && current.version == entry.version) { "The synchronized Go session changed: ${entry.path}" }
    }
    private fun checkOpen() { check(!project.isDisposed) { "The Go project is closed" } }
    private fun checkConfiguration() {
        checkOpen()
        require(GoToolchain.configurationKey(project) == configuration) { "The Go SDK or build settings changed. Run Change Signature again." }
    }
    companion object {
        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        fun canonicalText(bytes: ByteArray): String = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        private fun diskEntries(path: Path): List<String> = Files.newDirectoryStream(path).use { entries -> entries.map { child ->
            val attributes = Files.readAttributes(child, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            val kind = when { attributes.isSymbolicLink -> "l"; attributes.isDirectory -> "d"; attributes.isRegularFile -> "f"; else -> "o" }
            "$kind:${child.fileName}"
        }.sorted() }
        private fun nativeEntries(directory: VirtualFile): List<String> = directory.children.map { child ->
            val kind = when { child.`is`(VFileProperty.SYMLINK) -> "l"; child.isDirectory -> "d"; child.`is`(VFileProperty.SPECIAL) -> "o"; else -> "f" }
            "$kind:${child.name}"
        }.sorted()
    }
}
