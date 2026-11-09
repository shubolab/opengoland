# Platform document-listener initialization

The second complete native-workflows build has 521 tests and 62 failures. Many
failures share the same platform `PsiChangeHandler` null-Alarm callback; other
errors reference a temporarily disposed shared light project. The failed build
and its original input digest are retained separately. No standalone app is
delivered from that failed build.

The actual SDK has a final `myUpdateFileStatusAlarm` field. Its constructor
publishes a callback capturing `this` before assigning that field. There is no
disposal path that sets the field to null. The first logged NPE precedes both the
new indexing control and the gutter helper's indexing wait. Ordered Events→Corpus
controls using the failed build's instrumented test bytes pass 51 executions in
private JVMs without this NPE. Different loading/scheduling and fresh configuration
directories prevent those controls from negating the original full-suite failure.

A deterministic actual-SDK control keeps the real first document-after-commit
listener registration, injects cancellation at the second registration, and then
performs a native document edit and PSI commit. It reproduces the exact null-Alarm
failure without fabricating a handler field or service state. Moving the existing
Alarm initialization before callback publication makes that same control pass.
This proves an interrupted-construction mechanism; the original full-suite log
does not identify what first interrupted construction, so that trigger remains
unknown.

The independent repair changes only the initialization order in the pinned public
Apache-licensed platform source. The [pin](../../product/patches/platform-psi-listener-2026.1.4.json)
authenticates source, original SDK JAR, patch and required class names. The shared
patch builder verifies all rebuilt class bytes and every untouched JAR entry.
The source SDK remains read-only. Product packaging installs the verified complete
JAR only into its owned app copy and records it as a separate platform patch.
[Public source](https://github.com/JetBrains/intellij-community/blob/456919a9624bae72ac12efafc35d2b916cb0c5c5/platform/lang-impl/src/com/intellij/codeInsight/daemon/impl/PsiChangeHandler.java).

The complete test runtime replaces the single original language-implementation
JAR before the default platform class loader starts. An isolated overlay alone
cannot prove the full test JVM loaded the repaired class. Regression assertions
therefore check default-loader identity, the unique class-resource JAR provider
and all three resource hashes against the generated manifest, together with the
interrupted-constructor behavior in that default loader. Code-source locations
are checked when available; this SDK loader can return a null location, which
must remain explicit rather than be invented. The common build record binds whole-suite
results and keeps both failed cohorts distinct.

This is not proof of every daemon, project-disposal, cancellation or document
lifetime interleaving. The complete robustness acceptance row remains open.
