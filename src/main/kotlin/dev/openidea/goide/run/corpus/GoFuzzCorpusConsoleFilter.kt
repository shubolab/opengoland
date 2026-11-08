package dev.openidea.goide.run.corpus

import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.HyperlinkInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import java.nio.file.Path

/** Relative corpus paths are meaningful only in the actual Go package's preloaded directory. */
class GoFuzzCorpusConsoleFilter(private val packageDirectory: String?) : Filter, DumbAware {
    override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
        if (line.length > 64 * 1024 || entireLength < line.length) return null
        val match = FAILURE.find(line) ?: return null
        val path = match.groups[1] ?: return null
        val corpus = GoFuzzCorpusPath.reportedPath(packageDirectory, path.value) ?: return null
        val start = entireLength - line.length + path.range.first
        return Filter.Result(start, start + path.value.length, GoFuzzCorpusHyperlink(corpus.path))
    }

    companion object { private val FAILURE = Regex("^[\\t ]*Failing input written to ([^\\r\\n]+)(?:\\r?\\n)?$") }
}

/** A click can refresh the disk on a worker; highlighting/availability never does filesystem work. */
class GoFuzzCorpusHyperlink(val path: Path) : HyperlinkInfo {
    override fun navigate(project: Project) {
        if (project.isDisposed) return
        val application = ApplicationManager.getApplication()
        application.executeOnPooledThread {
            if (project.isDisposed) return@executeOnPooledThread
            val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
            // Resolve content detection and load the text on this worker too. The
            // later editor/context actions can use the already cached document.
            val document = file?.takeIf { it.isValid && !it.isDirectory }?.let {
                ReadAction.compute<com.intellij.openapi.editor.Document?, RuntimeException> {
                    FileDocumentManager.getInstance().getDocument(it)
                }
            }
            application.invokeLater({
                if (!project.isDisposed) WriteIntentReadAction.run {
                    if (file != null && document != null && file.isValid && !file.isDirectory) OpenFileDescriptor(project, file, 0).navigate(true)
                    else Messages.showWarningDialog(project, "The fuzz corpus input is no longer available: $path", "Go Fuzz Corpus")
                }
            }, project.disposed)
        }
    }
}
