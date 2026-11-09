# Standard Go navigation and Settings search

This is the historical **338-input 7284c945…** cohort. The later
[hierarchy and Structure repair](hierarchy-structure.md) synchronizes the four
navigation parameter gates and adds deterministic four-case RED/GREEN SDK
regressions. It supersedes the specific untested concurrent-offset boundary below;
the original cohort's measurements and remaining semantic/lifecycle limits stay
attributed to their original bytes.

The repaired package passes **404 Kotlin/platform tests across 91 reports**, with
zero failures, errors or skips. Its **338 captured inputs** have digest
`7284c94586560bcedf413621a21b7d3e4da9b4cb543a7312827288a3c0ae92e1`.
The [build record](standard-navigation-build.json) binds the final artifacts,
reports and terminal successful package run. All 32 manifest artifacts match;
the delivered LSP4IJ marker's 21 class hashes match the final public-source pin.
Build-input verification and deep strict local signature verification pass.

The fresh native cohort, **PID 10650**, completed the listed navigation, semantic-
cache, SDK, Go-test and Settings checks and normal Quit. Independent replay of
the closed wire trace confirms the dependency-triggered queries and returned
targets. [The native record](standard-navigation-native.json) keeps this cohort
separate from earlier observations; listed passes do not close all navigation
or complete IDE acceptance.

The initial `8e3905032b1cea34c09f879dc28e634b4ecf5be61dadf5f1e46e41cba2ac63db`
package (337 inputs) passed 391 Kotlin/platform tests across 90 reports, with
zero failures, errors or skips. Its native PID 6081 proved standard action
routing, chooser/history and first dirty-document navigation, then exposed a
**target-only navigation cache failure**. That cohort is closed and archived;
its positive and failed checks are retained separately below. All 36 full
acceptance requirements remain open.

## Why standard navigation previously stopped

Our Go PSI is a lexical tree. An identifier leaf does not automatically provide
a locally resolved Go declaration/reference. LSP4IJ's generic target evaluator is
registered for the empty language ID (`Language.ANY`), but this platform lookup
uses `forLanguage`: it visits the actual language and its base languages, rather
than automatically including ANY. `OpenIDEAGo` has no base language. The generic
registration therefore does not supply the missing Go query origin.
[LanguageExtension.java:108–116 and 177–183](https://github.com/JetBrains/intellij-community/blob/456919a9624bae72ac12efafc35d2b916cb0c5c5/platform/core-api/src/com/intellij/lang/LanguageExtension.java#L108-L183),
[LSP4IJ registrations](https://github.com/redhat-developer/lsp4ij/blob/b30f69dd1285f201d53ce360b7fad0bba96f0d58/src/main/resources/META-INF/plugin.xml#L526-L579).

`GoTargetElementEvaluator` supplies an identifier-backed query origin through
`adjustReferenceOrReferencedElement`, registered specifically for `OpenIDEAGo`.
It preserves an existing resolved element, accepts only an actual identifier
other than `_`, and uses the cached native document and a captured UTF-16 range.
The committed document can remain unsaved. A changed document, invalid file or
disposed project invalidates the origin. Target discovery performs no filesystem
access, process launch, RPC or forced PSI commit.

This origin allows the existing platform/LSP providers to do the semantic work;
it does not manufacture a resolved Go declaration. There is no replacement of
standard action IDs, keyboard shortcuts or chooser UI.
[TargetElementUtilBase.java:212–249](https://github.com/JetBrains/intellij-community/blob/456919a9624bae72ac12efafc35d2b916cb0c5c5/platform/analysis-impl/src/com/intellij/codeInsight/TargetElementUtilBase.java#L212-L249).

## Actual platform routes and chooser ownership

| Standard action | Route retained |
| --- | --- |
| Go To Implementation | `GotoImplementationHandler` → target evaluator → `ImplementationSearcher` → `DefinitionsScopedSearch` → existing LSP implementation provider. |
| Type Declaration | `GotoTypeDeclarationHandler2` → the current symbol/evaluator bridge → the place-aware type provider. |
| Go To Declaration / Cmd+B | Existing Declaration providers run before the evaluator fallback; a successful LSP Definition result retains priority. |

The current Type Declaration action does **not** use the old public
`GotoTypeDeclarationAction.findSymbolTypes` helper as its primary handler.
The new symbol bridge calls the evaluator and carries its origin through an
`EvaluatorReference`. Testing the old helper alone could leave the actual menu
action broken. The tests use the current `GttdKt.gotoTypeDeclaration(...).result()`
delegate and include a controlled invocation of the actual handler.
[GotoTypeDeclarationAction.java:33–36](https://github.com/JetBrains/intellij-community/blob/456919a9624bae72ac12efafc35d2b916cb0c5c5/platform/lang-impl/src/com/intellij/codeInsight/navigation/actions/GotoTypeDeclarationAction.java#L33-L36),
[targets.kt:117–185](https://github.com/JetBrains/intellij-community/blob/456919a9624bae72ac12efafc35d2b916cb0c5c5/platform/lang-impl/src/com/intellij/model/psi/impl/targets.kt#L117-L185),
[gttd.kt:96–136](https://github.com/JetBrains/intellij-community/blob/456919a9624bae72ac12efafc35d2b916cb0c5c5/platform/lang-impl/src/com/intellij/codeInsight/navigation/impl/gttd.kt#L96-L136).

The SDK retains single-target navigation, multi-target presentations and chooser
selection. Type Declaration uses its native target popup; implementation
navigation retains the standard background updater and popup cancellation.
The new evaluator does not select the first result itself.
[GotoTypeDeclarationHandler2.kt:28–63](https://github.com/JetBrains/intellij-community/blob/456919a9624bae72ac12efafc35d2b916cb0c5c5/platform/lang-impl/src/com/intellij/codeInsight/navigation/actions/GotoTypeDeclarationHandler2.kt#L28-L63),
[GotoTargetHandler.java:140–203](https://github.com/JetBrains/intellij-community/blob/456919a9624bae72ac12efafc35d2b916cb0c5c5/platform/lang-impl/src/com/intellij/codeInsight/navigation/GotoTargetHandler.java#L140-L203).

Declaration-provider priority is preserved in the actual current handler, rather
than inferred only from a legacy helper.
[GotoDeclarationOrUsageHandler2.kt:39–42](https://github.com/JetBrains/intellij-community/blob/456919a9624bae72ac12efafc35d2b916cb0c5c5/platform/lang-impl/src/com/intellij/codeInsight/navigation/actions/GotoDeclarationOrUsageHandler2.kt#L39-L42).
The real-gopls regression's Cmd+B comparison still invokes the old public
`findAllTargetElements` helper: that part proves Definition provider routing,
not execution of the actual registered Cmd+B action.

## Navigation caches and semantic dependency changes

The initial evaluator repaired action routing but did not repair the underlying
location cache. LSP4IJ's previous cache checked the query file's modification
stamp and query offset. A cached raw `LocationData` list could therefore survive
a change to another document. Reinterpreting its old line/column against the new
target text produced a wrong destination; merely following an old text marker
would also miss new implementations in previously unmatched files.
[AbstractLSPDocumentFeatureSupport](https://github.com/redhat-developer/lsp4ij/blob/b30f69dd1285f201d53ce360b7fad0bba96f0d58/src/main/java/com/redhat/devtools/lsp4ij/features/AbstractLSPDocumentFeatureSupport.java),
[LSPTypeDefinitionSupport](https://github.com/redhat-developer/lsp4ij/blob/b30f69dd1285f201d53ce360b7fad0bba96f0d58/src/main/java/com/redhat/devtools/lsp4ij/features/typeDefinition/LSPTypeDefinitionSupport.java).

The independent public-source repair opts only Declaration, Definition,
Type Definition and Implementation supports into semantic dependency epochs.
Other document-feature caches retain their previous policy. Unchanged epochs
reuse the same future; document, PSI or matched watched-dependency changes make
the next query load fresh data. Editor document events are counted conservatively,
including unrelated open projects, so this can cause extra cache misses without
claiming a complete static dependency graph.

Each generation captures immutable document/PSI/watched epochs at its start.
Matching server/configuration tracker references and values are captured after
initialization/connect and before the actual RPC. This allows a legitimate cold
first response while rejecting a held cold response after a matched dependency
or configuration change. There is no blanket startup exemption or completion-time
rebaseline. A stale generation cannot be published using a replacement generation's
key. Future/key publication and cancellation use the same monitor; event callbacks
increment counters, and validity checks read captured counters without target
filesystem access or PSI lookup. The upstream per-support offset checks remain
outside this monitor; their concurrent-query boundary is recorded below.

Only VFS events already matching that server's watcher patterns advance the
watched dependency epoch. Unmatched relay trace/log files do not invalidate
navigation. The [source pin's `navigationCacheRevision`](../../product/patches/lsp4ij-0.21.0-document-sync.json)
records eleven replayed public sources, twenty-one compiled outer/nested classes
and their exact hashes. Its final patch SHA-256 is
`385d306dda4fe8f7a93f87f4ae34cd57b29cd1a5461c90510e6f289bd03b3dde`.
The twelve prior document-sync/usage classes remain
byte-identical; the existing file listener intentionally changes to record matched
watcher epochs. These implementation records are separate from final package and
native verification.

## Settings search

The Go pages now implement `SearchableConfigurable` with the same stable IDs used
by the product descriptor. A runtime `GoSettingsSearchContributor` indexes the
actual Go and Go Inspections labels, including **Indent on Enter in raw strings**,
CGO, gopls and inspection flags. It supplies static strings without constructing
the settings forms or discovering executables. This addresses content-based
search, rather than relying only on a matching page title.

The public API supports runtime contributions; the registrar loads them and
filters results by configurable ID. A null `enableSearch` action is allowed and
does not replace the index.
[SearchableConfigurable.java:17–25](https://github.com/JetBrains/intellij-community/blob/456919a9624bae72ac12efafc35d2b916cb0c5c5/platform/ide-core/src/com/intellij/openapi/options/SearchableConfigurable.java#L17-L25).
Packaging skips prebuilt searchable-options artifact tasks; the registered
runtime contributor is the relevant integration.
[SearchableOptionContributor.kt:4–16](https://github.com/JetBrains/intellij-community/blob/456919a9624bae72ac12efafc35d2b916cb0c5c5/platform/platform-api/src/com/intellij/ide/ui/search/SearchableOptionContributor.kt#L4-L16),
[SearchableOptionProcessor.java:13–34](https://github.com/JetBrains/intellij-community/blob/456919a9624bae72ac12efafc35d2b916cb0c5c5/platform/platform-api/src/com/intellij/ide/ui/search/SearchableOptionProcessor.java#L13-L34),
[SearchableOptionsRegistrarImpl.kt:115–138](https://github.com/JetBrains/intellij-community/blob/456919a9624bae72ac12efafc35d2b916cb0c5c5/platform/platform-impl/src/com/intellij/ide/ui/search/SearchableOptionsRegistrarImpl.kt#L115-L138).

An earlier independent, controlled index run failed against the old compiled
pages/descriptor because the exact raw-string label had no content hit
(1 expected failure, 6.081 seconds). The updated isolated registration passed
(1 test, 4.982 seconds). That RED/GREEN run is private intermediate evidence in
`.tools/settings-search-evidence.json`; its hashes and summary are retained in
the build record. The initial package also includes the real registered registrar
regression using `initializeBlocking()` and `getConfigurables()`, with positive
Go/inspection queries and a negative unknown query.

A separate native check of the same immutable initial bundle, PID 6914, found
Go with `CGO_ENABLED`, filtered and highlighted the actual raw-string checkbox
with its full label, and found Go Inspections with highlighted `golangci-lint
flags`. Apply remained disabled: no setting was applied, Escape closed Settings,
and native Quit completed. Both recorded owned PIDs were absent afterward.
A cached automation binding timed out after Escape; rebinding found a responsive
window, and the EDT dump was waiting for events. This tool-binding retry is
retained without describing a product freeze. These completed observations
belong to the initial bundle and are not relabeled as repaired-package reruns.

The repaired PID 10650 cohort separately searched the exact raw-string label,
selected Go and visibly spotlighted the checkbox. Apply stayed disabled and
Escape cancelled Settings without a preference change. CGO and inspection-flag
searches were not repeated in this cohort. An early batched shortcut reached an
editor find bar before Settings was ready; the actual Settings field was then
selected. A transient `noWindowsAvailable` automation response recovered after
rebinding. No inspection checkbox was clicked; these retries remain explicit.

## Automated verification

| Verification stage | Result and cohort boundary |
| --- | --- |
| Repaired complete package | **404 tests / 91 reports; 0 failures/errors/skips.** Package, plugin structure and 338-input verification pass. |
| Final controlled standard navigation | 9 tests, included in 404. |
| Final cache-policy controlled subset | 11 tests, included in 404; independent final-pin run also passed in 4.147s. |
| Final actual stdio gopls navigation | 5 tests, included in 404; actual final-package class run 8.268s. |
| Registered Settings index | 1 test, included in 404. |
| Retained engine race/vet | Unchanged Go inputs: 271 pass events including subtests, 155 top-level tests, nine packages, one cached and eight fresh. Vet exit 0, empty output. |
| Initial package | Historical 391 tests / 90 reports, zero failures/errors/skips; separate archived bytes. |
| Actual-gopls cache RED | Archived initial LSP4IJ JAR: five tests, exactly the two new cache cases fail; original three pass. |
| Repaired native cohort | PID 10650: listed native checks pass; closed wire trace independently replayed, normal Quit/recorded-process cleanup complete. |

The 9 + 11 + 5 + 1 subsets are already included in **404**; neither standalone
runs nor initial-cohort results are added to that total. Go event counts are a
separate measure. All 103 captured engine inputs are byte-identical between
initial and repaired manifests. The final plugin JAR SHA-256 is
`da1b37f9a0ffcaef350d2e8bfb05aa3e7da611487046691d90e23060c803e67f`;
its LSP4IJ JAR SHA-256 is
`ff1682a7ded9ebc3fd073144376f74989622874fe6e562ed5dbc246486207829`.
Detailed report/artifact/log hashes belong in the build record.

The initial package's XML and JARs remain archived under
`.tools/standard-navigation-initial-cohort/`. Independent cache RED/GREEN
snapshots are retained in `.tools/standard-navigation-gopls/cache-red-evidence.json`
and `cache-green-evidence.json`. Their earlier warm-cache class overlay is
intermediate evidence; the terminal 404-test run verifies the final cold-start-
audited pin in the delivered package.

The actual-gopls tests resolve interface/type and method implementations, concrete
generic-alias targets, UTF-16 positions after Chinese/emoji text, a standard-library
type, and committed-but-unsaved source changes. The two added cases require real
fresh RPCs and semantic targets: a target-only edit must move the destination;
a third-file signature change must add an implementation while the origin and
previous targets stay unchanged. Disk contents remain unchanged. This prevents
a text-marker-only repair from passing. Owned stdio processes and their observed
descendants are terminated and independently checked absent.

The full implementation collector is exercised separately from the standard
handler's initial one-result search; these tests do not claim a native background
popup completed. The cache-policy tests additionally cover stable-future reuse
across all four supports, source-independent edits, configuration epochs, stale
inflight/replacement generations, legitimate cold first replies, held cold
watch/config changes, matched versus unmatched watcher events, and unchanged
non-navigation cache policy.

The other controlled tests cover identifier-only origins, invalidation after
edits, SingleTarget/MultipleTargets data, held replies, original query positions
after caret movement, progress cancellation and a queued write. The real handler's
released-editor case asserts no exception, editor disposal and one RPC. It does
**not** assert that every late navigation is suppressed. No held project-disposal
case is included.

## Initial native observations and retained failure

All observations in this section belong to initial digest **8e3905…**, PID 6081.
They were supplied by the root task operating the packaged application; this
writer performed no UI operation. Displayed positions are one-based line:column,
not LSP coordinates.

| Standard workflow | Initial-cohort observation |
| --- | --- |
| Cmd+Alt+B on `Source` | Six-item chooser; selecting Remote crossed go.work modules to `client/use.go` 11:6. Back returned to `contracts.go` 4:7; Forward returned to 11:6. |
| Find Action → Go To Implementation on `Read` | Three-item chooser; selecting Pointer.Read opened `pointer.go` 6:17. |
| Cmd+Alt+B on `IntGetter` | Three-item chooser; Cancel retained 7:7. Reopening and selecting IntBox opened `generic.go` 7:6. |
| Cmd+Shift+B on same-line Unicode `current` | Opened Source at `contracts.go` 4:6; Back restored `use.go` 15:39. Identifier-start invocation after two spaces also worked. |
| Actual Cmd+B on the receiver | Opened the local `current` declaration, distinct from Type Declaration's interface target. |
| Cmd+Shift+B on generic `getter` | Opened IntGetter at `contracts.go` 7:6. |
| Standard SDK navigation | Type Declaration on `std` opened io.Reader 86:6; actual Cmd+B on NewReader opened strings/reader.go 160:6 in selected Go 1.24.3. Read-only enforcement was not tested. |
| First navigation with two unsaved files | Source used `*impl.Pointer`; target had a leading Unicode comment. Pointer opened correctly at 6:6, while before/after disk hashes for both files remained initial. |
| Repeated query after only the target changed | **FAIL:** adding another target comment moved Pointer to 7:6, but the same source/caret Type Declaration opened import text at 5:34. No fresh typeDefinition RPC was sent. |

The last real dirty query, string ID `"115"`, used source version 4 and target
version 2. The destination then changed to version 3 while the repeated action
reused the old response. Independent actual-SDK/gopls RED reproduced the same
source-only cache lifetime: unchanged future/list identity, stale coordinates,
and no new request. The first correct dirty jump must not conceal this failure.

The root restored the fixture through native edits before Quit. All eleven disk
hashes matched the initial files and all five recorded owned PIDs were absent.
This proves cleanup, not successful repeated navigation or a complete Undo flow.
A transient extra Return after a chooser click inserted an unwanted newline;
native Undo restored it. This automation input mistake remains in the trace.
The initial trace, metadata, literal observations and dirty snapshots are archived
with that cohort; the [native record](standard-navigation-native.json) retains
its boundary alongside the new repaired-cohort evidence.

## Repaired native verification

The root completed the fresh **7284c945…**, PID **10650** walkthrough. These are new
observations in the repaired bytes, not transfers from PID 6081. Positions are
one-based native UI positions; request IDs are not display coordinates.

| Fresh standard workflow | Observed result |
| --- | --- |
| Cmd+Alt+B on Source | Initial six-item implementation chooser. |
| Only a previously unmatched third file changes | With the query and old destinations unchanged, unsaved `NewImplementation.Read` changed from int to string. The same 4:7 query produced seven candidates; selecting NewImplementation opened its declaration at 3:6. After actual text Undo restored int, the same query returned six candidates. All twelve fixture disk hashes remained initial in before/after snapshots. |
| Unicode same-line Type Declaration and Cmd+B | Cmd+Shift+B on current opened Source at 4:6; actual Cmd+B at the return receiver opened the local declaration at 15:36. |
| Two unsaved files, then only target changes again | After changing current to Pointer and adding a destination prefix, the first jump opened Pointer at 6:6. Adding only another target prefix, with source version/caret unchanged at 15:37, caused fresh request 83 and opened the actual Pointer at 7:6, confirmed through accessibility and screenshot. This directly repeats the initial failed case in repaired bytes. |
| Undo/Redo the target-only edit and repeat | The same query returned 6:6 after text Undo and 7:6 after text Redo, with fresh requests 103 and 117. Each text transition required a preceding caret-only Undo/Redo step; this is not a one-command Undo claim. |
| Undo/Redo the source type edit | The same query returned Source at 4:6 and then Pointer at 7:6, with requests 123 and 132. Caret-only steps preceded the actual text transitions. |
| Restore the original texts, then SDK Type Declaration | Request 146 opened selected Go 1.24.3 io.Reader at 86:6; Back returned to the source. SDK read-only enforcement was not exercised. |
| Native Ctrl+Shift+R at TestNavigationFixture | One test passed through Go 1.24.3 `go test -json -run ^TestNavigationFixture$ .`: package time 0.669s, no cached marker, exit 0. |
| Settings exact-label search | The actual raw-string checkbox was spotlighted on Go; Apply stayed disabled, Settings was cancelled without applying a preference. |

The third-file text Undo similarly followed a caret-only step. Only actual text
changes and their matching semantic results count toward cache verification.
The new candidate, fresh repeated request and corrected live location distinguish
this repair from moving an old marker or merely reopening a file.

After native Cmd+Q/Confirm Exit, CUA observed the app quit. All six recorded
owned application/engine/LSP/test PIDs were absent, and all twelve fixture disk
hashes matched their initial values. The retained session log has no
ERROR/SEVERE entries; it is not a warning-free claim. Detailed automation retries,
disk checkpoints and UI observations belong in the native record.

Independent replay verified the closed trace's **452 records / 332,644 bytes**,
twelve navigation RPCs and all twenty-six raw initialization/query request-response
records against the trace. Seven prefix/disk checkpoints matched their recorded
hashes and versions. Implementation requests **13 / 24 / 41** use the same query
source version, hash and position and return **6 / 7 / 6** candidates; only the
middle reply adds NewImplementation. Target-only requests **71 / 83 / 103 / 117**
keep source version 2, source hash and UTF-16 position **(14, 37)** unchanged.
Target versions **2 / 3 / 4 / 5** yield Pointer start lines **5 / 6 / 5 / 6**.
These protocol positions are zero-based and separate from the native UI labels
above. Every listed transition has a fresh RPC; requests **123 / 132 / 146**
independently confirm the source Undo, source Redo and SDK results. All twenty-two
tracked document events replay with valid incremental UTF-16 ranges and versions.

The trace contains **61 diagnostic events**, including 32 nonempty reports:
35 reports have versions and 26 do not. Its diagnostic items include **34 warnings
(severity 2) and two hints (severity 4)**, with no severity-1 item observed. This
is not a diagnostic-free result, and receipt order does not bind an unversioned
report to a source version. Only five `didClose` messages were observed; process
exit does not establish that every document received a close or a source ACK.
The independent audit also rechecked all six recorded PIDs as absent and all
twelve final disk hashes as initial. Trace, summary and audit hashes are retained
in the native record. The single native Go-test outcome remains a root UI
observation, distinct from the independent read-only protocol audit.

## Precise remaining boundaries

- Empty implementation results may fall back to the lexical source through the
  platform's `includeSelfIfNoOthers` behavior, even with unconditional self-inclusion
  disabled. A nonempty target array is not by itself evidence of a found implementation.
  [ImplementationSearcher.java:35–52](https://github.com/JetBrains/intellij-community/blob/456919a9624bae72ac12efafc35d2b916cb0c5c5/platform/lang-impl/src/com/intellij/codeInsight/navigation/ImplementationSearcher.java#L35-L52).
- The held-write test proves read/write serialization. The platform's modal resolver
  holds a read action around the computation, so it does not prove a simultaneous
  document mutation during a held RPC.
  [ActionUtil.kt:351–365](https://github.com/JetBrains/intellij-community/blob/456919a9624bae72ac12efafc35d2b916cb0c5c5/platform/platform-api/src/com/intellij/openapi/actionSystem/ex/ActionUtil.kt#L351-L365).
- Progress cancellation stops waiting with `ProcessCanceledException`, but neither
  the inspected wait nor these tests proves wire `$/cancelRequest` forwarding.
  [ProgressIndicatorUtils.java:377–421](https://github.com/JetBrains/intellij-community/blob/456919a9624bae72ac12efafc35d2b916cb0c5c5/platform/ide-core-impl/src/com/intellij/openapi/progress/util/ProgressIndicatorUtils.java#L377-L421).
- The native lazy navigation helper has no explicit editor-disposed/caret recheck;
  the released-editor regression is limited to its stated no-exception result.
  [ide.kt:47–65](https://github.com/JetBrains/intellij-community/blob/456919a9624bae72ac12efafc35d2b916cb0c5c5/platform/lang-impl/src/com/intellij/codeInsight/navigation/actions/ide.kt#L47-L65).
- The upstream implementation executor does not apply arbitrary supplied SearchScope
  filtering to each returned location. The separate Find Usages scope patch cannot
  be extrapolated to implementation navigation.
  [LSPWorkspaceImplementationsSearch.java:59–120](https://github.com/redhat-developer/lsp4ij/blob/b30f69dd1285f201d53ce360b7fad0bba96f0d58/src/main/java/com/redhat/devtools/lsp4ij/features/workspaceSymbol/LSPWorkspaceImplementationsSearch.java#L59-L120).
- The cache epoch is conservative and can include unrelated editor buffers. It
  is not a complete semantic dependency graph or acknowledgement that the server
  has consumed every source. Unobserved external changes and all SDK/build/OS or
  disposal interleavings remain outside the listed tests.
- The four upstream navigation supports still check and update `previousOffset`
  outside the base future monitor. Static inspection identifies a possible
  interleaving where concurrent different-offset calls on the same PsiFile reuse
  the other query's future. This predates the epoch patch; no native reproduction,
  dedicated concurrent-query regression or fix is claimed. The tested modal
  workflows do not establish safety for all such cache races.
- Hierarchy and Structure use distinct LSP providers. Their registration or server
  capability does not prove native trees, expansion, selection or lifecycle; a
  repaired goto action does not close those acceptance items.
- Generic-origin queries may return matches under some instantiation, including
  StringBox and SDK types. Their set differs from the concrete IntGetter alias;
  candidate expectations must follow the exact query and actual server reply.
- The completed protocol audit and native passes cover the listed workflows.
  Flat Go PSI, Complete
  Statement, full settings/navigation/runtime parity and all 36 acceptance
  requirements remain open.

Public platform references are pinned to `456919a9624bae72ac12efafc35d2b916cb0c5c5`;
LSP4IJ is pinned to version `0.21.0`, commit `b30f69dd1285f201d53ce360b7fad0bba96f0d58`. All cited
line numbers are raw-file lines including blank lines. No proprietary implementation
code was used for this independent integration.
