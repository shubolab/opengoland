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
