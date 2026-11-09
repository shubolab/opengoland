# Native Go type search

The native Go To Type and Search Everywhere Types contributor uses an independent
Go declaration catalog. It does not require opening a Go editor or starting gopls
first. This is a partial implementation record; the complete navigation and
performance acceptance rows remain open.

## Why a separate catalog

Actual pinned gopls controls return a named function type as `Function` rather
than a class kind. A broad workspace-symbol query returns 100 entries in the
retained fixture; its type is still present, so that control does not demonstrate
a specific missing type. Filtering that bounded response after retrieval cannot
establish a complete type catalog. The unchanged prior standalone package also
fails to find `PilotRecord` in both cold and warmed native Classes search.
The [reference record](../reference/native-workflows-reference.json) keeps these
owned observations separate from installed GoLand resource declarations and
official documentation.

## Go metadata and source identity

`search/types/catalog` runs in an owned, cancellable worker. It uses the selected
Go SDK, environment, build tags, workspace/module/vendor metadata and active
source files. AST `TypeSpec` declarations include named function types, generic
aliases and local type declarations; ordinary functions are excluded. The list
has no declaration-count cutoff. All Places includes selected dependency modules
and SDK source, including unimported packages. Project mode limits the catalog to
the project context. Explicit `GO111MODULE=off` GOPATH projects are supported.

Unsaved source and module/workspace manifests are overlays. Existing-parent
physical path resolution joins symlink aliases, including a new unsaved file.
Disk hashes cover original bytes; text hashes cover the LF-normalized native
document. UTF-16 declaration ranges are checked against source text. Source and
directory guards detect stale results, and recoverable syntax errors produce
partial coverage with an actionable warning.

The worker uses the selected local toolchain (`GOTOOLCHAIN=local`). This does not
establish every automatic toolchain-download context. The existing transport has
a 16 MiB frame bound in both directions and reports oversized data rather than
silently truncating declarations. Oversized request rejection must occur before
writing any frame so a search cannot terminate the shared engine. Representative
large-repository latency and memory
remain unverified.

## Native search and lifecycle

`GoTypeSearchContributor` implements the public native Go To Class contributor
contracts and reports the Types title. The native model owns fuzzy and qualified
matching. Results are navigable PSI elements with containing files and validated
token ranges. Shared upstream LSP class and symbol providers are unchanged.

A per-project index coalesces catalog requests across keystrokes. It captures the
selected settings and monotonic document/VFS epochs, including closed dirty
buffers. `DocumentEx.modificationSequence` prevents an edited snapshot from
becoming current merely because Undo restores a stamp. Physical alias resolution,
tool resolution and source reads run off the EDT. Model/project disposal and
cancellation prevent publication and cancel only the owned operation, not shared
engine initialization.

Native default project ID filters are index optimizations; they cannot discover
unregistered selected SDK roots. Canonical Project and All/Everything scopes
therefore apply their semantic content rules without treating that default hint
as a mandatory SDK exclusion. Canonical scopes ignore the supplied ID filter;
the public SPI does not distinguish the source of that hint. Arbitrary explicit
scopes enforce `contains()` and their associated ID filter. This distinction is
intentional and covered by actual SDK chooser tests.

## Verification boundary

The frozen Go catalog passes 12 race-enabled tests in 15.157 s. Its actual SDK
worker control returns 5,645 declarations, 2,614 source guards and 2,983,245 bytes,
with complete coverage and no issues. Cases include dirty manifests, unimported
dependencies, go.work siblings, vendor, explicit GOPATH, CGO original source,
Unicode/CRLF, symlinks, partial syntax and cancellation. Retained local inputs and
logs are indexed by `.tools/type-catalog/evidence.json`.

Native controlled chooser tests and fresh packaged-desktop observations must be
attributed to their exact input cohort in the common native-workflows build and
desktop records. These Go tests alone do not establish native navigation, full
GoLand equivalence, every scope/build context or complete race coverage.

The [final build](native-workflows-build.json) and [fresh native observations](native-workflows.md)
bind the complete 395-input cohort. The [desktop record](native-workflows-native.json)
retains the actual workflow and cleanup boundaries.
