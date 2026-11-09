# Verified SDK for new Go modules

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
project's selected SDK is set before project initialization, so startup services
receive the verified SDK setting before they start.

SDK probing and creation isolate ambient workspace/Go environment overrides,
build flags and automatic toolchain selection. This is specific to creation with
an explicitly chosen SDK; general project tasks continue to honor their captured
user environment and automatic toolchain settings. No global settings are changed.
Each command has finite output and time limits, cancellation and owned process
cleanup. Unix children use an independent process group; Windows has a process-tree
implementation whose native behavior remains unverified here.

## Automated evidence

Targeted regressions cover a real installed SDK, an empty project, existing-path
protection, false success, malformed/missing manifest results, nonzero exit,
large output, cancellation, timeout and descendants left by an exiting launcher.
The two SDK/creation test classes contain 12 tests. Both the native functional
cohort and the final toolbar bundle passed all 333 Kotlin tests, with no failures,
errors or skips, including these 12. The standalone targeted Kotlin/JUnit run
also passed all 12. Automated results are distinct from the desktop checks below.

## Native desktop evidence

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

This does not supply a full SDK registry/version selector or SDK download UI,
integration into the standard New Project wizard, or complete GoLand project
creation/import/management parity. The native case proves one local macOS SDK and
the runnable-example path. Native empty-project creation, alternate SDK versions,
all environment/permission/failure combinations and Windows process cleanup remain
open beyond the automated cases described above.

The [2026.1 SDK documentation](https://www.jetbrains.com/help/go/2026.1/configuring-goroot-and-gopath.html)
is a public workflow reference. No commercial runtime result or exact commercial
wizard equivalence is inferred from it; PROJECT-01 and PROJECT-03 remain open in
the acceptance matrix.
