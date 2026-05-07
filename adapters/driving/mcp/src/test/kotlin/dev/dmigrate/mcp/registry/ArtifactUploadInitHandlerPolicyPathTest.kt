package dev.dmigrate.mcp.registry

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.dmigrate.mcp.server.McpLimitsConfig
import dev.dmigrate.server.application.error.IdempotencyConflictException
import dev.dmigrate.server.application.error.PayloadTooLargeException
import dev.dmigrate.server.application.error.PolicyDeniedException
import dev.dmigrate.server.application.error.PolicyRequiredException
import dev.dmigrate.server.application.error.ValidationErrorException
import dev.dmigrate.server.application.fingerprint.DefaultPayloadFingerprintService
import dev.dmigrate.server.application.policy.ConfiguredPolicyService
import dev.dmigrate.server.application.policy.PolicyEffect
import dev.dmigrate.server.application.policy.PolicyService
import dev.dmigrate.server.application.quota.DefaultQuotaService
import dev.dmigrate.server.application.upload.UploadInitApprovalAttempt
import dev.dmigrate.server.application.upload.UploadInitApprovalFingerprint
import dev.dmigrate.server.application.upload.UploadInitOrchestrator
import dev.dmigrate.server.application.upload.UploadInitOutcome
import dev.dmigrate.server.application.upload.UploadInitRequest
import dev.dmigrate.server.core.artifact.ArtifactKind
import dev.dmigrate.server.core.error.ToolErrorCode
import dev.dmigrate.server.core.policy.PolicyDecision
import dev.dmigrate.server.core.principal.AuthSource
import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.upload.UploadSessionState
import dev.dmigrate.server.ports.memory.InMemoryQuotaStore
import dev.dmigrate.server.ports.memory.InMemorySyncEffectIdempotencyStore
import dev.dmigrate.server.ports.memory.InMemoryUploadInitClaimStore
import dev.dmigrate.server.ports.memory.InMemoryUploadSessionStore
import dev.dmigrate.server.ports.quota.QuotaDimension
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

/**
 * Phase F § 5.1 + § 8.3 (F.3 4/4) — pin't den policy-Init-Pfad
 * (`uploadIntent=job_input`) im MCP-Handler:
 *
 * - Allow -> Success-Envelope mit `uploadSessionId`/`ttlSeconds`.
 * - Challenge -> POLICY_REQUIRED-Envelope mit Approval-Detail-Feldern.
 * - Deny -> PolicyDeniedException -> POLICY_DENIED.
 * - InProgress (paralleler Single-Writer-Claim) -> OPERATION_TIMEOUT.
 * - IdempotencyConflict -> IdempotencyConflictException.
 * - ValidationError -> ValidationErrorException.
 * - Ohne Orchestrator faellt `job_input` weiterhin auf Phase-C
 *   POLICY_REQUIRED zurueck (Bestands-Caller unveraendert).
 * - `sizeBytes`/`expectedSizeBytes`-Alias-Konflikt -> VALIDATION_ERROR.
 */
class ArtifactUploadInitHandlerPolicyPathTest : FunSpec({

    val tenant = TenantId("acme")
    val alice = PrincipalId("alice")
    val principal = PrincipalContext(
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
    val now: Instant = Instant.parse("2026-05-06T12:00:00Z")
    val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)
    val sha256: String = "0".repeat(64)

    fun args(s: String): JsonObject = JsonParser.parseString(s).asJsonObject

    fun parseSuccess(outcome: ToolCallOutcome): JsonObject {
        val text = outcome.shouldBeInstanceOf<ToolCallOutcome.Success>()
            .content.single().text!!
        return JsonParser.parseString(text).asJsonObject
    }

    class Fixture(
        policyDefault: PolicyEffect = PolicyEffect.Allow,
        policyService: PolicyService? = null,
        wireOrchestrator: Boolean = true,
    ) {
        val syncEffectStore = InMemorySyncEffectIdempotencyStore()
        val claimStore = InMemoryUploadInitClaimStore()
        val sessionStore = InMemoryUploadSessionStore()
        val quotaStore = InMemoryQuotaStore()
        val quotaService = DefaultQuotaService(quotaStore) { Long.MAX_VALUE }
        private val sessionSeq = AtomicInteger(0)
        private val claimSeq = AtomicInteger(0)
        val orchestrator = UploadInitOrchestrator(
            syncEffectStore = syncEffectStore,
            claimStore = claimStore,
            sessionStore = sessionStore,
            policyService = policyService
                ?: ConfiguredPolicyService(rules = emptyList(), defaultEffect = policyDefault),
            approvalFingerprintService = UploadInitApprovalFingerprint(DefaultPayloadFingerprintService()),
            sessionIdFactory = { "ups-${sessionSeq.incrementAndGet()}" },
            claimIdFactory = { "claim-${claimSeq.incrementAndGet()}" },
            quotaService = quotaService,
        )
        val handler = ArtifactUploadInitHandler(
            sessionStore = sessionStore,
            quotaService = quotaService,
            limits = McpLimitsConfig(),
            options = ArtifactUploadInitHandler.Options(
                clock = clock,
                sessionIdGenerator = { "legacy-id-not-used" },
            ),
            uploadInitOrchestrator = if (wireOrchestrator) orchestrator else null,
        )
    }

    fun jobInputArgs(
        approvalKey: String = "key-1",
        size: Long = 1024,
        checksum: String = sha256,
        targetTable: String? = "warehouse.events",
        artifactKind: String = "UPLOAD_INPUT",
        mimeType: String = "application/json",
    ): JsonObject {
        val body = buildString {
            append("""{"uploadIntent":"job_input"""")
            append(""","approvalKey":"$approvalKey"""")
            append(""","sizeBytes":$size""")
            append(""","checksumSha256":"$checksum"""")
            append(""","artifactKind":"$artifactKind"""")
            append(""","mimeType":"$mimeType"""")
            if (targetTable != null) append(""","targetTable":"$targetTable"""")
            append("}")
        }
        return args(body)
    }

    test("Allow + job_input -> Success mit uploadSessionId; Session traegt approvalKey/Fingerprint/targetTable durabel") {
        val fx = Fixture(policyDefault = PolicyEffect.Allow)
        val outcome = fx.handler.handle(
            ToolCallContext(
                "artifact_upload_init",
                jobInputArgs(approvalKey = "key-allow", targetTable = "warehouse.events"),
                principal,
                requestId = "req-allow",
            ),
        )
        val payload = parseSuccess(outcome)
        payload.get("uploadSessionId").asString shouldBe "ups-1"
        payload.get("expectedFirstSegmentIndex").asInt shouldBe 1
        payload.get("expectedFirstSegmentOffset").asLong shouldBe 0L
        payload.getAsJsonObject("executionMeta").get("requestId").asString shouldBe "req-allow"

        val session = fx.sessionStore.findById(tenant, "ups-1")!!
        session.state shouldBe UploadSessionState.ACTIVE
        session.uploadIntent shouldBe ArtifactUploadInitHandler.INTENT_JOB_INPUT
        session.approvalKey shouldBe "key-allow"
        session.targetTable shouldBe "warehouse.events"
        session.checksumSha256 shouldBe sha256
        session.sizeBytes shouldBe 1024L
        fx.quotaStore.current(
            dev.dmigrate.server.ports.quota.QuotaKey(tenant, QuotaDimension.ACTIVE_UPLOAD_SESSIONS, alice),
        ) shouldBe 1L
        fx.quotaStore.current(
            dev.dmigrate.server.ports.quota.QuotaKey(tenant, QuotaDimension.UPLOAD_BYTES, alice),
        ) shouldBe 1024L
    }

    test("Identischer Retry liefert AlreadyInitialized als Success mit derselben uploadSessionId") {
        val fx = Fixture(policyDefault = PolicyEffect.Allow)
        val first = parseSuccess(
            fx.handler.handle(ToolCallContext("artifact_upload_init", jobInputArgs(), principal)),
        ).get("uploadSessionId").asString

        val second = parseSuccess(
            fx.handler.handle(ToolCallContext("artifact_upload_init", jobInputArgs(), principal)),
        ).get("uploadSessionId").asString
        second shouldBe first
    }

    test("Challenge -> POLICY_REQUIRED-Envelope mit Approval-Detail-Feldern, KEINE Session") {
        val fx = Fixture(
            policyDefault = PolicyEffect.Challenge(
                requiredScopes = setOf("dmigrate:artifact:upload"),
                reasons = listOf("policy:job-input-needs-approval"),
            ),
        )
        val outcome = fx.handler.handle(
            ToolCallContext(
                "artifact_upload_init",
                jobInputArgs(approvalKey = "key-challenge"),
                principal,
                requestId = "req-challenge",
            ),
        )
        val error = outcome.shouldBeInstanceOf<ToolCallOutcome.Error>()
        error.envelope.code shouldBe ToolErrorCode.POLICY_REQUIRED
        error.envelope.requestId shouldBe "req-challenge"
        val detailKeys = error.envelope.details.map { it.key }
        detailKeys shouldContain "policyName"
        detailKeys shouldContain "approvalRequestId"
        detailKeys shouldContain "correlationKind"
        detailKeys shouldContain "correlationKey"
        detailKeys shouldContain "requiredScopes"
        detailKeys shouldContain "reasons"
        error.envelope.details.first { it.key == "policyName" }.value shouldBe "upload_intent.job_input"
        error.envelope.details.first { it.key == "requiredScopes" }.value shouldContain
            "dmigrate:artifact:upload"

        // Plan § 8.3: KEINE Session, KEINE Quota.
        fx.sessionStore.findById(tenant, "ups-1") shouldBe null
        fx.quotaStore.current(
            dev.dmigrate.server.ports.quota.QuotaKey(tenant, QuotaDimension.ACTIVE_UPLOAD_SESSIONS, alice),
        ) shouldBe 0L
    }

    test("Deny -> PolicyDeniedException mit reasonCode") {
        val fx = Fixture(policyDefault = PolicyEffect.Deny(reasonCode = "policy:no-job-input"))
        val ex = shouldThrow<PolicyDeniedException> {
            fx.handler.handle(
                ToolCallContext("artifact_upload_init", jobInputArgs(approvalKey = "key-deny"), principal),
            )
        }
        ex.policyName shouldBe "upload_intent.job_input"
        ex.reason shouldBe "policy:no-job-input"
    }

    test("Abweichender Payload mit gleichem approvalKey -> IdempotencyConflictException") {
        val fx = Fixture(policyDefault = PolicyEffect.Allow)
        fx.handler.handle(ToolCallContext("artifact_upload_init", jobInputArgs(size = 1024), principal))
        shouldThrow<IdempotencyConflictException> {
            fx.handler.handle(ToolCallContext("artifact_upload_init", jobInputArgs(size = 9999), principal))
        }
    }

    test("InProgress (paralleler aktiver Claim) -> OPERATION_TIMEOUT-Envelope") {
        // Ein Sync-Effect-Reserve, der weder Existing noch Conflict
        // liefert, sondern Reserved — dann blockiert ein vorheriger
        // Claim die zweite Pipeline auf demselben Scope. Wir simulieren
        // das, indem der claimStore einen vorhandenen, gueltig-leasten
        // Claim haelt, bevor der Handler den zweiten Versuch faehrt.
        val fx = Fixture(policyDefault = PolicyEffect.Allow)
        // Erster Aufruf reserviert SyncEffect + acquired Claim und
        // committed durabel ein Outcome — dadurch wuerde der zweite
        // Aufruf AlreadyInitialized sehen. Stattdessen blockieren wir
        // den Claim manuell auf einem anderen Approval-Key, dann
        // betreten wir die Pipeline mit einem frischen Approval-Key,
        // dessen syncEffect-Reservierung "Reserved" ist, und einem
        // bereits aktiven Fremd-Claim — geht nur, indem wir den Claim
        // direkt ueber den Store blockieren.
        val claimScope = dev.dmigrate.server.ports.UploadInitClaimScope(
            tenantId = tenant,
            callerId = alice,
            toolName = "artifact_upload_init",
            approvalKey = "key-progress",
        )
        // Wir berechnen den erwarteten Fingerprint manuell, sonst
        // produziert der zweite Aufruf einen Conflict statt InProgress.
        val fingerprintService = UploadInitApprovalFingerprint(DefaultPayloadFingerprintService())
        val expectedFingerprint = fingerprintService.fingerprint(
            UploadInitApprovalAttempt(
                tenantId = tenant,
                callerId = alice,
                artifactKind = ArtifactKind.UPLOAD_INPUT,
                mimeType = "application/json",
                sizeBytes = 1024,
                checksumSha256 = sha256,
                uploadIntent = "job_input",
                targetTable = "warehouse.events",
                wireArtifactKind = "seed-data",
            ),
        )
        // Pre-populate: aktiver Claim eines anderen Owners auf demselben
        // SyncEffect-Scope + identischen Fingerprint.
        fx.claimStore.acquire(
            scope = claimScope,
            payloadFingerprint = expectedFingerprint,
            claimId = "claim-other",
            leaseExpiresAt = now.plusSeconds(60),
            now = now.minusSeconds(1),
        )

        val outcome = fx.handler.handle(
            ToolCallContext(
                "artifact_upload_init",
                jobInputArgs(approvalKey = "key-progress"),
                principal,
                requestId = "req-progress",
            ),
        )
        val error = outcome.shouldBeInstanceOf<ToolCallOutcome.Error>()
        error.envelope.code shouldBe ToolErrorCode.OPERATION_TIMEOUT
        error.envelope.details.map { it.key } shouldContain "claimLeaseExpiresAt"
    }

    test("Fehlender approvalKey fuer job_input -> ValidationErrorException(approvalKey)") {
        val fx = Fixture()
        val ex = shouldThrow<ValidationErrorException> {
            fx.handler.handle(
                ToolCallContext(
                    "artifact_upload_init",
                    args("""{"uploadIntent":"job_input","sizeBytes":1024,"checksumSha256":"$sha256"}"""),
                    principal,
                ),
            )
        }
        ex.violations.map { it.field } shouldContain "approvalKey"
    }

    test("Widersprechende sizeBytes/expectedSizeBytes-Doppelwerte -> VALIDATION_ERROR") {
        val fx = Fixture()
        val ex = shouldThrow<ValidationErrorException> {
            fx.handler.handle(
                ToolCallContext(
                    "artifact_upload_init",
                    args(
                        """{"uploadIntent":"job_input","approvalKey":"key-x",""" +
                            """"sizeBytes":1024,"expectedSizeBytes":2048,"checksumSha256":"$sha256"}""",
                    ),
                    principal,
                ),
            )
        }
        ex.violations.map { it.field } shouldContain "expectedSizeBytes"
    }

    test("Legacy-Alias `expectedSizeBytes` allein wird fuer job_input akzeptiert (additiv)") {
        val fx = Fixture(policyDefault = PolicyEffect.Allow)
        val outcome = fx.handler.handle(
            ToolCallContext(
                "artifact_upload_init",
                args(
                    """{"uploadIntent":"job_input","approvalKey":"key-alias",""" +
                        """"expectedSizeBytes":1024,"checksumSha256":"$sha256",""" +
                        """"artifactKind":"UPLOAD_INPUT"}""",
                ),
                principal,
            ),
        )
        parseSuccess(outcome).get("uploadSessionId").asString shouldBe "ups-1"
        fx.sessionStore.findById(tenant, "ups-1")!!.sizeBytes shouldBe 1024L
    }

    test("sizeBytes ueber maxArtifactUploadBytes -> PayloadTooLargeException") {
        val fx = Fixture(policyDefault = PolicyEffect.Allow)
        val handler = ArtifactUploadInitHandler(
            sessionStore = fx.sessionStore,
            quotaService = DefaultQuotaService(fx.quotaStore) { Long.MAX_VALUE },
            limits = McpLimitsConfig(maxArtifactUploadBytes = 1024),
            options = ArtifactUploadInitHandler.Options(clock = clock),
            uploadInitOrchestrator = fx.orchestrator,
        )
        val ex = shouldThrow<PayloadTooLargeException> {
            handler.handle(
                ToolCallContext("artifact_upload_init", jobInputArgs(size = 2048), principal),
            )
        }
        ex.maxBytes shouldBe 1024L
    }

    test("Negative sizeBytes -> VALIDATION_ERROR(sizeBytes)") {
        val fx = Fixture()
        val ex = shouldThrow<ValidationErrorException> {
            fx.handler.handle(
                ToolCallContext(
                    "artifact_upload_init",
                    args(
                        """{"uploadIntent":"job_input","approvalKey":"key-neg",""" +
                            """"sizeBytes":-1,"checksumSha256":"$sha256"}""",
                    ),
                    principal,
                ),
            )
        }
        ex.violations.map { it.field } shouldContain "sizeBytes"
    }

    test("sizeBytes=0 mit job_input ist gueltig (Single-Empty-Segment-Upload)") {
        val fx = Fixture(policyDefault = PolicyEffect.Allow)
        val empty = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        val outcome = fx.handler.handle(
            ToolCallContext(
                "artifact_upload_init",
                args(
                    """{"uploadIntent":"job_input","approvalKey":"key-empty",""" +
                        """"sizeBytes":0,"checksumSha256":"$empty","artifactKind":"UPLOAD_INPUT"}""",
                ),
                principal,
            ),
        )
        parseSuccess(outcome).get("uploadSessionId").asString shouldBe "ups-1"
    }

    test("F.4 (2/3): sizeBytes=0 + artifactKind=SCHEMA -> VALIDATION_ERROR (kein leeres Schema)") {
        val fx = Fixture(policyDefault = PolicyEffect.Allow)
        val empty = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        val ex = shouldThrow<ValidationErrorException> {
            fx.handler.handle(
                ToolCallContext(
                    "artifact_upload_init",
                    args(
                        """{"uploadIntent":"job_input","approvalKey":"key-empty-schema",""" +
                            """"sizeBytes":0,"checksumSha256":"$empty","artifactKind":"SCHEMA"}""",
                    ),
                    principal,
                ),
            )
        }
        ex.violations.map { it.field } shouldContain "sizeBytes"
        // Plan § 8.4: keine Session entsteht (Pre-Store-Validation).
        fx.sessionStore.findById(tenant, "ups-1") shouldBe null
    }

    test("Ungueltiger artifactKind -> VALIDATION_ERROR(artifactKind)") {
        val fx = Fixture()
        val ex = shouldThrow<ValidationErrorException> {
            fx.handler.handle(
                ToolCallContext(
                    "artifact_upload_init",
                    jobInputArgs(artifactKind = "NOT_A_KIND"),
                    principal,
                ),
            )
        }
        ex.violations.map { it.field } shouldContain "artifactKind"
    }

    test("Ohne Orchestrator faellt job_input weiterhin auf Phase-C POLICY_REQUIRED zurueck") {
        val fx = Fixture(wireOrchestrator = false)
        val ex = shouldThrow<PolicyRequiredException> {
            fx.handler.handle(
                ToolCallContext("artifact_upload_init", jobInputArgs(), principal),
            )
        }
        ex.policyName shouldBe "upload_intent.job_input"
    }

    test("schema_staging_readonly mit gewireten Orchestrator faellt unveraendert auf Legacy-Pfad") {
        val fx = Fixture(policyDefault = PolicyEffect.Allow)
        val handler = ArtifactUploadInitHandler(
            sessionStore = fx.sessionStore,
            quotaService = DefaultQuotaService(fx.quotaStore) { Long.MAX_VALUE },
            limits = McpLimitsConfig(),
            options = ArtifactUploadInitHandler.Options(
                clock = clock,
                sessionIdGenerator = { "ups-readonly" },
            ),
            uploadInitOrchestrator = fx.orchestrator,
        )
        val outcome = handler.handle(
            ToolCallContext(
                "artifact_upload_init",
                args(
                    """{"uploadIntent":"schema_staging_readonly",""" +
                        """"expectedSizeBytes":1024,"checksumSha256":"$sha256"}""",
                ),
                principal,
            ),
        )
        parseSuccess(outcome).get("uploadSessionId").asString shouldBe "ups-readonly"
        // Legacy-Pfad: Bestands-Session ohne approvalKey/Fingerprint.
        val session = fx.sessionStore.findById(tenant, "ups-readonly")!!
        session.approvalKey shouldBe null
        session.approvalFingerprint shouldBe null
    }

    test("Custom PolicyService (nicht ConfiguredPolicyService) propagiert reasonCode bis ins POLICY_DENIED") {
        val customPolicy = PolicyService { _ -> PolicyDecision.Denied(reasonCode = "policy:custom-deny") }
        val fx = Fixture(policyService = customPolicy)
        val ex = shouldThrow<PolicyDeniedException> {
            fx.handler.handle(
                ToolCallContext("artifact_upload_init", jobInputArgs(approvalKey = "key-custom"), principal),
            )
        }
        ex.reason shouldBe "policy:custom-deny"
    }
})
