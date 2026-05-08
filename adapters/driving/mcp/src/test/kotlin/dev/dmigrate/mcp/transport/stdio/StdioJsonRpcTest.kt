package dev.dmigrate.mcp.transport.stdio

import dev.dmigrate.mcp.protocol.McpProtocol
import dev.dmigrate.mcp.protocol.McpServiceImpl
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.charset.StandardCharsets

/**
 * §6.4 Akzeptanz #1: ein Client kann via stdio initialisieren. Wir
 * fuettern einen NDJSON-Initialize-Request rein und erwarten einen
 * NDJSON-Response mit `protocolVersion=2025-11-25` und der
 * Server-Metadaten.
 */
class StdioJsonRpcTest : FunSpec({

    test("initialize roundtrip over stdio NDJSON") {
        val req = """{"jsonrpc":"2.0","id":1,"method":"initialize",""" +
            """"params":{"protocolVersion":"2025-11-25",""" +
            """"clientInfo":{"name":"t","version":"1"},"capabilities":{}}}"""
        val input = ByteArrayInputStream("$req\n".toByteArray(StandardCharsets.UTF_8))
        val output = ByteArrayOutputStream()
        val rpc = StdioJsonRpc(input, output, McpServiceImpl(serverVersion = "9.9.9"))
        rpc.start()
        // Wait until the reader processes the request and writes a
        // response (busy-wait with timeout — keeps the test simple).
        val deadline = System.currentTimeMillis() + 5_000
        while (output.size() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        rpc.stop()
        val responseLine = output.toString(StandardCharsets.UTF_8).trim()
        responseLine shouldContain "\"protocolVersion\":\"${McpProtocol.MCP_PROTOCOL_VERSION}\""
        responseLine shouldContain "\"name\":\"${McpProtocol.SERVER_NAME}\""
        responseLine shouldContain "\"version\":\"9.9.9\""
        responseLine shouldContain "\"id\":1"
    }

    test("wrong protocolVersion produces JSON-RPC InvalidParams over stdio") {
        val req = """{"jsonrpc":"2.0","id":7,"method":"initialize",""" +
            """"params":{"protocolVersion":"1999-01-01",""" +
            """"clientInfo":{"name":"t","version":"1"}}}"""
        val input = ByteArrayInputStream("$req\n".toByteArray(StandardCharsets.UTF_8))
        val output = ByteArrayOutputStream()
        val rpc = StdioJsonRpc(input, output, McpServiceImpl(serverVersion = "0.1.0"))
        rpc.start()
        val deadline = System.currentTimeMillis() + 5_000
        while (output.size() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        rpc.stop()
        val responseLine = output.toString(StandardCharsets.UTF_8).trim()
        responseLine shouldContain "\"id\":7"
        responseLine shouldContain "\"error\""
        responseLine shouldContain "\"code\":${ResponseErrorCode.InvalidParams.value}"
    }

    test("stop unblocks the read loop on a piped input") {
        val pin = PipedInputStream()
        val pout = PipedOutputStream(pin)
        val out = ByteArrayOutputStream()
        val rpc = StdioJsonRpc(pin, out, McpServiceImpl(serverVersion = "0.1.0"))
        rpc.start()
        Thread.sleep(50)
        rpc.stop()
        // No messages were sent, so output stays empty.
        out.size() shouldBe 0
        // pout is unaffected — close from the reader side closes pin.
        pout.close()
    }

    test("awaitTermination returns when stdin reaches EOF (§12.4 Fix-iii)") {
        // §12.4: stdio reader terminates on EOF. The CLI driver
        // calls handle.awaitTermination() and expects to wake up
        // without an external SIGINT — otherwise a closed pipe
        // hangs the process forever.
        val input = ByteArrayInputStream(ByteArray(0)) // immediate EOF
        val output = ByteArrayOutputStream()
        val rpc = StdioJsonRpc(input, output, McpServiceImpl(serverVersion = "0.1.0"))
        rpc.start()
        // Run awaitTermination on a separate thread with a deadline
        // so a regression doesn't hang the test runner.
        val done = java.util.concurrent.CountDownLatch(1)
        Thread { rpc.awaitTermination(); done.countDown() }.apply { isDaemon = true; start() }
        val woke = done.await(5, java.util.concurrent.TimeUnit.SECONDS)
        woke shouldBe true
        rpc.stop()
    }

    test("awaitTermination returns after stop() even with no input activity") {
        val pin = PipedInputStream()
        val pout = PipedOutputStream(pin)
        val out = ByteArrayOutputStream()
        val rpc = StdioJsonRpc(pin, out, McpServiceImpl(serverVersion = "0.1.0"))
        rpc.start()
        // Trigger termination via stop() — awaitTermination should
        // return regardless of whether the reader thread saw any data.
        val done = java.util.concurrent.CountDownLatch(1)
        Thread { rpc.awaitTermination(); done.countDown() }.apply { isDaemon = true; start() }
        Thread.sleep(50)
        rpc.stop()
        val woke = done.await(5, java.util.concurrent.TimeUnit.SECONDS)
        woke shouldBe true
        pout.close()
    }
})
