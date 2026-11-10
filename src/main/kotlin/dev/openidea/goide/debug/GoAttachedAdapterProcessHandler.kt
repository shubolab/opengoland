package dev.openidea.goide.debug

import com.intellij.execution.configurations.GeneralCommandLine

/** Attach keeps the same bounded, nonrecursive owned-adapter cleanup contract. */
internal class GoAttachedAdapterProcessHandler(command: GeneralCommandLine, graceSeconds: Long = 3) :
    GoOwnedDapAdapterProcessHandler(command, graceSeconds)
