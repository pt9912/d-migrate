package dev.dmigrate.mcp.server

import dev.dmigrate.mcp.protocol.McpService
import dev.dmigrate.mcp.protocol.McpServiceImpl
import dev.dmigrate.mcp.transport.http.installMcpHttpRoute
import dev.dmigrate.mcp.transport.stdio.StdioJsonRpc
import dev.dmigrate.server.application.bootstrap.RuntimeBootstrap
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

    fun startHttp(
        config: McpServerConfig,
        serverVersion: String = "0.0.0",
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
                installMcpHttpRoute(
                    config = config,
                    serviceFactory = { McpServiceImpl(serverVersion) },
                )
            },
        )
        engine.start(wait = false)
        val resolvedPort = runBlocking { engine.engine.resolvedConnectors().first().port }
        return McpStartOutcome.Started(KtorHandle(engine, resolvedPort))
    }

    fun startStdio(
        config: McpServerConfig,
        input: InputStream = System.`in`,
        output: OutputStream = System.out,
        serverVersion: String = "0.0.0",
    ): McpStartOutcome {
        val errors = config.validate()
        if (errors.isNotEmpty()) return McpStartOutcome.ConfigError(errors)
        RuntimeBootstrap.initialize()
        val service: McpService = McpServiceImpl(serverVersion)
        val rpc = StdioJsonRpc(input, output, service).apply { start() }
        return McpStartOutcome.Started(StdioHandle(rpc))
    }
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
