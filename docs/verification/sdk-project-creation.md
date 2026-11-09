# Native Go projects and SDK management

The `83af9528d8acc37274c798e4d6a68403843960f0` source adds a Go-only
standard **New Project** flow and a shared SDK selector in **Settings > Go**.
The welcome action and **File > New > New Project** open the same native dialog.
It supports project name/location, module path, environment variables, optional
runnable examples and tests, and the choice of the bundled, local or downloaded
SDK. Cancel returns without creating the requested parent or project.

The selector displays the verified SDK version and home. **Add Local SDK**
probes the chosen Go home in a background task before registration. Removing
an SDK registration forgets the entry; it does not delete SDK files. An explicitly
configured invalid SDK remains visible rather than silently selecting a fallback.
The bundled SDK is stored as an automatic selection so the installed product can
move to a different directory.

**Download Go SDK** obtains the current stable catalogue from the official Go
download service, using the IDE network/proxy configuration. It selects an
archive for the host platform, checks the exact streamed size and SHA-256,
extracts into owned staging, and verifies the resulting SDK before publishing
and registering it. Existing destinations are not overwritten. Cancellation and
rollback retain unrelated or changed files. Network, archive, filesystem and
process work runs off the event dispatch thread. Archived and prerelease SDK
selection remains outside this implementation.

Five Java-specific project/module creation action IDs are removed from the
assembled workbench; this is an action-level change. Go creation, Open, Clone,
and development-service authentication remain available.

## Creation and startup ownership

New Go Module verifies the selected executable in a background task. A successful
exit code alone is insufficient: `/usr/bin/true` previously produced a reported
success with no `go.mod` in an independent JVM reproduction. The probe now checks
`go version`, structured `go env -json GOROOT GOVERSION`, and the reported SDK's
actual `bin/go` and `src/runtime`. That SDK binary must independently report the
same identity. Later initialization uses the verified binary, including when the
original selection was a shim.

Creation runs in an owned staging directory. After `go mod init`, the verified
SDK parses `go.mod` through `go mod edit -json` and checks its complete syntax and
requested module path. Empty-project mode has the same requirement. Only then
is a new destination reserved; existing paths are never replaced. The new
project receives its verified SDK and environment through the actual native
project-opening lifecycle.

The default opener passes the original `OpenProjectTask` directly to
`ProjectManagerEx.openProject`. The generic text-editor opener can reconstruct
that task and discard `beforeInit`. Our `beforeInit` callback only stages immutable
SDK/environment data in project user data: project services cannot safely be
instantiated there. `GoProjectSettings.initializeComponent()` consumes the data
after persistent state has loaded, before the first consumer receives the
initialized service. `beforeOpen` awaits that service's initialization so the
settings also become eligible for persistence when no Go editor is opened.
Ordinary project reopening reads the saved state and does not reapply the staged
new-project values. No restricted platform project-initializer extension is used.

SDK probing and creation isolate ambient workspace/Go environment overrides,
build flags and automatic toolchain selection. This is specific to creation with
an explicitly chosen SDK; general project tasks continue to honor their captured
user environment and automatic toolchain settings. No global settings are changed.
Each command has finite output and time limits, cancellation and owned process
cleanup. Unix children use an independent process group; Windows has a process-tree
implementation. The release's Windows controls and installer checks have their
own scope in the versioned release record; this paragraph does not establish
every Windows cancellation or process-tree scenario.

## Automated evidence

The final local standalone build passed **628 Kotlin/platform tests across 122
reports**, with zero failures, errors or skips. The new SDK download controls
cover catalogue/archive validation, hostile paths and link entries, cancellation,
destination ownership, rollback and modal completion. Startup controls use the
real platform project opener, first-consumer settings, actual SDK probing/module
initialization and selected-SDK Run-command preparation, save/close/reopen and a project without an observing Go editor. Source
and package identities are retained in the
[current native record](sdk-project-management-native.json). Fresh Go race/vet
and the Windows test counts are reported separately by release platform; they
are not inferred from this local Kotlin build.

Earlier targeted regressions cover a real installed SDK, an empty project, existing-path
protection, false success, malformed/missing manifest results, nonzero exit,
large output, cancellation, timeout and descendants left by an exiting launcher.
The historical two SDK/creation test classes contained 12 tests. Both the native functional
cohort and the final toolbar bundle passed all 333 Kotlin tests, with no failures,
errors or skips, including these 12. The standalone targeted Kotlin/JUnit run
also passed all 12. Automated results are distinct from the desktop checks below.

## Current native desktop evidence

The final local independent app at `83af952` used a fresh isolated profile,
empty owned Go caches and a system-only `PATH`, with no host Go/JDK or preselected
tools. Actual UI actions verified:

- Standard welcome New Project opened the Go wizard and displayed bundled Go
  1.26.10. Cancel returned to Welcome and left the requested parent absent.
- The official catalogue supplied Go 1.27.3. Download to a path containing spaces,
  Chinese and an emoji completed; the modal closed automatically and the parent
  wizard retained its fields and selected the verified downloaded SDK.
- Creating `example.org/finalnative` wrote `go 1.27.3` in the manifest. Native
  Run used that downloaded SDK, printed `Hello, world!` and exited zero. Native
  Test used the same SDK and passed both tests, uncached, in 0.323 seconds of
  package time. There was no bundled-SDK fallback.
- Settings search for `GOROOT` selected the Go page. The chosen Go 1.27.3 and
  exact Unicode environment text were visible before normal Quit and after a
  no-argument restart. The project/editor restored without a repeated trust
  prompt. Settings were cancelled without changes.
- File > New retained Go Module and version-control creation; the five
  Java-specific project/module actions were absent. Both sessions exited
  normally, all eight known owned process instances were gone, and the profile
  log had zero `ERROR`/`SEVERE` lines.

These are executions of the local source-specific `.app`. The installer release
record separately identifies the public DMG/EXE and their platform checks.

The [published 0.1.0-preview.20261110.2 record](releases/0.1.0-preview.20261110.2.json)
binds the later `354ff70` tag: only two test fixtures changed from `83af952`.
Both cloud suites completed 630 tests with zero failures/errors. Windows skipped
12 existing OS-specific cases; Mac skipped none. The full public DMG was downloaded, mounted and copied independently;
15,135 sealed plugin/tool files and all 654 Go-plugin class files matched their
expected identities. The embedded manifest matched the CI producer's exact hash.

Actual public installed UI created a new Unicode-path module using bundled Go
1.26.10, ran Hello World with exit zero and passed two uncached tests in 0.338
seconds of package time. Project/editor and exact environment settings restored
after restart. The [public native record](sdk-project-management-public-native.json)
keeps its boundary explicit: the optional native Add Local folder dialog could
not be completed in CUA. Recovery used the application's native `exit` command;
the two exits logged modal save/disposal cancellations, so this was not an
error-free whole session or ordinary UI-Quit acceptance. No successful native
Add Local selection is inferred. Source/API review found the standard native
chooser call, without establishing a product defect from the automation result.

An intermediate `850d276` package passed its 620 automated tests but exposed
three native failures: a production action getter logged an error, a successful
download modal stayed open, and the new project reverted to the bundled SDK.
Those defects were repaired before this final candidate. Earlier native results
are not relabeled as final-package executions.

## Historical local SDK evidence

The independent application with source-cohort digest
`f6bf8d2bf4c35f9fbf234b22eab9f186b23f36400e17b61e5df957c1daee448b`,
PID `80970`, completed these checks on the owned temporary project:

- Selecting `/usr/bin/true` showed **Unable to Create Go Module** with an actionable
  request to select a Go installation's `bin/go`. The destination
  `.tools/native-module-usages-ut36oqcs/错误 SDK 不应创建 😀` was absent afterward.
- Selecting `/Users/wangshubo/.local/share/mise/installs/go/1.24.3/bin/go` created
  `.tools/native-module-usages-ut36oqcs/新建 模块 😀` with module path
  `example.org/newnative`. Its actual manifest contained `module example.org/newnative`
  and `go 1.24.3`; `main.go`, `greeting.go` and `greeting_test.go` were present.
  The new native project window was opened and only the owned fixture was trusted.
- Native `go run .` printed `Hello, world!` and exited `0`.
- Native editor **Ctrl+Shift+R** ran `TestGreeting/world` and `TestGreeting/Go`
  through `go test -json -run ^TestGreeting$ .`. Both passed, uncached, in
  `0.579s`, with exit code `0`.

The [retained native record](sdk-project-native.json) contains the rejected SDK,
created manifest, Run/Test results and the project settings persisted on normal
Quit. Those settings retain the validated SDK's actual `bin/go`.

These actions ran in the **pre-toolbar-overflow** cohort. The [build record](module-usages-build.json)
separates it from the final `766e9401…` package and native toolbar checks. Only
Go toolbar/tree classes changed; SDK/project creation classes are byte-identical.
The earlier SDK actions are not relabeled as executions of the final bundle.

## Open boundaries

The newer registry/selector, stable SDK download UI and standard native wizard
close specific implementation gaps from the historical cohort. Complete GoLand
project creation/import/management remains open: all workspace/member/vendoring
flows, GOPATH/library roots, archived/prerelease SDK choices, all environment,
permission, proxy and failure combinations, Windows interactive SDK download,
and arbitrary external-SDK cold debugging require further evidence. The current
native session exercises one downloaded macOS SDK and a runnable-example project;
it does not establish exhaustive SDK behavior or commercial parity.

The [2026.1 SDK documentation](https://www.jetbrains.com/help/go/2026.1/configuring-goroot-and-gopath.html)
is a public workflow reference. No commercial runtime result or exact commercial
wizard equivalence is inferred from it; PROJECT-01 and PROJECT-03 remain open in
the acceptance matrix.
