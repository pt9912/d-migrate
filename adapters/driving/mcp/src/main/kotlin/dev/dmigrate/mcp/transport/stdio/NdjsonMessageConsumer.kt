package dev.dmigrate.mcp.transport.stdio

import org.eclipse.lsp4j.jsonrpc.MessageConsumer
import org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler
import org.eclipse.lsp4j.jsonrpc.messages.Message
import java.io.OutputStream

/**
 * NDJSON `MessageConsumer` per `ImpPlan-0.9.6-B.md` §12.4.
 *
 * Serializes outbound JSON-RPC messages with [jsonHandler] (Gson),
 * appends exactly one `\n` (LF) and writes the bytes to [output] as
 * UTF-8. Writes are `synchronized` on a private monitor so concurrent
 * server-to-client responses do not interleave their frames. No BOM
 * is emitted — embedded `\n` inside JSON strings is already escaped
 * as `\\n` by the serializer.
 */
class NdjsonMessageConsumer(
    private val output: OutputStream,
    private val jsonHandler: MessageJsonHandler,
) : MessageConsumer {

    private val writeLock = Any()

    override fun consume(message: Message) {
        val json = jsonHandler.serialize(message)
        val bytes = json.toByteArray(Charsets.UTF_8)
        synchronized(writeLock) {
            output.write(bytes)
            output.write(LF)
            output.flush()
        }
    }

    private companion object {
        const val LF: Int = 0x0A
    }
}
