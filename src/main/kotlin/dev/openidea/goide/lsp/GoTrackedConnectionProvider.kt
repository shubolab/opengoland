package dev.openidea.goide.lsp

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.vfs.VirtualFile
import com.redhat.devtools.lsp4ij.server.OSProcessStreamConnectionProvider
import org.eclipse.lsp4j.jsonrpc.messages.Message
import org.eclipse.lsp4j.services.LanguageServer

class GoTrackedConnectionProvider(command: GeneralCommandLine, private val documents: GoDocumentSync) : OSProcessStreamConnectionProvider(command) {
    private val session = documents.startSession()

    override fun handleMessage(message: Message, languageServer: LanguageServer?, rootURI: VirtualFile?) {
        // This public hook observes the wrapper's queue; it is not a transport acknowledgement.
        documents.observe(session, message)
    }

    override fun stop() {
        try { super.stop() } finally { documents.stopSession(session) }
    }
}
