# DAP expression editor compatibility

Status: **real platform regression passed; native local attach Watch editing
verified** in packaged snapshot `7b201d1`. See the exact build identity and
observations in [Native debug recovery](native-debug-recovery.md). An external
DAP server on loopback also passed mapped frame/breakpoint navigation and
Unicode Watch evaluation. Native headless connection, mapped frame navigation,
Unicode Watch evaluation and paused-session preservation also passed. Other
launch scenarios and native headless breakpoint interaction remain separate
acceptance obligations.

## Observed behavior

The native attach session in application PID `25474` successfully attached its
adapter PID `25919` to the explicitly started fixture PID `25838`. The desktop
check observed six goroutines, a `main.main:15` frame, and Watch
`cart.Value = 2640`. Run > Stop reported detach, exit 0, and disconnection. The
fixture remained alive and its heartbeat advanced from 2640 to 3567 before the
test owner cleaned up that fixture PID.

That earlier build exposed two separate acceptance failures:

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

## Implemented source fix

The Go-owned `GoDebuggerEditorsProvider` extends the public
[DAPDebuggerEditorsProvider](https://github.com/redhat-developer/lsp4ij/blob/0.21.0/src/main/java/com/redhat/devtools/lsp4ij/dap/DAPDebuggerEditorsProvider.java).
It overrides the four-argument `createExpressionCodeFragment` hook and returns
`GoDapExpressionCodeFragment`, a `DAPExpressionCodeFragment` subclass whose
`isPhysical()` follows the platform's
normal `viewProvider.isEventSystemEnabled()` rule. Retaining the DAP fragment
base class preserves its current-frame access and DAP-specific editor identity.

Both Go descriptors wire this provider through the public
[`DebugAdapterDescriptor.createDebuggerEditorsProvider`](https://github.com/redhat-developer/lsp4ij/blob/0.21.0/src/main/java/com/redhat/devtools/lsp4ij/dap/descriptors/DebugAdapterDescriptor.java)
hook. `GoDebugDescriptor` was extracted from the factory into its own file; its
launch lifecycle and parameters are unchanged. The two overrides cover native
Build/Test Debug, advanced launch, local attach, and remote attach. No global
file-type change, parser replacement, XML registration, or upstream binary patch
is needed for this source change.

The expression provider changes PSI/document lifecycle only. Variables recovery
is implemented separately by the Go DAP client/protocol bridge, as described
below; it is not attributed to the expression fragment fix.

## Platform test and native regression checks

`GoDebuggerEditorsProviderTest` uses `LightPlatformTestCase` and the actual
Application, Project, production Go parser, PSI/document managers, and write
commands. It includes an upstream-fragment control that must reproduce the
mismatch, the public provider path before commit, and repeated Unicode
replacement/deletion after commit. No core document/cache service is mocked.
All three provider tests passed as part of the 149-test suite with zero failures,
errors, or skips before packaging snapshot `7b201d1`. The upstream-fragment
control reproduces the mismatch, while the production provider returns the
same edited document before PSI commit and remains consistent after repeated
Unicode replacement/deletion.

Native application PID `36202` then pasted and evaluated
`cart.Value /* 中文 😀 */`, replaced it with
`cart.Value + 1 /* 替换 😀 */`, and deleted/re-entered `cart.Value` in a local
PID-attach session. Results were `2053`, `2054`, and `2053` in the selected
`main.main` frame. The editor remained usable through breakpoint selection,
value editing, stepping, and native Stop/disconnect. Exact runtime observations
and remaining cache-freshness limitations are in
[native-debug-recovery.md](native-debug-recovery.md).

## Separate Variables recovery

The new native trace established that Delve's optional package-globals lookup
failed the whole scopes request with `Unable to list globals`. The stock
[DAPStackFrame](https://github.com/redhat-developer/lsp4ij/blob/0.21.0/src/main/java/com/redhat/devtools/lsp4ij/dap/client/DAPStackFrame.java)
and
[DAPValue](https://github.com/redhat-developer/lsp4ij/blob/0.21.0/src/main/java/com/redhat/devtools/lsp4ij/dap/client/variables/DAPValue.java)
only complete the relevant child population on successful protocol responses.
The old native Variables spinner is consistent with that observed RPC failure.

`GoDapProtocol` retries actual locals only for that precise globals failure,
restores `showGlobalVariables` afterward, and provides a read-only, explicit
unavailable-package group. Other scopes/variables failures complete with error
items and bounded request deadlines. Private error handles are not sent to
Delve. The public DAP endpoint forwards all unintercepted methods, including
initialize/attach/disconnect; the Go client also routes the upstream convenience
execution methods through the bridge. Stop closes pending execution ownership
before forwarding disconnect, rather than waiting for scopes work to complete.

Native recovery populated locals, preserved frame-specific Watch evaluation,
and showed full package globals again at the actual breakpoint. This resolves
the reproduced endless spinner in that workflow; it does not establish every
possible variable representation or adapter failure mode.
