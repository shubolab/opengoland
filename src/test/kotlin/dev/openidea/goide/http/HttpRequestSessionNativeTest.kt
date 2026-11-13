package dev.openidea.goide.http

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.ThreadingSupport
import com.intellij.openapi.application.ex.ApplicationEx
import com.intellij.openapi.actionSystem.CustomizedDataContext
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.LightPlatformTestCase
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.ui.components.JBList
import dev.openidea.goide.lang.HttpRequestFileType
import java.awt.Component
import java.awt.Container
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertFailsWith

class HttpRequestSessionNativeTest : LightPlatformTestCase() {
    private fun session(text: String, cache: HttpResponseCache, source: VirtualFile? = null): HttpRequestSession {
        val plan = HttpRequestDocument.plan(text)
        return HttpRequestSession(project, text, plan, plan.entries, source, "Native HTTP batch", cache, {}, {}, {}, {}).also {
            Disposer.register(testRootDisposable, it)
        }
    }

    fun testRealBatchKeepsNamedResultsAndHttpErrorsDistinctFromTransportFailures() = LocalHttpFixture().use { fixture -> edt {
        val rawJson = "{\"message\":\"你好😀\"}".toByteArray()
        fixture.route("/json") { exchange ->
            exchange.responseHeaders.add("Content-Type", "application/json; charset=UTF-8")
            exchange.sendResponseHeaders(200, rawJson.size.toLong()); exchange.responseBody.write(rawJson)
        }
        fixture.route("/missing") { exchange ->
            val bytes = "not found".toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/plain")
            exchange.sendResponseHeaders(404, bytes.size.toLong()); exchange.responseBody.write(bytes)
        }
        val text = "### first\n# @name hello\nGET ${fixture.base}/json\n### malformed\nGET file:///unsupported\n### missing\nGET ${fixture.base}/missing"
        val file = LightVirtualFile("requests.http", HttpRequestFileType.INSTANCE, text)
        val cache = HttpResponseCache()
        val session = session(text, cache, file)
        try {
            session.handler.startNotify()
            PlatformTestUtil.waitWithEventsDispatching("Native HTTP batch must finish", { session.isFinished }, 10)
            assertEquals(1, session.handler.exitCode)
            assertEquals(listOf("hello", "malformed", "missing"), session.items.map { it.entry.displayName })
            assertEquals(listOf(HttpRequestPhase.COMPLETED, HttpRequestPhase.FAILED, HttpRequestPhase.COMPLETED), session.items.map { it.status.phase })
            assertEquals(200, session.items[0].status.httpStatus)
            assertEquals(404, session.items[2].status.httpStatus)
            assertTrue(session.items[1].status.message!!.contains("absolute http:// or https://"))
            assertTrue(rawJson.contentEquals(session.cachedResponse(session.items[0].entry)!!.body))
            val root = HttpServicesContributor()
            assertSame(session.descriptor, root.getServiceDescriptor(project, session))
            assertEquals(session.items, session.getServices(project))
            val descriptor = session.getServiceDescriptor(project, session.items[2])
            assertTrue(descriptor.navigatable!!.canNavigateToSource())
            assertNotNull(descriptor.contentComponent)
            assertTrue(descriptor.toolbarActions!!.getChildren(null).any { it.templatePresentation.text == "Stop All" })
        } finally { Disposer.dispose(session) }
        assertEquals(0L, cache.retainedBytes)
    } }

    fun testEvictionReleasesCreatedEditorButKeepsTheHttpStatus() = LocalHttpFixture().use { fixture -> edt {
        val secondEntered = CountDownLatch(1)
        val releaseSecond = CountDownLatch(1)
        val body = "x".repeat(1000).toByteArray()
        fixture.route("/first") { exchange ->
            exchange.responseHeaders.add("Content-Type", "text/plain")
            exchange.sendResponseHeaders(200, body.size.toLong()); exchange.responseBody.write(body)
        }
        fixture.route("/second") { exchange ->
            secondEntered.countDown(); releaseSecond.await()
            exchange.responseHeaders.add("Content-Type", "text/plain")
            exchange.sendResponseHeaders(201, body.size.toLong()); exchange.responseBody.write(body)
        }
        val cache = HttpResponseCache(5000)
        val session = session("GET ${fixture.base}/first\n### second\nGET ${fixture.base}/second", cache)
        val factory = EditorFactory.getInstance()
        val before = factory.allEditors.toSet()
        try {
            session.handler.startNotify()
            PlatformTestUtil.waitWithEventsDispatching("Second request must be pending", { secondEntered.count == 0L }, 10)
            val first = session.items.first()
            assertNotNull(session.cachedResponse(first.entry))
            assertNotNull(session.getServiceDescriptor(project, first).contentComponent)
            val editors = factory.allEditors.filter { it !in before }
            assertEquals(2, editors.size)
            releaseSecond.countDown()
            PlatformTestUtil.waitWithEventsDispatching("Second response must finish", { session.isFinished }, 10)
            session.refreshViews()
            assertNull(session.cachedResponse(first.entry))
            assertNotNull(session.cachedResponse(session.items.last().entry))
            assertEquals(HttpRequestPhase.COMPLETED, first.status.phase)
            assertEquals(200, first.status.httpStatus)
            assertEquals(1000, first.status.wireBytes)
            assertTrue(editors.all { it.isDisposed })
            assertEquals(before, factory.allEditors.toSet())
            assertTrue(cache.retainedBytes <= cache.maxBytes)
        } finally { releaseSecond.countDown(); Disposer.dispose(session) }
    } }

    fun testDisposeBeforeStartSendsNoRequestAndRejectsLateUiWork() = LocalHttpFixture().use { fixture -> edt {
        val received = AtomicInteger()
        fixture.route("/never") { exchange -> received.incrementAndGet(); exchange.sendResponseHeaders(200, -1) }
        val cache = HttpResponseCache()
        val session = session("GET ${fixture.base}/never", cache)
        val before = EditorFactory.getInstance().allEditors.toSet()
        Disposer.dispose(session)
        PlatformTestUtil.waitWithEventsDispatching("Disposed session must drain its native handler", { session.handler.isProcessTerminated }, 3)
        assertEquals(130, session.handler.exitCode)
        assertEquals(0, received.get())
        assertTrue(session.isDisposed)
        assertNull(session.descriptor.contentComponent)
        session.refreshViews()
        assertEquals(before, EditorFactory.getInstance().allEditors.toSet())
        assertEquals(0L, cache.retainedBytes)
    } }

    fun testSingleRequestDoesNotKeepHiddenEditorCopies() = LocalHttpFixture().use { fixture -> edt {
        fixture.route("/text") { exchange ->
            val bytes = "response".toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/plain")
            exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.write(bytes)
        }
        val cache = HttpResponseCache()
        val session = session("GET ${fixture.base}/text", cache)
        val factory = EditorFactory.getInstance()
        val before = factory.allEditors.toSet()
        try {
            session.handler.startNotify()
            PlatformTestUtil.waitWithEventsDispatching("Single response must finish", { session.isFinished }, 10)
            assertTrue(session.getServices(project).isEmpty())
            assertNotNull(session.descriptor.contentComponent)
            val owned = factory.allEditors.filter { it !in before }
            assertEquals(2, owned.size)
            session.descriptor.onNodeUnselected()
            assertTrue(owned.all { it.isDisposed })
            assertEquals(before, factory.allEditors.toSet())
            assertNotNull(session.descriptor.contentComponent)
            assertEquals(2, factory.allEditors.count { it !in before })
        } finally { Disposer.dispose(session) }
        assertEquals(before, factory.allEditors.toSet())
    } }

    fun testSwitchingTheRealBatchListReleasesThePreviousResponseEditors() = LocalHttpFixture().use { fixture -> edt {
        fixture.route("/text") { exchange ->
            val bytes = "response-你好😀".toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/plain; charset=UTF-8")
            exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.write(bytes)
        }
        val text = "GET ${fixture.base}/text\n### second\nGET ${fixture.base}/text\n### third\nGET ${fixture.base}/text"
        val session = session(text, HttpResponseCache())
        val factory = EditorFactory.getInstance()
        val before = factory.allEditors.toSet()
        try {
            session.handler.startNotify()
            PlatformTestUtil.waitWithEventsDispatching("Batch responses must finish", { session.isFinished }, 10)
            val component = session.descriptor.contentComponent!!
            val list = descendants(component).filterIsInstance<JBList<*>>().single { it.model.size == 3 }
            for (index in 1..2) {
                val previous = factory.allEditors.filter { it !in before }
                assertEquals(2, previous.size)
                list.selectedIndex = index
                assertTrue(previous.all { it.isDisposed })
                assertEquals(2, factory.allEditors.count { it !in before })
            }
            session.descriptor.onNodeUnselected()
            assertEquals(before, factory.allEditors.toSet())
        } finally { Disposer.dispose(session) }
    } }

    fun testLongExpandedUriIsOnlyTruncatedInTheRetainedUiPreview() = LocalHttpFixture().use { fixture -> edt {
        val queryLength = AtomicInteger()
        fixture.route("/long") { exchange ->
            queryLength.set(exchange.requestURI.rawQuery.length)
            exchange.sendResponseHeaders(200, -1)
        }
        val text = "GET ${fixture.base}/long?x=${"a".repeat(2000)}"
        val session = session(text, HttpResponseCache())
        try {
            session.handler.startNotify()
            PlatformTestUtil.waitWithEventsDispatching("Long URI request must finish", { session.isFinished }, 10)
            val preview = session.items.single().status.uri!!
            assertTrue(preview.length <= 512)
            assertTrue(preview.endsWith("…"))
            assertEquals(2002, queryLength.get())
            assertEquals(text, session.snapshotText)
            assertEquals(200, session.items.single().status.httpStatus)
        } finally { Disposer.dispose(session) }
    } }

    fun testServicesSnapshotsDoNotTakeLocksAndReuseEntryBoundToolbarActions() = edt {
        navigationFixture { session, file ->
            val application = ApplicationManager.getApplication() as ApplicationEx
            val descriptors = listOf(session.descriptor) + session.items.map { session.getServiceDescriptor(project, it) }
            val groups = descriptors.map { it.toolbarActions!! }
            val children = groups.map { it.getChildren(null) }
            assertNotSame(groups[0], groups[1])
            assertNotSame(groups[1], groups[2])
            val failure = AtomicReference<Throwable?>()
            val finished = CountDownLatch(1)
            val worker = Thread {
                try {
                    assertFalse(application.isReadAccessAllowed)
                    application.prohibitTakingLocksInsideAndRun({
                        // Positive controls use the SDK guard and the exact eager
                        // constructor that failed during native Services snapshots.
                        assertSdkReadLockProhibition()
                        assertFailsWith<ThreadingSupport.LockAccessDisallowed> {
                            OpenFileDescriptor(project, file, session.items[1].entry.line - 1, 0)
                        }
                        repeat(25) {
                            descriptors.forEachIndexed { index, descriptor ->
                                val navigation = descriptor.navigatable!!
                                assertTrue(navigation.canNavigate())
                                assertTrue(navigation.canNavigateToSource())
                                assertSame(groups[index], descriptor.toolbarActions)
                                val current = descriptor.toolbarActions!!.getChildren(null)
                                assertEquals(children[index].size, current.size)
                                current.indices.forEach { assertSame(children[index][it], current[it]) }
                                val context = CustomizedDataContext.withSnapshot(SimpleDataContext.getProjectContext(project)) { sink ->
                                    (descriptor as UiDataProvider).uiDataSnapshot(sink)
                                }
                                assertSame(session, HttpServicesDataKeys.SESSION.getData(context))
                            }
                            // State updates produce fresh descriptors. They must
                            // still point to the same action instances for this item.
                            session.items.forEachIndexed { index, item ->
                                assertSame(groups[index + 1], session.getServiceDescriptor(project, item).toolbarActions)
                            }
                        }
                    }, "HTTP Services snapshot regression: no read/write locks are permitted")
                } catch (problem: Throwable) { failure.set(problem) } finally { finished.countDown() }
            }.apply { isDaemon = true }
            worker.start()
            PlatformTestUtil.waitWithEventsDispatching("Lock-free Services snapshots must finish", { finished.count == 0L }, 5)
            failure.get()?.let { throw it }
        }
    }

    fun testDeferredSourceNavigationStillOpensTheOriginalRequestLine() = edt {
        navigationFixture { session, file ->
            val target = session.items[1]
            val manager = FileEditorManager.getInstance(project)
            try {
                session.getServiceDescriptor(project, target).navigatable!!.navigate(true)
                val editor = manager.selectedTextEditor
                assertNotNull("Explicit source navigation must open a real editor", editor)
                assertSame(file, FileDocumentManager.getInstance().getFile(editor!!.document))
                assertEquals(target.entry.line - 1, editor.caretModel.logicalPosition.line)
            } finally { manager.closeFile(file) }
        }
    }

    private fun navigationFixture(action: (HttpRequestSession, VirtualFile) -> Unit) {
        val root = Files.createTempDirectory("open-goland-http-navigation-").toRealPath()
        val access = Disposer.newDisposable("HTTP navigation fixture access")
        VfsRootAccess.allowRootAccess(access, root.toString())
        var session: HttpRequestSession? = null
        try {
            val text = "# @name first\nGET http://localhost/first\n### second\n# @name second\nGET http://localhost/second"
            val path = root.resolve("requests.http")
            Files.writeString(path, text)
            val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!
            session = session(text, HttpResponseCache(), file)
            action(session, file)
        } finally {
            session?.let {
                Disposer.dispose(it)
                PlatformTestUtil.waitWithEventsDispatching("Navigation fixture handler must drain", { it.handler.isProcessTerminated }, 3)
            }
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
            Disposer.dispose(access)
        }
    }

    /** Keep the captureless Runnable's generated name outside JUnit 3's test* discovery prefix. */
    private fun assertSdkReadLockProhibition() {
        assertFailsWith<ThreadingSupport.LockAccessDisallowed> { ReadAction.run<RuntimeException> {} }
    }

    private fun descendants(component: Component): List<Component> = buildList {
        add(component)
        if (component is Container) component.components.forEach { addAll(descendants(it)) }
    }

    private fun edt(action: () -> Unit) {
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) action() else application.invokeAndWait { action() }
    }
}
