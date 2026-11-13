package dev.openidea.goide.http

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.CustomizedDataContext
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.LightPlatformTestCase
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.ui.components.JBTabbedPane
import java.lang.reflect.Modifier
import javax.swing.JComponent

class HttpServicesToolbarNativeTest : LightPlatformTestCase() {
    fun testCompletedSingleResponseKeepsTheRealServicesToolbarAndTypedActionContext() = LocalHttpFixture().use { fixture -> edt {
        val bytes = "{\"message\":\"toolbar-你好😀\"}".toByteArray(Charsets.UTF_8)
        fixture.route("/toolbar") { exchange ->
            exchange.responseHeaders.add("Content-Type", "application/json; charset=UTF-8")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.write(bytes)
        }
        val text = "# @name toolbarResponse\nGET ${fixture.base}/toolbar"
        val plan = HttpRequestDocument.plan(text)
        val cache = HttpResponseCache()
        val session = HttpRequestSession(project, text, plan, plan.entries, null, "Native toolbar", cache, {}, {}, {}, {})
        Disposer.register(testRootDisposable, session)
        val factory = EditorFactory.getInstance()
        val before = factory.allEditors.toSet()
        try {
            session.handler.startNotify()
            PlatformTestUtil.waitWithEventsDispatching("The real HTTP response must finish before testing its Services content", {
                session.isFinished && session.handler.isProcessTerminated
            }, 10)
            assertEquals(0, session.handler.exitCode)
            assertEquals(HttpRequestPhase.COMPLETED, session.items.single().status.phase)
            assertTrue(session.getServices(project).isEmpty())

            // Control: the pinned platform deliberately suppresses its toolbar for tab panes.
            assertFalse(nativeToolbarRequired(JBTabbedPane()))
            val descriptor = session.descriptor
            assertTrue(descriptor is UiDataProvider)
            val provider = descriptor as UiDataProvider
            val registeredSave = ActionManager.getInstance().getAction("OpenGoland.Http.SaveResponse")
            assertNotNull(registeredSave)
            repeat(2) {
                val component = descriptor.contentComponent!!
                val owned = factory.allEditors.filter { it !in before }
                assertEquals(2, owned.size)
                assertTrue(owned.any { editor -> editor.document.text.contains("toolbar-你好😀") })
                assertTrue("A completed response, including one reopened after deselection, must retain the native Services toolbar",
                    nativeToolbarRequired(component))

                val actions = descriptor.toolbarActions!!.getChildren(null)
                assertTrue(actions.any { action -> action.templatePresentation.text == "Run Again" })
                assertTrue(actions.any { action -> action.templatePresentation.text == "Stop All" })
                assertSame(registeredSave, actions.single { action -> action is SaveHttpResponseAction })
                val context = CustomizedDataContext.withSnapshot(SimpleDataContext.getProjectContext(project)) { sink ->
                    provider.uiDataSnapshot(sink)
                }
                assertSame(session, HttpServicesDataKeys.SESSION.getData(context))
                assertSame(session.items.single(), HttpServicesDataKeys.REQUEST.getData(context))

                descriptor.onNodeUnselected()
                assertTrue(owned.all { editor -> editor.isDisposed })
                assertEquals(before, factory.allEditors.toSet())
            }
        } finally { Disposer.dispose(session) }
        assertEquals(before, factory.allEditors.toSet())
        assertEquals(0L, cache.retainedBytes)
    } }

    /** The implementation class stays confined to this pinned-SDK regression test. */
    private fun nativeToolbarRequired(component: JComponent): Boolean {
        val owner = Class.forName("com.intellij.platform.execution.serviceView.ServiceViewActionProvider", false, javaClass.classLoader)
        val method = owner.getDeclaredMethod("isActionToolBarRequired", JComponent::class.java)
        assertTrue(Modifier.isStatic(method.modifiers))
        assertEquals(java.lang.Boolean.TYPE, method.returnType)
        assertTrue(method.trySetAccessible())
        return method.invoke(null, component) as Boolean
    }

    private fun edt(action: () -> Unit) {
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) action() else application.invokeAndWait { action() }
    }
}
