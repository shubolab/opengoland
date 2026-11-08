# Native struct constructors and accessors

Code > Generate offers **Constructor**, **Getter**, **Setter**, and **Getter and
Setter** for a named Go struct. The corresponding Alt+Enter intentions open the
same native field-selection dialog. Field intentions prefer the current direct
field; the Generate menu starts with the editable fields selected. Names can be
edited before native Diff preview and a single global Undo command.

## Typed behavior

- Constructors accept the selected fields as parameters, preserve generic type
  parameters/constraints, and return a pointer or value. Unselected fields retain
  their zero values. Zero-input constructors are supported.
- Getters return the actual field type and can use pointer or value receivers.
  Setters always use pointer receivers and assign to the original receiver.
- Same-package private, embedded and grouped fields are eligible. Blank fields
  and inaccessible foreign fields are excluded. Existing/promoted methods,
  fields, package names, parameters, keywords and receiver names are checked for
  relevant collisions; a conflict rejects the whole plan.
- The backend resolves local named generic structs, canonical local aliases and
  derived named structs. Field types, constraints and imports use the same type
  universe. Candidate signatures, method sets, field bindings and the complete
  active workspace including tests are checked again before edits are returned.
- Source inspection and planning never write files or execute user applications.
  A retained worker/process group owns SDK subprocesses and cancellation.
- Shared native generation snapshots capture only cached/open/unsaved documents
  and in-memory settings. SDK/environment resolution stays in the background.
  Preview/application validate document stamps, LSP versions, disk/dependency
  guards and the selected toolchain; dirty source remains dirty until saved.
- Generation UI continuations check task cancellation both before enqueueing and
  on EDT delivery. A cancelled analysis cannot later open its queued dialog.

## Native evidence

[struct-helpers-native.json](struct-helpers-native.json) records the exact
293-file source snapshot and delivered artifacts. This cohort passed all 304
Kotlin tests, the complete Go race suite (198 test events including subtests;
unchanged packages used the Go test cache), Go vet, plugin structure and app
signature verification.

The standalone app generated a generic value constructor and value getters with
pointer setters. Alt+Enter selected only the current private field. The Generate
menu reported its existing accessors, disabled Preview for that collision, then
allowed the remaining field after deselection. Native Diff, Apply and independent
Undo/Redo preserved the dirty Chinese/emoji comment and the earlier constructor.
An uncached native Go test verified initialization, both setter mutations and a
value receiver interface method set. The fixture's pre-existing private-field
JSON-tag Vet warning was reproduced before and after generation; this is not a
claim that the native fixture itself passed all Vet checks.

This cohort also exercised the migrated Struct Tags dialog against the dirty
generic source, then cancelled without editing. Its older full edit evidence
remains in its own [source cohort](struct-tags-native.json).

## Reference and remaining work

The authenticated descriptor/resource scope and public JetBrains 2026.1 help are
retained in [struct-helpers-reference.json](../reference/struct-helpers-reference.json).
They establish constructor field selection and getter/setter workflow candidates,
not installed commercial execution, exact default names or receiver defaults.
`NewType`, `GetField` and `SetField` are editable independent suggestions.

Native lexical availability currently targets package named declarations with a
direct struct body, excluding aliases and incomplete bodies. The backend's alias
and derived-type cases have automated coverage; their native direct entry remains
open. Read-only SDK entry, all build contexts, incomplete candidate workspaces,
JSON-to-struct generation, commercial runtime comparison, exact dialog behavior
and full GoLand equivalence remain open.
