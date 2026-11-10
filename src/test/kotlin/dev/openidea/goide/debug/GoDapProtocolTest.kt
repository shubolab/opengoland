package dev.openidea.goide.debug

import org.eclipse.lsp4j.debug.*
import org.eclipse.lsp4j.debug.services.IDebugProtocolServer
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class GoDapProtocolTest {
    private fun globalsError() = ResponseErrorException(ResponseError(-32001, "Unable to list globals", mapOf("error" to mapOf("format" to "unable to determine current package due to unspecified function location"))))
    private fun args() = ScopesArguments().apply { frameId = 1002 }
    private fun locals() = ScopesResponse().apply { scopes = arrayOf(Scope().apply { name = "Locals"; variablesReference = 42 }) }
    private fun await(latch: CountDownLatch, event: String) {
        assertTrue(latch.await(5, TimeUnit.SECONDS), "Missing endpoint event: $event")
    }
    private fun tail(bridge: GoDapProtocol): CompletableFuture<*> = synchronized(bridge) {
        // A cancelled public future may finish before its bounded repair chain.
        // Observe that chain's existing completion event without widening production API.
        GoDapProtocol::class.java.getDeclaredField("tail").apply { isAccessible = true }
            .get(bridge) as CompletableFuture<*>
    }

    private open class Server : IDebugProtocolServer {
        val calls = CopyOnWriteArrayList<String>()
        override fun evaluate(args: EvaluateArguments): CompletableFuture<EvaluateResponse> {
            calls += args.expression
            return CompletableFuture.completedFuture(EvaluateResponse().apply { result = "Updated" })
        }
    }

    @Test fun actualGlobalsFailureRecoversRealLocalsAndRestoresConfiguration() {
        val server = object : Server() {
            var count = 0
            override fun scopes(args: ScopesArguments): CompletableFuture<ScopesResponse> {
                calls += "scopes:${args.frameId}"
                return if (count++ == 0) CompletableFuture.failedFuture(globalsError()) else CompletableFuture.completedFuture(locals())
            }
            override fun variables(args: VariablesArguments): CompletableFuture<VariablesResponse> {
                check(args.variablesReference == 42) { "A private error handle must never be sent to Delve" }
                return CompletableFuture.completedFuture(VariablesResponse().apply { variables = arrayOf(Variable().apply { name = "cart"; value = "Counter{37}"; variablesReference = 43 }) })
            }
        }
        GoDapProtocol(server).use { bridge ->
            val protocol = bridge.server
            val response = protocol.scopes(args()).join()
            assertEquals(listOf("scopes:1002", "dlv config showGlobalVariables false", "scopes:1002", "dlv config showGlobalVariables true"), server.calls)
            assertEquals("Locals", response.scopes[0].name)
            assertEquals(42, response.scopes[0].variablesReference)
            val cart = protocol.variables(VariablesArguments().apply { variablesReference = 42 }).join().variables.single()
            assertEquals("cart", cart.name)
            val error = response.scopes[1]
            assertEquals("Package variables unavailable", error.name)
            assertTrue(error.variablesReference < 0)
            assertTrue(protocol.variables(VariablesArguments().apply { variablesReference = error.variablesReference }).join().variables.single().value.contains("unspecified function location"))
            val rejected = assertFailsWith<java.util.concurrent.CompletionException> {
                protocol.setVariable(SetVariableArguments().apply { variablesReference = error.variablesReference; name = "Unavailable"; value = "1" }).join()
            }
            assertTrue(rejected.cause is IllegalArgumentException)
            assertEquals("An unavailable-variable error cannot be edited", rejected.cause!!.message)
        }
    }

    @Test fun validScopesAndNullOrEmptyVariableArraysKeepTheirRealHandles() {
        val server = object : Server() {
            override fun scopes(args: ScopesArguments) = CompletableFuture.completedFuture(locals())
            override fun variables(args: VariablesArguments) = CompletableFuture.completedFuture(VariablesResponse())
        }
        GoDapProtocol(server).use { bridge ->
            val protocol = bridge.server
            assertEquals(42, protocol.scopes(args()).join().scopes.single().variablesReference)
            assertTrue(protocol.variables(VariablesArguments().apply { variablesReference = 42 }).join().variables.isEmpty())
            assertTrue(server.calls.isEmpty(), "Valid requests must not toggle adapter configuration")
        }
    }

    @Test fun arbitraryScopesAndNestedVariableErrorsFinishWithExplicitErrors() {
        val server = object : Server() {
            override fun scopes(args: ScopesArguments) = CompletableFuture.failedFuture<ScopesResponse>(IllegalStateException("unknown frame id"))
            override fun variables(args: VariablesArguments) = CompletableFuture.failedFuture<VariablesResponse>(IllegalStateException("stale variable reference"))
        }
        GoDapProtocol(server).use { bridge ->
            val protocol = bridge.server
            val reference = protocol.scopes(args()).join().scopes.single().variablesReference
            val error = protocol.variables(VariablesArguments().apply { variablesReference = reference }).join().variables.single()
            assertEquals("error", error.type)
            assertEquals("unknown frame id", error.value)
            assertEquals(0, error.variablesReference)
            assertTrue("readOnly" in error.presentationHint.attributes)
            assertEquals("stale variable reference", protocol.variables(VariablesArguments().apply { variablesReference = 42 }).join().variables.single().value)
            assertTrue(server.calls.isEmpty())
        }
    }

    @Test fun failedLocalsRetryStillRestoresGlobalsAndReportsBothFailures() {
        val server = object : Server() {
            var count = 0
            override fun scopes(args: ScopesArguments) = CompletableFuture.failedFuture<ScopesResponse>(if (count++ == 0) globalsError() else IllegalStateException("frame resumed"))
        }
        GoDapProtocol(server).use { bridge ->
            val protocol = bridge.server
            val response = protocol.scopes(args()).join()
            assertEquals(listOf("dlv config showGlobalVariables false", "dlv config showGlobalVariables true"), server.calls)
            assertEquals(2, response.scopes.size)
            val messages = response.scopes.map { protocol.variables(VariablesArguments().apply { variablesReference = it.variablesReference }).join().variables.single().value }
            assertTrue(messages.any { it.contains("frame resumed") })
            assertTrue(messages.any { it.contains("Unable to list globals") })
        }
    }

    @Test fun cancellationDuringConfigHandoffRestoresAndSkipsFurtherLocalRequests() {
        val disabled = CompletableFuture<EvaluateResponse>()
        val restored = CompletableFuture<EvaluateResponse>()
        val disableEntered = CountDownLatch(1)
        val restoreEntered = CountDownLatch(1)
        val server = object : Server() {
            var scopes = 0
            override fun scopes(args: ScopesArguments): CompletableFuture<ScopesResponse> { scopes++; return CompletableFuture.failedFuture(globalsError()) }
            override fun evaluate(args: EvaluateArguments): CompletableFuture<EvaluateResponse> {
                calls += args.expression
                return if (args.expression.endsWith("false")) {
                    disableEntered.countDown(); disabled
                } else {
                    restoreEntered.countDown(); restored
                }
            }
        }
        GoDapProtocol(server).use { bridge ->
            val protocol = bridge.server
            val pending = protocol.scopes(args())
            await(disableEntered, "disable configuration before close")
            val repairFinished = tail(bridge)
            bridge.close()
            disabled.complete(EvaluateResponse())
            await(restoreEntered, "restore after cancellation")
            assertEquals(1, server.scopes)
            assertEquals(listOf("dlv config showGlobalVariables false", "dlv config showGlobalVariables true"), server.calls)
            restored.complete(EvaluateResponse())
            repairFinished.get(5, TimeUnit.SECONDS)
            assertEquals(1, server.scopes, "Closed recovery must not issue another locals request")
            assertTrue(pending.isCancelled)
            assertTrue(protocol.scopes(args()).isCompletedExceptionally)
        }
    }

    @Test fun closeCannotRestoreBeforeAnAlreadyClaimedDisableFactoryActuallySends() {
        val falseEntered = CountDownLatch(1)
        val releaseFalse = CountDownLatch(1)
        val falseApplied = CountDownLatch(1)
        val trueEntered = CountDownLatch(1)
        val applied = CopyOnWriteArrayList<Boolean>()
        val server = object : Server() {
            override fun scopes(args: ScopesArguments) = CompletableFuture.failedFuture<ScopesResponse>(globalsError())
            override fun evaluate(args: EvaluateArguments): CompletableFuture<EvaluateResponse> {
                if (args.expression.endsWith("false")) {
                    falseEntered.countDown()
                    check(releaseFalse.await(5, TimeUnit.SECONDS)) { "Disable factory was not released" }
                    applied += false
                    falseApplied.countDown()
                } else {
                    applied += true
                    trueEntered.countDown()
                }
                return CompletableFuture.completedFuture(EvaluateResponse())
            }
        }
        GoDapProtocol(server).use { bridge ->
            val pending = bridge.server.scopes(args())
            await(falseEntered, "claimed disable factory before payload")
            val repairFinished = tail(bridge)
            var restoreOvertook = false
            try {
                CompletableFuture.runAsync { bridge.close() }.get(2, TimeUnit.SECONDS)
                assertTrue(pending.isCancelled, "Close must invalidate local work without waiting for the socket factory")
                // A bounded negative observation of factory ordering, not a readiness sleep.
                restoreOvertook = trueEntered.await(200, TimeUnit.MILLISECONDS)
            } finally {
                releaseFalse.countDown()
                await(falseApplied, "disable factory actual application")
                repairFinished.get(5, TimeUnit.SECONDS)
            }
            assertEquals(listOf(false, true), applied,
                "A restore must follow the actual disable send/return; restoreOvertook=$restoreOvertook")
            assertFalse(restoreOvertook, "Restore must not enter while the claimed disable factory is still held")
            assertEquals(true, applied.last(), "The final adapter configuration must be restored")
        }
    }

    @Test fun userConfigUpdateIsQueuedAfterInternalRestore() {
        val retried = CompletableFuture<ScopesResponse>()
        val retryEntered = CountDownLatch(1)
        val server = object : Server() {
            var count = 0
            override fun scopes(args: ScopesArguments): CompletableFuture<ScopesResponse> = if (count++ == 0) {
                CompletableFuture.failedFuture(globalsError())
            } else {
                retryEntered.countDown(); retried
            }
        }
        GoDapProtocol(server).use { bridge ->
            val protocol = bridge.server
            val first = protocol.scopes(args())
            await(retryEntered, "locals retry held before user configuration")
            val update = protocol.evaluate(EvaluateArguments().apply { expression = "dlv config showGlobalVariables false"; context = "repl" })
            assertFalse(update.isDone)
            assertEquals(listOf("dlv config showGlobalVariables false"), server.calls)
            retried.complete(locals())
            first.get(5, TimeUnit.SECONDS); update.get(5, TimeUnit.SECONDS)
            assertEquals(listOf("dlv config showGlobalVariables false", "dlv config showGlobalVariables true", "dlv config showGlobalVariables false"), server.calls)
        }
    }

    @Test fun hungVariableRequestHasDeadlineAndCancelsTheClientRpcFuture() {
        val rpcEntered = CountDownLatch(1)
        val rpcCancelled = CountDownLatch(1)
        val rpc = object : CompletableFuture<VariablesResponse>() {
            override fun cancel(mayInterruptIfRunning: Boolean): Boolean =
                super.cancel(mayInterruptIfRunning).also { rpcCancelled.countDown() }
        }
        val server = object : Server() {
            override fun variables(args: VariablesArguments): CompletableFuture<VariablesResponse> {
                rpcEntered.countDown(); return rpc
            }
        }
        GoDapProtocol(server, timeoutSeconds = 1).use { bridge ->
            val pending = bridge.server.variables(VariablesArguments().apply { variablesReference = 42 })
            await(rpcEntered, "hung variables RPC")
            val response = pending.get(3, TimeUnit.SECONDS)
            assertTrue(response.variables.single().value.contains("timed out"))
            await(rpcCancelled, "client RPC cancellation after deadline")
            assertTrue(rpc.isCancelled)
        }
    }

    @Test fun allExecutionRequestsWaitForGlobalsRestoration() {
        val commands: List<Pair<String, (IDebugProtocolServer) -> CompletableFuture<*>>> = listOf(
            "continue" to { it.continue_(ContinueArguments()) }, "next" to { it.next(NextArguments()) },
            "stepIn" to { it.stepIn(StepInArguments()) }, "stepOut" to { it.stepOut(StepOutArguments()) },
            "stepBack" to { it.stepBack(StepBackArguments()) }, "reverse" to { it.reverseContinue(ReverseContinueArguments()) },
            "goto" to { it.goto_(GotoArguments()) }, "restart" to { it.restart(RestartArguments()) },
            "restartFrame" to { it.restartFrame(RestartFrameArguments()) },
            "call" to { it.evaluate(EvaluateArguments().apply { expression = "call checkpoint(cart)" }) },
            "setVariable" to { it.setVariable(SetVariableArguments().apply {
                variablesReference = 42; this.name = "label"; value = "\"updated\""
            }) },
        )
        for ((name, execute) in commands) {
            val retry = CompletableFuture<ScopesResponse>()
            val restore = CompletableFuture<EvaluateResponse>()
            val retryEntered = CountDownLatch(1)
            val restoreEntered = CountDownLatch(1)
            val server = object : Server() {
                var count = 0
                override fun scopes(args: ScopesArguments): CompletableFuture<ScopesResponse> = if (count++ == 0) CompletableFuture.failedFuture(globalsError()) else {
                    retryEntered.countDown(); retry
                }
                override fun evaluate(args: EvaluateArguments): CompletableFuture<EvaluateResponse> {
                    calls += args.expression
                    return when {
                        args.expression.endsWith("true") -> { restoreEntered.countDown(); restore }
                        else -> CompletableFuture.completedFuture(EvaluateResponse())
                    }
                }
                private fun done(name: String): CompletableFuture<Void> { calls += name; return CompletableFuture.completedFuture(null) }
                override fun continue_(args: ContinueArguments): CompletableFuture<ContinueResponse> { calls += "continue"; return CompletableFuture.completedFuture(ContinueResponse()) }
                override fun next(args: NextArguments) = done("next")
                override fun stepIn(args: StepInArguments) = done("stepIn")
                override fun stepOut(args: StepOutArguments) = done("stepOut")
                override fun stepBack(args: StepBackArguments) = done("stepBack")
                override fun reverseContinue(args: ReverseContinueArguments) = done("reverse")
                override fun goto_(args: GotoArguments) = done("goto")
                override fun restart(args: RestartArguments) = done("restart")
                override fun restartFrame(args: RestartFrameArguments) = done("restartFrame")
                override fun setVariable(args: SetVariableArguments): CompletableFuture<SetVariableResponse> {
                    assertEquals(42, args.variablesReference)
                    assertEquals("label", args.name)
                    assertEquals("\"updated\"", args.value)
                    calls += "setVariable"
                    return CompletableFuture.completedFuture(SetVariableResponse().apply { value = args.value })
                }
            }
            GoDapProtocol(server).use { bridge ->
                val protocol = bridge.server
                val recovered = protocol.scopes(args())
                await(retryEntered, "$name locals retry")
                val resumed = execute(protocol)
                assertFalse(resumed.isDone, name)
                assertEquals(listOf("dlv config showGlobalVariables false"), server.calls, name)
                retry.complete(locals())
                await(restoreEntered, "$name globals restore")
                assertFalse(resumed.isDone, name)
                assertEquals(listOf("dlv config showGlobalVariables false", "dlv config showGlobalVariables true"), server.calls, name)
                restore.complete(EvaluateResponse())
                recovered.get(5, TimeUnit.SECONDS); resumed.get(5, TimeUnit.SECONDS)
                assertEquals("dlv config showGlobalVariables true", server.calls[1], name)
                assertEquals(if (name == "call") "call checkpoint(cart)" else name, server.calls.last(), name)
            }
        }
    }

    @Test fun aFailedRestoreIsRetriedBeforeResumeAndCannotSilentlyLeaveGlobalsDisabled() {
        val server = object : Server() {
            var scopes = 0
            var restores = 0
            override fun scopes(args: ScopesArguments): CompletableFuture<ScopesResponse> = if (scopes++ == 0) CompletableFuture.failedFuture(globalsError()) else CompletableFuture.completedFuture(locals())
            override fun evaluate(args: EvaluateArguments): CompletableFuture<EvaluateResponse> {
                calls += args.expression
                return if (args.expression.endsWith("true") && restores++ == 0) CompletableFuture.failedFuture(IllegalStateException("transient config failure"))
                else CompletableFuture.completedFuture(EvaluateResponse())
            }
            override fun continue_(args: ContinueArguments): CompletableFuture<ContinueResponse> { calls += "continue"; return CompletableFuture.completedFuture(ContinueResponse()) }
        }
        GoDapProtocol(server).use { bridge ->
            val protocol = bridge.server
            protocol.scopes(args()).join()
            protocol.continue_(ContinueArguments()).join()
            assertEquals(listOf("dlv config showGlobalVariables false", "dlv config showGlobalVariables true", "dlv config showGlobalVariables true", "continue"), server.calls)
        }
    }

    @Test fun queueDeadlineIncludesWaitingForAnUnfinishedRecoveryTail() {
        val disable = CompletableFuture<EvaluateResponse>()
        val restore = CompletableFuture<EvaluateResponse>()
        val disableEntered = CountDownLatch(1)
        val restoreEntered = CountDownLatch(1)
        val server = object : Server() {
            override fun scopes(args: ScopesArguments) = CompletableFuture.failedFuture<ScopesResponse>(globalsError())
            override fun evaluate(args: EvaluateArguments): CompletableFuture<EvaluateResponse> = if (args.expression.endsWith("false")) {
                disableEntered.countDown(); disable
            } else {
                restoreEntered.countDown(); restore
            }
        }
        GoDapProtocol(server, timeoutSeconds = 1).use { bridge ->
            val protocol = bridge.server
            val first = protocol.scopes(args())
            await(disableEntered, "unfinished recovery disable")
            val second = protocol.scopes(args())
            val response = second.get(2, TimeUnit.SECONDS)
            assertTrue(response.scopes.single().variablesReference < 0)
            val message = protocol.variables(VariablesArguments().apply { variablesReference = response.scopes.single().variablesReference }).join().variables.single().value
            assertTrue(message.contains("timed out"))
            await(restoreEntered, "unfinished recovery restore")
            assertFalse(restore.isDone, "Queued UI work must finish its deadline before the internal recovery tail completes")
            restore.complete(EvaluateResponse())
            first.get(2, TimeUnit.SECONDS)
        }
    }

    @Test fun cancelledOrStoppedExecutionCannotEscapeALateRestoreAcknowledgement() {
        for (stop in listOf("cancel", "close", "disconnect")) {
            val laterRestore = CompletableFuture<EvaluateResponse>()
            val stopAck = CompletableFuture<Void>()
            val restoring = CountDownLatch(1)
            val disconnectEntered = CountDownLatch(1)
            val server = object : Server() {
                var scopes = 0
                var restores = 0
                override fun scopes(args: ScopesArguments): CompletableFuture<ScopesResponse> = if (scopes++ == 0) CompletableFuture.failedFuture(globalsError()) else CompletableFuture.completedFuture(locals())
                override fun evaluate(args: EvaluateArguments): CompletableFuture<EvaluateResponse> {
                    if (args.expression.endsWith("false")) return CompletableFuture.completedFuture(EvaluateResponse())
                    return if (restores++ == 0) CompletableFuture.failedFuture(IllegalStateException("first restore failed")) else {
                        restoring.countDown(); laterRestore
                    }
                }
                override fun continue_(args: ContinueArguments): CompletableFuture<ContinueResponse> { calls += "continue"; return CompletableFuture.completedFuture(ContinueResponse()) }
                override fun disconnect(args: DisconnectArguments): CompletableFuture<Void> {
                    calls += "disconnect"; disconnectEntered.countDown(); return stopAck
                }
            }
            GoDapProtocol(server).use { bridge ->
                val protocol = bridge.server
                protocol.scopes(args()).get(5, TimeUnit.SECONDS) // The first failed restore leaves repair work.
                val resumed = protocol.continue_(ContinueArguments())
                await(restoring, "$stop later restore before cancellation")
                val repairFinished = tail(bridge)
                assertFalse(resumed.isDone)
                var disconnect: CompletableFuture<Void>? = null
                when (stop) {
                    "cancel" -> resumed.cancel(true)
                    "close" -> bridge.close()
                    else -> {
                        disconnect = protocol.disconnect(DisconnectArguments().apply { terminateDebuggee = false })
                        await(disconnectEntered, "disconnect independent of held restore")
                        assertFalse(disconnect.isDone)
                        assertFalse(stopAck.isDone)
                    }
                }
                laterRestore.complete(EvaluateResponse())
                repairFinished.get(5, TimeUnit.SECONDS)
                assertTrue(resumed.isCancelled, stop)
                assertFalse("continue" in server.calls, "A late restore after $stop must not resume the target")
                stopAck.complete(null)
                disconnect?.get(5, TimeUnit.SECONDS)
            }
        }
    }

    @Test fun executionThatExpiresDuringRestoreDoesNotRunWhenRestoreFinallyCompletes() {
        val blocker = CompletableFuture<EvaluateResponse>()
        val laterRestore = CompletableFuture<EvaluateResponse>()
        val restoring = CompletableFuture<Unit>()
        val blockerEntered = CountDownLatch(1)
        val server = object : Server() {
            var scopes = 0
            var restores = 0
            override fun scopes(args: ScopesArguments): CompletableFuture<ScopesResponse> = if (scopes++ == 0) CompletableFuture.failedFuture(globalsError()) else CompletableFuture.completedFuture(locals())
            override fun evaluate(args: EvaluateArguments): CompletableFuture<EvaluateResponse> {
                if (args.expression.contains("-list")) { blockerEntered.countDown(); return blocker }
                if (args.expression.endsWith("false")) return CompletableFuture.completedFuture(EvaluateResponse())
                if (restores++ == 0) return CompletableFuture.failedFuture(IllegalStateException("first restore failed"))
                restoring.complete(Unit)
                return laterRestore
            }
            override fun continue_(args: ContinueArguments): CompletableFuture<ContinueResponse> { calls += "continue"; return CompletableFuture.completedFuture(ContinueResponse()) }
        }
        GoDapProtocol(server, timeoutSeconds = 1).use { bridge ->
            val protocol = bridge.server
            protocol.scopes(args()).join()
            protocol.evaluate(EvaluateArguments().apply { expression = "dlv config -list" })
            await(blockerEntered, "configuration blocker before queue deadline")
            val resumed = protocol.continue_(ContinueArguments())
            // Deliberately spend half the one-second queue deadline before restore:
            // this timer models deadline residence, never dispatcher readiness.
            CompletableFuture.delayedExecutor(500, TimeUnit.MILLISECONDS).execute { blocker.complete(EvaluateResponse()) }
            restoring.get(2, TimeUnit.SECONDS)
            val repairFinished = tail(bridge)
            assertFailsWith<java.util.concurrent.ExecutionException> { resumed.get(2, TimeUnit.SECONDS) }
            assertFalse(laterRestore.isDone, "The whole-queue deadline must precede this later RPC deadline")
            laterRestore.complete(EvaluateResponse())
            repairFinished.get(5, TimeUnit.SECONDS)
            assertFalse("continue" in server.calls)
        }
    }
}
