# Find Usages query policy and independent navigation

The earlier [native record](find-usages-native.json) retains real gopls errors
from `typeDefinition` and `implementation` requests issued while finding usages
of an ordinary function. That historical observation is unchanged. The public
LSP4IJ 0.21.0 usage support aggregates those navigation queries with declarations,
definitions and references. Its type classification is optional; implementation
queries are gated by server capability rather than the user's search intent.
The existing cancellation/error handler displays server errors before the usage
support maps unsuccessful results to an empty list.

The independent public-source patch adds two policies to `LSPUsageFeature`:
`shouldIncludeTypeDefinitions(PsiFile)` and
`shouldIncludeImplementations(PsiFile)`. Both default to `true`, retaining the
upstream behavior for other clients. The Go client overrides them to `false` for
Find Usages. Supported declaration, definition and reference queries remain;
`includeDeclaration: true` is unchanged. No server capability is disabled, and
no error message, error code or failed references response is suppressed.

The independent Go to Implementation, Go to Type Definition and type-hierarchy
supports use their own feature capabilities and request paths. They do not read
the new Find Usages policies. A declaration can still appear separately under
Definitions and in References; this change does not deduplicate those equal
ranges or classify Go reads/writes.

The [source pin](../../product/patches/lsp4ij-0.21.0-document-sync.json) identifies
the official public commit, original/patched source hashes and all compiled
classes. The two added sources are
[LSPUsageFeature](https://github.com/redhat-developer/lsp4ij/blob/b30f69dd1285f201d53ce360b7fad0bba96f0d58/src/main/java/com/redhat/devtools/lsp4ij/client/features/LSPUsageFeature.java)
and [LSPUsageSupport](https://github.com/redhat-developer/lsp4ij/blob/b30f69dd1285f201d53ce360b7fad0bba96f0d58/src/main/java/com/redhat/devtools/lsp4ij/usages/LSPUsageSupport.java).
The unified patch now covers six public sources and thirteen outer/nested
classes. The previous four patched sources and ten class digests are unchanged,
preserving the existing document-sync, deletion, scope and file-identity fixes.

## Automated and build evidence

Four real-SDK regressions exercise the actual accessor and feature supports with
controlled remote replies. On the previous class/client, the two Go-policy
assertions failed because both unwanted requests were sent; the two unaffected
default-client and independent-navigation cases passed. All four pass after the
change. The references-failure case observes the actual error notification with
its original `textDocument/references` feature and message.

A fifth test starts its own real stdio gopls. Function references return the
declaration and one call; interface references return its declaration, a
compile-time interface assignment check and a variable type. Neither Find Usages operation sends type-definition or
implementation requests. Separate explicit support calls then find `Value` as
an implementation of `Reader`, `Reader` as the type definition of `Current`, and
the actual `Reader` hierarchy item. This is a real protocol/SDK test, not desktop
action-routing evidence. The prior six scope/cache/file-identity regressions also
pass. The final packaged build passed all 378 Kotlin tests without failures,
errors or skips, including these eleven tests.

Main and test compilation use a private patched LSP4IJ dependency and the typed
Kotlin policy overrides. The original downloaded dependency and platform remain
read-only. The compile and delivered LSP4IJ JARs both have SHA-256
`9c7f4b2b3a8372c2130a0057f12071ec969a06dca7c6141bef6d306b0fab4bb1`;
all six-source/thirteen-class marker records match their actual class bytes.
Fixed ZIP entry metadata and sorted class/marker writing make reconstructed JAR
bytes stable. Cold build, verified warm reuse and rejection of an unrecorded
class under the new policy prefix were tested separately.

## Native cohort

The current desktop check uses PID 91007 and source digest
`d4b5eabb77d34c3c6f17f5e1162c21655e4bfd1c31e5c755a81ea1c2560576db`
(332 captured inputs). The [native record](find-usages-policy-native.json)
retains exact request/response records, original JSON-RPC ID types and raw trace
lines from the transparent relay to actual gopls v0.21.1. Its post-Quit snapshot
contains 307,503 bytes and 642 lines, SHA-256
`698a75d9cbf7493d6f23ff728697409d187dfa9eb15978565d97a6d31e16188d`.
The trace's initialize request identifies client PID 91007; its identity record
identifies the owned wrapper 91071 and gopls 91072. These three PIDs were absent
at extraction after the main task observed normal native Quit. This record does
not infer shutdown events or the state of unrecorded processes.

The first Alt+F7 operation at `Shared` in `one/shared.go` inherited **Current
File** from earlier native settings. With that file active, the UI showed
**Definitions (1)** and **References (2)**: the included declaration and its
same-file call. After changing Scope to **Project Files** and rerunning, it
showed **Definitions (1)** and **References (4)**, five displayed rows in total.
Both runs queried the same declaration at zero-based UTF-16 position `(2,6)`.

Actual references IDs are the JSON strings `"209"` and `"212"`, trace request/
response lines 579/581 and 585/587. Both requests set `includeDeclaration: true`
and both server responses contain the same complete list:

| Relative file | UTF-16 range, zero-based and end exclusive | Role |
| --- | --- | --- |
| `one/shared.go` | `(2,5)..(2,11)` | Declaration |
| `one/caller.go` | `(2,31)..(2,37)` | Call from `SecondFile` |
| `one/shared.go` | `(3,29)..(3,35)` | Call from `SameFile` |
| `two/caller.go` | `(4,30)..(4,36)` | Cross-module call |

The Current File result is client filtering of the complete server response;
scope is not an LSP references parameter. Four reference locations mean one
declaration plus three actual calls. The separate Definitions row is another
display of the declaration, not a fifth call.

The only usage-navigation requests in the two dispatch/reply groups (578–581
and 584–587) are definition plus references. Definition IDs are `"208"` and
`"211"`. No `typeDefinition` or `implementation` request was issued by either
ordinary-function search. An automatic `codeAction` request `"210"` between the
two groups is also retained in the wider interval; the evidence does not claim
that all editor traffic was Find Usages traffic. The native observer did not see
the previous function-related error notifications. An unrelated Shortcuts
conflicts notification was observed, so this is not a no-notifications claim.

## Explicit LSP actions and the standard-action gap

The main task separately selected the following **LSP-prefixed Find Action
entries**. Both actual server responses and native editor jumps succeeded:

| Explicit entry | Query | Actual target | Native editor result |
| --- | --- | --- | --- |
| LSP Type Definition(s) | `Current`, `one/reader.go` `(5,5)`; ID `"228"`, lines 627/628 | `Reader`, `(2,5)..(2,11)` | `one/reader.go` line 3, column 6 |
| LSP Implementation(s) | `Reader`, `one/reader.go` `(2,6)`; ID `"231"`, lines 633/634 | `Value`, `(3,5)..(3,10)` | `one/reader.go` line 4, column 6 |

The native initialize response advertises implementation, type-definition and
type-hierarchy capabilities. The successful explicit actions demonstrate that
the Find Usages policy has not globally disabled these navigation requests.
Native type-hierarchy UI was not exercised in this walkthrough.

The platform's standard **Go to Implementation(s)** and **Go to Type
Declaration** Find Action entries did not issue a query or change the editor
location in this cohort. They remain a concrete native entry/routing gap. The
observation does not establish why the standard target/action path failed, and
the successful LSP-prefixed entries must not be relabeled as standard-action or
shortcut passes. No standard-action fix is included in this evidence cohort.

## Boundaries

This is a Go Find Usages query-policy fix, not completion of LANG-03 or full
GoLand parity. Native cached searches, arbitrary local scopes, all interface
relationships, unsaved/racing searches, every build/SDK/OS context and all
navigation/hierarchy routes remain separate acceptance requirements. In
particular, a controlled support test must not be relabeled as proof of the
platform's standard Go to Implementation shortcut or menu action. References
error reporting is covered by the automated test; a deliberately failing native
references request has not been injected into the production session.

## Later standard-action repair

The standard-action gap above is an observation of the historical `d4b5eabb`
cohort. A later [navigation repair](standard-navigation.md) connects the actual
standard actions and records native choosers, type/declaration distinctions and
semantic cache invalidation. Its separate [native record](standard-navigation-native.json)
preserves the intermediate cache failure and the repaired package evidence; it
does not change this older query-policy cohort or close full LANG-03.
