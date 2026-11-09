package dev.openidea.goide.sdk

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.LightPlatformTestCase
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.UIUtil
import dev.openidea.goide.project.GoModuleSdk
import dev.openidea.goide.project.GoSdkProbe
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPOutputStream
import javax.swing.JButton
import javax.swing.JTextArea
import javax.swing.SwingUtilities

class GoSdkDownloadDialogTest : LightPlatformTestCase() {
    override fun runInDispatchThread() = false
    private val host = GoSdkHost("darwin", "arm64")

    fun testNativeDialogLoadsInBackgroundAndDeliversOnlyTheVerifiedCompletedInstallOnEdt() = fixture { root ->
        val installed = AtomicReference<GoModuleSdk?>()
        val edtCallback = AtomicBoolean()
        val catalogueOnEdt = AtomicBoolean(true)
        val probeCount = AtomicInteger()
        val bytes = archiveBytes()
        val downloads = GoSdkDownloads(object : GoSdkDownloadTransport {
            override fun open(uri: URI, cancellation: GoSdkDownloadCancellation): InputStream {
                catalogueOnEdt.set(SwingUtilities.isEventDispatchThread())
                return ByteArrayInputStream(if (uri == GoSdkDownloads.CATALOGUE) catalogue(bytes) else bytes)
            }
        }, { executable, cancellation ->
            assertFalse(SwingUtilities.isEventDispatchThread()); cancellation.check(); probeCount.incrementAndGet()
            GoModuleSdk(executable.toRealPath(), executable.parent.parent.toRealPath(), "go1.27.3")
        }, host = host)
        val parent = Disposer.newDisposable()
        val dialog = onEdt { GoSdkDownloadDialog(project, parent, { sdk -> edtCallback.set(SwingUtilities.isEventDispatchThread()); installed.set(sdk) }, downloads, host) }
        try {
            await { onEdt { dialog.isOKActionEnabled } }
            assertFalse(catalogueOnEdt.get())
            assertEquals(1, onEdt { field<ComboBox<*>>(dialog, "versions").itemCount })
            onEdt {
                field<JBTextField>(dialog, "location").text = root.resolve("SDK 中文😀").toString()
                dialog.performOKAction()
                assertFalse(dialog.isOKActionEnabled)
            }
            await { installed.get() != null }
            assertTrue(edtCallback.get()); assertEquals(2, probeCount.get())
            assertTrue(Files.isRegularFile(installed.get()!!.executable))
            assertEquals(root.resolve("SDK 中文😀").toRealPath(), installed.get()!!.root)
        } finally { onEdt { if (!dialog.isDisposed) dialog.close(1); Disposer.dispose(parent) } }
    }

    fun testCatalogueFailureRemainsVisibleAndReloadRetriesWithoutClosingTheDialog() {
        val calls = AtomicInteger()
        val bytes = archiveBytes()
        val downloads = GoSdkDownloads(object : GoSdkDownloadTransport {
            override fun open(uri: URI, cancellation: GoSdkDownloadCancellation): InputStream {
                if (calls.incrementAndGet() == 1) throw IOException("Offline catalogue fixture")
                return ByteArrayInputStream(catalogue(bytes))
            }
        }, host = host)
        val parent = Disposer.newDisposable()
        val callbacks = AtomicInteger()
        val dialog = onEdt { GoSdkDownloadDialog(project, parent, { callbacks.incrementAndGet() }, downloads, host) }
        try {
            await { onEdt { field<JTextArea>(dialog, "status").text.contains("Offline catalogue fixture") } }
            onEdt {
                assertFalse(dialog.isDisposed); assertFalse(dialog.isOKActionEnabled)
                field<JButton>(dialog, "reload").doClick()
            }
            await { onEdt { dialog.isOKActionEnabled } }
            assertEquals(2, calls.get()); assertEquals(0, callbacks.get())
        } finally { onEdt { dialog.close(1); Disposer.dispose(parent) } }
    }

    fun testParentDisposalCancelsOwnedCatalogueAndLateReplyCannotRegisterOrReviveDialog() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val callbacks = AtomicInteger()
        val cancellation = AtomicReference<GoSdkDownloadCancellation>()
        val bytes = catalogue(archiveBytes())
        val downloads = GoSdkDownloads(object : GoSdkDownloadTransport {
            override fun open(uri: URI, token: GoSdkDownloadCancellation): InputStream {
                cancellation.set(token)
                return object : ByteArrayInputStream(bytes) {
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); return super.read(buffer, offset, length)
                    }
                    override fun close() { closed.countDown(); release.countDown(); super.close() }
                }
            }
        }, host = host)
        val parent = Disposer.newDisposable()
        val dialog = onEdt { GoSdkDownloadDialog(project, parent, { callbacks.incrementAndGet() }, downloads, host) }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            onEdt { Disposer.dispose(parent) }
            assertTrue(cancellation.get().isCancelled())
            assertTrue(closed.await(5, TimeUnit.SECONDS))
            await { onEdt { dialog.isDisposed } }
            onEdt { UIUtil.dispatchAllInvocationEvents() }
            assertEquals(0, callbacks.get())
        } finally { release.countDown(); onEdt { if (!dialog.isDisposed) dialog.close(1); if (!Disposer.isDisposed(parent)) Disposer.dispose(parent) } }
    }

    fun testAlreadyDisposedParentNeverStartsNetworkOrLeavesTheDialogAlive() {
        val calls = AtomicInteger()
        val downloads = GoSdkDownloads(object : GoSdkDownloadTransport {
            override fun open(uri: URI, cancellation: GoSdkDownloadCancellation): InputStream { calls.incrementAndGet(); error("Disposed owner started a download") }
        }, host = host)
        val parent = Disposer.newDisposable()
        onEdt { Disposer.dispose(parent) }
        val dialog = onEdt { GoSdkDownloadDialog(project, parent, { error("Disposed owner received an SDK") }, downloads, host) }
        await { onEdt { dialog.isDisposed } }
        assertEquals(0, calls.get())
    }

    fun testChecksumFailureKeepsNativeDialogOpenAndNeverInvokesInstalledCallback() = fixture { root ->
        val bytes = archiveBytes()
        val callbacks = AtomicInteger()
        val downloads = GoSdkDownloads(object : GoSdkDownloadTransport {
            override fun open(uri: URI, cancellation: GoSdkDownloadCancellation): InputStream = ByteArrayInputStream(
                if (uri == GoSdkDownloads.CATALOGUE) catalogue(bytes, "0".repeat(64)) else bytes)
        }, { _, _ -> error("Checksum failure must not reach SDK probing") }, host = host)
        val parent = Disposer.newDisposable()
        val dialog = onEdt { GoSdkDownloadDialog(project, parent, { callbacks.incrementAndGet() }, downloads, host) }
        try {
            await { onEdt { dialog.isOKActionEnabled } }
            onEdt {
                field<JBTextField>(dialog, "location").text = root.resolve("bad SDK").toString()
                dialog.performOKAction()
            }
            await { onEdt { field<JTextArea>(dialog, "status").text.contains("checksum verification failed") } }
            onEdt { assertFalse(dialog.isDisposed); assertTrue(dialog.isOKActionEnabled) }
            assertEquals(0, callbacks.get()); assertFalse(Files.exists(root.resolve("bad SDK")))
        } finally { onEdt { dialog.close(1); Disposer.dispose(parent) } }
    }

    private fun archiveBytes(): ByteArray = ByteArrayOutputStream().also { output ->
        TarArchiveOutputStream(GZIPOutputStream(output)).use { tar ->
            for ((name, contents) in listOf("go/bin/go" to "binary", "go/src/runtime/runtime.go" to "runtime")) {
                val data = contents.toByteArray()
                tar.putArchiveEntry(TarArchiveEntry(name).apply { size = data.size.toLong(); mode = 493 })
                tar.write(data); tar.closeArchiveEntry()
            }
        }
    }.toByteArray()
    private fun catalogue(bytes: ByteArray, hash: String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }) =
        """[{"version":"go1.27.3","stable":true,"files":[{"version":"go1.27.3","kind":"archive","os":"darwin","arch":"arm64","filename":"go1.27.3.darwin-arm64.tar.gz","size":${bytes.size},"sha256":"$hash"}]}]""".toByteArray()
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition()) { if (System.nanoTime() > deadline) fail("SDK dialog did not finish the expected background operation"); Thread.sleep(20) }
    }
    private fun <T> onEdt(action: () -> T): T {
        val result = AtomicReference<T>()
        ApplicationManager.getApplication().invokeAndWait { result.set(action()) }
        return result.get()
    }
    private inline fun <reified T> field(dialog: GoSdkDownloadDialog, name: String): T =
        GoSdkDownloadDialog::class.java.getDeclaredField(name).apply { isAccessible = true }.get(dialog) as T
    private fun fixture(test: (Path) -> Unit) {
        val root = Files.createTempDirectory("open-goland-sdk-dialog-")
        try { test(root.toRealPath()) } finally { GoSdkProbe.deleteOwnedTree(root) }
    }
}
