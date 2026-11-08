package dev.openidea.goide.refactor

/** Extra analyzed inputs, including dependencies outside the files being edited. */
interface GoWorkspaceEditValidation {
    /** Operation-local main modules proved by the analyzed workspace configuration. */
    fun additionalEditableRoots(): Set<java.nio.file.Path> = emptySet()
    /** Called off the EDT before preparation and again after preview. Must not mutate source. */
    fun validateBackground()
    /** Called inside the native write command. Cached document/VFS state only; no filesystem or RPC. */
    fun validateDocuments()
}

data class GoEditUsage(val uri: String, val line: Int, val character: Int, val kind: String, val description: String) {
    override fun toString(): String = "$kind  ${uri.substringAfterLast('/')} : ${line + 1}  $description"
}
