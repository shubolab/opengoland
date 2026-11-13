package dev.openidea.goide.http

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale

class SaveHttpResponseAction : DumbAwareAction("Save Response…", "Save the original HTTP response bytes", AllIcons.Actions.MenuSaveall) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(event: AnActionEvent) {
        val session = event.getData(HttpServicesDataKeys.SESSION)
        val entry = event.getData(HttpServicesDataKeys.REQUEST)?.entry
        event.presentation.isEnabled = session != null && entry != null && session.cachedResponse(entry) != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val session = event.getData(HttpServicesDataKeys.SESSION) ?: return
        val entry = event.getData(HttpServicesDataKeys.REQUEST)?.entry ?: return
        val response = session.cachedResponse(entry) ?: return
        val media = response.headers.entries.firstOrNull { it.key.equals("content-type", true) }
            ?.value?.firstOrNull().orEmpty().substringBefore(';').trim().lowercase(Locale.ROOT)
        val extension = when {
            media.contains("json") -> "json"
            media.contains("xml") -> "xml"
            media == "text/html" -> "html"
            media.startsWith("text/") || media.isEmpty() -> "txt"
            else -> "bin"
        }
        val descriptor = FileSaverDescriptor("Save HTTP Response", "Save the original response bytes", extension)
        val target = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
            .save(null as VirtualFile?, "http-response-${System.currentTimeMillis()}.$extension")?.file?.toPath() ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            val failure = runCatching { HttpResponseFiles.save(target, response.body) }.exceptionOrNull()
            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed) NotificationGroupManager.getInstance().getNotificationGroup("open Goland HTTP")
                    .createNotification(if (failure == null) "HTTP response saved" else "Could not save HTTP response",
                        if (failure == null) target.toString() else failure.message ?: "File write failed.",
                        if (failure == null) NotificationType.INFORMATION else NotificationType.ERROR).notify(project)
            }
        }
    }
}

/** Keep the selected target intact if writing the original wire bytes fails. */
internal object HttpResponseFiles {
    fun save(target: Path, bytes: ByteArray) {
        val absolute = target.toAbsolutePath().normalize()
        val temporary = Files.createTempFile(absolute.parent, ".http-response-", ".tmp")
        try {
            Files.write(temporary, bytes)
            try { Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING) }
        } finally { Files.deleteIfExists(temporary) }
    }
}
