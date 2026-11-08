# DAP expression editor compatibility — awaiting fix

Status: **awaiting fix and native regression**. This investigation changes no
production source, running application, or platform/plugin binary.

## Observed behavior

The native attach session in application PID `25474` successfully attached its
adapter PID `25919` to the explicitly started fixture PID `25838`. The desktop
check observed six goroutines, a `main.main:15` frame, and Watch
`cart.Value = 2640`. Run > Stop reported detach, exit 0, and disconnection. The
fixture remained alive and its heartbeat advanced from 2640 to 3567 before the
test owner cleaned up that fixture PID.

Two remaining observations are separate acceptance failures:

- Variables remained at **Collecting data**.
- Pasting a Watch expression produced a `Document/PSI mismatch` exception at
  `2026-11-08 22:18:22.829`, following `EditorPaste`.

The latter is recorded in
`/Users/wangshubo/Library/Logs/OpenIDEA-Go/idea.log`, startup PID `25474`, build
`IC-261.26222.65` / open Goland 2026.1.4. The exception identifies
`com.redhat.devtools.lsp4ij.dap.evaluation.DAPExpressionCodeFragment`, an
event-system-enabled `SingleRootFileViewProvider`, a memory-backed
`LightVirtualFile` initially containing zero characters, and an uncommitted
document. The flat log does not contain the attached `document.txt` and
`psi.txt` contents, so their precise differing texts and lengths remain unknown.
The stack reaches `PsiDocumentManagerBase.getDocument`'s explicit length check.

## Compatibility cause supported by source

LSP4IJ 0.21 constructs the expression fragment with the two-argument
`SingleRootFileViewProvider` constructor, then overrides `isPhysical()` to return
false. That constructor enables the event system. See
[DAPExpressionCodeFragment](https://github.com/redhat-developer/lsp4ij/blob/0.21.0/src/main/java/com/redhat/devtools/lsp4ij/dap/evaluation/DAPExpressionCodeFragment.java)
and the platform's
[SingleRootFileViewProvider](https://github.com/JetBrains/intellij-community/blob/idea/2026.1.4/platform/core-impl/src/com/intellij/psi/SingleRootFileViewProvider.java).

In the pinned platform,
[PsiDocumentManagerBase](https://github.com/JetBrains/intellij-community/blob/idea/2026.1.4/platform/core-impl/src/com/intellij/psi/impl/PsiDocumentManagerBase.java)
returns no cached document for a PSI file whose `isPhysical()` is false. Its
`getDocument()` then reacquires the document and requires its length to equal the
PSI length. A paste can leave the document newer than the uncommitted PSI tree,
which matches the observed failure path. The normal
[PsiFileImpl](https://github.com/JetBrains/intellij-community/blob/idea/2026.1.4/platform/core-impl/src/com/intellij/psi/impl/source/PsiFileImpl.java)
rule instead derives PSI physicalness from `viewProvider.isEventSystemEnabled()`.
This flag governs PSI/document event and caching behavior; the virtual file can
remain memory-backed.

Our `GoParserDefinition` consumes the entire lexer input under a file root.
`cart.Value` has contiguous identifier/operator/identifier tokens. No Go parser
or lexer exception appears in this stack. The evidence supports a fragment
lifecycle incompatibility; it does not justify changing Go tokenization or
suppressing the platform's consistency check.

## Smallest proposed source fix

Add a Go-owned debugger editor provider extending the public
[DAPDebuggerEditorsProvider](https://github.com/redhat-developer/lsp4ij/blob/0.21.0/src/main/java/com/redhat/devtools/lsp4ij/dap/DAPDebuggerEditorsProvider.java).
Override its four-argument `createExpressionCodeFragment` hook and return a
`DAPExpressionCodeFragment` subclass whose `isPhysical()` follows the platform's
normal `viewProvider.isEventSystemEnabled()` rule. Retaining the DAP fragment
base class preserves its current-frame access and DAP-specific editor identity.

Wire this provider through the public
[`DebugAdapterDescriptor.createDebuggerEditorsProvider`](https://github.com/redhat-developer/lsp4ij/blob/0.21.0/src/main/java/com/redhat/devtools/lsp4ij/dap/descriptors/DebugAdapterDescriptor.java)
hook in both `GoDebugDescriptor` and `GoConnectionDescriptor`. That covers native
Build/Test Debug, advanced launch, local attach, and remote attach. No global
file-type change, parser replacement, XML registration, or upstream binary patch
is needed for this candidate.

This is a proposed compatibility correction, **not a verified fix**.

## Required regression checks

1. Use a real platform PSI fixture with the Go parser registered. Create an empty
   Go DAP expression fragment, obtain its document, and load its PSI tree.
2. In a write command, insert `cart.Value`. Before committing, ask
   `PsiDocumentManager.getDocument(fragment)` again. It must return the same
   edited document without a mismatch exception. After commit, document and PSI
   text and length must agree. Repeat replacement, deletion, and Unicode input.
3. On the next independently packaged desktop build, repeat Watch paste/edit
   while paused in both Go launch and attach sessions. Verify evaluation and
   source/frame context remain usable and inspect only that startup's log for
   new mismatch exceptions.
4. Repeat Stop/detach and heartbeat checks. Preserve the already verified target
   lifecycle behavior.

Variables **Collecting data** remains unresolved. Capture native DAP `scopes` and
`variables` requests, responses, errors, and cancellation before attributing it
to the expression editor. The stock
[DAPValue](https://github.com/redhat-developer/lsp4ij/blob/0.21.0/src/main/java/com/redhat/devtools/lsp4ij/dap/client/variables/DAPValue.java)
only completes child population on a successful variables response; an RPC
failure is one possible path to an unfinished UI node. No such failure has yet
been established for this native session, and the proposed expression fix must
not be counted as resolving Variables.
