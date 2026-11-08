package dev.openidea.goide.debug

import com.redhat.devtools.lsp4ij.dap.DebugMode
import java.nio.file.Path

enum class GoRemoteServerKind(private val label: String) {
    DAP_PROCESS("dlv dap — attach to process ID on server"), HEADLESS_SESSION("Headless Delve — existing session (DAP)");
    override fun toString() = label
}

/** Adapter transport and DAP request are independent: local attach spawns an adapter but sends attach. */
data class GoDebugConnection(
    val localAdapter: Boolean,
    val processId: Int?,
    val remoteKind: GoRemoteServerKind,
    val host: String,
    val port: Int,
    val workingDirectory: String,
    val environment: Map<String, String>,
    val substitutePath: List<Map<String, String>>,
    val hideSystemGoroutines: Boolean,
) {
    val adapterMode: DebugMode get() = if (localAdapter) DebugMode.LAUNCH else DebugMode.ATTACH
    val requestMode: DebugMode get() = DebugMode.ATTACH
    fun dapParameters(name: String): Map<String, Any> = buildMap {
        put("type", "go"); put("name", name); put("request", "attach")
        put("mode", if (!localAdapter && remoteKind == GoRemoteServerKind.HEADLESS_SESSION) "remote" else "local")
        processId?.let { put("processId", it) }
        put("stopOnEntry", true)
        put("showGlobalVariables", true)
        put("hideSystemGoroutines", hideSystemGoroutines)
        put("stackTraceDepth", 50)
        if (substitutePath.isNotEmpty()) put("substitutePath", substitutePath)
    }

    companion object {
        fun fromOptions(options: GoConnectionOptions, projectRoot: String, localAdapter: Boolean): GoDebugConnection {
            val kind = runCatching { GoRemoteServerKind.valueOf(options.remoteKind) }
                .getOrElse { throw IllegalArgumentException("Choose a supported DAP server type.") }
            val needsPid = localAdapter || kind == GoRemoteServerKind.DAP_PROCESS
            val pid = if (needsPid) options.processId.trim().toIntOrNull()?.takeIf { it > 0 }
                ?: throw IllegalArgumentException("Enter the positive process ID on the debugger server's machine.") else null
            val host = options.host.trim()
            val port = options.port.trim().toIntOrNull()
            if (!localAdapter) {
                require(host.isNotBlank() && host.none { it.isWhitespace() || it == '\u0000' } && !host.contains("://") && !host.startsWith('[') && host.count { it == ':' } != 1) {
                    "Enter a DAP host name or IP address without a URL, port, or IPv6 brackets."
                }
                require(port != null && port in 1..65535) { "Enter a DAP TCP port between 1 and 65535." }
            }
            val rawDirectory = options.workingDirectory.ifBlank { projectRoot }
            require(rawDirectory.isNotBlank() && '\u0000' !in rawDirectory) { "Select the local adapter working directory." }
            val directory = Path.of(rawDirectory).let { if (it.isAbsolute) it else Path.of(projectRoot).resolve(it) }.toAbsolutePath().normalize().toString()
            val environment = dev.openidea.goide.settings.GoToolchain.parseEnvironment(options.environmentVariables).toMap()
            return GoDebugConnection(localAdapter, pid, kind, host, port ?: 0, directory, environment,
                parseMappings(options.sourceMappings), options.hideSystemGoroutines)
        }

        fun parseMappings(text: String): List<Map<String, String>> = text.lineSequence().map(String::trim).filter(String::isNotEmpty).map { line ->
            val parts = line.split("=>", limit = 2).map(String::trim)
            require(parts.size == 2 && parts.all { it.isNotEmpty() && '\u0000' !in it }) {
                "Source mappings use local absolute prefix => remote absolute prefix, one per line."
            }
            require(Path.of(parts[0]).isAbsolute && isAbsoluteServerPath(parts[1])) { "Both source mapping prefixes must be absolute paths." }
            mapOf("from" to Path.of(parts[0]).normalize().toString(), "to" to parts[1])
        }.toList().also { mappings ->
            require(mappings.map { it.getValue("from") }.distinct().size == mappings.size) { "Each local source prefix can have only one remote mapping." }
        }

        private fun isAbsoluteServerPath(path: String) = path.startsWith('/') || Regex("^[A-Za-z]:[\\\\/].*").matches(path)
    }
}
