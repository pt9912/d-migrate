package dev.dmigrate.mcp.transport.stdio

import dev.dmigrate.mcp.protocol.McpService
import dev.dmigrate.mcp.transport.McpEndpointFactory
import org.slf4j.LoggerFactory
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * stdio JSON-RPC dispatcher per `ImpPlan-0.9.6-B.md` §12.1 + §12.4.
 *
 * Wires an [NdjsonMessageProducer] (reads NDJSON from [input]) to an
 * lsp4j `RemoteEndpoint` that dispatches to [service] and writes
 * responses through an [NdjsonMessageConsumer] on [output]. The reader
 * loop runs on a daemon thread; [stop] unblocks the loop.
 *
 * @param closeInputOnStop default `true` — closing the input stream
 *  is the only reliable way to unblock a `read()` on `System.in`. Set
 *  `false` only when the input is a shared stream that the server
 *  must not close on its own (e.g., a parent test that re-uses the
 *  stream after stop).
 */
class StdioJsonRpc(
    input: InputStream,
    output: OutputStream,
    service: McpService,
    private val closeInputOnStop: Boolean = true,
) {
    private val jsonHandler = McpEndpointFactory.jsonHandler()
    private val outbound = NdjsonMessageConsumer(output, jsonHandler)
    private val remote = McpEndpointFactory.remoteEndpoint(service, outbound)
    private val producer = NdjsonMessageProducer(input, jsonHandler, outbound)
    private var thread: Thread? = null

    fun start() {
        check(thread == null) { "StdioJsonRpc already started" }
        thread = Thread({
            try {
                producer.listen(remote)
            } catch (e: IOException) {
                // §12.4: IOException stops the lifecycle (no silent
                // death). Caller's stop() also closes the input, in
                // which case we land here on a closed-stream read —
                // benign.
                LOG.warn("stdio reader stopped on IOException: {}", e.message)
            }
        }, "mcp-stdio-reader").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        if (closeInputOnStop) producer.close() else producer.markClosed()
        thread?.interrupt()
        thread?.join(STOP_JOIN_MILLIS)
        thread = null
    }

    private companion object {
        val LOG = LoggerFactory.getLogger(StdioJsonRpc::class.java)
        const val STOP_JOIN_MILLIS: Long = 1_000L
    }
}
