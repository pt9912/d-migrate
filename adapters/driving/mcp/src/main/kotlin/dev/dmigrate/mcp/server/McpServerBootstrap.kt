package dev.dmigrate.mcp.server

import dev.dmigrate.mcp.auth.FileStdioTokenStore
import dev.dmigrate.mcp.auth.StdioPrincipalResolution
import dev.dmigrate.mcp.auth.StdioPrincipalResolver
import dev.dmigrate.mcp.protocol.McpService
import dev.dmigrate.mcp.protocol.McpServiceImpl
import dev.dmigrate.mcp.registry.PhaseBRegistries
import dev.dmigrate.mcp.registry.ToolRegistry
import dev.dmigrate.mcp.transport.http.installMcpHttpRoute
import dev.dmigrate.mcp.transport.stdio.StdioJsonRpc
import dev.dmigrate.server.application.bootstrap.RuntimeBootstrap
import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.ports.StdioTokenStore
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.runBlocking
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * In-process Lifecycle-Handle fuer einen gestarteten MCP-Server.
 * `boundPort` ist die effektiv genutzte HTTP-Port-Nummer fuer Tests
 * mit `port = 0` (ephemeral); bei stdio ist sie `0`, weil kein
 * Netzwerkport gebunden wird.
 */
interface McpServerHandle : AutoCloseable {
    val boundPort: Int
    fun stop()
    override fun close() {
        stop()
    }
}

sealed interface McpStartOutcome {
    data class Started(val handle: McpServerHandle) : McpStartOutcome
    data class ConfigError(val errors: List<String>) : McpStartOutcome
}

/**
 * Startpfade fuer §6.2 + §6.4.
 *
 * AP 6.4 wires the JSON-RPC layer:
 * - HTTP installs the `POST /mcp` route on the Ktor engine.
 * - stdio attaches a [StdioJsonRpc] read loop to the supplied
 *   in/out streams (defaults to `System.in`/`System.out`).
 *
 * Both transports dispatch into [McpServiceImpl]. AP 6.5 adds the
 * `MCP-Session-Id` lifecycle and routes for HTTP; AP 6.6 adds Auth.
 *
 * Validierungsfehler aus §12.12 werden als `ConfigError` zurueck-
 * gegeben — der Aufrufer (CLI/Embed-Tests) entscheidet, ob er das in
 * eine Exception umwandelt.
 */
object McpServerBootstrap {

    /**
     * @param toolRegistry transport-neutral registry per §4.7 — same
     *  instance is reused for stdio when both transports run in the
     *  same process. Defaults to [PhaseBRegistries.toolRegistry] which
     *  registers every 0.9.6 tool with `capabilities_list` as the
     *  only real handler.
     */
    fun startHttp(
        config: McpServerConfig,
        serverVersion: String = "0.0.0",
        toolRegistry: ToolRegistry = PhaseBRegistries.toolRegistry(config.scopeMapping),
    ): McpStartOutcome {
        val errors = config.validate()
        if (errors.isNotEmpty()) return McpStartOutcome.ConfigError(errors)
        RuntimeBootstrap.initialize()
        val engine = embeddedServer(
            factory = CIO,
            port = config.port,
            host = config.bindAddress,
            module = {
                // AP 6.5: full Streamable-HTTP route (POST/GET/DELETE,
                // Origin, Session-Id, Protocol-Version, Accept). AP 6.6
                // wraps Bearer/JWKS auth around the dispatch chain.
                // AP 6.8: serviceFactory builds an McpServiceImpl per
                // session that shares the route-level toolRegistry —
                // §6.8 acceptance ("stdio und HTTP nutzen dieselben
                // Registry-Instanzen").
                installMcpHttpRoute(
                    config = config,
                    serviceFactory = {
                        // initialPrincipal=null is correct: the route
                        // calls McpServiceImpl.bindPrincipal(...) per
                        // dispatch (after Bearer-validation), so the
                        // service never sees a stale principal.
                        McpServiceImpl(
                            serverVersion = serverVersion,
                            toolRegistry = toolRegistry,
                        )
                    },
                )
            },
        )
        engine.start(wait = false)
        val resolvedPort = runBlocking { engine.engine.resolvedConnectors().first().port }
        return McpStartOutcome.Started(KtorHandle(engine, resolvedPort))
    }

    /**
     * @param tokenStoreOverride bypasses the file-backed default
     *  ([FileStdioTokenStore.load]) — tests inject an in-memory
     *  `StdioTokenStore` here so they never need to write a token file
     *  to disk. Production callers leave this `null`; the resolver
     *  falls back to `config.stdioTokenFile` (or "no registry
     *  configured" if the field is also null).
     * @param tokenSupplier `DMIGRATE_MCP_STDIO_TOKEN` accessor;
     *  defaults to the OS env var. Tests inject a deterministic
     *  supplier.
     */
    fun startStdio(
        config: McpServerConfig,
        input: InputStream = System.`in`,
        output: OutputStream = System.out,
        serverVersion: String = "0.0.0",
        tokenStoreOverride: StdioTokenStore? = null,
        tokenSupplier: () -> String? = { System.getenv(STDIO_TOKEN_ENV) },
        toolRegistry: ToolRegistry = PhaseBRegistries.toolRegistry(config.scopeMapping),
    ): McpStartOutcome {
        val errors = config.validate()
        if (errors.isNotEmpty()) return McpStartOutcome.ConfigError(errors)
        RuntimeBootstrap.initialize()
        val store = tokenStoreOverride ?: config.stdioTokenFile?.let(FileStdioTokenStore::load)
        // §4.2 / §6.7: a missing/unknown principal does NOT crash the
        // server — initialize is auth-exempt; tool/resource calls
        // surface the principal absence via failedAuthRequiredEnvelope.
        val resolution = StdioPrincipalResolver(tokenSupplier, store).resolve()
        val principal: PrincipalContext? = (resolution as? StdioPrincipalResolution.Resolved)?.principal
        val service: McpService = McpServiceImpl(
            serverVersion = serverVersion,
            toolRegistry = toolRegistry,
            initialPrincipal = principal,
        )
        val rpc = StdioJsonRpc(input, output, service, principalResolution = resolution)
            .apply { start() }
        return McpStartOutcome.Started(StdioHandle(rpc))
    }

    private const val STDIO_TOKEN_ENV: String = "DMIGRATE_MCP_STDIO_TOKEN"
}

private class KtorHandle(
    private val engine: EmbeddedServer<*, *>,
    override val boundPort: Int,
) : McpServerHandle {

    private val stopped = AtomicBoolean(false)

    override fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        engine.stop(gracePeriodMillis = 0, timeoutMillis = 0)
    }
}

private class StdioHandle(
    private val rpc: StdioJsonRpc,
) : McpServerHandle {
    override val boundPort: Int = 0

    private val stopped = AtomicBoolean(false)

    override fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        rpc.stop()
    }
}
