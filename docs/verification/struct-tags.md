# Native Go struct tags

The product offers **Code > Generate > Struct Tags** and **Alt+Enter > Edit
struct tags** within a struct. A native dialog selects fields, an editable key
(with JSON/XML/YAML presets), a naming style and an operation. The typed Go
planner returns a WorkspaceEdit; the platform's native Diff preview and global
Undo command own application. Planning never writes source or runs user tests.

## Semantics

- Add missing preserves an existing key. Update derives names using original,
  camel, snake or lowercase styles. Remove deletes only the selected key.
- Other keys remain intact. Existing options remain intact unless **Replace
  existing options** is explicitly enabled. New keys can receive supplied options.
- Existing exact `"-"` ignore values remain unchanged during ordinary updates.
  Remove is explicit. Unexported fields are not selected by default.
- The planner uses the selected toolchain, build context and frozen dirty-source
  overlays. Inspection, configuration and field identities are checked again
  before generating a plan. Complete candidate type checking is required because
  changing a tag can change an unnamed struct's type identity.
- Shared field declarations are not silently split or partially changed. An
  incompatible selection or different required values produces a conflict.
  Malformed or ambiguous tag syntax also produces a conflict.
- Application validates observed document stamps, disk hashes and dependency
  directory membership again. Unsaved source remains unsaved; one global Undo
  restores the entire prior buffer, and Redo restores the changes.

## Reference and limitations

[The bounded reference record](../reference/struct-helpers-reference.json)
contains authenticated descriptor/resource identities and public JetBrains
help. The reference describes tag add/name-style intentions and **multiple
carets** for value editing. This implementation's bulk dialog and Remove operation
are independent interactions; they do not establish commercial runtime parity.
Constructors, accessors, JSON-to-struct generation, full multi-caret equivalence,
all build contexts and complete struct-helper coverage remain open.

The lexical Alt+Enter availability check is only an entry hint. Go AST/type
analysis determines the actual nearest struct and fields. Read-only SDK source,
generated source or unsupported contexts may be rejected. Current candidate
validation conservatively requires a type-correct active workspace.

## Verification

The exact build, native observations and limitations are retained in
[struct-tags-native.json](struct-tags-native.json). All 285 Kotlin tests, the complete Go race suite (184 test events including
subtests; unchanged packages used the Go test cache), Go vet, plugin structure and
app signature verification passed. Native Alt+Enter/Generate, Diff, unsaved
application, global Undo/Redo and an uncached JSON/XML test passed. A same-bundle
restart restored the project/run configuration; its rerun passed using Go cache.
