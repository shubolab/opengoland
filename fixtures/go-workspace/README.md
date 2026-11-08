# Go IDE acceptance workspace

Open this directory as one project. It deliberately contains two modules, local
dependencies, an interface and implementation, generics, Unicode, subtests,
a benchmark, and a fuzz test. All default tests pass without downloading modules.

```sh
cd app && go run .
cd ../catalog && go test -json ./...
go test -coverprofile=coverage.out ./...
go test -run '^$' -bench . -benchtime=1x
```

Set `OPENIDEA_FAIL=1` in a Go Test run configuration to verify failure display
and source navigation. Set a breakpoint on the `fmt.Println` line of `main.go`
to inspect `cart`, evaluate `price.Total()`, step, and resume.

The workspace root itself is not a module; root build/test commands must run
each `go.work` member. Editing and navigation must keep unsaved buffers and
cross-module references working.
