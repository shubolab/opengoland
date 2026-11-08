package dev.openidea.goide.lsp

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.redhat.devtools.lsp4ij.LanguageServerFactory
import com.redhat.devtools.lsp4ij.client.features.LSPClientFeatures
import com.redhat.devtools.lsp4ij.client.features.LSPRenameFeature
import com.redhat.devtools.lsp4ij.server.StreamConnectionProvider
import dev.openidea.goide.settings.GoProjectSettings
import dev.openidea.goide.settings.GoToolchain
import dev.openidea.goide.lang.GoLanguage
import org.eclipse.lsp4j.InitializeParams

class GoLanguageServerFactory : LanguageServerFactory {
    override fun createLanguageClient(project: Project) = GoLanguageClient(project)

    override fun createConnectionProvider(project: Project): StreamConnectionProvider =
        GoTrackedConnectionProvider(GoToolchain.command(project, "gopls", listOf("serve")), GoDocumentSync.get(project))

    override fun createClientFeatures(): LSPClientFeatures = object : LSPClientFeatures() {
        init {
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
        override fun initializeParams(params: InitializeParams) {
            super.initializeParams(params)
            val settings = GoProjectSettings.get(project)
            val environment = GoToolchain.parseEnvironment(settings.environment).toMutableMap()
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
        }
    }
}
