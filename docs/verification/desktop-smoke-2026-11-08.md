# Independent desktop smoke — 2026-11-08

Reference: local GoLand 2026.1.4. Reconstruction: the generated
`build/product/OpenIDEA Go.app`, bundle ID `dev.openidea.goide`.
Fixture: `fixtures/go-workspace` (two modules, local dependency, Unicode,
interfaces, generics, tests, benchmark, fuzz seed).

## First product launch

- `codesign --verify --deep --strict` passed before launch.
- The native application started from its own bundled open-source platform/JBR.
- Initial user agreement required explicit user review and approval. The user
  approved acceptance in this chat; the application then reached the project.
- Config/cache/plugin/log locations in the process log were all under this
  product's `OpenIDEA-Go` paths; normal GoLand/PyCharm profiles were not imported.
- The project tree, README, main.go editor and native desktop menus rendered.
- **Failed:** the copied extra plugin directories were not loaded by the 261
  product's modular loader. The apparent Go highlighting came from the platform's
  existing text support, not proof that our Go integration had started. Main-file
  Run was disabled and the expected Go file icon/gutter were absent.
- The application was closed through its native exit workflow. PID 9615 exited
  and `idea.log` recorded `IDE SHUTDOWN` before rebuilding the package.

## Rebuilt product, native process 11214

The second package uses the loader's supported `plugin.path` property and
removes conflicting product selector arguments. Its embedded build-input digest
is `b496a85b4b9443c980d9722ca030ac664149b77946b0db3564980c4b9d5a2c58`
(78 input files, 39 tests passing before packaging). Later source edits are not
part of these observations.

- Runtime inspection confirmed expanded paths to this app's own plugins and
  `Loaded custom plugins: Go Workbench (0.1.0-SNAPSHOT), LSP4IJ (0.21.0)`.
- Native Go icons, gutter Run actions, highlighting, and gopls inlay hints were
  visible. Cmd+B on `catalog.Greeting` opened its definition in the other module.
- The native Run toolbar executed `go run .` with the discovered Go SDK. Its
  console printed the complete Chinese/emoji greeting and `Total: 37`, exit 0.
- Running `catalog_test.go` produced the native SM test tree: six leaf tests
  passed. After showing passed tests and expanding `TestCartTotal`, the `empty`
  and `two_products` children were visible. This does not verify failures,
  skips, parallel execution, or rerun selection.
- **Failed:** root test-console aggregation duplicated some test output, while
  the selected test's own console contained it once. Source correction pending
  native regression in a new package.
- A DAP breakpoint at `main.go:15` stopped in `main.main:15`. The native debugger
  displayed `cart` and the `price` interface; expanding `cart` showed its Items
  slice. Evaluating `cart.Items[0].Price` returned `25`. F8 advanced to line 16;
  Resume completed with the expected output, exit 0, successful disconnect, and
  the debuggee's exited status.
- With trace enabled in the isolated profile, explicit Basic completion sent
  `textDocument/completion` request 172 at UTF-16 `(line=14, character=7)` for an
  unsaved `fmt.Pr`. The 1 ms response contained `Print`, `Printf`, and `Println`
  with edit range characters 5..7. Down, Down, Return inserted `Println` and
  preserved the Unicode argument. **Unknown:** the lookup list's visual layout
  was absent from the main-window AX/screenshot capture; candidate rendering
  has not passed visual acceptance. Ctrl+Space also conflicts with this macOS
  setup, so command dispatch was verified through Find Action instead.
- The native Go Coverage window discovered both modules and allowed selecting
  `example.com/openidea/catalog`.
- **Failed:** Run Coverage raised a platform thread-access assertion at
  `GoCoverageService.runSelected` / `FileDocumentManager.saveAllDocuments`.
  Plain Swing EDT execution does not guarantee write-intent read access on this
  platform version. Source correction pending a new package's native regression.
- The fixture was restored and saved with its original Unicode greeting. Native
  Exit was confirmed; PID 11214 exited before any package replacement.

These are individual workflow observations. They do not establish broad
GoLand equivalence, complete code intelligence, or debug/coverage completeness.

## Regression package, native process 13678

The next standalone snapshot used source digest
`ba97679d73bedd8ddfd440fc8190c4c8a5f1374e1ba6002b4549964f55bd358a`
(84 input files, 49 tests passing with no skips). This was still displayed as
OpenIDEA Go; the later requested rename to **open Goland** is a separate build.

- Run Coverage completed for `example.com/openidea/catalog`: 9/9 statements,
  100.0%, matching the actual Go test output. The earlier thread-access exception
  did not recur in this workflow.
- Double-clicking its result opened `catalog.go` at line 19. Green gutter
  coverage markers were visible on covered function bodies and statements.
- Running `catalog_test.go` again produced six passing leaves and exit 0. The
  root console showed each test RUN/PASS sequence once, confirming the duplicate
  aggregation correction. This run used Go's cached result.
- The app was closed through native Exit before the product rename/repackage.

Import/cancel/stale-marker cases, failed-test reruns, and the new Coverage
executor remain separate acceptance obligations.

## open Goland snapshot, native process 18599

Source digest `67b968fd59441c06ca378cae149426fb892002a7ad4b3f007b5f2c24e15ec157`
(104 input files, clean build, 67 tests passed, no failures/errors/skips).
The packaged app passed strict deep signature verification and includes the
pprof parser's Apache license notice.

- Native application/menu name is **open Goland**. Bundle identity is
  `dev.opengoland.ide`; runtime plugin paths point to `plugins/opengoland` and
  LSP4IJ inside the independent app. The live runtime verification passed.
- The platform's compiled-in application-info data initially overrides XML
  resources. The supported resource-loading property now makes the framework
  itself identify as open Goland, as confirmed in its runtime log.
- Native Run menu enabled `Run 'catalog_test.go' with Coverage`. It executed
  real `go test -json -coverprofile=… .`, then automatically opened Go Coverage
  with 9/9 covered statements and 100.0%.
- Go Profiles generated an actual CPU profile from the catalog benchmark. Its
  table displayed 10 sample records, six functions, and total CPU 1.07 s.
  `Cart.Total` had flat 620 ms / cumulative 640 ms in this particular run;
  double-clicking opened `catalog.go:19`. These sampled values are run-specific.
- Go Inspections ran Vet separately in the two modules and initially reported
  zero findings. A disposable Go file containing an invalid Unicode-bearing
  printf call produced one real finding at line 6; double-clicking navigated to
  that file and statement. Removing only that disposable file and rerunning
  restored zero findings in both modules. The probe is not part of the checkout.
- Native Exit completed before the final launcher correction/repackage.

Observed limitations: direct CLI startup immediately followed by a new-bundle
LaunchServices activation produced a second-instance DirectoryLock notice; the
first IDE process continued normally. The GUI helper is being changed to use
LaunchServices consistently, with a fresh launch/activation regression required.
The bundled platform Markdown plugin also logs a JCEF/proxy-service initializer
assertion (present in earlier snapshots as well); the IDE continues, but this
upstream lifecycle issue remains unresolved. Long leaf tests currently receive
their SM node only when the converter can determine leaf/suite ownership at
completion. The broader acceptance matrix remains open.

These observations do not mark the product-level acceptance matrix complete.

## Final launcher regression

Final source digest `6a91cf0399dccb6e6950e6a99bca33edf587270a538469ab8c4e7feacc4bce89`
changes only the GUI launch helper and removes trailing whitespace from the
preceding native workflow baseline. All 67 Kotlin tests and package/signature
checks passed again. LaunchServices started native PID 19805; a second helper
invocation and CUA activation retained that same single process and showed the
project window. The live name/bundle/plugin verification passed. The broader
Markdown lifecycle issue and feature acceptance gaps above remain open.

## Native editing, attach and live tests — snapshot 54b2629

Source digest `54b2629ccd9a7a9d75880aa4219318b4170c886e667bc300099aa348a1930532`
(128 input files, 114 Kotlin tests passing, no skips). Packaging and strict deep
signature verification passed. Native PID 25176 first exercised Rename; PID
25474 subsequently exercised tests, Markdown and local attach in the same build.

- Standard Shift+F6 opened our native Go Rename input. **Failed:** cross-module
  rename refused unopened `catalog_test.go`: gopls returns explicit version 0 for
  disk files, which the first transaction implementation required to have an
  open-document mirror. No edits were applied. This is corrected and rechecked
  in the following snapshot.
- A disposable blocking test produced a visible native
  `TestNativeLiveProgress, Running` node and its Chinese/emoji log before the
  release gate was opened. After release, dynamic `dir/file` and parallel children
  completed; the suite had eight passing leaves, exit 0, with the log appearing
  once. **Failed:** the native duration was 0 ms despite a 12.66 s parent wait;
  automatic leaf aggregation omitted the real Go parent's elapsed time.
- Startup logged proxy warmup completion before the application starter.
  Opening README rendered the native Markdown split preview and JCEF initialized
  with its sandbox enabled. The earlier JCEF/proxy initializer assertion did not
  recur in this process.
- A temporary native Go Attach to Process configuration loaded the owned target
  PID 25838 and adapter working directory. Debug launched only adapter PID 25919,
  attached to the existing target, exposed six goroutines and `main.main:15`, and
  evaluated `cart.Value` to 2640 in the selected frame. This value is run-specific.
- Native Run > Stop printed `Detaching without terminating target process`,
  adapter exit 0, and successful disconnect. OS identity plus the heartbeat
  advancing from 2640 to 3567 verified the target continued running. Only that
  owned fixture was then terminated; its temporary run configuration was removed.
- **Open defect:** attached Variables remained at `Collecting data`. Pasting the
  watch expression also logged a DAPExpressionCodeFragment Document/PSI mismatch,
  although evaluation returned a value. The compatibility diagnosis and pending
  fix are in `dap-expression-compatibility.md`. Remote UI and attached breakpoint
  interaction were not established by this smoke.

## Closed-file rename and duration regression — snapshot 867caa4

Source digest `867caa4dcb8c464df254c6ed510cc086f3d9c52e67be8ccfcc924f0b963e730c`
(128 input files, **119 Kotlin tests, zero failures/errors/skips**). Go race tests
and vet passed for the unchanged engine. Packaging, plugin structure and strict
deep signature checks passed. Native PID 26876 passed the live identity/plugin
verification.

- Shift+F6 on `catalog.Greeting` in `app/main.go` produced a native Diff preview
  for that open file plus the closed `catalog.go` and `catalog_test.go` files.
  Apply changed the definition and all three references, preserving the Chinese
  and emoji content. Saving confirmed the four replacements in all three files.
- A single Cmd+Z restored **all three files exactly** (`git diff --exit-code`
  passed). A single Cmd+Shift+Z restored all four replacements. A final single
  Undo and Save restored the original fixture again. This establishes this
  symbol-rename workflow, not package rename or every resource-operation case.
- Code > Optimize Imports was enabled for Go. After inserting an unused `os`
  import into the unsaved editor, the standard action removed it and retained
  the existing imports and Unicode source. Saving left the original file bytes.
- A fresh, deliberately uncached blocking Go run exposed immediate running
  progress again. After release, the native result showed eight passing leaves,
  **11 s 828 ms** total, parent Go output **10.43 s**, package output **11.173 s**,
  and exit 0. Root time includes the Go process's compilation/setup overhead;
  parallel child durations are not summed into an inflated wall duration.
- The disposable test file and release gate were removed after closing that
  editor. The IDE was returned to the original `app/main.go`.

The expression/Variables defects above remain open. Case-only resource renames
on case-insensitive filesystems and other transaction restrictions are recorded
in `workspace-edit-architecture.md`. The broad acceptance matrix remains open.


## Later debug-recovery snapshot

The later `7b201d1` packaged snapshot has 149 passing Kotlin tests and native
local attach evidence for Variables recovery, Unicode Watch editing, actual
field modification and stepping. Native external DAP configuration, mapped
source/breakpoint navigation, Unicode Watch evaluation, and a running-target
detach also passed on loopback. A headless multi-client server passed native
connection, mapped frame/Watch inspection, and preservation of the paused
session on disconnect; its native breakpoint interaction remains open. These
checks supersede the open expression/spinner
status above for that specific workflow. Exact build identity, observed target
lifecycle and remaining immediate-refresh/paused-Stop limits are recorded in
[native-debug-recovery.md](native-debug-recovery.md). The broad acceptance
matrix remains open.


The later `a00249f` review build passed 149 tests and repeated local native
Variables/Unicode Watch and actual-breakpoint field-edit/step checks. It also
exposed an entry-frame value-edit failure and a per-file final-breakpoint
removal failure. Both are retained in the debug-recovery record; the subsequent
removal correction has separate packaged native evidence below.


## Per-file breakpoint regression — snapshot 3331c4e

Source digest `3331c4eeef198e3f141faffa74796b1c1e7f3eaf6200809d66f136e9d8974f34`
(142 input files, **154 Kotlin tests, zero failures/errors/skips**). Build,
packaging, plugin structure, strict deep signing, and unchanged-engine Go race
and vet checks passed. Native PID `40389` passed live identity/plugin checks.

With a separate source's breakpoint retained, removing fixture line 7 emitted
the required empty per-source `setBreakpoints` replacement and preserved the
other source's line 15 request. Resume advanced the owned target's heartbeat
before Stop, without re-hitting the deleted fixture breakpoint. Native Stop
closed adapter PID `40512`; target PID `38601` survived and its heartbeat kept
advancing. The exact requests and lifecycle observations are in
[native-debug-recovery.md](native-debug-recovery.md#per-file-breakpoint-correction--native-process-40389).

The large-file gopls full-semantic-token error recurred while opening runtime
`proc.go` (214687 bytes against its 100000-byte limit). It remains open, along
with the other explicitly recorded debug/language limitations and the broad
acceptance matrix.
