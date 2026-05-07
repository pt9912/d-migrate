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
            details = approvalDetails(decision),
            approvalRequestId = decision.approvalRequestId,
            correlationKind = decision.correlationKind,
            correlationKey = decision.correlationKey,
            requiredScopes = decision.requiredScopes,
            reasons = decision.reasons,
        )

    fun replayChallenge(challenge: AiToolOutcome.FailedRetryable): AiToolWorkResult.FailedRetryable =
        AiToolWorkResult.FailedRetryable(
            toolErrorCode = challenge.toolErrorCode,
            scrubbedMessage = challenge.scrubbedMessage,
            details = challenge.details,
            approvalRequestId = challenge.approvalRequestId,
            correlationKind = challenge.correlationKind,
            correlationKey = challenge.correlationKey,
            requiredScopes = challenge.requiredScopes,
            reasons = challenge.reasons,
        )

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

    private fun approvalDetails(decision: PolicyDecision.RequiresApproval): List<ToolErrorDetail> = buildList {
        add(ToolErrorDetail("approvalRequestId", decision.approvalRequestId))
        add(ToolErrorDetail("correlationKind", decision.correlationKind.name))
        add(ToolErrorDetail("correlationKey", decision.correlationKey))
        decision.requiredScopes.sorted().forEach { add(ToolErrorDetail("requiredScope", it)) }
        decision.reasons.forEach { add(ToolErrorDetail("reason", it)) }
    }

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
