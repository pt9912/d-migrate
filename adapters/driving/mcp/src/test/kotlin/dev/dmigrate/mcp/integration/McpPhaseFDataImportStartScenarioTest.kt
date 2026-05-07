package dev.dmigrate.mcp.integration

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.dmigrate.mcp.protocol.McpServiceImpl
import dev.dmigrate.mcp.protocol.ToolsCallParams
import dev.dmigrate.mcp.registry.ArtifactUploadInitHandler
import dev.dmigrate.mcp.registry.PhaseCWiring
import dev.dmigrate.mcp.registry.PhaseERegistries
import dev.dmigrate.mcp.registry.PhaseEWiring
import dev.dmigrate.mcp.server.McpLimitsConfig
import dev.dmigrate.server.application.policy.ConfiguredPolicyService
import dev.dmigrate.server.application.policy.PolicyEffect
import dev.dmigrate.server.application.quota.DefaultQuotaService
import dev.dmigrate.server.core.artifact.ArtifactKind
import dev.dmigrate.server.core.artifact.ArtifactRecord
import dev.dmigrate.server.core.artifact.ArtifactUploadMetadata
import dev.dmigrate.server.core.artifact.ManagedArtifact
import dev.dmigrate.server.core.connection.ConnectionReference
import dev.dmigrate.server.core.connection.ConnectionSensitivity
import dev.dmigrate.server.core.job.JobVisibility
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
import java.io.ByteArrayInputStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Phase F § 8.7 (F.7 5/5) — End-to-End-Integration des
 * `data_import_start`-Tools durch den realen `tools/call`-Pfad in
 * [McpServiceImpl] mit der produktiven [PhaseERegistries]-Registry.
 *
 * Pin't:
 *
 * - Allow-Policy + gueltige Args -> Success-Envelope mit jobId/
 *   resourceUri/executionMeta. Job durabel im JobStore.
 * - Plan-§-8.7-Pflichten am Wire: VALIDATION_ERROR fuer fehlenden
 *   `targetConnectionRef`, RESOURCE_NOT_FOUND fuer unbekanntes
 *   Artefakt.
 * - Runner-Boundary aus F.7 (5/5): das Tool wird als Job durabel
 *   angelegt; ohne explizit injizierten Import-Runner terminiert der
 *   Datenjob fail-closed, statt erfolgreichen JDBC-I/O zu simulieren.
 */
class McpPhaseFDataImportStartScenarioTest : FunSpec({

    val tenant = Fixtures.tenant("acme")
    val now: Instant = Instant.parse("2026-05-06T12:00:00Z")
    val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)

    fun seedArtifact(
        store: InMemoryArtifactStore,
        contentStore: InMemoryArtifactContentStore,
        tenantId: TenantId,
        artifactId: String,
    ) {
        store.save(
            ArtifactRecord(
                managedArtifact = ManagedArtifact(
                    artifactId = artifactId,
                    filename = "upload-ups-1-$artifactId.bin",
                    contentType = "text/csv",
                    sizeBytes = 1024,
                    sha256 = "deadbeef".repeat(8),
                    createdAt = now,
                    expiresAt = now.plusSeconds(86_400),
                ),
                kind = ArtifactKind.UPLOAD_INPUT,
                tenantId = tenantId,
                ownerPrincipalId = Fixtures.principal("alice"),
                visibility = JobVisibility.TENANT,
                resourceUri = ServerResourceUri(tenantId, ResourceKind.ARTIFACTS, artifactId),
                uploadMetadata = ArtifactUploadMetadata(
                    artifactId = artifactId,
                    resourceUri = ServerResourceUri(tenantId, ResourceKind.ARTIFACTS, artifactId).render(),
                    uploadIntent = ArtifactUploadInitHandler.INTENT_JOB_INPUT,
                    wireArtifactKind = "seed-data",
                    contentType = "text/csv",
                    format = "csv",
                    targetTable = "warehouse.events",
                    sourceUploadSessionId = "ups-1",
                    policyFingerprint = "fp-upload",
                    sizeBytes = 1024,
                    sha256 = "deadbeef".repeat(8),
                ),
            ),
        )
        contentStore.write(
            artifactId = artifactId,
            source = ByteArrayInputStream(ByteArray(1024) { 'x'.code.toByte() }),
            expectedSizeBytes = 1024,
        )
    }

    fun seedConnection(store: InMemoryConnectionReferenceStore, tenantId: TenantId, connectionId: String) {
        store.save(
            ConnectionReference(
                connectionId = connectionId,
                tenantId = tenantId,
                displayName = "warehouse-prod",
                dialectId = "postgres",
                sensitivity = ConnectionSensitivity.NON_PRODUCTION,
                resourceUri = ServerResourceUri(tenantId, ResourceKind.CONNECTIONS, connectionId),
            ),
        )
    }

    fun phaseEWiring(policyDefault: PolicyEffect = PolicyEffect.Allow): PhaseEWiring {
        val jobStore = InMemoryJobStore()
        val idempotencyStore = InMemoryIdempotencyStore()
        val artifactStore = InMemoryArtifactStore()
        val artifactContentStore = InMemoryArtifactContentStore()
        val connectionStore = InMemoryConnectionReferenceStore()
        seedArtifact(artifactStore, artifactContentStore, tenant, "art-import-1")
        seedConnection(connectionStore, tenant, "warehouse")
        val phaseC = PhaseCWiring(
            uploadSessionStore = InMemoryUploadSessionStore(),
            uploadSegmentStore = InMemoryUploadSegmentStore(),
            artifactStore = artifactStore,
            artifactContentStore = artifactContentStore,
            schemaStore = InMemorySchemaStore(),
            jobStore = jobStore,
            quotaService = DefaultQuotaService(InMemoryQuotaStore()) { Long.MAX_VALUE },
            limits = McpLimitsConfig(),
            clock = clock,
            connectionStore = connectionStore,
        )
        return PhaseEWiring(
            phaseCWiring = phaseC,
            idempotencyStore = idempotencyStore,
            jobStartTransaction = InMemoryJobStartTransaction(jobStore, idempotencyStore),
            workerHandleRegistry = InMemoryWorkerHandleRegistry(),
            approvalGrantStore = InMemoryApprovalGrantStore(),
            policyService = ConfiguredPolicyService(rules = emptyList(), defaultEffect = policyDefault),
        )
    }

    fun service(wiring: PhaseEWiring): McpServiceImpl {
        val registry = PhaseERegistries.defaultToolRegistry(wiring)
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
        val w = phaseEWiring(PolicyEffect.Allow)
        val svc = service(w)
        val args = JsonParser.parseString(
            """
            {
              "idempotencyKey": "k-import-e2e-1",
              "targetConnectionRef": "dmigrate://tenants/acme/connections/warehouse",
              "artifactId": "art-import-1",
              "table": "warehouse.events"
            }
            """.trimIndent(),
        ).asJsonObject

        val result = call(svc, "data_import_start", args)
        result.isError shouldBe false
        val text = result.content.first().text ?: error("expected text content")
        val payload = JsonParser.parseString(text).asJsonObject
        payload.has("jobId") shouldBe true
        payload.get("resourceUri").asString shouldContain "dmigrate://tenants/acme/jobs/"
        payload.getAsJsonObject("executionMeta").has("requestId") shouldBe true

        // Plan-§-8.7 Akzeptanz: durabler JobRecord mit operation=data_import.
        val jobId = payload.get("jobId").asString
        val record = w.phaseCWiring.jobStore.findById(tenant, jobId)
            ?: error("expected JobRecord $jobId in store")
        record.managedJob.operation shouldBe "data_import"
    }

    test("Idempotenter Retry liefert dieselbe jobId") {
        val w = phaseEWiring(PolicyEffect.Allow)
        val svc = service(w)
        val args = JsonParser.parseString(
            """
            {
              "idempotencyKey": "k-import-e2e-replay",
              "targetConnectionRef": "dmigrate://tenants/acme/connections/warehouse",
              "artifactId": "art-import-1"
            }
            """.trimIndent(),
        ).asJsonObject
        val first = JsonParser.parseString(
            call(svc, "data_import_start", args).content.first().text!!,
        ).asJsonObject
        val second = JsonParser.parseString(
            call(svc, "data_import_start", args).content.first().text!!,
        ).asJsonObject
        first.get("jobId").asString shouldBe second.get("jobId").asString
    }

    test("targetConnectionRef fehlt -> VALIDATION_ERROR (vor Store-Write)") {
        val svc = service(phaseEWiring(PolicyEffect.Allow))
        val args = JsonParser.parseString(
            """{"idempotencyKey":"k-noref","artifactId":"art-import-1"}""",
        ).asJsonObject
        val result = call(svc, "data_import_start", args)
        result.isError shouldBe true
        result.content.first().text!! shouldContain "VALIDATION_ERROR"
        result.content.first().text!! shouldContain "targetConnectionRef"
    }

    test("Unbekanntes artifactId -> RESOURCE_NOT_FOUND") {
        val svc = service(phaseEWiring(PolicyEffect.Allow))
        val args = JsonParser.parseString(
            """
            {
              "idempotencyKey": "k-missing-art",
              "targetConnectionRef": "dmigrate://tenants/acme/connections/warehouse",
              "artifactId": "art-not-in-store"
            }
            """.trimIndent(),
        ).asJsonObject
        val result = call(svc, "data_import_start", args)
        result.isError shouldBe true
        result.content.first().text!! shouldContain "RESOURCE_NOT_FOUND"
    }

    test("data_import_start ist im Wire-Schema vom Phase-F-Output (resourceUri + executionMeta)") {
        // Plan-Smoke: das Output-Schema ist nicht mehr der Phase-B-
        // Stub jobIdOut(); jobStartOut() liefert resourceUri und
        // executionMeta. Der Allow-Pfad oben hat das verifiziert —
        // dieser Test pin't zusaetzlich, dass beide Felder im
        // Tool-Schema deklariert sind.
        val schemas = dev.dmigrate.mcp.schema.PhaseBToolSchemas.forTool("data_import_start")
            ?: error("data_import_start schema missing")
        val output = schemas.outputSchema
        @Suppress("UNCHECKED_CAST")
        val required = output["required"] as List<String>
        required shouldBe listOf("jobId", "resourceUri", "executionMeta")
    }
})
