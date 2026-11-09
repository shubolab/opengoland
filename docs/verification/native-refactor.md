# Standard native Go extraction and inline

Standard Introduce Variable, Introduce Constant, Extract Method/Function and
Inline actions route to a shared native Go refactoring service. Editor menus,
Refactor This and the custom Go Refactor chooser use the same planning/apply
path. This is partial implementation; move declarations, introduce type and
complete extract/inline equivalence remain open.

## Candidate, naming, validation and preview

Caret invocation first resolves a typed target; selected expressions/statements
can go directly to applicable variants and occurrence choices. Names are chosen
in a native modal dialog before final validation, followed by the shared native
workspace-edit preview. This does not claim the reference product's complete
in-place naming experience.

Pinned gopls actions are resolved with `gopls.apply_fix` and `ResolveEdits:true`.
Inspection does not execute an edit against the project. The engine captures an
immutable typed workspace snapshot, checks the candidate against source identity,
normalizes the chosen generated names and type-checks the complete resulting
candidate. Binding-aware normalization changes only the introduced declaration
and generated references. It preserves a pre-existing `newMethod` rather than
blindly renaming same-text occurrences.

The shared workspace-edit service presents the final preview and applies one
guarded global undo command. Source/disk hashes, LSP versions, captured settings,
coverage membership and native document event sequences prevent stale application.
Edit followed by Undo cannot revive an old capture just by restoring its stamp.
Cancellation, project disposal and preview cancellation must leave source intact.

## Semantics beyond successful compilation

Actual gopls controls demonstrate two candidates that compile but change runtime
behavior: moving a selected `defer` into the helper executes it before the rest
of the original function, and moving a direct `recover` into the helper prevents
recovery. The planner rejects those unsafe direct cases before preview. An intact
nested function's own defer is analyzed separately. These are selected effect
guards, not a general proof of behavior preservation.

Exact-use variable inline validates binding identity, assignment/effect safety,
parentheses and original type conversion. Declaration-driven all-use plans use
typed workspace references. Local variables require safe replacement and explicit
declaration removal. Statically resolved same-package non-generic functions and
methods use immediately invoked function literals to retain one call frame,
argument evaluation, named results and the copied body's defer/recover context.
Cross-file imports and free names must keep their typed identities. Receiver
adaptation must preserve Go's evaluation and panic timing rather than merely
produce compilable source.

Function inline offers keep/remove where proved valid; function-value references
force keep. Recursive or nested calls to the same target, generic/cross-package
all-use expansion, promoted receiver paths, dynamic calls and unsafe receiver or
lexical capture cases report conflicts. They are not silently partially rewritten.
An IIFE-based expansion is an independent implementation strategy; it does not
establish identical output formatting, stack presentation or all reference UX.

## Evidence and remaining scope

The [reference/control record](../reference/native-workflows-reference.json)
distinguishes static installed labels, official versioned documentation, actual
gopls candidates and disposable Go runtime controls. It also retains the prior
standalone package's missing native menu entries. A first changed-range probe is
not causal evidence that omitted trigger kinds or literal capability leaves were
the sole problem: identical-range Unknown and Invoked probes returned the tested
actions.

Final engine, actual SDK, global Undo/Redo and fresh standalone observations must
be read with the common native-workflows build/desktop cohort. Neither a typed
plan nor passing isolated tests proves all declaration refactoring semantics,
control-flow cases, cross-module/build contexts, cancellation interleavings or
complete GoLand parity. The full acceptance row remains unverified.

The [final build](native-workflows-build.json) and [fresh native observations](native-workflows.md)
bind the complete 395-input cohort. The [desktop record](native-workflows-native.json)
retains the actual workflow and cleanup boundaries.
