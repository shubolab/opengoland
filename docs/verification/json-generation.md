# Native JSON to Go generation

**Code > Generate > Type from JSON** opens a native JSON editor, type name and
numeric policy. **Struct Fields from JSON** and the struct's **Generate fields
from JSON** intention add selected fields without replacing existing fields.
Analyze / Refresh produces Fields, Types and Generated Go previews. Generated Go
shows the analyzed sample; the final Preview button plans the selected fields and
opens the native file Diff. Apply commits one global Undo transaction.

## Inference and preservation

JSON text is a sample, not a schema for all future values. Objects, arrays,
primitive roots and null roots are supported. Generated field order follows keys’
first textual occurrence; JSON tags retain original representable keys. Nested
named types receive deterministic names; namespace collisions receive visible
suffixes. Generated field suggestions use Go-style
initialisms such as `ID`, `TraceID` and `URL`, following
[Go review guidance](https://go.dev/wiki/CodeReviewComments#initialisms).
This naming policy is independent; exact GoLand generator defaults were not
established by the public reference.

The default numeric policy chooses int64/float64 only when the supplied numeric
samples survive JSON decode/encode with the same decimal value. Otherwise it
uses `json.Number`. The explicit number policy preserves numbers as `json.Number`.
Existing ordinary import aliases are reused. Root Number/RawMessage declarations
retain the alias semantics needed by encoding/json.

Fields notes mark members missing from some samples and members containing null.
Optional fields use pointers where needed to retain absence versus sampled zero
values. Members sampled as both missing and null use json.RawMessage with
omitempty to distinguish those cases when decoded into a fresh value. Null-only,
heterogeneous and unknown element types use RawMessage rather than a guessed
schema; these and missing-plus-null cases have visible inference warnings. Object
samples inside arrays are merged. A root object with keys that cannot be
faithfully represented by Go JSON struct tags uses a raw-message map rather than
silently changing keys. Fields mode marks such keys unavailable. The key `-`
uses an explicit non-ignore tag representation. Existing JSON keys, promoted
fields, field/type/import names and shadowed builtins are checked for conflicts.

Fields mode preserves existing fields, tags and comments, and uses explicit
selection for additions. Conflicts reject the complete selected plan. Generated
or read-only source, unsupported contexts, invalid JSON and resource limits have
explicit errors. Current input limits are 1 MiB, depth 64 and 20,000 values.

## Ownership and validation

JSON, type name or numeric-policy changes invalidate the last result immediately.
Preview uses the exact last successfully analyzed request and its IDs. Old,
out-of-order, cancelled or disposed dialog responses cannot replace current
choices. Reopened drafts require another Analyze. Field preferences can survive
reanalysis by JSON key; IDs always come from the new response.

Go source overlays and toolchain settings use shared frozen generation snapshots;
the dialog separately freezes each analyzed JSON request. SDK and Go analysis
stay off EDT. Owned workers retain SDK subprocess cancellation. Planning writes
no original project files and executes no user programs; the isolated candidate
is type checked. Native application revalidates source stamps, LSP versions,
disk/dependency conditions and the captured toolchain before one global Undo/Redo command.

## Evidence and boundaries

The selected GoLand descriptor and public 2026.1 paste/dialog workflow are recorded
in [the reference scope](../reference/struct-helpers-reference.json). Neither
sample inference defaults nor commercial runtime equivalence are established.
The [native cohort](json-generation-native.json) records source digest
`d2dc3e0755733e55267f3313d80a601634192323d1f230de9036c6a200937d17`
(311 input files), all 313 Kotlin tests, Go race coverage of 240 test events
(135 top-level tests, 9 packages, with cache reuse recorded), and Go vet.

Native checks exercised the actual JSON editor, stale analysis after switching
numeric policy, all preview tabs, complete native Diff/Apply, and independent
Undo/Redo for a new type followed by fields in an existing struct. An unsaved
Chinese/emoji comment and the original disk bytes survived both transactions
until explicit Save. The generated code reused an existing encoding/json alias.
Two uncached tests run through the standalone IDE then verified actual JSON
decode/encode, a large number, nested objects, null, Unicode and retained tags.
The separate [real gopls trace](multicaret-gopls.md) records multi-caret transport
and server-result evidence; these records do not establish full IDE parity.

Incomplete candidate workspaces, dot/blank or lexically shadowed JSON imports,
all build contexts, full JSON schema inference, exact commercial UI behavior and
complete GoLand parity remain open. A sample-driven converter does not claim to
validate every future payload.
