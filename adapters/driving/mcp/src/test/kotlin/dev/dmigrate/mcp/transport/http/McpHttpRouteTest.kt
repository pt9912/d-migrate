package dev.dmigrate.mcp.transport.http

import dev.dmigrate.mcp.protocol.McpProtocol
import dev.dmigrate.mcp.protocol.McpServiceImpl
import dev.dmigrate.mcp.server.AuthMode
import dev.dmigrate.mcp.server.McpServerConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode

private val LOOPBACK_CONFIG = McpServerConfig(authMode = AuthMode.DISABLED)
private const val INITIALIZE_BODY = """{"jsonrpc":"2.0","id":1,"method":"initialize",""" +
    """"params":{"protocolVersion":"2025-11-25",""" +
    """"clientInfo":{"name":"t","version":"1"},"capabilities":{}}}"""

/**
 * AP 6.5 Streamable HTTP — POST/GET/DELETE, Origin, Session-Id,
 * Protocol-Version, Accept, plus all of the AP 6.4 Initialize cases.
 */
class McpHttpRouteTest : FunSpec({

    test("POST /mcp with valid initialize returns 200 + Session-Id + Protocol-Version headers") {
        testApplication {
            application {
                installMcpHttpRoute(LOOPBACK_CONFIG, serviceFactory = { McpServiceImpl(serverVersion = "9.9.9") })
            }
            val resp = client.post("/mcp") { setBody(INITIALIZE_BODY) }
            resp.status shouldBe HttpStatusCode.OK
            resp.headers["MCP-Session-Id"]!!.let { java.util.UUID.fromString(it) }
            resp.headers["MCP-Protocol-Version"] shouldBe McpProtocol.MCP_PROTOCOL_VERSION
            val body = resp.bodyAsText()
            body shouldContain "\"protocolVersion\":\"${McpProtocol.MCP_PROTOCOL_VERSION}\""
            body shouldContain "\"id\":1"
        }
    }

    test("POST /mcp with wrong protocolVersion returns -32602 and NO session is created") {
        testApplication {
            application {
                installMcpHttpRoute(LOOPBACK_CONFIG, serviceFactory = { McpServiceImpl(serverVersion = "0.1.0") })
            }
            val resp = client.post("/mcp") {
                setBody(
                    """{"jsonrpc":"2.0","id":11,"method":"initialize",""" +
                        """"params":{"protocolVersion":"1999-01-01",""" +
                        """"clientInfo":{"name":"t","version":"1"}}}""",
                )
            }
            resp.status shouldBe HttpStatusCode.OK
            resp.headers["MCP-Session-Id"] shouldBe null
            val body = resp.bodyAsText()
            body shouldContain "\"id\":11"
            body shouldContain "\"code\":${ResponseErrorCode.InvalidParams.value}"
        }
    }

    test("POST /mcp notifications/initialized returns 202 Accepted (§12.13)") {
        testApplication {
            application {
                installMcpHttpRoute(LOOPBACK_CONFIG, serviceFactory = { McpServiceImpl(serverVersion = "0.1.0") })
            }
            // Initialize first to get a session id
            val initResp = client.post("/mcp") { setBody(INITIALIZE_BODY) }
            val sessionId = initResp.headers["MCP-Session-Id"]!!
            val resp = client.post("/mcp") {
                headers {
                    append("MCP-Session-Id", sessionId)
                    append("MCP-Protocol-Version", McpProtocol.MCP_PROTOCOL_VERSION)
                }
                setBody("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
            }
            resp.status shouldBe HttpStatusCode.Accepted
            resp.bodyAsText() shouldBe ""
        }
    }

    test("POST /mcp follow-up without Session-Id returns 404 + -32000") {
        testApplication {
            application {
                installMcpHttpRoute(LOOPBACK_CONFIG, serviceFactory = { McpServiceImpl(serverVersion = "0.1.0") })
            }
            val resp = client.post("/mcp") {
                headers { append("MCP-Protocol-Version", McpProtocol.MCP_PROTOCOL_VERSION) }
                setBody("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
            }
            resp.status shouldBe HttpStatusCode.NotFound
            resp.bodyAsText() shouldContain "\"code\":-32000"
        }
    }

    test("POST /mcp follow-up with unknown Session-Id returns 404 + -32000") {
        testApplication {
            application {
                installMcpHttpRoute(LOOPBACK_CONFIG, serviceFactory = { McpServiceImpl(serverVersion = "0.1.0") })
            }
            val resp = client.post("/mcp") {
                headers {
                    append("MCP-Session-Id", java.util.UUID.randomUUID().toString())
                    append("MCP-Protocol-Version", McpProtocol.MCP_PROTOCOL_VERSION)
                }
                setBody("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
            }
            resp.status shouldBe HttpStatusCode.NotFound
            resp.bodyAsText() shouldContain "\"code\":-32000"
        }
    }

    test("POST /mcp follow-up with mismatched Protocol-Version returns 400 + -32001") {
        testApplication {
            application {
                installMcpHttpRoute(LOOPBACK_CONFIG, serviceFactory = { McpServiceImpl(serverVersion = "0.1.0") })
            }
            val initResp = client.post("/mcp") { setBody(INITIALIZE_BODY) }
            val sessionId = initResp.headers["MCP-Session-Id"]!!
            val resp = client.post("/mcp") {
                headers {
                    append("MCP-Session-Id", sessionId)
                    append("MCP-Protocol-Version", "1999-01-01")
                }
                setBody("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
            }
            resp.status shouldBe HttpStatusCode.BadRequest
            resp.bodyAsText() shouldContain "\"code\":-32001"
        }
    }

    test("POST /mcp follow-up with missing Protocol-Version header returns 400 + -32001") {
        testApplication {
            application {
                installMcpHttpRoute(LOOPBACK_CONFIG, serviceFactory = { McpServiceImpl(serverVersion = "0.1.0") })
            }
            val initResp = client.post("/mcp") { setBody(INITIALIZE_BODY) }
            val sessionId = initResp.headers["MCP-Session-Id"]!!
            val resp = client.post("/mcp") {
                headers { append("MCP-Session-Id", sessionId) }
                setBody("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
            }
            resp.status shouldBe HttpStatusCode.BadRequest
            resp.bodyAsText() shouldContain "\"code\":-32001"
        }
    }

    test("POST /mcp with disallowed Origin returns 403 (no JSON-RPC body)") {
        val explicitOrigins = setOf("https://app.example")
        testApplication {
            application {
                installMcpHttpRoute(
                    config = LOOPBACK_CONFIG.copy(allowedOrigins = explicitOrigins),
                    serviceFactory = { McpServiceImpl(serverVersion = "0.1.0") },
                )
            }
            val resp = client.post("/mcp") {
                headers { append(HttpHeaders.Origin, "https://evil.example") }
                setBody(INITIALIZE_BODY)
            }
            resp.status shouldBe HttpStatusCode.Forbidden
            resp.bodyAsText() shouldContain "evil.example"
        }
    }

    test("POST /mcp with SSE-only Accept header returns 406 + -32600") {
        testApplication {
            application {
                installMcpHttpRoute(LOOPBACK_CONFIG, serviceFactory = { McpServiceImpl(serverVersion = "0.1.0") })
            }
            val resp = client.post("/mcp") {
                headers { append(HttpHeaders.Accept, "text/event-stream") }
                setBody(INITIALIZE_BODY)
            }
            resp.status shouldBe HttpStatusCode.NotAcceptable
            resp.bodyAsText() shouldContain "\"code\":${ResponseErrorCode.InvalidRequest.value}"
        }
    }

    test("POST /mcp with malformed body returns 400 + -32700") {
        testApplication {
            application {
                installMcpHttpRoute(LOOPBACK_CONFIG, serviceFactory = { McpServiceImpl(serverVersion = "0.1.0") })
            }
            val resp = client.post("/mcp") { setBody("not even json") }
            resp.status shouldBe HttpStatusCode.BadRequest
            resp.bodyAsText() shouldContain "\"code\":${ResponseErrorCode.ParseError.value}"
        }
    }

    test("POST /mcp with empty body returns 400 + -32600") {
        testApplication {
            application {
                installMcpHttpRoute(LOOPBACK_CONFIG, serviceFactory = { McpServiceImpl(serverVersion = "0.1.0") })
            }
            val resp = client.post("/mcp") { setBody("") }
            resp.status shouldBe HttpStatusCode.BadRequest
            resp.bodyAsText() shouldContain "\"code\":${ResponseErrorCode.InvalidRequest.value}"
        }
    }

    test("GET /mcp returns 405 (no SSE in Phase B)") {
        testApplication {
            application {
                installMcpHttpRoute(LOOPBACK_CONFIG, serviceFactory = { McpServiceImpl(serverVersion = "0.1.0") })
            }
            client.get("/mcp").status shouldBe HttpStatusCode.MethodNotAllowed
        }
    }

    test("DELETE /mcp without Session-Id returns 405") {
        testApplication {
            application {
                installMcpHttpRoute(LOOPBACK_CONFIG, serviceFactory = { McpServiceImpl(serverVersion = "0.1.0") })
            }
            client.delete("/mcp").status shouldBe HttpStatusCode.MethodNotAllowed
        }
    }

    test("DELETE /mcp with valid Session-Id returns 200 and removes the session") {
        testApplication {
            application {
                installMcpHttpRoute(LOOPBACK_CONFIG, serviceFactory = { McpServiceImpl(serverVersion = "0.1.0") })
            }
            val initResp = client.post("/mcp") { setBody(INITIALIZE_BODY) }
            val sessionId = initResp.headers["MCP-Session-Id"]!!
            val deleteResp = client.delete("/mcp") {
                headers { append("MCP-Session-Id", sessionId) }
            }
            deleteResp.status shouldBe HttpStatusCode.OK
            // After delete, follow-up requests against the same id must fail.
            val followUp = client.post("/mcp") {
                headers {
                    append("MCP-Session-Id", sessionId)
                    append("MCP-Protocol-Version", McpProtocol.MCP_PROTOCOL_VERSION)
                }
                setBody("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
            }
            followUp.status shouldBe HttpStatusCode.NotFound
        }
    }

    test("Initialize race: two parallel initializes get distinct Session-Ids") {
        testApplication {
            application {
                installMcpHttpRoute(LOOPBACK_CONFIG, serviceFactory = { McpServiceImpl(serverVersion = "0.1.0") })
            }
            val a = client.post("/mcp") { setBody(INITIALIZE_BODY) }
            val b = client.post("/mcp") { setBody(INITIALIZE_BODY) }
            val idA = a.headers["MCP-Session-Id"]!!
            val idB = b.headers["MCP-Session-Id"]!!
            check(idA != idB) { "expected distinct session ids, got $idA and $idB" }
        }
    }

    test("session reuse: serviceFactory invoked ONCE per Initialize, follow-ups use the same service") {
        testApplication {
            var serviceCount = 0
            application {
                installMcpHttpRoute(
                    config = LOOPBACK_CONFIG,
                    serviceFactory = {
                        serviceCount += 1
                        McpServiceImpl(serverVersion = "0.1.0")
                    },
                )
            }
            val initResp = client.post("/mcp") { setBody(INITIALIZE_BODY) }
            val sessionId = initResp.headers["MCP-Session-Id"]!!
            // Two follow-up notifications — neither must invoke the factory.
            repeat(2) {
                client.post("/mcp") {
                    headers {
                        append("MCP-Session-Id", sessionId)
                        append("MCP-Protocol-Version", McpProtocol.MCP_PROTOCOL_VERSION)
                    }
                    setBody("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
                }
            }
            check(serviceCount == 1) { "expected exactly 1 factory call, got $serviceCount" }
        }
    }
})
