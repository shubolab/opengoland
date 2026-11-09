# open Goland

[Source repository](https://github.com/shubolab/opengoland)

An independent desktop IDE for Go, implemented in Kotlin on the open-source
IntelliJ Platform. The product reference is the locally installed GoLand
2026.1.4. GoLand and PyCharm installations are reference applications, not
components of the generated product.

**Development is active. This repository is not yet a complete GoLand replacement.**
The full target includes the native workbench and editor, Go project/SDK/module
workflows, code intelligence and refactoring, run/test/coverage/benchmark/fuzz,
debugging and goroutines, profiling, terminal, Git, and supporting engineering
tools. Runtime and feature parity remain subject to the acceptance matrix.

## Build and launch

Prerequisites: macOS Apple silicon for the current app package, Go 1.24 or later,
Python 3, and network access on the first build. The project pins Gradle,
LSP4IJ, and the official open-source IntelliJ IDEA 2026.1.4 distribution. The
platform bootstrap verifies the published SHA-256. No global IDE installation
is modified.

```sh
./scripts/bootstrap-platform.sh
./scripts/build.sh
./scripts/dev.sh "$PWD/fixtures/go-workspace"
```

Build the independent app, including its own platform, JVM, plugins, and Go
engine:

```sh
./scripts/package-product.sh
./scripts/launch-product.sh "$PWD/fixtures/go-workspace"
```

The packaged product uses separate configuration, cache, plugin, and log paths
under `OpenIDEA-Go`. It does not require an installed GoLand or PyCharm. Initial
distribution, signing, platform startup, and native workflow checks are still
in progress; consult current verification evidence before relying on a build.

The display name is `open Goland`, the Gradle project and CLI are `opengoland`,
and the macOS bundle ID is `dev.opengoland.ide`. Existing internal plugin IDs and
the isolated `OpenIDEA-Go` settings directory are retained for configuration
compatibility. The local checkout directory does not affect the product name.

Install `gopls` and `dlv` with your selected Go SDK if they are not installed:

```sh
go install golang.org/x/tools/gopls@v0.21.1
go install github.com/go-delve/delve/cmd/dlv@v1.24.2
```

Settings > Go selects tool executables and project build environment. Tools > Go
contains module/workspace and engineering actions. File > New > Go Module
creates a module through the actual Go toolchain. Go Build, Go Test, and Go
Debug configurations use the platform's native execution and debugger UI.

Run > Edit Configurations > **+** also provides these attach configurations:

- **Go Attach to Process**: enter the PID of an existing local Go process and
  the local adapter working directory, then Debug the configuration. The selected
  Delve executable starts a local DAP adapter. Attaching does not rebuild the
  target or change its environment; build the target with debug symbols, for
  example `go build -gcflags='all=-N -l'`, when needed. Stop requests a
  non-terminating detach and closes the local adapter. A native check found
  that stopping while paused could leave the surviving target OS-stopped;
  resume before Stop when the process should keep running. The current handler
  now waits for non-terminating detach before adapter cleanup, and one packaged
  entry-paused Stop check restored the target heartbeat. The historical
  intermittency remains open; see [Stop ordering evidence](docs/verification/debug-stop-ordering.md).
- **Go Remote Debug (DAP)**: enter the DAP host and TCP port reachable from the
  IDE, including through an existing tunnel. Select the server type explicitly.
  For **dlv dap — attach to process ID on server**, start an external
  `dlv dap --listen=127.0.0.1:38697` and enter the PID on that server's machine.
  Stop detaches from the target; the single-use external adapter normally exits.
  For **Headless Delve — existing session (DAP)**, the server already owns the
  debugging session, so no PID is entered in the IDE. Start that server with
  `--accept-multiclient`, for example:

  ```sh
  dlv --headless --accept-multiclient --listen=127.0.0.1:38697 exec ./server
  ```

  Stop disconnects from a multi-client headless server and preserves the target's
  current running or paused state. Resume before Stop if the target should keep
  running. A single-client headless server can terminate a target it launched,
  even when the client requests a non-terminating disconnect. These connections
  use DAP; generic JSON-RPC endpoints are unsupported.

Both configurations accept source mappings in the direction **local IDE prefix
=> remote server prefix**, one per line, for example
`/Users/me/project => /srv/project`. The mapping translates local breakpoint
paths to server paths and returned stack locations back to local sources.
The native debugger uses DAP threads for Go goroutines and evaluates Watches in
the selected stack frame. The three attach protocol scenarios have passed the
direct-tool probe below. Native local PID attach, goroutine/frame selection,
evaluation, and Stop leaving the target alive have also been exercised. Remote
`dlv dap` configuration UI, mapped stack/breakpoint navigation and running-target
detach have since passed a native loopback check. Native headless configuration,
mapped frame navigation, Unicode Watch evaluation, and preservation of a paused
multi-client session also passed on loopback; native headless breakpoint
interaction remains open. A later packaged native check also verified local
Variables recovery, Unicode Watch editing, field modification and stepping;
see [native debug recovery](docs/verification/native-debug-recovery.md) for exact
build evidence and the remaining immediate-value-refresh, entry-frame value-edit,
and paused-Stop limitations. The `3331c4e` package passed a native wire/heartbeat
check for removing a file's last breakpoint while another file retains one.
The newer semantic-token bridge partitions large Go files into bounded requests;
real gopls integration and native SDK `runtime/proc.go` display checks have passed.
See the [large-file evidence](docs/verification/large-file-semantic-tokens.md)
for the exact snapshots and remaining native cancellation checks.

Code > Optimize Imports uses gopls through the standard native action. Standard
Rename opens a cross-file Diff preview; approved edits use a single global
Undo/Redo command. Refactor > Change Signature provides native parameter/result
tables and a typed Go engine planner for name, parameter, result, call and
interface changes, including explicit defaults and migrations. It preserves
evaluation order and previews the complete file/usage group before applying.
Native cross-module, interface, added-parameter/result and Undo/Redo checks have
passed; [implementation and boundaries](docs/verification/change-signature-implementation.md)
describe conservative conflicts, UI gaps and build-context limitations.
Refactor > Go Refactor also offers gopls refactorings for the selection.

Code > Generate provides Empty test file, Test for function, Tests for file, and
Tests for package. The native dialog selects declarations, same/external package,
test names, and concrete generic types. A typed Go planner validates the full
candidate before a cross-file Diff preview; applying uses one global Undo/Redo
command. Standard Test/Subject navigation is integrated. See the
[test-generation evidence and boundaries](docs/verification/test-generation.md).

Code > Implement Methods and the native **Implement interface** inlay open a typed
receiver/interface chooser. It supports existing local types or a new struct,
pointer/value receivers, generic interfaces, and all or selected missing methods.
The complete candidate is checked before Diff preview and grouped Undo/Redo.
Local aliases and test-package mocks are supported. See the
[interface-generation evidence and boundaries](docs/verification/interface-generation.md).

Code > Generate > Struct Tags and **Edit struct tags** under Alt+Enter provide
field selection, custom tag keys, naming styles, and add/update/remove operations.
Other keys, explicit ignore values, and existing options are retained by default.
The typed planner previews a complete candidate before one global Undo/Redo
transaction. This bulk editor is an independent workflow; GoLand's documented
multi-caret tag-value editing remains a separate parity item. See the
[struct tag evidence and boundaries](docs/verification/struct-tags.md).

Code > Generate also offers native **Constructor**, **Getter**, **Setter**, and
**Getter and Setter** dialogs for named structs, with field selection, editable
names, generic types, pointer/value choices, typed validation and global Undo.
Alt+Enter on a field defaults to that field. **Edit tag values for key** adds
native value-start carets for simultaneous input. See the
[struct helper evidence](docs/verification/struct-helpers.md) and
[multi-caret boundaries](docs/verification/struct-tag-carets.md).

Code > Generate > **Type from JSON** opens a native JSON editor with a type
name and numeric policy. **Struct Fields from JSON** and the struct intention
add selected new fields while retaining existing fields and tags. Analyze /
Refresh shows inferred fields, nested types and warnings; Preview opens the
validated native Diff before Apply. Edits to the input invalidate the analysis.
See [JSON generation and inference boundaries](docs/verification/json-generation.md).

Workspace edits check actual document versions and unsaved changes,
with a separate verified-disk baseline for closed files. Consult the
[transaction boundaries](docs/verification/workspace-edit-architecture.md)
for unsupported resource-edit cases and checks still awaiting native evidence.

The native Go Test configuration has `gotest`, `gobench`, and `go test -fuzz`
frameworks. Benchmark settings include exact patterns, duration/iteration limits,
and allocation reporting. Fuzz settings include a function pattern, time/iteration
limit, minimization limit, and worker count. Empty ordinary-test patterns exclude
unrelated tests in Benchmark/Fuzz modes. Fuzz Debug executes seed corpus without
mutation workers. Saved failing inputs open from console links as corpus data;
supported filenames offer exact seed Run/Debug. See the
[test workflow evidence and boundaries](docs/verification/native-test-workflows.md).

Go Test also supports the native Run with Coverage executor. Go Coverage shows
statement totals and editor markers. Go Profiles generates CPU/memory/block/mutex
pprof files, displays flat/cumulative hotspots, and opens recorded source locations.
Go Inspections runs Vet or golangci-lint across one module or the workspace, with
clickable diagnostics. Configure the optional golangci-lint executable under
Settings > Go > Go Inspections.

## Verification and evidence

```sh
./scripts/build.sh
(cd engine && go test -race ./... && go vet ./...)
python3 scripts/verify-go-tools.py
python3 scripts/verify-go-attach.py
```

The direct-tool probe uses disposable copies of the fixture and exercises real
gopls and Delve. Its success establishes the tools' protocol behavior, not the
desktop integration. A separate native smoke has verified plugin loading,
cross-module definition navigation, program execution, passing subtest trees,
completion candidate insertion, and a breakpoint/variables/evaluation/step/resume
workflow. Later snapshots have native coverage, profiling, inspections, immediate
long-test progress, import cleanup, and cross-module rename with one-step
Undo/Redo evidence. Each observation is tied to its actual source snapshot;
these checks do not establish full product equivalence.

The current verification batch passed all 404 Kotlin/platform tests across 91
reports, with zero failures, errors or skips. The Go race run passed 271 test
events including subtests (one package cached, eight fresh), and Go vet passed.
All engine inputs remained unchanged through the final navigation repair.
It includes typed test/interface/struct-helper/tag/JSON generation, native tag carets,
Unicode project-path persistence, dirty-source and two-file native
Undo/Redo, stale dependency rejection, named Test/Subject chooser navigation,
clickable interface inlays, immutable settings/connection ownership, and actual
upstream LSP initialization/deletion/UTF-16 synchronization regressions. Native evidence includes
an unsaved go.work adding an unopened external caller, stale-preview rejection,
three-file interface migration, and combined name/parameter/result conversion.
The [native records](docs/verification/change-signature-native.json) retain
fixture sources, outputs and the actual build identities.

The current [JSON generation cohort](docs/verification/json-generation-native.json)
also passed two uncached Go round-trip tests through the standalone IDE.
A separate [native multi-caret trace](docs/verification/multicaret-gopls.md)
records actual gopls hover and versioned error/Undo/Redo results, including the
normal production scheduling path and its remaining coverage limits.

The latest [module/workspace task checks](docs/verification/module-task-scopes.md)
cover explicit module targets, workspace download/vendor/sync, sequential generation,
and native Stop with owned-process cleanup. [New Go module creation](docs/verification/sdk-project-creation.md)
rejects a non-Go executable and creates a real SDK-validated project that passes
native Run/Test. [Find Usages](docs/verification/find-usages.md) now filters the
requested scope and retains cross-file references; real two-module navigation and
Current File filtering were observed. The native toolbar's hover-popup interaction
remains open. A later [Find Usages policy check](docs/verification/find-usages-policy.md)
removes unrelated type-definition/implementation queries while retaining real
reference errors and explicit LSP navigation. Standard platform implementation
and type-declaration actions did not dispatch queries in that older desktop check.
The later [standard navigation repair](docs/verification/standard-navigation.md)
connects the real platform actions, native multi-target choosers and Type
Declaration. The final standalone package passed Unicode and selected-SDK
navigation, unsaved target-only movement, third-file semantic changes, and
Undo/Redo queries with fresh actual gopls requests. It also passed a native Go
test and exact-label Go Settings search. Hierarchy, Structure and full navigation
parity remain open.
[Build identities and cohort boundaries](docs/verification/module-usages-build.json)
keep these checks separate from earlier delivery evidence and full IDE acceptance.

The [native Go typing batch](docs/verification/editor-typing.md) adds quote
pairing/overtyping, comment continuation, Go Enter/closing/case indentation and
the raw-string indentation preference. Real standalone keyboard actions,
Undo/Redo and two uncached Go tests were observed in a Unicode-path workspace.
The [typing build record](docs/verification/editor-typing-build.json) identifies
the exact 332-input package; lexer/PSI, IME, full performance and commercial
runtime equivalence remain bounded as documented.

The attach probe starts only its own temporary fixtures and adapters. It verifies
local PID attach, external `dlv dap` PID attach, and headless multi-client DAP
attach, including source mapping, goroutines, Watches, target survival after
detach, and cleanup of its owned processes. Native local configuration/Stop
evidence is recorded separately from the direct protocol results.

- [Reference investigation](docs/reference/README.md): exact local versions,
  selected resource declarations, file/member digests, and evidence limitations.
- [REA evidence ledger](docs/reference/rea-ledger.md): retained shipped-artifact
  identities and native observation limitations.
- [Product acceptance matrix](docs/reference/acceptance-matrix.json): full target
  requirements and verification state. A declaration or compile result cannot
  satisfy a runtime requirement.
- [Direct Go tool verification](docs/verification/go-tools.json).
- [Direct DAP attach verification](docs/verification/go-attach.json).
- [Native desktop observations](docs/verification/desktop-smoke-2026-11-08.md).
- [Native debug recovery](docs/verification/native-debug-recovery.md): attached
  Variables, Unicode Watches, field modification, stepping, external DAP source
  mapping and detach, and remaining limits.
- [Native test/benchmark/fuzz workflows](docs/verification/native-test-workflows.md).
- [Native test generation](docs/verification/test-generation.md).
- [Native interface-method generation](docs/verification/interface-generation.md).
- [Deletion lifecycle controls](docs/verification/lsp-deletion-lifecycle.json).
- [Initialization document synchronization](docs/verification/lsp-document-sync.md).
- [Standard navigation, cache invalidation and Settings search](docs/verification/standard-navigation.md).
- [Attach Stop ordering](docs/verification/debug-stop-ordering.md).
- [Engine protocol and tests](engine/README.md).
- [Pinned platform input](product/platform.lock.json).

The open-source platform provides the workbench/editor foundation. LSP4IJ
provides open-source LSP/DAP integration, gopls Go language analysis, and Delve
debugging. GoLand-specific semantic and engineering behavior must be reproduced
and verified independently. The product retains upstream notices and does not
include the proprietary GoLand Go plugin.

The packaged OSS platform includes a pinned public-source fix for supplementary
Unicode in persisted map keys. An emoji project path now survives trust/recent
project serialization and a normal restart. This preserves explicit trust
decisions; it does not infer or migrate historical truncated paths. See the
[Unicode persistence evidence](docs/verification/unicode-persistence.md).
