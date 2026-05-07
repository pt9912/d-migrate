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

class PhaseERegistriesTest : FunSpec({

    val clock = Clock.fixed(Instant.parse("2026-05-05T12:00:00Z"), ZoneOffset.UTC)
    val tenant = TenantId("acme")
    val alice = PrincipalId("alice")
    val bob = PrincipalId("bob")

    fun phaseEWiring(
        policyEffect: PolicyEffect = PolicyEffect.Allow,
    ): PhaseEWiring {
        val jobStore = InMemoryJobStore()
        val idempotencyStore = InMemoryIdempotencyStore()
        val quotaStore = InMemoryQuotaStore()
        val phaseC = PhaseCWiring(
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
        return PhaseEWiring(
            phaseCWiring = phaseC,
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

    test("defaultToolRegistry: Phase-E Start-Tools + job_cancel sind produktive Handler") {
        val registry = PhaseERegistries.defaultToolRegistry(phaseEWiring())
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
        val registry = PhaseERegistries.defaultToolRegistry(phaseEWiring())
        registry.findHandler("schema_reverse_start")
            .shouldNotBeInstanceOf<UnsupportedToolHandler>()
        registry.findHandler("data_profile_start")
            .shouldNotBeInstanceOf<UnsupportedToolHandler>()
        registry.findHandler("schema_compare_start")
            .shouldNotBeInstanceOf<UnsupportedToolHandler>()
        registry.findHandler("job_cancel")
            .shouldNotBeInstanceOf<UnsupportedToolHandler>()
    }

    test("defaultToolRegistry: Phase-C-Handler bleiben unveraendert (Sample: schema_validate)") {
        val registry = PhaseERegistries.defaultToolRegistry(phaseEWiring())
        // schema_validate ist Phase-C, kein UnsupportedToolHandler.
        val handler = registry.findHandler("schema_validate")
        handler shouldNotBe null
        handler.shouldNotBeInstanceOf<UnsupportedToolHandler>()
    }

    test("defaultToolRegistry: nicht-E/F-Tools, die noch nicht implementiert sind, bleiben Unsupported") {
        // data_export_start bleibt weiter UnsupportedToolHandler —
        // Plan §3.2 schliesst es aus dieser Phase aus.
        // `data_import_start` und `data_transfer_start` sind
        // Phase-F-aktiv (eigene Tests unten).
        val registry = PhaseERegistries.defaultToolRegistry(phaseEWiring())
        registry.findHandler("data_export_start")
            .shouldBeInstanceOf<UnsupportedToolHandler>()
    }

    test("defaultToolRegistry: data_import_start ist Phase-F-aktiv (kein UnsupportedToolHandler)") {
        // Phase F § 8.7 (F.7 5/5): produktiver Handler statt
        // UnsupportedToolHandler.
        val registry = PhaseERegistries.defaultToolRegistry(phaseEWiring())
        registry.findHandler("data_import_start")
            .shouldBeInstanceOf<DataImportStartHandler>()
    }

    test("defaultToolRegistry: data_transfer_start ist Phase-F-aktiv (kein UnsupportedToolHandler)") {
        // Phase F § 8.8 (F.8 4/4): produktiver Handler statt
        // UnsupportedToolHandler.
        val registry = PhaseERegistries.defaultToolRegistry(phaseEWiring())
        registry.findHandler("data_transfer_start")
            .shouldBeInstanceOf<DataTransferStartHandler>()
    }

    test("defaultToolRegistry: artifact_upload_init job_input nutzt Phase-F-Orchestrator und reserviert Init-Quotas") {
        val wiring = phaseEWiring(PolicyEffect.Allow)
        val registry = PhaseERegistries.defaultToolRegistry(wiring)
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

        val session = wiring.phaseCWiring.uploadSessionStore.findById(tenant, sessionId)!!
        session.uploadIntent shouldBe ArtifactUploadInitHandler.INTENT_JOB_INPUT
        session.approvalKey shouldBe "upload-key-1"
        session.targetTable shouldBe "public.events"
        wiring.phaseCWiring.quotaService.let {
            wiring.phaseCWiring.uploadSessionStore.findById(tenant, sessionId)!!.state shouldBe UploadSessionState.ACTIVE
        }
    }

    test("defaultToolRegistry: administrativer artifact_upload_abort nutzt Phase-F-Policy-Pipeline") {
        val wiring = phaseEWiring(PolicyEffect.Allow)
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
        wiring.phaseCWiring.uploadSessionStore.save(session)
        val quotaKey = QuotaKey(tenant, QuotaDimension.ACTIVE_UPLOAD_SESSIONS, alice)
        wiring.phaseCWiring.quotaService.reserve(quotaKey, 1)
        wiring.phaseCWiring.quotaService.commit(dev.dmigrate.server.application.quota.QuotaReservation(quotaKey, 1))

        val registry = PhaseERegistries.defaultToolRegistry(wiring)
        val args = JsonParser.parseString(
            """{"uploadSessionId":"ups-admin-abort","approvalKey":"abort-key-1","reason":"cleanup"}""",
        ).asJsonObject

        val outcome = registry.findHandler("artifact_upload_abort")!!.handle(
            ToolCallContext("artifact_upload_abort", args, principal(bob, admin = true), requestId = "req-abort"),
        ).shouldBeInstanceOf<ToolCallOutcome.Success>()
        val payload = JsonParser.parseString(outcome.content.single().text!!).asJsonObject
        payload.get("uploadSessionState").asString shouldBe "ABORTED"
        wiring.phaseCWiring.uploadSessionStore.findById(tenant, "ups-admin-abort")!!.state shouldBe
            UploadSessionState.ABORTED
    }

    test("defaultToolRegistry: alle Descriptors aus PhaseC bleiben sichtbar") {
        val phaseCRegistry = PhaseCRegistries.defaultToolRegistry(phaseEWiring().phaseCWiring)
        val phaseERegistry = PhaseERegistries.defaultToolRegistry(phaseEWiring())
        phaseERegistry.all().map { it.name }.toSet() shouldBe
            phaseCRegistry.all().map { it.name }.toSet()
    }
})
