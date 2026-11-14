package dev.openidea.goide.run

import com.intellij.openapi.project.Project
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.util.SystemInfo
import com.intellij.jna.JnaLoader
import com.pty4j.PtyProcessBuilder
import com.pty4j.unix.PtyHelpers
import java.nio.file.Path
import java.nio.file.Files
import java.nio.charset.CodingErrorAction
import java.nio.CharBuffer
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.io.ByteArrayOutputStream
import java.io.InputStream
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonStreamParser
import com.intellij.util.execution.ParametersListUtil
import dev.openidea.goide.navigation.search.GoTypeCatalogCodec
import dev.openidea.goide.lsp.GoDocumentSync
import dev.openidea.goide.lsp.GoExternalFileWatches
import com.intellij.openapi.Disposable
import dev.openidea.goide.settings.GoToolchain
import dev.openidea.goide.debug.GoTestDebugArguments
import dev.openidea.goide.engine.GoEngineExecutable
import java.util.concurrent.CompletableFuture

/** Native UTF-16 document text, captured once on BGT under ReadAction, never per gutter token. */
internal data class GoRunBuildOverlay(
    val uri: String,
    val text: String,
    val documentStamp: Long,
    val documentSequence: Int?,
    val oversized: Boolean = false,
)

/** Each request owns defensive copies of values/environment/overlays. No live configuration reads. */
internal data class GoRunBuildContextRequest(
    val sourceUri: String,
    val workingDirectory: String,
    val values: GoRunParameters,
    val settings: GoToolchain.Settings,
    val overlays: List<GoRunBuildOverlay>,
    val debug: Boolean = false,
)

/**
 * Successful authoritative package metadata. URI sets include .go test/external-test membership.
 * Unknown/malformed/failed metadata completes the operation exceptionally; it is never IGNORED.
 * validateDisk is BGT-only and throws when canonical aliases, files or directory guards changed.
 * Native document sequence/configuration/generation checks remain owned by the service.
 */
internal data class GoRunBuildContextSnapshot(
    val sourceUri: String,
    val sourceText: String,
    val packagePath: String,
    val packageDirectoryUri: String,
    val activeSourceUris: Set<String>,
    val ignoredSourceUris: Set<String>,
    val watchedFileUris: Set<String>,
    val watchedDirectoryUris: Set<String>,
    val validateDisk: () -> Unit,
    val invalidSourceUris: Set<String> = emptySet(),
    val diagnostics: List<String> = emptyList(),
    val watchedAllDirectoryUris: Set<String> = emptySet(),
    val packageMetadata: GoPackageMetadata = GoPackageMetadata(emptyMap(), emptyList()),
)

/** Creation is memory-only. Register this operation before scheduling its start on BGT. */
internal interface GoRunBuildContextSource : Disposable {
    fun create(request: GoRunBuildContextRequest): GoRunBuildContextOperation
    override fun dispose() {}
}

/** Cancel/dispose signals immediately; pipe/process teardown and all waits are BGT-owned. */
internal interface GoRunBuildContextOperation : Disposable {
    val future: CompletableFuture<GoRunBuildContextSnapshot>
    /** BGT only. A cancel that wins before this call must prevent all process creation. */
    fun start()
    override fun dispose()
}

/** Active local replacement modules for one open package, never workspace-folder additions. */
internal data class GoLocalDependencyScopeSnapshot(
    val roots: Set<Path>,
    val watchedFileUris: Set<String>,
    val watchedDirectoryUris: Set<String>,
    val validateDisk: () -> Unit,
    val complete: Boolean,
    val diagnostics: List<String>,
    val watchedAllDirectoryUris: Set<String> = emptySet(),
    val recoveryCandidates: Map<Path, GoDependencyRootState> = emptyMap(),
)

internal interface GoLocalDependencyScopeOperation : Disposable {
    val future: CompletableFuture<GoLocalDependencyScopeSnapshot>
    fun start()
    override fun dispose()
}

/** Bounded selected-SDK metadata loads; no shared engine or language server. */
internal class GoRunBuildContextLoader(private val project: Project) : GoRunBuildContextSource {
    private val closed = AtomicBoolean()
    private val operations = ConcurrentHashMap.newKeySet<Operation<*>>()

    override fun create(request: GoRunBuildContextRequest): GoRunBuildContextOperation {
        val copy = request.copy(values = request.values.independentCopy(), overlays = request.overlays.map { it.copy() })
        val operation = BuildOperation(copy)
        operations += operation
        if (closed.get()) operation.dispose()
        return operation
    }

    /** Independent from gutter/run membership: only an open-package scope owner schedules this. */
    fun createDependencyScope(request: GoRunBuildContextRequest): GoLocalDependencyScopeOperation {
        val copy = request.copy(values = request.values.independentCopy(), overlays = request.overlays.map { it.copy() })
        val operation = DependencyOperation(copy)
        operations += operation
        if (closed.get()) operation.dispose()
        return operation
    }

    override fun dispose() {
        closed.set(true)
        operations.toList().forEach { it.dispose() }
    }

    private inner class BuildOperation(request: GoRunBuildContextRequest) : Operation<GoRunBuildContextSnapshot>(request), GoRunBuildContextOperation {
        override fun loadSnapshot() = (load(request, ::checkCurrent, false) as Loaded.Build).value
    }

    private inner class DependencyOperation(request: GoRunBuildContextRequest) : Operation<GoLocalDependencyScopeSnapshot>(request), GoLocalDependencyScopeOperation {
        override fun loadSnapshot() = (load(request, ::checkCurrent, true) as Loaded.Dependencies).value
    }

    private sealed interface Loaded {
        data class Build(val value: GoRunBuildContextSnapshot) : Loaded
        data class Dependencies(val value: GoLocalDependencyScopeSnapshot) : Loaded
    }

    private abstract inner class Operation<T>(protected val request: GoRunBuildContextRequest) : Disposable {
        val future = CompletableFuture<T>()
        private val cancelled = AtomicBoolean()
        private val started = AtomicBoolean()
        private var deadline = Long.MAX_VALUE
        init { future.whenComplete { _, _ ->
            if (future.isCancelled) cancelled.set(true)
            operations.remove(this)
        } }
        override fun dispose() { cancelled.set(true); future.cancel(false); operations.remove(this) }
        protected fun checkCurrent() {
            if (cancelled.get() || closed.get() || project.isDisposed || Thread.currentThread().isInterrupted)
                throw CancellationException("Go build context loading was cancelled")
            require(System.nanoTime() < deadline) { "Go build-context loading exceeded 60 seconds" }
            ProgressManager.checkCanceled()
        }
        protected abstract fun loadSnapshot(): T
        fun start() {
            check(!ApplicationManager.getApplication().isDispatchThread) { "Go build context loading must run in background" }
            if (!started.compareAndSet(false, true)) return
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
            try {
                checkCurrent()
                val snapshot = loadSnapshot()
                checkCurrent()
                future.complete(snapshot)
            } catch (failure: Throwable) {
                if (failure is CancellationException || failure is ProcessCanceledException || failure is InterruptedException || cancelled.get()) future.cancel(false)
                else future.completeExceptionally(failure)
            } finally { operations.remove(this) }
        }
    }

    private fun load(input: GoRunBuildContextRequest, checkCurrent: () -> Unit, dependencyScope: Boolean): Loaded {
        checkCurrent()
        val guards = Guards()
        val originalDirectory = Path.of(input.workingDirectory)
        require(originalDirectory.isAbsolute) { "The Go package directory must be absolute" }
        val directory = guards.physical(originalDirectory)
        require(Files.isDirectory(directory)) { "The Go package directory disappeared" }
        val originalSource = GoTypeCatalogCodec.path(input.sourceUri)
        val source = guards.physical(originalSource)
        require(source.parent == directory) { "The exact Go source is outside the selected package directory" }
        // Reuse the existing Go Test/Delve argument contract, including legacy
        // build flags in Test arguments and the explicit -args/-- boundary.
        val rawBuildFlags = ParametersListUtil.parse(input.values.buildFlags, false, true)
        val rawArguments = ParametersListUtil.parse(input.values.arguments, false, true)
        val arguments = GoTestDebugArguments.create(
            if (input.debug) rawBuildFlags else GoTestDebugArguments.runMetadataFlags(rawBuildFlags),
            if (input.debug) rawArguments else GoTestDebugArguments.runMetadataFlags(rawArguments), "", input.debug).buildFlags
        require(arguments.none { it == "-C" || it.startsWith("-C=") }) { "Use the package working directory instead of -C for an exact Go source entry" }
        val staging = Files.createTempDirectory("open-goland-go-build-context-")
        try {
            // Resolve candidate buffers once without retaining unrelated aliases as
            // guards. Only effective input paths become watched sources below.
            val candidates = input.overlays.groupBy { GoTypeCatalogCodec.physicalPath(GoTypeCatalogCodec.path(it.uri)).toString() }
            fun dirtyText(path: Path): String? {
                val matches = candidates[GoTypeCatalogCodec.physicalPath(path).toString()].orEmpty()
                if (matches.isEmpty()) return null
                require(matches.none { it.oversized }) { "A Go input overlay exceeds its size limit" }
                require(matches.all { it.text == matches.first().text }) { "Two editor buffers refer to different text for the same Go input" }
                matches.forEach { guards.file(GoTypeCatalogCodec.path(it.uri)) }
                return matches.first().text.also { text ->
                    require(text.length <= MAX_FILE && Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text)).remaining() <= MAX_FILE) { "A Go input overlay exceeds its size limit" }
                }
            }
            var privateGoEnv: Path? = null
            fun useDirtyEnvironment(path: Path) {
                guards.file(path)
                dirtyText(path)?.let { text ->
                    privateGoEnv = staging.resolve("go.env").also { Files.writeString(it, text, Charsets.UTF_8) }
                }
            }
            fun metadataCommand(args: List<String>) = command(input, args, directory).apply {
                privateGoEnv?.let { withEnvironment("GOENV", it.toString()) }
            }
            val base = command(input, listOf("env", "-json", "GOENV"), directory)
            val effective = base.effectiveEnvironment
            guards.file(Path.of(base.exePath), 128 * 1024 * 1024)
            effectiveValue(effective, "GOROOT")?.takeIf(String::isNotBlank)?.let { value ->
                val sdk = Path.of(value)
                if (sdk.isAbsolute) { guards.file(sdk.resolve("go.env")); guards.file(sdk.resolve("VERSION")) }
            }
            var environmentPath = effectiveValue(effective, "GOENV")?.takeUnless { it == "off" || it.isBlank() }?.let {
                Path.of(it).also { value -> require(value.isAbsolute) { "Go requires GOENV to be an absolute path or off" } }
            }
            environmentPath?.let(::useDirtyEnvironment)
            val location = objectJson(execute(metadataCommand(listOf("env", "-json", "GOENV")), checkCurrent))
            val envLocation = string(location, "GOENV", allowEmpty = true)
            if (environmentPath == null && envLocation.isNotBlank() && envLocation != "off") {
                environmentPath = resolve(directory, envLocation)
                useDirtyEnvironment(environmentPath)
            }
            // Capture the real Go environment file before asking Go for its flags.
            val environment = objectJson(execute(metadataCommand(listOf("env", "-json", "GOFLAGS", "GOENV", "GOWORK", "GO111MODULE")), checkCurrent))
            val goFlags = ParametersListUtil.parse(string(environment, "GOFLAGS", allowEmpty = true), false, true)
            val allFlags = goFlags + arguments
            val externalOverlay = lastFlag(allFlags, "-overlay")?.takeIf(String::isNotBlank)
            val modFile = lastFlag(allFlags, "-modfile")?.takeIf(String::isNotBlank)?.let { resolve(directory, it) }
            modFile?.let { guards.file(it); guards.file(it.resolveSibling(it.fileName.toString().removeSuffix(".mod") + ".sum")) }
            val ancestors = generateSequence(directory) { it.parent }.toList()
            val metadata = setOf("go.mod", "go.sum", "go.work", "go.work.sum")
            ancestors.forEach { ancestor ->
                guards.directory(ancestor, metadataOnly = true)
                metadata.forEach { guards.file(ancestor.resolve(it)) }
            }
            effectiveValue(effective, "GOWORK")?.takeUnless { it.isBlank() || it == "off" }?.let {
                val work = resolve(directory, it)
                guards.file(work); guards.file(work.resolveSibling(work.fileName.toString() + ".sum"))
            }
            guards.directory(directory, metadataOnly = false)
            guards.directory(originalDirectory, metadataOnly = false)
            guards.file(originalSource)
            Files.newDirectoryStream(directory, "*.go").use { files -> files.forEach { guards.file(it) } }
            val replacements = linkedMapOf<String, String>()
            externalOverlay?.let { value ->
                val path = resolve(directory, value)
                val parsed = objectJson(dirtyText(path) ?: guards.text(path))
                guards.file(path)
                val replace = parsed.get("Replace")
                require(replace != null && replace.isJsonObject) { "The configured Go overlay has no Replace object" }
                replace.asJsonObject.entrySet().forEach { (from, to) ->
                    require(to.isJsonPrimitive && to.asJsonPrimitive.isString) { "A Go overlay replacement must be a string" }
                    val target = guards.physical(resolve(directory, from))
                    val backing = to.asString
                    replacements[target.toString()] = if (backing.isEmpty()) "" else guards.physical(resolve(directory, backing)).also { guards.file(it) }.toString()
                    guards.file(target)
                }
            }
            val overlays = linkedMapOf<String, GoRunBuildOverlay>()
            for (overlay in input.overlays) {
                checkCurrent()
                val original = GoTypeCatalogCodec.path(overlay.uri)
                val path = GoTypeCatalogCodec.physicalPath(original)
                val relevant = path.parent == directory && path.fileName.toString().endsWith(".go") ||
                    path.fileName.toString() in metadata && (path.parent in ancestors || uri(path) in guards.fileUris) ||
                    uri(path) in setOfNotNull(modFile?.let(::uri), externalOverlay?.let { uri(GoTypeCatalogCodec.physicalPath(resolve(directory, it))) }, environmentPath?.let { uri(GoTypeCatalogCodec.physicalPath(it)) })
                if (!relevant) continue
                require(!overlay.oversized) { "A Go input overlay exceeds its size limit" }
                guards.file(original)
                val previous = overlays.putIfAbsent(path.toString(), overlay)
                require(previous == null || previous.text == overlay.text) { "Two editor buffers refer to different text for the same Go source" }
            }
            overlays.forEach { (path, overlay) ->
                val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(overlay.text))
                val bytes = ByteArray(encoded.remaining()).also { encoded.get(it) }
                require(bytes.size <= MAX_FILE) { "A Go editor overlay exceeds the build-context input limit" }
                val backing = staging.resolve("source-${replacements.size}")
                Files.write(backing, bytes)
                replacements[path] = backing.toString()
            }
            fun overlayFlags(): List<String> = if (replacements.isEmpty()) emptyList() else {
                val replace = JsonObject()
                replacements.forEach { (from, to) -> replace.addProperty(from, to) }
                val manifest = staging.resolve("overlay.json")
                Files.writeString(manifest, JsonObject().apply { add("Replace", replace) }.toString(), Charsets.UTF_8)
                listOf("-overlay=$manifest")
            }
            val flags = removeFlag(arguments, "-overlay")
            fun effectiveText(path: Path): String? {
                val key = guards.physical(path).toString()
                val replacement = replacements[key]
                return dirtyText(path) ?: when {
                    replacement == "" -> null
                    replacement != null -> GoTypeCatalogCodec.text(readBounded(Path.of(replacement)))
                    Files.isRegularFile(path) -> guards.text(path)
                    else -> null
                }
            }
            val explicitWork = effectiveValue(effective, "GOWORK")
            val work = when {
                explicitWork == "off" -> null
                !explicitWork.isNullOrBlank() && explicitWork != "auto" -> Path.of(explicitWork).also {
                    require(it.isAbsolute) { "Go requires GOWORK to be an absolute path, auto, or off" }
                }.normalize()
                else -> ancestors.map { it.resolve("go.work") }.firstOrNull { effectiveText(it) != null }
            }
            var ownedWork: Path? = null
            var workspaceEdits = emptyList<String>()
            var moduleFlags = emptyList<String>()
            if (work != null) {
                val text = effectiveText(work) ?: error("The selected Go workspace file disappeared")
                ownedWork = staging.resolve("go.work")
                Files.writeString(ownedWork, text, Charsets.UTF_8)
                val sum = work.resolveSibling(work.fileName.toString() + ".sum")
                val ownedSum = staging.resolve("go.work.sum")
                Files.writeString(ownedSum, effectiveText(sum).orEmpty(), Charsets.UTF_8)
                // Keep the real workspace path first: Go itself chooses vendor
                // mode and validates relative replacements/modules.txt unchanged.
                replacements[work.toString()] = ownedWork.toString()
                replacements[guards.physical(work).toString()] = ownedWork.toString()
                replacements[sum.toString()] = ownedSum.toString()
                replacements[guards.physical(sum).toString()] = ownedSum.toString()
                val vendor = work.parent.resolve("vendor")
                if (Files.isDirectory(vendor)) watchVendor(vendor, guards, checkCurrent)
                workspaceEdits = workspacePlan(work.parent, ownedWork, guards, checkCurrent, ::metadataCommand, dependencyScope)
                // Other declared workspace modules/replacements can change the
                // build context too. Apply only their now-authoritative manifests.
                for (known in guards.fileUris.toList()) {
                    val path = GoTypeCatalogCodec.path(known)
                    if (path.fileName.toString() !in setOf("go.mod", "go.sum")) continue
                    val text = dirtyText(path) ?: continue
                    val backing = staging.resolve("workspace-source-${replacements.size}")
                    Files.writeString(backing, text, Charsets.UTF_8)
                    replacements[guards.physical(path).toString()] = backing.toString()
                }
            } else if (string(environment, "GO111MODULE", allowEmpty = true) != "off") {
                val rootMod = ancestors.map { it.resolve("go.mod") }.firstOrNull { effectiveText(it) != null }
                if (rootMod != null) {
                    val vendor = rootMod.parent.resolve("vendor")
                    if (Files.isDirectory(vendor)) watchVendor(vendor, guards, checkCurrent)
                    val selected = modFile ?: rootMod
                    require(selected.fileName.toString().endsWith(".mod")) { "Go -modfile must name a .mod file" }
                    val ownedMod = staging.resolve("selected.mod")
                    Files.writeString(ownedMod, effectiveText(selected) ?: error("The selected Go module file disappeared"), Charsets.UTF_8)
                    effectiveText(selected.resolveSibling(selected.fileName.toString().removeSuffix(".mod") + ".sum"))?.let { Files.writeString(staging.resolve("selected.sum"), it, Charsets.UTF_8) }
                    // Go's -modfile keeps the actual module root, including relative
                    // replace directives, while updates affect only our private copy.
                    moduleFlags = listOf("-modfile=$ownedMod")
                }
            }
            val listArguments = if (dependencyScope) listOf("list", "-deps", "-e", "-test", "-json=$DEPENDENCY_FIELDS")
                else listOf("list", "-e", "-find", "-json=$FIELDS")
            var privateWorkspace = false
            fun query(arguments: List<String>, operands: List<String>): String = try {
                execute(metadataCommand(arguments + flags + overlayFlags() + moduleFlags + operands)
                    .apply { if (privateWorkspace) withEnvironment("GOWORK", ownedWork.toString()) }, checkCurrent)
            } catch (failure: MetadataCommandFailure) {
                // Go refuses to write an overlaid sum. That precise failure is
                // the only retry: move the workspace/sum to our private copy,
                // preserving module roots via Go's own workspace editor. Native
                // vendor mode does not fetch/update sums and stays on the first lane.
                if (work == null || ownedWork == null || !failure.stderr.contains("updates to go.sum needed") ||
                    !failure.stderr.contains("part of the overlay specified with -overlay")) throw failure
                checkCurrent()
                if (workspaceEdits.isNotEmpty()) execute(metadataCommand(listOf("work", "edit") + workspaceEdits + ownedWork.toString()), checkCurrent)
                privateWorkspace = true
                execute(metadataCommand(arguments + flags + overlayFlags() + moduleFlags + operands)
                    .withEnvironment("GOWORK", ownedWork.toString()), checkCurrent)
            }
            fun queryPackages() = query(listArguments, listOf("."))
            var output = queryPackages()
            if (dependencyScope) {
                // Discovery is followed by a guarded query. A newly discovered
                // module's dirty manifest may reveal another replacement; iterate
                // only that bounded metadata closure, never the dependency tree.
                val guardedModules = mutableSetOf<Path>()
                repeat(MAX_DEPENDENCY_PASSES) {
                    val parsed = dependencyMetadata(output, directory, guards)
                    val recoveries = parsed.recoveryCandidates.toMutableMap()
                    val prefixes = recoveryModulePrefixes(parsed.failedImports)
                    if (prefixes.isNotEmpty()) {
                        val selected = query(listOf("list", "-m", "-e", "-json=$RECOVERY_MODULE_FIELDS"), prefixes)
                        recoveryMetadata(selected, prefixes.toSet(), directory, guards).forEach { (root, state) ->
                            val previous = recoveries.putIfAbsent(root, state)
                            if (previous != null && previous != state) throw GoDependencyRootChanged("A selected Go recovery root changed while loading")
                        }
                    }
                    require((parsed.roots + recoveries.keys).size <= MAX_DEPENDENCY_ROOTS) { "The selected Go dependency roots exceed the watch limit" }
                    var discovered = false
                    for (root in parsed.moduleRoots + recoveries.filterValues { it.isDirectory }.keys) {
                        if (!guardedModules.add(root)) continue
                        discovered = true
                        guards.directory(root, metadataOnly = true)
                        for (name in listOf("go.mod", "go.sum")) {
                            val path = root.resolve(name)
                            guards.file(path)
                            val text = dirtyText(path) ?: continue
                            val backing = staging.resolve("dependency-manifest-${replacements.size}")
                            Files.writeString(backing, text, Charsets.UTF_8)
                            replacements[guards.physical(path).toString()] = backing.toString()
                        }
                    }
                    if (!discovered) {
                        checkCurrent(); guards.validate()
                        return Loaded.Dependencies(GoLocalDependencyScopeSnapshot(parsed.roots, guards.fileUris.toSet(),
                            guards.directoryUris.toSet(), { guards.validate() }, parsed.complete, parsed.diagnostics, guards.allDirectoryUris,
                            recoveries.toMap()))
                    }
                    output = queryPackages()
                }
                error("The Go local dependency manifest closure exceeds its limit")
            }
            val result = objectJson(output)
            val reported = guards.physical(Path.of(string(result, "Dir")))
            require(reported == directory) { "Go returned metadata for a different package directory" }
            val packagePath = string(result, "ImportPath")
            // This is the same selected-SDK query that classified the source.
            // Retain its real package/module roots for failure links and coverage;
            // another go list after verification could write the user's module
            // in a legitimate -mod=mod context and invalidate our own guards.
            val parsedMetadata = GoPackageDirectories.parseMetadata(output)
            val packageMetadata = GoPackageMetadata(
                java.util.Collections.unmodifiableMap(LinkedHashMap(parsedMetadata.directories)),
                java.util.Collections.unmodifiableList(parsedMetadata.modules.toList()),
            )
            require(packageMetadata.directories.size == 1 && packageMetadata.directories[packagePath]?.let {
                guards.physical(Path.of(it)) == directory
            } == true) { "Go returned incomplete package location metadata" }
            val invalid = members(result, "InvalidGoFiles", directory, originalDirectory, guards)
            val active = (members(result, "GoFiles", directory, originalDirectory, guards) +
                members(result, "CgoFiles", directory, originalDirectory, guards) +
                members(result, "TestGoFiles", directory, originalDirectory, guards) +
                members(result, "XTestGoFiles", directory, originalDirectory, guards)).toMutableSet()
            val ignored = members(result, "IgnoredGoFiles", directory, originalDirectory, guards) - invalid
            active.removeAll(invalid)
            require((active intersect ignored).isEmpty()) { "Go returned contradictory active and ignored source membership" }
            val sourceUri = uri(source)
            fun selectedAliases(values: Set<String>): Set<String> = if (sourceUri in values) values + input.sourceUri else values
            require(sourceUri in active || sourceUri in ignored || sourceUri in invalid) { "Go did not report build membership for the selected source" }
            val error = result.get("Error")
            val diagnostics = if (error == null || error.isJsonNull) emptyList() else {
                require(error.isJsonObject) { "Go returned malformed package diagnostics" }
                listOf(string(error.asJsonObject, "Err"))
            }
            val sourceText = overlays[source.toString()]?.text ?: replacements[source.toString()]?.let {
                require(it.isNotEmpty()) { "The selected source is deleted by the configured Go overlay" }
                GoTypeCatalogCodec.text(readBounded(Path.of(it)))
            } ?: GoTypeCatalogCodec.text(readBounded(source))
            checkCurrent(); guards.validate()
            return Loaded.Build(GoRunBuildContextSnapshot(input.sourceUri, sourceText, packagePath, uri(directory), selectedAliases(active),
                selectedAliases(ignored), guards.fileUris.toSet(), guards.directoryUris.toSet(), { guards.validate() }, selectedAliases(invalid), diagnostics, guards.allDirectoryUris,
                packageMetadata))
        } finally {
            Files.walk(staging).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }

    private data class DependencyMetadata(
        val roots: Set<Path>,
        val moduleRoots: Set<Path>,
        val complete: Boolean,
        val diagnostics: List<String>,
        val failedImports: Set<String>,
        val recoveryCandidates: Map<Path, GoDependencyRootState>,
    )

    /** Use Go's selected package locations, rather than treating raw replace directives as active imports. */
    private fun dependencyMetadata(output: String, directory: Path, guards: Guards): DependencyMetadata {
        val roots = linkedSetOf<Path>()
        val moduleRoots = linkedSetOf<Path>()
        val diagnostics = linkedSetOf<String>()
        val failedImports = linkedSetOf<String>()
        val recoveryCandidates = linkedMapOf<Path, GoDependencyRootState>()
        var incomplete = false
        var anchor = false
        var count = 0
        fun diagnostic(value: com.google.gson.JsonElement?) {
            if (value == null || value.isJsonNull) return
            require(value.isJsonObject) { "Go returned malformed dependency diagnostics" }
            incomplete = true
            if (diagnostics.size < 100) diagnostics += string(value.asJsonObject, "Err").take(4096)
        }
        val parser = JsonStreamParser(output)
        while (parser.hasNext()) {
            require(++count <= 20_000) { "The Go dependency graph exceeds its package limit" }
            val value = parser.next()
            require(value.isJsonObject) { "Go returned malformed dependency metadata" }
            val entry = value.asJsonObject
            val importPath = string(entry, "ImportPath")
            diagnostic(entry.get("Error"))
            entry.get("DepsErrors")?.takeUnless { it.isJsonNull }?.let { errors ->
                require(errors.isJsonArray) { "Go returned malformed dependency errors" }
                errors.asJsonArray.forEach(::diagnostic)
            }
            entry.get("Incomplete")?.takeUnless { it.isJsonNull }?.let {
                require(it.isJsonPrimitive && it.asJsonPrimitive.isBoolean) { "Go returned malformed dependency completeness" }
                if (it.asBoolean) incomplete = true
            }
            val reported = entry.get("Dir")?.takeUnless { it.isJsonNull }?.let {
                val path = Path.of(string(entry, "Dir"))
                require(path.isAbsolute) { "Go returned a relative dependency directory" }
                guards.physical(path)
            }
            if (reported == directory) anchor = true
            val module = entry.get("Module")?.takeUnless { it.isJsonNull }
            if ((module == null || reported == null) && (entry.get("Error")?.isJsonNull == false ||
                    entry.get("Incomplete")?.takeUnless { it.isJsonNull }?.asBoolean == true)) failedImports += importPath
            if (module == null) continue
            require(module.isJsonObject) { "Go returned malformed dependency module metadata" }
            val moduleObject = module.asJsonObject
            diagnostic(moduleObject.get("Error"))
            val main = moduleObject.get("Main")?.takeUnless { it.isJsonNull }?.let {
                require(it.isJsonPrimitive && it.asJsonPrimitive.isBoolean) { "Go returned malformed main-module metadata" }
                it.asBoolean
            } ?: false
            if (main) {
                moduleObject.get("Dir")?.takeUnless { it.isJsonNull }?.let {
                    val root = Path.of(string(moduleObject, "Dir"))
                    require(root.isAbsolute) { "Go returned a relative main-module directory" }
                    moduleRoots.add(guards.physical(root))
                }
                continue
            }
            val replacement = moduleObject.get("Replace")?.takeUnless { it.isJsonNull } ?: continue
            require(replacement.isJsonObject) { "Go returned malformed replacement metadata" }
            val replacementObject = replacement.asJsonObject
            diagnostic(replacementObject.get("Error"))
            val version = replacementObject.get("Version")?.takeUnless { it.isJsonNull }?.let {
                string(replacementObject, "Version", allowEmpty = true)
            }.orEmpty()
            if (version.isNotEmpty()) continue // A downloaded version is not a local replacement root.
            val rootValue = replacementObject.get("Dir")?.takeUnless { it.isJsonNull } ?: continue
            require(rootValue.isJsonPrimitive && rootValue.asJsonPrimitive.isString) { "Go returned malformed local replacement directory" }
            val lexicalRoot = Path.of(rootValue.asString)
            require(lexicalRoot.isAbsolute) { "Go returned a relative local replacement directory" }
            val root = guards.physical(lexicalRoot)
            // Vendoring reports replacement metadata too, but those packages are
            // loaded from vendor rather than the replacement's source directory.
            if (reported == null || !reported.startsWith(root) || root.relativize(reported).any { it.toString() == "vendor" }) continue
            val state = guards.recoveryRoot(root)
            if (!state.isDirectory) throw GoDependencyRootChanged("An active local replacement directory disappeared")
            roots.add(root)
            moduleRoots.add(root)
            requireSafeRecoveryRoot(root, directory)
            if (moduleObject.get("Error")?.isJsonNull != false && replacementObject.get("Error")?.isJsonNull != false)
                recoveryCandidates[root] = state
        }
        require(count > 0 && anchor) { "Go returned no metadata for the selected dependency package" }
        if (incomplete && diagnostics.isEmpty()) diagnostics += "Go reported an incomplete dependency graph"
        require(roots.size <= MAX_DEPENDENCY_ROOTS) { "The selected Go dependency roots exceed the watch limit" }
        return DependencyMetadata(roots.toSet(), moduleRoots.toSet(), !incomplete, diagnostics.toList(), failedImports.toSet(), recoveryCandidates.toMap())
    }

    /** Literal path prefixes only; Go decides which of them is an actually selected module. */
    private fun recoveryModulePrefixes(imports: Set<String>): List<String> {
        val result = linkedSetOf<String>()
        for (path in imports) {
            require(path.length <= 4096) { "A failed Go import exceeds the recovery path limit" }
            if (path.startsWith('-') || path.any { it.isWhitespace() || it in "\\@*?{}[]\u0000" } ||
                path.split('/').any { it.isEmpty() || it == "." || it == ".." || it == "..." }) continue
            val parts = path.split('/')
            for (size in 1..parts.size) {
                val prefix = parts.take(size).joinToString("/")
                // "all" is a go-list pattern, never an exact module query.
                if (prefix == "all") continue
                result += prefix
                require(result.size <= MAX_RECOVERY_PREFIXES) { "The failed Go import prefixes exceed the recovery query limit" }
            }
        }
        return result.toList()
    }

    private fun recoveryMetadata(output: String, requested: Set<String>, directory: Path, guards: Guards): Map<Path, GoDependencyRootState> {
        val result = linkedMapOf<Path, GoDependencyRootState>()
        val seen = linkedSetOf<String>()
        val parser = JsonStreamParser(output)
        while (parser.hasNext()) {
            val value = parser.next()
            require(value.isJsonObject) { "Go returned malformed selected-module metadata" }
            val module = value.asJsonObject
            val path = string(module, "Path")
            require(path in requested && seen.add(path)) { "Go returned an unrequested or duplicate selected module" }
            for (objectWithError in listOfNotNull(module, module.get("Replace")?.takeUnless { it.isJsonNull }?.let {
                require(it.isJsonObject) { "Go returned malformed selected-module replacement" }; it.asJsonObject
            })) {
                objectWithError.get("Error")?.takeUnless { it.isJsonNull }?.let {
                    require(it.isJsonObject) { "Go returned malformed selected-module error" }; string(it.asJsonObject, "Err")
                }
            }
            if (module.get("Error")?.isJsonNull == false) continue
            val main = module.get("Main")?.takeUnless { it.isJsonNull }?.let {
                require(it.isJsonPrimitive && it.asJsonPrimitive.isBoolean) { "Go returned malformed selected-module ownership" }; it.asBoolean
            } ?: false
            if (main || module.get("Version")?.takeUnless { it.isJsonNull }?.let { string(module, "Version", true) }.isNullOrEmpty()) continue
            val replacement = module.get("Replace")?.takeUnless { it.isJsonNull }?.asJsonObject ?: continue
            if (replacement.get("Error")?.isJsonNull == false ||
                replacement.get("Version")?.takeUnless { it.isJsonNull }?.let { string(replacement, "Version", true) }.orEmpty().isNotEmpty()) continue
            // Go deliberately omits top-level Dir in vendor mode, even when
            // Replace.Dir is present. Never turn a broken vendored import into
            // a watch on the unused original replacement source.
            val selectedDirectory = module.get("Dir")?.takeUnless { it.isJsonNull } ?: continue
            val replacementDirectory = replacement.get("Dir")?.takeUnless { it.isJsonNull } ?: continue
            require(selectedDirectory.isJsonPrimitive && selectedDirectory.asJsonPrimitive.isString &&
                replacementDirectory.isJsonPrimitive && replacementDirectory.asJsonPrimitive.isString) { "Go returned malformed recovery directory" }
            val selected = Path.of(selectedDirectory.asString)
            val lexical = Path.of(replacementDirectory.asString)
            require(selected.isAbsolute && lexical.isAbsolute) { "Go returned a relative recovery directory" }
            val root = guards.physical(lexical)
            require(guards.physical(selected) == root) { "Go returned contradictory selected recovery directories" }
            requireSafeRecoveryRoot(root, directory)
            result[root] = guards.recoveryRoot(root)
            require(result.size <= MAX_DEPENDENCY_ROOTS) { "The selected Go recovery roots exceed the watch limit" }
        }
        return result
    }

    private fun requireSafeRecoveryRoot(root: Path, directory: Path) {
        val home = GoTypeCatalogCodec.physicalPath(Path.of(System.getProperty("user.home")).toAbsolutePath())
        val projectRoot = project.basePath?.let { GoTypeCatalogCodec.physicalPath(Path.of(it).toAbsolutePath()) }
        require(root.toString().length <= 4096 && GoExternalFileWatches.isSafeDependencyRoot(root, projectRoot, home) &&
            GoExternalFileWatches.isSafeDependencyRoot(root, directory, home)) { "The selected Go recovery root is outside the safe watch scope" }
    }

    private fun workspacePlan(originalParent: Path, owned: Path, guards: Guards, checkCurrent: () -> Unit,
                              command: (List<String>) -> GeneralCommandLine, dependencyScope: Boolean): List<String> {
        val parsed = objectJson(execute(command(listOf("work", "edit", "-json", owned.toString())), checkCurrent))
        val edits = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        parsed.get("Use")?.takeUnless { it.isJsonNull }?.also { require(it.isJsonArray) { "Go returned malformed workspace uses" } }?.asJsonArray?.forEach { entry ->
            require(entry.isJsonObject) { "Go returned malformed workspace use entries" }
            val path = string(entry.asJsonObject, "DiskPath")
            val actual = guards.physical(resolve(originalParent, path))
            require(seen.add(uri(actual))) { "The Go workspace selects the same module more than once" }
            guards.file(actual.resolve("go.mod")); guards.file(actual.resolve("go.sum")); guards.directory(actual, metadataOnly = true)
            if (!Path.of(path).isAbsolute) { edits += "-dropuse=$path"; edits += "-use=$actual" }
        }
        parsed.get("Replace")?.takeUnless { it.isJsonNull }?.also { require(it.isJsonArray) { "Go returned malformed workspace replacements" } }?.asJsonArray?.forEach { entry ->
            require(entry.isJsonObject) { "Go returned malformed workspace replacements" }
            val old = entry.asJsonObject.getAsJsonObject("Old") ?: error("Go workspace replacement has no old module")
            val replacement = entry.asJsonObject.getAsJsonObject("New") ?: error("Go workspace replacement has no new module")
            val path = string(replacement, "Path")
            val version = replacement.get("Version")?.let { string(replacement, "Version", allowEmpty = true) }.orEmpty()
            if (version.isEmpty()) {
                val actual = guards.physical(resolve(originalParent, path))
                guards.file(actual.resolve("go.mod")); guards.file(actual.resolve("go.sum"))
                if (!dependencyScope || guards.recoveryRoot(actual, selected = false).isDirectory) guards.directory(actual, metadataOnly = true)
                if (Path.of(path).isAbsolute) return@forEach
                val oldVersion = old.get("Version")?.let { string(old, "Version", allowEmpty = true) }.orEmpty()
                val key = string(old, "Path") + if (oldVersion.isEmpty()) "" else "@$oldVersion"
                edits += "-dropreplace=$key"; edits += "-replace=$key=$actual"
            }
        }
        return edits.toList()
    }

    /** Guard the native vendor tree without rewriting its bytes or path spelling. */
    private fun watchVendor(from: Path, guards: Guards, checkCurrent: () -> Unit) {
        var count = 0
        Files.walk(from).use { paths -> paths.forEach { path ->
            checkCurrent(); require(++count <= 50_000) { "The Go vendor tree exceeds the build-context file limit" }
            if (Files.isDirectory(path)) guards.directory(path, metadataOnly = false, allEntries = true)
            else if (path.fileName.toString().endsWith(".go") || path.fileName.toString() == "modules.txt") guards.file(path)
        } }
    }

    private fun command(input: GoRunBuildContextRequest, args: List<String>, directory: Path): GeneralCommandLine =
        GoToolchain.command(project, "go", args, input.settings).withWorkDirectory(directory.toFile()).withEnvironment(input.values.environment)
            .withCharset(Charsets.UTF_8).apply {
                if (!input.values.passParentEnvironment) withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.NONE)
            }

    private fun execute(command: GeneralCommandLine, checkCurrent: () -> Unit): String {
        checkCurrent()
        val windows = SystemInfo.isWindows
        val commandLine = listOf(command.exePath) + command.parametersList.list
        val ownedCommand = if (windows) {
            val owner = checkNotNull(GoEngineExecutable.resolve()) { "The bundled Go command owner is unavailable" }
            listOf(owner.toString(), "--opengoland-owned-command", "--") + commandLine
        } else commandLine
        val process: Process = if (windows) ProcessBuilder(ownedCommand).directory(command.workDirectory).apply {
            environment().clear(); environment().putAll(command.effectiveEnvironment)
        }.start() else {
            check(JnaLoader.isLoaded()) { "The IDE process launcher is unavailable" }
            PtyProcessBuilder(commandLine.toTypedArray()).setDirectory(command.workDirectory!!.toString())
                .setEnvironment(command.effectiveEnvironment).setConsole(true).setRedirectErrorStream(false).setSpawnProcessUsingJdkOnMacIntel(false).start()
        }
        val failures = AtomicReference<Throwable?>()
        val stdout = ByteArrayOutputStream(); val stderr = ByteArrayOutputStream()
        fun reader(stream: InputStream, output: ByteArrayOutputStream) = Thread({
            try { stream.use { input ->
                val buffer = ByteArray(4096)
                while (true) {
                    val count = input.read(buffer); if (count < 0) break
                    if (output.size() + count > MAX_OUTPUT) error("Go build-context output exceeded its limit")
                    output.write(buffer, 0, count)
                }
            } } catch (failure: Throwable) { failures.compareAndSet(null, failure) }
        }, "open Goland build-context output").apply { isDaemon = true; start() }
        val readers = mutableListOf<Thread>()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        fun stop() {
            if (windows) {
                // The bundled owner assigns the command to a KILL_ON_JOB_CLOSE
                // job atomically at creation. Only this native Process belongs
                // to us; PID/parent snapshots after exit are not ownership proof.
                // Closing the owner also ends pre-sample and orphaned children.
                if (process.isAlive) process.destroyForcibly()
            } else {
                val native = PtyHelpers.getInstance()
                val pid = process.pid().also { require(it in 1..Int.MAX_VALUE.toLong()) }.toInt()
                if (native.killpg(pid, 0) == 0) { native.killpg(pid, 15); if (!process.waitFor(300, TimeUnit.MILLISECONDS)) native.killpg(pid, 9) }
                // Parent exit alone does not prove its dedicated group is gone.
                if (native.killpg(pid, 0) == 0) native.killpg(pid, 9)
            }
            process.waitFor(1000, TimeUnit.MILLISECONDS)
        }
        var primaryFailure: Throwable? = null
        try {
            readers += reader(process.inputStream, stdout)
            readers += reader(process.errorStream, stderr)
            while (true) {
                checkCurrent(); failures.get()?.let { throw it }
                if (process.waitFor(25, TimeUnit.MILLISECONDS)) break
                require(System.nanoTime() < deadline) { "The selected Go SDK did not finish build-context metadata within 30 seconds" }
            }
            stop()
            val drain = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (readers.any { it.isAlive }) {
                checkCurrent(); failures.get()?.let { throw it }
                require(System.nanoTime() < drain) { "Go build-context output did not close" }
                readers.forEach { it.join(20) }
            }
            failures.get()?.let { throw it }; checkCurrent()
            fun decode(bytes: ByteArray) = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            val out = decode(stdout.toByteArray()); val err = decode(stderr.toByteArray())
            if (process.exitValue() != 0) throw MetadataCommandFailure(process.exitValue(), err)
            return out
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            val interrupted = Thread.interrupted()
            var cleanupFailure: Throwable? = null
            fun cleanup(action: () -> Unit) {
                try { action() } catch (failure: Throwable) {
                    val previous = cleanupFailure
                    if (previous == null) cleanupFailure = failure
                    else if (previous !== failure) previous.addSuppressed(failure)
                }
            }
            try {
                cleanup { stop() }
                runCatching { process.outputStream.close() }; runCatching { process.inputStream.close() }; runCatching { process.errorStream.close() }
                readers.forEach { cleanup { it.join(1000) } }
            } finally { if (interrupted) Thread.currentThread().interrupt() }
            cleanupFailure?.let { failure ->
                val primary = primaryFailure
                if (primary == null) throw failure
                if (primary !== failure) primary.addSuppressed(failure)
            }
        }
    }

    private class MetadataCommandFailure(exit: Int, val stderr: String) : IllegalStateException("Go build-context metadata failed (exit $exit): ${stderr.take(4096)}")

    private class Guards {
        private val aliases = linkedMapOf<String, Path>()
        private val files = linkedMapOf<String, Pair<Path, String?>>()
        private val directories = linkedMapOf<String, Pair<Path, List<String>>>()
        private val limits = mutableMapOf<String, Int>()
        private val metadataDirectories = mutableSetOf<String>()
        private val allDirectories = mutableSetOf<String>()
        private val recoveryRoots = linkedMapOf<String, GoDependencyRootState>()
        private val selectedRecoveryRootUris = linkedSetOf<String>()
        private val recoveryManifestUris = linkedSetOf<String>()
        val fileUris = linkedSetOf<String>()
        val directoryUris = linkedSetOf<String>()
        val allDirectoryUris: Set<String> get() = allDirectories.toSet()
        fun physical(path: Path): Path {
            require(path.isAbsolute) { "A build-context path must be absolute" }
            val result = GoTypeCatalogCodec.physicalPath(path)
            val previous = aliases.putIfAbsent(path.normalize().toString(), result)
            if (previous != null && previous != result) validateRecoveryRoots()
            require(previous == null || previous == result) { "A Go path alias changed while loading build context" }
            return result
        }
        fun file(path: Path, limit: Int = MAX_FILE) {
            val actual = physical(path); val key = uri(actual)
            fileUris += key; fileUris += uri(path)
            limits[key] = maxOf(limits[key] ?: 0, limit)
            val hash = hash(actual, limits.getValue(key))
            val previous = files.putIfAbsent(key, actual to hash)
            if (previous != null) requireFileHash(key, hash, previous.second, "A Go build-context input changed while loading")
        }
        fun text(path: Path): String {
            file(path)
            require(Files.isRegularFile(path)) { "A required Go build-context input disappeared: $path" }
            require(Files.size(path) <= MAX_FILE) { "A Go build-context input exceeds its size limit" }
            return rawText(readBounded(path))
        }
        private fun hash(path: Path, limit: Int): String? {
            if (!Files.exists(path)) return null
            require(Files.isRegularFile(path) && Files.size(path) <= limit) { "Invalid or oversized Go build-context file: $path" }
            return GoTypeCatalogCodec.hash(readBounded(path, limit))
        }
        fun directory(path: Path, metadataOnly: Boolean, allEntries: Boolean = false) {
            val actual = physical(path); val key = uri(actual)
            if (!metadataOnly) metadataDirectories -= key else if (key !in directories) metadataDirectories += key
            if (allEntries || key in allDirectories) { allDirectories += key; allDirectories += uri(path) }
            directoryUris += key; directoryUris += uri(path)
            val snapshot = try { entries(actual, key in metadataDirectories, key in allDirectories) }
            catch (failure: Exception) { validateRecoveryRoots(failure); throw failure }
            directories[key] = actual to snapshot
        }
        fun recoveryRoot(path: Path, selected: Boolean = true): GoDependencyRootState {
            val actual = physical(path)
            val key = uri(actual)
            // Raw workspace declarations need presence guards too, but only
            // Go's actual selection may request an automatic recovery retry.
            if (selected) selectedRecoveryRootUris += key
            val state = GoDependencyRootState.capture(actual)
            val previous = recoveryRoots.putIfAbsent(key, state)
            if (previous != null && previous != state) rootChanged(key, "A Go recovery root changed while loading")
            if (selected) recoveryManifestUris += uri(physical(actual.resolve("go.mod")))
            return state
        }
        private fun rootChanged(key: String, message: String, cause: Throwable? = null): Nothing {
            if (key in selectedRecoveryRootUris) throw GoDependencyRootChanged(message, cause)
            throw IllegalArgumentException(message, cause)
        }
        private fun requireFileHash(key: String, actual: String?, expected: String?, message: String) {
            if (actual != expected && key in recoveryManifestUris)
                throw GoDependencyRootChanged("An exact selected Go recovery manifest changed")
            require(actual == expected) { message }
        }
        private fun validateRecoveryRoots(cause: Throwable? = null) {
            for ((key, state) in recoveryRoots) if (!state.isCurrent())
                rootChanged(key, "A Go recovery root changed", cause)
        }
        private fun entries(path: Path, metadataOnly: Boolean, allEntries: Boolean): List<String> =
            Files.newDirectoryStream(path).use { children -> children.filter { entry ->
                val name = entry.fileName.toString()
                allEntries || name in setOf("go.mod", "go.sum", "go.work", "go.work.sum", "vendor") || !metadataOnly && name.endsWith(".go")
            }.map { entry ->
                // Filter before stat: unrelated entries in /tmp or other
                // ancestors can disappear while this metadata-only guard loads.
                val attrs = Files.readAttributes(entry, java.nio.file.attribute.BasicFileAttributes::class.java, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                val kind = when { attrs.isSymbolicLink -> "l"; attrs.isDirectory -> "d"; attrs.isRegularFile -> "f"; else -> "o" }
                "$kind:${entry.fileName}"
            }.sorted() }
        fun validate() {
            check(!ApplicationManager.getApplication().isDispatchThread) { "Go build-context disk validation must run in background" }
            validateRecoveryRoots()
            aliases.forEach { (path, expected) -> require(GoTypeCatalogCodec.physicalPath(Path.of(path)) == expected) { "A Go path alias changed" } }
            files.forEach { (key, value) -> val (path, expected) = value
                requireFileHash(key, hash(path, limits.getValue(key)), expected, "A Go build-context source or manifest changed")
            }
            directories.forEach { (key, value) ->
                val actual = try { entries(value.first, key in metadataDirectories, key in allDirectories) }
                catch (failure: Exception) { validateRecoveryRoots(failure); throw failure }
                require(actual == value.second) { "Go package membership changed" }
            }
        }
    }

    private fun members(json: JsonObject, field: String, directory: Path, originalDirectory: Path, guards: Guards): Set<String> {
        val raw = json.get(field) ?: return emptySet()
        require(raw.isJsonArray) { "Go returned malformed $field membership" }
        return buildSet {
            raw.asJsonArray.forEach { value ->
                require(value.isJsonPrimitive && value.asJsonPrimitive.isString) { "Go returned a non-string source member" }
                val name = value.asString
                require(name.isNotBlank() && name.endsWith(".go") && '/' !in name && '\\' !in name && name != "." && name != "..") { "Go returned an invalid source member" }
                val path = guards.physical(directory.resolve(name))
                require(path.parent == directory) { "Go returned a source member outside the selected package" }
                guards.file(path)
                guards.file(originalDirectory.resolve(name))
                add(uri(path)); add(uri(originalDirectory.resolve(name)))
            }
        }
    }
    private fun objectJson(text: String): JsonObject {
        require(!text.trimStart().startsWith("\uFEFF")) { "Go JSON input must not contain a UTF-8 byte order mark" }
        val value = JsonParser.parseString(text)
        require(value.isJsonObject) { "Go returned no single build-context JSON object" }
        return value.asJsonObject
    }
    private fun string(json: JsonObject, key: String, allowEmpty: Boolean = false): String {
        val value = json.get(key)
        require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString && (allowEmpty || value.asString.isNotBlank())) { "Go did not report $key" }
        return value.asString
    }
    private fun lastFlag(flags: List<String>, name: String): String? {
        var result: String? = null
        for (index in flags.indices) if (flags[index] == name) result = flags.getOrNull(index + 1) ?: error("Missing $name value")
        else if (flags[index].startsWith("$name=")) result = flags[index].substring(name.length + 1)
        return result
    }
    private fun removeFlag(flags: List<String>, name: String): List<String> = buildList {
        var index = 0
        while (index < flags.size) {
            val flag = flags[index++]
            if (flag == name) { require(index < flags.size) { "Missing $name value" }; index++ }
            else if (!flag.startsWith("$name=")) add(flag)
        }
    }
    private fun effectiveValue(environment: Map<String, String>, key: String): String? = environment.entries.lastOrNull { it.key.equals(key, ignoreCase = SystemInfo.isWindows) }?.value
    private fun resolve(directory: Path, text: String): Path = Path.of(text).let { if (it.isAbsolute) it.normalize() else directory.resolve(it).normalize() }
    private companion object {
        const val FIELDS = "Dir,ImportPath,Module,GoFiles,CgoFiles,TestGoFiles,XTestGoFiles,IgnoredGoFiles,InvalidGoFiles,Error"
        const val DEPENDENCY_FIELDS = "ImportPath,Dir,Module,Error,DepsErrors,Incomplete"
        const val RECOVERY_MODULE_FIELDS = "Path,Version,Main,Dir,GoMod,Error,Replace"
        const val MAX_DEPENDENCY_ROOTS = 128
        const val MAX_RECOVERY_PREFIXES = 1024
        const val MAX_DEPENDENCY_PASSES = 8
        const val MAX_FILE = 8 * 1024 * 1024
        const val MAX_OUTPUT = 2 * 1024 * 1024
        fun readBounded(path: Path, limit: Int = MAX_FILE): ByteArray = Files.newInputStream(path).use { stream ->
            stream.readNBytes(limit + 1).also { require(it.size <= limit) { "A Go build-context input exceeds its size limit" } }
        }
        fun rawText(bytes: ByteArray): String = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        fun uri(path: Path) = GoDocumentSync.fileUri(path)
    }
}
