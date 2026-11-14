package com.redhat.devtools.lsp4ij

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.LightPlatformTestCase
import com.redhat.devtools.lsp4ij.client.features.LSPClientFeatures
import com.redhat.devtools.lsp4ij.client.LanguageClientImpl
import com.redhat.devtools.lsp4ij.server.StreamConnectionProvider
import com.redhat.devtools.lsp4ij.server.definition.LanguageServerDefinition
import dev.openidea.goide.lsp.GoLanguageClient
import dev.openidea.goide.lsp.GoDocumentSync
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.Launcher
import org.eclipse.lsp4j.jsonrpc.RemoteEndpoint
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageServer
import org.eclipse.lsp4j.services.TextDocumentService
import org.eclipse.lsp4j.services.WorkspaceService
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.Proxy
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Actual client/wrapper capability handling with held startup stages; no native gopls claim. */
class GoCapabilityRegistrationLifecycleTest : LightPlatformTestCase() {
    override fun runInDispatchThread() = false

    fun testHeldInitializationPreservesRegisterThenUnregisterOrder() = Fixture().use { f ->
        val registration = f.client.registerCapability(f.registration("held"))
        val unregistration = f.client.unregisterCapability(f.unregistration("held"))
        pending(registration)
        pending(unregistration)
        assertTrue(f.handlers().isEmpty())
        f.initialization.complete(null)
        registration.await(); unregistration.await()
        assertTrue("A deferred registration must not revive after its unregister ACK", f.handlers().isEmpty())
        f.assertNoExternalRoots()
    }

    fun testCancellingCallerViewDoesNotLetUnregisterPassTheInternalRegistration() = Fixture().use { f ->
        val registration = f.client.registerCapability(f.registration("cancelled", external = true))
        assertTrue(registration.cancel(false))
        val unregistration = f.client.unregisterCapability(f.unregistration("cancelled"))
        pending(unregistration)
        assertFalse("A request owns no shared initialization", f.initialization.isCancelled)
        f.initialization.complete(null)
        unregistration.await()
        assertTrue(registration.isCancelled)
        assertTrue(f.handlers().isEmpty())
        f.assertNoExternalRoots()
    }

    fun testInitializationFailureFailsTheRegistrationAndReleasesTheFollowingUnregister() = Fixture().use { f ->
        val registration = f.client.registerCapability(f.registration("failed", external = true))
        val unregistration = f.client.unregisterCapability(f.unregistration("failed"))
        val failure = IllegalStateException("owned initialization failure")
        f.initialization.completeExceptionally(failure)
        val rejected = runCatching { registration.await() }.exceptionOrNull()
        assertTrue(generateSequence(rejected) { it.cause }.any { it === failure })
        unregistration.await()
        assertTrue(f.handlers().isEmpty())
        f.assertNoExternalRoots()
    }

    fun testRegistrationCanFinishInsideInitializedBeforeTheStartupFutureFinishes() = Fixture().use { f ->
        val ready = f.bindContext()
        // The real startup chain publishes this gate after server capabilities,
        // before invoking initialized. Its final initializeFuture is still held.
        ready.complete(null)
        f.client.registerCapability(f.registration("initialized")).await()
        assertFalse("The initialized callback must not have to finish startup to receive its ACK", f.initialization.isDone)
        assertTrue(f.handlers().containsKey("initialized"))
        f.client.unregisterCapability(f.unregistration("initialized")).await()
        assertTrue(f.handlers().isEmpty())
    }

    fun testStopCancelsWaitingRegistrationsAndLateUncancellableStartupCannotReviveThem() = Fixture().use { f ->
        val ready = f.bindContext()
        val registration = f.client.registerCapability(f.registration("stopped", external = true))
        pending(registration)
        f.wrapper.stop().await()
        rejected(registration)
        ready.complete(null); f.initialization.complete(null)
        assertTrue(f.handlers().isEmpty())
        f.assertNoExternalRoots()
    }

    fun testDisposedWrapperRejectsLateStartupAndNewRegistration() = Fixture().use { f ->
        val ready = f.bindContext()
        val registration = f.client.registerCapability(f.registration("disposed", external = true))
        pending(registration)
        f.wrapper.dispose()
        rejected(registration)
        ready.complete(null); f.initialization.complete(null)
        rejected(invokeBaseCapability(f.client, "registerCapability", f.registration("after-dispose")))
        assertTrue(f.handlers().isEmpty())
        f.assertNoExternalRoots()
    }

    fun testCancellingOneOwnedRegistrationDoesNotCancelSharedStartupOrAnotherRegistration() = Fixture().use { f ->
        val cancelled = f.registerAsync(f.registration("cancelled"))
        val retained = f.registerAsync(f.registration("retained"))
        assertTrue(cancelled.cancel(false))
        assertFalse(f.initialization.isCancelled)
        f.initialization.complete(null)
        retained.await()
        assertEquals(setOf("retained"), f.handlers().keys)
        f.client.unregisterCapability(f.unregistration("retained")).await()
    }

    fun testStoppedGenerationCannotRegisterIntoItsReplacement() = Fixture().use { f ->
        val oldReady = f.bindContext()
        val oldInitialization = f.initialization
        val old = f.registerAsync(f.registration("old"))
        f.wrapper.stop().await()
        rejected(old)
        f.initialization = UncancellableFuture()
        f.setField("initializeFuture", f.initialization)
        f.replaceClient()
        val currentReady = f.bindContext()
        val current = f.client.registerCapability(f.registration("current"))
        oldReady.complete(null); oldInitialization.complete(null)
        assertTrue(f.handlers().isEmpty())
        currentReady.complete(null)
        current.await()
        assertEquals(setOf("current"), f.handlers().keys)
        f.client.unregisterCapability(f.unregistration("current")).await()
    }

    fun testRealStartupAllowsTheInitializedPeerToAwaitItsRegistrationAck() = StartupFixture().use { f ->
        val peer = StartupPeer(blocked = false, registerDuringInitialized = true)
        f.start(peer)
        assertSame(peer.remote, f.wrapper.initializedServer.get(10, TimeUnit.SECONDS))
        assertEquals(ServerStatus.started, f.wrapper.serverStatus)
        assertFalse("The callback completed its real client registration ACK", peer.registrationFailed.get())
    }

    fun testRealStartedClientOwnedScopeInstallsTheActualMatcherAndColdRoot() = StartupFixture().use { f ->
        val peer = StartupPeer(blocked = false)
        f.start(peer)
        assertSame(peer.remote, f.wrapper.initializedServer.get(10, TimeUnit.SECONDS))
        f.assertWatchers(a = false, b = false)
        f.registerOwned("owned-local-scope", setOf("a")).await()
        assertTrue("The real wrapper must retain an unregistration handler for the owned matcher", f.handlers().containsKey("owned-local-scope"))
        f.assertWatchers(a = true, b = false)
        assertNotNull("The same production operation must also prime the never-opened dependency source",
            LocalFileSystem.getInstance().findFileByPathIfCached(f.path("a/external.go").toString()))
        f.client().unregisterCapability(UnregistrationParams(listOf(Unregistration("owned-local-scope", "workspace/didChangeWatchedFiles")))).await()
        f.assertWatchers(a = false, b = false)
        f.assertNoOwnedRoots(f.client())
    }

    fun testRealStartedClientOwnedScopeRejectsIncompleteRootCoverage() = StartupFixture().use { f ->
        f.start(StartupPeer(blocked = false))
        f.wrapper.initializedServer.get(10, TimeUnit.SECONDS)
        val failure = runCatching { f.registerOwned("owned-incomplete", setOf("a", "missing")).await() }.exceptionOrNull()
        assertNotNull("A successful matcher registration alone must not ACK incomplete VFS coverage", failure)
        assertTrue(failure.toString(), generateSequence(failure) { it.cause }.any {
            it is IllegalStateException && it.message.orEmpty().contains("could not be watched")
        })
        // The coordinator owns rollback after a rejected install; exercise its
        // actual client unregistration entry point here rather than fake leases.
        f.client().unregisterCapability(UnregistrationParams(listOf(Unregistration("owned-incomplete", "workspace/didChangeWatchedFiles")))).await()
        f.assertWatchers(a = false, b = false)
        f.assertNoOwnedRoots(f.client())
    }

    fun testRecreatedDirectoryReconciliationNotifiesTheBoundStartedConnection() = StartupFixture().use { f ->
        val peer = StartupPeer(blocked = false)
        f.start(peer)
        f.wrapper.initializedServer.get(10, TimeUnit.SECONDS)
        val expected = GoDocumentSync.fileUri(f.path("a"))
        f.reconcileOwned(setOf("a")).await()
        Files.move(f.path("a"), f.path("parked-a"))
        f.reconcileOwned(setOf("a")).await()
        Files.writeString(Files.createDirectory(f.path("a")).resolve("external.go"), "package external\n")
        f.reconcileOwned(setOf("a")).await()
        val changes = peer.watchedChanges.map { it.changes.single() }
        println("CONTROLLED_PEER_EXISTS_MISSING_RECREATED_URIS=${JSONUtils.getLsp4jGson().toJson(changes)}")
        assertEquals("The same directory has one lexical URI while present, missing and recreated",
            List(3) { expected }, changes.map { it.uri })
        assertEquals(List(3) { FileChangeType.Changed }, changes.map { it.type })
    }

    fun testDirectoryReconciliationPreservesPreviouslyQueuedVfsNotificationOrder() = StartupFixture().use { f ->
        val peer = StartupPeer(blocked = false)
        f.start(peer)
        f.wrapper.initializedServer.get(10, TimeUnit.SECONDS)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val dispatcher = f.field("dispatcher") as ExecutorService
        val held = dispatcher.submit { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
        assertTrue("The actual wrapper dispatcher must be held", entered.await(10, TimeUnit.SECONDS))
        try {
            val deleted = FileEvent(f.path("a/go.mod").toUri().toASCIIString(), FileChangeType.Deleted)
            val listener = requireNotNull(f.field("fileListener"))
            listener.javaClass.superclass.getDeclaredMethod("didChangeWatchedFiles", List::class.java).apply { isAccessible = true }
                .invoke(listener, listOf(deleted))
            val reconciled = f.reconcileOwned(setOf("a"))
            // Observe whether the real client can overtake the held dispatcher.
            // A correctly queued reconciliation is allowed to remain pending.
            val completedBeforeRelease = try {
                reconciled.get(500, TimeUnit.MILLISECONDS)
                true
            } catch (_: TimeoutException) { false }
            println("CONTROLLED_PEER_RECONCILE_COMPLETED_BEFORE_DISPATCH_RELEASE=$completedBeforeRelease")
            release.countDown()
            held.get(10, TimeUnit.SECONDS)
            f.wrapper.sendNotification { it }.get(10, TimeUnit.SECONDS)
            reconciled.await()
            val actual = peer.watchedChanges.flatMap { it.changes }.map { it.uri to it.type }
            println("CONTROLLED_PEER_WATCHED_SERVICE_INVOCATIONS=${JSONUtils.getLsp4jGson().toJson(peer.watchedChanges)}")
            assertEquals("Directory reconciliation must preserve the ordinary VFS dispatch FIFO",
                listOf(deleted.uri to FileChangeType.Deleted, GoDocumentSync.fileUri(f.path("a")) to FileChangeType.Changed), actual)
        } finally { release.countDown() }
    }

    fun testDirectoryReconciliationQueuedOnTheDispatcherCannotNotifyAfterConnectionReplacement() = StartupFixture().use { f ->
        val old = StartupPeer(blocked = false)
        f.start(old)
        f.wrapper.initializedServer.get(10, TimeUnit.SECONDS)
        val oldClient = f.client()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val dispatcher = f.field("dispatcher") as ExecutorService
        val held = dispatcher.submit { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        try {
            val notification = f.reconcileOwned(setOf("a"), oldClient)
            pending(notification)
            f.wrapper.stop().await()
            val current = StartupPeer(blocked = false)
            f.start(current)
            assertSame(current.remote, f.wrapper.initializedServer.get(10, TimeUnit.SECONDS))
            release.countDown()
            held.get(10, TimeUnit.SECONDS)
            rejected(notification)
            assertTrue("A stale dispatcher consumer cannot notify the replacement connection", current.watchedChanges.isEmpty())
            assertTrue("A disposed client cannot send to its stopped connection either", old.watchedChanges.isEmpty())
        } finally { release.countDown() }
    }

    fun testCancellableIdleStoppingCanResumeQueuedNotificationOnTheSameClientAndProxy() = StartupFixture().use { f ->
        val peer = StartupPeer(blocked = false)
        f.start(peer)
        f.wrapper.initializedServer.get(10, TimeUnit.SECONDS)
        val client = f.client()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val dispatcher = f.field("dispatcher") as ExecutorService
        val held = dispatcher.submit { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        try {
            val notification = f.reconcileOwned(setOf("a"), client)
            pending(notification)
            // Explicit client lifecycle callbacks model a cancelled idle timer;
            // the actual started wrapper and its remote proxy stay unchanged.
            client.handleServerStatusChanged(ServerStatus.stopping)
            client.handleServerStatusChanged(ServerStatus.started)
            assertSame(peer.remote, f.field("languageServer"))
            assertSame(client, f.client())
            release.countDown()
            held.get(10, TimeUnit.SECONDS)
            notification.await()
            assertEquals(listOf(GoDocumentSync.fileUri(f.path("a")) to FileChangeType.Changed),
                peer.watchedChanges.flatMap { it.changes }.map { it.uri to it.type })
        } finally { release.countDown() }
    }

    fun testQueuedAndLateOldDirectoryReconciliationCannotNotifyAReplacementServer() = StartupFixture().use { f ->
        val old = StartupPeer(blocked = false)
        f.start(old)
        f.wrapper.initializedServer.get(10, TimeUnit.SECONDS)
        val client = f.client()
        val barrier = CompletableFuture<Void>()
        GoLanguageClient::class.java.getDeclaredField("capabilityQueue").apply { isAccessible = true }.set(client, barrier)
        val pending = f.reconcileOwned(setOf("a"), client)
        assertFalse(pending.isDone)
        try {
            f.wrapper.stop().await()
            val current = StartupPeer(blocked = false)
            f.start(current)
            f.wrapper.initializedServer.get(10, TimeUnit.SECONDS)
            barrier.complete(null)
            rejected(pending)
            // Capture after B has started, too: a disposed old client must not
            // adopt B through the wrapper's now-current languageServer getter.
            rejected(f.reconcileOwned(setOf("a"), client))
            assertTrue("The obsolete refresh must not enter the new server", current.watchedChanges.isEmpty())
            assertTrue("The blocked old connection did not receive a late event", old.watchedChanges.isEmpty())
        } finally { barrier.complete(null) }
    }

    fun testStoppedRealClientCannotAcknowledgeAnOwnedScopeAsInstalled() = StartupFixture().use { f ->
        f.start(StartupPeer(blocked = false))
        f.wrapper.initializedServer.get(10, TimeUnit.SECONDS)
        val stopped = f.client()
        f.wrapper.stop().await()
        rejected(f.registerOwned("owned-after-stop", setOf("b"), stopped))
        f.assertWatchers(a = false, b = false)
        f.assertNoOwnedRoots(stopped)
    }

    fun testStoppingDuringInitializedCannotPublishTheOldConnection() = StartupFixture().use { f ->
        val peer = StartupPeer(blocked = true)
        f.start(peer)
        val oldPipeline = f.retainUncancellableStartup()
        f.wrapper.stop().await()
        peer.release.countDown()
        oldPipeline.handle { _, _ -> null }.get(10, TimeUnit.SECONDS)
        assertNull("An old initialized callback cannot republish a stopped connection", f.field("languageServer"))
        assertEquals(ServerStatus.stopped, f.wrapper.serverStatus)
    }

    fun testLateInitializedCancellationCannotStopOrPoisonTheReplacementServer() = StartupFixture().use { f ->
        val old = StartupPeer(blocked = true, rejectAfterRelease = true)
        f.start(old)
        val oldPipeline = f.retainUncancellableStartup()
        f.wrapper.stop().await()
        val current = StartupPeer(blocked = false)
        f.start(current)
        assertSame(current.remote, f.wrapper.initializedServer.get(10, TimeUnit.SECONDS))
        old.release.countDown()
        oldPipeline.handle { _, _ -> null }.get(10, TimeUnit.SECONDS)
        assertSame(current.remote, f.field("languageServer"))
        assertEquals("Stale cancellation cannot stop the current generation", ServerStatus.started, f.wrapper.serverStatus)
        assertNull("Stale cancellation cannot poison the current startup", f.field("serverError"))
        assertEquals(0, current.shutdowns.get())
    }

    fun testLateInitializeResponseCannotOverwriteTheReplacementCapabilities() = StartupFixture().use { f ->
        val old = StartupPeer(blocked = false, holdInitialize = true)
        f.start(old, awaitInitialized = false)
        val oldPipeline = f.retainUncancellableStartup()
        f.wrapper.stop().await()
        val current = StartupPeer(blocked = false)
        f.start(current)
        assertSame(current.remote, f.wrapper.initializedServer.get(10, TimeUnit.SECONDS))
        old.initializeReply.complete(InitializeResult(old.capabilities))
        oldPipeline.handle { _, _ -> null }.get(10, TimeUnit.SECONDS)
        assertSame("A stale initialize response cannot overwrite current capabilities", current.capabilities, f.wrapper.serverCapabilitiesSync)
        assertSame(current.remote, f.field("languageServer"))
        assertEquals(ServerStatus.started, f.wrapper.serverStatus)
        assertNull(f.field("serverError"))
    }

    fun testOldClientRegisterBeforeFirstCaptureCannotInstallIntoTheReplacement() = StartupFixture().use { f ->
        val old = StartupPeer(blocked = false); f.start(old)
        f.wrapper.initializedServer.get(10, TimeUnit.SECONDS)
        HeldClientCall(old.client as GoLanguageClient, "registerCapability", f.watchRegistration("late-old", "a")).use { held ->
            held.started()
            f.wrapper.stop().await()
            val current = StartupPeer(blocked = false); f.start(current)
            f.wrapper.initializedServer.get(10, TimeUnit.SECONDS)
            current.client.registerCapability(f.watchRegistration("shared", "b")).await()
            f.assertWatchers(a = false, b = true)
            held.finish()
            f.assertWatchers(a = false, b = true)
            assertEquals(setOf("shared"), f.handlers().keys)
        }
    }

    fun testOldClientRegisterWithReusedIdCannotReplaceTheCurrentWatcher() = StartupFixture().use { f ->
        val old = StartupPeer(blocked = false); f.start(old)
        f.wrapper.initializedServer.get(10, TimeUnit.SECONDS)
        HeldClientCall(old.client as GoLanguageClient, "registerCapability", f.watchRegistration("shared", "a")).use { held ->
            held.started()
            f.wrapper.stop().await()
            val current = StartupPeer(blocked = false); f.start(current)
            f.wrapper.initializedServer.get(10, TimeUnit.SECONDS)
            current.client.registerCapability(f.watchRegistration("shared", "b")).await()
            f.assertWatchers(a = false, b = true)
            held.finish()
            f.assertWatchers(a = false, b = true)
            assertEquals(setOf("shared"), f.handlers().keys)
        }
    }

    fun testOldClientUnregisterBeforeFirstCaptureCannotRemoveTheCurrentWatcher() = StartupFixture().use { f ->
        val old = StartupPeer(blocked = false); f.start(old)
        f.wrapper.initializedServer.get(10, TimeUnit.SECONDS)
        val params = UnregistrationParams(listOf(Unregistration("shared", "workspace/didChangeWatchedFiles")))
        HeldClientCall(old.client as GoLanguageClient, "unregisterCapability", params).use { held ->
            held.started()
            f.wrapper.stop().await()
            val current = StartupPeer(blocked = false); f.start(current)
            f.wrapper.initializedServer.get(10, TimeUnit.SECONDS)
            current.client.registerCapability(f.watchRegistration("shared", "b")).await()
            f.assertWatchers(a = false, b = true)
            held.finish()
            f.assertWatchers(a = false, b = true)
            assertEquals(setOf("shared"), f.handlers().keys)
        }
    }

    /** Models an action already past GoClient's closed check, paused before its super entry. */
    private inner class HeldClientCall(client: GoLanguageClient, method: String, params: Any) : AutoCloseable {
        private val entered = CountDownLatch(1)
        private val release = CountDownLatch(1)
        private val executor = Executors.newSingleThreadExecutor()
        private val completion = CompletableFuture.supplyAsync({
            entered.countDown(); check(release.await(10, TimeUnit.SECONDS))
            invokeBaseCapability(client, method, params)
        }, executor).thenCompose { it }
        fun started() { assertTrue(entered.await(10, TimeUnit.SECONDS)) }
        fun finish() { release.countDown(); completion.handle { _, _ -> null }.get(10, TimeUnit.SECONDS) }
        override fun close() { try { finish() } finally { executor.shutdownNow() } }
    }

    @Suppress("UNCHECKED_CAST")
    private fun invokeBaseCapability(client: GoLanguageClient, method: String, params: Any): CompletableFuture<Void> {
        val type = MethodType.methodType(CompletableFuture::class.java, params.javaClass)
        val lookup = MethodHandles.privateLookupIn(GoLanguageClient::class.java, MethodHandles.lookup())
        return lookup.findSpecial(LanguageClientImpl::class.java, method, type, GoLanguageClient::class.java)
            .invokeWithArguments(client, params) as CompletableFuture<Void>
    }

    /** Real wrapper start/init/stop stages; only its remote peer and streams are controlled. */
    private inner class StartupFixture : AutoCloseable {
        private val root = Files.createTempDirectory("owned capability client 中文😀 ").toRealPath()
        private val lifetime = Disposer.newDisposable("owned capability client roots")
        private val peers = ConcurrentLinkedQueue<StartupPeer>()
        private val owned = mutableListOf<StartupPeer>()
        private var building: StartupPeer? = null
        private val definition = object : LanguageServerDefinition("owned-capability-startup", "Owned startup regression", "", false, null, false, false) {
            override fun createConnectionProvider(project: Project): StreamConnectionProvider = requireNotNull(peers.poll()).also { building = it }.provider
            override fun createLanguageClient(project: Project): LanguageClientImpl = GoLanguageClient(project)
        }
        val wrapper = object : LanguageServerWrapper(project, definition) {
            private val closed = AtomicBoolean()
            private val features = object : LSPClientFeatures() {
                override fun <S : LanguageServer> createLauncherBuilder(): Launcher.Builder<S> {
                    val peer = requireNotNull(building)
                    return object : Launcher.Builder<S>() {
                        @Suppress("UNCHECKED_CAST")
                        override fun createProxy(endpoint: RemoteEndpoint): S {
                            peer.client = localServices.single() as LanguageClient
                            return peer.remote as S
                        }
                    }
                }
            }.also { it.setServerWrapper(this) }
            override fun getClientFeatures() = features
            override fun dispose() {
                if (closed.compareAndSet(false, true)) { super.dispose(); features.dispose() }
            }
        }
        init {
            VfsRootAccess.allowRootAccess(lifetime, root.toString())
            for (name in listOf("a", "b")) Files.writeString(Files.createDirectories(root.resolve(name)).resolve("external.go"), "package external\n")
        }

        fun start(peer: StartupPeer, awaitInitialized: Boolean = true) {
            owned += peer; peers.offer(peer); wrapper.start()
            assertTrue("The actual startup must reach initialize", peer.initializeEntered.await(10, TimeUnit.SECONDS))
            if (awaitInitialized) assertTrue("The actual startup must reach initialized", peer.entered.await(10, TimeUnit.SECONDS))
        }
        fun field(name: String): Any? = LanguageServerWrapper::class.java.getDeclaredField(name).apply { isAccessible = true }.get(wrapper)
        @Suppress("UNCHECKED_CAST")
        fun handlers() = synchronized(wrapper) { field("dynamicRegistrations") as Map<String, Runnable> }
        fun watchRegistration(id: String, directory: String) = RegistrationParams(listOf(Registration(id, "workspace/didChangeWatchedFiles",
            JSONUtils.getLsp4jGson().toJsonTree(DidChangeWatchedFilesRegistrationOptions(listOf(FileSystemWatcher().apply {
                setGlobPattern(root.resolve(directory).toString().replace('\\', '/') + "/**/*.go"); kind = WatchKind.Change
            }))))))
        fun path(relative: String) = root.resolve(relative)
        fun client() = requireNotNull(field("languageClient")) as GoLanguageClient
        @Suppress("UNCHECKED_CAST")
        fun registerOwned(id: String, directories: Set<String>, client: GoLanguageClient = client()): CompletableFuture<Void> {
            // This is the synthetic local registration entry used by discovered
            // dependencies, not the already-deserialized server RPC path.
            val entry = GoLanguageClient::class.java.getDeclaredMethod("registerDependencyScope", String::class.java, Set::class.java)
                .apply { isAccessible = true }
            return entry.invoke(client, id, directories.mapTo(linkedSetOf()) { root.resolve(it) }) as CompletableFuture<Void>
        }
        @Suppress("UNCHECKED_CAST")
        fun reconcileOwned(directories: Set<String>, client: GoLanguageClient = client()): CompletableFuture<Void> {
            val entry = GoLanguageClient::class.java.getDeclaredMethod("reconcileDependencyScope", Set::class.java)
                .apply { isAccessible = true }
            return entry.invoke(client, directories.mapTo(linkedSetOf()) { root.resolve(it) }) as CompletableFuture<Void>
        }
        fun assertNoOwnedRoots(client: GoLanguageClient) {
            val owner = GoLanguageClient::class.java.getDeclaredField("externalFiles").apply { isAccessible = true }.get(client)
            val roots = owner.javaClass.getDeclaredField("roots").apply { isAccessible = true }.get(owner) as Map<*, *>
            assertTrue("Failed/unregistered owned scope must not retain independent VFS roots", roots.isEmpty())
        }
        fun assertWatchers(a: Boolean, b: Boolean) {
            val listener = requireNotNull(field("fileListener"))
            val manager = listener.javaClass.superclass.getDeclaredField("fileSystemWatcherManager").apply { isAccessible = true }.get(listener)
            val matches = manager.javaClass.getMethod("isMatchFilePattern", URI::class.java, Int::class.javaPrimitiveType)
            assertEquals("Old-client tasks must not install or replace watcher A", a, matches.invoke(manager, root.resolve("a/external.go").toUri(), WatchKind.Change))
            assertEquals("Old-client tasks must retain current watcher B", b, matches.invoke(manager, root.resolve("b/external.go").toUri(), WatchKind.Change))
        }
        @Suppress("UNCHECKED_CAST")
        fun retainUncancellableStartup(): CompletableFuture<Void> {
            val actual = field("initializeFuture") as CompletableFuture<Void>
            val retained = UncancellableFuture()
            actual.whenComplete { _, error -> if (error == null) retained.complete(null) else retained.completeExceptionally(error) }
            LanguageServerWrapper::class.java.getDeclaredField("initializeFuture").apply { isAccessible = true }.set(wrapper, retained)
            return actual
        }
        override fun close() {
            owned.forEach { it.release.countDown() }
            wrapper.dispose()
            owned.forEach { it.provider.stop() }
            Disposer.dispose(lifetime); root.toFile().deleteRecursively()
        }
    }

    private class StartupPeer(
        blocked: Boolean,
        private val rejectAfterRelease: Boolean = false,
        private val registerDuringInitialized: Boolean = false,
        holdInitialize: Boolean = false,
    ) {
        val capabilities = ServerCapabilities()
        val initializeReply = CompletableFuture<InitializeResult>().also { if (!holdInitialize) it.complete(InitializeResult(capabilities)) }
        val initializeEntered = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(if (blocked) 1 else 0)
        val shutdowns = AtomicInteger()
        val registrationFailed = AtomicBoolean()
        val watchedChanges = ConcurrentLinkedQueue<DidChangeWatchedFilesParams>()
        lateinit var client: LanguageClient
        private val stopped = CountDownLatch(1)
        val provider = object : StreamConnectionProvider {
            private val input = object : InputStream() {
                override fun read(): Int { stopped.await(30, TimeUnit.SECONDS); return -1 }
            }
            override fun start() {}
            override fun getInputStream() = input
            override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()
            override fun isAlive() = stopped.count > 0
            override fun stop() { stopped.countDown() }
        }
        private fun service(type: Class<*>): Any = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.get(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "Owned ${type.simpleName}"
                "didChangeWatchedFiles" -> { watchedChanges.add(args!![0] as DidChangeWatchedFilesParams); null }
                else -> if (CompletableFuture::class.java.isAssignableFrom(method.returnType)) CompletableFuture.completedFuture<Any>(null) else null
            }
        }
        private val text = service(TextDocumentService::class.java)
        private val workspace = service(WorkspaceService::class.java)
        val remote = Proxy.newProxyInstance(LanguageServer::class.java.classLoader, arrayOf(LanguageServer::class.java)) { proxy, method, args ->
            when (method.name) {
                "initialize" -> { initializeEntered.countDown(); initializeReply }
                "initialized" -> {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    if (rejectAfterRelease) throw CancellationException("owned late initialized cancellation")
                    if (registerDuringInitialized) {
                        val registration = RegistrationParams(listOf(Registration("inside-initialized", "workspace/executeCommand",
                            JSONUtils.getLsp4jGson().toJsonTree(ExecuteCommandOptions(listOf("owned.initialized"))))))
                        try { client.registerCapability(registration).get(5, TimeUnit.SECONDS) }
                        catch (failure: Exception) { registrationFailed.set(true); throw failure }
                    }
                    null
                }
                "shutdown" -> { shutdowns.incrementAndGet(); CompletableFuture.completedFuture<Any>(null) }
                "getTextDocumentService" -> text
                "getWorkspaceService" -> workspace
                "exit" -> null
                "equals" -> proxy === args?.get(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "Owned startup peer"
                else -> null
            }
        } as LanguageServer
    }

    private inner class Fixture : AutoCloseable {
        private val root = Files.createTempDirectory("owned capability startup 中文😀 ").toRealPath()
        private val lifetime = Disposer.newDisposable("owned capability registration")
        private val clients = mutableListOf<GoLanguageClient>()
        val wrapper = Wrapper(project, requireNotNull(LanguageServersRegistry.getInstance().getServerDefinition("openidea.gopls")))
        var client = GoLanguageClient(project).also { it.setServerWrapper(wrapper); clients += it }
        var initialization = UncancellableFuture()
        init {
            VfsRootAccess.allowRootAccess(lifetime, root.toString())
            Files.writeString(root.resolve("external.go"), "package external\n")
            setField("initializeFuture", initialization)
            setField("serverCapabilities", ServerCapabilities())
            bindContext(initialization)
        }

        fun registration(id: String, external: Boolean = false): RegistrationParams {
            val method = if (external) "workspace/didChangeWatchedFiles" else "workspace/executeCommand"
            val options = if (external) DidChangeWatchedFilesRegistrationOptions(listOf(FileSystemWatcher().apply {
                setGlobPattern(root.toString().replace('\\', '/') + "/**/*.go"); kind = WatchKind.Change
            })) else ExecuteCommandOptions(listOf("owned.$id"))
            return RegistrationParams(listOf(Registration(id, method, JSONUtils.getLsp4jGson().toJsonTree(options))))
        }

        fun unregistration(id: String) = UnregistrationParams(listOf(Unregistration(id,
            if (id in setOf("cancelled", "failed", "stopped", "disposed")) "workspace/didChangeWatchedFiles" else "workspace/executeCommand")))

        fun replaceClient() {
            client = GoLanguageClient(project).also { it.setServerWrapper(wrapper); clients += it }
            setField("serverCapabilities", ServerCapabilities())
        }

        fun bindContext(ready: UncancellableFuture = UncancellableFuture()): UncancellableFuture {
            val type = LanguageServerWrapper::class.java.declaredClasses.single { it.simpleName == "InitializingContext" }
            val context = type.declaredConstructors.single().apply { isAccessible = true }.newInstance(wrapper)
            type.getDeclaredField("capabilityRegistrationReady").apply { isAccessible = true }.set(context, ready)
            type.getDeclaredField("languageClient").apply { isAccessible = true }.set(context, client)
            setField("currentInitializingContext", context)
            return ready
        }

        fun setField(name: String, value: Any) {
            LanguageServerWrapper::class.java.getDeclaredField(name).apply { isAccessible = true }.set(wrapper, value)
        }

        @Suppress("UNCHECKED_CAST")
        fun handlers() = synchronized(wrapper) {
            LanguageServerWrapper::class.java.getDeclaredField("dynamicRegistrations").apply { isAccessible = true }.get(wrapper) as Map<String, Runnable>
        }

        @Suppress("UNCHECKED_CAST")
        fun registerAsync(params: RegistrationParams) = LanguageServerWrapper::class.java
            .getMethod("registerCapabilityAsync", RegistrationParams::class.java).invoke(wrapper, params) as CompletableFuture<Void>

        fun assertNoExternalRoots() {
            val owner = GoLanguageClient::class.java.getDeclaredField("externalFiles").apply { isAccessible = true }.get(client)
            val roots = owner.javaClass.getDeclaredField("roots").apply { isAccessible = true }.get(owner) as Map<*, *>
            assertTrue("Unregistration/failed startup must leave no independently owned root", roots.isEmpty())
        }

        override fun close() {
            clients.forEach { it.dispose() }; wrapper.dispose(); Disposer.dispose(lifetime)
            root.toFile().deleteRecursively()
        }
    }

    private class Wrapper(project: Project, definition: LanguageServerDefinition) : LanguageServerWrapper(project, definition) {
        private val features = LSPClientFeatures().also { it.setServerWrapper(this) }
        private val closed = AtomicBoolean()
        override fun getClientFeatures() = features
        override fun dispose() {
            if (closed.compareAndSet(false, true)) { super.dispose(); features.dispose() }
        }
    }

    private class UncancellableFuture : CompletableFuture<Void>() {
        override fun cancel(mayInterruptIfRunning: Boolean) = false
    }

    private fun pending(future: CompletableFuture<Void>) {
        assertTrue("An operation behind held initialization must not ACK early",
            runCatching { future.get(300, TimeUnit.MILLISECONDS) }.exceptionOrNull() is TimeoutException)
    }
    private fun rejected(future: CompletableFuture<Void>) {
        val failure = runCatching { future.await() }.exceptionOrNull()
        assertNotNull("Stopped/failed initialization must complete the request exceptionally", failure)
        assertFalse("A timeout is a hung request, not a completed rejection", failure is TimeoutException)
    }
    private fun CompletableFuture<Void>.await() { get(10, TimeUnit.SECONDS) }
}
