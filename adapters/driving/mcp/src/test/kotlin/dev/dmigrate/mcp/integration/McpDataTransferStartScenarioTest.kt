package dev.dmigrate.mcp.integration

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.dmigrate.mcp.protocol.McpServiceImpl
import dev.dmigrate.mcp.protocol.ToolsCallParams
import dev.dmigrate.mcp.registry.McpRuntimeWiring
import dev.dmigrate.mcp.registry.OperationalMcpRegistries
import dev.dmigrate.mcp.registry.OperationalMcpWiring
import dev.dmigrate.mcp.server.McpLimitsConfig
import dev.dmigrate.server.application.policy.ConfiguredPolicyService
import dev.dmigrate.server.application.policy.PolicyEffect
import dev.dmigrate.server.application.quota.DefaultQuotaService
import dev.dmigrate.server.core.connection.ConnectionReference
import dev.dmigrate.server.core.connection.ConnectionSensitivity
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.core.resource.ServerResourceUri
import dev.dmigrate.server.ports.contract.Fixtures
import dev.dmigrate.server.ports.memory.InMemoryApprovalGrantStore
import dev.dmigrate.server.ports.memory.InMemoryArtifactContentStore
import dev.dmigrate.server.ports.memory.InMemoryArtifactStore
import dev.dmigrate.server.ports.memory.InMemoryConnectionReferenceStore
import dev.dmigrate.server.ports.memory.InMemoryIdempotencyStore
import dev.dmigrate.server.ports.memory.InMemoryJobStartTransaction
import dev.dmigrate.server.ports.memory.InMemoryJobStore
import dev.dmigrate.server.ports.memory.InMemoryQuotaStore
import dev.dmigrate.server.ports.memory.InMemorySchemaStore
import dev.dmigrate.server.ports.memory.InMemoryUploadSegmentStore
import dev.dmigrate.server.ports.memory.InMemoryUploadSessionStore
import dev.dmigrate.server.ports.memory.InMemoryWorkerHandleRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * LF-010 / LF-013 / LN-009 / LN-011 § 8.8 (F.8 4/4) — End-to-End-Integration des
 * `data_transfer_start`-Tools durch den realen `tools/call`-Pfad in
 * [McpServiceImpl] mit der produktiven [OperationalMcpRegistries]-Registry.
 */
class McpDataTransferStartScenarioTest : FunSpec({

    val tenant = Fixtures.tenant("acme")
    val now: Instant = Instant.parse("2026-05-06T12:00:00Z")
    val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)

    fun seedConnection(store: InMemoryConnectionReferenceStore, tenantId: TenantId, connectionId: String) {
        store.save(
            ConnectionReference(
                connectionId = connectionId,
                tenantId = tenantId,
                displayName = connectionId,
                dialectId = "postgres",
                sensitivity = ConnectionSensitivity.NON_PRODUCTION,
                resourceUri = ServerResourceUri(tenantId, ResourceKind.CONNECTIONS, connectionId),
            ),
        )
    }

    fun operationalWiring(policyDefault: PolicyEffect = PolicyEffect.Allow): OperationalMcpWiring {
        val jobStore = InMemoryJobStore()
        val idempotencyStore = InMemoryIdempotencyStore()
        val connectionStore = InMemoryConnectionReferenceStore()
        seedConnection(connectionStore, tenant, "source-db")
        seedConnection(connectionStore, tenant, "target-db")
        val phaseC = McpRuntimeWiring(
            uploadSessionStore = InMemoryUploadSessionStore(),
            uploadSegmentStore = InMemoryUploadSegmentStore(),
            artifactStore = InMemoryArtifactStore(),
            artifactContentStore = InMemoryArtifactContentStore(),
            schemaStore = InMemorySchemaStore(),
            jobStore = jobStore,
            quotaService = DefaultQuotaService(InMemoryQuotaStore()) { Long.MAX_VALUE },
            limits = McpLimitsConfig(),
            clock = clock,
            connectionStore = connectionStore,
        )
        return OperationalMcpWiring(
            runtimeWiring = phaseC,
            idempotencyStore = idempotencyStore,
            jobStartTransaction = InMemoryJobStartTransaction(jobStore, idempotencyStore),
            workerHandleRegistry = InMemoryWorkerHandleRegistry(),
            approvalGrantStore = InMemoryApprovalGrantStore(),
            policyService = ConfiguredPolicyService(rules = emptyList(), defaultEffect = policyDefault),
        )
    }

    fun service(wiring: OperationalMcpWiring): McpServiceImpl {
        val registry = OperationalMcpRegistries.defaultToolRegistry(wiring)
        val principal = Fixtures.principalContext(principalId = "alice", tenant = "acme")
            .copy(scopes = setOf("dmigrate:admin"), isAdmin = true)
        return McpServiceImpl(
            serverVersion = "test",
            toolRegistry = registry,
            initialPrincipal = principal,
        )
    }

    fun call(svc: McpServiceImpl, tool: String, args: JsonObject) =
        svc.toolsCall(ToolsCallParams(name = tool, arguments = args)).get()

    test("Allow-Policy + gueltige Args -> Success-Envelope mit jobId/resourceUri/executionMeta + JobStore-Eintrag") {
        val w = operationalWiring(PolicyEffect.Allow)
        val svc = service(w)
        val args = JsonParser.parseString(
            """
            {
              "idempotencyKey": "k-transfer-e2e-1",
              "sourceConnectionRef": "dmigrate://tenants/acme/connections/source-db",
              "targetConnectionRef": "dmigrate://tenants/acme/connections/target-db"
            }
            """.trimIndent(),
        ).asJsonObject

        val result = call(svc, "data_transfer_start", args)
        result.isError shouldBe false
        val text = result.content.first().text ?: error("expected text content")
        val payload = JsonParser.parseString(text).asJsonObject
        payload.has("jobId") shouldBe true
        payload.get("resourceUri").asString shouldContain "dmigrate://tenants/acme/jobs/"
        payload.getAsJsonObject("executionMeta").has("requestId") shouldBe true

        val jobId = payload.get("jobId").asString
        val record = w.runtimeWiring.jobStore.findById(tenant, jobId)
            ?: error("expected JobRecord $jobId in store")
        record.managedJob.operation shouldBe "data_transfer"
    }

    test("Idempotenter Retry liefert dieselbe jobId") {
        val svc = service(operationalWiring(PolicyEffect.Allow))
        val args = JsonParser.parseString(
            """
            {
              "idempotencyKey": "k-transfer-replay",
              "sourceConnectionRef": "dmigrate://tenants/acme/connections/source-db",
              "targetConnectionRef": "dmigrate://tenants/acme/connections/target-db"
            }
            """.trimIndent(),
        ).asJsonObject
        val first = JsonParser.parseString(
            call(svc, "data_transfer_start", args).content.first().text!!,
        ).asJsonObject
        val second = JsonParser.parseString(
            call(svc, "data_transfer_start", args).content.first().text!!,
        ).asJsonObject
        first.get("jobId").asString shouldBe second.get("jobId").asString
    }

    test("targetConnectionRef fehlt -> VALIDATION_ERROR") {
        val svc = service(operationalWiring(PolicyEffect.Allow))
        val args = JsonParser.parseString(
            """
            {
              "idempotencyKey": "k-noref",
              "sourceConnectionRef": "dmigrate://tenants/acme/connections/source-db"
            }
            """.trimIndent(),
        ).asJsonObject
        val result = call(svc, "data_transfer_start", args)
        result.isError shouldBe true
        result.content.first().text!! shouldContain "VALIDATION_ERROR"
        result.content.first().text!! shouldContain "targetConnectionRef"
    }

    test("Unbekannte sourceConnectionRef -> RESOURCE_NOT_FOUND") {
        val svc = service(operationalWiring(PolicyEffect.Allow))
        val args = JsonParser.parseString(
            """
            {
              "idempotencyKey": "k-missing-source",
              "sourceConnectionRef": "dmigrate://tenants/acme/connections/missing",
              "targetConnectionRef": "dmigrate://tenants/acme/connections/target-db"
            }
            """.trimIndent(),
        ).asJsonObject
        val result = call(svc, "data_transfer_start", args)
        result.isError shouldBe true
        result.content.first().text!! shouldContain "RESOURCE_NOT_FOUND"
    }

    test("Transfer mit filter + sinceColumn/since -> Success") {
        val svc = service(operationalWiring(PolicyEffect.Allow))
        val args = JsonParser.parseString(
            """
            {
              "idempotencyKey": "k-transfer-incremental",
              "sourceConnectionRef": "dmigrate://tenants/acme/connections/source-db",
              "targetConnectionRef": "dmigrate://tenants/acme/connections/target-db",
              "filter": "tenant_id = 'tenant'",
              "sinceColumn": "updated_at",
              "since": "2026-05-01T00:00:00Z",
              "chunkSize": 5000
            }
            """.trimIndent(),
        ).asJsonObject
        val result = call(svc, "data_transfer_start", args)
        result.isError shouldBe false
    }

    test("data_transfer_start ist im Wire-Schema vom LF-010 / LF-013 / LN-009 / LN-011-Output (resourceUri + executionMeta)") {
        val schemas = dev.dmigrate.mcp.schema.McpToolSchemas.forTool("data_transfer_start")
            ?: error("data_transfer_start schema missing")
        val output = schemas.outputSchema
        @Suppress("UNCHECKED_CAST")
        val required = output["required"] as List<String>
        required shouldBe listOf("jobId", "resourceUri", "executionMeta")
    }
})
