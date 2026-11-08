# Independent product distribution

The final product is an independent Go IDE based on the open-source IntelliJ
Platform. A successful development plugin build or a PyCharm sandbox launch is
an intermediate checkpoint, not evidence that this product is complete.

The product's display name is **open Goland**. The application is generated at
`build/product/open Goland.app`, with the `opengoland` command-line entry point
and bundle ID `dev.opengoland.ide`.

## Development SDK

`scripts/dev.sh` launches the selected IntelliJ SDK using Gradle's isolated
development sandbox. It prefers the pinned open-source IDEA platform installed
by `scripts/bootstrap-platform.sh`, then the locally installed PyCharm app.
Set `OPENIDEA_PLATFORM_PATH` to select another compatible installation. Installed
application files and the user's normal IDE configuration are not modified.

The plugin depends only on the IntelliJ Platform module. It does not depend on
the official Go plugin. PyCharm is useful as a development host precisely
because Go support must come from this project's own integration.

## Standalone build route

JetBrains publishes open-source IntelliJ IDEA installers on the
[intellij-community releases page](https://github.com/JetBrains/intellij-community/releases).
They are distinct from the unified commercial IntelliJ IDEA distribution.
For a distributable standalone product, pin an open-source platform release or
source revision, include this project's Go integration, and build the product
with its own application metadata, launchers, configuration directories and
packaging. Preserve the platform and third-party notices.

`scripts/package-product.sh` assembles a macOS Apple silicon app from the pinned,
SHA-256-verified open-source IDEA installer and this project's prepared plugins.
It supplies an application identity, launcher and isolated configuration paths,
updates product names in the copied OSS application-info resources, preserves
upstream notices, and applies an ad-hoc signature for local execution. It refuses
to package an arbitrary installed commercial IDE as its base.

The 2026 platform uses a generated bundled-module repository, so copying a new
plugin into `Contents/plugins` alone does not make it load. The product launcher
explicitly lists the Go integration and LSP4IJ directories in `plugin.path` using
the native launcher's relocatable `$APP_PACKAGE` variable. Runtime verification
must confirm both plugins are loaded; file presence and a successful package
signature are insufficient. Vendor and configuration-selector JVM arguments are
also replaced consistently rather than appended after the upstream values.

The pinned platform embeds application-info data in compiled classes. The
supported `intellij.platform.load.app.info.from.resources=true` property makes
the assembled product use its branded XML resources instead. This follows the
upstream [ApplicationNamesInfo loading path](https://github.com/JetBrains/intellij-community/blob/idea/261.26222.65/platform/core-api/src/com/intellij/openapi/application/ApplicationNamesInfo.java).

The source build entry point is `installers.cmd` in the upstream repository.
The project must select and configure its own product modules and bundled
plugins; changing the title of a commercial GoLand installation is not this
build route.

Official references:

- [Building third-party IDEs on the IntelliJ Platform](https://plugins.jetbrains.com/docs/intellij/intellij-platform.html)
- [Open-source builds and the unified distribution](https://lp.jetbrains.com/intellij-idea-unified-faq/)
- [IntelliJ Community source build instructions](https://github.com/JetBrains/intellij-community)

The assembled macOS app has passed local ad-hoc signature verification and an
independent launch with both custom plugins loaded. Selected native workflows
and outstanding runtime defects are recorded in
`docs/verification/desktop-smoke-2026-11-08.md`. This is development evidence;
cross-platform installers, distribution signing, and full product acceptance
remain open tasks.
