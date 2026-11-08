# Native tag value carets

With the caret in a struct tag key/value, **Alt+Enter > Edit tag values for key**
places native carets at that key's value beginnings in each direct field of the
nearest struct. It does not select, replace or save text. Normal editor typing,
paste, Undo and Redo own subsequent edits. Other keys and options remain intact
unless the user edits them explicitly.

The lexical scanner retains UTF-16 source spans while decoding raw/interpreted
Go tag literals and escaped Unicode. Nested struct fields are isolated. Shared
field declarations have one physical tag and therefore one caret. The current
field remains primary. Read-only/deleted/viewer contexts, ambiguous duplicate keys,
malformed tags and incomplete target scopes are rejected; unrelated unfinished
functions/comments elsewhere in the file do not disable a complete target.
The operation reparses the current document on invocation and respects the
platform's maximum caret count. It runs no filesystem work or subprocesses.

Six real native Editor regressions cover caret placement, TypedAction input,
Undo/Redo, escaped/interpreted/Unicode values, shared/nested fields, incomplete
external code, malformed target scopes and stale/read-only/deleted contexts.
The standalone desktop cohort in
[struct-helpers-native.json](struct-helpers-native.json) displayed **2 carets**,
pasted `新😀` into both JSON values, preserved their options and XML keys, and
verified one-step Undo/Redo without losing generated methods. The input was
undone before the actual generated-code test.

The [reference record](../reference/struct-helpers-reference.json) establishes
carets inside values, while exact commercial selection/replacement behavior was
not observed. Value-start placement without a selection is our independent policy;
this does not close commercial multi-caret, IME or all editor interaction parity.
