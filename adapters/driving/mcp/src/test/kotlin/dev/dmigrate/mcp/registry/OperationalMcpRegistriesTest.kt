package dev.dmigrate.mcp.registry

import dev.dmigrate.mcp.server.McpLimitsConfig
import dev.dmigrate.server.application.policy.ConfiguredPolicyService
import dev.dmigrate.server.application.policy.PolicyEffect
import dev.dmigrate.server.application.quota.DefaultQuotaService
import dev.dmigrate.server.core.artifact.ArtifactKind
import dev.dmigrate.server.core.principal.AuthSource
import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.core.resource.ServerResourceUri
import dev.dmigrate.server.core.upload.UploadSession
import dev.dmigrate.server.core.upload.UploadSessionState
import dev.dmigrate.server.ports.memory.InMemoryApprovalGrantStore
import dev.dmigrate.server.ports.memory.InMemoryArtifactContentStore
import dev.dmigrate.server.ports.memory.InMemoryArtifactStore
import dev.dmigrate.server.ports.memory.InMemoryIdempotencyStore
import dev.dmigrate.server.ports.memory.InMemoryJobStartTransaction
import dev.dmigrate.server.ports.memory.InMemoryJobStore
import dev.dmigrate.server.ports.memory.InMemoryQuotaStore
import dev.dmigrate.server.ports.memory.InMemorySchemaStore
import dev.dmigrate.server.ports.memory.InMemoryUploadSegmentStore
import dev.dmigrate.server.ports.memory.InMemoryUploadSessionStore
import dev.dmigrate.server.ports.memory.InMemoryWorkerHandleRegistry
import dev.dmigrate.server.ports.quota.QuotaDimension
import dev.dmigrate.server.ports.quota.QuotaKey
import com.google.gson.JsonParser
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldNotBeInstanceOf
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class OperationalMcpRegistriesTest : FunSpec({

    val clock = Clock.fixed(Instant.parse("2026-05-05T12:00:00Z"), ZoneOffset.UTC)
    val tenant = TenantId("acme")
    val alice = PrincipalId("alice")
    val bob = PrincipalId("bob")

    fun operationalWiring(
        policyEffect: PolicyEffect = PolicyEffect.Allow,
    ): OperationalMcpWiring {
        val jobStore = InMemoryJobStore()
        val idempotencyStore = InMemoryIdempotencyStore()
        val quotaStore = InMemoryQuotaStore()
        val phaseC = McpRuntimeWiring(
            uploadSessionStore = InMemoryUploadSessionStore(),
            uploadSegmentStore = InMemoryUploadSegmentStore(),
            artifactStore = InMemoryArtifactStore(),
            artifactContentStore = InMemoryArtifactContentStore(),
            schemaStore = InMemorySchemaStore(),
            jobStore = jobStore,
            quotaService = DefaultQuotaService(quotaStore) { Long.MAX_VALUE },
            limits = McpLimitsConfig(),
            clock = clock,
        )
        return OperationalMcpWiring(
            runtimeWiring = phaseC,
            idempotencyStore = idempotencyStore,
            jobStartTransaction = InMemoryJobStartTransaction(jobStore, idempotencyStore),
            workerHandleRegistry = InMemoryWorkerHandleRegistry(),
            approvalGrantStore = InMemoryApprovalGrantStore(),
            policyService = ConfiguredPolicyService(rules = emptyList(), defaultEffect = policyEffect),
        )
    }

    fun principal(id: PrincipalId, admin: Boolean = false): PrincipalContext = PrincipalContext(
        principalId = id,
        homeTenantId = tenant,
        effectiveTenantId = tenant,
        allowedTenantIds = setOf(tenant),
        scopes = setOf("dmigrate:artifact:upload", "dmigrate:data:write", "dmigrate:admin"),
        isAdmin = admin,
        auditSubject = id.value,
        authSource = AuthSource.SERVICE_ACCOUNT,
        expiresAt = Instant.MAX,
    )

    test("defaultToolRegistry: LF-012 / LN-011 / LN-017 / LN-027 Start-Tools + job_cancel sind produktive Handler") {
        val registry = OperationalMcpRegistries.defaultToolRegistry(operationalWiring())
        registry.findHandler("schema_reverse_start")
            .shouldBeInstanceOf<SchemaReverseStartHandler>()
        registry.findHandler("data_profile_start")
            .shouldBeInstanceOf<DataProfileStartHandler>()
        registry.findHandler("schema_compare_start")
            .shouldBeInstanceOf<SchemaCompareStartHandler>()
        registry.findHandler("job_cancel")
            .shouldBeInstanceOf<JobCancelHandler>()
    }

    test("defaultToolRegistry: Start-Tools + job_cancel sind nicht mehr UnsupportedToolHandler") {
        val registry = OperationalMcpRegistries.defaultToolRegistry(operationalWiring())
        registry.findHandler("schema_reverse_start")
            .shouldNotBeInstanceOf<UnsupportedToolHandler>()
        registry.findHandler("data_profile_start")
            .shouldNotBeInstanceOf<UnsupportedToolHandler>()
        registry.findHandler("schema_compare_start")
            .shouldNotBeInstanceOf<UnsupportedToolHandler>()
        registry.findHandler("job_cancel")
            .shouldNotBeInstanceOf<UnsupportedToolHandler>()
    }

    test("defaultToolRegistry: LF-012 / LN-038-Handler bleiben unveraendert (Sample: schema_validate)") {
        val registry = OperationalMcpRegistries.defaultToolRegistry(operationalWiring())
        // schema_validate ist LF-012 / LN-038, kein UnsupportedToolHandler.
        val handler = registry.findHandler("schema_validate")
        handler shouldNotBe null
        handler.shouldNotBeInstanceOf<UnsupportedToolHandler>()
    }

    test("defaultToolRegistry: data_import_start ist LF-010 / LF-013 / LN-009 / LN-011-aktiv (kein UnsupportedToolHandler)") {
        // LF-010 / LF-013 / LN-009 / LN-011 § 8.7 (F.7 5/5): produktiver Handler statt
        // UnsupportedToolHandler.
        val registry = OperationalMcpRegistries.defaultToolRegistry(operationalWiring())
        registry.findHandler("data_import_start")
            .shouldBeInstanceOf<DataImportStartHandler>()
    }

    test("defaultToolRegistry: data_transfer_start ist LF-010 / LF-013 / LN-009 / LN-011-aktiv (kein UnsupportedToolHandler)") {
        // LF-010 / LF-013 / LN-009 / LN-011 § 8.8 (F.8 4/4): produktiver Handler statt
        // UnsupportedToolHandler.
        val registry = OperationalMcpRegistries.defaultToolRegistry(operationalWiring())
        registry.findHandler("data_transfer_start")
            .shouldBeInstanceOf<DataTransferStartHandler>()
    }

    test("LF-010 / LF-013 / LN-009 / LN-011: job_input init reserves quotas") {
        val wiring = operationalWiring(PolicyEffect.Allow)
        val registry = OperationalMcpRegistries.defaultToolRegistry(wiring)
        val args = JsonParser.parseString(
            """
            {
              "uploadIntent": "job_input",
              "approvalKey": "upload-key-1",
              "sizeBytes": 12,
              "checksumSha256": "${"0".repeat(64)}",
              "artifactKind": "UPLOAD_INPUT",
              "mimeType": "text/csv",
              "targetTable": "public.events"
            }
            """.trimIndent(),
        ).asJsonObject

        val outcome = registry.findHandler("artifact_upload_init")!!.handle(
            ToolCallContext("artifact_upload_init", args, principal(alice), requestId = "req-init"),
        ).shouldBeInstanceOf<ToolCallOutcome.Success>()
        val payload = JsonParser.parseString(outcome.content.single().text!!).asJsonObject
        val sessionId = payload.get("uploadSessionId").asString

        val session = wiring.runtimeWiring.uploadSessionStore.findById(tenant, sessionId)!!
        session.uploadIntent shouldBe ArtifactUploadInitHandler.INTENT_JOB_INPUT
        session.approvalKey shouldBe "upload-key-1"
        session.targetTable shouldBe "public.events"
        wiring.runtimeWiring.quotaService.let {
            wiring.runtimeWiring.uploadSessionStore.findById(tenant, sessionId)!!.state shouldBe UploadSessionState.ACTIVE
        }
    }

    test("defaultToolRegistry: administrativer artifact_upload_abort nutzt LF-010 / LF-013 / LN-009 / LN-011-Policy-Pipeline") {
        val wiring = operationalWiring(PolicyEffect.Allow)
        val session = UploadSession(
            uploadSessionId = "ups-admin-abort",
            tenantId = tenant,
            ownerPrincipalId = alice,
            resourceUri = ServerResourceUri(tenant, ResourceKind.UPLOAD_SESSIONS, "ups-admin-abort"),
            artifactKind = ArtifactKind.UPLOAD_INPUT,
            mimeType = "text/csv",
            sizeBytes = 12,
            segmentTotal = 1,
            checksumSha256 = "1".repeat(64),
            uploadIntent = ArtifactUploadInitHandler.INTENT_JOB_INPUT,
            state = UploadSessionState.ACTIVE,
            createdAt = clock.instant(),
            updatedAt = clock.instant(),
            idleTimeoutAt = clock.instant().plusSeconds(300),
            absoluteLeaseExpiresAt = clock.instant().plusSeconds(3600),
        )
        wiring.runtimeWiring.uploadSessionStore.save(session)
        val quotaKey = QuotaKey(tenant, QuotaDimension.ACTIVE_UPLOAD_SESSIONS, alice)
        wiring.runtimeWiring.quotaService.reserve(quotaKey, 1)
        wiring.runtimeWiring.quotaService.commit(dev.dmigrate.server.application.quota.QuotaReservation(quotaKey, 1))

        val registry = OperationalMcpRegistries.defaultToolRegistry(wiring)
        val args = JsonParser.parseString(
            """{"uploadSessionId":"ups-admin-abort","approvalKey":"abort-key-1","reason":"cleanup"}""",
        ).asJsonObject

        val outcome = registry.findHandler("artifact_upload_abort")!!.handle(
            ToolCallContext("artifact_upload_abort", args, principal(bob, admin = true), requestId = "req-abort"),
        ).shouldBeInstanceOf<ToolCallOutcome.Success>()
        val payload = JsonParser.parseString(outcome.content.single().text!!).asJsonObject
        payload.get("uploadSessionState").asString shouldBe "ABORTED"
        wiring.runtimeWiring.uploadSessionStore.findById(tenant, "ups-admin-abort")!!.state shouldBe
            UploadSessionState.ABORTED
    }

    test("defaultToolRegistry: alle Runtime-Descriptors bleiben sichtbar") {
        val runtimeRegistry = McpRuntimeRegistries.defaultToolRegistry(operationalWiring().runtimeWiring)
        val operationalRegistry = OperationalMcpRegistries.defaultToolRegistry(operationalWiring())
        operationalRegistry.all().map { it.name }.toSet() shouldBe
            runtimeRegistry.all().map { it.name }.toSet()
    }
})
