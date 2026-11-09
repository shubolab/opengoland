package dev.openidea.goide.project

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.Comparator
import java.util.concurrent.CancellationException
import javax.swing.SwingUtilities

internal data class GoModuleSdk(val executable: Path, val root: Path, val version: String)

/** SDK identity is established by Go itself, then used for creation and parsing. */
internal object GoSdkProbe {
    private data class Report(val root: Path, val version: String)

    fun inspect(executable: String, cancelled: () -> Boolean = { false }, onOutput: (String) -> Unit = {}): GoModuleSdk {
        check(!SwingUtilities.isEventDispatchThread()) { "Go SDK checks must run in a background task." }
        GoModuleProcesses.checkCancelled(cancelled)
        val staging = Files.createTempDirectory("open-goland-sdk-probe-")
        try {
            onOutput("Checking the selected Go SDK…")
            val selected = report(executable, staging, cancelled)
            val root = selected.root.toRealPath()
            val filename = if (System.getProperty("os.name").startsWith("Windows")) "go.exe" else "go"
            val actual = root.resolve("bin").resolve(filename).toRealPath()
            if (!Files.isRegularFile(actual) || !Files.isExecutable(actual) || !Files.isDirectory(root.resolve("src/runtime")))
                throw IOException("The reported GOROOT does not contain a complete Go SDK (bin/$filename and src/runtime): $root")
            // A shim may legitimately select an SDK. Run that SDK's own binary
            // again so later creation never trusts a successful wrapper exit.
            val verified = report(actual.toString(), staging, cancelled)
            if (verified.root.toRealPath() != root || verified.version != selected.version)
                throw IOException("The selected executable and its GOROOT report different Go SDK versions. Choose the SDK's bin/$filename executable.")
            return GoModuleSdk(actual, root, verified.version)
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            throw IOException("Unable to verify Go SDK '$executable'. Choose a Go installation's bin/go executable. ${error.message}", error)
        } finally { deleteOwnedTree(staging) }
    }

    private fun report(executable: String, directory: Path, cancelled: () -> Boolean): Report {
        val versionOutput = GoModuleProcesses.run(executable, listOf("version"), directory, cancelled, timeoutMillis = 15_000).stdout.trim()
        val version = Regex("^go version (.+) [A-Za-z0-9_]+/[A-Za-z0-9_]+$").matchEntire(versionOutput)?.groupValues?.get(1)
            ?: throw IOException("The selected program did not report a Go version.")
        if (!Regex("go[0-9]+\\.[0-9]+(?:\\.[0-9]+)?(?:(?:beta|rc)[0-9]+)?(?: .+)?").matches(version) && !version.startsWith("devel "))
            throw IOException("The selected program reported an unsupported Go version format: $version")
        val env = parseObject(GoModuleProcesses.run(executable, listOf("env", "-json", "GOROOT", "GOVERSION"), directory, cancelled, timeoutMillis = 15_000).stdout)
        val reportedVersion = string(env, "GOVERSION")
        val root = Path.of(string(env, "GOROOT"))
        if (reportedVersion != version || !root.isAbsolute) throw IOException("go version and go env did not identify the same absolute Go SDK.")
        return Report(root, reportedVersion)
    }

    fun validateModule(sdk: GoModuleSdk, directory: Path, modulePath: String, cancelled: () -> Boolean) {
        GoModuleProcesses.checkCancelled(cancelled)
        val manifest = directory.resolve("go.mod")
        if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS) || Files.size(manifest) !in 1..(1L shl 20))
            throw IOException("Go module initialization did not create a regular, non-empty go.mod file. Choose a working Go SDK and try again.")
        val parsed = parseObject(GoModuleProcesses.run(sdk.executable.toString(), listOf("mod", "edit", "-json"), directory, cancelled, timeoutMillis = 15_000).stdout)
        val module = parsed.getAsJsonObject("Module") ?: throw IOException("The generated go.mod has no module directive.")
        if (string(module, "Path") != modulePath) throw IOException("The generated go.mod module path does not match '$modulePath'. The new project was not published.")
    }

    private fun parseObject(text: String): JsonObject = try {
        JsonParser.parseString(text).takeIf { it.isJsonObject }?.asJsonObject ?: throw IOException("Go returned no JSON object.")
    } catch (error: RuntimeException) { throw IOException("Go returned invalid JSON: ${error.message}", error) }
    private fun string(value: JsonObject, key: String): String = value.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        ?.takeIf(String::isNotBlank) ?: throw IOException("Go did not report $key.")

    fun deleteOwnedTree(root: Path) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
        Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }
}
