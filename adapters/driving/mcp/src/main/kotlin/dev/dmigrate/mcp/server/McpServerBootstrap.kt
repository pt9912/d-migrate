package dev.dmigrate.mcp.server

import dev.dmigrate.server.application.bootstrap.RuntimeBootstrap
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.runBlocking
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
 * Startpfade fuer §6.2. Die HTTP-Variante bindet einen leeren Ktor-
 * Endpunkt — Routes (`/mcp`, `/.well-known/...`) folgen in AP 6.4/6.5.
 * Die stdio-Variante haelt nur ein Handle vor; lsp4j-NDJSON-Wiring
 * folgt in AP 6.4.
 *
 * Validierungsfehler aus §12.12 werden als `ConfigError` zurueck-
 * gegeben — der Aufrufer (CLI/Embed-Tests) entscheidet, ob er das in
 * eine Exception umwandelt.
 */
object McpServerBootstrap {

    fun startHttp(config: McpServerConfig): McpStartOutcome {
        val errors = config.validate()
        if (errors.isNotEmpty()) return McpStartOutcome.ConfigError(errors)
        RuntimeBootstrap.initialize()
        // Routes (`/mcp`, `/.well-known/...`) follow in AP 6.4/6.5.
        val engine = embeddedServer(
            factory = CIO,
            port = config.port,
            host = config.bindAddress,
            module = { /* placeholder until AP 6.4 */ },
        )
        engine.start(wait = false)
        // Resolve once on the start thread (no coroutine context here)
        // so request-handler coroutines never have to runBlocking.
        val resolvedPort = runBlocking { engine.engine.resolvedConnectors().first().port }
        return McpStartOutcome.Started(KtorHandle(engine, resolvedPort))
    }

    fun startStdio(config: McpServerConfig): McpStartOutcome {
        val errors = config.validate()
        if (errors.isNotEmpty()) return McpStartOutcome.ConfigError(errors)
        RuntimeBootstrap.initialize()
        return McpStartOutcome.Started(StdioHandle())
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

private class StdioHandle : McpServerHandle {
    override val boundPort: Int = 0

    private val stopped = AtomicBoolean(false)

    override fun stop() {
        stopped.compareAndSet(false, true)
    }
}
