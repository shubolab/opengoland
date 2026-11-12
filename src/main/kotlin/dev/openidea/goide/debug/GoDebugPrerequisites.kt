package dev.openidea.goide.debug

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.SystemInfo
import java.nio.file.Files
import java.nio.file.Path

/** Fail before Delve starts when macOS will deny the debugserver attach. */
internal object GoDebugPrerequisites {
    private val devToolsSecurity = Path.of("/usr/sbin/DevToolsSecurity")

    fun requireMacDebuggerAccess() {
        if (!SystemInfo.isMac || ApplicationManager.getApplication().isHeadlessEnvironment) return
        if (!Files.isExecutable(devToolsSecurity)) {
            throw ExecutionException("macOS debugger permission check is unavailable: $devToolsSecurity is missing.")
        }
        val result = try {
            CapturingProcessHandler(GeneralCommandLine(devToolsSecurity.toString(), "-status"))
                .runProcess(3_000, true)
        } catch (failure: Exception) {
            throw ExecutionException("Could not verify macOS debugger permissions. Run 'sudo DevToolsSecurity -enable' in Terminal, then retry.", failure)
        }
        if (result.isTimeout || result.isCancelled) {
            throw ExecutionException("Timed out verifying macOS debugger permissions. Run 'sudo DevToolsSecurity -enable' in Terminal, then retry.")
        }
        val status = (result.stdout + "\n" + result.stderr).trim()
        permissionFailure(status, result.exitCode)?.let { throw ExecutionException(it) }
    }

    internal fun permissionFailure(status: String, exitCode: Int): String? {
        if (exitCode == 0 && !status.contains("disabled", ignoreCase = true)) return null
        return "macOS has not enabled debugger attach permission. Delve will start a suspended helper and appear to hang. " +
            "Run 'sudo DevToolsSecurity -enable' in Terminal, enter the administrator password there, then rerun Debug. " +
            "If this is a managed computer, ask its administrator to allow developer tools. (${status.trim()})"
    }
}
