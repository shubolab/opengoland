package dev.openidea.goide.debug

import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.redhat.devtools.lsp4ij.dap.breakpoints.DAPBreakpointHandler
import com.redhat.devtools.lsp4ij.dap.breakpoints.DAPBreakpointProperties
import com.redhat.devtools.lsp4ij.dap.descriptors.DebugAdapterDescriptor

/** Keep DAP's per-source replacement semantics when another file still has breakpoints. */
class GoDapBreakpointHandler(session: XDebugSession, descriptor: DebugAdapterDescriptor, project: Project) :
    DAPBreakpointHandler(session, descriptor, project) {
    override fun unregisterBreakpoint(breakpoint: XLineBreakpoint<DAPBreakpointProperties>, temporary: Boolean) {
        val position = breakpoint.sourcePosition ?: return
        if (!supportsBreakpoint(breakpoint)) return
        breakpoints.remove(breakpoint)
        // The inherited public operation includes this source even when its last
        // breakpoint was removed, preserving the remaining breakpoints in every file.
        unregisterTemporaryBreakpoint(position)
    }
}
