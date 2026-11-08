package dev.openidea.goide.lint

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.KillableProcessHandler

/** Owns only the process tree started from this command line. */
object GoInspectionProcesses {
    fun create(command: GeneralCommandLine): KillableProcessHandler = KillableProcessHandler(command).apply {
        setShouldDestroyProcessRecursively(true)
        setShouldKillProcessSoftly(false)
    }
}
