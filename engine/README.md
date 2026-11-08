# open Goland engine

The engine is an independent Go tool runner for the Kotlin IDE. It does not use
the installed GoLand plugin or invoke a shell. It requires Go on `PATH` (or an
explicit Go executable at initialization). `gopls` and `dlv` are detected but
their LSP/DAP sessions are owned by the IDE integration layer.

```sh
cd engine
go test -race ./...
go build -o bin/opengoland-engine ./cmd/opengoland-engine
```

## Wire contract

The executable reads JSON-RPC 2.0 requests from stdin and writes responses and
notifications to stdout. Each message uses UTF-8 byte-based framing:

```text
Content-Length: <byte length>\r\n
\r\n
<JSON body>
```

Stdout contains protocol messages only. Framing limits are 8 KiB for headers and
16 MiB for a body. Request IDs can be strings or numbers. Notifications have no
`id`. Clients must continuously drain stdout while requests are active.

| Method | Params | Result |
|---|---|---|
| `initialize` | `{root, goExecutable?}` | Capabilities, project metadata, tools |
| `project/info` | `{}` | Refreshed project metadata |
| `tools/status` | `{}` | Array of Go/gopls/Delve executable status |
| `task/start` | `{id, command, args?, cwd?, env?}` | `{id, accepted:true, steps:[{cwd,args}]}` |
| `task/cancel` | `{id}` | `{id,cancelled}`; false when no task with that ID is active |
| `shutdown` | `{}` | `null`, after owned tasks have been cancelled; process then exits |
| `exit` notification | `{}` | Closes the transport and cancels owned tasks |

`initialize` may run once per engine process. Each instance owns one project.
The task ID is a nonempty string of at most 256 characters, separate from the
JSON-RPC request ID. A live task ID cannot be reused. A task-start response is
written before that task's first event. Independent requests can complete out of
order, and cancellation can be processed while metadata requests are pending.

Initialization result:

```json
{
  "name":"opengoland-engine",
  "version":"0.1.0-dev",
  "protocolVersion":1,
  "root":"/project",
  "os":"darwin",
  "arch":"arm64",
  "commands":["build","run","test","vet","tidy","download","generate","benchmark","coverage","list","workSync"],
  "features":{"streamOutput":true,"cancelProcessGroup":true,"testEvents":true,"coverageProfile":true,"workspaces":true},
  "project":{},
  "tools":[{"name":"go","available":true,"path":"/sdk/bin/go","version":"go version ..."}]
}
```

`project` and `project/info` use this shape:

```json
{
  "root":"/project",
  "name":"project",
  "environment":{"GOROOT":"/sdk","GOVERSION":"go1.24.3","GOWORK":"/project/go.work"},
  "modules":[{"Path":"example.org/app","Main":true,"Dir":"/project/app","GoMod":"/project/app/go.mod","GoVersion":"1.24.0"}],
  "moduleRoots":["/project/app"],
  "workspace":"/project/go.work",
  "warnings":[]
}
```

`environment` is the real `go env -json` object. `modules` contains concatenated
`go list -m -json` records; its field casing follows the Go command. Module
records may additionally have `Version`, `Replace`, or `Error`. `workspace` is
omitted when no workspace is active. Warnings retain failures to read metadata;
an ordinary directory can still be initialized before it has a `go.mod`.

## Tasks and events

Commands map to argument vectors, never interpolated shell commands:

| Command | Go command prefix | Default target |
|---|---|---|
| `build` | `go build` | `./...` |
| `run` | `go run` | `.` |
| `test` | `go test -json` | `./...` |
| `vet` | `go vet` | `./...` |
| `tidy` | `go mod tidy` | none |
| `download` | `go mod download` | none |
| `generate` | `go generate` | `./...` |
| `benchmark` | `go test -json -run=^$ -bench=.` | `./...` |
| `coverage` | `go test -json -coverprofile=<owned cache path>` | `./...` |
| `list` | `go list -json` | `./...` |
| `workSync` | `go work sync` | none |

Explicit `args` replace the default target and are appended after the prefix.
The client must include a target such as `./...` when it supplies flags and
wants recursive scope. JSON and coverage output flags are engine-managed and
cannot be overridden. `env` overlays the inherited environment, including SDK,
build tags through `GOFLAGS`, `GOOS`, `GOARCH`, and `CGO_ENABLED` settings.

A working directory may be absolute or project-relative. It must resolve inside
the project or a module explicitly declared in the current Go workspace.
Symlinks are resolved before checking confinement. `project/info` refreshes the
declared module roots. With no explicit args, workspace-root build/test/vet/list/
generate/benchmark commands visit every module when the root has no `go.mod`.
Coverage requires selecting one module in a multi-module workspace; each run
returns its own real profile, which can be imported by the IDE.

`vendor` first queries `go env -json GOWORK` using the selected SDK and task
environment. An enabled workspace runs **one** `go work vendor`; otherwise it
runs `go mod vendor`. It does not fan out across modules or silently set
`GOWORK=off`. An explicit `GOWORK=off` requests module mode. Both tool invocations
belong to the cancellable task process group and appear as `task/step` events.

## Performance profiles

Generate one concrete package with the same `task/start` lifecycle:

```json
{"id":"cpu-1","command":"profile","cwd":"/project/app","args":["-benchtime=2s"],"env":{"GOFLAGS":"-tags=integration"},"profile":{"kind":"cpu","mode":"benchmark","target":".","pattern":"BenchmarkCart"}}
```

Kinds are `cpu`, `memory`, `block`, `mutex`; modes are `test` and `benchmark`.
`args` contains additional Go test options. Output flags and `-args` are managed
by this workflow. Each run uses `-count=1`, with benchmark mode additionally
selecting `-run=^$`. Memory, block and mutex use sampling rate 1. The profile and
test binary live in a unique user-cache directory, never the source directory.
Completed tasks add `profile:{path,kind,binary}` to `task/exit`; failed tests can
still produce a usable profile. Cancellation removes partial profile artifacts.

`profile/inspect` takes `{path,sampleIndex?,sort?:"flat"|"cumulative",limit?}`.
It reads an explicitly selected regular file (including imports outside the
project) and returns `sampleTypes:[{type,unit,total}]`, selected `sampleIndex`,
sample count, duration/time, `rows:[{name,systemName?,file?,line,flat,cumulative,
flatPercent,cumulativePercent}]`, function count, truncation and warnings.
Limits default to 2,000 rows and cap at 5,000. Compressed and expanded input must
each fit within 64 MiB. Damaged protobuf and out-of-range metrics return RPC
errors. Samples remain in their original units.

Parsing uses the Apache-2.0 official `github.com/google/pprof/profile` package,
pinned to the same revision bundled with Go 1.24.3. Source locations come from
protobuf Function/Line records. Inline leaf samples and recursive cumulative
values follow pprof semantics; the official DropFrames/KeepFrames policy is
applied. No human-readable pprof table is used to infer source paths. Native
navigation uses exact recorded absolute paths or an unambiguous compiler
trimpath module prefix mapped to actual module metadata. Missing source remains
unavailable. Address-only imports can be viewed but require symbolized profiles
to navigate to source.

Notifications:

```json
{"jsonrpc":"2.0","method":"task/step","params":{"id":"t1","cwd":"/project/app","executable":"/sdk/bin/go","args":["test","-json","./..."]}}
{"jsonrpc":"2.0","method":"task/output","params":{"id":"t1","stream":"stdout","text":"..."}}
{"jsonrpc":"2.0","method":"test/event","params":{"id":"t1","event":{"Action":"fail","Package":"example.org/app","Test":"TestApp/subtest"}}}
{"jsonrpc":"2.0","method":"task/exit","params":{"id":"t1","code":1,"cancelled":false}}
```

`test/event.event` is the original decoded `go test -json` object, including its
package, subtest, timing, and output fields. Ordinary stdout/stderr is also
streamed. UTF-8 split across process writes is reassembled for console output.
Cancellation emits `cancelled:true` with exit code 130. A successful coverage
task adds `coverageProfile` to its exit event. Nonzero Go command statuses are
retained; compilation and test failures are never represented as success.

On macOS/Linux each task uses its own process group. Cancellation and shutdown
kill that owned group, including a `go run` descendant. Windows currently uses
`taskkill /T` against its owned process PID; Windows behavior still requires
runtime validation. This engine is a developer tool, not a sandbox for untrusted
Go source or toolchain configuration.

## Verified scope

The test suite exercises real, offline Go projects: a two-module workspace,
passing and deliberately failing subtests, coverage output, Benchmark output,
streaming Unicode, Content-Length framing, response/event ordering, CWD and
symlink confinement, and cancellation of a running Go descendant. Four real
profile types are compared numerically with official `go tool pprof -topproto
-filefunctions` output; benchmark generation, precise source metadata,
inline/recursive accounting, profile RPC import, cancellation cleanup, and
workspace/module vendor behavior are tested without network dependencies. The fixtures
live in `testdata/workspace`. These checks establish the tool backend behavior;
they do not establish complete IDE, LSP, DAP, UI, or GoLand feature equivalence.
