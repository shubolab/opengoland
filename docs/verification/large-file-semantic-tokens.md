# Large-file Go semantic tokens — 2026-11-09

Status: the production LSP bridge and tool integration have passed tests. An
intermediate package and the final standalone package also passed bounded
native SDK-file checks. Native editing/cancellation remains open. This does not
establish general GoLand language-feature parity.

## Cause and implementation

Pinned gopls 0.21.1 rejects a semantic-token request whose requested span exceeds
100000 UTF-8 bytes. This applies to both full and range requests; it is visible
in its public [Go implementation](https://github.com/golang/tools/blob/gopls/v0.21.1/gopls/internal/golang/semtok.go).
The native failure previously involved a 214687-byte SDK `runtime/proc.go`.

The Go client uses the public
[`LSPClientFeatures.createLauncherBuilder`](https://github.com/redhat-developer/lsp4ij/blob/0.21.0/src/main/java/com/redhat/devtools/lsp4ij/client/features/LSPClientFeatures.java)
hook and subclasses
[`DefaultLauncherBuilder`](https://github.com/redhat-developer/lsp4ij/blob/0.21.0/src/main/java/com/redhat/devtools/lsp4ij/server/DefaultLauncherBuilder.java).
It retains the existing LSP4IJ launcher, transport, request IDs, logging, and
message processor. `GoSemanticTokensServer` wraps the typed public
`LanguageServer` and `TextDocumentService` interfaces; unrelated RPCs, including
Java default interface methods, are forwarded to the real endpoint.

The client explicitly offers UTF-16 positions. Immutable text/version snapshots
follow the actual didOpen/didChange/didClose calls. Requests are partitioned at
an 80000-byte budget, with UTF-16 positions at code-point boundaries and without
splitting CRLF. This also handles oversized single lines. A small request
retains the original full/range RPC. A larger request makes sequential bounded
range RPCs, decodes document-relative token positions, removes exact duplicate
boundary-intersecting tokens, sorts and re-encodes one complete response. The
upstream legend and token kinds/modifiers are retained. No highlighting feature
or error-reporting option is disabled.

Changes, close, cancellation, and server disposal invalidate pending aggregates.
A changed document cannot combine ranges from different observed client
versions. Aggregate cancellation cancels the underlying RPC future; it does
not establish that remote computation stops immediately. Closed-file reads run
off the event dispatch thread, and the exact disk text is checked again before
publishing a closed-file aggregate. Malformed dirty notifications refuse the
request rather than substituting saved disk contents. CPU/encoding work also
runs off the event dispatch thread.

Aggregate result IDs contain a per-server UUID and a local sequence. The delta
endpoint returns a fresh full response, which is legal in the LSP response
union, and never forwards an aggregate ID to gopls's unrelated range IDs.

## Executed tests

The root-owned targeted Gradle run passed **20 tests with zero failures, errors,
or skips**: seven existing document-sync tests, twelve bridge tests, and one
real-gopls integration test. The real integration test ran in 1.799 seconds.
It uses the actual production factory and launcher with one owned `gopls serve`
process; a public raw endpoint is used only as the upstream control.

| Real tool fixture | UTF-8 bytes | Verified result |
| --- | ---: | --- |
| Unicode comment and trailing declaration | 83043 | Chunked data exactly equals a successful upstream full response |
| 12000 declarations with Chinese, emoji, and CRLF | 408904 | Raw full request reproduces the size error; the bridge returns all 12000 declaration tokens, including their exact line sequence |
| One oversized Unicode string and a following declaration | 300044 | The string token appears exactly once across intersecting ranges; its full UTF-16 length and the following declaration are preserved |

The unit tests cover Unicode/CRLF/long-line partitioning, absolute positions,
deduplication, RPC forwarding, full/delta cancellation, document changes,
close/disposal, invalid dirty mirrors, closed-file changes, and result-ID
separation across sessions. The real test discovers gopls on PATH or uses
`OPENGOLAND_GOPLS_TEST`; when no tool is installed it reports an explicit skipped
integration test. The executed run above did not skip it.

A subsequent small lifecycle correction drops late notifications after the
bridge has closed, with an additional assertion inside the existing disposal
test. The final root-owned `test buildPlugin` run confirmed that guard and all
**182 Kotlin tests with zero failures, errors, or skips**, including the same
20 LSP tests. The real-gopls test ran again in 0.526 seconds. Its compiled input
digest was
`2f81aa5947740553f7e006f39a6560a9bfe871578f18186ab5e56629b8547523`
(177 input files).

That full run also passed `EngineClientEdtDisposalTest`: a real owned child
acknowledges initialization and then stops reading stdin, while a 4 MiB request
blocks the actual pipe writer. Disposal on the real platform EDT returns within
the test's two-second deadline; separate cleanup terminates the owned process,
the writer unblocks, and later requests cannot restart the disposed engine.
This process/EDT check uses the real project settings service and preserves the
other existing engine lifecycle tests.

## Native SDK-file observation

The intermediate standalone package with input digest `2f81aa5…` ran as native
PID `73740`, with gopls PID `74184` and Go engine PID `74346`. Root opened the
actual Go 1.24.3 SDK `src/runtime/proc.go` (214687 bytes). The beginning of the
file rendered comments and its package declaration. Near the end, the native
viewport at line 7376 showed blue `doInit1` function text, blue builtin `print`,
and type/parameter inlay hints. The earlier size-cap error notification was not
shown during this check; error reporting stayed at **As notification**.

With verbose trace enabled temporarily, the native trace displayed one
successful `textDocument/semanticTokens/range` response whose data began at
document line 0. The accessibility tree retained only one such entry. This
observation therefore does **not** prove a particular total range-request count
or retain all range bodies from that native session. Complete aggregation over
the 408904-byte fixture, including all 12000 declaration tokens, is established
by the separate real-gopls production-launcher regression above.

Verbose tracing was turned off and applied before native PID `73740` exited.
The SDK is an external reference source: native editing/cancellation was not
exercised on it. The observed inlay hints are also a separate language feature;
they do not independently establish complete semantic-token classification.

## Final-package SDK smoke and signature preservation

The final package has input digest
`2960da4418ca4da403eb6b04d56889e38d3d89b5cf70272af2131c352274e961`
(177 input files). Compared with the preceding `ae7475a…` package, the only
input change is `scripts/package-product.py`; Go and Kotlin sources are
identical. A fresh full Gradle run again passed **182 tests with zero failures,
errors, or skips**.

This package ran as native PID `87875`, with gopls PID `87899` and engine PID
`87957`. Its own SDK `proc.go` viewport near line 7376 again rendered blue
builtin `print`, blue `doInit1` function text, and type inlays. The SDK was not
edited. After native Quit, OS checks found all three processes gone. These
observations belong to this final artifact rather than being transferred from
the intermediate package.

The packaging correction prepares LSP4IJ's two fixed disassembly resources
before signing. All **32 plugin artifact hashes** remained unchanged through
the native run, including those two pre-existing bundle files. Strict deep
`codesign` verification passed before startup, while the native app and its
LSP/engine were active, and after Quit. This workflow therefore did not recreate
the earlier first-run mutation of the sealed application. It does not prove
every future plugin workflow is immutable.

The final smoke does not add evidence for a particular total native range-RPC
count. The earlier trace observation retains its one-entry scope, and complete
large-file aggregation remains supported by the real-gopls regression.

## Remaining verification

Exercise native editing/cancellation on an owned large Go fixture instead of
modifying the installed SDK. The bridge preserves gopls's semantic
classifications; it does not independently improve their language accuracy or
complete the broader acceptance matrix.
