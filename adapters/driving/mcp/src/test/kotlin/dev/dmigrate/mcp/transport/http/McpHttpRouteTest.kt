package dev.dmigrate.mcp.transport.http

import dev.dmigrate.mcp.protocol.McpProtocol
import dev.dmigrate.mcp.protocol.McpServiceImpl
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode

/**
 * §6.4 Akzeptanz #2: ein Client kann via HTTP initialisieren. Postet
 * einen JSON-RPC-`initialize`-Request an `/mcp` und erwartet eine
 * JSON-RPC-Response mit `protocolVersion=2025-11-25`.
 */
class McpHttpRouteTest : FunSpec({

    test("POST /mcp with valid initialize returns 2025-11-25 response") {
        testApplication {
            application {
                installMcpHttpRoute(serviceFactory = { McpServiceImpl(serverVersion = "9.9.9") })
            }
            val resp = client.post("/mcp") {
                headers {
                    append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                }
                setBody(
                    """{"jsonrpc":"2.0","id":1,"method":"initialize",""" +
                        """"params":{"protocolVersion":"2025-11-25",""" +
                        """"clientInfo":{"name":"t","version":"1"},"capabilities":{}}}""",
                )
            }
            resp.status shouldBe HttpStatusCode.OK
            val body = resp.bodyAsText()
            body shouldContain "\"protocolVersion\":\"${McpProtocol.MCP_PROTOCOL_VERSION}\""
            body shouldContain "\"name\":\"${McpProtocol.SERVER_NAME}\""
            body shouldContain "\"version\":\"9.9.9\""
            body shouldContain "\"id\":1"
        }
    }

    test("POST /mcp with wrong protocolVersion returns JSON-RPC error -32602") {
        testApplication {
            application {
                installMcpHttpRoute(serviceFactory = { McpServiceImpl(serverVersion = "0.1.0") })
            }
            val resp = client.post("/mcp") {
                headers {
                    append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                }
                setBody(
                    """{"jsonrpc":"2.0","id":11,"method":"initialize",""" +
                        """"params":{"protocolVersion":"1999-01-01",""" +
                        """"clientInfo":{"name":"t","version":"1"}}}""",
                )
            }
            resp.status shouldBe HttpStatusCode.OK
            val body = resp.bodyAsText()
            body shouldContain "\"id\":11"
            body shouldContain "\"code\":${ResponseErrorCode.InvalidParams.value}"
        }
    }

    test("POST /mcp with notifications/initialized returns 204 No Content") {
        testApplication {
            application {
                installMcpHttpRoute(serviceFactory = { McpServiceImpl(serverVersion = "0.1.0") })
            }
            val resp = client.post("/mcp") {
                headers {
                    append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                }
                setBody("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
            }
            resp.status shouldBe HttpStatusCode.NoContent
        }
    }

    test("POST /mcp with malformed body returns 400 Bad Request") {
        testApplication {
            application {
                installMcpHttpRoute(serviceFactory = { McpServiceImpl(serverVersion = "0.1.0") })
            }
            val resp = client.post("/mcp") {
                setBody("not even json")
            }
            resp.status shouldBe HttpStatusCode.BadRequest
        }
    }

    test("each request gets a fresh service — no negotiated state leaks across calls") {
        testApplication {
            var serviceCount = 0
            application {
                installMcpHttpRoute(serviceFactory = {
                    serviceCount += 1
                    McpServiceImpl(serverVersion = "0.1.0")
                })
            }
            val initBody = """{"jsonrpc":"2.0","id":1,"method":"initialize",""" +
                """"params":{"protocolVersion":"2025-11-25",""" +
                """"clientInfo":{"name":"t","version":"1"}}}"""
            client.post("/mcp") { setBody(initBody) }
            client.post("/mcp") { setBody(initBody) }
            check(serviceCount >= 2) { "factory should be invoked per request, was=$serviceCount" }
        }
    }
})
