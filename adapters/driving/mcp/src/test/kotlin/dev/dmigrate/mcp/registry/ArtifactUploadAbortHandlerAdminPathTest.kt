package dev.dmigrate.mcp.registry

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.dmigrate.server.application.error.ForbiddenPrincipalException
import dev.dmigrate.server.application.error.IdempotencyConflictException
import dev.dmigrate.server.application.error.PolicyDeniedException
import dev.dmigrate.server.application.error.PolicyRequiredException
import dev.dmigrate.server.application.error.ValidationErrorException
import dev.dmigrate.server.application.fingerprint.DefaultPayloadFingerprintService
import dev.dmigrate.server.application.policy.ConfiguredPolicyService
import dev.dmigrate.server.application.policy.PolicyEffect
import dev.dmigrate.server.application.policy.PolicyService
import dev.dmigrate.server.application.quota.DefaultQuotaService
import dev.dmigrate.server.application.upload.AbortApprovalFingerprint
import dev.dmigrate.server.core.artifact.ArtifactKind
import dev.dmigrate.server.core.policy.PolicyDecision
import dev.dmigrate.server.core.principal.AuthSource
import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.core.resource.ServerResourceUri
import dev.dmigrate.server.core.upload.UploadSession
import dev.dmigrate.server.core.upload.UploadSessionState
import dev.dmigrate.server.ports.memory.InMemoryAbortOutcomeStore
import dev.dmigrate.server.ports.memory.InMemoryQuotaStore
import dev.dmigrate.server.ports.memory.InMemorySyncEffectIdempotencyStore
import dev.dmigrate.server.ports.memory.InMemoryUploadSegmentStore
import dev.dmigrate.server.ports.memory.InMemoryUploadSessionStore
import dev.dmigrate.server.ports.quota.QuotaDimension
import dev.dmigrate.server.ports.quota.QuotaKey
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Phase F § 5.3 + § 8.6 (F.6 3/3) — pin't den administrativen
 * `artifact_upload_abort`-Pfad ueber den Handler:
 *
 * - Owner-Self-Abort: Phase-C-Verhalten unveraendert.
 * - Cross-Principal ohne Admin-Pipeline: Forbidden (Bestands-Compat).
 * - Cross-Principal mit Pipeline + Allow-Policy: Abort durch + Quotas
 *   freigegeben + AbortOutcome durabel.
 * - Cross-Principal mit Pipeline + Challenge: POLICY_REQUIRED.
 * - Cross-Principal mit Pipeline + Deny: POLICY_DENIED.
 * - Fehlender approvalKey: VALIDATION_ERROR.
 * - Replay-Idempotenz mit gleichem Fingerprint -> selber Outcome.
 * - Replay mit abweichendem `reason` -> IDEMPOTENCY_CONFLICT.
 */
class ArtifactUploadAbortHandlerAdminPathTest : FunSpec({

    val tenant = TenantId("acme")
    val alice = PrincipalId("alice") // session owner
    val opsAdmin = PrincipalId("ops-admin")
    val now: Instant = Instant.parse("2026-05-06T12:00:00Z")
    val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)

    fun adminPrincipal(scopes: Set<String> = setOf("dmigrate:artifact:upload")) = PrincipalContext(
        principalId = opsAdmin,
        homeTenantId = tenant,
        effectiveTenantId = tenant,
        allowedTenantIds = setOf(tenant),
        scopes = scopes,
        isAdmin = true,
        auditSubject = "ops-admin",
        authSource = AuthSource.SERVICE_ACCOUNT,
        expiresAt = Instant.MAX,
    )

    class Fixture(
        policyDefault: PolicyEffect = PolicyEffect.Allow,
        policyService: PolicyService? = null,
        wireAdminPipeline: Boolean = true,
        sessionId: String = "ups-1",
        sizeBytes: Long = 1024,
    ) {
        val sessionStore = InMemoryUploadSessionStore()
        val segmentStore = InMemoryUploadSegmentStore()
        val quotaStore = InMemoryQuotaStore()
        val syncEffectStore = InMemorySyncEffectIdempotencyStore()
        val abortOutcomeStore = InMemoryAbortOutcomeStore()
        private val quota = DefaultQuotaService(quotaStore) { Long.MAX_VALUE }
        val sessionsKey = QuotaKey(tenant, QuotaDimension.ACTIVE_UPLOAD_SESSIONS, alice)
        val bytesKey = QuotaKey(tenant, QuotaDimension.UPLOAD_BYTES, alice)

        init {
            // Init-Reservierungen pro Hand simulieren — der Admin-
            // Abort muss diese genau wieder freigeben (auf Owner alice,
            // NICHT auf Admin opsAdmin).
            quota.reserve(sessionsKey, amount = 1)
            quota.reserve(bytesKey, amount = sizeBytes)
            sessionStore.save(
                UploadSession(
                    uploadSessionId = sessionId,
                    tenantId = tenant,
                    ownerPrincipalId = alice,
                    resourceUri = ServerResourceUri(tenant, ResourceKind.UPLOAD_SESSIONS, sessionId),
                    artifactKind = ArtifactKind.UPLOAD_INPUT,
                    mimeType = "text/csv",
                    sizeBytes = sizeBytes,
                    segmentTotal = 1,
                    checksumSha256 = "deadbeef".repeat(8),
                    uploadIntent = ArtifactUploadInitHandler.INTENT_JOB_INPUT,
                    state = UploadSessionState.ACTIVE,
                    createdAt = now,
                    updatedAt = now,
                    idleTimeoutAt = now.plusSeconds(300),
                    absoluteLeaseExpiresAt = now.plusSeconds(3600),
                ),
            )
        }

        private val pipeline = if (wireAdminPipeline) {
            AdministrativeAbortPipeline(
                sessionStore = sessionStore,
                segmentStore = segmentStore,
                quotaService = quota,
                syncEffectStore = syncEffectStore,
                abortOutcomeStore = abortOutcomeStore,
                abortApprovalFingerprint = AbortApprovalFingerprint(DefaultPayloadFingerprintService()),
                policyService = policyService
                    ?: ConfiguredPolicyService(rules = emptyList(), defaultEffect = policyDefault),
                clock = clock,
            )
        } else {
            null
        }

        val handler = ArtifactUploadAbortHandler(
            sessionStore = sessionStore,
            segmentStore = segmentStore,
            quotaService = quota,
            clock = clock,
            administrativeAbortPipeline = pipeline,
        )
    }

    fun args(s: String): JsonObject = JsonParser.parseString(s).asJsonObject

    fun parsePayload(outcome: ToolCallOutcome): JsonObject {
        val text = outcome.shouldBeInstanceOf<ToolCallOutcome.Success>().content.single().text!!
        return JsonParser.parseString(text).asJsonObject
    }

    test("Cross-Principal ohne wired Admin-Pipeline -> ForbiddenPrincipalException (Bestands-Compat)") {
        val fx = Fixture(wireAdminPipeline = false)
        shouldThrow<ForbiddenPrincipalException> {
            fx.handler.handle(
                ToolCallContext(
                    "artifact_upload_abort",
                    args("""{"uploadSessionId":"ups-1","reason":"ops","approvalKey":"key-1"}"""),
                    adminPrincipal(),
                ),
            )
        }
    }

    test("Cross-Principal mit Pipeline + Allow -> ABORTED, Quotas freigegeben (auf Owner-Schluessel)") {
        val fx = Fixture(policyDefault = PolicyEffect.Allow)
        val outcome = fx.handler.handle(
            ToolCallContext(
                "artifact_upload_abort",
                args("""{"uploadSessionId":"ups-1","reason":"ops-cleanup","approvalKey":"key-allow"}"""),
                adminPrincipal(),
            ),
        )
        val payload = parsePayload(outcome)
        payload.get("uploadSessionState").asString shouldBe "ABORTED"
        payload.get("preAbortState").asString shouldBe "ACTIVE"
        payload.get("quotaReleased").asBoolean shouldBe true
        payload.get("reason").asString shouldBe "ops-cleanup"

        // Plan § 5.3: ABORTED + Cleanup + Quota-Release durabel.
        fx.sessionStore.findById(tenant, "ups-1")!!.state shouldBe UploadSessionState.ABORTED
        fx.quotaStore.current(fx.sessionsKey) shouldBe 0L
        fx.quotaStore.current(fx.bytesKey) shouldBe 0L
    }

    test("Cross-Principal mit Challenge-Policy -> PolicyRequiredException, KEINE State-Aenderung") {
        val fx = Fixture(
            policyDefault = PolicyEffect.Challenge(
                requiredScopes = setOf("dmigrate:admin"),
                reasons = listOf("policy:cross-principal-abort-needs-grant"),
            ),
        )
        shouldThrow<PolicyRequiredException> {
            fx.handler.handle(
                ToolCallContext(
                    "artifact_upload_abort",
                    args("""{"uploadSessionId":"ups-1","approvalKey":"key-need-approval"}"""),
                    adminPrincipal(),
                ),
            )
        }
        // Plan: KEIN Abort, KEIN Quota-Release.
        fx.sessionStore.findById(tenant, "ups-1")!!.state shouldBe UploadSessionState.ACTIVE
        fx.quotaStore.current(fx.sessionsKey) shouldBe 1L
    }

    test("Cross-Principal mit Deny-Policy -> PolicyDeniedException") {
        val fx = Fixture(policyDefault = PolicyEffect.Deny(reasonCode = "policy:no-admin-abort"))
        val ex = shouldThrow<PolicyDeniedException> {
            fx.handler.handle(
                ToolCallContext(
                    "artifact_upload_abort",
                    args("""{"uploadSessionId":"ups-1","approvalKey":"key-deny"}"""),
                    adminPrincipal(),
                ),
            )
        }
        ex.reason shouldBe "policy:no-admin-abort"
        fx.sessionStore.findById(tenant, "ups-1")!!.state shouldBe UploadSessionState.ACTIVE
    }

    test("Cross-Principal ohne approvalKey -> VALIDATION_ERROR(approvalKey)") {
        val fx = Fixture()
        val ex = shouldThrow<ValidationErrorException> {
            fx.handler.handle(
                ToolCallContext(
                    "artifact_upload_abort",
                    args("""{"uploadSessionId":"ups-1","reason":"forgot-key"}"""),
                    adminPrincipal(),
                ),
            )
        }
        ex.violations.map { it.field } shouldBe listOf("approvalKey")
    }

    test("Replay desselben admin-Aborts ist idempotent (gleicher Fingerprint -> selber Outcome)") {
        val fx = Fixture(policyDefault = PolicyEffect.Allow)
        val first = parsePayload(
            fx.handler.handle(
                ToolCallContext(
                    "artifact_upload_abort",
                    args("""{"uploadSessionId":"ups-1","reason":"ops","approvalKey":"key-replay"}"""),
                    adminPrincipal(),
                ),
            ),
        )
        first.get("uploadSessionState").asString shouldBe "ABORTED"

        val second = parsePayload(
            fx.handler.handle(
                ToolCallContext(
                    "artifact_upload_abort",
                    args("""{"uploadSessionId":"ups-1","reason":"ops","approvalKey":"key-replay"}"""),
                    adminPrincipal(),
                ),
            ),
        )
        // Plan § 5.3: identischer Outcome bei identischem Fingerprint.
        second.get("uploadSessionState").asString shouldBe "ABORTED"
        second.get("preAbortState").asString shouldBe first.get("preAbortState").asString
        second.get("quotaReleased").asBoolean shouldBe true
    }

    test("Replay mit abweichendem reason -> IDEMPOTENCY_CONFLICT") {
        val fx = Fixture(policyDefault = PolicyEffect.Allow)
        fx.handler.handle(
            ToolCallContext(
                "artifact_upload_abort",
                args("""{"uploadSessionId":"ups-1","reason":"ops","approvalKey":"key-conflict"}"""),
                adminPrincipal(),
            ),
        )
        // Plan § 5.3: anderer reason -> IDEMPOTENCY_CONFLICT statt
        // altem Outcome.
        shouldThrow<IdempotencyConflictException> {
            fx.handler.handle(
                ToolCallContext(
                    "artifact_upload_abort",
                    args("""{"uploadSessionId":"ups-1","reason":"DIFFERENT","approvalKey":"key-conflict"}"""),
                    adminPrincipal(),
                ),
            )
        }
    }

    test("Owner-Self-Abort bleibt unveraendert (Phase C)") {
        val fx = Fixture(policyDefault = PolicyEffect.Allow)
        val ownerPrincipal = PrincipalContext(
            principalId = alice,
            homeTenantId = tenant,
            effectiveTenantId = tenant,
            allowedTenantIds = setOf(tenant),
            scopes = setOf("dmigrate:artifact:upload"),
            isAdmin = false,
            auditSubject = "alice",
            authSource = AuthSource.SERVICE_ACCOUNT,
            expiresAt = Instant.MAX,
        )
        val outcome = fx.handler.handle(
            ToolCallContext(
                "artifact_upload_abort",
                args("""{"uploadSessionId":"ups-1"}"""),
                ownerPrincipal,
            ),
        )
        val payload = parsePayload(outcome)
        // Owner-Self-Abort schickt segmentsDeleted-Feld zurueck (Phase C);
        // der Admin-Pfad nutzt das preAbortState/quotaReleased-Schema.
        payload.has("segmentsDeleted") shouldBe true
        payload.has("preAbortState") shouldBe false
    }

    test("Custom PolicyService propagiert reasonCode bis ins POLICY_DENIED") {
        val customPolicy = PolicyService { _ -> PolicyDecision.Denied(reasonCode = "policy:custom-no-abort") }
        val fx = Fixture(policyService = customPolicy)
        val ex = shouldThrow<PolicyDeniedException> {
            fx.handler.handle(
                ToolCallContext(
                    "artifact_upload_abort",
                    args("""{"uploadSessionId":"ups-1","approvalKey":"key-custom"}"""),
                    adminPrincipal(),
                ),
            )
        }
        ex.reason shouldBe "policy:custom-no-abort"
    }
})
