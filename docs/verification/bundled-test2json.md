# Bundled official test2json

Go 1.26.10 omits `test2json` from its precompiled tool directory. Its public
`cmd/go/internal/tool/tool.go` builds the official `cmd/test2json` package when
`go tool test2json` first runs, then executes the cached binary. Even `go tool
-n test2json` builds that missing tool before reporting its path.

Our debug-test session starts this converter during background preparation and
allows three seconds for it to finish after the target terminates. A short
first test can therefore finish while its converter is still compiling. We
preserve the existing exit-status, cancellation and cleanup semantics.

## Independent cold-cache observation

The bundled Go 1.26.10 executable, with a new owned `GOCACHE`, took 2.856 seconds
to convert an immediately closed passing test stream on this Mac. With valid
resource settings `GOMAXPROCS=1` and `GOFLAGS=-p=1`, it remained alive after three
seconds and eventually produced the correct pass events and exit zero at
8.776 seconds. Warm conversion took about 0.17 seconds. These observations
demonstrate a first-use deadline hazard; they do not establish the exact cause
of the earlier CI3 assertion failure, whose converter diagnostics were not
retained.

The original command results are in `.tools/test2json-first-use/`. They used
only owned temporary caches; the authenticated SDK and its source cache were
not modified.

## Derived SDK tool

`bundle-go-tools.py` now compiles `cmd/test2json` using the authenticated **host
runtime SDK**, Go 1.26.10. It uses that SDK's compiler and standard-library
source, independently of the newer Go 1.27.3 compiler required by gopls and
Delve. Cross compilation uses the requested target OS/architecture, CGO off,
`-trimpath`, `-buildvcs=false`, disabled module/workspace/network configuration
and a private temporary build cache.

Only the owned staged SDK receives the resulting file:

- `go/pkg/tool/darwin_arm64/test2json`
- `go/pkg/tool/windows_amd64/test2json.exe`

The complete original SDK source and license notices remain. The authenticated
SDK cache and its extraction marker remain unchanged. The manifest's
`derivedSdkTools.test2json` records the host/target archive digests, compiler
digest, full SDK source-tree digest, build arguments/settings, build information
and derived binary size/digest. The ordinary payload file map also covers it.
This explicitly describes a staged SDK with an added derived tool, rather than
claiming that every payload byte is unchanged from the upstream archive.

The existing Linux builder-host route has a separately pinned Go 1.26.10 host
runtime compiler archive; this does not add a Linux IDE installer target.

## Controls and remaining scope

Actual derivation controls built the Mac and Windows tools from the pinned SDK
and verified that both original SDK trees and marker files were unchanged.
With the derived Mac tool and an untouched user cache, `go tool -n test2json`
resolved the staged binary in 0.724 seconds. A real UTF-8/Chinese/emoji passing
test stream converted in 0.491 seconds, exit zero, without creating that cache.
The Windows result is a real AMD64 PE32+ executable with Go 1.26.10 build
information; native Windows execution remains a CI requirement. These are
private component controls, not installer or GUI acceptance.

The final derivations used Unicode output paths with a forced CP1252 default
for omitted subprocess encodings. Our Go-only subprocess reader explicitly
decodes UTF-8. Real `go version -m` output on such a path fails under the old
reader and succeeds under the new one; no other tool's decoder is changed.

Five portable guard tests cover the source/compiler and target boundary,
unexpected existing-tool preservation, mismatched build identity and source
mutation during compilation, and UTF-8 output under a non-UTF-8 default. No
production timeout was increased and no test was prewarmed to hide the issue.
Selecting an external, unbundled Go SDK that
also lazy-builds tools still has the existing first-use readiness limitation.
The full IDE acceptance matrix remains open.
