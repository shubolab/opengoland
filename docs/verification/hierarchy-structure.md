# Go hierarchy and Structure workflows

A subsequent [package Structure batch](structure-package.md) adds receiver grouping,
package symbols and native Go filters/sorting, then repairs asynchronous popup
preselection. Its 351-input/472-test package and native PID26222 are separate
from this historical 343-input/424-test record. The later result supersedes the
receiver-grouping/package-view limitation below; full parity remains open.

The repaired package passes **424 Kotlin/platform tests across 94 reports**, with
zero failures, errors or skips. Its **343 frozen inputs** have digest
`edfe8d6a92f915c908a3872af65c74ea5d8ee310a108644f3d0258c7b425ab1c`.
The complete package command terminated successfully. All 32 manifest artifacts
match, the LSP marker's twenty-one classes match the final public-source pin,
343-input verification passes, and deep strict local signature verification exits
0. The [build record](hierarchy-structure-build.json) binds those checks and
artifacts to this cohort. Fresh native PID **15944** completed the listed workflows
and normal Quit. Independent replay of the closed trace and final identity/cleanup
checks also pass within their stated scope. The
[native record](hierarchy-structure-native.json) retains the cohort boundary.

The complete run includes **11 hierarchy workflows**,
**five Structure presentation cases**, and **four concurrent navigation-parameter
cases**. Their independent actual-SDK runs also pass; these subsets are already
included in 424 and are not added again. Their SDK is IntelliJ IDEA Community
2026.1.4, **IC-261.26222.65**, with the bundled JBR and an owned in-memory language
service. Controlled SDK tests alone are not standalone-delivery evidence.

The old **7284c945…**, PID **12124** native cohort demonstrated useful hierarchy
and Structure workflows, then exposed a **confirmed Base On action failure**.
Those observations belong to its previous bytes. They are not transferred to the
new package. All 36 complete acceptance requirements remain open.

## Reference scope

REA authenticated the installed GoLand **2026.1.4 / GO-261.26222.72** and selected
unchanged descriptor, action, keymap and property resources. That metadata declares
Go type/call hierarchy and Structure providers, plus the corresponding base,
subtype/supertype and source-navigation actions. It does not establish their
runtime behavior, active keymap, complete filtering or implementation details.
No proprietary implementation class was read or copied, and the reference app,
configuration, licenses and projects were not changed. The
[reference record](../reference/hierarchy-structure-reference.json) preserves
observation, inference and unknown separately.

The versioned public GoLand documentation describes the native file Structure
window and popup, navigation, typing search, package visibility and exportability
options. Its published images visibly show function/method signatures and field
or underlying-type suffixes; struct/interface rows omit synthetic bodies. These
are screenshot-shown presentations, not an established clean-install default or
a measurement of the installed commercial runtime.
[File structure](https://www.jetbrains.com/help/go/2026.1/viewing-structure-of-a-source-file.html),
[Structure controls](https://www.jetbrains.com/help/go/2026.1/structure-tool-window-file-structure-popup.html),
[tool-window image](https://resources.jetbrains.com/help/img/idea/2026.1/go_structure_popup_window.png),
[popup image](https://resources.jetbrains.com/help/img/idea/2026.1/go_file_structure_popup.png).

## What the previous native package proved

These are root-observed native UI results from **338-input 7284c945…**, commit
`18cd54c2204fde17a1cdd3c501aea27ac4355a01`, PID **12124**. Displayed positions below
are one-based native UI labels; raw LSP positions are separately zero-based.

| Actual native operation | Previous-cohort result |
| --- | --- |
| Find Action: Type Hierarchy on Source, contracts.go 4:7 | Native Subtypes of Source tree shows ExtendedSource, Remote, Embedded, Extended, Memory and Pointer. Double-clicking Remote opens client/use.go 11:6. Prepare request 13 at LSP (3, 5), then subtype request 14 and child expansion. |
| Find Action: Call Hierarchy on Leaf, functions.go 3:7 | Callers tree contains Left and Right. Expanding Left shows Calls and Right; double-click Calls opens client/use.go 18:6. |
| Actual Call Hierarchy shortcut at Right identifier end, then callee toggle | Callers of Right opens; the callee control changes the view to Callees of Right with Leaf and Left. |
| Structure window on use.go | Remote, (Remote).Read, Use, Generic, Standard, Calls and Shadow appear. Labels omit signatures. Double-click Use opens 15:6. |
| Append an unsaved UnsavedStructure function | Fresh documentSymbol request 59 follows document version 2; the visible tree adds the function while the disk hash remains original. |
| Undo that append | Source text returns exactly, a new documentSymbol request is issued, and the function disappears from the tree. This does not establish every navigation/Undo history sequence. |
| Native File Structure popup | The seven symbols appear; keyboard Down from Use selects Generic and Return navigates to 16:6. |
| Switch from use.go to functions.go | Structure changes its root and shows Leaf, Left and Right without retaining the old Use symbols. |
| Base On This Method on the selected call-hierarchy node | **FAIL:** the actual action dispatch throws the missing-client exception described below; no new prepare request or new root appears. |

The server itself labels Calls with `core/graph • use.go` while its URI identifies
client/use.go. The old client displays that server detail; this record does not
silently replace it with a guessed package label. In this gopls response, hierarchy
`range` equals `selectionRange`; a separate controlled fixture is needed to prove
the client's handling when they differ.

After normal Quit, the five recorded owned PIDs were absent and all twelve fixture
disk hashes matched their initial values. The old trace has **226 records /
129,741 bytes**, SHA-256
`336d3e876694d5f983910cf27cf429ea1846704b608787d8222b522fd2f87315`.
Its session log contains the confirmed SEVERE action exception, so this is expressly
not an error-free native cohort.

Some early right-click or RootPane-focused attempts were inconclusive. The later
failure is established by the actual exception stack and recorded last action,
not by a missing popup or an apparently ignored key. Offscreen AX controls and
batched shortcuts during loading also required fresh state or keyboard selection;
those automation retries are retained with the native observations.

## Correct Go dispatch and hierarchy node lifetime

The decisive old stack is `CallHierarchy.BaseOnThisMethod` →
`HierarchyBrowserBaseEx.BaseOnThisElementAction` →
`BrowseHierarchyActionBase.createAndAddToPanel` → the platform built-in
`LspCallHierarchyProvider.createHierarchyBrowser`, which throws
`IllegalStateException: Missing cached LSP client on call-hierarchy target`.
The same Type and Call failure reproduces in the actual SDK with a real hierarchy
JTree and an enabled registered Base On action.

The LSP4IJ fake hierarchy element inherits `Language.ANY`. Platform provider lookup
tries the selected element's language before its containing-file fallback, so the
competing built-in ANY provider wins before the Go provider is considered. Its
target does not carry that other LSP implementation's cached client. Merely changing
the language would leave a second error: the old hierarchy item's PSI text range
is the dummy `(0, 0)`, and re-rooting prepares at that range start.
[FakePsiElement](https://github.com/JetBrains/intellij-community/blob/456919a9624bae72ac12efafc35d2b916cb0c5c5/platform/core-impl/src/com/intellij/psi/impl/FakePsiElement.java#L29-L32),
[provider lookup](https://github.com/JetBrains/intellij-community/blob/456919a9624bae72ac12efafc35d2b916cb0c5c5/platform/lang-impl/src/com/intellij/ide/hierarchy/actions/BrowseHierarchyActionBase.java#L140-L168),
[old hierarchy item](https://github.com/redhat-developer/lsp4ij/blob/b30f69dd1285f201d53ce360b7fad0bba96f0d58/src/main/java/com/redhat/devtools/lsp4ij/features/hierarchy/LSPHierarchyItemPsiElement.java#L27-L42).

The independent [Go providers](../../src/main/kotlin/dev/openidea/goide/navigation/GoHierarchyProviders.kt)
keep the standard action IDs, native hierarchy browser, async tree and LSP4IJ
background services. They provide these Go-specific contracts:

- Initial discovery accepts actual Go identifier leaves in the committed cached
  document. It prefers the token at the caret, permits an identifier-end fallback,
  and rejects blanks, comments, strings, punctuation, `_` and EOF without an
  adjacent identifier. It does not
  discover files, commit PSI or issue RPCs in the target hook.
- Query origins and result nodes report Go as their language, so standard Base On
  lookup returns to the Go provider. Native context menus and shortcuts remain
  platform actions.
- Declaration and selection ranges have separate document markers. Navigation
  and the rebase query use the selection. Child expansion clones the outgoing
  hierarchy item with both live ranges, preserving URI, name, kind, tags, detail
  and opaque server data; the old response object is not modified.
- The SDK disposes the old browser before handing its selected node to the new
  browser. Disposal freezes the last valid range and document stamp, then disposes
  both markers. A new browser creates its own markers from that valid snapshot;
  deleted/invalid markers cannot revive at an identical replacement.
- Symbol equivalence uses project/file, kind, name and live range. A repeated
  ancestor becomes a visible terminal recursive node, excluding the synthetic
  query root from cycle detection. This prevents self/mutual calls and compatible
  interface paths from expanding forever through fresh fake-PSI identities.

Root creation uses cached documents. Missing target documents are loaded only in
the platform's background descriptor construction. Marker snapshots do not perform
filesystem or process work on the EDT. Existing gopls call-hierarchy code reads
`Item.Range.Start`, which is why moving only the displayed node or selection marker
would be insufficient for later child requests.
[SDK disposal order](https://github.com/JetBrains/intellij-community/blob/456919a9624bae72ac12efafc35d2b916cb0c5c5/platform/lang-impl/src/com/intellij/ide/hierarchy/HierarchyBrowserBaseEx.java#L663-L685),
[gopls v0.21.1 call hierarchy](https://github.com/golang/tools/blob/gopls/v0.21.1/gopls/internal/server/call_hierarchy.go).

## Structure signatures reach the native renderer

The old server already returned signature/type details. `DocumentSymbolData`
delegates them to its client feature, but the upstream Structure tree element does
not forward the secondary presentation into its own `ItemPresentation`. The SDK
renders that tree element rather than the wrapped PSI element's presentation, so
the visible rows remain name-only.
[upstream model](https://github.com/redhat-developer/lsp4ij/blob/b30f69dd1285f201d53ce360b7fad0bba96f0d58/src/main/java/com/redhat/devtools/lsp4ij/features/documentSymbol/LSPDocumentSymbolStructureViewModel.java),
[SDK tree-element presentation](https://github.com/JetBrains/intellij-community/blob/456919a9624bae72ac12efafc35d2b916cb0c5c5/platform/structure-view-impl/src/com/intellij/ide/structureView/impl/common/PsiTreeElementBase.java#L30-L38).

[GoDocumentSymbolFeature](../../src/main/kotlin/dev/openidea/goide/lsp/GoDocumentSymbolFeature.kt),
wired by the Go language-server factory, formats recognized function/method details
as signatures and field/variable/underlying-type details as suffixes. It avoids
duplicating a signature in secondary text and suppresses synthetic struct/interface
bodies. Unrecognized detail retains native secondary text; absent detail retains
the name. Server-provided symbol names/data remain intact; parent structure, kind
icons/decorations, children, selection-range navigation and document-symbol caching
remain delegated to the existing model. Other LSP clients retain their prior
presentation.

The actual-SDK test pipeline is the public LSP document-symbol Structure model →
Go feature → tree element → `StructureViewComponent.createWrapper` used by File
Structure → actual `NodeRenderer`. It is not a DTO-only formatting check. Tests
also navigate through the native wrapper after same-line Chinese/emoji text,
refresh a signature from an unsaved edit, retain unchanged-context cache reuse,
and prove that presentation does not save the source.

## Concurrent navigation parameters share the future monitor

The previous navigation-cache record deliberately left a same-PsiFile,
different-offset interleaving open. The four public support getters updated
`previousOffset` outside the monitor used by the base future cache. A second query
could publish its future while the first was waiting, allowing the first to reuse
the wrong location.

The new public-source revision synchronizes the Declaration, Definition, Type
Definition and Implementation getters on their existing support instance. Parameter
comparison, cancellation/key update and base future publication now share that
monitor. It does not change the earlier semantic-epoch policy or other feature
caches. Exactly four compiled classes change; the other seventeen classes in the
eleven-source/twenty-one-class pin remain byte-identical.
[Pinned revision](../../product/patches/lsp4ij-0.21.0-document-sync.json).

The deterministic [SDK regression](../../src/test/kotlin/com/redhat/devtools/lsp4ij/GoNavigationParameterGateTest.kt)
uses uninstrumented public getters and real PSI/VFS/accessor state. It acquires
read access before owning the support monitor, verifies worker A is blocked on
that exact monitor, runs B reentrantly, then releases A. The old getters return
B's Right location for A; the repaired getters send both distinct UTF-16 positions
and return their own targets. Unchanged A still reuses its future. Worker join is
bounded; monitor release and fixture cleanup run in `finally`. This is a controlled
schedule, not native concurrency stress or a fairness guarantee.

## Verification stages

| Stage | Result and exact boundary |
| --- | --- |
| Old native 7284c945…, PID 12124 | Positive workflows above, plus confirmed Call Base On SEVERE failure. Closed with five recorded PIDs absent and twelve disk files initial. |
| Hierarchy SDK baseline | Three tests fail: actual Type/Call Base On exception and identifier-start whitespace selection. The earlier legacy non-snapshot context attempt is excluded. |
| Go hierarchy SDK repair | **11 pass, 5.028s.** Actual tree/action dispatch, live ranges, dirty rebase/expansion, view switches, recursive paths and invalid-node disposal. Six hierarchy RPC methods assert non-EDT execution. |
| Structure actual renderer baseline | Five tests run, **three fail**: signatures, dirty refreshed signatures and unknown secondary detail. Existing selection navigation and generic-client presentation pass. |
| Go Structure actual renderer repair | **Five pass, 4.400s.** Native wrapper/renderer, icons, UTF-16 selection navigation, dirty details/cache reuse and fallback presentation. |
| Navigation parameter baseline/repair | **Four fail → four pass**, repaired run 4.485s; actual public getters, controlled server replies, stable-context future reuse retained. |
| Repaired complete package | **424 tests / 94 reports; zero failures/errors/skips.** Includes hierarchy 11, Structure five, parameter-gate four, existing cache eleven and real-gopls navigation five. 343-input and manifest verification pass. |
| Engine race/vet commands | Exit 0: 271 pass events including subtests, 155 top-level tests, nine package passes. **All nine packages cached, zero fresh.** Vet exit 0 with empty output; this is not a new uncached Go execution. |
| Repaired native hierarchy/Structure | PID 15944 completed the listed workflows and normal Quit; closed trace and final identity/cleanup independently audited. Old UI results are not transferred to the new bytes. |

The final Go plugin JAR SHA-256 is
`7871539706f399ba00fd2e692eb74a3ac9503cf44289d79bd5c1e3406cd7b256`;
the LSP4IJ JAR SHA-256 is
`ec6f92576b2d62caf4a9298ec859bcd8c7cc82b4cdde5abe6426f2b607675b44`.
The marker records final public patch SHA-256
`c3caaafb453e35d5926e5f25e02b299af0bd7d81e624584b657ee2d611ccbd5c`.
The eleven hierarchy cases pass in the complete-run XML in 5.058s; the four
parameter cases take 0.159s and the five Structure cases 0.242s. The durations in
the earlier rows identify their separate independent runs, not additional tests.

Independent run logs and hashes are retained in `.tools/hierarchy-workflow/`,
`.tools/go-structure-details/evidence.json`, and the source pin's
`navigationParameterGateRevision`. The build record retains the complete terminal
command, XML/log hashes, input verification and artifact identities. These subsets
are not added to historical or complete-suite counts.

## Repaired-package native verification

The root completed the fresh **343-input edfe8d6a…**, PID **15944** walkthrough with
its own thirteen-file fixture. These are observations against the repaired package,
not transfers from the previous PID 12124 cohort. Native editor positions below
are one-based UI labels; the explicitly marked protocol position is zero-based
UTF-16.

| Repaired native workflow | Observed result |
| --- | --- |
| Type Hierarchy at Source identifier start after two spaces | Actual Ctrl+H at contracts.go 4:7 produces the six expected subtype rows: ExtendedSource, Remote, Embedded, Extended, Memory and Pointer. |
| Base On ExtendedSource, then switch direction | The hierarchy shortcut on the selected node changes the base; its subtype view shows Extended. Switching to supertypes shows Source and io.Closer. |
| Dirty Unicode prefix, then Base On Source and navigate | Fresh prepareTypeHierarchy request **40** uses UTF-16 **(4, 6)**; double-click Source opens its live position **5:7**. All thirteen disk hashes remain initial in the paired checkpoints. A caret-only Undo followed by text Undo restores contracts.go. |
| Call Hierarchy and consecutive Base On actions | Leaf shows Left/Right; rebasing on Left shows Calls/Right; rebasing again on Right opens Callers of Right. This repeats the old crashing standard action in the repaired package. |
| Switch Right to callees | The native toolbar changes the view to Callees of Right with Leaf and Left. |
| Recursive calls and Expand All | Recursive terminates at a self-recursive leaf; Even → Odd → Even terminates at a recursive leaf. Expand All becomes disabled at the bounded tree. |
| Structure on presentation.go | Native rows show nested `Name: string` and `名称: string`, `(*Record).Lookup(value string) (int, bool)`, `Counter: int64`, `(Counter).Count() int64`, `Alias: Record`, Table with `Item: T`, `(Table[T]).ItemValue() T`, and `Sample(value string, counts ...int) (string, error)`. Struct rows omit synthetic bodies; receiver methods remain flat. |
| File Structure popup and navigation | Actual Cmd+F12 retains those signatures/types. End and Return on Sample navigate to **14:6**. |
| Dirty Structure signature and Undo | An unsaved appended function appears as `DirtySymbol(label string) (string, bool)` in the actual visible row and screenshot. Document-symbol request **103** follows didChange; disk remains original. Cmd+Z removes the text and visible symbol and restores the editor to **14:79**. |
| Unicode field navigation | Double-clicking `名称: string` opens the field at **6:2**. |
| Native Go test | Ctrl+Shift+R at TestNavigationFixture shows **one test passed**. The console runs Go 1.24.3 `go test -json -run ^TestNavigationFixture$ .`, displays RUN/PASS output and exits 0. **Cache status was not independently captured; no uncached claim is made.** |

After normal Cmd+Q and Confirm Exit, CUA observed the app quit. Root checks find
all six recorded owned PIDs absent, including the native-test process, and all
thirteen final fixture hashes equal their initial values. The final IDE session
log contains no ERROR/SEVERE entries; this is not a warning-free claim. The closed
trace contains **333 records / 206,696 bytes**, SHA-256
`e4e8cd52059043f7145a19e95c1e7f502cf7808bfcd46801a4e6a5005fb455d9`.

Two attempts to target the test declaration through AX reported an offscreen
element. A screenshot-guided click on the visible declaration followed by the
standard shortcut dispatched the actual test; the failed targeting attempts are
not test failures. Hierarchy selection was checked through keyboard/native selected
text rather than assuming a row click had selected the intended item. Only the
owned fixture was trusted, without trusting its parent folder.

Independent replay matched **140 request/response pairs**: 136 client requests and
four server requests, with no duplicate IDs within a direction, orphan responses,
unanswered requests or response errors. All **48 hierarchy/document-symbol queries**
have replies. All **nineteen document events** replay with valid UTF-16 ranges and
versions. Request 40 uses version-2 client text at UTF-16 document offset 111;
request 103 returns DirtySymbol with its signature from version 2, while request
118 uses restored version 3 and no longer returns that symbol. The protocol proves
those source/result transitions; the root's separate native observations establish
the visible row, selection and Undo action.

The audit records **22 versioned diagnostic events**, seven nonempty reports with
seven severity-2 warning items; no severity-1 diagnostic item is observed. This is
not a diagnostic-free result. The final IDE session log has zero ERROR/SEVERE
matches but retains two WARN lines and informational exception strings; it is not
a claim that every log message is clean.

All nine last client document texts match disk. The final independent checks also
recompute all thirteen fixture hashes, 343 captured inputs and 32 manifest
artifacts, and find all six recorded PIDs absent. Only **four didClose messages
for nine didOpen messages** were observed, and no protocol `shutdown` or `exit`
message appears. Process absence does not establish a complete document-close or
server shutdown handshake. Client versions, hashes and corresponding replies are
not acknowledgements that the server consumed every source.

The native record retains the closed protocol audit SHA-256
`50e010acb3dff30f60de14eff075b1e9dd4aee7500fd65c19ca00d531e01a2ab`
and final independent-check record SHA-256
`4cc80c011e2091b6bcc3d9dddfc35900197560f6c430ceb9793e7b495b29406c`.
It attributes native UI/test observations, offline wire replay and final
process/disk checks separately. The protocol analyzer does not itself operate the
UI or prove visual rendering from a reply alone.

## Remaining boundaries

- The repaired native workflows and their independent audit cover the listed
  cases. These bounded results do not close LANG-03 or standalone delivery as
  a whole.
- Call-scope filtering, call-site occurrence ranges/counts, pinning, retained tabs,
  every refresh/cancel/disposal interleaving and every SDK/build/OS context remain
  outside these results. Upstream call mapping still discards call-site ranges.
- Live markers repair valid nodes' positional movement. They are not a complete
  dependency-semantic epoch, a proof about all in-flight old replies, or a full-source
  server acknowledgement. Changed/deleted symbols must not be inferred valid from
  an unchanged spelling alone in every possible semantic context.
- Type hierarchy remains the upstream subtype/supertype views; the combined view
  is not implemented. Generic matching follows each actual backend query rather
  than an assumed universal candidate set.
- This historical presentation change used document symbols over flat lexical Go
  PSI. Receiver grouping, package Structure and export/private controls were not
  established by that cohort; see the subsequent [package Structure record](structure-package.md).
  Complete generic parameter display and every GoLand option remain open. No
  missing type information is invented.
- Commercial descriptors and public screenshots define reference evidence only.
  They do not establish commercial runtime defaults, full behavior equivalence or
  closure of any of the 36 complete acceptance requirements.
