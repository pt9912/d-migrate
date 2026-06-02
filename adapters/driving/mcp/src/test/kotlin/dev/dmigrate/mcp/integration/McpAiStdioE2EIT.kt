package dev.dmigrate.mcp.integration

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.dmigrate.mcp.prompts.DefaultPromptRegistry
import dev.dmigrate.mcp.protocol.McpServiceImpl
import dev.dmigrate.mcp.registry.McpRuntimeWiring
import dev.dmigrate.mcp.registry.OperationalMcpWiring
import dev.dmigrate.mcp.registry.AiMcpRegistries
import dev.dmigrate.mcp.registry.AiMcpWiring
import dev.dmigrate.mcp.server.McpLimitsConfig
import dev.dmigrate.mcp.transport.stdio.StdioJsonRpc
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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * LF-017 / LF-024 / LN-030 / LN-031 — End-to-end-Akzeptanz über den realen
 * NDJSON-stdio-Transport für die KI-nahen Tools und MCP-Prompts.
 *
 * Pin't:
 * - initialize advertised `capabilities.prompts` (LF-017 / LF-024 / LN-030 / LN-031).
 * - tools/list zeigt die drei produktiven KI-Tools an.
 * - prompts/list zeigt die drei Pflichtprompts an.
 * - tools/call procedure_transform_plan -> Success über stdio.
 * - prompts/get -> Success über stdio.
 * - prompts/get unbekannter Name -> JSON-RPC-Fehler (RESOURCE_NOT_FOUND).
 */
class McpAiStdioE2EIT : FunSpec({

    val tenant = TenantId("acme")
    val alice = PrincipalId("alice")
    val now: Instant = Instant.parse("2026-05-07T13:00:00Z")
    val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)

    fun principal() = PrincipalContext(
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

    fun aiWiring(): AiMcpWiring {
        val jobStore = InMemoryJobStore()
        val idempotencyStore = InMemoryIdempotencyStore()
        val schemaStore = InMemorySchemaStore()
        // Schema seeden, damit AI-Handler eine valide Source haben.
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
        val phaseC = McpRuntimeWiring(
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
        val phaseE = OperationalMcpWiring(
            runtimeWiring = phaseC,
            idempotencyStore = idempotencyStore,
            jobStartTransaction = InMemoryJobStartTransaction(jobStore, idempotencyStore),
            workerHandleRegistry = InMemoryWorkerHandleRegistry(),
            approvalGrantStore = InMemoryApprovalGrantStore(),
            policyService = ConfiguredPolicyService(emptyList(), PolicyEffect.Allow),
        )
        return AiMcpWiring(operationalWiring = phaseE)
    }

    fun runStdioRoundtrip(frames: List<String>): List<String> {
        val gWiring = aiWiring()
        val registry = AiMcpRegistries.defaultToolRegistry(gWiring)
        val service = McpServiceImpl(
            serverVersion = "0.9.7-it",
            toolRegistry = registry,
            initialPrincipal = principal(),
            promptRegistry = DefaultPromptRegistry.mandatory(),
            promptHygieneService = DefaultPromptHygieneService(),
        )
        val ndjson = frames.joinToString("\n", postfix = "\n")
        val input = ByteArrayInputStream(ndjson.toByteArray(StandardCharsets.UTF_8))
        val output = ByteArrayOutputStream()
        val rpc = StdioJsonRpc(input, output, service)
        rpc.start()
        val deadline = System.currentTimeMillis() + 10_000
        while (output.toString(StandardCharsets.UTF_8).count { it == '\n' } < frames.size &&
            System.currentTimeMillis() < deadline
        ) {
            Thread.sleep(20)
        }
        rpc.stop()
        return output.toString(StandardCharsets.UTF_8)
            .split('\n').filter { it.isNotBlank() }
    }

    fun parseResultObj(line: String): JsonObject =
        JsonParser.parseString(line).asJsonObject.getAsJsonObject("result")

    fun parseToolText(line: String): JsonObject =
        JsonParser.parseString(parseResultObj(line).getAsJsonArray("content").get(0).asJsonObject.get("text").asString)
            .asJsonObject

    fun initFrame(id: Int = 1): String =
        """{"jsonrpc":"2.0","id":$id,"method":"initialize","params":""" +
            """{"protocolVersion":"2025-11-25","clientInfo":{"name":"g9-it","version":"0.9.7"},"capabilities":{}}}"""

    test("LF-017 / LF-024 / LN-030 / LN-031: initialize advertised capabilities.prompts neben tools + resources") {
        val resp = runStdioRoundtrip(listOf(initFrame()))
        resp.size shouldBe 1
        val capabilities = parseResultObj(resp[0]).getAsJsonObject("capabilities")
        capabilities.has("tools") shouldBe true
        capabilities.has("resources") shouldBe true
        capabilities.has("prompts") shouldBe true
        capabilities.getAsJsonObject("prompts").get("listChanged").asBoolean shouldBe false
    }

    test("LF-017 / LF-024 / LN-030 / LN-031: prompts/list ueber stdio liefert die drei Pflichtprompts") {
        val resp = runStdioRoundtrip(
            listOf(
                initFrame(1),
                """{"jsonrpc":"2.0","id":2,"method":"prompts/list","params":{}}""",
            ),
        )
        resp.size shouldBe 2
        val prompts = parseResultObj(resp[1]).getAsJsonArray("prompts")
        prompts.size() shouldBe 3
        val names = (0 until prompts.size()).map { prompts[it].asJsonObject.get("name").asString }
        names shouldBe listOf("procedure_analysis", "procedure_transformation", "testdata_planning")
    }

    test("LF-017 / LF-024 / LN-030 / LN-031: prompts/get happy path liefert Prompt-Nachrichten") {
        val resp = runStdioRoundtrip(
            listOf(
                initFrame(1),
                """{"jsonrpc":"2.0","id":2,"method":"prompts/get","params":""" +
                    """{"name":"procedure_analysis","arguments":""" +
                    """{"schemaRef":"dmigrate://tenants/acme/schemas/schema-1","procedureName":"foo"}}}""",
            ),
        )
        resp.size shouldBe 2
        val result = parseResultObj(resp[1])
        result.has("description") shouldBe true
        result.getAsJsonArray("messages").size() shouldBe 1
        val message = result.getAsJsonArray("messages").get(0).asJsonObject
        message.get("role").asString shouldBe "user"
        message.getAsJsonObject("content").get("text").asString shouldContain
            "schemaRef=dmigrate://tenants/acme/schemas/schema-1"
    }

    test("LF-017 / LF-024 / LN-030 / LN-031: prompts/get mit unbekanntem Prompt -> JSON-RPC error mit dmigrateCode=RESOURCE_NOT_FOUND") {
        val resp = runStdioRoundtrip(
            listOf(
                initFrame(1),
                """{"jsonrpc":"2.0","id":2,"method":"prompts/get","params":{"name":"no_such_prompt"}}""",
            ),
        )
        resp.size shouldBe 2
        val errorObj = JsonParser.parseString(resp[1]).asJsonObject.getAsJsonObject("error")
        errorObj.get("message").asString shouldContain "no_such_prompt"
        errorObj.getAsJsonObject("data").get("dmigrateCode").asString shouldBe "RESOURCE_NOT_FOUND"
    }

    test("LF-017 / LF-024 / LN-030 / LN-031: tools/call procedure_transform_plan ueber stdio -> Success mit planRef") {
        val args = """{"approvalKey":"k-stdio-1","schemaRef":"dmigrate://tenants/acme/schemas/schema-1",""" +
            """"procedureName":"foo","targetDialect":"POSTGRESQL"}"""
        val resp = runStdioRoundtrip(
            listOf(
                initFrame(1),
                """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":""" +
                    """{"name":"procedure_transform_plan","arguments":$args}}""",
            ),
        )
        resp.size shouldBe 2
        val tool = parseToolText(resp[1])
        tool.get("planRef").asString shouldContain "dmigrate://tenants/acme/artifacts/art-"
        tool.getAsJsonObject("providerMeta").get("providerName").asString shouldBe "noop"
    }

    test("LF-017 / LF-024 / LN-030 / LN-031: KI-Tool-Retry mit gleichem approvalKey -> Replay (selber planRef)") {
        val args = """{"approvalKey":"k-replay","schemaRef":"dmigrate://tenants/acme/schemas/schema-1",""" +
            """"procedureName":"foo","targetDialect":"POSTGRESQL"}"""
        val resp = runStdioRoundtrip(
            listOf(
                initFrame(1),
                """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":""" +
                    """{"name":"procedure_transform_plan","arguments":$args}}""",
                """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":""" +
                    """{"name":"procedure_transform_plan","arguments":$args}}""",
            ),
        )
        resp.size shouldBe 3
        val first = parseToolText(resp[1])
        val second = parseToolText(resp[2])
        first.get("planRef").asString shouldBe second.get("planRef").asString
        // summary signalisiert den Replay-Pfad.
        second.get("summary").asString shouldBe "replayed plan"
    }

    test("LF-017 / LF-024 / LN-030 / LN-031: gleicher approvalKey + abweichender Payload -> IDEMPOTENCY_CONFLICT-Envelope") {
        val args1 = """{"approvalKey":"k-conflict","schemaRef":"dmigrate://tenants/acme/schemas/schema-1",""" +
            """"procedureName":"foo","targetDialect":"POSTGRESQL"}"""
        val args2 = """{"approvalKey":"k-conflict","schemaRef":"dmigrate://tenants/acme/schemas/schema-1",""" +
            """"procedureName":"bar","targetDialect":"POSTGRESQL"}"""
        val resp = runStdioRoundtrip(
            listOf(
                initFrame(1),
                """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":""" +
                    """{"name":"procedure_transform_plan","arguments":$args1}}""",
                """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":""" +
                    """{"name":"procedure_transform_plan","arguments":$args2}}""",
            ),
        )
        resp.size shouldBe 3
        // Zweiter Aufruf liefert ToolErrorEnvelope mit Code=IDEMPOTENCY_CONFLICT.
        // Wire-Form: result.content[0].text enthaelt ein ToolErrorEnvelope-JSON,
        // result.isError=true.
        val secondResp = JsonParser.parseString(resp[2]).asJsonObject
        val secondResult = secondResp.getAsJsonObject("result")
        secondResult.get("isError").asBoolean shouldBe true
        secondResult.getAsJsonArray("content").get(0).asJsonObject
            .get("text").asString shouldContain "IDEMPOTENCY_CONFLICT"
    }

    test("LF-017 / LF-024 / LN-030 / LN-031: KI-Tool ohne dmigrate:ai:execute -> ForbiddenPrincipal-Wire-Envelope") {
        // Custom-Wiring mit read-only-Principal.
        val gWiring = aiWiring()
        val registry = AiMcpRegistries.defaultToolRegistry(gWiring)
        val readOnlyPrincipal = principal().copy(scopes = setOf("dmigrate:read"))
        val service = McpServiceImpl(
            serverVersion = "test",
            toolRegistry = registry,
            initialPrincipal = readOnlyPrincipal,
            promptRegistry = DefaultPromptRegistry.mandatory(),
            promptHygieneService = DefaultPromptHygieneService(),
        )
        val args = """{"approvalKey":"k-noscope","schemaRef":"dmigrate://tenants/acme/schemas/schema-1",""" +
            """"procedureName":"foo","targetDialect":"POSTGRESQL"}"""
        val frames = listOf(
            initFrame(1),
            """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":""" +
                """{"name":"procedure_transform_plan","arguments":$args}}""",
        )
        val ndjson = frames.joinToString("\n", postfix = "\n")
        val input = ByteArrayInputStream(ndjson.toByteArray(StandardCharsets.UTF_8))
        val output = ByteArrayOutputStream()
        val rpc = StdioJsonRpc(input, output, service)
        rpc.start()
        val deadline = System.currentTimeMillis() + 10_000
        while (output.toString(StandardCharsets.UTF_8).count { it == '\n' } < 2 &&
            System.currentTimeMillis() < deadline
        ) {
            Thread.sleep(20)
        }
        rpc.stop()
        val responses = output.toString(StandardCharsets.UTF_8).split('\n').filter { it.isNotBlank() }
        responses.size shouldBe 2
        // Der Scope-Check im Handler wirft ForbiddenPrincipalException.
        // McpServiceImpl mappt das via ErrorMapper auf ToolErrorEnvelope.
        val toolResp = JsonParser.parseString(responses[1]).asJsonObject.getAsJsonObject("result")
        toolResp.get("isError").asBoolean shouldBe true
        toolResp.getAsJsonArray("content").get(0).asJsonObject
            .get("text").asString shouldContain "FORBIDDEN_PRINCIPAL"
    }
})
