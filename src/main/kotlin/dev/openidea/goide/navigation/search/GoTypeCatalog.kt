package dev.openidea.goide.navigation.search

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.DocumentEx
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import dev.openidea.goide.engine.EngineClient
import dev.openidea.goide.generate.tests.generationToolchainParameters
import dev.openidea.goide.lsp.GoDocumentSync
import dev.openidea.goide.settings.GoToolchain
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal data class GoTypeEntry(val name: String, val packagePath: String, val packageName: String,
                                val uri: String, val range: Range, val selection: Range, val kind: String, val origin: String)
internal data class GoTypeGuard(val uri: String, val path: Path, val exists: Boolean, val disk: String,
                               val text: String, val stamp: Long?)
internal data class GoTypeDirectory(val path: Path, val entries: List<String>)
internal data class GoTypeCatalog(val id: String, val configuration: String, val types: List<GoTypeEntry>,
                                 val guards: Map<String, GoTypeGuard>, val directories: List<GoTypeDirectory>,
                                 val complete: Boolean, val issues: List<String>, val moduleRoots: Set<String>)

/** Pure native input capture: no active editor, file discovery or executable resolution is required. */
internal class GoTypeSearchInput private constructor(val project: Project, val settings: GoToolchain.Settings,
                                                    val wire: JsonObject, private val observed: List<Observed>) {
    private data class Observed(val file: VirtualFile, val document: Document, val stamp: Long, val sequence: Int?)
    @Volatile private var prepared: JsonObject? = null
    @Volatile private var aliases: Map<String, Observed> = emptyMap()
    @Volatile private var overlayTexts: Map<String, String> = emptyMap()
    @Volatile private var currentObserved: List<Observed> = observed
    @Volatile private var physicalRoot: String? = null

    /** Resolve physical aliases only in background; the frontend never guesses an LSP version. */
    fun prepareAliases() {
        check(!ApplicationManager.getApplication().isDispatchThread)
        val mapped = mutableMapOf<String, Observed>()
        fun canonical(uri: String): String {
            val path = GoTypeCatalogCodec.path(uri)
            var existing: Path? = path
            val suffix = mutableListOf<Path>()
            while (existing != null && !Files.exists(existing)) { suffix.add(existing.fileName); existing = existing.parent }
            val real = runCatching { requireNotNull(existing).toRealPath() }.getOrElse { return GoTypeCatalogCodec.canonical(uri) }
            return GoDocumentSync.fileUri(suffix.asReversed().fold(real) { parent, name -> parent.resolve(name) })
        }
        observed.forEach { entry ->
            val uri = canonical(GoDocumentSync.fileUri(Path.of(entry.file.path)))
            val previous = mapped.putIfAbsent(uri, entry)
            require(previous == null || previous.document === entry.document) { "Two editor buffers refer to the same Go source. Save or close one before searching." }
        }
        physicalRoot = GoTypeCatalogCodec.path(canonical(GoDocumentSync.fileUri(Path.of(wire.get("root").asString)))).toString()
        val request = wire.deepCopy()
        val texts = mutableMapOf<String, String>()
        request.getAsJsonArray("overlays").forEach { element ->
            val overlay = element.asJsonObject
            val uri = canonical(overlay.get("uri").asString)
            overlay.addProperty("uri", uri); texts[uri] = overlay.get("text").asString
        }
        aliases = mapped.toMap(); overlayTexts = texts.toMap(); prepared = request
    }
    fun requestWire(): JsonObject = requireNotNull(prepared).deepCopy()
    fun overlay(uri: String): String? = overlayTexts[uri]
    fun document(uri: String): Pair<VirtualFile, Document>? = aliases[uri]?.let { it.file to it.document }
    /** Backend guards are the authoritative overlay membership, after metadata discovery. */
    fun retainSources(uris: Set<String>) {
        currentObserved = aliases.filterKeys { it in uris }.values.distinct()
    }
    fun sourceAliases(uris: Set<String>): Set<String> = aliases.filterKeys { it in uris }.values.mapTo(mutableSetOf()) { it.file.path }
    fun projectRoots(): Set<String> = setOfNotNull(wire.get("root").asString, physicalRoot)

    fun isCurrent(): Boolean {
        if (project.isDisposed || project.basePath != wire.get("root").asString) return false
        if (runCatching { GoToolchain.capture(project).key }.getOrNull() != settings.key) return false
        val manager = FileDocumentManager.getInstance()
        return currentObserved.all { it.file.isValid && manager.getCachedDocument(it.file) === it.document &&
            it.document.modificationStamp == it.stamp && (it.document as? DocumentEx)?.modificationSequence == it.sequence }
    }

    companion object {
        private val metadata = setOf("go.mod", "go.sum", "go.work", "go.work.sum")
        fun capture(project: Project): GoTypeSearchInput = ReadAction.compute<GoTypeSearchInput, RuntimeException> {
            check(!project.isDisposed) { "The Go project is closed" }
            val root = project.basePath ?: error("Open a Go project before searching for types")
            val settings = GoToolchain.capture(project)
            val manager = FileDocumentManager.getInstance()
            val observed = mutableListOf<Observed>()
            val overlays = JsonArray()
            // Closed unsaved buffers still belong to the document manager. The
            // backend resolves workspace/dependency roots and ignores unrelated
            // overlays, including those from another open IDE project.
            val open = EditorFactory.getInstance().allEditors.filter { it.project === project }.map { it.document }
            for (document in (open + manager.unsavedDocuments.toList()).distinct()) {
                val file = manager.getFile(document)?.takeIf { it.isValid && it.isInLocalFileSystem } ?: continue
                if (file.extension != "go" && file.name !in metadata) continue
                val text = document.text
                val uri = GoDocumentSync.fileUri(Path.of(file.path))
                observed += Observed(file, document, document.modificationStamp, (document as? DocumentEx)?.modificationSequence)
                if (!manager.isDocumentUnsaved(document)) continue
                overlays.add(JsonObject().apply {
                    addProperty("uri", uri); addProperty("text", text); addProperty("documentStamp", document.modificationStamp)
                    GoDocumentSync.get(project).snapshot(uri)?.takeIf { it.text == text }?.let { synced ->
                        add("lsp", JsonObject().apply { addProperty("session", synced.session); addProperty("version", synced.version) })
                    }
                })
            }
            GoTypeSearchInput(project, settings, JsonObject().apply { addProperty("root", root); add("overlays", overlays) }, observed)
        }
    }
}

/** The index owns each operation; initialization and the shared engine process are not canceled by a popup. */
internal interface GoTypeCatalogSource : com.intellij.openapi.Disposable {
    fun prepare(input: GoTypeSearchInput) = input.prepareAliases()
    fun load(input: GoTypeSearchInput, includeExternal: Boolean, operationId: String): CompletableFuture<JsonObject>
    fun cancel(operationId: String)
    fun release(operationId: String) {}
    override fun dispose() {}
}

internal class GoEngineTypeCatalogSource(private val project: Project) : GoTypeCatalogSource {
    @Volatile private var engine: EngineClient? = null
    private class Operation {
        val canceled = AtomicBoolean()
        val request = AtomicReference<CompletableFuture<*>?>()
    }
    private val operations = ConcurrentHashMap<String, Operation>()
    private val closed = AtomicBoolean()
    override fun load(input: GoTypeSearchInput, includeExternal: Boolean, operationId: String): CompletableFuture<JsonObject> {
        // Registration is in memory and precedes tool resolution/initialization.
        // An early cancel therefore cannot be lost because no engine exists yet.
        val operation = operations.computeIfAbsent(operationId) { Operation() }
        fun canceled() = closed.get() || operation.canceled.get() || !input.isCurrent()
        if (canceled()) return CompletableFuture.failedFuture(CancellationException())
        val parameters = input.requestWire().apply {
            generationToolchainParameters(project, input.settings).entrySet().forEach { (key, value) -> add(key, value) }
            addProperty("operationId", operationId)
            addProperty("includeDependencies", includeExternal); addProperty("includeSdk", includeExternal)
        }
        if (canceled()) return CompletableFuture.failedFuture(CancellationException())
        val connection = project.getService(EngineClient::class.java).also { engine = it }
        if (canceled()) return CompletableFuture.failedFuture(CancellationException())
        val request = connection.request("search/types/catalog", parameters, input.settings, timeoutSeconds = 180)
        operation.request.set(request)
        if (canceled()) { request.cancel(false); connection.cancelOperation(operationId) }
        return request.thenApply { if (canceled()) throw CancellationException(); it.asJsonObject }
    }
    override fun cancel(operationId: String) {
        operations.computeIfAbsent(operationId) { Operation() }.let { it.canceled.set(true); it.request.get()?.cancel(false) }
        engine?.cancelOperation(operationId)
    }
    override fun release(operationId: String) { operations.remove(operationId) }
    override fun dispose() { closed.set(true); operations.keys.toList().forEach(::cancel); operations.clear() }
}

internal object GoTypeCatalogCodec {
    private val kinds = setOf("struct", "interface", "function", "alias", "type")
    private val origins = setOf("project", "dependency", "sdk")
    fun decode(value: JsonObject): GoTypeCatalog {
        fun string(value: JsonObject, key: String) = value.get(key)?.takeUnless { it.isJsonNull }?.asString.orEmpty()
        fun array(value: JsonObject, key: String) = value.get(key)?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
        val id = string(value, "catalogId"); val configuration = string(value, "configurationDigest")
        require(id.isNotBlank() && configuration.isNotBlank()) { "The Go type catalog has no source identity" }
        val guards = array(value, "sourceGuards").associate { element ->
            val guard = element.asJsonObject; val uri = canonical(string(guard, "uri"))
            uri to GoTypeGuard(uri, path(uri), guard.get("exists")?.asBoolean ?: true, string(guard, "diskSha256"),
                string(guard, "textSha256"), guard.get("documentStamp")?.takeUnless { it.isJsonNull }?.asLong)
        }
        val types = array(value, "types").map { element ->
            val type = element.asJsonObject
            val name = string(type, "name"); val uri = canonical(string(type, "uri"))
            val kind = string(type, "kind"); val origin = string(type, "origin")
            require(name.isNotBlank() && kind in kinds && origin in origins && uri in guards) { "Invalid Go type declaration" }
            GoTypeEntry(name, string(type, "packagePath"), string(type, "packageName"), uri,
                range(type.getAsJsonObject("range")), range(type.getAsJsonObject("selectionRange")), kind, origin)
        }.distinctBy { Triple(it.uri, it.selection, it.name) }
        val directories = array(value, "directories").map { element ->
            val directory = element.asJsonObject
            GoTypeDirectory(path(canonical(string(directory, "uri"))), array(directory, "entries").map { it.asString }.sorted())
        }
        val issues = array(value, "issues").mapNotNull { it.asJsonObject.get("message")?.asString?.takeIf(String::isNotBlank) }
        val coverage = value.getAsJsonObject("coverage")
        val moduleRoots = coverage?.let { array(it, "modules") }?.mapTo(mutableSetOf()) {
            val root = Path.of(it.asString)
            require(root.isAbsolute) { "A Go module root must be absolute" }
            root.normalize().toString()
        }.orEmpty()
        return GoTypeCatalog(id, configuration, types, guards, directories,
            coverage?.get("complete")?.asBoolean ?: false, issues, moduleRoots)
    }

    private fun range(value: JsonObject?): Range {
        requireNotNull(value) { "A Go type has no source range" }
        fun position(value: JsonObject?): Position {
            requireNotNull(value) { "A Go type has no source position" }
            val line = value.get("line").asBigDecimal.intValueExact(); val column = value.get("character").asBigDecimal.intValueExact()
            require(line >= 0 && column >= 0) { "Invalid Go type source position" }
            return Position(line, column)
        }
        val result = Range(position(value.getAsJsonObject("start")), position(value.getAsJsonObject("end")))
        require(result.start.line < result.end.line || result.start.line == result.end.line && result.start.character <= result.end.character) { "Reversed Go type source range" }
        return result
    }

    fun canonical(uri: String): String = GoDocumentSync.canonicalUri(uri).also { path(it) }
    fun path(uri: String): Path {
        val parsed = URI(uri)
        require(parsed.scheme == "file" && parsed.authority.isNullOrEmpty() && parsed.query == null && parsed.fragment == null) { "A Go type requires a local source URI" }
        return Path.of(parsed).toAbsolutePath().normalize()
    }
    /** Existing-parent resolution also classifies new or deleted paths below a symlink. Background only. */
    fun physicalPath(path: Path): Path {
        check(!ApplicationManager.getApplication().isDispatchThread)
        var existing: Path? = path.toAbsolutePath().normalize()
        val suffix = mutableListOf<Path>()
        while (existing != null && !Files.exists(existing)) { suffix.add(existing.fileName); existing = existing.parent }
        val real = requireNotNull(existing).toRealPath()
        return suffix.asReversed().fold(real) { parent, name -> parent.resolve(name) }
    }
    fun hash(bytes: ByteArray): String = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
    fun text(bytes: ByteArray): String = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF").replace("\r\n", "\n").also {
            require('\r' !in it) { "Convert standalone CR line endings before searching Go source" }
        }
    fun directoryEntries(path: Path): List<String> = Files.newDirectoryStream(path).use { entries -> entries.map { entry ->
        val attributes = Files.readAttributes(entry, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        val kind = when { attributes.isSymbolicLink -> "l"; attributes.isDirectory -> "d"; attributes.isRegularFile -> "f"; else -> "o" }
        "$kind:${entry.fileName}"
    }.sorted() }
}
