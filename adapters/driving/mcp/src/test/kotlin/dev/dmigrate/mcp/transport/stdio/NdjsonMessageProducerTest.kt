package dev.dmigrate.mcp.transport.stdio

import dev.dmigrate.mcp.protocol.McpService
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.eclipse.lsp4j.jsonrpc.MessageConsumer
import org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler
import org.eclipse.lsp4j.jsonrpc.messages.Message
import org.eclipse.lsp4j.jsonrpc.messages.NotificationMessage
import org.eclipse.lsp4j.jsonrpc.messages.RequestMessage
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.eclipse.lsp4j.jsonrpc.messages.ResponseMessage
import org.eclipse.lsp4j.jsonrpc.services.ServiceEndpoints
import java.io.ByteArrayInputStream

private fun jsonHandler(): MessageJsonHandler =
    MessageJsonHandler(ServiceEndpoints.getSupportedMethods(McpService::class.java))

private class CapturingConsumer : MessageConsumer {
    val messages: MutableList<Message> = mutableListOf()
    override fun consume(message: Message) {
        messages += message
    }
}

private fun parseAll(input: String, errorConsumer: MessageConsumer? = null): List<Message> {
    val jh = jsonHandler()
    val producer = NdjsonMessageProducer(
        ByteArrayInputStream(input.toByteArray(Charsets.UTF_8)),
        jh,
        errorConsumer,
    )
    val consumer = CapturingConsumer()
    producer.use { it.listen(consumer) }
    return consumer.messages
}

class NdjsonMessageProducerTest : FunSpec({

    test("roundtrip: serialize -> parse is identity-preserving") {
        val jh = jsonHandler()
        val original = NotificationMessage().apply {
            jsonrpc = "2.0"
            method = "notifications/initialized"
        }
        val serialized = jh.serialize(original)
        val parsed = parseAll(serialized + "\n")
        parsed shouldHaveSize 1
        val n = parsed[0].shouldBeInstanceOf<NotificationMessage>()
        n.method shouldBe "notifications/initialized"
    }

    test("multi-frame in one buffer yields multiple messages") {
        val req = """{"jsonrpc":"2.0","id":1,"method":"initialize",""" +
            """"params":{"protocolVersion":"2025-11-25","clientInfo":{"name":"t","version":"v"}}}"""
        val notif = """{"jsonrpc":"2.0","method":"notifications/initialized"}"""
        val parsed = parseAll("$req\n$notif\n")
        parsed shouldHaveSize 2
        parsed[0].shouldBeInstanceOf<RequestMessage>()
        parsed[1].shouldBeInstanceOf<NotificationMessage>()
    }

    test("blank lines at stream start and between frames are skipped") {
        val notif = """{"jsonrpc":"2.0","method":"notifications/initialized"}"""
        val parsed = parseAll("\n\n   \n$notif\n\n$notif\n")
        parsed shouldHaveSize 2
    }

    test("UTF-8 BOM at stream start is tolerated") {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val notif = """{"jsonrpc":"2.0","method":"notifications/initialized"}""".toByteArray(Charsets.UTF_8)
        val input = bom + notif + byteArrayOf(0x0A)
        val jh = jsonHandler()
        val producer = NdjsonMessageProducer(ByteArrayInputStream(input), jh)
        val consumer = CapturingConsumer()
        producer.use { it.listen(consumer) }
        consumer.messages shouldHaveSize 1
    }

    test("trailing CR before LF (CRLF) is stripped") {
        val notif = """{"jsonrpc":"2.0","method":"notifications/initialized"}"""
        val parsed = parseAll("$notif\r\n")
        parsed shouldHaveSize 1
    }

    test("JSON string with embedded escaped \\n stays in one frame") {
        // Embedded newline must be JSON-escaped (\\n in source = \n in JSON).
        val req = """{"jsonrpc":"2.0","id":1,"method":"initialize",""" +
            """"params":{"protocolVersion":"2025-11-25",""" +
            """"clientInfo":{"name":"line1\nline2","version":"v"}}}"""
        val parsed = parseAll("$req\n")
        parsed shouldHaveSize 1
        // The raw frame must NOT split at the embedded \n.
        val rm = parsed[0].shouldBeInstanceOf<RequestMessage>()
        rm.method shouldBe "initialize"
    }

    test("parse error with id sends -32700 response with echo id") {
        // Malformed body but a recognizable id literal at the start.
        val malformed = """{"jsonrpc":"2.0","id":42,"method":"initialize","params":{"""
        val captured = CapturingConsumer()
        val parsed = parseAll(malformed + "\n", errorConsumer = captured)
        parsed shouldHaveSize 0
        captured.messages shouldHaveSize 1
        val resp = captured.messages[0].shouldBeInstanceOf<ResponseMessage>()
        // id was echoed (numeric 42 → string "42" in our impl)
        resp.id shouldBe "42"
        resp.error.code shouldBe ResponseErrorCode.ParseError.value
        resp.error.message shouldContain "Parse error"
    }

    test("parse error without id is dropped, no response written") {
        val malformed = """{"this":"is malformed"""
        val captured = CapturingConsumer()
        val parsed = parseAll(malformed + "\n", errorConsumer = captured)
        parsed shouldHaveSize 0
        captured.messages shouldHaveSize 0
    }

    test("close stops the listen loop on the next read") {
        val producer = NdjsonMessageProducer(
            ByteArrayInputStream(ByteArray(0)),
            jsonHandler(),
        )
        producer.close()
        // listen returns immediately because input is at EOF and closed.
        val consumer = CapturingConsumer()
        producer.listen(consumer)
        consumer.messages shouldHaveSize 0
    }

    test("string id in malformed body is echoed back") {
        val malformed = """{"jsonrpc":"2.0","id":"req-abc","method":"initialize","params":{"""
        val captured = CapturingConsumer()
        parseAll(malformed + "\n", errorConsumer = captured)
        val resp = captured.messages[0].shouldBeInstanceOf<ResponseMessage>()
        resp.id shouldBe "req-abc"
        resp.error.message shouldNotContain "without id"
    }

    test("malformed body with nested id-like keys echoes the LAST id (top-level)") {
        // Heuristic: nested objects (client_id, params with `id` fields,
        // resource ids in arrays) precede the top-level id by JSON
        // object key order convention. Choosing the LAST match avoids
        // accidentally echoing an inner id.
        val malformed = """{"jsonrpc":"2.0",""" +
            """"params":{"client_id":"inner","resource":{"id":"deep"}},""" +
            """"id":99,"method":"initialize",{"""
        val captured = CapturingConsumer()
        parseAll(malformed + "\n", errorConsumer = captured)
        val resp = captured.messages[0].shouldBeInstanceOf<ResponseMessage>()
        resp.id shouldBe "99"
    }
})
