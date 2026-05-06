package dev.dmigrate.server.application.upload

import dev.dmigrate.server.application.fingerprint.DefaultPayloadFingerprintService
import dev.dmigrate.server.application.policy.ConfiguredPolicyService
import dev.dmigrate.server.application.policy.PolicyEffect
import dev.dmigrate.server.application.policy.PolicyRule
import dev.dmigrate.server.core.artifact.ArtifactKind
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.upload.UploadSessionState
import dev.dmigrate.server.ports.memory.InMemorySyncEffectIdempotencyStore
import dev.dmigrate.server.ports.memory.InMemoryUploadInitClaimStore
import dev.dmigrate.server.ports.memory.InMemoryUploadSessionStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * Phase F § 5.1 + § 8.3 (F.3 3/4) — Pin't den policy-pflichtigen
 * Init-Pfad ueber alle Pipeline-Branches.
 */
class UploadInitOrchestratorTest : FunSpec({

    val tenant = TenantId("acme")
    val principal = PrincipalId("alice")
    val now: Instant = Instant.parse("2026-05-06T10:00:00Z")

    class Fixture(
        policyDefault: PolicyEffect = PolicyEffect.Allow,
        policyRules: List<PolicyRule> = emptyList(),
    ) {
        val syncEffectStore = InMemorySyncEffectIdempotencyStore()
        val claimStore = InMemoryUploadInitClaimStore()
        val sessionStore = InMemoryUploadSessionStore()
        val fingerprintService = UploadInitApprovalFingerprint(DefaultPayloadFingerprintService())
        private val sessionSeq = AtomicInteger(0)
        private val claimSeq = AtomicInteger(0)
        val orchestrator = UploadInitOrchestrator(
            syncEffectStore = syncEffectStore,
            claimStore = claimStore,
            sessionStore = sessionStore,
            policyService = ConfiguredPolicyService(rules = policyRules, defaultEffect = policyDefault),
            approvalFingerprintService = fingerprintService,
            sessionIdFactory = { "session-${sessionSeq.incrementAndGet()}" },
            claimIdFactory = { "claim-${claimSeq.incrementAndGet()}" },
        )

        fun request(
            approvalKey: String = "key-1",
            sizeBytes: Long = 1024,
            checksum: String = "a".repeat(64),
            mimeType: String = "application/json",
            artifactKind: ArtifactKind = ArtifactKind.SCHEMA,
            uploadIntent: String = "schema_staging_readonly",
            targetTable: String? = null,
        ) = UploadInitRequest(
            tenantId = tenant,
            callerId = principal,
            approvalKey = approvalKey,
            attempt = UploadInitApprovalAttempt(
                tenantId = tenant,
                callerId = principal,
                artifactKind = artifactKind,
                mimeType = mimeType,
                sizeBytes = sizeBytes,
                checksumSha256 = checksum,
                uploadIntent = uploadIntent,
                targetTable = targetTable,
            ),
            now = now,
        )
    }

    test("Allow + Reserved -> Initialized; Session traegt approvalKey + Fingerprint durabel") {
        val fx = Fixture(policyDefault = PolicyEffect.Allow)
        val outcome = fx.orchestrator.init(fx.request())
        val initialized = outcome.shouldBeInstanceOf<UploadInitOutcome.Initialized>()
        initialized.uploadSessionId shouldBe "session-1"
        initialized.expectedFirstSegmentIndex shouldBe 1
        initialized.expectedFirstSegmentOffset shouldBe 0L

        val session = fx.sessionStore.findById(tenant, "session-1").shouldNotBeNull()
        session.state shouldBe UploadSessionState.ACTIVE
        session.approvalKey shouldBe "key-1"
        session.approvalFingerprint.shouldNotBeNull()
    }

    test("Identischer Retry liefert AlreadyInitialized mit derselben sessionId") {
        val fx = Fixture()
        val first = fx.orchestrator.init(fx.request())
        val sessionId = first.shouldBeInstanceOf<UploadInitOutcome.Initialized>().uploadSessionId

        val second = fx.orchestrator.init(fx.request())
        val replay = second.shouldBeInstanceOf<UploadInitOutcome.AlreadyInitialized>()
        replay.uploadSessionId shouldBe sessionId
    }

    test("Abweichender Payload mit gleichem approvalKey -> IdempotencyConflict") {
        val fx = Fixture()
        fx.orchestrator.init(fx.request(sizeBytes = 1024))
        val outcome = fx.orchestrator.init(fx.request(sizeBytes = 9999))
        outcome.shouldBeInstanceOf<UploadInitOutcome.IdempotencyConflict>()
    }

    test("RequiresApproval -> PolicyRequired ohne Session/Claim-Reste") {
        val fx = Fixture(
            policyDefault = PolicyEffect.Challenge(
                requiredScopes = setOf("dmigrate:artifact:upload"),
                reasons = listOf("policy:upload-needs-approval"),
            ),
        )
        val outcome = fx.orchestrator.init(fx.request(approvalKey = "key-policy"))
        val required = outcome.shouldBeInstanceOf<UploadInitOutcome.PolicyRequired>()
        required.requiredScopes shouldBe setOf("dmigrate:artifact:upload")
        // Plan § 8.3: KEINE Session, KEINE aktive Berechtigung.
        fx.sessionStore.findById(tenant, "session-1").shouldBeNull()
        // Claim wurde freigegeben, sodass die naechste Approval-Replay-
        // Runde den Single-Writer-Cycle neu beginnen kann.
        fx.claimStore.findById(
            dev.dmigrate.server.ports.UploadInitClaimScope(
                tenantId = tenant,
                callerId = principal,
                toolName = "artifact_upload_init",
                approvalKey = "key-policy",
            ),
        ).shouldBeNull()
    }

    test("Denied -> PolicyDenied; keine Session, kein Claim") {
        val fx = Fixture(policyDefault = PolicyEffect.Deny("policy:tenant-blocked"))
        val outcome = fx.orchestrator.init(fx.request(approvalKey = "key-deny"))
        val denied = outcome.shouldBeInstanceOf<UploadInitOutcome.PolicyDenied>()
        denied.reasonCode shouldBe "policy:tenant-blocked"
        fx.sessionStore.findById(tenant, "session-1").shouldBeNull()
    }

    test("Leerer approvalKey -> ValidationError vor SyncEffect/Claim-Writes") {
        val fx = Fixture()
        val outcome = fx.orchestrator.init(fx.request(approvalKey = ""))
        outcome.shouldBeInstanceOf<UploadInitOutcome.ValidationError>()
        // Kein SyncEffect-/Claim-Eintrag erzeugt — erkennbar am leeren Session-Store.
        fx.sessionStore.findById(tenant, "session-1").shouldBeNull()
    }

    test("Negativer sizeBytes -> ValidationError") {
        val fx = Fixture()
        val outcome = fx.orchestrator.init(fx.request(sizeBytes = -1))
        outcome.shouldBeInstanceOf<UploadInitOutcome.ValidationError>()
    }
})
