package dev.dmigrate.cli.integration

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import dev.dmigrate.mcp.protocol.InitializeParams
import dev.dmigrate.mcp.protocol.InitializeResult
import dev.dmigrate.mcp.protocol.ToolsCallParams
import dev.dmigrate.mcp.protocol.ToolsCallResult
import dev.dmigrate.mcp.protocol.ToolsListResult
import dev.dmigrate.cli.commands.McpStateDirLock
import dev.dmigrate.mcp.auth.DisabledAuthValidator
import dev.dmigrate.mcp.server.AuthMode
import dev.dmigrate.mcp.server.McpServerBootstrap
import dev.dmigrate.mcp.server.McpServerConfig
import dev.dmigrate.mcp.server.McpStartOutcome
import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.ports.memory.InMemoryAuditSink
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration

/**
 * AP 6.24 E1: HTTP harness using an in-process [McpServerBootstrap.startHttp]
 * on `127.0.0.1:0`. The bound port is read from the
 * [McpServerHandle.boundPort] after start; tests never see a fixed
 * port. Auth is `DISABLED` (loopback-only per §12.12), so no
 * Bearer / JWT machinery — the server treats every request as
 * authenticated against [principal].
 *
 * Session lifecycle:
 * - the first request MUST be `initialize` and MUST NOT carry an
 *   `MCP-Session-Id` header. The server returns the session id in
 *   the `MCP-Session-Id` response header.
 * - subsequent requests carry both `MCP-Session-Id` and
 *   `MCP-Protocol-Version` headers (the latter copied from the
 *   initialize response).
 *
 * The wiring is the AP-6.21 file-backed Phase-C wiring built explicitly
 * via [IntegrationFixtures.integrationWiring] — plan §6.24 forbids
 * HTTP from inheriting the file-backed wiring implicitly from the
 * stdio CLI path.
 */
internal class HttpHarness(
    override val stateDir: Path,
    override val principal: PrincipalContext,
    private val auditSinkRef: InMemoryAuditSink,
    private val wiringRef: dev.dmigrate.mcp.registry.PhaseCWiring,
    private val handle: dev.dmigrate.mcp.server.McpServerHandle,
    private val baseUri: URI,
    private val stateDirLock: McpStateDirLock,
) : McpClientHarness {

    override val name: String = "http"

    val auditSink: InMemoryAuditSink get() = auditSinkRef

    /** Test-only wiring access; see [StdioHarness.wiring]. */
    internal val wiring: dev.dmigrate.mcp.registry.PhaseCWiring get() = wiringRef

    private val rpc = JsonRpcClient()
    private val gson: Gson = GsonBuilder().disableHtmlEscaping().serializeNulls().create()
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT)
        .build()
    private val diagnosticHistory = java.util.concurrent.ConcurrentLinkedDeque<HarnessRpcExchange>()

    @Volatile private var sessionId: String? = null
    @Volatile private var protocolVersion: String = McpClientHarness.PROTOCOL_VERSION

    override fun initialize(params: InitializeParams): InitializeResult {
        val raw = sendRequestRaw("initialize", gson.toJsonTree(params), allowSessionHeaders = false)
        val response = raw.parsed.resultOrThrow()
        sessionId = raw.responseHeaders.firstValue(HEADER_SESSION_ID).orElseThrow {
            error("HTTP initialize did not return $HEADER_SESSION_ID")
        }
        raw.responseHeaders.firstValue(HEADER_PROTOCOL_VERSION).ifPresent { protocolVersion = it }
        return gson.fromJson(response, InitializeResult::class.java)
    }

    override fun initializedNotification() {
        sendNotification("notifications/initialized", null)
    }

    override fun toolsList(): ToolsListResult =
        gson.fromJson(call("tools/list", null), ToolsListResult::class.java)

    override fun toolsCall(name: String, arguments: JsonElement?): ToolsCallResult {
        val params = ToolsCallParams(name = name, arguments = arguments)
        val raw = sendRequestRaw("tools/call", gson.toJsonTree(params), allowSessionHeaders = true, toolName = name)
        return gson.fromJson(raw.parsed.resultOrThrow(), ToolsCallResult::class.java)
    }

    override fun dumpDiagnostics(reason: String): String =
        HarnessDiagnostics.render(name, stateDir, diagnosticHistory.toList(), reason)

    override fun resourcesRead(uri: String): JsonElement = resourcesReadRaw(uri).resultOrThrow()

    override fun resourcesReadRaw(uri: String): JsonRpcResponse {
        val params = JsonObject().apply { addProperty("uri", uri) }
        return sendRequestRaw("resources/read", params, allowSessionHeaders = true).parsed
    }

    override fun resourcesListRaw(cursor: String?): JsonRpcResponse {
        val params = JsonObject().apply {
            if (cursor != null) addProperty("cursor", cursor)
        }
        return sendRequestRaw("resources/list", params, allowSessionHeaders = true).parsed
    }

    override fun resourcesTemplatesListRaw(): JsonRpcResponse =
        sendRequestRaw("resources/templates/list", JsonObject(), allowSessionHeaders = true).parsed

    private fun call(method: String, params: JsonElement?): JsonElement =
        sendRequestRaw(method, params, allowSessionHeaders = true).parsed.resultOrThrow()

    private fun sendRequestRaw(
        method: String,
        params: JsonElement?,
        allowSessionHeaders: Boolean,
        toolName: String? = null,
    ): HttpRpcResult {
        val id = rpc.nextId()
        val body = rpc.request(method, params, id)
        val req = buildRequest(body, allowSessionHeaders)
        val response = httpClient.send(req, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
        check(response.statusCode() in 200..299) {
            "HTTP $method failed: status=${response.statusCode()} body=${response.body()}"
        }
        val parsed = rpc.parseResponse(response.body())
        require(parsed.id == id) { "HTTP: response id ${parsed.id} != request id $id (method=$method)" }
        recordExchange(method, parsed, toolName)
        return HttpRpcResult(parsed, response.headers())
    }

    /** Same ring-buffer semantics as [StdioHarness.recordExchange]. */
    private fun recordExchange(method: String, response: JsonRpcResponse, toolName: String?) {
        val outcome = response.error?.let { "err ${it.get("code")?.asInt}" } ?: "ok"
        diagnosticHistory.add(HarnessRpcExchange(method, outcome, toolName))
        while (diagnosticHistory.size > McpClientHarness.DIAGNOSTIC_RPC_HISTORY_SIZE) {
            diagnosticHistory.pollFirst()
        }
    }

    /**
     * AP 6.24 §6.24 final-review: posts a raw JSON-RPC body and
     * returns `(statusCode, body)` WITHOUT the 2xx-only assertion in
     * [sendRequestRaw]. Scenario tests that need to scan an HTTP
     * error body (Origin-rejection, session-validation failure,
     * payload-too-large, etc.) for secret leaks dispatch through
     * this method. Optional [extraHeaders] can override (or replace)
     * the standard headers; pass `Origin = ""` to skip that header
     * entirely so the route's allowlist rejects the request.
     */
    fun postUnchecked(
        body: String,
        extraHeaders: Map<String, String> = emptyMap(),
    ): Pair<Int, String> {
        val builder = HttpRequest.newBuilder(baseUri)
            .timeout(REQUEST_TIMEOUT)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body, Charsets.UTF_8))
        // Default Origin if caller didn't override.
        if ("Origin" !in extraHeaders) {
            builder.header("Origin", baseUri.scheme + "://" + baseUri.authority)
        }
        extraHeaders.forEach { (k, v) ->
            // Empty-string sentinel = "skip this header". Lets the
            // test send a request with NO Origin to trigger the
            // "<absent>" branch of the allowlist rejection.
            if (v.isNotEmpty()) builder.header(k, v)
        }
        val response = httpClient.send(
            builder.build(),
            HttpResponse.BodyHandlers.ofString(Charsets.UTF_8),
        )
        return response.statusCode() to response.body()
    }

    private fun sendNotification(method: String, params: JsonElement?) {
        val body = rpc.notification(method, params)
        val req = buildRequest(body, allowSessionHeaders = true)
        val response = httpClient.send(req, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
        // Notifications return 202 Accepted with empty body.
        check(response.statusCode() == STATUS_ACCEPTED) {
            "HTTP notification $method expected $STATUS_ACCEPTED, got ${response.statusCode()}"
        }
    }

    private fun buildRequest(body: String, allowSessionHeaders: Boolean): HttpRequest {
        val builder = HttpRequest.newBuilder(baseUri)
            .timeout(REQUEST_TIMEOUT)
            .header("Content-Type", "application/json")
            .header("Origin", baseUri.scheme + "://" + baseUri.authority)
            .POST(HttpRequest.BodyPublishers.ofString(body, Charsets.UTF_8))
        if (allowSessionHeaders) {
            sessionId?.let { builder.header(HEADER_SESSION_ID, it) }
            builder.header(HEADER_PROTOCOL_VERSION, protocolVersion)
        }
        return builder.build()
    }

    override fun close() {
        handle.stop()
        // Release the advisory lock LAST so a parallel start racing
        // on the same stateDir can never see it free while ktor is
        // still tearing down its connectors.
        try { stateDirLock.close() } catch (_: Throwable) {}
    }

    private data class HttpRpcResult(
        val parsed: JsonRpcResponse,
        val responseHeaders: java.net.http.HttpHeaders,
    )

    companion object {
        private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(5)
        private val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(30)
        private const val STATUS_ACCEPTED: Int = 202
        private const val HEADER_SESSION_ID: String = "MCP-Session-Id"
        private const val HEADER_PROTOCOL_VERSION: String = "MCP-Protocol-Version"

        fun start(
            stateDir: Path,
            principal: PrincipalContext,
            limits: dev.dmigrate.mcp.server.McpLimitsConfig = dev.dmigrate.mcp.server.McpLimitsConfig(),
        ): HttpHarness = when (val outcome = tryStart(stateDir, principal, limits)) {
            is StartOutcome.Started -> outcome.harness
            is StartOutcome.LockConflict -> error("http harness lock conflict: ${outcome.diagnostic}")
            is StartOutcome.BootstrapError -> error("http bootstrap failed: ${outcome.errors.joinToString()}")
            is StartOutcome.LockFailed -> error("http harness lock failed: ${outcome.message}")
        }

        /**
         * AP 6.24 E7: factory variant that surfaces the lock-conflict
         * branch so the lock-/concurrency-test can pin it without
         * unwinding through `error(...)`. Mirrors
         * [StdioHarness.tryStart] so the scenario runner can call
         * either transport polymorphically.
         */
        fun tryStart(
            stateDir: Path,
            principal: PrincipalContext,
            limits: dev.dmigrate.mcp.server.McpLimitsConfig = dev.dmigrate.mcp.server.McpLimitsConfig(),
        ): StartOutcome {
            // §6.24 + §6.21: acquire the advisory lock BEFORE any
            // wiring or ktor engine construction so a conflicting
            // start produces zero side effects (no audit sink, no
            // bound port, no ktor threads). Mirrors McpCommand.run().
            val lock = when (val r = McpStateDirLock.tryAcquire(stateDir, version = LOCK_VERSION)) {
                is McpStateDirLock.AcquireOutcome.Acquired -> r.lock
                is McpStateDirLock.AcquireOutcome.Conflict -> return StartOutcome.LockConflict(r.diagnostic)
                is McpStateDirLock.AcquireOutcome.Failed -> return StartOutcome.LockFailed(r.message)
            }

            val wiringBundle = IntegrationFixtures.integrationWiring(stateDir, limits = limits)
            // Default scope mapping: PhaseCRegistries.defaultToolRegistry
            // requires `capabilities_list` (and the rest of the Phase-C
            // tools) to be present in the scope mapping at registration
            // time, even though `AuthMode.DISABLED` short-circuits the
            // route's runtime scope check.
            val config = McpServerConfig(
                bindAddress = "127.0.0.1",
                port = 0,
                authMode = AuthMode.DISABLED,
            )
            // §6.24: inject a per-run DisabledAuthValidator so the
            // HTTP route's AuthMode.DISABLED short-circuit returns
            // THIS harness's principal — not the static
            // ANONYMOUS_PRINCIPAL the production default uses. This
            // is what makes per-transport-run principal isolation
            // work end-to-end on HTTP without switching to a
            // non-DISABLED auth flow.
            val outcome = McpServerBootstrap.startHttp(
                config = config,
                phaseCWiring = wiringBundle.wiring,
                authValidatorOverride = DisabledAuthValidator(principal = principal),
            )
            val started = when (outcome) {
                is McpStartOutcome.Started -> outcome
                is McpStartOutcome.ConfigError -> {
                    try { lock.close() } catch (_: Throwable) {}
                    return StartOutcome.BootstrapError(outcome.errors)
                }
            }
            val baseUri = URI.create("http://127.0.0.1:${started.handle.boundPort}/mcp")
            // §6.24: bounded readiness retry before the first
            // scenario request. Probe the well-known metadata
            // endpoint (always 200 OK on AuthMode.DISABLED, no
            // session required) until reachable or the timeout
            // elapses. In-process Ktor returns once the connector
            // is bound, but the spec demands an explicit probe so a
            // future async-bind change cannot regress the suite.
            awaitHttpReadiness(started.handle.boundPort)
            // AuthMode.DISABLED means the route does not derive the
            // principal from a Bearer token — the integration suite
            // pins the principal at the wiring layer instead. We pass
            // it back on the harness surface so spec asserts can
            // reference it.
            return StartOutcome.Started(
                HttpHarness(
                    stateDir = stateDir,
                    principal = principal,
                    auditSinkRef = wiringBundle.auditSink,
                    wiringRef = wiringBundle.wiring,
                    handle = started.handle,
                    baseUri = baseUri,
                    stateDirLock = lock,
                ),
            )
        }

        private const val LOCK_VERSION: String = "0.0.0-it-http"

        /**
         * Bounded readiness retry per §6.24 acceptance:
         * "HTTP-Harness wartet mit bounded Readiness-Retry vor dem
         * ersten Szenario-Request; direkte Sleeps ohne Probe sind
         * nicht zulaessig". Probes the well-known metadata
         * endpoint (always 200 OK on AuthMode.DISABLED) at fixed
         * intervals until it answers or the deadline elapses.
         * Throws on timeout — a non-ready engine is a hard test
         * setup failure, never silently swallowed.
         */
        private fun awaitHttpReadiness(port: Int) {
            val client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(READINESS_PROBE_INTERVAL_MS))
                .build()
            val probeUri = URI.create("http://127.0.0.1:$port/.well-known/oauth-protected-resource")
            val req = HttpRequest.newBuilder(probeUri)
                .timeout(Duration.ofMillis(READINESS_PROBE_INTERVAL_MS))
                .GET()
                .build()
            val deadline = System.currentTimeMillis() + READINESS_DEADLINE_MS
            while (System.currentTimeMillis() < deadline) {
                val ok = runCatching {
                    val r = client.send(req, HttpResponse.BodyHandlers.discarding())
                    r.statusCode() == HTTP_STATUS_OK
                }.getOrDefault(false)
                if (ok) return
                Thread.sleep(READINESS_PROBE_INTERVAL_MS)
            }
            error("HTTP harness: server on port $port did not become ready within ${READINESS_DEADLINE_MS}ms")
        }

        private const val READINESS_PROBE_INTERVAL_MS: Long = 50
        private const val READINESS_DEADLINE_MS: Long = 5_000
        private const val HTTP_STATUS_OK: Int = 200
    }

    /** AP 6.24 E7: typed start outcome — see [StdioHarness.StartOutcome]. */
    sealed interface StartOutcome {
        data class Started(val harness: HttpHarness) : StartOutcome
        data class LockConflict(val diagnostic: String) : StartOutcome
        data class BootstrapError(val errors: List<String>) : StartOutcome
        data class LockFailed(val message: String) : StartOutcome
    }
}
