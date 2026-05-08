package dev.dmigrate.mcp.integration

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.dmigrate.mcp.protocol.McpServiceImpl
import dev.dmigrate.mcp.protocol.ToolsCallParams
import dev.dmigrate.mcp.registry.ArtifactUploadInitHandler
import dev.dmigrate.mcp.registry.McpRuntimeWiring
import dev.dmigrate.mcp.registry.OperationalMcpRegistries
import dev.dmigrate.mcp.registry.OperationalMcpWiring
import dev.dmigrate.mcp.registry.DataOperationWorkerFactory
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
import io.kotest.matchers.string.shouldStartWith
import java.io.ByteArrayInputStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Phase F § 8.10 (F.10): Integrationstest fuer den vollstaendigen
 * Tool-Roundtrip `data_import_start` / `data_transfer_start` →
 * `job_status_get` → `job_cancel`.
 *
 * Pin't, dass die Phase-F-Start-Handler ihre Job-Records in
 * denselben Store schreiben, den `job_status_get` und `job_cancel`
 * lesen, und dass Phase-F-Datenoperationen im Default ohne explizit
 * injizierten Runner fail-closed terminieren. Dadurch wird kein
 * erfolgreicher JDBC-/Secret-Materialisierungs-Pfad simuliert, wenn
 * er im Bootstrap nicht wirklich verdrahtet ist.
 */
class McpImportTransferRoundtripIT : FunSpec({

    val tenant = Fixtures.tenant("acme")
    val now: Instant = Instant.parse("2026-05-07T12:00:00Z")
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
                displayName = connectionId,
                dialectId = "postgres",
                sensitivity = ConnectionSensitivity.NON_PRODUCTION,
                resourceUri = ServerResourceUri(tenantId, ResourceKind.CONNECTIONS, connectionId),
            ),
        )
    }

    fun operationalWiring(): OperationalMcpWiring {
        val jobStore = InMemoryJobStore()
        val idempotencyStore = InMemoryIdempotencyStore()
        val artifactStore = InMemoryArtifactStore()
        val artifactContentStore = InMemoryArtifactContentStore()
        val connectionStore = InMemoryConnectionReferenceStore()
        seedArtifact(artifactStore, artifactContentStore, tenant, "art-rt-1")
        seedConnection(connectionStore, tenant, "warehouse")
        seedConnection(connectionStore, tenant, "source-db")
        seedConnection(connectionStore, tenant, "target-db")
        val phaseC = McpRuntimeWiring(
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
        return OperationalMcpWiring(
            runtimeWiring = phaseC,
            idempotencyStore = idempotencyStore,
            jobStartTransaction = InMemoryJobStartTransaction(jobStore, idempotencyStore),
            workerHandleRegistry = InMemoryWorkerHandleRegistry(),
            approvalGrantStore = InMemoryApprovalGrantStore(),
            policyService = ConfiguredPolicyService(rules = emptyList(), defaultEffect = PolicyEffect.Allow),
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

    fun call(svc: McpServiceImpl, tool: String, args: JsonObject): JsonObject {
        val result = svc.toolsCall(ToolsCallParams(name = tool, arguments = args)).get()
        result.isError shouldBe false
        val text = result.content.first().text ?: error("expected text content")
        return JsonParser.parseString(text).asJsonObject
    }

    fun callRaw(svc: McpServiceImpl, tool: String, args: JsonObject) =
        svc.toolsCall(ToolsCallParams(name = tool, arguments = args)).get()

    test("data_import_start -> job_status_get round-trip ueber tools/call") {
        val svc = service(operationalWiring())
        val startArgs = JsonParser.parseString(
            """
            {
              "idempotencyKey": "k-rt-import-1",
              "targetConnectionRef": "dmigrate://tenants/acme/connections/warehouse",
              "artifactId": "art-rt-1",
              "table": "warehouse.events",
              "format": "csv"
            }
            """.trimIndent(),
        ).asJsonObject
        val startPayload = call(svc, "data_import_start", startArgs)
        val jobId = startPayload.get("jobId").asString
        val resourceUri = startPayload.get("resourceUri").asString
        resourceUri shouldStartWith "dmigrate://tenants/acme/jobs/"

        // Roundtrip via jobId — der Status-Handler liest aus dem
        // gemeinsamen JobStore, den auch der Start-Handler beschreibt.
        val statusByIdArgs = JsonParser.parseString("""{"jobId":"$jobId"}""").asJsonObject
        val statusById = call(svc, "job_status_get", statusByIdArgs)
        statusById.get("jobId").asString shouldBe jobId
        statusById.get("operation").asString shouldBe "data_import"
        statusById.get("resourceUri").asString shouldBe resourceUri
        statusById.get("status").asString shouldBe "FAILED"
        statusById.get("terminal").asBoolean shouldBe true
        statusById.getAsJsonObject("error").get("code").asString shouldBe
            DataOperationWorkerFactory.ERROR_CODE_DATA_RUNNER_NOT_CONFIGURED

        // Roundtrip via resourceUri (zweiter Adressierungspfad).
        val statusByUriArgs = JsonParser.parseString(
            """{"resourceUri":"$resourceUri"}""",
        ).asJsonObject
        val statusByUri = call(svc, "job_status_get", statusByUriArgs)
        statusByUri.get("jobId").asString shouldBe jobId
    }

    test("data_transfer_start -> job_status_get round-trip ueber tools/call") {
        val svc = service(operationalWiring())
        val startArgs = JsonParser.parseString(
            """
            {
              "idempotencyKey": "k-rt-transfer-1",
              "sourceConnectionRef": "dmigrate://tenants/acme/connections/source-db",
              "targetConnectionRef": "dmigrate://tenants/acme/connections/target-db",
              "tables": ["public.orders"],
              "chunkSize": 1000
            }
            """.trimIndent(),
        ).asJsonObject
        val startPayload = call(svc, "data_transfer_start", startArgs)
        val jobId = startPayload.get("jobId").asString

        val status = call(
            svc, "job_status_get",
            JsonParser.parseString("""{"jobId":"$jobId"}""").asJsonObject,
        )
        status.get("operation").asString shouldBe "data_transfer"
        status.get("status").asString shouldBe "FAILED"
        status.get("terminal").asBoolean shouldBe true
        status.getAsJsonObject("error").get("code").asString shouldBe
            DataOperationWorkerFactory.ERROR_CODE_DATA_RUNNER_NOT_CONFIGURED
    }

    test("data_import_start -> job_cancel ueber tools/call (idempotenter Replay)") {
        val w = operationalWiring()
        val svc = service(w)
        val startArgs = JsonParser.parseString(
            """
            {
              "idempotencyKey": "k-rt-cancel-import-1",
              "targetConnectionRef": "dmigrate://tenants/acme/connections/warehouse",
              "artifactId": "art-rt-1"
            }
            """.trimIndent(),
        ).asJsonObject
        val jobId = call(svc, "data_import_start", startArgs).get("jobId").asString

        // Cancel via jobId — der CancelHandler liest aus demselben
        // Store und schreibt eine Cancel-Markierung. Bei einem schon
        // terminalen fail-closed Datenjob liefert er ein replay-faehiges
        // Ergebnis ohne State-Mutation; bei
        // einem laufenden Job markiert er Cancel-Pending. Pin't, dass
        // der Roundtrip ueber tools/call durchlaeuft (kein
        // UNSUPPORTED_TOOL_OPERATION oder Validation-Fehler).
        val cancelResult = callRaw(
            svc, "job_cancel",
            JsonParser.parseString("""{"jobId":"$jobId","reason":"f10-roundtrip"}""").asJsonObject,
        )
        cancelResult.isError shouldBe false
        val cancelText = cancelResult.content.first().text ?: error("expected text content")
        val cancelJson = JsonParser.parseString(cancelText).asJsonObject
        cancelJson.get("jobId").asString shouldBe jobId
        cancelJson.get("operation").asString shouldBe "data_import"
        cancelJson.has("status") shouldBe true
        cancelJson.has("terminal") shouldBe true
        cancelJson.has("executionMeta") shouldBe true
    }

    test("Unbekannter sourceConnectionRef-Tenant -> RESOURCE_NOT_FOUND statt Cross-Tenant-Lookup") {
        // Plan-§-8.8-Akzeptanz: tenant-prefix mismatch fuer
        // Connection-Refs liefert VALIDATION_ERROR (nicht
        // RESOURCE_NOT_FOUND), sodass eine fremde Tenant-ID nicht via
        // Existenz-Test eruiert werden kann.
        val svc = service(operationalWiring())
        val args = JsonParser.parseString(
            """
            {
              "idempotencyKey": "k-rt-cross-tenant",
              "sourceConnectionRef": "dmigrate://tenants/other-tenant/connections/source-db",
              "targetConnectionRef": "dmigrate://tenants/acme/connections/target-db"
            }
            """.trimIndent(),
        ).asJsonObject
        val result = callRaw(svc, "data_transfer_start", args)
        result.isError shouldBe true
        val text = result.content.first().text ?: error("expected text content")
        text shouldContain "VALIDATION_ERROR"
        text shouldContain "tenant prefix mismatch"
    }
})
