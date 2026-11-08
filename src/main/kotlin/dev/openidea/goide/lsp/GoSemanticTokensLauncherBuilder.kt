package dev.openidea.goide.lsp

import com.redhat.devtools.lsp4ij.client.features.LSPClientFeatures
import com.redhat.devtools.lsp4ij.server.DefaultLauncherBuilder
import org.eclipse.lsp4j.jsonrpc.RemoteEndpoint
import org.eclipse.lsp4j.services.LanguageServer

/** Retain LSP4IJ's transport, logging, message processing and request-id policy. */
internal class GoSemanticTokensLauncherBuilder<S : LanguageServer>(features: LSPClientFeatures,
    private val created: (GoSemanticTokensServer) -> Unit) : DefaultLauncherBuilder<S>(features) {
    override fun createProxy(remoteEndpoint: RemoteEndpoint): S {
        val bridge = GoSemanticTokensServer(super.createProxy(remoteEndpoint))
        created(bridge)
        @Suppress("UNCHECKED_CAST")
        return bridge.server as S
    }
}
