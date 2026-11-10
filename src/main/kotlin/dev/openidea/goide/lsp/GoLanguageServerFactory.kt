package dev.openidea.goide.lsp

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.redhat.devtools.lsp4ij.LanguageServerFactory
import com.redhat.devtools.lsp4ij.ServerStatus
import com.redhat.devtools.lsp4ij.client.features.LSPClientFeatures
import com.redhat.devtools.lsp4ij.client.features.LSPRenameFeature
import com.redhat.devtools.lsp4ij.client.features.LSPUsageFeature
import com.redhat.devtools.lsp4ij.server.StreamConnectionProvider
import dev.openidea.goide.settings.GoToolchain
import dev.openidea.goide.lang.GoLanguage
import dev.openidea.goide.run.GoRunBuildContextService
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.GeneralClientCapabilities
import org.eclipse.lsp4j.jsonrpc.Launcher
import org.eclipse.lsp4j.services.LanguageServer
import java.util.concurrent.atomic.AtomicReference

class GoLanguageServerFactory : LanguageServerFactory {
    override fun createLanguageClient(project: Project) = GoLanguageClient(project)

    override fun createConnectionProvider(project: Project): StreamConnectionProvider =
        GoTrackedConnectionProvider(GoToolchain.command(project, "gopls", listOf("serve")), GoDocumentSync.get(project))

    override fun createClientFeatures(): LSPClientFeatures = object : LSPClientFeatures(), GoLspBuildContextEpoch {
        private val semanticServer = AtomicReference<GoSemanticTokensServer?>()
        private val initializedBuildContext = AtomicReference<GoToolchain.Settings?>()
        override fun isBuildContextCurrent() = initializedBuildContext.get()?.let {
            it == GoToolchain.capture(project).copy(delveExecutable = "")
        } ?: true
        init {
            setDocumentSymbolFeature(GoDocumentSymbolFeature())
            setSemanticTokensFeature(GoBuildContextSemanticTokensFeature())
            setCodeActionFeature(GoBuildContextCodeActionFeature())
            setInlayHintFeature(GoBuildContextInlayHintFeature())
            setDocumentHighlightFeature(GoBuildContextDocumentHighlightFeature())
            // Find Usages locates references and declarations. Type definitions
            // and implementations remain available through their own actions;
            // gopls legitimately rejects those queries on ordinary functions.
            setUsageFeature(object : LSPUsageFeature() {
                override fun shouldIncludeTypeDefinitions(file: PsiFile) = false
                override fun shouldIncludeImplementations(file: PsiFile) = false
            })
            // Standard Rename is supplied by our versioned preview/undo handler.
            // Keep the distinct file-operation capabilities used by project-tree renames.
            setRenameFeature(object : LSPRenameFeature() {
                override fun isRenameSupported(file: PsiFile) = file.language != GoLanguage && super.isRenameSupported(file)
            })
        }
        override fun isCaseSensitive(file: PsiFile) = true
        override fun getLineCommentPrefix(file: PsiFile) = "//"
        override fun getBlockCommentPrefix(file: PsiFile) = "/*"
        override fun getBlockCommentSuffix(file: PsiFile) = "*/"
        override fun keepServerAlive() = true
        override fun <S : LanguageServer> createLauncherBuilder(): Launcher.Builder<S> =
            GoSemanticTokensLauncherBuilder(this) { semanticServer.getAndSet(it)?.close() }
        override fun handleServerStatusChanged(status: ServerStatus) {
            if (status == ServerStatus.stopping || status == ServerStatus.stopped) semanticServer.getAndSet(null)?.close()
            super.handleServerStatusChanged(status)
            if (status == ServerStatus.started && isBuildContextCurrent()) GoRunBuildContextService.refreshLspFeatures(project)
        }
        override fun dispose() {
            semanticServer.getAndSet(null)?.close()
            super.dispose()
        }
        override fun initializeParams(params: InitializeParams) {
            super.initializeParams(params)
            // The document mirror, native editor and chunk boundaries all use UTF-16.
            val general = params.capabilities.general ?: GeneralClientCapabilities().also { params.capabilities.general = it }
            general.positionEncodings = listOf("utf-16")
            // Capture the same values used below. Until the asynchronous restart
            // completes, an old server must not answer for new build settings.
            // Delve-only changes do not restart gopls and do not affect it.
            val settings = GoToolchain.capture(project).copy(delveExecutable = "")
            // Only explicit project overrides here: the BGT command path already
            // adapted its actual CONSOLE parent. Do not resolve a console env in initialize.
            val environment = GoToolchain.goplsEnvironment(GoToolchain.parseEnvironment(settings.environment)).toMutableMap()
            if (settings.goos.isNotEmpty()) environment["GOOS"] = settings.goos
            if (settings.goarch.isNotEmpty()) environment["GOARCH"] = settings.goarch
            if (settings.cgoEnabled.isNotEmpty()) environment["CGO_ENABLED"] = settings.cgoEnabled
            params.initializationOptions = buildMap<String, Any> {
                put("env", environment)
                put("semanticTokens", true)
                put("usePlaceholders", true)
                put("gofumpt", false)
                put("staticcheck", true)
                put("hints", mapOf("assignVariableTypes" to true, "compositeLiteralFields" to true, "parameterNames" to true))
                if (settings.buildTags.isNotEmpty()) put("buildFlags", listOf("-tags=" + settings.buildTags.split(Regex("[,\\s]+")).filter(String::isNotEmpty).joinToString(",")))
            }
            initializedBuildContext.set(settings)
        }
    }
}
