package dev.openidea.goide.http

import com.intellij.execution.lineMarker.RunLineMarkerProvider
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiFileFactory
import com.intellij.testFramework.LightPlatformTestCase
import com.intellij.testFramework.PlatformTestUtil
import dev.openidea.goide.lang.HttpRequestFileType
import java.util.concurrent.CountDownLatch

class HttpRequestRunNativeTest : LightPlatformTestCase() {
    fun testNativeProviderExposesRequestGutterAndSkipsMethodInBody() {
        val source = "POST http://localhost\n\nGET is plain body text\n### next\nGET http://localhost/next"
        val file = PsiFileFactory.getInstance(project).createFileFromText("native.http", HttpRequestFileType.INSTANCE, source)
        val provider = RunLineMarkerProvider()
        assertNotNull(provider.getLineMarkerInfo(file.findElementAt(0)!!))
        assertNull(provider.getLineMarkerInfo(file.findElementAt(source.indexOf("GET"))!!))
        val next = provider.getLineMarkerInfo(file.findElementAt(source.lastIndexOf("GET"))!!)
        assertNotNull(next)
        assertEquals(1, next!!.createGutterRenderer()!!.popupMenuActions!!.getChildren(null).size)
    }

    fun testRealHandlerUsesUnsavedSnapshotAndPrintsResponse() = LocalHttpFixture().use { fixture ->
        fixture.route("/native") { exchange ->
            val bytes = "native-response-你好😀".toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "text/plain; charset=UTF-8")
            exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.write(bytes)
        }
        val output = StringBuffer()
        val handler = HttpRequestProcessHandler("GET ${fixture.base}/native", 0) {}
        handler.addProcessListener(object : ProcessAdapter() {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) { output.append(event.text) }
        })
        handler.startNotify()
        PlatformTestUtil.waitWithEventsDispatching("HTTP response must terminate its native process handler", { handler.isProcessTerminated }, 10)
        assertEquals(0, handler.exitCode)
        assertTrue(output.toString().contains("native-response-你好😀"))
        assertTrue(output.toString().contains("HTTP 200"))
    }

    fun testNativeStopTerminatesOwnedRequestWithoutWaitingForServer() = LocalHttpFixture().use { fixture ->
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        fixture.route("/wait") { exchange -> entered.countDown(); release.await(); exchange.sendResponseHeaders(200, -1) }
        val handler = HttpRequestProcessHandler("GET ${fixture.base}/wait", 0) {}
        try {
            handler.startNotify()
            PlatformTestUtil.waitWithEventsDispatching("Server must receive the request", { entered.count == 0L }, 10)
            handler.destroyProcess()
            PlatformTestUtil.waitWithEventsDispatching("Native Stop must finish promptly", { handler.isProcessTerminated }, 3)
            assertEquals(130, handler.exitCode)
        } finally { release.countDown(); handler.destroyProcess() }
    }
}
