# open Goland

Build an independent, complete Go desktop IDE whose reference is the installed
GoLand 2026.1.4. Kotlin and the open-source IntelliJ Platform own the workbench
and editor. Go tools and the Go engine provide language and engineering services.

- Do not substitute a WebView UI or a basic editor for the requested IDE.
- Keep the IDE local and usable without an IDE account. Do not add billing,
  subscriptions, trials, paid feature gates, or commercial upgrade promotions.
  Credentials for Git and other user-selected development services are separate.
- Installed GoLand and PyCharm are reference applications. Do not modify their
  installation, user configuration, license state, or project data.
- Use REA for shipped-artifact and runtime claims; normal tools for our source.
  Record observation, inference, and unknown separately in `docs/reference`.
- Do not copy or redistribute proprietary implementation code. Build the product
  from the open-source platform and independently implemented Go integrations.
- All editor/LSP positions are UTF-16. Keep versioned documents, undo, cancellation,
  unsaved edits, and project disposal correct.
- Never perform filesystem or subprocess work on the event dispatch thread.
- Own and terminate only subprocesses launched by this project.
- Development sandbox success is intermediate evidence, not standalone delivery.
- Keep the full acceptance matrix open until runtime evidence proves each item.
- Every delivered version must have a GitHub Release with real Windows `.exe`
  and macOS `.dmg` installers, checksums, and installation instructions. Build and
  check each platform's actual product; source archives alone are not a release.
- Keep a clear, accurate Chinese README alongside the English README. Include a
  GIF of an example project actually running in the native IDE, identify its
  recorded version and system, and refresh it when the demonstrated workflow
  materially changes. Do not substitute mock UI for runtime captures.
- Keep local test artifacts bounded. Reuse pinned SDKs and tool bundles instead
  of making a full copy for every test. Check free disk space before a large
  build; retain at least 20 GiB of headroom and clean completed test outputs first
  if the build would breach that reserve.
- Keep the latest verified local installer and installed application. Remove
  older project-generated installers, application copies, and completed sandbox
  indexes/caches after retaining compact hashes, provenance, logs, and test
  results. A temporary candidate may remain while it is needed for comparison.
- Before deleting generated artifacts, check process ownership, open files, and
  current build/runtime dependencies. Preserve source, worktrees, user data,
  reference applications, needed SDKs, and GitHub Release assets. Record removed
  historical binary paths so old evidence does not imply they remain available.

Verification: `./gradlew test buildPlugin`, `cd engine && go test -race ./...`,
then actual native desktop checks and real Go project workflows. Use the
documented project launch scripts with isolated configuration directories.
