# Debug session control gaps

Standard Pause is currently an incomplete workflow. The independent macOS app
at source `83af9528d8acc37274c798e4d6a68403843960f0` started an owned Go program
through native Go Debug, with the bundled Go 1.26.10 and Delve 1.27.2. Every
250 ms the program wrote an increasing heartbeat into its own project directory.

The enabled standard **Find Action > Pause Program** action was invoked while
the program was running. The heartbeat was 178 before invocation, 219 at the
first subsequent observation and 390 at the second. The native Frames list
remained empty and Variables displayed **Connected**. Thus this actual session
did not pause. This was a failing baseline, not a repaired-feature acceptance.

Native Stop reported target exit 137 and a successful debugger disconnect.
After normal application Quit, the app, file watcher, language server, adapter
and debugserver PIDs disappeared. The target was defunct and reparented to PID1;
it was no longer running, but this is not an all-PIDs-gone cleanup result. The
profile contained zero `ERROR`/`SEVERE` lines. The
[source-specific native record](debug-pause-baseline-native.json) retains the
heartbeat observations, fixture hash, exact process identities and limitations.
No native DAP wire trace was captured in this session.

A separate actual-platform-SDK protocol regression also identifies a first-load
goroutine selection problem: the session can select/evaluate another goroutine
while Step Over or Continue still uses the earlier suspend-context thread.
The controlled peer is distinct from a native Delve/goroutine UI observation.
The [frozen SDK baseline](debug-session-controls-baseline.json) retains two
independent JVM runs with the same seven cases: three expected-behavior failures
and four passing controls. In the cold Step case, `evaluate(frameId=2021)`
returned marker `202`, followed by a successful `next(threadId=101)` request;
the selected thread was 202. Complete framed requests/responses distinguish the
client's incorrect control target from a transport or server error.
Native cold-goroutine comparison and the repairs remain outstanding. A cached
stack control is not a substitute for the first-load case.

The SDK-management preview does not claim these controls are fixed. Complete
debugger acceptance, including Pause, first-load selection, delayed replies,
session disposal and launch/attach process cleanup, remains open.

## Unreleased candidate: native action and cleanup still fail

Source `dfe0cd16fb379088b4565bd9094b77c33ccbf40f` integrates the protocol and
session-control candidate. Its standalone build passed 694 Kotlin tests in 128
reports and 58 Python controls. These results do not change the public `.3`
release's status or prove native Debug controls.

An isolated native check of that assembled macOS application used the default,
unwrapped bundled Delve and a fixture with two user goroutines. After standard
**Pause Program**, the main heartbeat advanced `1402 → 1405 → 1409` and the worker
receipt advanced as well. Frames remained unavailable, Variables showed
**Connected**, and the toolbar did not offer Pause. No native DAP wire trace was
captured, so this does not establish that a pause request reached Delve.

Standard Stop reported exit 137 and a successful disconnect. The adapter and
debugserver disappeared, but the target remained a zombie after normal Quit;
the other seven captured process identities were absent. This fails the
all-processes-gone criterion even though no captured process was still running.
The isolated profile contained zero `ERROR` and zero `SEVERE` lines.

The [candidate native record](debug-session-candidate-native.json) preserves the
source and application identity, advancing receipts, full owned process checks
and the runtime-verifier limitation: its startup-log check read the default
profile instead of this live JVM's overridden log path. That log claim is not
accepted as isolated-profile evidence. Native cold-goroutine Step/Continue and
real stopped-source navigation were not reached. Further fixes and a fresh
native check are required before publishing a repaired version.

The native action has a separate capability gate: implementing
`DAPDebugProcess.startPausing()` does not set
`XDebugSession.setPauseActionSupported(true)`. The Go root client now declares
that capability for Debug sessions. Three registered-action controls reproduce
the missing toolbar gate before the fix and check running, paused, stopped and
non-debug states afterwards. They exercise the real registered Pause action and
the actual SDK session over a controlled DAP peer; all 33 related action/session
and client-routing cases pass. A fresh desktop retry is still required.

The runtime verifier now takes the log directory from the live JVM's
`idea.log.path` when set. Four controls use two real logs to reject stale global
startup/plugin records and accept the explicitly selected profile. This repairs
future verification; it does not retrospectively strengthen the old receipt.
