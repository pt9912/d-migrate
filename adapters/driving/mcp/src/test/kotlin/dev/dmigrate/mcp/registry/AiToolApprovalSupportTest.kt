package dev.dmigrate.mcp.registry

import dev.dmigrate.server.application.ai.AiToolEnvelope
import dev.dmigrate.server.application.ai.AiToolWorkResult
import dev.dmigrate.server.application.approval.ApprovalAttempt
import dev.dmigrate.server.application.approval.ApprovalGrantService
import dev.dmigrate.server.application.approval.ApprovalGrantValidation
import dev.dmigrate.server.core.ai.AiToolOutcome
import dev.dmigrate.server.core.ai.AiToolScope
import dev.dmigrate.server.core.approval.ApprovalGrant
import dev.dmigrate.server.core.approval.ApprovalCorrelationKind
import dev.dmigrate.server.core.error.ToolErrorCode
import dev.dmigrate.server.core.error.ToolErrorDetail
import dev.dmigrate.server.core.policy.PolicyDecision
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Instant

class AiToolApprovalSupportTest : FunSpec({

    val tenant = TenantId("acme")
    val caller = PrincipalId("alice")
    val now = Instant.parse("2026-05-07T12:00:00Z")
    val fingerprint = "a".repeat(64)
    val scope = AiToolScope(tenant, caller, "procedure_transform_plan", "approval-key-1")

    fun envelope() = AiToolEnvelope(
        toolName = scope.toolName,
        tenantId = tenant,
        callerId = caller,
        approvalKey = scope.approvalKey,
        payloadFingerprint = fingerprint,
        now = now,
    )

    fun challenge(
        approvalRequestId: String? = "apr-1",
        correlationKind: ApprovalCorrelationKind? = ApprovalCorrelationKind.APPROVAL_KEY,
        correlationKey: String? = "approval-key-1",
    ) = AiToolOutcome.FailedRetryable(
        scope = scope,
        payloadFingerprint = fingerprint,
        toolErrorCode = ToolErrorCode.POLICY_REQUIRED,
        scrubbedMessage = "approval required",
        attemptCount = 1,
        lastAttemptAt = now,
        approvalRequestId = approvalRequestId,
        correlationKind = correlationKind,
        correlationKey = correlationKey,
        requiredScopes = setOf("ai:plan"),
    )

    test("RequiresApproval wird als retrybare Challenge mit aggregierten Details modelliert") {
        val result = AiToolApprovalSupport.requiresApproval(
            PolicyDecision.RequiresApproval(
                approvalRequestId = "apr-1",
                correlationKind = ApprovalCorrelationKind.APPROVAL_KEY,
                correlationKey = "approval-key-1",
                requiredScopes = setOf("artifact:read", "ai:plan"),
                reasons = listOf("policy:manual-review", "policy:second-reason"),
            ),
        )

        result.toolErrorCode shouldBe ToolErrorCode.POLICY_REQUIRED
        result.scrubbedMessage shouldBe "approval required"
        result.approvalRequestId shouldBe "apr-1"
        result.correlationKind shouldBe ApprovalCorrelationKind.APPROVAL_KEY
        result.correlationKey shouldBe "approval-key-1"
        result.requiredScopes shouldBe setOf("ai:plan", "artifact:read")
        result.reasons shouldBe listOf("policy:manual-review", "policy:second-reason")
        // Follow-up AP 1: aggregierte Felder, analog zu JobStartHandlerSupport
        // (`requiredScopes` sortiert + komma-, `reasons` pipe-getrennt).
        result.details shouldContain ToolErrorDetail("approvalRequestId", "apr-1")
        result.details shouldContain ToolErrorDetail(
            "correlationKind",
            ApprovalCorrelationKind.APPROVAL_KEY.name,
        )
        result.details shouldContain ToolErrorDetail("correlationKey", "approval-key-1")
        result.details shouldContain ToolErrorDetail("requiredScopes", "ai:plan,artifact:read")
        result.details shouldContain ToolErrorDetail(
            "reasons",
            "policy:manual-review|policy:second-reason",
        )
        // Follow-up AP 1: keine wiederholten Singular-Schlüssel mehr.
        val keys = result.details.map { it.key }
        keys shouldNotContain "requiredScope"
        keys shouldNotContain "reason"
    }

    test("Replay rebuildet Approval-Details aus strukturierten Feldern (AP 1)") {
        // Follow-up AP 1: stored Challenge-Details aus älterem Codepfad
        // (Singular-Form) dürfen nicht durchschlagen — der Replay erzeugt die
        // aggregierte AP-1-Form aus den strukturierten Feldern, damit der
        // Wire-Vertrag konsistent bleibt.
        val challenge = AiToolOutcome.FailedRetryable(
            scope = scope,
            payloadFingerprint = fingerprint,
            toolErrorCode = ToolErrorCode.POLICY_REQUIRED,
            scrubbedMessage = "approval required",
            details = listOf(
                ToolErrorDetail("requiredScope", "ai:plan"),
                ToolErrorDetail("reason", "policy:manual-review"),
            ),
            attemptCount = 1,
            lastAttemptAt = now,
            approvalRequestId = "apr-1",
            correlationKind = ApprovalCorrelationKind.APPROVAL_KEY,
            correlationKey = "approval-key-1",
            requiredScopes = setOf("ai:plan"),
            reasons = listOf("policy:manual-review"),
        )

        val replay = AiToolApprovalSupport.replayChallenge(challenge)

        replay.toolErrorCode shouldBe challenge.toolErrorCode
        replay.scrubbedMessage shouldBe challenge.scrubbedMessage
        replay.approvalRequestId shouldBe challenge.approvalRequestId
        replay.requiredScopes shouldBe challenge.requiredScopes
        replay.reasons shouldBe challenge.reasons
        // Aggregierte Form, nicht die ursprünglichen Singular-Einträge.
        replay.details shouldContain ToolErrorDetail("requiredScopes", "ai:plan")
        replay.details shouldContain ToolErrorDetail("reasons", "policy:manual-review")
        val replayKeys = replay.details.map { it.key }
        replayKeys shouldNotContain "requiredScope"
        replayKeys shouldNotContain "reason"
    }

    test("Replay laesst Details unveraendert, wenn Challenge keine Approval-Felder traegt") {
        // Wenn die durable Failed-Retryable kein Approval-Challenge ist
        // (z. B. OPERATION_TIMEOUT-Reclaim), bleiben die ursprünglichen
        // Details bestehen — wir rebuilden nur Approval-Form.
        val challenge = AiToolOutcome.FailedRetryable(
            scope = scope,
            payloadFingerprint = fingerprint,
            toolErrorCode = ToolErrorCode.OPERATION_TIMEOUT,
            scrubbedMessage = "claim lease expired without commit",
            details = listOf(ToolErrorDetail("info", "lease-expired")),
            attemptCount = 1,
            lastAttemptAt = now,
        )

        val replay = AiToolApprovalSupport.replayChallenge(challenge)
        replay.details shouldBe challenge.details
    }

    test("Ungueltiger Approval-Grant wird in terminale POLICY_DENIED-Antwort uebersetzt") {
        val service = object : ApprovalGrantService {
            override fun validate(
                attempt: ApprovalAttempt,
                now: Instant,
            ): ApprovalGrantValidation = ApprovalGrantValidation.Invalid.PayloadMismatch
        }

        val result = AiToolApprovalSupport.validateGrant(
            rawToken = "grant-token",
            challenge = challenge(),
            envelope = envelope(),
            payloadFingerprint = fingerprint,
            approvalGrantService = service,
        )

        val terminal = result.shouldBeInstanceOf<AiToolWorkResult.FailedTerminal>()
        terminal.toolErrorCode shouldBe ToolErrorCode.POLICY_DENIED
        terminal.scrubbedMessage shouldBe "approval grant rejected: policy:payload-mismatch"
    }

    test("Approval-Validierung mappt alle Invalid-Gruende stabil auf Policy-Codes") {
        val cases = listOf(
            ApprovalGrantValidation.Invalid.Unknown to "policy:grant-unknown",
            ApprovalGrantValidation.Invalid.Expired to "policy:grant-expired",
            ApprovalGrantValidation.Invalid.TenantMismatch to "policy:tenant-mismatch",
            ApprovalGrantValidation.Invalid.CallerMismatch to "policy:caller-mismatch",
            ApprovalGrantValidation.Invalid.ToolMismatch to "policy:tool-mismatch",
            ApprovalGrantValidation.Invalid.ApprovalRequestIdMismatch to "policy:approval-request-mismatch",
            ApprovalGrantValidation.Invalid.CorrelationMismatch to "policy:correlation-mismatch",
            ApprovalGrantValidation.Invalid.PayloadMismatch to "policy:payload-mismatch",
            ApprovalGrantValidation.Invalid.ScopeMismatch(setOf("ai:execute")) to "policy:scope-mismatch",
            ApprovalGrantValidation.Invalid.IssuerMismatch to "policy:issuer-mismatch",
        )

        cases.forEach { (invalid, code) ->
            val service = object : ApprovalGrantService {
                override fun validate(
                    attempt: ApprovalAttempt,
                    now: Instant,
                ): ApprovalGrantValidation = invalid
            }

            val terminal = AiToolApprovalSupport.validateGrant(
                rawToken = "grant-token",
                challenge = challenge(),
                envelope = envelope(),
                payloadFingerprint = fingerprint,
                approvalGrantService = service,
            ).shouldBeInstanceOf<AiToolWorkResult.FailedTerminal>()

            terminal.scrubbedMessage shouldBe "approval grant rejected: $code"
        }
    }

    test("Approval-Validierung nutzt ApprovalKey-Fallback fuer aeltere retrybare Challenges") {
        var captured: ApprovalAttempt? = null
        val service = object : ApprovalGrantService {
            override fun validate(
                attempt: ApprovalAttempt,
                now: Instant,
            ): ApprovalGrantValidation {
                captured = attempt
                return ApprovalGrantValidation.Invalid.Unknown
            }
        }

        AiToolApprovalSupport.validateGrant(
            rawToken = "grant-token",
            challenge = challenge(correlationKind = null, correlationKey = null),
            envelope = envelope(),
            payloadFingerprint = fingerprint,
            approvalGrantService = service,
        )

        captured?.correlationKind shouldBe ApprovalCorrelationKind.APPROVAL_KEY
        captured?.correlationKey shouldBe "approval-key-1"
    }

    test("Gueltiger Approval-Grant laesst den Provider-Pfad weiterlaufen") {
        val service = object : ApprovalGrantService {
            override fun validate(
                attempt: ApprovalAttempt,
                now: Instant,
            ): ApprovalGrantValidation =
                ApprovalGrantValidation.Valid(
                    ApprovalGrant(
                        approvalRequestId = "apr-1",
                        correlationKind = ApprovalCorrelationKind.APPROVAL_KEY,
                        correlationKey = "approval-key-1",
                        approvalTokenFingerprint = "unused",
                        toolName = scope.toolName,
                        tenantId = tenant,
                        callerId = caller,
                        payloadFingerprint = fingerprint,
                        issuerFingerprint = "issuer-fp",
                        issuedScopes = setOf("ai:plan"),
                        grantSource = "test",
                        expiresAt = now.plusSeconds(60),
                    ),
                )
        }

        AiToolApprovalSupport.validateGrant(
            rawToken = "grant-token",
            challenge = challenge(correlationKind = null),
            envelope = envelope(),
            payloadFingerprint = fingerprint,
            approvalGrantService = service,
        ) shouldBe null
    }
})
