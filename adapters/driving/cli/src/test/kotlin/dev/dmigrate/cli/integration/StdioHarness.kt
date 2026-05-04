package dev.dmigrate.cli.integration

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import dev.dmigrate.mcp.protocol.InitializeParams
import dev.dmigrate.mcp.protocol.InitializeResult
import dev.dmigrate.mcp.protocol.ToolsCallParams
import dev.dmigrate.mcp.protocol.ToolsCallResult
import dev.dmigrate.mcp.protocol.ToolsListResult
import dev.dmigrate.cli.commands.McpStateDirLock
import dev.dmigrate.mcp.server.AuthMode
import dev.dmigrate.mcp.server.McpServerBootstrap
import dev.dmigrate.mcp.server.McpServerConfig
import dev.dmigrate.mcp.server.McpStartOutcome
import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.ports.memory.InMemoryAuditSink
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.io.PrintWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * AP 6.24 E1: stdio harness using an in-process [McpServerBootstrap.startStdio]
 * wired via two `PipedStream` pairs. The server runs on its own
 * daemon thread; the test thread writes JSON-RPC requests into the
 * server's stdin pipe and pulls responses off the server's stdout
 * pipe via a small reader thread that lifts each NDJSON line into a
 * blocking queue.
 *
 * The wiring is the AP-6.21 file-backed Phase-C wiring with an
 * [InMemoryAuditSink] attached so `tests` can assert audit-event
 * shape per [auditSink].
 */
internal class StdioHarness(
    override val stateDir: Path,
    override val principal: PrincipalContext,
    private val auditSinkRef: InMemoryAuditSink,
    private val wiringRef: dev.dmigrate.mcp.registry.PhaseCWiring,
    private val handle: dev.dmigrate.mcp.server.McpServerHandle,
    private val clientToServer: PipedOutputStream,
    private val serverToClient: PipedInputStream,
    private val readerThread: Thread,
    private val responseQueue: LinkedBlockingQueue<String>,
    private val notificationsHandled: java.util.concurrent.atomic.AtomicLong,
    private val stateDirLock: McpStateDirLock,
) : McpClientHarness {

    override val name: String = "stdio"

    val auditSink: InMemoryAuditSink get() = auditSinkRef

    override val wiring: dev.dmigrate.mcp.registry.PhaseCWiring get() = wiringRef

    private val rpc = JsonRpcClient()
    private val gson: Gson = GsonBuilder().disableHtmlEscaping().serializeNulls().create()
    private val writer = PrintWriter(OutputStreamWriter(clientToServer, StandardCharsets.UTF_8), false)

    override fun initialize(params: InitializeParams): InitializeResult {
        val raw = call("initialize", gson.toJsonTree(params))
        return gson.fromJson(raw, InitializeResult::class.java)
    }

    override fun initializedNotification() {
        send(rpc.notification("notifications/initialized"))
    }

    override fun toolsList(): ToolsListResult =
        gson.fromJson(call("tools/list", null), ToolsListResult::class.java)

    override fun toolsCall(name: String, arguments: JsonElement?): ToolsCallResult {
        val params = ToolsCallParams(name = name, arguments = arguments)
        return gson.fromJson(call("tools/call", gson.toJsonTree(params)), ToolsCallResult::class.java)
    }

    override fun resourcesRead(uri: String): JsonElement = resourcesReadRaw(uri).resultOrThrow()

    override fun resourcesReadRaw(uri: String): JsonRpcResponse {
        val params = com.google.gson.JsonObject().apply { addProperty("uri", uri) }
        return callRaw("resources/read", params)
    }

    private fun call(method: String, params: JsonElement?): JsonElement = callRaw(method, params).resultOrThrow()

    private fun callRaw(method: String, params: JsonElement?): JsonRpcResponse {
        val id = rpc.nextId()
        send(rpc.request(method, params, id))
        val raw = responseQueue.poll(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            ?: error("stdio: no response within ${REQUEST_TIMEOUT_SECONDS}s for $method")
        val parsed = rpc.parseResponse(raw)
        require(parsed.id == id) { "stdio: response id ${parsed.id} != request id $id" }
        return parsed
    }

    @Suppress("unused")
    fun notificationsObserved(): Long = notificationsHandled.get()

    private fun send(line: String) {
        writer.println(line)
        writer.flush()
        if (writer.checkError()) throw IOException("stdio writer reported error")
    }

    override fun close() {
        try { writer.close() } catch (_: IOException) {}
        try { clientToServer.close() } catch (_: IOException) {}
        handle.stop()
        readerThread.interrupt()
        readerThread.join(STOP_TIMEOUT_MILLIS)
        try { serverToClient.close() } catch (_: IOException) {}
        // Mirror McpServerLifecycle: release the advisory lock LAST so
        // a parallel start attempt that's racing on the same stateDir
        // can never observe the lock free while the server is still
        // shutting down its IO.
        try { stateDirLock.close() } catch (_: Throwable) {}
    }

    companion object {
        const val REQUEST_TIMEOUT_SECONDS: Long = 10
        const val STOP_TIMEOUT_MILLIS: Long = 5_000

        fun start(
            stateDir: Path,
            principal: PrincipalContext,
            limits: dev.dmigrate.mcp.server.McpLimitsConfig = dev.dmigrate.mcp.server.McpLimitsConfig(),
        ): StdioHarness = when (val outcome = tryStart(stateDir, principal, limits)) {
            is StartOutcome.Started -> outcome.harness
            is StartOutcome.LockConflict -> error("stdio harness lock conflict: ${outcome.diagnostic}")
            is StartOutcome.BootstrapError -> error("stdio bootstrap failed: ${outcome.errors.joinToString()}")
            is StartOutcome.LockFailed -> error("stdio harness lock failed: ${outcome.message}")
        }

        /**
         * AP 6.24 E7: factory variant that surfaces the lock-conflict
         * branch so the lock-/concurrency-test can pin it without
         * unwinding through `error(...)`. All other failure modes
         * collapse to typed outcomes too; tests should pattern-match
         * on the sealed [StartOutcome].
         */
        @Suppress("LongMethod")
        fun tryStart(
            stateDir: Path,
            principal: PrincipalContext,
            limits: dev.dmigrate.mcp.server.McpLimitsConfig = dev.dmigrate.mcp.server.McpLimitsConfig(),
        ): StartOutcome {
            // §6.24 + §6.21: acquire the advisory lock BEFORE any
            // wiring or pipe construction so a conflicting start
            // produces zero side effects (no audit sink, no spool
            // dirs, no reader thread). Mirrors McpCommand.run().
            val lock = when (val r = McpStateDirLock.tryAcquire(stateDir, version = LOCK_VERSION)) {
                is McpStateDirLock.AcquireOutcome.Acquired -> r.lock
                is McpStateDirLock.AcquireOutcome.Conflict -> return StartOutcome.LockConflict(r.diagnostic)
                is McpStateDirLock.AcquireOutcome.Failed -> return StartOutcome.LockFailed(r.message)
            }

            val wiringBundle = IntegrationFixtures.integrationWiring(stateDir, limits = limits)
            val clientOut = PipedOutputStream()
            val serverIn = PipedInputStream(clientOut, PIPE_BUFFER_BYTES)
            val serverOut = PipedOutputStream()
            val clientIn = PipedInputStream(serverOut, PIPE_BUFFER_BYTES)

            val notificationsHandled = java.util.concurrent.atomic.AtomicLong(0)
            val responseQueue = LinkedBlockingQueue<String>()
            val reader = BufferedReader(InputStreamReader(clientIn, StandardCharsets.UTF_8))
            val readerThread = Thread({
                try {
                    while (!Thread.currentThread().isInterrupted) {
                        val line = reader.readLine() ?: break
                        if (line.isBlank()) continue
                        // Notifications without an `id`: lift to counter only.
                        val parsed = JsonRpcClient().parseResponse(line)
                        if (parsed.id == null) notificationsHandled.incrementAndGet()
                        else responseQueue.put(line)
                    }
                } catch (_: IOException) { /* server closed */ }
                  catch (_: InterruptedException) { /* harness stopped */ }
            }, "mcp-stdio-harness-reader").apply {
                isDaemon = true
                start()
            }

            val config = McpServerConfig(
                bindAddress = "127.0.0.1",
                port = 0,
                authMode = AuthMode.DISABLED,
            )
            val token = "it-test-token-${java.util.UUID.randomUUID()}"
            val tokenStore = StubStdioTokenStore.forPrincipal(principal, token)
            val outcome = McpServerBootstrap.startStdio(
                config = config,
                input = serverIn,
                output = serverOut,
                phaseCWiring = wiringBundle.wiring,
                tokenStoreOverride = tokenStore,
                tokenSupplier = { token },
            )
            val handle = when (outcome) {
                is McpStartOutcome.Started -> outcome.handle
                is McpStartOutcome.ConfigError -> {
                    // Bootstrap failed AFTER we took the lock — release
                    // it so a retry can succeed and the stateDir cleanup
                    // path doesn't block on a dangling lockfile.
                    try { lock.close() } catch (_: Throwable) {}
                    readerThread.interrupt()
                    return StartOutcome.BootstrapError(outcome.errors)
                }
            }
            return StartOutcome.Started(
                StdioHarness(
                    stateDir = stateDir,
                    principal = principal,
                    auditSinkRef = wiringBundle.auditSink,
                    wiringRef = wiringBundle.wiring,
                    handle = handle,
                    clientToServer = clientOut,
                    serverToClient = clientIn,
                    readerThread = readerThread,
                    responseQueue = responseQueue,
                    notificationsHandled = notificationsHandled,
                    stateDirLock = lock,
                ),
            )
        }

        private const val PIPE_BUFFER_BYTES: Int = 1024 * 1024
        private const val LOCK_VERSION: String = "0.0.0-it-stdio"
    }

    /**
     * AP 6.24 E7: typed outcome so the lock-/concurrency-test can
     * pin a [LockConflict] without throwing. All other modes also
     * surface as discriminated cases so the test runner gets the
     * exact failure class on the wire.
     */
    sealed interface StartOutcome {
        data class Started(val harness: StdioHarness) : StartOutcome
        data class LockConflict(val diagnostic: String) : StartOutcome
        data class BootstrapError(val errors: List<String>) : StartOutcome
        data class LockFailed(val message: String) : StartOutcome
    }
}
