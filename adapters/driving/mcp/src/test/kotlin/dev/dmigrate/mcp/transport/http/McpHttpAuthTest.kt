package dev.dmigrate.mcp.transport.http

import dev.dmigrate.mcp.auth.AuthValidator
import dev.dmigrate.mcp.auth.BearerValidationResult
import dev.dmigrate.mcp.protocol.McpProtocol
import dev.dmigrate.mcp.protocol.McpServiceImpl
import dev.dmigrate.mcp.server.AuthMode
import dev.dmigrate.mcp.server.McpServerConfig
import dev.dmigrate.server.core.principal.AuthSource
import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.net.URI
import java.time.Instant

private const val INITIALIZE_BODY = """{"jsonrpc":"2.0","id":1,"method":"initialize",""" +
    """"params":{"protocolVersion":"2025-11-25",""" +
    """"clientInfo":{"name":"t","version":"1"},"capabilities":{}}}"""

private val JWKS_CONFIG = McpServerConfig(
    authMode = AuthMode.JWT_JWKS,
    issuer = URI.create("https://issuer.example/"),
    jwksUrl = URI.create("https://issuer.example/jwks"),
    audience = "mcp.dmigrate",
)

private fun principalWithScopes(vararg scopes: String): PrincipalContext = PrincipalContext(
    principalId = PrincipalId("test-user"),
    homeTenantId = TenantId("tenant-A"),
    effectiveTenantId = TenantId("tenant-A"),
    allowedTenantIds = setOf(TenantId("tenant-A")),
    scopes = scopes.toSet(),
    isAdmin = "dmigrate:admin" in scopes,
    auditSubject = "test-user",
    authSource = AuthSource.OIDC,
    expiresAt = Instant.parse("2099-01-01T00:00:00Z"),
)

private class FakeAuthValidator(private val result: BearerValidationResult) : AuthValidator {
    var lastToken: String? = null
    override suspend fun validate(token: String): BearerValidationResult {
        lastToken = token
        return result
    }
}

/**
 * AP 6.6 Bearer / Scope / Metadata acceptance tests. The JWT
 * cryptography itself lives in `JwksAuthValidator`; here we inject
 * fake [AuthValidator]s so the tests stay deterministic and fast.
 */
class McpHttpAuthTest : FunSpec({

    test("401 with WWW-Authenticate when Authorization header is absent") {
        testApplication {
            application {
                installMcpHttpRoute(
                    config = JWKS_CONFIG,
                    serviceFactory = { McpServiceImpl(serverVersion = "0.1.0") },
                    authValidatorOverride = FakeAuthValidator(BearerValidationResult.Invalid("unused")),
                )
            }
            val resp = client.post("/mcp") { setBody(INITIALIZE_BODY) }
            resp.status shouldBe HttpStatusCode.Unauthorized
            val challenge = resp.headers[HttpHeaders.WWWAuthenticate]!!
            challenge shouldStartWith "Bearer realm=\"dmigrate-mcp\""
            challenge shouldContain "resource_metadata=\""
        }
    }

    test("401 with invalid_token when access_token is supplied as a query parameter (§12.14)") {
        // §12.14: tokens MUST come from `Authorization: Bearer …`.
        // Query parameters are explicitly rejected — even if the
        // Authorization header is missing, the query-parameter path
        // must NOT silently fall back. The validator is never asked.
        testApplication {
            application {
                installMcpHttpRoute(
                    config = JWKS_CONFIG,
                    serviceFactory = { McpServiceImpl(serverVersion = "0.1.0") },
                    authValidatorOverride = FakeAuthValidator(
                        BearerValidationResult.Valid(principalWithScopes("dmigrate:read")),
                    ),
                )
            }
            val resp = client.post("/mcp?access_token=some-bearer") {
                setBody(INITIALIZE_BODY)
            }
            resp.status shouldBe HttpStatusCode.Unauthorized
            val challenge = resp.headers[HttpHeaders.WWWAuthenticate]!!
            challenge shouldContain "error=\"invalid_token\""
            challenge shouldContain "query parameters"
        }
    }

    test("401 with invalid_token when validator rejects") {
        testApplication {
            application {
                installMcpHttpRoute(
                    config = JWKS_CONFIG,
                    serviceFactory = { McpServiceImpl(serverVersion = "0.1.0") },
                    authValidatorOverride = FakeAuthValidator(
                        BearerValidationResult.Invalid("token expired"),
                    ),
                )
            }
            val resp = client.post("/mcp") {
                headers { append(HttpHeaders.Authorization, "Bearer some-token") }
                setBody(INITIALIZE_BODY)
            }
            resp.status shouldBe HttpStatusCode.Unauthorized
            val challenge = resp.headers[HttpHeaders.WWWAuthenticate]!!
            challenge shouldContain "error=\"invalid_token\""
            challenge shouldContain "token expired"
        }
    }

    test("Initialize with valid token + scope-free method succeeds") {
        val validator = FakeAuthValidator(
            BearerValidationResult.Valid(principalWithScopes("dmigrate:read")),
        )
        testApplication {
            application {
                installMcpHttpRoute(
                    config = JWKS_CONFIG,
                    serviceFactory = { McpServiceImpl(serverVersion = "9.9.9") },
                    authValidatorOverride = validator,
                )
            }
            val resp = client.post("/mcp") {
                headers { append(HttpHeaders.Authorization, "Bearer good-token") }
                setBody(INITIALIZE_BODY)
            }
            resp.status shouldBe HttpStatusCode.OK
            resp.headers["MCP-Session-Id"] shouldContain "-"  // looks like a UUID
            validator.lastToken shouldBe "good-token"
        }
    }

    test("403 insufficient_scope when scope-required method is called with empty scopes") {
        // Use an unknown method to trigger the fail-closed admin requirement.
        val validator = FakeAuthValidator(
            BearerValidationResult.Valid(principalWithScopes()), // empty scopes
        )
        testApplication {
            application {
                installMcpHttpRoute(
                    config = JWKS_CONFIG,
                    serviceFactory = { McpServiceImpl(serverVersion = "0.1.0") },
                    authValidatorOverride = validator,
                )
            }
            // Initialize first (scope-free) so the session is set up.
            val initResp = client.post("/mcp") {
                headers { append(HttpHeaders.Authorization, "Bearer good-token") }
                setBody(INITIALIZE_BODY)
            }
            val sessionId = initResp.headers["MCP-Session-Id"]!!
            // Call a method whose mapping requires dmigrate:read (tools/list).
            val resp = client.post("/mcp") {
                headers {
                    append(HttpHeaders.Authorization, "Bearer good-token")
                    append("MCP-Session-Id", sessionId)
                    append("MCP-Protocol-Version", McpProtocol.MCP_PROTOCOL_VERSION)
                }
                setBody("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")
            }
            resp.status shouldBe HttpStatusCode.Forbidden
            val challenge = resp.headers[HttpHeaders.WWWAuthenticate]!!
            challenge shouldContain "error=\"insufficient_scope\""
            challenge shouldContain "dmigrate:read"
        }
    }

    test("tools/call capabilities_list works for a dmigrate:read principal (§12.9 Fix-i)") {
        // §12.9 + §12.14: the scope-check on tools/call must target
        // the actual tool name (here `capabilities_list` →
        // dmigrate:read), NOT the literal method "tools/call" which
        // would fall through to the admin fail-closed default.
        val validator = FakeAuthValidator(
            BearerValidationResult.Valid(principalWithScopes("dmigrate:read")),
        )
        testApplication {
            application {
                installMcpHttpRoute(
                    config = JWKS_CONFIG,
                    serviceFactory = {
                        McpServiceImpl(
                            serverVersion = "0.1.0",
                            toolRegistry = dev.dmigrate.mcp.registry.PhaseBRegistries.toolRegistry(),
                        )
                    },
                    authValidatorOverride = validator,
                )
            }
            val initResp = client.post("/mcp") {
                headers { append(HttpHeaders.Authorization, "Bearer good-token") }
                setBody(INITIALIZE_BODY)
            }
            val sessionId = initResp.headers["MCP-Session-Id"]!!
            val resp = client.post("/mcp") {
                headers {
                    append(HttpHeaders.Authorization, "Bearer good-token")
                    append("MCP-Session-Id", sessionId)
                    append("MCP-Protocol-Version", McpProtocol.MCP_PROTOCOL_VERSION)
                }
                setBody(
                    """{"jsonrpc":"2.0","id":42,"method":"tools/call",""" +
                        """"params":{"name":"capabilities_list","arguments":{}}}""",
                )
            }
            resp.status shouldBe HttpStatusCode.OK
            resp.bodyAsText() shouldContain "\"id\":42"
            resp.bodyAsText() shouldContain "dmigrateContractVersion"
        }
    }

    test("tools/call admin-scoped tool with dmigrate:read principal returns 403 (§12.9 Fix-i)") {
        // Counterpoint to the positive test: a tool whose scope is
        // dmigrate:admin (e.g. `connections/list` would qualify if it
        // were a tool — we use a synthetic admin-only mapping here)
        // must STILL be blocked when the principal only has
        // dmigrate:read. Verifies the scope-key extraction picks the
        // correct entry, not the method name.
        val configWithAdminTool = JWKS_CONFIG.copy(
            scopeMapping = JWKS_CONFIG.scopeMapping +
                ("admin_tool" to setOf("dmigrate:admin")),
        )
        val validator = FakeAuthValidator(
            BearerValidationResult.Valid(principalWithScopes("dmigrate:read")),
        )
        testApplication {
            application {
                installMcpHttpRoute(
                    config = configWithAdminTool,
                    serviceFactory = { McpServiceImpl(serverVersion = "0.1.0") },
                    authValidatorOverride = validator,
                )
            }
            val initResp = client.post("/mcp") {
                headers { append(HttpHeaders.Authorization, "Bearer good-token") }
                setBody(INITIALIZE_BODY)
            }
            val sessionId = initResp.headers["MCP-Session-Id"]!!
            val resp = client.post("/mcp") {
                headers {
                    append(HttpHeaders.Authorization, "Bearer good-token")
                    append("MCP-Session-Id", sessionId)
                    append("MCP-Protocol-Version", McpProtocol.MCP_PROTOCOL_VERSION)
                }
                setBody(
                    """{"jsonrpc":"2.0","id":7,"method":"tools/call",""" +
                        """"params":{"name":"admin_tool","arguments":{}}}""",
                )
            }
            resp.status shouldBe HttpStatusCode.Forbidden
            resp.headers[HttpHeaders.WWWAuthenticate]!! shouldContain "dmigrate:admin"
        }
    }

    test("DISABLED mode bypasses Bearer + Scope entirely") {
        testApplication {
            application {
                // McpServerConfig() defaults are JWT_JWKS — switch to DISABLED.
                installMcpHttpRoute(
                    config = McpServerConfig(authMode = AuthMode.DISABLED),
                    serviceFactory = { McpServiceImpl(serverVersion = "0.1.0") },
                )
            }
            val resp = client.post("/mcp") { setBody(INITIALIZE_BODY) }
            resp.status shouldBe HttpStatusCode.OK
            resp.bodyAsText() shouldContain McpProtocol.MCP_PROTOCOL_VERSION
        }
    }

    test("GET /.well-known/oauth-protected-resource returns the metadata document") {
        testApplication {
            application {
                installMcpHttpRoute(
                    config = JWKS_CONFIG,
                    serviceFactory = { McpServiceImpl(serverVersion = "0.1.0") },
                )
            }
            val resp = client.get("/.well-known/oauth-protected-resource")
            resp.status shouldBe HttpStatusCode.OK
            val body = resp.bodyAsText()
            body shouldContain "\"resource\":"
            body shouldContain "\"authorization_servers\":"
            body shouldContain "\"scopes_supported\":"
            body shouldContain "\"bearer_methods_supported\":[\"header\"]"
        }
    }
})
