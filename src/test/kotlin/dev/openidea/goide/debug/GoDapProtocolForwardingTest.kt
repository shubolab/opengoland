package dev.openidea.goide.debug

import org.eclipse.lsp4j.debug.Capabilities
import org.eclipse.lsp4j.debug.DisconnectArguments
import org.eclipse.lsp4j.debug.InitializeRequestArguments
import org.eclipse.lsp4j.debug.ThreadsResponse
import org.eclipse.lsp4j.debug.services.IDebugProtocolServer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GoDapProtocolForwardingTest {
    @Test fun javaDefaultRpcMethodsReachTheActualEndpoint() {
        val calls = CopyOnWriteArrayList<String>()
        val disconnectEntered = CountDownLatch(1)
        val disconnectAck = CompletableFuture<Void>()
        val actual = object : IDebugProtocolServer {
            override fun initialize(args: InitializeRequestArguments): CompletableFuture<Capabilities> {
                calls += "initialize:${args.adapterID}"
                return CompletableFuture.completedFuture(Capabilities().apply { supportsConfigurationDoneRequest = true })
            }
            override fun attach(args: MutableMap<String, Any>): CompletableFuture<Void> {
                calls += "attach:${args["processId"]}"
                return CompletableFuture.completedFuture(null)
            }
            override fun threads(): CompletableFuture<ThreadsResponse> {
                calls += "threads"
                return CompletableFuture.completedFuture(ThreadsResponse().apply { threads = emptyArray() })
            }
            override fun disconnect(args: DisconnectArguments): CompletableFuture<Void> {
                assertFalse(args.terminateDebuggee)
                calls += "disconnect"
                disconnectEntered.countDown()
                return disconnectAck
            }
        }
        GoDapProtocol(actual).use { bridge ->
            val server = bridge.server
            assertTrue(server.initialize(InitializeRequestArguments().apply { adapterID = "go" }).join().supportsConfigurationDoneRequest)
            server.attach(mutableMapOf<String, Any>("processId" to 37)).join()
            assertTrue(server.threads().join().threads.isEmpty())
            val disconnected = server.disconnect(DisconnectArguments().apply { terminateDebuggee = false })
            assertTrue(disconnectEntered.await(5, TimeUnit.SECONDS), "The actual endpoint must receive disconnect")
            assertFalse(disconnected.isDone, "The forwarded future must still own the actual endpoint acknowledgement")
            disconnectAck.complete(null)
            disconnected.get(5, TimeUnit.SECONDS)
            assertEquals(listOf("initialize:go", "attach:37", "threads", "disconnect"), calls)
        }
    }
}
