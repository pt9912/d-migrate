package dev.dmigrate.mcp.registry

import dev.dmigrate.server.application.ai.AiToolEnvelope
import dev.dmigrate.server.application.ai.AiToolWorkResult
import dev.dmigrate.server.application.approval.ApprovalAttempt
import dev.dmigrate.server.application.approval.ApprovalGrantService
import dev.dmigrate.server.application.approval.ApprovalGrantValidation
import dev.dmigrate.server.application.approval.ApprovalTokenFingerprint
import dev.dmigrate.server.core.ai.AiToolOutcome
import dev.dmigrate.server.core.approval.ApprovalCorrelationKind
import dev.dmigrate.server.core.error.ToolErrorCode
import dev.dmigrate.server.core.error.ToolErrorDetail
import dev.dmigrate.server.core.policy.PolicyDecision

internal object AiToolApprovalSupport {

    fun requiresApproval(decision: PolicyDecision.RequiresApproval): AiToolWorkResult.FailedRetryable =
        AiToolWorkResult.FailedRetryable(
            ToolErrorCode.POLICY_REQUIRED,
            "approval required",
            details = buildApprovalDetails(
                approvalRequestId = decision.approvalRequestId,
                correlationKind = decision.correlationKind,
                correlationKey = decision.correlationKey,
                requiredScopes = decision.requiredScopes,
                reasons = decision.reasons,
            ),
            approvalRequestId = decision.approvalRequestId,
            correlationKind = decision.correlationKind,
            correlationKey = decision.correlationKey,
            requiredScopes = decision.requiredScopes,
            reasons = decision.reasons,
        )

    fun replayChallenge(challenge: AiToolOutcome.FailedRetryable): AiToolWorkResult.FailedRetryable {
        // LF-017 / LF-024 / LN-030 / LN-031: KI-Approval-Challenges müssen denselben aggregierten
        // Detailvertrag liefern wie Job-/Upload-Pfade (`requiredScopes`,
        // `reasons` als kommagetrennte/pipegetrennte aggregierte Felder, nicht
        // wiederholte Singular-Einträge). Der Replay rebuilded die Details aus
        // den strukturierten Feldern, damit auch durable Challenges aus älteren
        // Codepfaden den LF-017 / LF-024 / LN-030 / LN-031-Vertrag erfüllen.
        val rebuiltDetails = rebuildApprovalDetails(challenge) ?: challenge.details
        return AiToolWorkResult.FailedRetryable(
            toolErrorCode = challenge.toolErrorCode,
            scrubbedMessage = challenge.scrubbedMessage,
            details = rebuiltDetails,
            approvalRequestId = challenge.approvalRequestId,
            correlationKind = challenge.correlationKind,
            correlationKey = challenge.correlationKey,
            requiredScopes = challenge.requiredScopes,
            reasons = challenge.reasons,
        )
    }

    fun validateGrant(
        rawToken: String,
        challenge: AiToolOutcome.FailedRetryable,
        envelope: AiToolEnvelope,
        payloadFingerprint: String,
        approvalGrantService: ApprovalGrantService,
    ): AiToolWorkResult? {
        val validation = approvalGrantService.validate(
            ApprovalAttempt(
                tokenFingerprint = ApprovalTokenFingerprint.compute(rawToken),
                approvalRequestId = challenge.approvalRequestId!!,
                tenantId = envelope.tenantId,
                callerId = envelope.callerId,
                toolName = envelope.toolName,
                correlationKind = challenge.correlationKind ?: ApprovalCorrelationKind.APPROVAL_KEY,
                correlationKey = challenge.correlationKey ?: envelope.approvalKey,
                payloadFingerprint = payloadFingerprint,
                requiredScopes = challenge.requiredScopes,
            ),
            envelope.now,
        )
        return when (validation) {
            is ApprovalGrantValidation.Valid -> null
            is ApprovalGrantValidation.Invalid -> AiToolWorkResult.FailedTerminal(
                ToolErrorCode.POLICY_DENIED,
                "approval grant rejected: ${invalidApprovalReason(validation)}",
            )
        }
    }

    /**
     * Liefert die aggregierten LF-017 / LF-024 / LN-030 / LN-031-Details für ein durable
     * `POLICY_REQUIRED`-Outcome — oder `null`, wenn die Challenge keine
     * vollständigen Approval-Felder trägt (dann erhält der Caller die
     * gespeicherten `details` unverändert).
     */
    private fun rebuildApprovalDetails(
        challenge: AiToolOutcome.FailedRetryable,
    ): List<ToolErrorDetail>? {
        if (challenge.toolErrorCode != ToolErrorCode.POLICY_REQUIRED) return null
        val approvalRequestId = challenge.approvalRequestId ?: return null
        val correlationKind = challenge.correlationKind ?: return null
        val correlationKey = challenge.correlationKey ?: return null
        return buildApprovalDetails(
            approvalRequestId = approvalRequestId,
            correlationKind = correlationKind,
            correlationKey = correlationKey,
            requiredScopes = challenge.requiredScopes,
            reasons = challenge.reasons,
        )
    }

    /**
     * LF-017 / LF-024 / LN-030 / LN-031: Detail-Form ist aggregiert (`requiredScopes` als
     * sortierte, kommagetrennte Liste; `reasons` pipegetrennt) — analog zu
     * [JobStartHandlerSupport.toToolCallOutcome] für Job-Start-Pfade. So
     * sehen Clients dieselbe Approval-Challenge-Form für KI-Tools wie für
     * Job-/Upload-Operationen.
     */
    private fun buildApprovalDetails(
        approvalRequestId: String,
        correlationKind: ApprovalCorrelationKind,
        correlationKey: String,
        requiredScopes: Set<String>,
        reasons: List<String>,
    ): List<ToolErrorDetail> = listOf(
        ToolErrorDetail("approvalRequestId", approvalRequestId),
        ToolErrorDetail("correlationKind", correlationKind.name),
        ToolErrorDetail("correlationKey", correlationKey),
        ToolErrorDetail("requiredScopes", requiredScopes.sorted().joinToString(",")),
        ToolErrorDetail("reasons", reasons.joinToString("|")),
    )

    private fun invalidApprovalReason(validation: ApprovalGrantValidation.Invalid): String =
        when (validation) {
            ApprovalGrantValidation.Invalid.Unknown -> "policy:grant-unknown"
            ApprovalGrantValidation.Invalid.Expired -> "policy:grant-expired"
            ApprovalGrantValidation.Invalid.TenantMismatch -> "policy:tenant-mismatch"
            ApprovalGrantValidation.Invalid.CallerMismatch -> "policy:caller-mismatch"
            ApprovalGrantValidation.Invalid.ToolMismatch -> "policy:tool-mismatch"
            ApprovalGrantValidation.Invalid.ApprovalRequestIdMismatch -> "policy:approval-request-mismatch"
            ApprovalGrantValidation.Invalid.CorrelationMismatch -> "policy:correlation-mismatch"
            ApprovalGrantValidation.Invalid.PayloadMismatch -> "policy:payload-mismatch"
            is ApprovalGrantValidation.Invalid.ScopeMismatch -> "policy:scope-mismatch"
            ApprovalGrantValidation.Invalid.IssuerMismatch -> "policy:issuer-mismatch"
        }
}
