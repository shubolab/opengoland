# Native debug recovery — 2026-11-08–09

Independent application: **open Goland**, bundle `dev.opengoland.ide`, native
process `36202`. The packaged build-input SHA-256 is
`7b201d1ca261890a68447656626063bbd349643c6bd6fa3ee9882a2e057fba3f`
(140 input files). Before packaging, **149 Kotlin tests passed with zero
failures, errors, or skips**, including real IntelliJ platform PSI/document,
DAP transport/routing, and native VFS Undo/Redo regressions. Packaging, plugin
structure validation, and strict deep signature verification passed. Live
name/bundle/plugin verification passed for that process; the retained result is
[runtime-debug-recovery.json](runtime-debug-recovery.json).

This records our product's native runtime behavior. It does not establish
reference GoLand parity or close the full acceptance matrix.

## Local PID attach and unavailable globals

A disposable debug-symbol fixture, owned by this test, ran as PID `36191`.
The native **Go Attach to Process** configuration started adapter PID `36360`
and attached to that existing process. The fixture increments a `Counter.Value`
and writes a heartbeat from its own loop; no existing user process was attached.

At the entry stop in `runtime.gopark`, Delve returned scopes error `-32001`,
`Unable to list globals`, with detail that the current package could not be
determined from an unspecified function location. The native DAP trace showed:

1. The original full `scopes` request failed.
2. `evaluate("dlv config showGlobalVariables false")` succeeded.
3. A second `scopes` request returned actual local variables.
4. `evaluate("dlv config showGlobalVariables true")` succeeded.
5. The native tree populated locals and an explicit **Package variables
   unavailable** group. It did not remain at **Collecting data**.

Selecting `main.main:15` displayed the real local `cart` with `Value: 2053`.
After a native line breakpoint was set and Resume executed, the target stopped
at `main.checkpoint:7`. Full scopes then returned both **Locals** and **Globals
(package main)** without the recovery path. The trace included the real global
`sink = 2052`. This establishes that the recovery did not permanently suppress
package variables in this session.

## Watch editing, value modification, and step

In the selected main frame, native paste/evaluation produced:

| Native editor operation | Expression | Observed result |
| --- | --- | --- |
| Paste | `cart.Value /* 中文 😀 */` | `2053` |
| Select all and replace | `cart.Value + 1 /* 替换 😀 */` | `2054` |
| Delete and re-enter | `cart.Value` | `2053` |

The PID `36202` startup segment of the product log, from `23:26:06` through
`23:38:57`, contains no new `Document/PSI mismatch`, `Last Action: EditorPaste`,
or `SEVERE` entry. This bounded log check is separate from the older failures
retained earlier in the same log file.

At the breakpoint, expanding the pointer, its dereferenced `Counter`, and the
`Value` child displayed the actual value `2053`. F2 opened the native value
editor. Entering `3000` sent `setVariable` for that field and Delve replied with
`value: "3000"`. Step Over advanced to `main.checkpoint:8`; the refreshed local
and a new evaluation of `cart.Value` both displayed **3000**.

**Remaining limitation:** immediately after `setVariable`, Delve returned the
old `2053` from subsequent `scopes`/`variables` and `evaluate` calls. The value
became visible after the actual step. This check proves the target memory
change and subsequent execution, but does not prove immediate cache/UI freshness
while remaining at the same stopped instruction. The adapter's successful
reply alone is not counted as that stronger result.

## Native detach

The disposable line breakpoint was removed in the UI. Clearing its server-side
state was not established in this phase. While paused at line 8, native
Run > Stop reported **Detaching without terminating target process**,
**Process finished with exit code 0**, and **Disconnected successfully from the
debug server**. OS checks confirmed adapter PID `36360` exited and the exact
owned target PID `36191` remained alive. However, its process status was `Ts`
and its heartbeat stayed at `2053` during the immediate post-detach check.

**Remaining limitation:** this paused-Stop workflow did not leave the target
running. The earlier direct protocol probe resumed before disconnect and does
not establish this distinct paused-Stop behavior. Target survival, target
execution, and native console success are separate observations. The test owner
resumed only this owned fixture before continuing external-server checks; that
cleanup operation is not product behavior. A separate raw-DAP investigation
removed only the pre-disconnect Continue from a disposable copy of the existing
attach probe: one of its first two cases reproduced the surviving-but-stopped
target without IntelliJ/LSP4IJ. Six additional paused-disconnect cases (three
local adapters and three external mapped adapters) all resumed normally. Those
results indicate intermittent backend behavior; they neither erase this native
failure nor establish a product-level fix.

## External DAP server and source mapping

Native Run > Edit Configurations displayed the **Go Remote Debug (DAP)**
configuration with host `127.0.0.1`, port `59250`, process ID `36191`, and the
explicit `dlv dap` server type. Its mapping field used local-to-server direction:

```text
/Users/wangshubo/develop/openidea-go/.tools/native-attach-client => /Users/wangshubo/develop/openidea-go/.tools/native-attach-probe
```

Debug connected to the separately started DAP server PID `36192`; the IDE did
not start a new adapter for this configuration. The real wire trace contained
`clientName: open Goland 2026.1.4`, local-PID attach to `36191`, and the expected
`substitutePath`. Selecting the main frame opened the mirrored **local client**
`native-attach-client/main.go`, rather than the server source path. Locals
showed `cart.Value = 5027`, and pasted Watch `cart.Value /* 远程 😀 */` returned
`5027` in that frame.

Cmd+F8 set a native breakpoint on mirrored line 7. Resume stopped at
`main.checkpoint:7` with a verified red breakpoint, actual locals, and full
package globals. The breakpoint was removed in the UI; its server-side removal
was not independently established in this phase. This time the test explicitly
resumed before Stop; `disconnect` request 39 used `terminateDebuggee: false`
and was acknowledged in 9 ms. OS identity checks found target PID `36191`
alive in state `Ss`, its heartbeat advanced from `5409` to `5423` in about
0.3 seconds, and the single-use external adapter PID `36192` exited.

This establishes the external `dlv dap` configuration UI, mapped breakpoint and
stack navigation, Watch evaluation, and running-target detach on loopback. It
does not establish a different machine's network/tunnel setup. Native headless
evidence follows separately; it must not be inferred from this server type.

## Headless multi-client session

The test owner started external server PID `37699` with:

```sh
dlv attach 36191 --headless --accept-multiclient --listen=127.0.0.1:0
```

It listened on loopback port `61862`. Native Edit Configurations selected
**Headless Delve — existing session (DAP)**, retained the same source mapping,
and displayed host `127.0.0.1` and port `61862`. Debug connected to this existing
server, without starting a local adapter. The attach trace used mode `remote`
and no PID, because that server already owned the session.

Selecting the main frame opened `native-attach-client/main.go`. The native
locals tree showed `Counter.Value = 6516`; Watch
`cart.Value` with a comment containing `Headless中文😀` evaluated to `6516` in that frame.

Stop was intentionally issued while paused. Disconnect request 24 specified
`terminateDebuggee: false` and was acknowledged in 5 ms. The server console
explicitly reported closing the client session and leaving the multi-client
server and debuggee halted. OS checks found server PID `37699` alive in state
`Ss`, target PID `36191` alive in state `TXs`, and unchanged heartbeat
`6516 → 6516` during a 0.3-second check. That is the expected preservation of a
paused headless session; it differs from the local adapter's paused-detach
limitation above because this server intentionally retains the debugging
session for subsequent clients.

This establishes native headless configuration selection, connection, mapped
frame navigation, Unicode Watch evaluation, and paused-state preservation on
loopback. A native headless breakpoint was not exercised in this phase. The
prior direct protocol probe covers its own breakpoint/source-mapping path,
not that remaining native interaction. A different host or tunnel was not
tested.

## Scope and remaining checks

The expression compatibility tests and this local attach workflow cover the
previously reproduced Watch paste mismatch and unfinished Variables tree for
this specific build. The external-DAP workflow additionally covers one mapped
remote configuration on loopback. The headless workflow covers connection,
frame navigation, Watch evaluation, and paused-state preservation. These checks
do not establish every launch/attach frame type, native headless breakpoint
interaction, adapter failure mode, or debugger feature.
The request-level test suite also verifies bounded error completion, preserved
RPC forwarding, cancellation, restoration before execution, and immediate Stop
forwarding. A unit or transport test does not replace native runtime evidence.


## Build boundary for subsequent review fixes

All GUI observations above belong to packaged digest `7b201d1…`, native PID
`36202`, and its preceding 149-test run. A subsequent review identified that
Delve string assignment through `setVariable` can execute target code and must
share the execution/restoration queue. That follow-up is verified separately
below. Passing platform tests or repackaging a later revision does not transfer
these GUI observations
automatically to that build.


## Execution-queue review build — native process 38607

This later standalone build has input digest
`a00249f477a18264c835b31f4da1c18e7b233b80ac15dd560b062f3dbeee46f0`.
All **149 Kotlin tests** passed, packaging and strict signing passed, and the
live process identity/plugin check passed; see
[runtime-debug-recovery-final.json](runtime-debug-recovery-final.json).
`setVariable` now shares the execution/restoration queue, because assigning a
Go string can execute target code. These are this build's own native results;
they do not replay all preceding external-DAP/headless scenarios.

Native Edit Configurations created **Final Review Attach** with the owned target
PID `38601`; Debug started adapter PID `38854`. Selecting the entry main frame
showed local `cart.Value = 6030`, and a Watch evaluating `cart.Value` with a
Chinese/emoji comment returned `6030`.

**Observed entry-frame failure:** F2 on its `Value` field returned **Unable to
look up variable**. This failure remains recorded even though a later actual
breakpoint allowed editing; its precise cause is not established by this check.

A native breakpoint stopped at `main.checkpoint:7`. Expanding the pointer, its
dereferenced struct and `Value` then using F2 sent the field edit to `7000`.
Step Over reached line 8; the native local value and a new Watch evaluation both
showed `7000`. Native Stop reported detach and adapter exit 0. OS identity
checks then confirmed adapter PID `38854` exited and target PID `38601` remained
alive in state `Ss`; stable numeric heartbeat reads advanced `9509 → 9521` in
0.25 seconds. An earlier empty-read race was excluded from that measurement.
This particular detach left its target running; the earlier intermittent
paused-Stop failure remains recorded.

This process's startup log segment, from `23:44:02` through native Quit, contained
zero `Document/PSI mismatch`, zero `Last Action: EditorPaste`, and zero `SEVERE`
entries.

**Additional failure found:** with a breakpoint retained in `app/main.go:15`,
removing the final breakpoint in the fixture source did not send
`setBreakpoints` with an empty list for that removed file. Resume therefore hit
the UI-deleted fixture breakpoint again. The upstream public breakpoint-handler
source only sends that empty-file update when the global set is empty. A
Go-owned public-hook correction and platform regressions were prepared for the
next build. This snapshot does not establish correct per-file breakpoint removal.

The corrected package is identified separately below. These observations do
not transfer automatically to that later build.


## Per-file breakpoint correction — native process 40389

Final packaged input digest:
`3331c4eeef198e3f141faffa74796b1c1e7f3eaf6200809d66f136e9d8974f34`
(**142 input files**). All **154 Kotlin tests passed with zero failures, errors,
or skips**. `test buildPlugin`, packaging, plugin structure validation, and
strict deep signature verification passed. Go engine race tests and vet also
passed for the unchanged engine. Native process `40389` passed live product
identity and plugin verification; see
[runtime-debug-recovery-breakpoints.json](runtime-debug-recovery-breakpoints.json).

`GoDapBreakpointHandler` uses the public upstream handler hook to include the
removed breakpoint's source in the next per-source replacement, even when
another source still has breakpoints. Both Go debug descriptors use this
handler. Five real IntelliJ platform regressions cover the upstream failure
control, last-in-file removal, removal among multiple same-file breakpoints,
clearing all sources, and temporary Run-to-Cursor removal without losing
persistent breakpoints.

Native **Final Review Attach** targeted the same owned process PID `38601` and
started adapter PID `40512`. Initial wire requests registered fixture line 7,
which Delve verified, and retained the separate `app/main.go:15` breakpoint.
The latter source is absent from this fixture binary and was therefore
unverified, but its request state was preserved. Resume stopped at
`main.checkpoint:7`, displaying actual **Locals**, **Globals**, and
`cart.Value = 27574`.

Selecting `sink` on fixture line 7 and pressing Cmd+F8 removed its native UI
breakpoint. This time the actual wire trace showed:

1. `setBreakpoints` request 20 for the fixture source carried both
   `breakpoints: []` and `lines: []`; Delve acknowledged it in 1 ms.
2. Request 21 retained `app/main.go:15` in the other source's replacement and
   was acknowledged in 1 ms.
3. Continue request 22 was acknowledged in 2 ms. Before Stop, stable numeric
   heartbeat reads advanced `27928 → 27952` in 0.5 seconds. The native Run menu
   disabled stepping while the target ran. The removed fixture breakpoint did
   not stop execution again during that check.

Native Stop reported non-terminating detach and adapter exit 0. OS checks
confirmed adapter PID `40512` exited, target PID `38601` remained alive in state
`Ss`, and the post-Stop heartbeat advanced `29407 → 29422` in 0.3 seconds.
This verifies the reproduced per-file breakpoint-removal correction in the
standalone native product while preserving another source's request state.
It is not evidence that the absent `app/main.go` breakpoint could bind in this
unrelated target binary.

The startup log segment beginning `23:57:49` contained no new
`Document/PSI mismatch`, `Last Action: EditorPaste`, or `SEVERE` entry through
this check. A separate **unresolved large-file failure recurred**: gopls
`textDocument/semanticTokens/full` reported that runtime `proc.go` is 214687
bytes, exceeding its 100000-byte full-token limit. The native error notification
is retained as a known language-highlighting limitation, not hidden or counted
as repaired by the debugger fixes.

These final native observations cover the per-file removal regression and
local attach/detach in this exact build. Earlier Watch-edit, external-DAP and
headless results retain their own build identities. Immediate value-cache
freshness, the entry-frame value-edit failure, intermittent paused local
detach, native headless breakpoint interaction, and broader acceptance items
remain open.

Native Quit completed and PID `40389` exited before any later package operation.
After the survival checks, the test owner stopped its own fixture PID `38601`
and removed the exact temporary Final Review Attach configuration. All owned
fixture/adapter processes from this check were cleaned up.
