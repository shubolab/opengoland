package dev.openidea.goide.debug

import org.eclipse.lsp4j.debug.Capabilities
import org.eclipse.lsp4j.debug.DisconnectArguments
import org.eclipse.lsp4j.debug.InitializeRequestArguments
import org.eclipse.lsp4j.debug.ThreadsResponse
import org.eclipse.lsp4j.debug.services.IDebugProtocolServer
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GoDapProtocolForwardingTest {
    @Test fun javaDefaultRpcMethodsReachTheActualEndpoint() {
        val calls = mutableListOf<String>()
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
                return CompletableFuture.completedFuture(null)
            }
        }
        GoDapProtocol(actual).use { bridge ->
            val server = bridge.server
            assertTrue(server.initialize(InitializeRequestArguments().apply { adapterID = "go" }).join().supportsConfigurationDoneRequest)
            server.attach(mutableMapOf<String, Any>("processId" to 37)).join()
            assertTrue(server.threads().join().threads.isEmpty())
            server.disconnect(DisconnectArguments().apply { terminateDebuggee = false }).join()
            assertEquals(listOf("initialize:go", "attach:37", "threads", "disconnect"), calls)
        }
    }
}
