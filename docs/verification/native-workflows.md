# Native type search, refactoring and debug tests

The final standalone app has 395 frozen build inputs with digest
`625e694e114ab354abdbf57a55bf39fb995b7cb603f0f6d0d7e2072a081d1347`.
Its [complete build record](native-workflows-build.json) reports 524 platform
tests across 108 reports, an uncached Go race run and Go vet. The
[desktop record](native-workflows-native.json) describes a separate, fresh native
session in the owned Unicode-path fixture. All complete acceptance rows remain
unverified; these observations cover specific workflows only.

## Observed desktop behavior

| Workflow | Observed result |
| --- | --- |
| Cold type search | Before opening any Go editor, Cmd+O finds a struct, named function type, alias and declaration 239 in a 240-type file. |
| SDK type navigation | All Places query `bytes.Buffer` opens selected Go 1.24.3 SDK source at 20:6. |
| Dirty type search | A pasted Unicode-comment/type declaration is found after closing its editor and reopens at 23:6. After two native Undo commands, a fresh search reports no result. Disk checkpoints precede closing and follow Undo; no intermediate disk capture establishes whether closing saved the buffer. |
| Extract method | Standard Cmd+Option+M opens the Go variant chooser, naming dialog and native preview. `ComputeWork` preserves an existing `newMethod`. Cancel leaves source intact; Apply, one Undo, one Redo and final Undo work in the editor. |
| Inline all | Standard Cmd+Option+N previews two files, removes the unused declaration and expands both calls. The actual saved result passes `TestInlineResults`; one Undo from the second file restores both files, one Redo restores the tested result, and a final Undo restores the fixture. |
| Standard test Debug | A native running tree accompanies the XDebugger breakpoint, `TestMain:12` frame and `marker = 42`. Continue produces one passed and one failed child. Double-clicking the failure opens line 25:13. |
| Failed-only Debug retry | Debugger More → Rerun Failed Go Tests creates a new debug session for `TestNativeDebug/fails`. The tree has one failed test; console RUN lines exclude its passing sibling. |
| Manual test Debug | The advanced Go Debug `mode=test` profile retains its exact passing-child selector. Its breakpoint/locals are visible, then Continue produces one passed test and target/execution exit 0. |
| Stop before tests | Stop at `TestMain:12` marks the package Ignored and prints “Go test debugging was stopped.” No test RUN occurs. The execution handler's cancellation sentinel prints exit 0; it is not asserted as a natural target exit or a passed test. |

The failing standard and failed-only sessions both report actual target exit 1
and execution exit 1 while the Delve adapter exits 0. Adapter messages are
prefixed. The displayed test durations include time paused at breakpoints and
time spent operating the UI; they are not runtime performance measurements.

The original six source files match their initial hashes after the final Undo
and after normal application exit. All recorded debugging descendants are absent
before Quit; all 30 recorded owned process IDs are absent after Quit. No
unrelated process is terminated. The retained current-session log has no ERROR
or SEVERE lines, but has 94 warnings, including slow native class searches,
platform configuration/learning messages, JSON-schema notifications and long
paused-process notices. This does not establish warning-free operation.
The separate gopls trace also contains two error-level `window/logMessage`
notifications: document highlighting for line 23 reports a server document with
only lines 0–21; the pasted-text `didChange` is logged afterward. There are no
JSON-RPC error responses in that trace. The highlight ordering gap remains open;
the IDE log's zero ERROR count must not be extended to all protocol logs.
Bidirectional wrapper logging can interleave: one response is recorded before
its paired request. Trace ordinals are not a strict wire-arrival clock.
An independent replay pairs all 197 directional, typed-ID requests and validates
the recorded UTF-16 change/edit ranges. It rechecks source hashes, process absence,
the current build inputs and artifacts; native UI observations remain attributed
to root's CUA session. No LSP shutdown/exit exchange is captured, so process
cleanup alone does not prove a graceful language-protocol handshake.

## Boundaries and retained retries

The desktop host still labels the search tab **Classes**, despite the Go
contributor's Types label. Selected searches took more than two seconds and
triggered platform slow-contributor warnings. Complete search latency, memory,
all scopes and automatic SDK selection remain open.

The failed-test action is exposed in the current new debugger's More menu.
It is not a globally registered platform `AbstractRerunFailedTestsAction`
delegate, so global shortcut/Find Action integration is not claimed. This
fixture does not prove every nested/parallel test, package failure, manual
profile, conditional breakpoint or adapter version.

One extraction attempt included an unintended extra Return after selecting the
chooser item; that edited the source selection. The stale-input guard rejected
the plan, and native Undo restored the selection. This is retained as an
automation error, not relabeled as a stable-input product failure or an
intentional stale-input experiment. Invalid AX menu/row operations, stale
virtual-list labels and incorrect coordinate attempts are retained separately
from successful screenshot-confirmed observations.

The [type-search](type-search.md), [refactoring](native-refactor.md) and
[debug test-tree](debug-test-trees.md) records explain supported implementation
cases. The [platform listener repair](platform-psi-listener.md) records the
public-source constructor fix and both failed build cohorts. Installed GoLand
static resources and official documentation remain reference evidence; no
matched commercial runtime session is claimed by this batch.
