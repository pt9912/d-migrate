package dev.dmigrate.mcp.integration

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.dmigrate.mcp.auth.DisabledAuthValidator
import dev.dmigrate.mcp.prompts.DefaultPromptRegistry
import dev.dmigrate.mcp.protocol.McpProtocol
import dev.dmigrate.mcp.protocol.McpServiceImpl
import dev.dmigrate.mcp.registry.PhaseCWiring
import dev.dmigrate.mcp.registry.PhaseEWiring
import dev.dmigrate.mcp.registry.PhaseGRegistries
import dev.dmigrate.mcp.registry.PhaseGWiring
import dev.dmigrate.mcp.server.AuthMode
import dev.dmigrate.mcp.server.McpLimitsConfig
import dev.dmigrate.mcp.server.McpServerConfig
import dev.dmigrate.mcp.transport.http.installMcpHttpRoute
import dev.dmigrate.server.application.audit.prompt.DefaultPromptHygieneService
import dev.dmigrate.server.application.policy.ConfiguredPolicyService
import dev.dmigrate.server.application.policy.PolicyEffect
import dev.dmigrate.server.application.quota.DefaultQuotaService
import dev.dmigrate.server.core.principal.AuthSource
import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.core.resource.ServerResourceUri
import dev.dmigrate.server.ports.SchemaIndexEntry
import dev.dmigrate.server.ports.memory.InMemoryApprovalGrantStore
import dev.dmigrate.server.ports.memory.InMemoryArtifactContentStore
import dev.dmigrate.server.ports.memory.InMemoryArtifactStore
import dev.dmigrate.server.ports.memory.InMemoryIdempotencyStore
import dev.dmigrate.server.ports.memory.InMemoryJobStartTransaction
import dev.dmigrate.server.ports.memory.InMemoryJobStore
import dev.dmigrate.server.ports.memory.InMemoryProfileStore
import dev.dmigrate.server.ports.memory.InMemoryQuotaStore
import dev.dmigrate.server.ports.memory.InMemorySchemaStore
import dev.dmigrate.server.ports.memory.InMemoryUploadSegmentStore
import dev.dmigrate.server.ports.memory.InMemoryUploadSessionStore
import dev.dmigrate.server.ports.memory.InMemoryWorkerHandleRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Phase G § 6 G.9 — End-to-end-Akzeptanz über den realen
 * Streamable-HTTP-Transport für die KI-nahen Tools und MCP-Prompts.
 *
 * Pin't:
 * - initialize über HTTP advertised capabilities.prompts.
 * - prompts/list und prompts/get laufen über HTTP mit derselben
 *   MCP-Session.
 * - tools/call procedure_transform_plan über HTTP -> Success.
 * - prompts/get mit Hygiene-Verletzung -> JSON-RPC mit
 *   dmigrateCode=PROMPT_HYGIENE_BLOCKED.
 */
class McpPhaseGHttpE2EIT : FunSpec({

    val tenant = TenantId("acme")
    val alice = PrincipalId("alice")
    val now: Instant = Instant.parse("2026-05-07T13:00:00Z")
    val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)

    val principal = PrincipalContext(
        principalId = alice,
        homeTenantId = tenant,
        effectiveTenantId = tenant,
        allowedTenantIds = setOf(tenant),
        scopes = setOf("dmigrate:read", "dmigrate:ai:execute"),
        isAdmin = false,
        auditSubject = "alice",
        authSource = AuthSource.SERVICE_ACCOUNT,
        expiresAt = Instant.MAX,
    )

    fun phaseGWiring(): PhaseGWiring {
        val jobStore = InMemoryJobStore()
        val idempotencyStore = InMemoryIdempotencyStore()
        val schemaStore = InMemorySchemaStore()
        schemaStore.register(
            SchemaIndexEntry(
                schemaId = "schema-1",
                tenantId = tenant,
                resourceUri = ServerResourceUri(tenant, ResourceKind.SCHEMAS, "schema-1"),
                artifactRef = "art-schema-1",
                displayName = "schema-1",
                createdAt = now,
                expiresAt = now.plusSeconds(3600),
            ),
        )
        val phaseC = PhaseCWiring(
            uploadSessionStore = InMemoryUploadSessionStore(),
            uploadSegmentStore = InMemoryUploadSegmentStore(),
            artifactStore = InMemoryArtifactStore(),
            artifactContentStore = InMemoryArtifactContentStore(),
            schemaStore = schemaStore,
            jobStore = jobStore,
            quotaService = DefaultQuotaService(InMemoryQuotaStore()) { Long.MAX_VALUE },
            limits = McpLimitsConfig(),
            clock = clock,
            profileStore = InMemoryProfileStore(),
        )
        val phaseE = PhaseEWiring(
            phaseCWiring = phaseC,
            idempotencyStore = idempotencyStore,
            jobStartTransaction = InMemoryJobStartTransaction(jobStore, idempotencyStore),
            workerHandleRegistry = InMemoryWorkerHandleRegistry(),
            approvalGrantStore = InMemoryApprovalGrantStore(),
            policyService = ConfiguredPolicyService(emptyList(), PolicyEffect.Allow),
        )
        return PhaseGWiring(phaseEWiring = phaseE)
    }

    fun parseResultObj(text: String): JsonObject =
        JsonParser.parseString(text).asJsonObject.getAsJsonObject("result")

    fun parseToolText(text: String): JsonObject =
        JsonParser.parseString(parseResultObj(text).getAsJsonArray("content").get(0).asJsonObject.get("text").asString)
            .asJsonObject

    val initBody = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":""" +
        """{"protocolVersion":"2025-11-25","clientInfo":{"name":"g9-http-it","version":"0.9.6"},"capabilities":{}}}"""

    val cfg = McpServerConfig(authMode = AuthMode.DISABLED)

    fun HttpRequestBuilder.mcpAccept() {
        headers { append(HttpHeaders.Accept, "application/json, text/event-stream") }
    }

    fun serviceFactory(): () -> McpServiceImpl = {
        val gWiring = phaseGWiring()
        McpServiceImpl(
            serverVersion = "0.9.6-it",
            toolRegistry = PhaseGRegistries.defaultToolRegistry(gWiring),
            initialPrincipal = principal,
            promptRegistry = DefaultPromptRegistry.mandatory(),
            promptHygieneService = DefaultPromptHygieneService(),
        )
    }

    test("Plan §6 G.9: initialize via HTTP advertised capabilities.prompts") {
        testApplication {
            application {
                installMcpHttpRoute(
                    config = cfg,
                    serviceFactory = serviceFactory(),
                    authValidatorOverride = DisabledAuthValidator(principal = principal),
                )
            }
            val resp = client.post("/mcp") {
                mcpAccept()
                setBody(initBody)
            }
            resp.status shouldBe HttpStatusCode.OK
            val body = resp.bodyAsText()
            val capabilities = parseResultObj(body).getAsJsonObject("capabilities")
            capabilities.has("prompts") shouldBe true
        }
    }

    test("Plan §6 G.9: prompts/list + prompts/get + tools/call procedure_transform_plan in einer Session") {
        testApplication {
            application {
                installMcpHttpRoute(
                    config = cfg,
                    serviceFactory = serviceFactory(),
                    authValidatorOverride = DisabledAuthValidator(principal = principal),
                )
            }
            val initResp = client.post("/mcp") {
                mcpAccept()
                setBody(initBody)
            }
            initResp.status shouldBe HttpStatusCode.OK
            val sessionId = initResp.headers["MCP-Session-Id"]!!
            val protocolVersion = initResp.headers["MCP-Protocol-Version"]!!
            protocolVersion shouldBe McpProtocol.MCP_PROTOCOL_VERSION

            suspend fun rpc(body: String): String {
                val r = client.post("/mcp") {
                    mcpAccept()
                    headers {
                        append("MCP-Session-Id", sessionId)
                        append("MCP-Protocol-Version", protocolVersion)
                    }
                    setBody(body)
                }
                r.status shouldBe HttpStatusCode.OK
                return r.bodyAsText()
            }

            // 1. prompts/list
            val listBody = rpc("""{"jsonrpc":"2.0","id":2,"method":"prompts/list","params":{}}""")
            val prompts = parseResultObj(listBody).getAsJsonArray("prompts")
            prompts.size() shouldBe 3

            // 2. prompts/get happy path
            val getBody = rpc(
                """{"jsonrpc":"2.0","id":3,"method":"prompts/get","params":""" +
                    """{"name":"testdata_planning","arguments":""" +
                    """{"schemaRef":"dmigrate://tenants/acme/schemas/schema-1","targetDialect":"POSTGRESQL"}}}""",
            )
            val getResult = parseResultObj(getBody)
            getResult.has("description") shouldBe true
            getResult.getAsJsonArray("messages").size() shouldBe 1

            // 3. tools/call procedure_transform_plan
            val callBody = rpc(
                """{"jsonrpc":"2.0","id":4,"method":"tools/call","params":""" +
                    """{"name":"procedure_transform_plan","arguments":""" +
                    """{"approvalKey":"k-http-1","schemaRef":"dmigrate://tenants/acme/schemas/schema-1",""" +
                    """"procedureName":"foo","targetDialect":"POSTGRESQL"}}}""",
            )
            val tool = parseToolText(callBody)
            tool.get("planRef").asString shouldStartWith "dmigrate://tenants/acme/artifacts/art-"
            tool.getAsJsonObject("providerMeta").get("providerName").asString shouldBe "noop"
        }
    }

    test("Plan §6 G.9: prompts/get mit Hygiene-Verletzung -> JSON-RPC mit dmigrateCode=PROMPT_HYGIENE_BLOCKED") {
        testApplication {
            application {
                installMcpHttpRoute(
                    config = cfg,
                    serviceFactory = serviceFactory(),
                    authValidatorOverride = DisabledAuthValidator(principal = principal),
                )
            }
            val initResp = client.post("/mcp") {
                mcpAccept()
                setBody(initBody)
            }
            val sessionId = initResp.headers["MCP-Session-Id"]!!
            val protocolVersion = initResp.headers["MCP-Protocol-Version"]!!

            val resp = client.post("/mcp") {
                mcpAccept()
                headers {
                    append("MCP-Session-Id", sessionId)
                    append("MCP-Protocol-Version", protocolVersion)
                }
                setBody(
                    """{"jsonrpc":"2.0","id":2,"method":"prompts/get","params":""" +
                        """{"name":"testdata_planning","arguments":""" +
                        """{"schemaRef":"dmigrate://tenants/acme/schemas/schema-1","targetDialect":"POSTGRESQL",""" +
                        """"rulesSummary":"use api_key=AKIA1234567890ABCDEF"}}}""",
                )
            }
            val errorObj = JsonParser.parseString(resp.bodyAsText()).asJsonObject.getAsJsonObject("error")
            errorObj.getAsJsonObject("data").get("dmigrateCode").asString shouldBe "PROMPT_HYGIENE_BLOCKED"
            // Plan §6 G.4 Akzeptanz: kein Secret im public message.
            errorObj.get("message").asString.contains("AKIA") shouldBe false
        }
    }

    test("Plan §6 G.9: prompts/get unbekannter Name -> JSON-RPC mit dmigrateCode=RESOURCE_NOT_FOUND") {
        testApplication {
            application {
                installMcpHttpRoute(
                    config = cfg,
                    serviceFactory = serviceFactory(),
                    authValidatorOverride = DisabledAuthValidator(principal = principal),
                )
            }
            val initResp = client.post("/mcp") {
                mcpAccept()
                setBody(initBody)
            }
            val sessionId = initResp.headers["MCP-Session-Id"]!!
            val protocolVersion = initResp.headers["MCP-Protocol-Version"]!!

            val resp = client.post("/mcp") {
                mcpAccept()
                headers {
                    append("MCP-Session-Id", sessionId)
                    append("MCP-Protocol-Version", protocolVersion)
                }
                setBody("""{"jsonrpc":"2.0","id":2,"method":"prompts/get","params":{"name":"missing"}}""")
            }
            val errorObj = JsonParser.parseString(resp.bodyAsText()).asJsonObject.getAsJsonObject("error")
            errorObj.get("message").asString shouldContain "missing"
            errorObj.getAsJsonObject("data").get("dmigrateCode").asString shouldBe "RESOURCE_NOT_FOUND"
        }
    }
})
