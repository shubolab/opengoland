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
open "build/product/open Goland.app" --args "$PWD/fixtures/go-workspace"
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
```

The direct-tool probe uses disposable copies of the fixture and exercises real
gopls and Delve. Its success establishes the tools' protocol behavior, not the
desktop integration. A separate native smoke has verified plugin loading,
cross-module definition navigation, program execution, passing subtest trees,
completion candidate insertion, and a breakpoint/variables/evaluation/step/resume
workflow. Coverage and test-console defects found in that package are recorded
with their source snapshot; fixes need native regression before being counted.

- [Reference investigation](docs/reference/README.md): exact local versions,
  selected resource declarations, file/member digests, and evidence limitations.
- [REA evidence ledger](docs/reference/rea-ledger.md): retained shipped-artifact
  identities and native observation limitations.
- [Product acceptance matrix](docs/reference/acceptance-matrix.json): full target
  requirements and verification state. A declaration or compile result cannot
  satisfy a runtime requirement.
- [Direct Go tool verification](docs/verification/go-tools.json).
- [Native desktop observations](docs/verification/desktop-smoke-2026-11-08.md).
- [Engine protocol and tests](engine/README.md).
- [Pinned platform input](product/platform.lock.json).

The open-source platform provides the workbench/editor foundation. LSP4IJ
provides open-source LSP/DAP integration, gopls Go language analysis, and Delve
debugging. GoLand-specific semantic and engineering behavior must be reproduced
and verified independently. The product retains upstream notices and does not
include the proprietary GoLand Go plugin.
