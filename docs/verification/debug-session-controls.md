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
