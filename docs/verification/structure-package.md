# Go Structure grouping, package view and native controls

This batch adds Go receiver grouping, package-wide symbols, private-member
visibility and exportability sorting to the native Structure tool window and
File Structure popup. The repaired complete package run passes **472
Kotlin/platform tests across 98 reports**, with zero failures, errors or skips.
Its **351 frozen inputs** have digest
`920334cbb15377ac2d838481da768b9a8be97631263737cd4cb621e813864c23`.
All 32 manifest artifacts match, input verification passes, and deep strict local
signature verification exits 0. Fresh native PID **26222** completes the bounded
workflows below and normal Quit; independent replay of its closed trace and final
identity/cleanup checks pass within their stated scope. The
[build record](structure-package-build.json) and
[native record](structure-package-native.json) retain those boundaries.
The initial 350-input package passed 465 tests but its native run confirmed a
**File Structure initial-selection failure**. Its positives and that failure are
retained separately; they are not transferred to the repair. All 36 complete
acceptance requirements remain open.

## Reference and backend boundaries

The [reference record](../reference/structure-package-reference.json) separates
REA-authenticated installed GoLand resources, official 2026.1 documentation and
screenshots, pinned open-source Platform contracts, and owned actual gopls stdio
controls. The selected commercial resource literals include **Show Package
Structure**, **Show Private Members** and **By Exportability**. They establish
labels, not implementation, active states or first-launch defaults. No proprietary
implementation class was read or copied, and the installed reference was not
modified.

Official images show receiver methods beneath types and a flat package symbol
list with filename provenance. They do not establish every pointer, generic,
alias, malformed-source or test/build-context grouping policy. The cross-language
help's Show Fields and Group Methods by Defining Type entries are under the
JavaScript/TypeScript/CoffeeScript section; they are not evidence of Go controls.
[File Structure](https://www.jetbrains.com/help/go/2026.1/viewing-structure-of-a-source-file.html),
[Structure controls](https://www.jetbrains.com/help/go/2026.1/structure-tool-window-file-structure-popup.html),
[popup image](https://resources.jetbrains.com/help/img/idea/2026.1/go_file_structure_popup.png),
[tool-window image](https://resources.jetbrains.com/help/img/idea/2026.1/go_structure_popup_window.png).

The pinned public gopls v0.21.1 command is:

```json
{"method":"workspace/executeCommand","params":{"command":"gopls.package_symbols","arguments":[{"URI":"file:///package/model.go"}]}}
```

Its result contains `PackageName`, `Files` and recursive `Symbols`. Every symbol's
`file` index is independent; an omitted index means **zero**, including a child
whose parent is in another file. The graph follows the narrowest selected
metadata context and its `CompiledGoFiles`. It is syntax-based receiver grouping,
not a type-checked receiver/alias graph. Its top-level server ordering is by name
and then start line. The client restores file/source order before native sorters.
[Command contract](https://github.com/golang/tools/blob/gopls/v0.21.1/gopls/internal/protocol/command/interface.go),
[syntax graph](https://github.com/golang/tools/blob/gopls/v0.21.1/gopls/internal/golang/symbols.go),
[command handler](https://github.com/golang/tools/blob/gopls/v0.21.1/gopls/internal/server/command.go#L1777-L1803).

Owned real-gopls controls establish differing normal, same-package test and
external-test membership, active build tags, dirty cross-file changes and a
non-disk overlay in their fixtures. They do not establish the commercial product's
rules or every reconstruction VFS/virtual-URI path. A separate actual CGO control
shows a compiler-cache file at index zero, source files at later indices, and
compiler-generated `_Cgo` symbols. The source and ordinary-file queries return
the same graph; source `documentSymbol` returns only its source declarations.
That is a backend observation, not commercial CGO parity or native acceptance.

## Native model and receiver grouping

[GoStructureViewFactory](../../src/main/kotlin/dev/openidea/goide/lsp/GoStructureViewFactory.kt)
keeps the upstream LSP capability gate and native hosts. For Go it constructs
[GoStructureViewModel](../../src/main/kotlin/dev/openidea/goide/lsp/GoStructureViewModel.kt),
which wraps the existing file root with `GoReceiverStructureRoot`, then
`GoPackageStructureRoot`. Non-Go files retain the upstream model. Generic clients
on Go files retain their original flat symbol results. The model owns and disposes
its package root; it exposes the same controls to both native Structure hosts.

The file fallback groups canonical pointer, value and generic receiver names
under one unambiguous local type. It checks real cached source selections and
non-alias type declarations before moving a method. Existing fields remain
children. Aliases, interfaces used as receiver candidates, ambiguous names,
malformed selections and foreign-file candidates are kept visible without guessed
ownership. Named primitive and function types can have methods too.

Tree wrappers preserve the original `DocumentSymbolData`, native selection value,
navigation, icon and node key. Receiver prefixes are removed only from the grouped
method display; server DTO names/data remain intact. Explicit navigation forwarding
also preserves Java default-interface methods that Kotlin delegation alone would
not forward. Dirty refresh and Undo rebuild from the upstream immutable symbol
snapshot. The editor-position callback reads only a current completed package or
document-symbol future and strict cached UTF-16 ranges. A cold callback does not
load symbols, resolve files, wait or issue RPC on the EDT.

## Initial native failure and popup selection repair

The initial **350-input / 92f0de1a…** package, native PID **22681**, visibly groups
pointer, generic and named function-type receiver methods; preserves signatures
and source filenames; and hides CGO compiler internals. Native package/private
toggles and alphabetical/exportability sorting work in the observed fixtures.
Screenshots establish Unicode private/exported visibility, including supplementary
uppercase names. Package-off filtering keeps a foreign type ancestor needed for
a current-file method. These are narrow observations, not complete parity.

Actual foreign method navigation opens `methods.go` at 3:18. A new unsaved
`(Missing).Draft` remains visible after closing its dirty editor, and its tree row
reopens the buffer at 9:16. Two native Undo operations first restore caret history,
then remove the text and its row. Internal test Structure includes its current
test declarations and method; the external-test variant shows its own symbols.
The native whole-package `go test -json .` run reports **two tests passed**,
Go 1.24.3 exit 0 and 0.445 s without a cached marker; its fixture includes a real
CGO call. This is not a function-specific run claim.

Manual popup navigation to `Lookup` succeeds. **Reopening the popup with the caret
at `Lookup`, types.go 14:18, fails to preselect it:** the visible selected row is
the parent `Record`, including a later screenshot. AX can expose a cached
"Selected Lookup" row, so that accessibility label is not treated as selection
proof. This is the confirmed failure in the initial bytes, despite their passing
automated build and other native positives. The old closed trace and observations
remain separate from the repair.

The actual SDK reproduces all four original selection failures with a real
`FileStructurePopup` constructor, its captured `myInitialElement`, actual
`rebuildAndSelect` delegate and asynchronous JTree. No replacement selection model
is used. The constructor captures the target before package symbols arrive. A
warm ordinary document-symbol target differs from the package node class; a fully
cold model has no initial target at all. Rebuilding the tree does not recapture
that initial target.
[Popup capture/selection](https://github.com/JetBrains/intellij-community/blob/456919a9624bae72ac12efafc35d2b916cb0c5c5/platform/lang-impl/src/com/intellij/ide/util/FileStructurePopup.java),
[native selection visitor](https://github.com/JetBrains/intellij-community/blob/456919a9624bae72ac12efafc35d2b916cb0c5c5/platform/lang-impl/src/com/intellij/ide/structureView/newStructureView/StructureViewComponent.java).

The independent repair uses a per-view `GoStructureSelectionOrigin`: a cached,
versioned source point whose PSI parent resolves to the matching completed symbol
snapshot. It follows the native visitor's PSI ancestry contract without changing
protocol DTO equality, hash codes or global caches. A completed-future token caches
the resolved parent per snapshot and changes when the package response supersedes
ordinary document symbols. The bridge performs no file resolution, RPC or wait.
It is not a navigable declaration or tree key.

The origin expires when its source, model, editor or project becomes invalid.
Native Undo can restore `modificationStamp`, so the source snapshot also captures
the public `DocumentEx.modificationSequence`; an edited origin cannot revive merely
because Undo restored its text and stamp. A fresh popup gets a fresh source origin.
[Public sequence contract](https://github.com/JetBrains/intellij-community/blob/456919a9624bae72ac12efafc35d2b916cb0c5c5/platform/core-impl/src/com/intellij/openapi/editor/ex/DocumentEx.java#L91-L96).
The final seven controlled cases include cold/warm/body/reopen selection, generic
flat-client selection without a package request, edit/Undo/non-revival and fresh
popup, and model/editor disposal. They pass **7/7 in 4.925 s**. Against the unchanged
initial production classes, the same seven cases give **six failures**, with the
generic-flat case passing. The repaired native outcomes are listed separately
below; they do not convert the original native failure into a pass. The green SDK log includes
an expected strict-range fallback warning from the canned peer's old-range reply
during edit/Undo; it is not asserted to be warning-free or to describe a native
session log.

The initial native run restores observed preferences and quits normally. Its six
recorded owned PIDs are absent and all nine recorded fixture files match initial
disk hashes. Its trace has **250 records / 305,193 bytes**, SHA-256
`8e50c1632e77ceb4338391a62743efc98f0f9e2bfc7f2758d2f79dcca59a4bdd`.
The final-session ERROR/SEVERE scan is empty; warnings and informational exception
strings are not excluded. Root observations are retained in
`.tools/structure-native-initial-observations.json`; the independent protocol audit
is separate. No newer package or successful repair is inferred from these bytes.

## Repaired-package native workflows

These are root-observed native results from the **351-input / 920334cb…** package,
PID **26222**. Displayed file positions are one-based UI positions. A new popup
model is exercised; the language-server and ordinary symbol caches are not claimed
fully cold. Fully cold response timing is covered by the controlled SDK tests,
not inferred from desktop timing.

| Actual native operation | Repaired-cohort result |
| --- | --- |
| First Cmd+F12 from Lookup, types.go 14:18, before opening the Structure tool window | Actual screenshot selects **Lookup**, rather than parent Record. This directly retests the initial failure. |
| Package on, Escape, reopen from Lookup | Package entries and foreign filenames appear. A fresh popup preserves the package choice and selects Lookup. |
| Private off, then restore on | Visible private names disappear, including Chinese, titlecase and lowercase examples; exported Ωmega and supplementary-plane Lu 𐐀Count remain. Screenshot state is used instead of cached AX rows. |
| Cmd+F12 inside the Lookup body, types.go 14:53 | Actual selection remains Lookup. |
| From a fresh popup, select Rename and Return | Opens methods.go 3:18 through the actual cross-file row. |
| Structure tool window on methods.go | Cross-file Record children, Counter.Count, Callback.Invoke and the named function-type suffix are visible with package/private on. |
| Append unresolved Draft, close the dirty buffer and select its foreign tree row | Draft remains visible and reopens the actual unsaved buffer at 9:16. All nine recorded disk files remain initial. |
| Two native Undo operations | The first restores caret history to 10:1; the second restores original source at 7:1 and removes Draft from the visible tree. |
| Internal-test source, click TestOnly, Cmd+F12 | Actual selection is **TestOnly**. Package off retains its foreign Record ancestor, current method and current test function. |
| Native whole-package Go test | Go 1.24.3 `go test -json .` reports **two tests passed**, 0.968 s without a cached marker, exit 0. TestStructureFixture includes a real CGO call returning 42; TestExternal also passes. No function-specific context claim is made. |
| Open external_test.go | Its visible Structure root contains ExternalOnly and TestExternal, without base Record or CgoAnswer. |
| Normal Quit | Confirm Exit and actual app quit are observed; all six recorded owned PIDs are absent. |

Tool-window **Alphabetically** and **By Exportability** toggles were exercised on
the initial 350-input package. They were **not retested on the repaired 351-input
package**, and those old native results are not transferred here. Their final
automated regressions remain separately attributed.

Automation retries are retained: initial Go To File was canceled during searching;
checkbox focus once caused a different navigation before a separate fresh-popup
Rename pass; a source-coordinate click was needed after text-selection access was
unavailable in the test file. Cached/offscreen AX rows are not visibility or
selection evidence. These retries are not silently treated as successful actions
or product failures.

After normal Quit, all nine fixture disk hashes match initial contents. The closed
trace has **251 records / 281,521 bytes**, SHA-256
`ceebcfb926ab31e88368770a484887a909461c408c84a54c37498d96a74dfea1`.
The final-session log has zero ERROR/SEVERE lines; this is not a warning-free claim.
Root observations are retained in `.tools/structure-final-native-observations.json`.

Independent replay pairs all **104 RPCs**, including **14 package-symbol** and
**12 document-symbol** requests, with zero orphan responses, unanswered requests,
response errors or document-sync/structural issues. The audit checks **459 returned
source-node occurrences** (374 package and 85 document symbols), each declaration/selection range
against explicit request and response source candidates: **1,836 valid checks**.
It excludes **169 auxiliary/external nodes** without reading their bodies or
checking their ranges. This is not a claim that every raw compiler node is a valid
source declaration.

All four disk checkpoints match. The closed-buffer sequence is explicit: didClose
record 117, dirty version-1 reopen 118, package query 44 at record 119, and
supplemental document-symbol query 45 at record 122 returning Draft at raw UTF-16
(8, 15). Undo change 158 precedes fresh document query 64 at record 162, whose
response omits Draft. The caret-only first Undo and actual tree/editor outcomes
remain separately attributed root UI observations.

Independent final checks match all **351 archived/current inputs**, **32
artifacts**, six Platform and twenty-one LSP patch classes, all **98 XML reports /
472 passing tests**, nine fixture files and six absent recorded PIDs. Signature
verification exits 0 and the exact final-session log has zero ERROR/SEVERE lines.
On-disk class/marker matching does not establish every live class-resolution
decision. The summary SHA-256 is
`6dde3d38fd2ec87149d6cf8b7ca4e57199f44abbb029251a8b8fe46d1d64d12b`;
independent checks SHA-256 is
`88697bcb23f1c4416b57622022415c6ce82b6ca07b55b847e861a1ff5df0d14a`.
The portable native record embeds the independently frozen evidence.

The trace includes **19 diagnostic notifications**, ten nonempty, containing
twenty-four information and two error items while the fixture is edited. These
language diagnostics differ from the IDE session's ERROR/SEVERE log scan.
Numeric diagnostic versions repeat across reopen epochs, so ambiguous messages
remain unassigned to a unique source epoch. Package replies carry no document
version; the source-candidate checks are not full server-content acknowledgements.
Four distinct opened document URIs have final didClose envelopes. No shutdown
request, exit notification or cancel request appears in this trace. Normal UI Quit
and process cleanup are separately observed/checked; this trace does not prove all
cancellation/disposal races, future sessions or build/test contexts.

## Authoritative package graph and safe fallback

[GoPackageStructure](../../src/main/kotlin/dev/openidea/goide/lsp/GoPackageStructure.kt)
implements `GoPackageStructureRoot`, `GoPackageSymbolSupport`, file-aware symbol
data and package elements. The optional package action is offered only when the
matched initialized Go server advertises `gopls.package_symbols`. Unsupported
servers and null/error results retain the grouped file view, rather than a client
directory enumeration or an invented package.

The package root always obtains one authoritative package snapshot. Its reversed
**Show Package Structure** filter selects current-file nodes when unchecked and
the package graph when checked. A foreign type ancestor remains visible when it
holds a current-file method. Foreign rows carry filename provenance; the model
does not insert artificial file-container folders. Package origin and private
visibility compose as distinct native filters.

Before the command, background work connects project-open Go buffers and closed
unsaved Go buffers in the query directory, then waits for actual shared didOpen
and pending-change dispatch barriers. The navigation-aware support captures the
initialized server/dependency generation after connection. Response decoding,
URI/VFS/PSI resolution and uncached document work stay off the EDT. The decoder
preserves original `Files` index slots while excluding entries outside the query
source directory, including compiler-cache auxiliary files. It does not reindex
later source symbols or render compiler internals as package source.

Strict UTF-16 decoding rejects fractional/out-of-range indices, invalid lines or
columns, surrogate splits and selection ranges outside declarations. An invalid
source graph falls back as a whole; it is not partly installed with clamped
navigation. File-aware keys distinguish otherwise identical declarations such as
separate `init` functions. Generation, document stamp, cancellation and exact
server identity/status checks prevent stale nodes from remaining navigable.

The backend helper can omit methods whose receiver declaration is missing. The
client scans lexical receiver-method selections in background, including source
files with no other returned symbols, and requests `textDocument/documentSymbol`
only for affected files. It merges by file/kind/selection identity, preserving
known parents and cross-file children without duplication. Unmatched methods stay
flat and navigable. This preserves partial editing output; it does not validate or
repair invalid Go receiver types.

Publication ties the immutable children to the exact valid support future.
`currentElement` cannot combine an old completed graph with a pending newer
generation. Stable unsupported, null and ordinary-error fallbacks are cached for
their generation to avoid EDT retry/rebuild loops; ordinary failures are logged
as warnings. Cancellation is not converted into success. A lifecycle monitor
linearizes owned request publication and disposal without awaiting inside the
monitor. The owned EDT-scheduled loader and wait are canceled on invalidation or
disposal; shared document-synchronization/fallback futures are not canceled by
this view. Late replies cannot publish into a changed or disposed model.

## Go visibility and sorting

[GoStructureControls](../../src/main/kotlin/dev/openidea/goide/lsp/GoStructureControls.kt)
uses the public `FileStructureFilter`, `TreeActionWithDefaultState` and `Sorter`
contracts, with the platform alphabetical sorter. **Show Private Members** is
enabled by default as an independent product choice preserving the all-members
view. No commercial default or new shortcut is claimed.

Visibility uses raw symbol names/kinds, with Go identifier code-point categories
and initial Unicode `Lu` exportability. Method receiver text is excluded from the
identifier decision. Unknown, synthetic, malformed and unsupported nodes remain
visible. Exportability sorting ranks exported, unknown and private nodes
separately, retains stable ties and precedes the optional alphabetical comparator.
It does not parse formatted presentation labels or perform file/RPC work in UI
callbacks. JBR and a selected Go SDK can use different Unicode table versions;
the code-point-category tests are not a claim of identical tables for every Go
release.

## Verification stages

These private runs use actual IntelliJ IDEA Community **2026.1.4 /
IC-261.26222.65**, bundled JBR 25 and Kotlin 2.3.21. Their controlled owned remote
peers are not real-gopls source acknowledgement or native desktop evidence. Counts
below are stages/subsets, not numbers to add to a later complete-suite total.

| Stage | Recorded result and scope |
| --- | --- |
| Go controls | **6/6 pass, 5.087 s.** Actual native tree action wrappers and sorting/filter pipeline; real File Structure checkbox click, persistence, disposal/reopen; Unicode visibility, conservative unknowns and comparator contracts. |
| Receiver grouping baseline | **8 cases, 7 failures.** A test-only switch selects the real upstream LSP factory. This is a bounded public-model baseline, not the exact old product binary. The generic-client case stays green. |
| Initial receiver grouping implementation | **8/8 pass, 5.435 s.** Actual SDK model/renderer/navigation, original-value tree selection, dirty refresh/Undo, conservative malformed/alias cases and cold/warm cached-only editor callbacks. This predates the popup repair. |
| Existing presentation regressions | **5/5 pass, 4.913 s.** Signatures/types remain intact alongside the new model. These cases already existed; do not count them twice. |
| Package hardening intermediate | **22/22 pass, 6.240 s.** Negative capability/result/error, synchronization, strict ranges, native cross-file navigation, origin/private composition, cancellation, lifecycle and generation guards. This is an intermediate source cohort. |
| Missing-receiver and node-epoch hardening | **25/25 pass, 6.929 s.** The same new test classes against the intermediate 22-case production overlay give exactly **4 expected failures**: current/foreign missing receivers, partial-child merge and stale navigation after a configuration epoch. The compiled overlays are recorded; no ABI failure is used as RED evidence. |
| CGO auxiliary-file hardening | **27/27 pass, 6.528 s.** The same 27 test classes against the preceding 25-case production overlay give exactly **2 expected CGO failures**, with the prior 25 cases still passing. New cases preserve raw file-index slots while excluding unmapped compiler auxiliaries, retain cross-file navigation and cache a stable fallback when no query source is mapped. This controlled SDK result is not standalone real-CGO integration. |
| Initial complete build and packaged artifacts | **465 tests / 97 reports, zero failures, errors or skips; terminal exit 0.** The 350-input cohort and all 32 artifact hashes match, with input and deep strict signature verification passing. This predates the popup repair. |
| Initial native desktop workflows | Native grouping/controls/navigation/dirty-buffer/test positives coexist with a **confirmed popup preselection FAIL**. These observations belong only to the initial 350-input package. |
| Popup selection repair | **7/7 pass, 4.925 s.** Same seven cases against unchanged initial production classes give six failures; the original four-case reproduction is separately 4/4 RED. These post-build cases were not in the initial 465 tests. |
| Final-model private compatibility runs | Grouping **8/8, 8.169 s**; existing presentation **5/5, 8.142 s**; independent package **27/27, 6.487 s**. These bind the repaired model SHA `584031f5…a795`, separately from the initial runs. |
| Repaired complete build | **472 tests / 98 reports, zero failures, errors or skips; terminal exit 0.** The 351-input repair cohort and all 32 artifacts match, with input and deep strict signature verification passing. |
| Fresh native workflows | PID **26222** completes the listed popup/grouping/filters/navigation/dirty-buffer/Undo/test/normal-Quit checks on the repaired 351-input package. Independent closed-trace replay and final identity/cleanup checks pass within the recorded boundaries. Tool-window sorting is not retested in this cohort. |

Private evidence is retained in
`.tools/go-structure-controls/evidence.json`,
`.tools/go-structure-grouping/evidence.json`,
`.tools/package-structure/hardening-evidence.json`,
`.tools/package-structure/final-evidence.json` and
`.tools/package-structure/cgo-final-evidence.json`.
Their logs and compiled-class digests distinguish each run; the last filename's
27-case snapshot binds the CGO hardening, while `final-evidence.json` retains its
earlier 25-case cohort. Backend controls are
separately retained in `.tools/gopls-package-symbols-dnofkt2v/report.json` and
`.tools/gopls-package-cgo-9gs7r12l/report.json`. Those real stdio sessions shut down
normally, their recorded owned PIDs/process groups were absent, and fixture disks
were unchanged. Sampled process ownership does not reconstruct every short-lived
descendant's history.

The popup repair and final-model compatibility runs are separately bound in
`.tools/go-structure-popup-selection/final-evidence.json` and
`.tools/package-structure/popup-final-model-evidence.json`. The former retains the
same-source RED/GREEN boundary, excluded harness attempt and exact compiled-class
digests; the initial four-case RED record is not relabeled as a seven-case run.

The initial complete run includes package **27/27 in 1.591 s**, controls **6/6 in
0.358 s**, grouping **8/8 in 0.406 s** and existing presentation **5/5 in
0.181 s**. These are included in 465, not added to it. The packaged Go plugin JAR
SHA-256 is
`335776e3cfe1e326d8f3dcc4edb275e7d508d6a7fe6c0b2f5c87dae7f59491b5`;
the unchanged pinned LSP JAR is
`ec6f92576b2d62caf4a9298ec859bcd8c7cc82b4cdde5abe6426f2b607675b44`.
The initial build facts are retained in `.tools/structure-package-build-facts.json`;
the portable [build record](structure-package-build.json) keeps that cohort
separate from the repair.

The repaired complete run includes package **27/27 in 1.716 s**, controls **6/6 in
0.364 s**, grouping **8/8 in 0.424 s**, popup selection **7/7 in 0.379 s** and
existing presentation **5/5 in 0.186 s**. All are included in 472. The repaired Go
plugin JAR SHA-256 is
`181d0a9d5e0e600286d40fd624da51d33cdcc8aeb6b78c5b1448c28a758290b3`;
the LSP JAR is unchanged from the initial package. Repaired build facts are
retained in `.tools/structure-final-build-facts.json`. No initial native result is
used as repaired-package acceptance.

This turn's full Go race run exits 0 with **271 passing test events / 155
top-level tests / nine passing packages**. All nine package results are cached;
there are **zero fresh package results**. Vet exits 0 with empty output. Separate
Go 1.24.3 fixture controls run `go test -count=1 ./...` and
`go test -count=1 -tags=special ./...`, passing in 0.810 s and 0.318 s respectively
for the main fixture package. The subpackage has no tests. Those CLI controls are
not native IDE test runs or package-view acceptance.

The actual-SDK negative tests cover late replies after another document changes,
explicit cancellation and a fresh next generation, model/project disposal,
closed-unsaved buffers, held shared futures, pending configuration changes,
unsupported commands and malformed positions. They prove those deterministic
paths with a controlled peer. They do not prove all language-server delivery,
operating-system scheduling, user project sizes or every interleaving.

## Remaining scope

Commercial toggle defaults,
internal property IDs and the selected `of {0}` resource's interpolation meaning
remain unknown. No proprietary implementation was inferred from a registration or
label. The package graph follows the backend's selected build/test context, not
all contexts at once. Every cgo/generated/workspace/overlay URI combination,
typed alias equivalence, semantic receiver validity, large-project performance,
all asynchronous races and full GoLand equivalence remain outside the evidence
given here. The editor still has the previously documented flat PSI boundary.
