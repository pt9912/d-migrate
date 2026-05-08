package dev.dmigrate.mcp.transport.stdio

import dev.dmigrate.mcp.protocol.McpService
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler
import org.eclipse.lsp4j.jsonrpc.messages.NotificationMessage
import org.eclipse.lsp4j.jsonrpc.services.ServiceEndpoints
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private fun jsonHandler(): MessageJsonHandler =
    MessageJsonHandler(ServiceEndpoints.getSupportedMethods(McpService::class.java))

class NdjsonMessageConsumerTest : FunSpec({

    test("consume writes the JSON followed by exactly one LF") {
        val out = ByteArrayOutputStream()
        val sut = NdjsonMessageConsumer(out, jsonHandler())
        sut.consume(NotificationMessage().apply {
            jsonrpc = "2.0"
            method = "notifications/initialized"
        })
        val bytes = out.toByteArray()
        bytes.last() shouldBe 0x0A
        // Only one LF — the framing trailer.
        bytes.count { it == 0x0A.toByte() } shouldBe 1
    }

    test("output has no UTF-8 BOM") {
        val out = ByteArrayOutputStream()
        val sut = NdjsonMessageConsumer(out, jsonHandler())
        sut.consume(NotificationMessage().apply {
            jsonrpc = "2.0"
            method = "notifications/initialized"
        })
        val bytes = out.toByteArray()
        // First three bytes must not be the UTF-8 BOM (0xEF 0xBB 0xBF).
        listOf(bytes[0], bytes[1], bytes[2]).shouldContainExactly(
            *bytes.take(3).toTypedArray(),
        )
        check(bytes[0] != 0xEF.toByte() || bytes[1] != 0xBB.toByte() || bytes[2] != 0xBF.toByte())
    }

    test("embedded newline in JSON string stays JSON-escaped") {
        val out = ByteArrayOutputStream()
        val sut = NdjsonMessageConsumer(out, jsonHandler())
        // Use the params field which Gson serializes as JSON. The
        // simplest way: a NotificationMessage with method whose name
        // doesn't contain a newline (it shouldn't), but also write a
        // request whose params include a string with an embedded LF
        // — Gson must escape it as \\n.
        val notif = NotificationMessage().apply {
            jsonrpc = "2.0"
            method = "notifications/initialized"
            params = mapOf("note" to "line1\nline2")
        }
        sut.consume(notif)
        val text = out.toString(Charsets.UTF_8)
        text shouldContain "line1\\nline2"
        text shouldNotContain "line1\nline2" // raw LF must not leak into the wire
        // Plus exactly one trailing frame separator at the very end.
        text.last() shouldBe '\n'
    }

    test("concurrent writes are serialized — no interleaving") {
        val out = ByteArrayOutputStream()
        val sut = NdjsonMessageConsumer(out, jsonHandler())
        val pool = Executors.newFixedThreadPool(8)
        try {
            val latch = CountDownLatch(1)
            val count = 100
            repeat(count) { i ->
                pool.submit {
                    latch.await()
                    sut.consume(NotificationMessage().apply {
                        jsonrpc = "2.0"
                        method = "notifications/initialized"
                        params = mapOf("seq" to i)
                    })
                }
            }
            latch.countDown()
            pool.shutdown()
            check(pool.awaitTermination(10, TimeUnit.SECONDS)) { "writers did not finish in time" }
        } finally {
            pool.shutdownNow()
        }
        // Every line must be a full, parseable JSON object — no
        // truncation, no interleaving.
        val lines = out.toString(Charsets.UTF_8).split('\n').filter { it.isNotEmpty() }
        val jh = jsonHandler()
        for (line in lines) {
            jh.parseMessage(line)
        }
        lines.size shouldBe 100
    }
})
