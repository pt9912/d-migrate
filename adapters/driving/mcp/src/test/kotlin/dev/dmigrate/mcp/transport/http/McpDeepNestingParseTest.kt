package dev.dmigrate.mcp.transport.http

import dev.dmigrate.mcp.protocol.McpServiceImpl
import dev.dmigrate.mcp.server.AuthMode
import dev.dmigrate.mcp.server.McpServerConfig
import dev.dmigrate.mcp.transport.McpEndpointFactory
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode

/**
 * Security-Audit „Methodische Einschränkungen": Verifikation der Gson-Rekursionstiefe
 * im `parseBody`-Pfad. Zwei Ebenen:
 *
 * 1. **Empirisch** — belegt, wie die transitive Gson tief verschachteltes JSON
 *    behandelt (StackOverflowError = Error, den `catch (e: Exception)` NICHT fängt).
 * 2. **End-to-end** — belegt, dass der [JsonNestingGuard] am Transport-Boundary die
 *    Attacke in ein deterministisches `400` ParseError verwandelt, statt einen
 *    uncaught Throwable auf dem Request-Thread zu erzeugen.
 */
class McpDeepNestingParseTest : FunSpec({

    fun deeplyNestedRequest(depth: Int): String =
        """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":""" +
            "[".repeat(depth) + "]".repeat(depth) + "}"

    test("empirical: deeply nested JSON surfaces as a caught-able Exception, not an uncaught Error") {
        val handler = McpEndpointFactory.jsonHandler()
        val thrown: Throwable? = try {
            handler.parseMessage(deeplyNestedRequest(depth = 100_000))
            null
        } catch (t: Throwable) {
            t
        }
        thrown.shouldNotBeNull()
        withClue("underlying throwable was ${thrown!!::class.qualifiedName}: ${thrown.message}") {
            // Refutes the audit's methodical-limitation hypothesis: with the
            // resolved Gson (which enforces a structural nesting limit), deep
            // input raises a MalformedJsonException that lsp4j wraps into a
            // MessageIssueException — an Exception, hence CAUGHT by parseBody's
            // `catch (e: Exception)` → clean 400. No uncaught StackOverflowError
            // occurs with this dependency set. The JsonNestingGuard exists as
            // defense-in-depth so this property does not depend on the transitive
            // Gson keeping its nesting default (lsp4j wraps only JsonParseException,
            // not Error — a Gson downgrade below the limit would let a raw SOE
            // escape the catch).
            (thrown is Exception) shouldBe true
            (thrown is Error) shouldBe false
        }
    }

    test("end-to-end: POST /mcp with over-deep JSON returns a clean 400 ParseError (no crash)") {
        testApplication {
            application {
                installMcpHttpRoute(
                    McpServerConfig(authMode = AuthMode.DISABLED),
                    serviceFactory = { McpServiceImpl(serverVersion = "9.9.9") },
                )
            }
            val resp = client.post("/mcp") {
                headers { append(HttpHeaders.Accept, "application/json, text/event-stream") }
                setBody(deeplyNestedRequest(depth = JsonNestingGuard.MAX_DEPTH + 5_000))
            }
            resp.status shouldBe HttpStatusCode.BadRequest
            val body = resp.bodyAsText()
            body shouldContain "\"code\":${ResponseErrorCode.ParseError.value}"
            body shouldContain "nesting"
        }
    }

    test("end-to-end: a normally nested request is NOT rejected by the guard") {
        testApplication {
            application {
                installMcpHttpRoute(
                    McpServerConfig(authMode = AuthMode.DISABLED),
                    serviceFactory = { McpServiceImpl(serverVersion = "9.9.9") },
                )
            }
            val resp = client.post("/mcp") {
                headers { append(HttpHeaders.Accept, "application/json, text/event-stream") }
                setBody(
                    """{"jsonrpc":"2.0","id":1,"method":"initialize",""" +
                        """"params":{"protocolVersion":"2025-11-25",""" +
                        """"clientInfo":{"name":"t","version":"1"},"capabilities":{}}}""",
                )
            }
            // Reaches the real handler → not the guard's 400/parse-error path.
            resp.status shouldBe HttpStatusCode.OK
        }
    }
})
