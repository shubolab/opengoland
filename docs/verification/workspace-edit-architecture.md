# Go workspace-edit transactions

`GoWorkspaceEditService` implements the native commit boundary used by the Go
language client and semantic actions. A successful planner or unit test does
not establish native refactoring, file Undo/Redo, or complete GoLand parity.

## Public integration

```kotlin
val service = GoWorkspaceEditService.get(project)
val request = service.captureRequest()
service.apply(edit, title, preview = true, request = request, onApplied = { next ->
    // Advance the owning server-command baseline before its next applyEdit.
})
```

`captureRequest()` uses a read action and enumerates open editors, unsaved
documents, and weak references to documents previously loaded by this service.
The SDK has no public `FileDocumentManager.cachedDocuments` enumeration. Capture
does not enumerate project files or access the filesystem. File URIs use the
pure `GoDocumentSync.fileUri` encoder, avoiding `Path.toUri()` filesystem probes.

The immutable request snapshot contains document modification stamps and actual
gopls notification mirrors (`version`, `text`, `session`). A service-owned
document/VFS event sequence detects changes after capture, including documents
opened and changed after the original request. `GoDocumentSync` obtains versions
from actual outgoing didOpen/didChange/didClose messages. No synchronizer
reflection or guessed IDE-stamp-to-LSP-version conversion is used.

### Closed files and gopls version zero

The first native cross-module rename revealed that gopls v0.21.1 includes
explicit version `0` for files which were never opened with didOpen. Requiring
an open-document mirror for every non-null version rejected valid closed-file
edits. This is a documented server convention, not a reason to ignore versions:

- [file.Handle.Version](https://github.com/golang/tools/blob/gopls/v0.21.1/gopls/internal/file/file.go)
  defines the disk-backed handle version as zero.
- [diskFile.Version](https://github.com/golang/tools/blob/gopls/v0.21.1/gopls/internal/cache/fs_memoized.go)
  returns zero, while the overlay implementation returns the client's version.
- [DocumentChangeEdit](https://github.com/golang/tools/blob/gopls/v0.21.1/gopls/internal/protocol/edits.go)
  places that handle version directly in the TextDocumentEdit identifier.

A separate real-gopls probe opened files with versions 17/29/41 and observed
those exact versions for overlays, zero for closed files, and zero after
didClose. Zero is also a legal initial overlay version, so it cannot alone prove
that a document is closed.

The service accepts version zero without a mirror only when background
preparation proves a real existing disk source, no opened editor, no dirty
buffer, and identical cached/disk text. The disk baseline records SHA-256,
size, last-modification time, and physical file key, checked around the content
read. It is checked again after preview; request-period document/VFS events,
disk changes newer than request capture, live VFS/document stamps, and any
newly opened overlay still reject the edit. An opened file without a valid
mirror and a closed file with a nonzero unverifiable version remain rejected.
No synthetic GoSyncedDocument is created for a disk handle.

`acceptedDisk` advances only this transaction's touched physical baselines,
alongside `acceptedChanges`. It does not recapture other files. A later disk
fingerprint must match an acknowledged baseline even if its timestamp was
backdated. Precomputed hashes keep this acknowledgement free of disk reads on
the EDT.

Existing unsaved-document and Undo behavior is retained: this change does not
silently save all files or invent didOpen/didChange messages. A dirty closed
buffer is not accepted as a version-zero disk source. Consecutive server edits
to such a buffer need a verified notification/save workflow before broadening
that rule. CreateFile followed by an explicit-zero edit to a newly created empty
file is likewise not classified as an *existing* disk handle by this fix.
Both are explicit remaining server-shape cases, rather than ignored versions.

## Validation, preview, and commit

1. Background preparation obtains main module directories from `project/info`,
   including external `go.work` main modules. Project content roots and explicitly
   opened local file directories are also eligible. `GOROOT` and `GOMODCACHE`
   are excluded. A full ordered in-memory plan is built before any mutation.
2. Text ranges use strict UTF-16 positions, recognize LF/CRLF/CR, reject ranges
   outside a line and positions inside a surrogate pair, and reject overlap.
   Same-position insertions retain protocol order; insertions can precede one
   replacement at that position. Text-document versions must match the actual
   synchronized version and text.
3. `documentChanges` resource operations run in protocol order. Create,
   cross-directory file/folder rename, delete, overwrite/ignore options, and
   recursive-directory-delete rules are planned together with text changes.
   File contents and affected resource trees are backed up off the EDT.
4. Optional preview uses a native file list and `DiffRequestPanel` inside a
   `DialogWrapper` with Apply/Cancel. A required change annotation also forces
   preview. Cancel reports `applied=false` without writing.
5. After preview, disk snapshots are checked again on a background thread.
   Immediately before writing, document stamps/text, real LSP versions/sessions,
   VFS validity/stamps, directory entries, and missing-target assumptions are
   checked again under the write command. A stale edit reports a failure rather
   than applying to newer text.
6. Mutation uses native VFS create/move/rename/delete and preloaded native
   documents within one `WriteCommandAction.withGlobalUndo()`. It uses the
   platform's DocumentUndoProvider and FileUndoProvider; it registers no custom
   UndoableAction and does not disable native Undo. Resource operations require
   enabled Local History coverage so that file Undo is available.
7. A failure journal restores only operations actually executed or begun, in
   reverse order. Rollback attempts every inverse and reports restoration errors.
   The service never reports successful application after a failed commit.

Cancellation and entry into the atomic write command share a lock. Cancellation
which wins first prevents the command from starting. Once the native command
begins, cancellation returns false rather than leaving half of the command
applied with a cancelled future.

## Consecutive server edits

One server command may issue more than one workspace/applyEdit. After a successful
commit, the service records its own synchronous document/VFS events and advances
only affected old/new URIs and resource subtrees. It retains the original
request sequence and every untouched document's baseline. The `onApplied`
callback executes on the same EDT turn, after the native command returns and
before the response future completes.

`acceptedChanges` acknowledges the exact paths and sequence numbers changed by
that transaction. It is not a fresh full-project capture: user changes to another
file during the first preview remain unacknowledged and are rejected if a later
server edit tries to modify that file. If the real didChange notification has
not caught up yet, the updated stamp does not invent a synchronized version;
the subsequent edit still has to match the live mirror during planning.

## Case-only file and directory renames

Case-only RenameFile operations use physical identity rather than treating a
different URI spelling as an overwrite. Background preparation verifies
`Files.isSameFile` for both the resource and its parent and enumerates the actual
directory entry. There must be exactly one matching entry. Two case-sensitive
hard-link names can share an inode but are still two entries, so shared content
or shared inode alone never authorizes this path.

The planner keeps one logical tree for verified spelling aliases, preserving
the source document/disk origin. Folder aliases reuse the same backup bytes
instead of double-counting the transaction limits. Ordered repeated/reversed
case changes and text edits through the new URI operate on that same resource.
An alias referenced before its rename also resolves to the existing resource;
CreateFile cannot mistake it for an empty target. Preview revalidation checks
both identity and the actual original entry name, including an intervening
case-only external rename.

The native executor verifies that source and target still resolve to the same
`VirtualFile` and parent. It calls native `rename`; it neither deletes a target
nor sets the overwrite flag for a case alias. A distinct unexpected target is
rejected unless a genuine overwrite was planned. The same native executor is
used in production and the platform regression tests; no reflection/test-only
mutation path or additional UndoableAction is introduced.

The pinned SDK's
[LocalFileSystemBase.renameFile](https://github.com/JetBrains/intellij-community/blob/idea/2026.1.4/platform/platform-impl/src/com/intellij/openapi/vfs/impl/local/LocalFileSystemBase.java)
explicitly handles case-insensitive equal-name renames and uses an atomic NIO
move when supported. The public
[VirtualFile identity contract](https://github.com/JetBrains/intellij-community/blob/idea/2026.1.4/platform/core-api/src/com/intellij/openapi/vfs/VirtualFile.java)
defines equal instances as representing the same resource during its lifetime.
This product calls those native APIs and preserves native FileUndoProvider and
DocumentUndoProvider recording within the existing single global command.

A disposable probe on the current APFS volume confirmed one directory entry
for `name.go`/`Name.go`, unchanged inode after rename, and corresponding folder
and child identity. Added regressions cover real temporary filesystem identity,
hard-link counterexamples, planner aliases, subsequent open-versioned and
closed-version-zero text edits, repeated/reversed renames, and pre-rename alias
references. Two new LightPlatformTestCase tests exercise actual local VFS,
documents, Local History and one global Undo/Redo for file and folder cases,
including assertions that no delete event occurs. Execution of this new test
batch has passed all eight planner/temporary-filesystem tests. The first run of
the two platform tests stopped before mutation because their canonical
`/private/var/...` temporary directories were outside the test fixture's allowed
VFS roots. Those tests now register only their own temporary root with the
standard scoped `VfsRootAccess.allowRootAccess` API and revoke it after cleanup;
their actual native Undo/Redo rerun remains pending. No production access checks
were changed. Standalone native acceptance remains separate evidence.

## Current explicit limits

- Case-only aliases require the physical-identity and directory-entry proof
  described above. A distinct existing target on a case-sensitive filesystem is
  still a normal target collision. Ordinary platform Project-tree rename is a
  separate route from this transaction service.
- A second versioned TextDocumentEdit for the same original document in one
  batch is rejected, including across a resource rename. The client cannot
  prove an intermediate notification version merely by adding one to the
  original version. Separate successful server transactions can advance their
  acknowledged request baseline normally.
- Symbolic-link resource paths, non-local/non-file URIs, and snippet workspace
  edits are rejected. The client supports both plain TextEdit list payloads and
  LSP4IJ's Either-wrapped text payloads, without advertising snippet support.
- Backups are bounded to 16 MiB per file, 64 MiB total, and 20,000 filesystem
  resources. Read-only resources or resources without native Local History
  coverage fail before mutation.
- A failed command that was fully rolled back may leave one native Undo step
  with no visible net change. No unsupported Undo suppression or duplicate custom
  action is used to hide that step. A filesystem failure which also prevents
  rollback is returned explicitly with both failure reasons; it is not counted
  as an applied edit or a successful rollback.

## Evidence and remaining native checks

The root agent's serialized `compileKotlin` and `compileTestKotlin` completed
successfully. The first full test run identified two resource-delete planner
assertions; the shared cause was Kotlin treating `List<Path> + Path` as an
Iterable of path segments. Deleting children and the root explicitly fixes the
cause; both original assertions remain. The version-guessing path has also been
removed and a regression test covers repeated versions before/after rename.
The root agent owns the next complete test run and product snapshot.

The initial native rename from an open app file to its declaration/references
in a second module failed before mutation because a closed test file had a
non-null zero version. The closed-disk baseline change above addresses that
observed failure. A new product snapshot must repeat the cross-module rename,
its full preview, and single Undo/Redo before counting the native route as
passed.

Deterministic tests cover UTF-16/line endings, simultaneous edit ordering,
overlap, real-mirror version checks, create/edit/move/delete ordering, folder
renames, overwrite/ignore/recursive semantics, annotations, SDK/LSP4IJ payload
shapes, request/preview staleness, session changes, per-path acknowledgements,
an injected temporary-filesystem rollback failure, and cancellation/commit races.

Native acceptance is still required for:

- Preview Apply and Cancel, including typing while preview is open.
- Unsaved multi-file rename and organize imports, followed by exactly one
  global Undo and one Redo, preserving every affected document.
- File/folder create, cross-directory rename, overwrite, delete, and their
  original contents/paths through Undo/Redo.
- A rejected stale version/session and an injected commit failure returning
  applied=false with unchanged affected files after rollback.
- Consecutive server edits accepting only their own earlier changes while
  rejecting independent user changes to another file.

Relevant pinned-platform sources:
[FileUndoProvider](https://github.com/JetBrains/intellij-community/blob/idea/2026.1.4/platform/lvcs-impl/src/com/intellij/openapi/command/impl/FileUndoProvider.java),
[DocumentUndoProvider](https://github.com/JetBrains/intellij-community/blob/idea/2026.1.4/platform/platform-impl/src/com/intellij/openapi/command/impl/DocumentUndoProvider.java),
[UndoUtil](https://github.com/JetBrains/intellij-community/blob/idea/2026.1.4/platform/core-api/src/com/intellij/openapi/command/undo/UndoUtil.java).
The ordered-resource and text-range rules follow the
[LSP WorkspaceEdit specification](https://github.com/microsoft/language-server-protocol/blob/gh-pages/_specifications/lsp/3.17/types/workspaceEdit.md).
