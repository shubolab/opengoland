# Standalone Markdown/JCEF proxy initialization

The pinned open-source platform is IntelliJ IDEA 2026.1.4,
IC-261.26222.65. In three native sessions before this change, opening Markdown
preview logged a `ProxyMigrationService` service-creation error beneath
`JBCefApp$Holder.<clinit>`. JCEF continued startup and the application remained
usable. This is distinct from a Go-language or Markdown rendering failure.

## Startup ordering

`StandaloneProxyWarmup` uses `ApplicationInitializedListener.execute`, and only
when `idea.vendor.name` is `OpenGoland`. No project is required. The callback
awaits `Application.serviceAsync<HttpConfigurable>()` in `Dispatchers.IO`.
Normal service/state initialization requests the proxy migration service before
any Markdown editor can trigger `JBCefApp.Holder`. No service is requested from
our class/companion initialization or constructor. No proxy values, credentials,
or user configuration fields are read, modified, reset, or logged by our code.

The pinned platform's
[ApplicationLoader](https://github.com/JetBrains/intellij-community/blob/idea/2026.1.4/platform/platform-impl/bootstrap/src/com/intellij/platform/ide/bootstrap/ApplicationLoader.kt)
launches application-initialized callbacks in its structured application-load
scope (the `app initialized callback` span, followed by `callAppInitialized`).
The pinned
[`span` implementation](https://github.com/JetBrains/intellij-community/blob/idea/2026.1.4/platform/diagnostic/telemetry-impl/src/tracer.kt)
uses `withContext` on both branches, so it awaits its child coroutines.
That scope completes before `loadApp` returns the application starter. It
differs from the explicitly detached `applicationActivity` path. The warmup
therefore waits for actual service initialization, rather than scheduling work
and hoping it completes before editor restoration.

The relevant upstream chain is
[JBCefApp](https://github.com/JetBrains/intellij-community/blob/idea/2026.1.4/platform/ui.jcef/jcef/JBCefApp.java)
→ `SettingsHelper.loadArgs` →
[JBCefProxySettings](https://github.com/JetBrains/intellij-community/blob/idea/2026.1.4/platform/ui.jcef/jcef/JBCefProxySettings.java)
→ `HttpConfigurable` → `ProxyMigrationService`.

This application initialization extension point is marked internal by the
platform. It is used here as a workaround within our independently packaged IDE
with a pinned platform, not as a Marketplace plugin integration. Recheck the
startup ordering and remove the workaround when adopting an upstream fix.
The vendor guard leaves GoLand, PyCharm, and the development SDK unchanged.
Registration alone does not make any normal user IDE use our product vendor.

There is no detached coroutine, sleep, timeout-based scheduling, JCEF shutdown,
Markdown disablement, global installation patch, log filtering, or swallowed
exception. Failure and cancellation propagate to the startup lifecycle.

## Registration

The product's plugin descriptor must add this inside its existing
`com.intellij` extensions block:

```xml
<applicationInitializedListener implementation="dev.openidea.goide.lifecycle.StandaloneProxyWarmup"/>
```

No packaging, application property, or installed reference IDE changes are
needed. The standalone package already sets `idea.vendor.name=OpenGoland`.

## Verification boundary

The added deterministic tests cover an initialization barrier held by an
explicit coroutine signal, continuing startup only after service readiness,
vendor isolation, failure propagation, cancellation, and off-EDT execution
even when invoked from the EDT. They use no delay and do not open an IDE.

Compilation and test execution are pending the root agent's serialized Gradle
run. The native regression is also pending and must use a new packaged source
snapshot, not the currently running fixed snapshot.

In the next native process, compare the new log markers:

- `open Goland proxy warmup: starting before application starter`
- `open Goland proxy warmup: completed before application starter`

The completion marker must precede the first Markdown `attachHtmlPanel`/JCEF
startup log. First open Markdown preview and verify rendered content, edit the
Markdown source and confirm the preview updates, close/reopen it, and verify
there is no new `ProxyMigrationService` static-initializer error in that process's
log segment. An absence of the old error without functional preview is not a
passed regression. Do not change or remove the previous sessions' evidence.
