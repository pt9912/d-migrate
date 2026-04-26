package dev.dmigrate.mcp.transport.stdio

import org.eclipse.lsp4j.jsonrpc.MessageConsumer
import org.eclipse.lsp4j.jsonrpc.MessageProducer
import org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.eclipse.lsp4j.jsonrpc.messages.ResponseMessage
import org.slf4j.LoggerFactory
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * NDJSON `MessageProducer` per `ImpPlan-0.9.6-B.md` §12.4.
 *
 * Reads UTF-8 bytes from [input] until each `\n` (LF, 0x0A), parses
 * the line as a JSON-RPC message via [jsonHandler], and dispatches it
 * to the listening [MessageConsumer]. The first call tolerates a
 * UTF-8 BOM; blank lines are silently skipped; trailing `\r` (CRLF)
 * is stripped.
 *
 * Parse errors that include a recognizable `id` are answered with a
 * JSON-RPC `-32700` (parse error) response routed through
 * [errorResponseConsumer]; errors without an `id` are dropped and
 * logged. The reader itself does not stop on a single bad line.
 */
class NdjsonMessageProducer(
    input: InputStream,
    private val jsonHandler: MessageJsonHandler,
    private val errorResponseConsumer: MessageConsumer? = null,
) : MessageProducer, AutoCloseable {

    // Buffer the input once — `input.read()` reads byte-by-byte and
    // would otherwise issue one syscall per byte on unbuffered streams
    // like System.in. BufferedInputStream is idempotent if the caller
    // already wrapped, so this is safe.
    private val input: InputStream = if (input is BufferedInputStream) input else BufferedInputStream(input)

    @Volatile private var closed: Boolean = false

    override fun listen(messageConsumer: MessageConsumer) {
        var stripBom = true
        val frame = ByteArrayOutputStream()
        while (!closed) {
            val byte = try {
                input.read()
            } catch (e: IOException) {
                if (closed) return
                throw e
            }
            if (byte == -1) {
                if (frame.size() > 0) handleFrame(frame.toByteArray(), stripBom, messageConsumer)
                return
            }
            if (byte == LF) {
                handleFrame(frame.toByteArray(), stripBom, messageConsumer)
                stripBom = false
                frame.reset()
            } else {
                frame.write(byte)
            }
        }
    }

    private fun handleFrame(
        rawBytes: ByteArray,
        stripBom: Boolean,
        messageConsumer: MessageConsumer,
    ) {
        var bytes = rawBytes
        if (bytes.isNotEmpty() && bytes.last() == CR) bytes = bytes.copyOfRange(0, bytes.size - 1)
        if (stripBom && bytes.size >= BOM.size && bytes.sliceArray(BOM.indices).contentEquals(BOM)) {
            bytes = bytes.copyOfRange(BOM.size, bytes.size)
        }
        val line = String(bytes, Charsets.UTF_8).trim()
        if (line.isEmpty()) return
        try {
            val message = jsonHandler.parseMessage(line)
            messageConsumer.consume(message)
        } catch (e: Exception) {
            handleParseError(line, e)
        }
    }

    private fun handleParseError(line: String, cause: Exception) {
        val outbound = errorResponseConsumer
        val idStr = tryExtractIdAsString(line)
        if (outbound == null || idStr == null) {
            LOG.warn("Discarded malformed JSON-RPC frame ({} bytes): {}", line.length, cause.message)
            return
        }
        val response = ResponseMessage().apply {
            jsonrpc = JSONRPC_VERSION
            id = idStr
            error = ResponseError(
                ResponseErrorCode.ParseError,
                "Parse error: ${cause.message ?: cause.javaClass.simpleName}",
                null,
            )
        }
        try {
            outbound.consume(response)
        } catch (e: Exception) {
            LOG.warn("Failed to send -32700 parse-error response (id={}): {}", idStr, e.message)
        }
    }

    override fun close() {
        markClosed()
        try {
            input.close()
        } catch (_: IOException) {
            // best-effort
        }
    }

    /**
     * Sets the closed flag without closing the underlying input. The
     * read loop notices on the next byte boundary; useful when the
     * caller owns the input stream and must not close it (see
     * [StdioJsonRpc.closeInputOnStop]).
     */
    fun markClosed() {
        closed = true
    }

    /**
     * Returns the raw JSON-RPC `id` from [line] when the rest of the
     * document is too malformed to fully parse. Heuristic: take the
     * LAST `"id":` occurrence — top-level ids appear after nested
     * `"client_id"`/`"id"` fields in object initialization order, so
     * preferring the last match is the safest single-pass guess
     * without a real JSON parser. Numeric ids are stringified —
     * lsp4j-based clients accept either form in the `-32700` response.
     */
    private fun tryExtractIdAsString(line: String): String? {
        val match = ID_PATTERN.findAll(line).lastOrNull() ?: return null
        val stringId = match.groupValues[1].takeIf { it.isNotEmpty() }
        if (stringId != null) return stringId
        return match.groupValues[2].takeIf { it.isNotEmpty() }
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(NdjsonMessageProducer::class.java)
        private const val LF: Int = 0x0A
        private const val CR: Byte = 0x0D
        private const val JSONRPC_VERSION: String = "2.0"
        private val BOM: ByteArray = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        // `"id"` followed by `:` then either a quoted string (group 1) or a numeric literal (group 2).
        private val ID_PATTERN = Regex(
            """"id"\s*:\s*(?:"((?:[^"\\]|\\.)*)"|(-?\d+(?:\.\d+)?))""",
        )
    }
}
