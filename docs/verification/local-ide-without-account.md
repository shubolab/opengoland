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

The candidate at `ff68e00` also omits the upstream Data Sharing configurable.
It removes only the exact `ConsentConfigurable` registration from the cached
core descriptor and two existing resources: `IdeaPlugin.xml` and
`PlatformExtensions.xml`. Consent services, APIs and stored choices are
unchanged. The filtered index is now 3,118,158 bytes, SHA-256
`18b298bc75d780d197a8dea17aaa637aa783e947a40753c6699c68ef91fc3089`.
The other 43 raw plugin records and three prior descriptor patches remain
preserved. This candidate has source/control evidence; its rebuilt installer
and repaired native Settings behavior are not yet verified.

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

## Independent publisher and consent boundaries

The owned product identifies its publisher as open Goland contributors, with
short name open Goland. The pinned public
[startup EULA loader](https://github.com/JetBrains/intellij-community/blob/456919a9624bae72ac12efafc35d2b916cb0c5c5/platform/platform-impl/bootstrap/src/com/intellij/platform/ide/bootstrap/eua.kt#L23-L32)
uses an explicit vendor property, otherwise the application metadata, and
returns `NonJbVendor` for an independent publisher before loading the upstream
agreement. No agreement acceptance or license state is written by this change.
Copyright and third-party notices remain.

[ConsentOptions](https://github.com/JetBrains/intellij-community/blob/456919a9624bae72ac12efafc35d2b916cb0c5c5/platform/platform-impl/src/com/intellij/ide/gdpr/ConsentOptions.java#L161-L167)
selects vendor-specific bundled consent data. The inspected core resources have
no `consents-open Goland.json`; the old native failure also shows the empty
defaults path. Ordinary release statistics require an explicit accepted
permission, and the public forced free-license product set excludes `IC`.
This is a bounded source/default-state result. It does not establish absence of
all network traffic, plugin-specific collectors, prior user choices, or
headless, TeamCity, local-test and forced-provider overrides. Removing a setting
registration neither grants consent nor disables arbitrary third-party code.

The installer smoke check writes `intellij.startup.wizard=false` and
`idea.initially.ask.config=never` only into its temporary Mac profile; Windows
uses the equivalent flags in its temporary smoke VM options. These suppress
the [startup wizard](https://github.com/JetBrains/intellij-community/blob/456919a9624bae72ac12efafc35d2b916cb0c5c5/platform/platform-impl/src/com/intellij/openapi/application/InitialConfigImportState.kt#L52-L56)
and [legacy import dialog](https://github.com/JetBrains/intellij-community/blob/456919a9624bae72ac12efafc35d2b916cb0c5c5/platform/platform-impl/initial-config-import/src/com/intellij/openapi/application/ConfigImportHelper.java#L254-L269)
for the isolated command-line check. `idea.initially.ask.config=false` alone
does not cover both paths. No imported-settings or accepted-consent marker is
fabricated, and normal product first-run behavior is unchanged. The complete
import wizard's possible account or promotion pages remain unverified; its
public orchestrator and stage names do not prove which pages execute.

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

The later 435-input installer cohort, digest
`aed7531cb9b21686c16e054f0acf9b2c67b88e94eb98f0d900ac113d26530978`,
is separately archived under `.tools/installer-435-native-cohort`. Its fresh
`-version` launch first showed Import Settings; after Skip Import it printed the
platform version and exited. Native Settings then repeatedly failed to create
the Data Sharing configurable: the empty immutable consent list was passed to
`removeIf`. This is an observed failure of that old cohort, not a successful
validation of `ff68e00`.

The new candidate passes 32 Python controls and 24 actual-SDK metadata controls,
including exact consent-registration removal and preservation of the consent
service API and other index records. These are separate control suites, not
additional Gradle/native acceptance counts. The source and evidence bindings are
in the [reference record](../reference/local-ide-distribution-reference.json).
The subsequent local `6fd063a` package has 438 frozen inputs, digest
`0fec796ade10f1e1da8b507bef60f199f01c38eca653db8f6b8fde7942cd6f08`.
Its full DMG driver passes image verification, read-only mount and installation
copy, strict signature and sealed-file checks, unattended cold-profile native
version, bundled tool versions and one uncached Go engine test. Its 564 platform
tests pass with zero failures, errors or skips. The unchanged Go checks are
explicitly reused from their authenticated fresh local record, not presented as
a new Go test execution.

A separate native session uses a fresh profile and empty owned Go caches, with
host Go/JDK settings stripped and no executable fields entered. Its first real
Debug test passes one test in 7.601 seconds; target, adapter and IDE execution
all exit zero. The original Data Sharing page is absent, `consents` yields
Nothing to show, and Settings search no longer emits the old exception. Normal
Quit closes the app and all three retained process IDs are absent. The archive
is `.tools/installer-6fd-native-cohort`.

This is a local Mac installer cohort, not the future CI release artifact or
the latest source. Resource-constrained first-use `test2json` preparation is a
separate investigation. Windows installation and all full-IDE acceptance rows
remain open.
