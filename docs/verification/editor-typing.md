# Native Go editor typing

The final editor batch passes all **378 Kotlin/platform tests**, with zero
failures, errors or skips, and packages the independent native application.
The [build record](editor-typing-build.json) binds these results to 332 captured
inputs, digest `d4b5eabb77d34c3c6f17f5e1162c21655e4bfd1c31e5c755a81ea1c2560576db`,
and the delivered plugin/engine hashes. The standalone desktop passed the listed
typing/settings/test checks in the [native record](editor-typing-native.json).
Build success and process identity alone do not establish editing parity.

## Implementation

The editor remains the open-source IntelliJ Platform editor: native documents,
carets, Enter/TypedAction/Backspace dispatch, selection, settings and Undo/Redo.
The independent Kotlin handlers inspect the current UTF-16 document without
filesystem operations, subprocesses, RPC or forced PSI commits on a keystroke.
gopls 0.21.1 does not implement on-type formatting; these local operations do
not rely on that request.

| Component | Behavior |
| --- | --- |
| `GoLexer` | Go whitespace and identifier categories; bounded UTF-16 spans; malformed interpreted-literal recovery at LF; scanner-compatible numeric boundaries; longest operator tokens. |
| `GoIndentContext` / `GoLineIndentProvider` | Blocks, struct/interface bodies, switch/select cases, labels, calls, generic/index lists, composite literals, grouped declarations and statement continuations; native tab/indent/continuation/smart-tab options. |
| `GoEnterHandler` / `GoEnterBetweenBracesDelegate` | Literal handling and the platform's no-commit pair-splitting pipeline; native Smart indent on Enter remains effective. |
| `GoTypedIndentHandler` | Closing-only line alignment on insertion and overtyping; case/default colon alignment without treating a developing `:=` as a label. |
| `GoQuoteHandler` / `GoQuoteTypedHandler` | Interpreted/rune/raw quote pairing and overtyping, escape boundaries, raw backslashes and supplementary identifier boundaries through the native typed action. |
| `GoCommentEnterHandler` | Mid-line `//` continuation; EOL/empty-comment exit; directive protection; plain/starred block-comment continuation and closure settings. |

The existing `GoCommenter` and brace matcher continue to support native comment
and paired-brace actions. Quote pairing and surrounding a selection obey the
platform's preferences. Literal/comment punctuation never contributes structural
braces or cases. The seven real quote/typed/indent/Enter extensions are registered
in the product descriptor; the integrated tests exercise that product setup.

The context scanner uses per-delimiter index stacks, a monotonic line cursor,
cached leading indentation and constant-size header summaries. Missing closers
do not rescan the entire nesting stack. Frames are pushed and popped once,
without a reduced context-depth cap. Regressions cover deep incomplete nesting,
unmatched closers and long balanced-composite sequences. A review's 32KB nested
probe fell from approximately 1.6 seconds to 18 milliseconds in one standalone
measurement; this is evidence for that regression fix, not a general latency
guarantee.

Header regressions distinguish completed control blocks, ordinary semicolons,
for/if/switch initializers, nested function literals, type-switch cases and
labelled blocks. Function element types in map/array/slice composites do not
turn identifier keys into block labels. All eleven compound assignment operators
continue from the statement anchor as complete tokens. Channel consumers in
`GoSourceFunctions`, `GoStructTagCarets` and `GoNamedTypeHeaders` consume `<-`
directly and do not invent an operator from invalid `< -` punctuation.

## Raw-string preference

The native Go settings page persists **Indent on Enter in raw strings** through
`GoProjectSettings.indentRawStringsOnEnter`. Its default `false` is an independent
product choice; the installed commercial default was not observed.

When disabled, raw Enter inserts exactly a newline. When enabled together with
native Smart indent on Enter, it copies the current line's exact leading
spaces/tabs. It does not interpret raw contents as Go structure or normalize
their data. Disabling Smart indent also disables this copied indentation.
Changing this editor-only preference does not change SDK/environment identity
or restart gopls. Native Undo is covered for the preference-enabled edit.

The Go-specific toggle is documented in
[GoLand 2026.1 Go settings](https://www.jetbrains.com/help/go/2026.1/go-configuration.html);
shared pairing and Enter preferences are documented in
[Smart Keys](https://www.jetbrains.com/help/go/2026.1/settings-smart-keys.html).

## Verification

Before these extensions, actual platform Enter split an empty brace pair but
left a function body's new line unindented; nested if/case/call lines lacked
the required Go indentation. Actual quote baseline actions inserted a lone
opening quote and an extra closing quote; splitting `//` contents lost its
comment prefix. These were observations of our previous sandbox, not the
commercial reference. The old lexer failed six of the nine expanded lexical
regressions; the current lexer passes all nine.

The final integrated totals include the following subsets. **Do not add them
to 378 or count earlier standalone runs as additional final tests.**

| Integrated subset | Tests | Failures/errors/skips |
| --- | ---: | --- |
| Native Enter, typed alignment, preferences, UTF-16/CRLF, Undo/Redo and multiple carets | 18 | 0 / 0 / 0 |
| Native quotes, comments, Backspace, preferences and Undo/Redo | 10 | 0 / 0 / 0 |
| Lexer boundaries and restart/bounded-range behavior | 9 | 0 / 0 / 0 |
| Tag-value native carets and named generic type headers | 8 + 3 | 0 / 0 / 0 |
| Source-function discovery, including channel/function/variadic forms | 11 | 0 / 0 / 0 |
| Go settings | 3 | 0 / 0 / 0 |
| Find Usages policy: controlled platform tests plus actual stdio gopls | 4 + 1 | 0 / 0 / 0 |

The usage-policy tests retain declarations/definitions/references while excluding
unrelated type-definition/implementation queries from Go Find Usages. Explicit
LSP implementation, type-definition and type-hierarchy features remain enabled;
other clients retain their upstream aggregate policy. Real reference failures
still report their own error. The stdio test uses actual gopls replies rather
than manufactured locations. The [native policy record](find-usages-policy.md)
separates successful explicit LSP navigation from the standard platform
navigation entry points that did not dispatch queries in this desktop check.

The selected Go 1.24.3 scanner, gofmt and compiler supplied independent oracles
for representative Unicode/numeric/operator boundaries, valid channel types,
control-header/composite cases and compound assignments. The unchanged engine's
full race command reports 271 successful test events, including subtests, 155
top-level tests and nine test packages; all nine results were cached. The engine
command package has no tests. Vet exited successfully with an empty log.

The first full package attempt had two failures among 371 tests: an old lexer
consumer and a platform fixture startup-order assumption. Both were corrected,
and the final package run has 378 passing tests. The build record retains log
and report hashes rather than treating the failed attempt as passing evidence.
The actual platform is IC-261.26222.65; the commercial reference is
GO-261.26222.72. Runtime identity for owned PID 91007 verifies open Goland and
its plugin paths/loading, with its stated limitations. It does not prove the
keyboard actions by itself.

## Standalone desktop observations

Owned PID 91007 opened a fresh two-module workspace whose path includes Chinese,
spaces and an emoji. Native punctuation actions paired and deleted empty quotes,
skipped existing interpreted/rune/raw closers, indented nested blocks/calls,
aligned a typed case colon and closing brace, and continued a line comment after
Chinese/emoji text. One Undo/Redo restored the entire nested brace split and
indentation. Unicode literal content was inserted with a separate native paste;
IME composition was not tested.

The raw-string checkbox initially appeared unchecked. Enter in a line with four
leading spaces first inserted only LF, then copied all four spaces after the
checkbox was enabled. Each edit was individually undone to the exact original
raw value. The trace retains versions 17–20 and one continuous gopls connection
without reinitialization or `didChangeConfiguration`. Exact-label Settings search
did not locate this new checkbox; Other Settings > Go did. The saved setting is
still `true` after normal exit; reopening this toggle remains unverified.

The actual Go 1.24.3 `TestNativeTyping` and cross-module `TestCross` both passed
through the native test executor without cached output, with package times
0.452s and 0.612s. The assertions check the Unicode quote, case routing, exact
raw string and raw backslash/rune contents, plus ordinary and interface calls.
The final versioned UTF-16 replay matches all five opened documents on disk.
Version 21 only removes trailing whitespace at save time; it is not counted as
another keyboard action. The initial deliberately missing colon and a transient
curly quotation mark produced real diagnostics; syntax/type errors cleared after
repair, while staticcheck warnings remained.

After native Quit/Exit, all seven recorded application/tool/test PIDs were absent.
The final 332-input digest and all 32 manifest plugin artifact hashes still match;
deep strict local signature verification passed. The current-session log has no
ERROR/SEVERE entries and retains 18 platform warnings. The trace does not contain
a complete LSP shutdown/exit handshake; process termination is separate evidence.

## Reference and remaining boundaries

[The reference record](../reference/editor-typing-reference.json) separates
REA-authenticated static descriptor observations, public documentation,
inference and unknowns. No proprietary implementation was read, loaded,
decompiled or copied. Public API design used the pinned OSS Platform sources
and [language feature extension documentation](https://plugins.jetbrains.com/docs/intellij/additional-minor-features.html).
Static registrations and public help do not prove commercial runtime defaults
or exact keystroke output.

- `GoParserDefinition` still builds a flat, consume-all PSI tree. Local lexical
  assistance does not supply a complete Go grammar, structural typed PSI,
  Complete Statement implementation or full commercial editor equivalence.
- Interpreted-string/rune Enter inserts the requested newline. Automatic valid
  string-expression splitting/concatenation is not implemented or established
  as equivalent to the installed reference; gopls diagnoses invalid source.
- Unicode categories come from the host JBR. Selected Go 1.24.3 uses Unicode
  15.0; newer host categories can differ for newly introduced code points.
  Selected-SDK/gopls diagnostics remain authoritative.
- CRLF-backed-file tests use the platform's normalized document representation;
  they do not claim raw byte-preserving file rewriting by these handlers.
- IME composition, every overwrite/virtual-space mode and preference combination,
  all malformed syntax, large-file latency and complete reference runtime parity
  remain open. The full acceptance matrix stays open.
