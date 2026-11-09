# Find Usages scope and native navigation

The pinned public LSP4IJ 0.21.0 usage searcher had two correctness defects: fresh and cached references did not apply the requested search scope, and containment filtering compared numerical ranges without requiring the same file. A narrower range in another file could remove a valid usage. Six regressions using real platform PSI/VFS, the actual accessor/searcher and native global/local scopes failed on the original class and passed after the independent public-source fix. Only remote reference locations are supplied by the controlled test server. The main task subsequently completed the full 333-test Kotlin suite without failures.

Both search paths now share scope filtering. Global scopes use the platform's PSI scope utility; local scopes use the actual file and range because LSP usage nodes are synthetic rather than children of the selected PSI node. Only strict containment within the same VirtualFile removes a larger range. Equal and partially overlapping ranges are retained. The regressions exercise fresh/cached paths, empty/global/local scopes, cross-file containment and order permutations.

The existing pinned patch contains four public source files and ten compiled outer/nested classes. The earlier three document-sync/deletion source files and eight class digests are unchanged. The build verifies the exact complete patched class set on cold build and warm reuse, including nested classes, and rejects an unrecorded class under a patched prefix. The patch pin records the source and class hashes plus the independent regression evidence.

The independent native application then exercised Find Usages in a two-module Go workspace. From the declaration of `Shared` in `one/shared.go`, Alt+F7 with **Project Files** selected displayed **Definitions (1)** and **References (4)**. The actual `textDocument/references` request ID was the JSON string `"88"` (trace lines 206/210). It queried `one/shared.go` at LSP position `(4,6)` and explicitly set `includeDeclaration: true`.

The real server returned the following four locations, retained without filtering in [the native wire record](find-usages-native.json):

| Relative file | LSP UTF-16 range, zero-based and end exclusive | Role in this fixture |
| --- | --- | --- |
| `one/shared.go` | `(4,5)..(4,11)` | Declaration of `Shared` |
| `one/caller.go` | `(2,31)..(2,37)` | Call from `SecondFile` |
| `one/shared.go` | `(6,29)..(6,35)` | Call from `SameFile` |
| `two/source.go` | `(5,30)..(5,36)` | Cross-module call from `Cross` |

Thus four reference locations mean one included declaration and three actual call sites. The separate Definitions category is another aggregated feature result; the UI total must not be described as five distinct calls. End/Return on the final result opened `two/source.go` at the observed editor position line 6, column 31, matching the zero-based start `(5,30)`.

With `two/source.go` active, the native Find Usages settings were changed to **Current File** and the existing search was rerun. The UI displayed **References (1)**, containing only the call in `two/source.go` at line 6. The second actual references request, JSON string `"109"` (252/256), still queried the original declaration in `one/shared.go` at `(4,6)` with `includeDeclaration: true`. Its server response was the same complete four-location list. The search scope is an IntelliJ client option, not a field of the LSP references request: the observed one-row Current File result demonstrates the native client filtering for this fixture. “Current File” here means the active `two/source.go`, not the declaration file.

The extraction preserves both raw request/response records, their original JSON-RPC ID types and raw trace lines, all returned locations, related aggregate-query responses, and disk excerpts/hashes. Its read snapshot was 152,297 bytes, SHA-256 `ac2e2b3dee383e4d499c84df472e213356bbfdda1fee1332630892caf0b429e4`. This hash identifies that snapshot/prefix rather than any later appended messages or process-shutdown evidence. The [build record](module-usages-build.json) binds these actions to the 323-file `f6bf8d2b…` native cohort, PID 80970. The final `766e9401…` bundle changes only Go toolbar/tree classes; the usage classes are byte-identical. These actions retain their original execution cohort.

An aggregation/notification experience issue remains open. For this ordinary function, the generic usage aggregation also sent `textDocument/typeDefinition` (IDs `"87"`, `"108"`) and `textDocument/implementation` (IDs `"89"`, `"110"`). gopls returned the actual errors `cannot find type name from type func(value int) int` and `Shared is a function, not a method (query at 'func' token to find matching signatures)`. Successful reference results and correct scope filtering do not resolve these unnecessary-query/error-notification interactions. They are retained as evidence rather than suppressed or treated as missing references.

Both selected native runs emitted fresh references requests. The native cached-results path was not verified; its evidence remains the controlled real-SDK regressions. This native fixture also does not establish arbitrary local scopes, all interface relations, SDK/dependency navigation, type/call hierarchy, all build contexts, unsaved/racing reference refresh, commercial GoLand behavior or full IDE parity.
