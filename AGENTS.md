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

Verification: `./gradlew test buildPlugin`, `cd engine && go test -race ./...`,
then actual native desktop checks and real Go project workflows. Use the
documented project launch scripts with isolated configuration directories.
