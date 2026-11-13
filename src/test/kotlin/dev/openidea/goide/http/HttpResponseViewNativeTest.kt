package dev.openidea.goide.http

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.LightPlatformTestCase
import com.intellij.testFramework.PlatformTestUtil
import java.util.concurrent.atomic.AtomicReference

class HttpResponseViewNativeTest : LightPlatformTestCase() {
    fun testCrlfResponseUsesValidEditorSeparatorsWithoutChangingPreparedOrWireText() = edt {
        val parent = Disposer.newDisposable("HTTP CRLF response native test")
        Disposer.register(testRootDisposable, parent)
        val original = "first\r\n你好😀\r\nlast\r"
        val response = HttpRequestResponse(200, mapOf("Content-Type" to listOf("text/plain")), original.toByteArray(), 0)
        val presentation = HttpResponsePresentation.prepare(response)
        val before = EditorFactory.getInstance().allEditors.toSet()
        try {
            HttpResponseView(project, parent).show(presentation)
            val owned = EditorFactory.getInstance().allEditors.filter { it !in before }
            assertEquals(2, owned.size)
            assertTrue(owned.any { it.document.text == "first\n你好😀\nlast\n" })
            assertEquals(original, presentation.bodyText)
            assertEquals(original, String(response.body, Charsets.UTF_8))
        } finally { Disposer.dispose(parent) }
        assertEquals(before, EditorFactory.getInstance().allEditors.toSet())
    }

    fun testNativeViewersHighlightAndReleaseEditorsOnSwitchAndParentDisposal() = edt {
        val factory = EditorFactory.getInstance()
        val before = factory.allEditors.toSet()
        val parent = Disposer.newDisposable("HTTP response view native test")
        Disposer.register(testRootDisposable, parent)
        try {
            val view = HttpResponseView(project, parent)
            view.show(HttpResponsePresentation.prepare(HttpRequestResponse(200,
                mapOf("Content-Type" to listOf("application/json")), "{\"value\":\"你好😀\"}".toByteArray(), 1)))
            val first = factory.allEditors.filter { it !in before }
            assertEquals(2, first.size)
            assertTrue(first.all { it.isViewer && it.project == project })
            val body = first.single { it.document.text.startsWith("{") } as EditorEx
            assertTrue(body.document.text.contains("你好😀"))
            val iterator = body.highlighter.createIterator(0)
            var tokens = 0
            while (!iterator.atEnd()) { tokens++; iterator.advance() }
            assertTrue("JSON must use the SDK syntax highlighter, not a plain text component", tokens > 3)
            view.showMessage("Sending the next request…")
            assertTrue(first.all { it.isDisposed })
            assertEquals(before, factory.allEditors.toSet())
            view.show(HttpResponsePresentation.prepare(HttpRequestResponse(200,
                mapOf("Content-Type" to listOf("text/html")), "<script>literal only</script>".toByteArray(), 1)))
            val second = factory.allEditors.filter { it !in before }
            assertEquals(2, second.size)
            assertTrue(second.all { it.isViewer })
            assertTrue(second.any { it.document.text == "<script>literal only</script>" })
            Disposer.dispose(parent)
            assertTrue(second.all { it.isDisposed })
            view.showMessage("late delivery")
            view.show(HttpResponsePresentation.prepare(HttpRequestResponse(200, emptyMap(), byteArrayOf(), 1)))
            assertEquals(before, factory.allEditors.toSet())
        } finally { Disposer.dispose(parent) }
    }

    fun testBackgroundParentDisposalReleasesEditorsOnTheEventThreadWithoutBlockingIt() = edt {
        val factory = EditorFactory.getInstance()
        val before = factory.allEditors.toSet()
        val parent = Disposer.newDisposable("HTTP response view background disposal")
        Disposer.register(testRootDisposable, parent)
        try {
            val view = HttpResponseView(project, parent)
            view.show(HttpResponsePresentation.prepare(HttpRequestResponse(200, emptyMap(), "hello".toByteArray(), 1)))
            val owned = factory.allEditors.filter { it !in before }
            assertEquals(2, owned.size)
            val error = AtomicReference<Throwable?>()
            val closer = Thread {
                try { Disposer.dispose(parent) } catch (failure: Throwable) { error.set(failure) }
            }
            closer.start(); closer.join(2_000)
            assertFalse("Background disposal must not wait for the blocked EDT", closer.isAlive)
            assertNull(error.get())
            PlatformTestUtil.waitWithEventsDispatching("Owned response editors must be released", { owned.all { it.isDisposed } }, 3)
            assertEquals(before, factory.allEditors.toSet())
        } finally { Disposer.dispose(parent) }
    }

    private fun edt(action: () -> Unit) {
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) action() else application.invokeAndWait { action() }
    }
}
