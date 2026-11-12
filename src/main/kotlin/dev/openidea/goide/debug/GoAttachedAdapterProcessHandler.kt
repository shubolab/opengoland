package dev.openidea.goide.debug

import com.intellij.execution.configurations.GeneralCommandLine

/** Attach keeps the same bounded, nonrecursive owned-adapter cleanup contract. */
internal class GoAttachedAdapterProcessHandler(command: GeneralCommandLine, graceSeconds: Long = 3) :
    // An attach adapter deliberately detaches from a user process. Its descendants
    // are not owned by this IDE session and must never be reaped here.
    GoOwnedDapAdapterProcessHandler(command, graceSeconds, terminateTrackedDescendantsOnStop = false)
