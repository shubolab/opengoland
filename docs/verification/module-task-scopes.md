# Go module and workspace task scopes

Tools > Go and the Modules & Toolchain panel resolve task targets from the
selected SDK's actual `go env` and active workspace. A Go file inside a module
selects that module. A workspace root with several eligible modules opens a
native target dialog without silently choosing its first member. The Go panel
also uses the selected module node as the context.

| Command | Target semantics |
| --- | --- |
| Mod Tidy | Current/selected module, or explicitly all workspace members in sequence |
| Download | Active workspace dependency graph, or the standalone module |
| Vendor | `go work vendor` for the active workspace; otherwise `go mod vendor` |
| Workspace Sync | Active workspace's `go work sync` |
| Generate, Vet, Benchmark | Current/selected module, or explicitly all workspace members |

This follows the distinction in the [Go module reference](https://go.dev/ref/mod):
Tidy changes one main module, whereas workspace commands operate on its selected
workspace graph. Module discovery uses the effective environment and supports
`GOWORK=off`, explicit workspace paths, canonical local paths and workspace
members outside the project directory. When an automatically discovered
workspace is selected, its absolute path is retained while changing to an
external member's working directory. User toolchain selection, including
`GOTOOLCHAIN=auto`, is preserved.

The native menu uses the platform Save All action before discovery because these
are real CLI commands consuming disk files. These commands can change module,
sum, vendor or generated files. They do not claim editor-transaction Undo for CLI
side effects. The console identifies every executable, argument vector and working
directory. Explicit multi-module selections stop at the first failed step. After
an executed task ends, VFS is refreshed in the background and the Go panel reloads;
responses from an older reload cannot replace newer panel state.

Target inspection uses bounded opaque identities with frozen environment and
SDK/context/module/workspace metadata. A selected request cannot substitute its
own directory. Expired/foreign selections and changed guarded input are rejected.
The native controller prepares a paused task, then checks current settings and
cached document state on the EDT before asking the engine to resume. New unsaved
Go/module documents and edits to observed documents invalidate preparation.
Resume revalidates the backend file/path guards without rerunning SDK probes.
Stop, request failures and timeouts cancel both the preparation operation and
its owned task; cancellation never reconnects an engine.

These checks define a preparation boundary. They do not freeze all project or
dependency source for an entire running CLI command, or prevent the user from
editing after a task has been released. Ordinary external-file conflict handling
still applies to CLI writes. Scope guards are not a complete semantic dependency
snapshot. The new target protocol explicitly rejects alternate `-C`, `-modfile`
and `-overlay` inputs it cannot freeze; the previous raw task RPC remains
compatible. GOPATH-only projects, complete dependency-version editing, workspace
creation/member management and full GoLand project management remain open.

The [native record](module-task-native.json) retains the actual two-module
workspace and commands. In the `f6bf8d2b…` cohort, choosing Module: one in the
native target dialog changed only its manifest; explicitly choosing all workspace
members tidied both while retaining the used cross-module dependency. Workspace
Sync, Download and workspace Vendor each exited 0. Generate All ran in both
module directories and produced matching receipts. From an explicitly focused
`one/slow/generate.go`, Generate selected Module: one automatically.

Native Stop ended that running generator with observed console status **-1**;
all three recorded task workers disappeared while the app, engine and gopls
remained alive. No generator completion marker appeared. Subsequent uncached
`TestShared` and cross-module `TestCross` runs passed through the IDE. Preparation,
dirty-document and stale-manifest rejection are separate automated platform/engine
tests; they were not all manually triggered in this walkthrough.

The final `766e9401…` package replaces the clipped Swing button row with a native
ActionToolbar. At full width all five commands are visible. A narrower panel
showed the platform overflow indicator; its painted chevron requires mouse-motion
hover, which this CUA API could not reliably exercise. Actual hover-popup command
execution remains unverified. The narrow visible Mod Tidy action retained selected
Module: two and exited 0; after restoring width, the direct Workspace Sync action
also exited 0. Both consoles showed termination and disabled Stop afterward.

The [build and cohort record](module-usages-build.json) binds the terminal final
package to all 333 Kotlin tests, zero failures/errors/skips, and the unchanged Go
engine's full race suite (271 successful test events including subtests, 155
top-level tests, nine test packages) plus vet. Only GoToolWindow.kt changed between
the functional and final toolbar cohorts; all other 469 common product/usage
classes are byte-identical. Both native cohorts exited normally and all recorded
owned processes were absent afterward. Full project-management and reference
runtime acceptance remain open.
