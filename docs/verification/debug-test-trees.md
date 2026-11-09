# Native test results during Go debugging

Both the standard Go Test configuration and advanced Go Debug `mode=test` use a
native SM test console while retaining the standard XDebugger/DAP session. Other
Go Debug modes retain their existing runner. This record describes partial
implementation and controlled checks; full test/debug acceptance remains open.

## Target output and execution result

The runner captures the concrete configuration and selected Go toolchain before
background preparation. Package metadata, Delve startup and `go tool test2json`
run off the EDT. The test binary receives `-test.v=test2json`. Only DAP target
stdout/stderr enters the official converter; adapter, console and REPL output
does not become test protocol. A persistent UTF-8 encoder preserves supplementary
characters split across chunks. Converter input is bounded and cleanup has a
deadline.

The native test event handler is the execution result's process handler. Its
terminal result comes from the test target, rather than the adapter's OS status.
Adapter diagnostics are explicitly prefixed. Failed-test rerun is registered in
the debugger toolbar and preserves Debug executor, concrete configuration type,
package and exact nested test selector. Advanced manual profiles retain their
own factory and fields instead of being converted into standard Go Test profiles.

## Delve 1.24.2 compatibility

Actual Delve 1.24.2 emits `terminated` without a DAP `exited` event in the tested
normal completion path. Its exact PID/status console diagnostic arrives during
disconnect. The client prefers a real typed `exited` event. For the explicitly
detected 1.24.2 version only, after natural termination and while its single owned
disconnect is in progress, it accepts the complete console diagnostic matching
the previously observed target system PID. Target output, wrong PIDs, early
diagnostics and other versions cannot supply this fallback status. The transport
stays alive through the disconnect acknowledgement or bounded deadline.

This is a narrowly pinned adapter compatibility bridge, not a generic DAP exit
contract. Without a confirmed status the IDE reports incomplete/failed results;
it does not assume exit 0. Converter failure can fail the IDE run while preserving
the true target status in diagnostics. Stop requests cancellation before teardown;
Stop after a confirmed target exit does not rewrite that exit as cancellation.

## Controlled evidence

The initial controlled local source subset has digest
`e7301e56bb3a0bd745c4b7ef6d444dd3fbabe51f37e92260fb1908e4d0934b17`;
this is not the complete product input digest. Twenty actual SDK/official
converter/controlled-DAP cases pass, including manual profile routing, frozen
toolchain settings, source location, status bridge negatives, cancellation and
converter failure. Eleven existing launch/argument/runner compatibility cases
also pass.

The first complete strict Gradle run later catches a missing read action in that
test's `Location.virtualFile`/descriptor assertions. The follow-up test captures
path, offset and line under one read action and passes its 12-case SDK suite;
production sources are unchanged. The old subset digest is retained as historical
evidence, not relabeled as the repaired test. The common build record binds the
subsequent complete-suite result.

A same-source actual Go 1.24.3 / Delve 1.24.2 control runs four owned sessions:
standard passing/failing siblings, exact failed-child retry, a good-only advanced
manual profile, and a `TestMain` breakpoint followed by Stop. Confirmed target and
IDE codes are 1/1, 1/1 and 0/0; the cancelled session does not invent a target exit.
The breakpoint control uses public DAP breakpoint acknowledgement and stack trace,
not an observed native XDebugger UI interaction. All recorded owned processes
are checked separately after cleanup.

Fresh standalone native test-tree selection, toolbar retry, source navigation,
breakpoint/continue and Stop observations belong to the common native-workflows
desktop record. Controlled DAP success cannot substitute for those UI outcomes
or establish every GoLand test/debug workflow.

The [final build](native-workflows-build.json) and [fresh native observations](native-workflows.md)
bind the complete 395-input cohort. The [desktop record](native-workflows-native.json)
retains the actual workflow and cleanup boundaries.
