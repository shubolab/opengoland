# Native entry points and highlight synchronization

This revision follows the 395-input `4433bdd` package. Its retained desktop trace
contains two error-level gopls highlight logs for line 23 while the server reports
only lines 0–21. These are `window/logMessage` notifications, not JSON-RPC error
responses. Bidirectional wrapper log order is not a strict wire-arrival clock.
The prior package also displays Classes and exposes failed Debug retry only in
the debugger More menu. Its [observations](native-workflows-native.json) remain
historical evidence, separate from this revision's build and desktop checks.

## Highlight requests and document ownership

The pinned public LSP4IJ repair now rebuilds thirteen sources and 26 classes.
Highlighting captures the native cached `DocumentEx`, its monotonic modification
sequence, and the exact UTF-16 offset/position pair. Existing connection owners
are captured before asynchronous server selection. Selected owners retain their
server instance, opened-document object, synchronizer document and configuration
epoch. A later edit followed by Undo, disconnect/reconnect, replaced document or
server cannot revive the old request or its completed cache.

The request waits on derived didOpen/flush futures, then enters the same FIFO
dispatcher as document notifications. Dispatch does not start or restart a server.
Source and connection validity are checked again before RPC and before delivery.
Each server receives its own URI and position parameters. Cancellation belongs to
the request's futures; it does not cancel shared initialization, didOpen or
didChange work. Completed no-server results are not kept as a valid cache.

The accessor returns an immutable copy of its started-server registry under the
registry monitor. `forceCreateServer` uses that same monitor, and definition
listeners iterate snapshots. This removes the newly introduced dependency on a
live HashSet view; it is not a proof of every server lifecycle interleaving.

Fourteen actual-SDK controls use real native documents, Undo, the actual upstream
synchronizer and its FIFO dispatcher. Only the protocol peer is controlled. The
same final test source has thirteen failures against the old accepted patch and
fourteen passes against the new candidate. Cases cover Unicode paste, held
notification dispatch, source edits/Undo, shared pending open/change work,
cancellation, support disposal, connection replacement and late replies, cache
reuse/reconnection and the immutable registry contract. This does not substitute
for the subsequent standalone desktop session.

The request fence is currently scoped to highlighting. Other position-sensitive
LSP features have not all adopted it; four navigation supports still have a
related static synchronization risk. Their existing native-generation guards
alone do not prove the server has received every pending document change.

## Type search title and cache reuse

The independent Go product now declares Go as its primary IDE language through
the SDK's `IdeLanguageCustomization` service. The original native class-search
host can therefore obtain the Go contributor's Types title. Other language
contributors, native models, scopes and shortcuts remain available.

The first packaged desktop check still shows Classes and logs a registration
conflict with `JavaIdeLanguageCustomization`. The controlled title test replaces
the service and does not establish actual product registration order. The copied
OSS distribution contains a separate Java-primary IDE customization plugin,
`com.intellij.java.ide`; it is distinct from Java language support.

The [Go distribution configuration](../../product/patches/platform-go-product-2026.1.4.json)
authenticates that plugin's exact two files and descriptor, excludes its directory
only from the owned generated copy, and removes only its ApplicationInfo
essential-plugin entry. Java language and JSON remain essential. The source SDK,
installed IDEs and user disabled-plugin settings are not modified. Six actual-SDK
copy controls check the transformation, preserved Java language JAR, source SDK
hashes and rejected tampering/escaping outputs. Repaired native startup and title
are separate required evidence.

The [resource reference](../reference/go-product-identity-reference.json) binds the
REA inventories and pinned public declarations. Its SDK lib/bundled-plugin XML scan
finds no other exact dependency on this plugin or its declared modules; dynamic
third-party dependencies are outside that scan.

A complete All Places catalog can serve Project queries, with authoritative
`origin=project` membership and native scope filtering. A Project catalog cannot
serve All Places. Complete source and directory guards remain intact. Relevant
changes invalidate results; unknown paths are classified through existing-parent
physical resolution in background. Cache use and navigation pause during that
classification. Proven unrelated changes retain the catalog; affected or
unclassifiable paths invalidate it. No filesystem resolution is added to the EDT.

The final controlled cohort has 22 actual-SDK tests and two actual-engine/SDK
tests. The old source baseline was run against twenty cases with six failures;
the final alias/lifecycle additions are not mislabeled as old-source RED controls.
One real-engine observation measures 3,076 ms for cold All Places and 62 ms for
subsequent cached queries plus Project switching. These are fixture observations,
not a latency guarantee. Cold selected-SDK catalog discovery can still exceed
two seconds; there is no result cap or weakened source/membership validation.

## Standard failed-test action

The session action is now an actual `AbstractRerunFailedTestsAction`, bound to its
SM console/model and native shortcut. The standard global action finds it through
the selected execution descriptor. Debug installs the restart action before the
SDK constructs its visible session tab. One console instance is shared by the
builder and action. Closing that console disables the delegate and removes its
shortcut binding; running, passed and ignored-only executions do not enable it.

The platform `MyRunProfile` captures an immutable retry plan. Its typed state
launches every exact package/subtest configuration through the ordinary execution
manager and selected executor. Advanced manual profiles retain their concrete
factory and frozen fields. Separate exact paths avoid Go's slash-component regex
cross-product. No launch is hidden inside `getRunProfile`.

Creating child settings through `RunManager.createConfiguration` resets their
Before Launch tasks to template tasks. An initial candidate's actual Go control
reproduces one task execution from the outer profile and none from its two
children. The repair uses
an outer-only peer clone with empty tasks and restores each child's frozen task
clones after creating its settings. The same control then observes two task
executions, two actual Go targets, their exact selectors and failing results.
This is a per-child execution policy; matched commercial custom-task behavior and
all task providers have not been verified.

Controlled action updates, actual global invocation, profile/executor routing and
public native-tab models are intermediate evidence. Their headless data context
and backend mock descriptor boundaries remain explicit. Subsequent standalone
Find Action/shortcut, selected-tab and More-menu observations must be read with
their own packaged build identity.

## Complete build cohorts

The first 400-input cohort (`055b1f14`) has 554 tests and one failure: the stable
highlight-cache assertion did not establish a committed, unchanged PSI baseline.
Its retained log has nearby PSI loading/tree events but no per-query snapshots,
so its unique trigger is not established. The fixture now commits first and
asserts committed state, PSI stamp and native document sequence around both
queries. Production guards are unchanged. The same strengthened 14-case source
is rerun against old and new complete JARs.

The next 400-input cohort (`a1ed9df2`) passes all 554 tests in 111 reports and
packages successfully. Its fresh desktop session verifies three highlight spans
after paste and Redo, and the built-in global failed-test action starts a real
fails-only Debug session. Its Classes title and startup conflict remain a failed
native result. These build, source, trace and process records are retained
separately before the subsequent Go distribution build.
