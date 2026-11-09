# Local IDE without an account

open Goland must work as an independent local Go IDE without an IDE account,
sign-in requirement, subscription, payment, trial countdown, license activation
or paid-upgrade flow. These are product requirements, not optional plans for
commercialization. Git and other explicitly used development services may still
require their own remote credentials. Copyright and third-party license notices
remain part of the distribution.

## Own-source audit

A bounded read-only audit on 2026-11-09 searched this repository's `src`,
`scripts`, `product` and `docs`; the Go engine was checked additionally. No own
implementation of account authentication, billing, paid entitlement, subscription
management, trial gating or paid-upgrade UI was found. The
[plugin descriptor](../../src/main/resources/META-INF/plugin.xml#L12) declares
the project's editor, language services, tools and actions; no own commercial
account service or action was identified there. This source result does not
establish which upstream SDK UI remains in a packaged application.

Relevant keyword matches have different meanings:

- [`subscription` in GoProfileService](../../src/main/kotlin/dev/openidea/goide/profile/GoProfileService.kt#L29)
  and [GoLintToolWindow](../../src/main/kotlin/dev/openidea/goide/lint/GoLintToolWindow.kt#L77)
  owns a closeable event listener. It has no account or payment semantics.
- [`CheckUpgrades` and `UpgradeDependency`](change-signature-evidence/source/gopls/internal/protocol/command/interface.go.txt#L98)
  in retained public gopls evidence concern Go module dependencies.
- Account and license references in historical REA records describe reference
  applications and investigation boundaries. They do not implement those flows
  in open Goland.

The [Go distribution configuration](../../product/patches/platform-go-product-2026.1.4.json)
and [packaging code](../../scripts/package-product.py#L209) keep the owned copy's
Go identity, bundled-plugin loading data and module inventories consistent.
This is build configuration, not a commercial product strategy. The source SDK
and installed reference applications are not changed by this audit.

## Packaged SDK registrations

The owned distribution removes the complete Settings Sync module registration,
including its startup activity, activation listener, services, settings page,
history tool window and actions. The active Grazie plugin and its optional
Kotlin, TOML and Maven integrations are removed. A shared logging library needed
by retained development tooling stays at its original path.

Nine authenticated XML resources and the fast plugin-loading index remove these
registrations, Try Ultimate, the GitHub Ultimate promotion, two automatic plugin
advertising producers and Check Suggested Plugins. The available-module graph
removes sixteen modules and exactly three matching optional integration edges.
Git, GitHub, GitLab, Maven, Kotlin and TOML remain in the product inventory.
The [resource reference](../reference/local-ide-distribution-reference.json)
separates registered components from dormant labels and compatibility APIs.
No license or authentication result is fabricated.

A pinned public platform patch keeps the native Installed plugin manager and
local disk installation without creating Marketplace, prices or purchase UI.
The panel does not subscribe to remote plugin updates. Six actual-SDK controls
fail on the old panel and pass on the patched panel. Arbitrary user-supplied
third-party plugins are outside this bundled-product claim.

Rewriting an optimized SDK JAR must also remove its stale absolute IKV offset
trailer. Standard ZIP parsing and code-signature verification did not catch this:
the first 408-input package passed 560 tests, then failed before native startup.
An actual IntelliJ archive-reader guard now reads every normal payload entry and
closes each rewritten archive before sealing a product. Ordinary comments,
notices and the inert reserved index payload remain intact.

## Native diagnostic observation

A private copy of that 408-input package, with only five stale ZIP trailers
cleared, starts successfully. Its native settings show only Installed; the gear
menu retains Install Plugin from Disk. An empty plugin search has no Marketplace
link. Settings search for Backup and Sync yields Nothing to show. Version Control
> GitHub still offers Add account, without entering or saving credentials.

The same session displays Types before opening a Go editor, returns correct
highlight ranges after Unicode paste and Redo, and invokes the standard global
Rerun Failed Tests action to start a real fails-only Debug session. Normal Quit
leaves eight observed process IDs absent and six fixture sources at their initial
hashes. This is a diagnostic cohort using explicitly selected older tools and an
existing isolated profile; it is not the forthcoming installer or a clean-machine
claim. The release installer and fresh bundled-tool default checks have separate
build identities and evidence. The complete IDE acceptance matrix remains open.
